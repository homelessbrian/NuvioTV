package com.nuvio.tv.livetv.startup

import android.content.Context
import androidx.navigation.NavController
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveTvStartupEntryPoint {
    fun liveTvPreferences(): LiveTvPreferences
}

/**
 * "Open Live TV when Nuvio starts": the first time the home screen appears after the app
 * starts, go straight to Live TV (Back from Live TV still leads to Nuvio's menu and home).
 */
object LiveTvStartup {
    @Volatile private var handled = false

    suspend fun maybeOpenLiveTv(context: Context, nav: NavController) {
        if (handled) return
        handled = true
        val prefs = EntryPointAccessors.fromApplication(
            context.applicationContext, LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
        val settings = runCatching { prefs.settings.first() }.getOrNull() ?: return
        if (!settings.startOnLiveTv) return
        nav.navigate(Screen.LiveTv.route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}
