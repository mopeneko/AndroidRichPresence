package cc.lem0n.androidrichpresence.presence

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PresenceStore {
    private val mutableScreen = MutableStateFlow(ScreenState())
    val screen = mutableScreen.asStateFlow()
    var refresh: (() -> Unit)? = null
    internal fun publish(state: ScreenState) { mutableScreen.value = state }
    private fun preferences(context: Context) = context.getSharedPreferences("presence", Context.MODE_PRIVATE)
    fun settings(context: Context): Settings = preferences(context).let {
        Settings(it.getBoolean("enabled", false), it.getString("packageName", "com.google.android.youtube").orEmpty())
    }
    fun save(context: Context, settings: Settings) {
        preferences(context).edit().putBoolean("enabled", settings.enabled)
            .putString("packageName", settings.packageName.trim()).apply()
        PresenceRuntime.get(context).settings(settings)
        refresh?.invoke()
    }
}
