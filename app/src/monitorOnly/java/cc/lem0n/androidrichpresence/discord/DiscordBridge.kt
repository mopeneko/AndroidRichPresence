package cc.lem0n.androidrichpresence.discord

import android.app.Activity
import cc.lem0n.androidrichpresence.presence.*

internal class DiscordBridge : SdkPort {
    companion object {
        const val supported = false
        fun initialize(@Suppress("UNUSED_PARAMETER") activity: Activity): Boolean = false
    }
    override val available = supported
    override val isOpen = false
    override fun open(generation: Long): Unit = error("監視専用ビルドです")
    override fun authorize(): Unit = error("監視専用ビルドです")
    override fun install(access: ByteArray): Unit = error("監視専用ビルドです")
    override fun refresh(refresh: ByteArray): Unit = error("監視専用ビルドです")
    override fun connect(): Unit = error("監視専用ビルドです")
    override fun update(operation: Long, presence: Presence): Unit = error("監視専用ビルドです")
    override fun clear() = false
    override fun close() = Unit
    override fun poll() = emptyList<SdkEvent>()
}
