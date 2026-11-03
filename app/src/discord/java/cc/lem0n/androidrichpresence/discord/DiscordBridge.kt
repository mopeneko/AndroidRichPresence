package cc.lem0n.androidrichpresence.discord

import android.app.Activity
import cc.lem0n.androidrichpresence.presence.*
import com.discord.socialsdk.DiscordSocialSdkInit
import java.io.ByteArrayInputStream
import java.io.DataInputStream

internal class DiscordBridge : SdkPort {
    companion object {
        const val supported = true
        fun initialize(activity: Activity): Boolean {
            System.loadLibrary("presence_bridge")
            DiscordSocialSdkInit.setEngineActivity(activity)
            return true
        }
    }
    override val available = supported
    private var handle = 0L
    private var generation = 0L
    override val isOpen: Boolean get() = handle != 0L
    override fun open(generation: Long) {
        check(!isOpen)
        this.generation = generation
        handle = nativeCreate()
        check(isOpen)
    }
    override fun authorize() = nativeAuthorize(handle)
    override fun install(access: ByteArray) = nativeInstall(handle, access)
    override fun refresh(refresh: ByteArray) = nativeRefresh(handle, refresh)
    override fun connect() = nativeConnect(handle)
    override fun update(operation: Long, presence: Presence) {
        val title = presenceTitle(presence.video.title).toByteArray(Charsets.UTF_8)
        nativeUpdate(handle, operation, title, presence.playing)
    }
    override fun clear() = isOpen && nativeClear(handle)
    override fun close() {
        if (isOpen) nativeClose(handle)
        handle = 0
    }
    override fun poll(): List<SdkEvent> {
        if (!isOpen) return emptyList()
        val events = mutableListOf<SdkEvent>()
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
