package com.castcharm.android.ui.shared_components

// Navigation extension functions on NavHostController that encode the app's
// specific navigation rules in one place. All screens call these instead of
// calling navigate() directly, so changes to back-stack policy only need to
// be made here.

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

// Navigate to the full-screen player. launchSingleTop prevents stacking multiple
// player destinations if the user taps the mini-player bar repeatedly.
fun NavHostController.navigateToPlayer() {
    navigate("player") {
        launchSingleTop = true
    }
}

// Navigate to a top-level bottom-nav destination (dashboard, feeds, downloads,
// settings). popUpTo(start) clears all destinations above the start destination
// so the back stack never grows from tab-switching. saveState/restoreState are
// both false because we intentionally do NOT preserve scroll or list state when
// switching tabs — the DB-backed flows always show current data.
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

// Push the episode list for a specific feed onto the back stack. launchSingleTop
// avoids duplicating the destination if the user navigates to the same feed twice.
fun NavHostController.navigateToFeedEpisodes(feedId: Int) {
    navigate("episodes/$feedId") {
        launchSingleTop = true
    }
}

// Navigate from the Dashboard "podcast" card to the episode list for that feed.
// The user arrived via Dashboard, so we first switch the bottom nav to the Feeds
// tab (clearing the back stack above the start destination), then push the episode
// list. This way pressing Back from episodes lands on the Feeds list, not Dashboard.
fun NavHostController.navigateToFeedEpisodesFromDashboard(feedId: Int) {
    navigateToTopLevel("feeds")
    navigateToFeedEpisodes(feedId)
}

// Navigate from a nested feed-episodes screen back to the Feeds list root.
// Used by the top-bar back arrow in EpisodeListScreen when the user wants to
// return to the feed grid without going all the way back to the previous tab.
fun NavHostController.navigateToFeedsRootFromNested(currentRoute: String?) {
    navigateToTopLevel("feeds")
}