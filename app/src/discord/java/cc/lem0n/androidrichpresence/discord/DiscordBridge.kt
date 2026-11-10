package cc.lem0n.androidrichpresence.discord

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.os.Looper
import cc.lem0n.androidrichpresence.R
import cc.lem0n.androidrichpresence.presence.*
import com.discord.socialsdk.DiscordSocialSdkInit
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue

internal class DiscordBridge : SdkPort {
    companion object {
        const val supported = true
        private var host: SdkContextActivity? = null
        private var appContext: Context? = null
        fun initializeBackground(context: Context): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            appContext = context.applicationContext
            if (host == null) {
                System.loadLibrary("presence_bridge")
                val value = SdkContextActivity(context.applicationContext)
                DiscordSocialSdkInit.setEngineActivity(value)
                host = value
            }
            return true
        }
        fun initialize(activity: Activity): Boolean {
            initializeBackground(activity)
            host!!.attach(activity)
            return true
        }
        fun detach(activity: Activity) { host?.detach(activity) }
    }
    override val available = supported
    private var handle = 0L
    private var generation = 0L
    private val launchFailures = ConcurrentLinkedQueue<SdkEvent>()
    override val isOpen: Boolean get() = handle != 0L
    override fun open(generation: Long) {
        check(!isOpen)
        this.generation = generation
        handle = nativeCreate()
        check(isOpen)
    }
    override fun authorize() {
        val current = generation
        host?.launchUnavailable = { launchFailures.add(SdkEvent.AuthFailed(current)) }
        nativeAuthorize(handle)
    }
    override fun install(access: ByteArray) = nativeInstall(handle, access)
    override fun refresh(refresh: ByteArray) = nativeRefresh(handle, refresh)
    override fun connect() = nativeConnect(handle)
    override fun update(operation: Long, presence: Presence) {
        val clean = clampTitle(presence.video.title)
        val details = if (clean.codePointCount(0, clean.length) == 1)
            appContext?.getString(R.string.presence_title_short, clean) ?: clean else clean
        val title = details.toByteArray(Charsets.UTF_8)
        nativeUpdate(handle, operation, title, presence.playing)
    }
    override fun clear() = isOpen && nativeClear(handle)
    override fun close() {
        host?.launchUnavailable = null
        if (isOpen) nativeClose(handle)
        handle = 0
    }
    override fun poll(): List<SdkEvent> {
        if (!isOpen) return emptyList()
        val events = mutableListOf<SdkEvent>()
        while (true) events += launchFailures.poll() ?: break
        repeat(64) {
            val bytes = nativePoll(handle) ?: return events
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val event = when (input.readInt()) {
                        1 -> SdkEvent.Tokens(generation, input.secret(), input.secret(), input.readInt().toLong())
                        2 -> SdkEvent.Installed(generation, input.readBoolean())
                        3 -> SdkEvent.Status(generation, input.readBoolean())
                        4 -> SdkEvent.Expired(generation)
                        5 -> SdkEvent.Completed(generation, input.readLong(), input.readBoolean())
                        6 -> SdkEvent.AuthFailed(generation)
                        7 -> SdkEvent.Exchanging(generation)
                        else -> error("Invalid SDK event")
                    }
                    check(input.available() == 0)
                    events += event
                }
            } finally { bytes.fill(0) }
        }
        return events
    }
    private fun DataInputStream.secret(): ByteArray {
        val length = readInt()
        check(length in 1..32_768 && length <= available())
        return ByteArray(length).also { readFully(it) }
    }
    private external fun nativeCreate(): Long
    private external fun nativeAuthorize(handle: Long)
    private external fun nativeInstall(handle: Long, access: ByteArray)
    private external fun nativeRefresh(handle: Long, refresh: ByteArray)
    private external fun nativeConnect(handle: Long)
    private external fun nativeUpdate(handle: Long, operation: Long, title: ByteArray, playing: Boolean)
    private external fun nativePoll(handle: Long): ByteArray?
    private external fun nativeClear(handle: Long): Boolean
    private external fun nativeClose(handle: Long)
}

// SDK 1.10 uses its Activity for Context operations, including RPC bind/unbind.
// Keep that context stable; interactive launches require the actual screen.
private class SdkContextActivity(context: Context) : Activity() {
    private var foreground = WeakReference<Activity>(null)
    @Volatile var launchUnavailable: (() -> Unit)? = null
    init { attachBaseContext(context.createConfigurationContext(context.resources.configuration)) }

    fun attach(activity: Activity) { foreground = WeakReference(activity) }
    fun detach(activity: Activity) {
        if (foreground.get() === activity) foreground.clear()
    }
    override fun startActivity(intent: Intent) = startActivity(intent, null)
    override fun startActivity(intent: Intent, options: Bundle?) {
        val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (activity == null) { launchUnavailable?.invoke(); return }
        try {
            activity.startActivity(intent, options)
        } catch (_: ActivityNotFoundException) {
            launchUnavailable?.invoke()
        } catch (_: SecurityException) {
            launchUnavailable?.invoke()
        }
    }
}
