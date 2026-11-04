package cc.lem0n.androidrichpresence.presence

const val DEFAULT_APPLICATION_ID = "1567132201588367360"

data class Video(val title: String, val mediaId: String? = null)
sealed interface Playback {
    data object NoSession : Playback
    data object MissingMetadata : Playback
    data class Playing(val video: Video) : Playback
    data class Paused(val video: Video) : Playback
    data class Buffering(val video: Video?) : Playback
}
data class Presence(val video: Video, val playing: Boolean)
fun Playback.presence(): Presence? = when (this) {
    is Playback.Playing -> Presence(video, true)
    is Playback.Paused -> Presence(video, false)
    else -> null
}

sealed interface Auth {
    data object Unlinked : Auth
    data object NeedsForeground : Auth
    data object Authorizing : Auth
    data object Exchanging : Auth
    data object Installing : Auth
    data object Connecting : Auth
    data object Ready : Auth
    data object Refreshing : Auth
    data object MonitorOnly : Auth
    data class Failed(val reason: String) : Auth
}
sealed interface Delivery {
    data object Idle : Delivery
    data object Updating : Delivery
    data object ClearPending : Delivery
    data object ClearRequested : Delivery
    data class Accepted(val presence: Presence) : Delivery
    data class Recovering(val attempt: Int) : Delivery
    data class Failed(val reason: String) : Delivery
}
data class Settings(
    val enabled: Boolean = false,
    val packageName: String = "com.google.android.youtube",
)
data class ScreenState(
    val auth: Auth = Auth.Unlinked,
    val operation: Long = 0,
    val connected: Boolean = false,
    val playback: Playback = Playback.NoSession,
    val delivery: Delivery = Delivery.Idle,
    val blockedReason: String? = "監視サービスは未接続です",
)
fun presenceTitle(title: String): String {
    val clean = title.trim()
    val length = clean.codePointCount(0, clean.length)
    if (length == 1) return "動画: $clean"
    val utf8 = clean.toByteArray(Charsets.UTF_8)
    if (utf8.size <= 128) return clean
    var end = 128
    while (utf8[end].toInt() and 0xC0 == 0x80) end--
    return String(utf8, 0, end, Charsets.UTF_8)
}
