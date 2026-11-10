package cc.lem0n.androidrichpresence.presence

internal class PresenceSession(
    private val sdk: SdkPort,
    private val vault: TokenVault,
    private val elapsedNow: () -> Long,
    private val epochNow: () -> Long,
    private val publish: (ScreenState) -> Unit,
) {
    private var state = ScreenState()
    private var settings = Settings()
    private var tokens: SecretTokenPair? = null
    private var wanted = false
    private var foreground = false
    private var networkAvailable = true
    private var refreshRequired = false
    private var generation = 0L
    private var nextOperation = 0L
    private data class Pending(val operation: Long, val presence: Presence, val connection: Long)
    private var connection = 0L
    private var pending: Pending? = null
    private var accepted: Presence? = null
    private var clearBarrier = false
    private var cleared = false
    private var ready = false
    private var deadline = 0L
    private var pendingDeadline = 0L
    private var retryAt = 0L
    private var closeAt = 0L
    private var retries = 0
    val needsTick: Boolean get() = sdk.isOpen || retryAt != 0L || closeAt != 0L

    init {
        if (!sdk.available) state = state.copy(auth = Auth.MonitorOnly)
        else when (val saved = vault.load()) {
            TokenLoad.Empty -> Unit
            TokenLoad.RequiresLink -> state = state.copy(auth = Auth.Failed(AuthIssue.LinkUnreadable))
            is TokenLoad.Stored -> {
                tokens = saved.tokens
                wanted = true
                state = state.copy(auth = Auth.Connecting)
            }
        }
        render()
    }

    fun foregroundReady() {
        foreground = true
        if (wanted && !sdk.isOpen && retryAt == 0L && state.auth !is Auth.Failed) restore()
        render()
    }

    fun settings(value: Settings) {
        val previous = desired()
        settings = value
        if (previous != null && desired() == null) clearBarrier = true
        if (wanted && !sdk.isOpen && retryAt == 0L && closeAt == 0L) restore()
        reconcile()
    }

    fun networkChanged(available: Boolean) {
        if (networkAvailable == available) return
        networkAvailable = available
        if (available && wanted && closeAt == 0L) {
            if (!sdk.isOpen || state.auth == Auth.Connecting) {
                retryAt = 0
                destroyClient()
                restore()
            } else if (ready && (refreshRequired || tokens?.let { epochNow() >= it.expiresAt - 60_000 } == true)) {
                beginRefresh()
            }
        }
        render()
    }

    fun source(playback: Playback, connected: Boolean) {
        val previous = desired()
        state = state.copy(playback = playback, connected = connected)
        if (previous != null && desired() == null) clearBarrier = true
        reconcile()
    }

    fun link() {
        if (!sdk.available || closeAt != 0L || state.auth in listOf(Auth.Authorizing, Auth.Exchanging,
                Auth.Installing, Auth.Connecting, Auth.Refreshing, Auth.Ready)) return
        if (!foreground) { state = state.copy(auth = Auth.NeedsForeground); render(); return }
        wanted = true
        retries = 0
        retryAt = 0
        destroyClient()
        tokens?.destroy()
        tokens = null
        refreshRequired = false
        if (!vault.erase()) { requireLink(AuthIssue.StorageUnavailable); return }
        open()
        state = state.copy(auth = Auth.Authorizing)
        deadline = elapsedNow() + 180_000
        sdk.authorize()
        render()
    }

    fun unlink() {
        wanted = false
        retryAt = 0
        deadline = 0
        ready = false
        pending = null
        pendingDeadline = 0
        ++generation
        state = state.copy(delivery = Delivery.Idle)
        if (sdk.isOpen) {
            runCatching { sdk.clear() }
                .onSuccess { if (it) state = state.copy(delivery = Delivery.ClearRequested) }
                .onFailure {
                    state = state.copy(delivery = Delivery.Failed)
                }
        }
        tokens?.destroy()
        tokens = null
        refreshRequired = false
        val erased = vault.erase()
        state = state.copy(auth = if (erased) Auth.Unlinked else Auth.Failed(AuthIssue.UnlinkFailed), operation = 0)
        closeAt = if (sdk.isOpen) elapsedNow() + 700 else 0
        render()
    }

    fun retry() {
        if (!wanted || tokens == null || closeAt != 0L || state.auth == Auth.Refreshing) return
        retries = 0
        retryAt = 0
        destroyClient()
        restore()
    }

    fun event(event: SdkEvent) {
        if (event.generation != generation || !wanted || closeAt != 0L) {
            if (event is SdkEvent.Tokens) event.destroy()
            return
        }
        when (event) {
            is SdkEvent.Exchanging -> if (state.auth == Auth.Authorizing) state = state.copy(auth = Auth.Exchanging)
            is SdkEvent.Tokens -> {
                if (state.auth !in listOf(Auth.Authorizing, Auth.Exchanging, Auth.Refreshing)) { event.destroy(); return }
                if (event.access.isEmpty() || event.refresh.isEmpty() || event.expiresInSeconds <= 0) {
                    event.destroy(); requireLink(AuthIssue.CredentialsRejected); return
                }
                val replacement = SecretTokenPair(event.access, event.refresh, epochNow() + event.expiresInSeconds * 1000)
                if (!vault.replace(replacement)) {
                    replacement.destroy()
                    requireLink(AuthIssue.StorageWriteFailed)
                    return
                }
                tokens?.destroy()
                tokens = replacement
                refreshRequired = false
                install(replacement)
            }
            is SdkEvent.Installed -> {
                if (state.auth != Auth.Installing) return
                if (!event.success) { recover(); return }
                state = state.copy(auth = Auth.Connecting)
                deadline = elapsedNow() + 20_000
                sdk.connect()
            }
            is SdkEvent.Status -> {
                if (event.ready && state.auth in listOf(Auth.Connecting, Auth.Ready)) {
                    ready = true
                    deadline = 0
                    state = state.copy(auth = Auth.Ready)
                } else if (!event.ready) {
                    ready = false
                    ++connection
                    accepted = null
                    cleared = false
                    if (state.auth == Auth.Ready) {
                        state = state.copy(auth = Auth.Connecting)
                        deadline = elapsedNow() + 20_000
                    }
                }
            }
            is SdkEvent.Expired -> if (state.auth !in listOf(Auth.Refreshing, Auth.Installing)) beginRefresh()
            is SdkEvent.Completed -> {
                val operation = pending?.takeIf { it.operation == event.operation } ?: return
                pending = null
                pendingDeadline = 0
                state = state.copy(operation = 0)
                if (!event.success) { recover(); return }
                accepted = operation.presence.takeIf { ready && operation.connection == connection }
                retries = 0
                if (ready && desired() == accepted && !clearBarrier)
                    state = state.copy(delivery = Delivery.Accepted(operation.presence))
            }
            is SdkEvent.AuthFailed -> { requireLink(AuthIssue.AuthRejected); return }
        }
        reconcile()
    }

    fun tick() {
        val time = elapsedNow()
        if (closeAt != 0L && time >= closeAt) {
            closeAt = 0
            destroyClient()
        }
        if (!wanted) { render(); return }
        if (retryAt != 0L && time >= retryAt) { retryAt = 0; restore() }
        if (pendingDeadline != 0L && time >= pendingDeadline) { recover(); return }
        if (deadline != 0L && time >= deadline) {
            if (state.auth in listOf(Auth.Authorizing, Auth.Exchanging, Auth.Refreshing))
                requireLink(AuthIssue.Timeout)
            else recover()
            return
        }
        if (ready && pending == null && tokens?.let { epochNow() >= it.expiresAt - 60_000 } == true) beginRefresh()
        reconcile()
    }

    fun failure() {
        if (state.auth in listOf(Auth.Authorizing, Auth.Exchanging, Auth.Refreshing))
            requireLink(AuthIssue.Timeout)
        else if (wanted) recover()
        else { destroyClient(); render() }
    }

    private fun desired(): Presence? = if (settings.enabled && state.connected) state.playback.presence() else null
    private fun open() { ++generation; sdk.open(generation); cleared = false; accepted = null }
    private fun install(value: SecretTokenPair) {
        ready = false
        pending = null
        pendingDeadline = 0
        state = state.copy(auth = Auth.Installing, operation = 0)
        deadline = elapsedNow() + 20_000
        sdk.install(value.access)
    }
    private fun restore() {
        if (!networkAvailable) return
        val saved = tokens ?: return
        open()
        if (refreshRequired || saved.expiresAt <= epochNow() + 60_000) beginRefresh() else install(saved)
    }
    private fun beginRefresh() {
        val saved = tokens ?: return
        refreshRequired = true
        if (!networkAvailable) return
        refreshRequired = false
        destroyClient()
        open()
        state = state.copy(auth = Auth.Refreshing, operation = 0)
        deadline = elapsedNow() + 20_000
        if (!vault.markRefreshing()) { requireLink(AuthIssue.StorageWriteFailed); return }
        sdk.refresh(saved.refresh)
    }
    private fun destroyClient() {
        ++generation
        sdk.close()
        ready = false
        pending = null
        pendingDeadline = 0
        accepted = null
        deadline = 0
        state = state.copy(operation = 0)
    }
    private fun requireLink(issue: AuthIssue) {
        wanted = false
        retryAt = 0
        destroyClient()
        tokens?.destroy()
        tokens = null
        vault.erase()
        state = state.copy(auth = Auth.Failed(issue), delivery = Delivery.Idle)
        render()
    }
    private fun recover() {
        destroyClient()
        if (!wanted || tokens == null) return
        retries = if (retries == Int.MAX_VALUE) retries else retries + 1
        retryAt = elapsedNow() + minOf(60_000L, 1000L shl minOf(retries - 1, 6))
        state = state.copy(auth = Auth.Connecting, delivery = Delivery.Recovering(retries))
        render()
    }
    private fun reconcile() {
        if (wanted && ready && closeAt == 0L) {
            if (pending != null) {
                if (clearBarrier) state = state.copy(delivery = Delivery.ClearPending)
            } else if (clearBarrier || desired() == null) {
                if (clearBarrier || !cleared) {
                    if (!sdk.clear()) { recover(); return }
                    clearBarrier = false
                    cleared = true
                    accepted = null
                    state = state.copy(delivery = Delivery.ClearRequested)
                }
            }
            val target = desired()
            if (pending == null && !clearBarrier && target != null && target != accepted) {
                val operation = ++nextOperation
                pending = Pending(operation, target, connection)
                cleared = false
                pendingDeadline = elapsedNow() + 15_000
                state = state.copy(operation = operation, delivery = Delivery.Updating)
                sdk.update(operation, target)
            }
        }
        render()
    }
    private fun render() {
        state = state.copy(blocker = when {
            !settings.enabled -> Blocker.SharingOff
            !state.connected -> Blocker.MonitorDisconnected
            state.auth != Auth.Ready -> Blocker.NotReady
            else -> null
        })
        publish(state)
    }
}
