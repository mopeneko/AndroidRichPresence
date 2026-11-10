package cc.lem0n.androidrichpresence.youtube

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import java.io.FileDescriptor
import java.io.PrintWriter
import cc.lem0n.androidrichpresence.presence.Playback
import cc.lem0n.androidrichpresence.presence.PresenceRuntime
import cc.lem0n.androidrichpresence.presence.PresenceStore
import cc.lem0n.androidrichpresence.presence.Video

class YouTubeNotificationListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val component by lazy { ComponentName(this, javaClass) }
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var selected: MediaSession.Token? = null
    private var runtime: PresenceRuntime? = null
    private var connected = false
    private var packageNameFilter = ""
    private var registration = 0L
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        if (connected) bind(sessions.orEmpty())
    }
    private val refresh: () -> Unit = { main.post { refreshSettings() }; Unit }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (connected) return
        connected = true
        if (runtime == null) runtime = PresenceRuntime.get(this)
        PresenceStore.refresh = refresh
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            refreshSettings()
        } catch (_: SecurityException) {
            disconnect()
        }
    }

    private fun refreshSettings() {
        if (!connected) return
        val settings = PresenceStore.settings(this)
        if (packageNameFilter != settings.packageName) {
            unbind()
            packageNameFilter = settings.packageName
            runtime?.source(Playback.NoSession, connected)
        }
        try {
            bind(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            disconnect()
        }
    }

    private fun bind(sessions: List<MediaController>) {
        val eligible = sessions.filter { it.packageName == packageNameFilter }
        val tokens = eligible.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { token ->
            controllers.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }
        eligible.forEach { controller ->
            val token = controller.sessionToken
            if (token !in controllers) {
                val generation = registration
                val callback = object : MediaController.Callback() {
                    private fun current() = connected && generation == registration && controllers[token]?.second === this
                    override fun onMetadataChanged(metadata: MediaMetadata?) { if (current()) emit() }
                    override fun onPlaybackStateChanged(state: PlaybackState?) { if (current()) emit() }
                    override fun onSessionDestroyed() {
                        if (!current()) return
                        controllers.remove(token)?.let { it.first.unregisterCallback(it.second) }
                        emit()
                    }
                }
                controllers[token] = controller to callback
                controller.registerCallback(callback, main)
            }
        }
        emit()
    }

    private fun emit() {
        val controller = controllers.values.map { it.first }.sortedWith(
            compareByDescending<MediaController> { score(it) }
                .thenByDescending { it.sessionToken == selected }
        ).firstOrNull()
        selected = controller?.sessionToken
        val playback = controller?.snapshot() ?: Playback.NoSession
        runtime?.source(playback, connected)
    }

    private fun score(controller: MediaController): Int = when (controller.playbackState?.state) {
        PlaybackState.STATE_PLAYING -> 3
        PlaybackState.STATE_PAUSED -> 2
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 1
        else -> 0
    }

    private fun MediaController.snapshot(): Playback {
        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val video = title?.let { Video(it, metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)) }
        return when (playbackState?.state) {
            PlaybackState.STATE_PLAYING -> video?.let(Playback::Playing) ?: Playback.MissingMetadata
            PlaybackState.STATE_PAUSED -> video?.let(Playback::Paused) ?: Playback.MissingMetadata
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> Playback.Buffering(video)
            else -> Playback.NoSession
        }
    }

    private fun unbind() {
        registration++
        controllers.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        controllers.clear()
        selected = null
    }

    private fun disconnect() {
        if (!connected) return
        connected = false
        manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        unbind()
        if (PresenceStore.refresh === refresh) PresenceStore.refresh = null
        runtime?.source(Playback.NoSession, false)
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        val screen = PresenceStore.screen.value
        writer.println("sourcePackage=${PresenceStore.settings(this).packageName}")
        writer.println("connected=${screen.connected}")
        writer.println("playback=${screen.playback}")
        writer.println("auth=${screen.auth}")
        writer.println("authenticated=${screen.auth == cc.lem0n.androidrichpresence.presence.Auth.Ready}")
        writer.println("operation=${screen.operation}")
        writer.println("delivery=${screen.delivery}")
        writer.println("blockedReason=${screen.blocker}")
    }

    override fun onListenerDisconnected() { disconnect(); super.onListenerDisconnected() }
    override fun onDestroy() {
        disconnect()
        runtime = null
        super.onDestroy()
    }
}
