package cc.lem0n.androidrichpresence

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.service.notification.NotificationListenerService
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.lem0n.androidrichpresence.discord.DiscordBridge
import cc.lem0n.androidrichpresence.presence.Auth
import cc.lem0n.androidrichpresence.presence.AuthIssue
import cc.lem0n.androidrichpresence.presence.Blocker
import cc.lem0n.androidrichpresence.presence.Delivery
import cc.lem0n.androidrichpresence.presence.Playback
import cc.lem0n.androidrichpresence.presence.PresenceRuntime
import cc.lem0n.androidrichpresence.presence.PresenceStore
import cc.lem0n.androidrichpresence.presence.ScreenState
import cc.lem0n.androidrichpresence.ui.theme.AndroidRichPresenceTheme
import cc.lem0n.androidrichpresence.youtube.YouTubeNotificationListener

class MainActivity : ComponentActivity() {
    private var notificationAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = PresenceRuntime.get(this)
        if (runCatching { DiscordBridge.initialize(this) }.getOrDefault(false)) runtime.foregroundReady()
        enableEdgeToEdge()
        setContent {
            AndroidRichPresenceTheme {
                val screen by PresenceStore.screen.collectAsState()
                var saved by remember { mutableStateOf(PresenceStore.settings(this)) }
                var packageName by remember { mutableStateOf(saved.packageName) }
                val packageValid = packageName.trim().matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.title_app), style = MaterialTheme.typography.headlineSmall)
                        Text(authText(screen.auth))
                        Button(enabled = DiscordBridge.supported && screen.auth !in listOf(Auth.Authorizing,
                            Auth.Exchanging, Auth.Installing, Auth.Connecting, Auth.Refreshing, Auth.Ready),
                            onClick = { runtime.authorize() }) { Text(stringResource(R.string.action_link_discord)) }
                        if (screen.auth !in listOf(Auth.Unlinked, Auth.MonitorOnly)) {
                            OutlinedButton(onClick = { runtime.disconnect() }) { Text(stringResource(R.string.action_unlink)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.label_share_to_discord), modifier = Modifier.padding(top = 12.dp))
                            Switch(checked = saved.enabled, onCheckedChange = {
                                saved = saved.copy(enabled = it)
                                PresenceStore.save(this@MainActivity, saved)
                            })
                        }
                        if (!DiscordBridge.supported) {
                            Text(stringResource(R.string.notice_monitor_only),
                                color = MaterialTheme.colorScheme.error)
                        }
                        Text(stringResource(if (notificationAccess) R.string.notification_access_granted else R.string.notification_access_required))
                        Text(stringResource(R.string.notification_access_explanation),
                            style = MaterialTheme.typography.bodySmall)
                        if (!notificationAccess) {
                            Text(stringResource(R.string.restricted_settings_help),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text(stringResource(R.string.action_open_notification_access)) }
                        if (!notificationAccess) {
                            OutlinedButton(onClick = {
                                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", this@MainActivity.packageName, null)))
                            }) { Text(stringResource(R.string.action_open_app_settings)) }
                        }
                        StatusCard(screen)
                        OutlinedTextField(value = packageName, onValueChange = { packageName = it },
                            label = { Text(stringResource(R.string.label_package_to_monitor)) }, singleLine = true,
                            isError = !packageValid, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { packageName = "com.google.android.youtube" }) { Text(stringResource(R.string.preset_official_youtube)) }
                            OutlinedButton(onClick = { packageName = "app.morphe.android.youtube" }) { Text(stringResource(R.string.preset_morphe)) }
                        }
                        Text(stringResource(R.string.label_current_source, saved.packageName), style = MaterialTheme.typography.bodySmall)
                        Button(enabled = packageValid, onClick = {
                            saved = saved.copy(packageName = packageName.trim())
                            PresenceStore.save(this@MainActivity, saved)
                        }) { Text(stringResource(R.string.action_save)) }
                        OutlinedButton(enabled = screen.auth is Auth.Failed,
                            onClick = { runtime.retry() }) { Text(stringResource(R.string.action_retry_delivery)) }
                        Text(stringResource(R.string.notice_background_limits),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val component = ComponentName(this, YouTubeNotificationListener::class.java)
        notificationAccess = AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.contains(component) == true
        if (notificationAccess && !PresenceStore.screen.value.connected) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                NotificationListenerService.requestUnbind(component)
            }
            NotificationListenerService.requestRebind(component)
        }
        if (runCatching { DiscordBridge.initialize(this) }.getOrDefault(false))
            PresenceRuntime.get(this).foregroundReady()
        PresenceStore.refresh?.invoke()
    }

    override fun onPause() {
        DiscordBridge.detach(this)
        super.onPause()
    }
}

