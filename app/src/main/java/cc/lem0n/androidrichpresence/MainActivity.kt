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
import androidx.compose.ui.unit.dp
import cc.lem0n.androidrichpresence.discord.DiscordBridge
import cc.lem0n.androidrichpresence.presence.Auth
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
                        Text("YouTube Presence", style = MaterialTheme.typography.headlineSmall)
                        Text(authLabel(screen.auth))
                        Button(enabled = DiscordBridge.supported && screen.auth !in listOf(Auth.Authorizing,
                            Auth.Exchanging, Auth.Installing, Auth.Connecting, Auth.Refreshing, Auth.Ready),
                            onClick = { runtime.authorize() }) { Text("Discordと連携") }
                        if (screen.auth !in listOf(Auth.Unlinked, Auth.MonitorOnly)) {
                            OutlinedButton(onClick = { runtime.disconnect() }) { Text("連携を解除（端末の情報を削除）") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Discord に共有", modifier = Modifier.padding(top = 12.dp))
                            Switch(checked = saved.enabled, onCheckedChange = {
                                saved = saved.copy(enabled = it)
                                PresenceStore.save(this@MainActivity, saved)
                            })
                        }
                        if (!DiscordBridge.supported) {
                            Text("監視専用ビルドのため、Discord には送信できません。再生情報の確認は利用できます。",
                                color = MaterialTheme.colorScheme.error)
                        }
                        Text(if (notificationAccess) "通知へのアクセスは許可済みです" else "通知へのアクセスを許可してください")
                        Text("Android は通知全体へのアクセスを許可します。このアプリは指定した YouTube アプリのメディア情報だけを読み取ります。",
                            style = MaterialTheme.typography.bodySmall)
                        if (!notificationAccess) {
                            Text("設定画面のスイッチが「Controlled by Restricted Setting」と表示されて操作できない場合は、ストア以外からインストールしたアプリへの Android の制限です。このアプリでは解除できません。Android の設定で「アプリ」から AndroidRichPresence を開き、右上のメニューから「Allow restricted settings」（制限付き設定を許可）を選んでから、もう一度お試しください。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("通知へのアクセス設定を開く") }
                        if (!notificationAccess) {
                            OutlinedButton(onClick = {
                                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", this@MainActivity.packageName, null)))
                            }) { Text("このアプリの設定を開く") }
                        }
                        StatusCard(screen)
                        OutlinedTextField(value = packageName, onValueChange = { packageName = it },
                            label = { Text("監視する YouTube のパッケージ名") }, singleLine = true,
                            isError = !packageValid, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { packageName = "com.google.android.youtube" }) { Text("公式 YouTube") }
                            OutlinedButton(onClick = { packageName = "app.morphe.android.youtube" }) { Text("Morphe") }
                        }
                        Text("現在の監視対象: ${saved.packageName}", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = packageValid, onClick = {
                            saved = saved.copy(packageName = packageName.trim())
                            PresenceStore.save(this@MainActivity, saved)
                        }) { Text("設定を保存") }
                        OutlinedButton(enabled = screen.auth is Auth.Failed,
                            onClick = { runtime.retry() }) { Text("Discord への送信を再試行") }
                        Text("アプリの強制停止や Android による終了後の動作は保証できません。バッファリング・停止・情報の取得不能時は表示の消去を要求します。",
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
            Text("取得した再生情報", style = MaterialTheme.typography.titleMedium)
            val (title, state) = when (val playback = screen.playback) {
                Playback.NoSession -> "動画はありません" to "停止・未検出"
                Playback.MissingMetadata -> "タイトルを取得できません" to "情報不足"
                is Playback.Playing -> playback.video.title to "再生中"
                is Playback.Paused -> playback.video.title to "一時停止"
                is Playback.Buffering -> (playback.video?.title ?: "タイトル未取得") to "バッファリング"
            }
            Text(title)
            Text(state)
            Text(if (screen.connected) "監視サービス接続済み" else "監視サービス未接続")
            screen.blockedReason?.let { Text(it) }
            Text(when (val delivery = screen.delivery) {
                Delivery.Idle -> "Discord への送信はまだありません"
                Delivery.Updating -> "Discord に更新を送信中"
                Delivery.ClearPending -> "更新の完了を待って表示を消去します"
                Delivery.ClearRequested -> "表示の消去を要求しました。Discord 側の消去は未確認です"
                is Delivery.Accepted -> "Discord が更新を受け付けました。プロフィールの表示は Discord で確認してください"
                is Delivery.Failed -> delivery.reason
                is Delivery.Recovering -> "Discord に再接続しています（${delivery.attempt} 回目）"
            })
        }
    }
}

private fun authLabel(auth: Auth): String = when (auth) {
    Auth.Unlinked -> "Discord は未連携です"
    Auth.NeedsForeground -> "画面を開いて Discord への接続を再開してください"
    Auth.Authorizing, Auth.Exchanging -> "Discord との連携を確認しています"
    Auth.Installing, Auth.Connecting -> "Discord に接続しています"
    Auth.Ready -> "Discord に接続済みです"
    Auth.Refreshing -> "Discord への接続を更新しています"
    Auth.MonitorOnly -> "監視専用ビルドです"
    is Auth.Failed -> auth.reason
}
