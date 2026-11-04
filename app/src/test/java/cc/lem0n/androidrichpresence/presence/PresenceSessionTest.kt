package cc.lem0n.androidrichpresence.presence

import org.junit.Assert.*
import org.junit.Test

class PresenceSessionTest {
    private class Vault : TokenVault {
        var stored: TokenLoad = TokenLoad.Empty
        var writable = true
        var markerWritable = true
        var marked = false
        var commits = 0
        override fun load() = stored
        override fun replace(tokens: SecretTokenPair): Boolean {
            if (!writable) return false
            commits++
            stored = TokenLoad.Stored(SecretTokenPair(tokens.access.copyOf(), tokens.refresh.copyOf(), tokens.expiresAt))
            marked = false
            return true
        }
        override fun markRefreshing(): Boolean {
            if (!markerWritable) return false
            marked = true
            stored = TokenLoad.RequiresLink
            return true
        }
        override fun erase(): Boolean { stored = TokenLoad.Empty; return true }
    }
    private class Sdk : SdkPort {
        override var available = true
        override var isOpen = false
        var generation = 0L
        var ready = false
        var opens = 0
        var closes = 0
        var installs = 0
        var connects = 0
        var refreshes = 0
        var throwOnClear = false
        val effects = mutableListOf<String>()
        val updates = mutableListOf<Pair<Long, Presence>>()
        override fun open(generation: Long) { check(!isOpen); isOpen = true; this.generation = generation; opens++ }
        override fun authorize() { effects += "authorize" }
        override fun install(access: ByteArray) { installs++; effects += "install" }
        override fun refresh(refresh: ByteArray) { refreshes++; effects += "refresh" }
        override fun connect() { connects++ }
        override fun update(operation: Long, presence: Presence) {
            check(ready)
            updates += operation to presence
            effects += "show:${presence.video.title}"
        }
        override fun clear(): Boolean {
            if (!ready) return false
            if (throwOnClear) throw IllegalStateException("Clear outcome unknown")
            effects += "clear"
            return true
        }
        override fun close() { if (isOpen) closes++; isOpen = false; ready = false }
        override fun poll() = emptyList<SdkEvent>()
    }
    private class Fixture(val sdk: Sdk = Sdk(), val vault: Vault = Vault()) {
        var time = 1_000_000L
        var elapsedTime = 1_000_000L
        var screen = ScreenState()
        val session = PresenceSession(sdk, vault, elapsedNow = { elapsedTime }, epochNow = { time },
            publish = { screen = it })
        fun emit(event: SdkEvent) {
            if (event is SdkEvent.Status) sdk.ready = event.ready
            session.event(event)
        }
        fun tokens() = emit(SdkEvent.Tokens(sdk.generation, "fake-access".toByteArray(), "fake-refresh".toByteArray(), 3600))
        fun ready() {
            emit(SdkEvent.Installed(sdk.generation, true))
            emit(SdkEvent.Status(sdk.generation, true))
        }
        fun link() { session.foregroundReady(); session.link(); tokens(); ready() }
        fun playing(title: String = "A") {
            session.settings(Settings(true))
            session.source(Playback.Playing(Video(title)), true)
        }
        fun complete(success: Boolean = true) {
            emit(SdkEvent.Completed(sdk.generation, sdk.updates.last().first, success))
        }
        fun advance(ms: Long) { time += ms; elapsedTime += ms; session.tick() }
    }
    @Test fun `source is visible without linking and nothing is sent`() {
        val f = Fixture(); f.playing()
        assertEquals(Playback.Playing(Video("A")), f.screen.playback)
        assertEquals(0, f.sdk.opens); assertTrue(f.sdk.updates.isEmpty())
    }
    @Test fun `tokens must be durable then installed and ready before sending`() {
        val f = Fixture(); f.playing(); f.session.foregroundReady(); f.session.link()
        f.tokens()
        assertEquals(1, f.vault.commits); assertEquals(1, f.sdk.installs)
        assertEquals(0, f.sdk.connects); assertTrue(f.sdk.updates.isEmpty())
        f.emit(SdkEvent.Installed(f.sdk.generation, true))
        assertEquals(1, f.sdk.connects); assertTrue(f.sdk.updates.isEmpty())
        f.emit(SdkEvent.Status(f.sdk.generation, true))
        assertEquals("A", f.sdk.updates.single().second.video.title)
    }
    @Test fun `failed durable commit never installs or connects`() {
        val f = Fixture(); f.vault.writable = false; f.session.foregroundReady(); f.session.link(); f.tokens()
        assertEquals(0, f.sdk.installs); assertEquals(0, f.sdk.connects)
        assertFalse(f.sdk.isOpen); assertTrue(f.screen.auth is Auth.Failed)
    }
    @Test fun `transient import failure preserves credentials and retries before connecting`() {
        val f = Fixture(); f.playing(); f.session.foregroundReady(); f.session.link(); f.tokens()
        f.emit(SdkEvent.Installed(f.sdk.generation, false))
        assertEquals(0, f.sdk.connects)
        assertFalse(f.sdk.isOpen)
        assertTrue(f.vault.stored is TokenLoad.Stored)
        assertEquals(Auth.Connecting, f.screen.auth)
        f.advance(999)
        assertEquals(1, f.sdk.installs)
        f.advance(1)
        assertEquals(2, f.sdk.installs)
        assertEquals(0, f.sdk.connects)
        f.ready(); f.complete()
        assertEquals(1, f.sdk.connects)
        assertEquals(Auth.Ready, f.screen.auth)
        assertEquals(Delivery.Accepted(Presence(Video("A"), true)), f.screen.delivery)
        assertEquals(1, f.vault.commits)
        assertEquals(1, f.sdk.effects.count { it == "authorize" })
    }
    @Test fun `latest media wins during connect and an inflight update`() {
        val f = Fixture(); f.playing("A"); f.session.foregroundReady(); f.session.link()
        f.playing("B"); f.tokens(); f.ready()
        assertEquals(listOf("B"), f.sdk.updates.map { it.second.video.title })
        f.playing("C"); f.playing("D")
        assertEquals(1, f.sdk.updates.size)
        f.complete()
        assertEquals(listOf("B", "D"), f.sdk.updates.map { it.second.video.title })
        f.complete(); assertEquals(Delivery.Accepted(Presence(Video("D"), true)), f.screen.delivery)
    }
    @Test fun `stop then restart preserves the clear barrier after inflight work`() {
        val f = Fixture(); f.playing(); f.link()
        f.session.source(Playback.NoSession, true); f.playing("B"); f.complete()
        assertEquals(listOf("show:A", "clear", "show:B"), f.sdk.effects.filter { it == "clear" || it.startsWith("show:") })
    }
    @Test fun `sharing off then on clears before sending the same title again`() {
        val f = Fixture(); f.playing(); f.link()
        f.session.settings(Settings(false)); f.session.settings(Settings(true)); f.complete()
        assertEquals(listOf("show:A", "clear", "show:A"), f.sdk.effects.filter { it == "clear" || it.startsWith("show:") })
    }
    @Test fun `missing source and buffering clear while pause stays paused`() {
        val f = Fixture(); f.playing(); f.link(); f.complete()
        f.session.source(Playback.Paused(Video("A")), true); f.complete(); f.advance(600_000)
        assertEquals(Delivery.Accepted(Presence(Video("A"), false)), f.screen.delivery)
        f.session.source(Playback.Buffering(Video("A")), true)
        assertEquals("clear", f.sdk.effects.last())
        f.playing("B"); f.complete(); f.session.source(Playback.NoSession, false)
        assertEquals("clear", f.sdk.effects.last()); assertFalse(f.screen.connected)
    }
    @Test fun `timeout destroys the uncertain generation then publishes only newest media`() {
        val f = Fixture(); f.playing(); f.link(); val oldGeneration = f.sdk.generation
        val oldOperation = f.sdk.updates.single().first
        f.playing("B"); f.advance(15_001)
        assertFalse(f.sdk.isOpen); assertEquals(1, f.sdk.closes)
        f.emit(SdkEvent.Completed(oldGeneration, oldOperation, true))
        f.advance(1000); f.ready()
        assertEquals("B", f.sdk.updates.last().second.video.title)
        assertTrue(f.sdk.generation != oldGeneration)
    }
    @Test fun `native reconnect status invalidates readiness and pending deadline still expires`() {
        val f = Fixture(); f.playing(); f.link()
        f.emit(SdkEvent.Status(f.sdk.generation, false)); f.playing("B")
        assertEquals(1, f.sdk.updates.size)
        f.emit(SdkEvent.Status(f.sdk.generation, true)); f.advance(15_001)
        assertFalse(f.sdk.isOpen)
    }
    @Test fun `prolonged outage keeps retrying with capped backoff and recovers without user action`() {
        val f = Fixture(); f.playing(); f.link()
        val retryDelays = listOf(1000L, 2000L, 4000L, 8000L, 16_000L, 32_000L) + List(14) { 60_000L }
        retryDelays.forEachIndexed { index, delay ->
            f.complete(false)
            repeat(20) { f.playing("changing-$it") }
            val opens = f.sdk.opens
            f.advance(delay - 1)
            assertEquals("Retry ${index + 1} must wait for its backoff", opens, f.sdk.opens)
            f.advance(1)
            assertEquals("Retry ${index + 1} must resume automatically", opens + 1, f.sdk.opens)
            f.ready()
        }
        f.complete()
        assertEquals(Auth.Ready, f.screen.auth)
        assertEquals(Delivery.Accepted(Presence(Video("changing-19"), true)), f.screen.delivery)
        assertEquals(1, f.sdk.effects.count { it == "authorize" })
    }
    @Test fun `native reconnection resends previously accepted current media`() {
        val f = Fixture(); f.playing(); f.link(); f.complete()
        f.emit(SdkEvent.Status(f.sdk.generation, false))
        f.emit(SdkEvent.Status(f.sdk.generation, true))
        assertEquals(listOf("A", "A"), f.sdk.updates.map { it.second.video.title })
        f.complete()
        assertEquals(Delivery.Accepted(Presence(Video("A"), true)), f.screen.delivery)
    }
    @Test fun `completion from before native disconnect resends current media after reconnect`() {
        listOf("A", "B").forEach { title ->
            val f = Fixture(); f.playing(); f.link()
            f.emit(SdkEvent.Status(f.sdk.generation, false))
            f.playing(title)
            f.emit(SdkEvent.Status(f.sdk.generation, true))
            assertEquals(1, f.sdk.updates.size)
            f.complete()
            assertEquals(listOf("A", title), f.sdk.updates.map { it.second.video.title })
            assertEquals(Delivery.Updating, f.screen.delivery)
            f.complete()
            assertEquals(Delivery.Accepted(Presence(Video(title), true)), f.screen.delivery)
        }
    }
    @Test fun `refresh is marked durable before request and replacement before import`() {
        val f = Fixture(); f.link(); val oldGeneration = f.sdk.generation
        f.emit(SdkEvent.Expired(oldGeneration))
        assertTrue(f.vault.marked); assertEquals(1, f.sdk.refreshes)
        assertEquals(1, f.sdk.installs)
        f.tokens()
        assertEquals(2, f.vault.commits); assertEquals(2, f.sdk.installs); assertFalse(f.vault.marked)
    }
    @Test fun `refresh marker failure never sends refresh and cannot silently reconnect`() {
        val f = Fixture(); f.link(); f.vault.markerWritable = false
        f.emit(SdkEvent.Expired(f.sdk.generation)); f.advance(100_000)
        assertEquals(0, f.sdk.refreshes); assertFalse(f.sdk.isOpen); assertTrue(f.screen.auth is Auth.Failed)
    }
    @Test fun `refresh write failure discards rotated credentials`() {
        val f = Fixture(); f.link(); f.emit(SdkEvent.Expired(f.sdk.generation)); f.vault.writable = false; f.tokens()
        assertFalse(f.sdk.isOpen); assertEquals(1, f.sdk.installs); assertTrue(f.screen.auth is Auth.Failed)
    }
    @Test fun `ambiguous refresh timeout cannot replay the consumed credentials`() {
        val f = Fixture(); f.link(); f.emit(SdkEvent.Expired(f.sdk.generation))
        f.advance(20_001)
        assertTrue(f.screen.auth is Auth.Failed)
        assertEquals(TokenLoad.Empty, f.vault.stored)
        f.session.retry(); f.session.networkChanged(false); f.session.networkChanged(true)
        f.advance(120_000)
        assertEquals(1, f.sdk.refreshes)
        assertFalse(f.sdk.isOpen)
    }
    @Test fun `saved link restores at settings initialization without foreground or consent`() {
        val vault = Vault().apply { stored = TokenLoad.Stored(SecretTokenPair(byteArrayOf(1), byteArrayOf(2), 9_000_000)) }
        val f = Fixture(vault = vault)
        f.session.settings(Settings(true))
        assertEquals(1, f.sdk.installs)
        f.session.source(Playback.Playing(Video("A")), true)
        f.ready()
        assertEquals(Auth.Ready, f.screen.auth); assertFalse(f.sdk.effects.contains("authorize"))
        assertEquals("A", f.sdk.updates.single().second.video.title)
    }
    @Test fun `expired saved access refreshes in background at settings initialization`() {
        val vault = Vault().apply { stored = TokenLoad.Stored(SecretTokenPair(byteArrayOf(1), byteArrayOf(2), 999_999)) }
        val f = Fixture(vault = vault)
        f.session.settings(Settings(true))
        assertEquals(1, f.sdk.refreshes)
        assertTrue(f.vault.marked)
        assertEquals(0, f.sdk.installs)
        assertFalse(f.sdk.effects.contains("authorize"))
        f.tokens(); f.ready()
        assertEquals(Auth.Ready, f.screen.auth)
        assertEquals(1, f.vault.commits)
        assertFalse(f.vault.marked)
    }
    @Test fun `offline expired saved credentials wait for network before background refresh`() {
        val vault = Vault().apply { stored = TokenLoad.Stored(SecretTokenPair(byteArrayOf(1), byteArrayOf(2), 999_999)) }
        val f = Fixture(vault = vault)
        f.session.networkChanged(false)
        f.session.settings(Settings(true))
        f.session.source(Playback.Playing(Video("A")), true)
        f.advance(3_600_000)
        assertEquals(0, f.sdk.opens)
        assertEquals(0, f.sdk.refreshes)
        assertFalse(f.vault.marked)
        assertTrue(f.vault.stored is TokenLoad.Stored)
        f.session.networkChanged(true)
        assertEquals(1, f.sdk.refreshes)
        assertTrue(f.vault.marked)
        assertEquals(0, f.sdk.installs)
        f.tokens()
        assertEquals(1, f.vault.commits)
        assertFalse(f.vault.marked)
        assertEquals(1, f.sdk.installs)
        f.ready(); f.complete()
        assertEquals(Auth.Ready, f.screen.auth)
        assertEquals(Delivery.Accepted(Presence(Video("A"), true)), f.screen.delivery)
        assertFalse(f.sdk.effects.contains("authorize"))
    }
    @Test fun `unlink during offline backoff cancels recovery when network returns`() {
        val f = Fixture(); f.playing(); f.link(); f.complete(false)
        f.session.networkChanged(false)
        f.advance(500)
        f.session.unlink()
        val opens = f.sdk.opens
        f.advance(3_600_000)
        f.session.networkChanged(true)
        f.advance(3_600_000)
        assertEquals(opens, f.sdk.opens)
        assertEquals(1, f.sdk.installs)
        assertEquals(TokenLoad.Empty, f.vault.stored)
        assertEquals(Auth.Unlinked, f.screen.auth)
        assertFalse(f.sdk.isOpen)
    }
    @Test fun `network loss during refresh never replays the rotating refresh token`() {
        val f = Fixture(); f.link()
        f.emit(SdkEvent.Expired(f.sdk.generation))
        val refreshGeneration = f.sdk.generation
        val opens = f.sdk.opens
        f.session.networkChanged(false)
        f.advance(1000)
        f.session.networkChanged(true)
        assertEquals(1, f.sdk.refreshes)
        assertEquals(opens, f.sdk.opens)
        assertEquals(refreshGeneration, f.sdk.generation)
        assertTrue(f.vault.marked)
        f.tokens(); f.ready()
        assertEquals(Auth.Ready, f.screen.auth)
        assertEquals(2, f.vault.commits)
        assertFalse(f.vault.marked)
        assertEquals(1, f.sdk.refreshes)
    }
    @Test fun `repeated network availability callbacks open only one recovery client`() {
        val f = Fixture(); f.playing(); f.link(); f.complete(false)
        f.session.networkChanged(false)
        f.advance(60_000)
        val opens = f.sdk.opens
        assertFalse(f.sdk.isOpen)
        repeat(10) { f.session.networkChanged(true) }
        assertEquals(opens + 1, f.sdk.opens)
        assertEquals(2, f.sdk.installs)
        assertEquals(1, f.sdk.connects)
        f.ready(); f.complete()
        repeat(10) { f.session.networkChanged(true) }
        assertEquals(opens + 1, f.sdk.opens)
        assertEquals(Auth.Ready, f.screen.auth)
        assertEquals(Delivery.Accepted(Presence(Video("A"), true)), f.screen.delivery)
    }
    @Test fun `disabled sharing stays disabled when saved link restores in background`() {
        val vault = Vault().apply { stored = TokenLoad.Stored(SecretTokenPair(byteArrayOf(1), byteArrayOf(2), 9_000_000)) }
        val f = Fixture(vault = vault)
        f.session.settings(Settings(false))
        assertEquals(1, f.sdk.installs)
        f.session.source(Playback.Playing(Video("A")), true)
        f.ready()
        f.emit(SdkEvent.Status(f.sdk.generation, false))
        f.emit(SdkEvent.Status(f.sdk.generation, true))
        assertEquals(Auth.Ready, f.screen.auth)
        assertTrue(f.sdk.updates.isEmpty())
        assertFalse(f.sdk.effects.contains("authorize"))
    }
    @Test fun `restart during ambiguous refresh requires link`() {
        val f = Fixture(vault = Vault().apply { stored = TokenLoad.RequiresLink })
        f.session.foregroundReady(); f.session.retry(); f.advance(100_000)
        assertEquals(0, f.sdk.opens); assertTrue(f.screen.auth is Auth.Failed)
    }
    @Test fun `unlink ignores late credential and ready callbacks and cancels retry`() {
        val f = Fixture(); f.session.foregroundReady(); f.session.link(); val generation = f.sdk.generation
        f.session.unlink()
        val event = SdkEvent.Tokens(generation, byteArrayOf(11), byteArrayOf(12), 3600)
        f.emit(event); f.emit(SdkEvent.Status(generation, true)); f.advance(100_000)
        assertArrayEquals(byteArrayOf(0), event.access)
        assertEquals(TokenLoad.Empty, f.vault.stored); assertEquals(0, f.sdk.installs); assertFalse(f.sdk.isOpen)
        assertEquals(Auth.Unlinked, f.screen.auth)
    }
    @Test fun `sharing disabled blocks any resend through reconnect`() {
        val f = Fixture(); f.playing(); f.link(); f.session.settings(Settings(false))
        f.advance(15_001); f.advance(1000); f.ready()
        assertEquals(1, f.sdk.updates.size); assertEquals("clear", f.sdk.effects.last())
    }
    @Test fun `wall clock rollback does not postpone update timeout`() {
        val f = Fixture(); f.playing(); f.link()
        f.time -= 86_400_000
        f.advance(15_001)
        assertFalse("Timed-out SDK client must close despite wall clock rollback", f.sdk.isOpen)
    }
    @Test fun `saved token expiry uses epoch time independently of elapsed time`() {
        val f = Fixture(); f.elapsedTime = 500; f.link()
        assertEquals(f.time + 3_600_000, (f.vault.stored as TokenLoad.Stored).tokens.expiresAt)
    }
    @Test fun `wall clock rollback does not postpone unlink close`() {
        val f = Fixture(); f.link(); f.session.unlink()
        f.time -= 86_400_000
        f.advance(701)
        assertFalse("Unlinked SDK client must close despite wall clock rollback", f.sdk.isOpen)
    }
    @Test fun `clear exception cannot prevent local credential deletion`() {
        val f = Fixture(); f.link(); f.sdk.throwOnClear = true
        runCatching { f.session.unlink() }.onFailure { f.session.failure() }
        assertEquals(TokenLoad.Empty, f.vault.stored)
        assertEquals(Auth.Unlinked, f.screen.auth)
        f.advance(701)
        assertFalse(f.sdk.isOpen)
    }
    @Test fun `monitor only never opens a client or loads credentials`() {
        val f = Fixture(sdk = Sdk().apply { available = false })
        f.playing(); f.session.foregroundReady(); f.session.link(); f.advance(1_000_000)
        assertEquals(Auth.MonitorOnly, f.screen.auth); assertEquals(0, f.sdk.opens)
    }
    @Test fun `secret representations never reveal their contents`() {
        val secret = SecretTokenPair("fake-secret".toByteArray(), "fake-refresh".toByteArray(), 1)
        assertFalse(secret.toString().contains("fake-secret"))
    }
    @Test fun `presence title fits Discord UTF-8 limit for the rejected Japanese video`() {
        val title = "【雑談/chat】初見さんも大歓迎！マシンガントークでしゃべりまくります【茶々プラムム/vtuber】"
        assertEquals(
            "【雑談/chat】初見さんも大歓迎！マシンガントークでしゃべりまくります【茶々プラムム/vtuber",
            presenceTitle(title),
        )
        val f = Fixture(); f.playing(title)
        assertEquals(Playback.Playing(Video(title)), f.screen.playback)
    }
    @Test fun `presence title preserves complete Japanese characters and emoji at byte limit`() {
        assertEquals("猫".repeat(42), presenceTitle("猫".repeat(43)))
        assertEquals("😀".repeat(32), presenceTitle("😀".repeat(129)))
        assertEquals("A".repeat(124) + "😀", presenceTitle("A".repeat(124) + "😀B"))
        assertEquals("A".repeat(125), presenceTitle("A".repeat(125) + "😀B"))
        assertEquals("A".repeat(125) + "猫", presenceTitle("A".repeat(125) + "猫B"))
        assertEquals("A".repeat(126), presenceTitle("A".repeat(126) + "猫B"))
        assertEquals("A".repeat(126) + "é", presenceTitle("A".repeat(126) + "éB"))
        assertEquals("A".repeat(127), presenceTitle("A".repeat(127) + "éB"))
    }
    @Test fun `presence title keeps ASCII titles through 128 bytes and trims whitespace`() {
        assertEquals("AB", presenceTitle(" AB "))
        assertEquals("A".repeat(128), presenceTitle("A".repeat(128)))
        assertEquals("A".repeat(128), presenceTitle("A".repeat(129)))
    }
    @Test fun `presence title retains single character prefix`() {
        assertEquals("動画: A", presenceTitle(" A "))
        assertEquals("動画: 猫", presenceTitle("猫"))
        assertEquals("動画: 😀", presenceTitle("😀"))
    }
}