@Composable
private fun StatusCard(screen: ScreenState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.card_playback_title), style = MaterialTheme.typography.titleMedium)
            Text(playbackTitle(screen.playback))
            Text(playbackState(screen.playback))
            Text(stringResource(if (screen.connected) R.string.monitor_connected else R.string.monitor_disconnected))
            screen.blocker?.let { Text(blockerText(it)) }
            Text(deliveryText(screen.delivery))
        }
    }
}

@Composable
private fun playbackTitle(playback: Playback): String = when (playback) {
    Playback.NoSession -> stringResource(R.string.playback_none)
    Playback.MissingMetadata -> stringResource(R.string.playback_title_missing)
    is Playback.Playing -> playback.video.title
    is Playback.Paused -> playback.video.title
    is Playback.Buffering -> playback.video?.title ?: stringResource(R.string.playback_title_pending)
}

@Composable
private fun playbackState(playback: Playback): String = when (playback) {
    Playback.NoSession -> stringResource(R.string.playback_state_none)
    Playback.MissingMetadata -> stringResource(R.string.playback_state_missing)
    is Playback.Playing -> stringResource(R.string.playback_state_playing)
    is Playback.Paused -> stringResource(R.string.playback_state_paused)
    is Playback.Buffering -> stringResource(R.string.playback_state_buffering)
}

@Composable
private fun deliveryText(delivery: Delivery): String = when (delivery) {
    Delivery.Idle -> stringResource(R.string.delivery_idle)
    Delivery.Updating -> stringResource(R.string.delivery_updating)
    Delivery.ClearPending -> stringResource(R.string.delivery_clear_pending)
    Delivery.ClearRequested -> stringResource(R.string.delivery_clear_requested)
    is Delivery.Accepted -> stringResource(R.string.delivery_accepted)
    Delivery.Failed -> stringResource(R.string.delivery_clear_failed)
    is Delivery.Recovering -> stringResource(R.string.delivery_recovering, delivery.attempt)
}

@Composable
private fun blockerText(blocker: Blocker): String = when (blocker) {
    Blocker.SharingOff -> stringResource(R.string.blocked_sharing_off)
    Blocker.MonitorDisconnected -> stringResource(R.string.blocked_monitor_disconnected)
    Blocker.NotReady -> stringResource(R.string.blocked_discord_not_ready)
}

@Composable
private fun authText(auth: Auth): String = when (auth) {
    Auth.Unlinked -> stringResource(R.string.auth_unlinked)
    Auth.NeedsForeground -> stringResource(R.string.auth_needs_foreground)
    Auth.Authorizing, Auth.Exchanging -> stringResource(R.string.auth_authorizing)
    Auth.Installing, Auth.Connecting -> stringResource(R.string.auth_connecting)
    Auth.Ready -> stringResource(R.string.auth_ready)
    Auth.Refreshing -> stringResource(R.string.auth_refreshing)
    Auth.MonitorOnly -> stringResource(R.string.auth_monitor_only)
    is Auth.Failed -> stringResource(when (auth.issue) {
        AuthIssue.LinkUnreadable -> R.string.auth_issue_link_unreadable
        AuthIssue.StorageUnavailable -> R.string.auth_issue_storage_unavailable
        AuthIssue.UnlinkFailed -> R.string.auth_issue_unlink_failed
        AuthIssue.CredentialsRejected -> R.string.auth_issue_credentials_rejected
        AuthIssue.StorageWriteFailed -> R.string.auth_issue_storage_write_failed
        AuthIssue.AuthRejected -> R.string.auth_issue_auth_rejected
        AuthIssue.Timeout -> R.string.auth_issue_timeout
    })
}
