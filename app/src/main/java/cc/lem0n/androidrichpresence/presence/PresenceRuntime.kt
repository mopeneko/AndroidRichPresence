package cc.lem0n.androidrichpresence.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import cc.lem0n.androidrichpresence.discord.DiscordBridge

class PresenceRuntime private constructor(context: Context) {
    companion object {
        @Volatile private var instance: PresenceRuntime? = null
        fun get(context: Context): PresenceRuntime = instance ?: synchronized(this) {
            instance ?: PresenceRuntime(context.applicationContext).also { instance = it }
        }
    }
    init { DiscordBridge.initializeBackground(context) }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val worker = HandlerThread("DiscordPresence").apply { start() }
    private val handler = Handler(worker.looper)
    private val bridge = DiscordBridge()
    private lateinit var session: PresenceSession
    private var scheduled = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
    }
    private val pump = Runnable {
        scheduled = false
        execute {
            bridge.poll().forEach(session::event)
            session.tick()
        }
    }
    init {
        handler.post {
            session = PresenceSession(bridge, EncryptedTokenVault(context),
                elapsedNow = SystemClock::elapsedRealtime, epochNow = System::currentTimeMillis,
                publish = PresenceStore::publish)
            execute {
                connectivity.registerDefaultNetworkCallback(networkCallback)
                session.networkChanged(networkAvailable())
                session.settings(PresenceStore.settings(context))
            }
        }
    }
    fun foregroundReady() = post { foregroundReady() }
    fun authorize() = post { link() }
    fun disconnect() = post { unlink() }
    fun retry() = post { retry() }
    fun settings(value: Settings) = post { settings(value) }
    fun source(playback: Playback, connected: Boolean) = post { source(playback, connected) }
    private fun networkChanged() = post { networkChanged(networkAvailable()) }
    private fun networkAvailable(): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private fun post(action: PresenceSession.() -> Unit) {
        handler.post { execute { session.action() } }
    }
    private fun execute(action: () -> Unit) {
        try { action() } catch (_: Exception) { session.failure() }
        schedule()
    }
    private fun schedule() {
        if (session.needsTick && !scheduled) {
            scheduled = true
            handler.postDelayed(pump, if (bridge.isOpen) 50 else 250)
        }
    }
}
