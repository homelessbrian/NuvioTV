package com.nuvio.tv.livetv.startup

import android.content.Context
import androidx.navigation.NavController
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.effectiveStartPage
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
    fun onDemandRepository(): com.nuvio.tv.livetv.ondemand.OnDemandRepository
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
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, LiveTvStartupEntryPoint::class.java)
        val settings = runCatching { entry.liveTvPreferences().settings.first() }.getOrNull() ?: return
        val route = when (settings.effectiveStartPage) {
            "LIVE_TV" -> Screen.LiveTv.route
            // On Demand only when something has been imported; otherwise stay on Home.
            "ON_DEMAND" -> Screen.OnDemand.route.takeIf {
                runCatching { entry.onDemandRepository().also { r -> r.start() }.hasContent.value || waitForOnDemand(entry) }.getOrDefault(false)
            }
            "SEARCH" -> Screen.Search.route
            "LIBRARY" -> Screen.Library.route
            "DISCOVER" -> Screen.Discover.route
            else -> null
        } ?: return
        nav.navigate(route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** The On Demand index is checked in the background at start-up; give it a moment. */
    private suspend fun waitForOnDemand(entry: LiveTvStartupEntryPoint): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(2_000) { entry.onDemandRepository().hasContent.first { it } } ?: false
}
