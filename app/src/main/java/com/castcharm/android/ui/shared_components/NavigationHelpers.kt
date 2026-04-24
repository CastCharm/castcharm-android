package com.castcharm.android.ui.shared_components

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

fun NavHostController.navigateToPlayer() {
    navigate("player") {
        launchSingleTop = true
    }
}

fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            inclusive = false
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}

fun NavHostController.navigateToFeedEpisodes(feedId: Int) {
    navigate("episodes/$feedId") {
        launchSingleTop = true
    }
}

fun NavHostController.navigateToFeedEpisodesFromDashboard(feedId: Int) {
    navigateToTopLevel("feeds")
    navigateToFeedEpisodes(feedId)
}

fun NavHostController.navigateToFeedsRootFromNested(currentRoute: String?) {
    navigateToTopLevel("feeds")
}