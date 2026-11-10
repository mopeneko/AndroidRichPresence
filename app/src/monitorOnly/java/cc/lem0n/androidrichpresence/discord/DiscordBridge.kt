package cc.lem0n.androidrichpresence.discord

import android.app.Activity
import android.content.Context
import cc.lem0n.androidrichpresence.presence.*

internal class DiscordBridge : SdkPort {
    companion object {
        const val supported = false
        fun initializeBackground(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false
        fun initialize(@Suppress("UNUSED_PARAMETER") activity: Activity): Boolean = false
        fun detach(@Suppress("UNUSED_PARAMETER") activity: Activity) = Unit
    }
    override val available = supported
    override val isOpen = false
    override fun open(generation: Long): Unit = error("Discord is unavailable in monitor-only builds")
    override fun authorize(): Unit = error("Discord is unavailable in monitor-only builds")
    override fun install(access: ByteArray): Unit = error("Discord is unavailable in monitor-only builds")
    override fun refresh(refresh: ByteArray): Unit = error("Discord is unavailable in monitor-only builds")
    override fun connect(): Unit = error("Discord is unavailable in monitor-only builds")
    override fun update(operation: Long, presence: Presence): Unit = error("Discord is unavailable in monitor-only builds")
    override fun clear() = false
    override fun close() = Unit
    override fun poll() = emptyList<SdkEvent>()
}
