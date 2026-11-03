package cc.lem0n.androidrichpresence.presence

internal class SecretTokenPair(val access: ByteArray, val refresh: ByteArray, val expiresAt: Long) {
    fun destroy() { access.fill(0); refresh.fill(0) }
    override fun toString() = "SecretTokenPair(redacted)"
}
internal sealed interface TokenLoad {
    data object Empty : TokenLoad
    data object RequiresLink : TokenLoad
    class Stored(val tokens: SecretTokenPair) : TokenLoad {
        override fun toString() = "Stored(redacted)"
    }
}
internal interface TokenVault {
    fun load(): TokenLoad
    fun replace(tokens: SecretTokenPair): Boolean
    fun markRefreshing(): Boolean
    fun erase(): Boolean
}
internal sealed interface SdkEvent {
    val generation: Long
    class Tokens(override val generation: Long, val access: ByteArray, val refresh: ByteArray,
                 val expiresInSeconds: Long) : SdkEvent {
        fun destroy() { access.fill(0); refresh.fill(0) }
        override fun toString() = "Tokens(redacted)"
    }
    data class Exchanging(override val generation: Long) : SdkEvent
    data class Installed(override val generation: Long, val success: Boolean) : SdkEvent
    data class Status(override val generation: Long, val ready: Boolean) : SdkEvent
    data class Expired(override val generation: Long) : SdkEvent
    data class Completed(override val generation: Long, val operation: Long, val success: Boolean) : SdkEvent
    data class AuthFailed(override val generation: Long) : SdkEvent
}
internal interface SdkPort {
    val available: Boolean
    val isOpen: Boolean
    fun open(generation: Long)
    fun authorize()
    fun install(access: ByteArray)
    fun refresh(refresh: ByteArray)
    fun connect()
    fun update(operation: Long, presence: Presence)
    fun clear(): Boolean
    fun close()
    fun poll(): List<SdkEvent>
}
