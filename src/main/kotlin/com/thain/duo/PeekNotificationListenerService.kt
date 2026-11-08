package com.thain.duo

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

/**
 * Reads the device's active notifications and turns them into one [PeekNotification] per app:
 * the app's small icon plus how many of its notifications are showing.
 *
 * Manifest: see the snippet in the README/chat. The system only binds this service once it has
 * been granted notification access (see "Granting access" in the chat).
 *
 * Callbacks arrive on the main thread.
 */
class PeekNotificationListenerService : NotificationListenerService() {

    companion object {
        /** The one source PeekController reads from. Same process as this service. */
        val source = ListenerNotificationSource()

        /**
         * Ongoing notifications (media players, foreground services, calls...) are left out
         * unless this is true.
         */
        var includeOngoing = false
    }

    override fun onListenerConnected() = rebuild()

    override fun onListenerDisconnected() {
        source.publish(emptyList())
        // Ask the system to bind us again. Only works while access is still granted.
        requestRebind(ComponentName(this, PeekNotificationListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = rebuild()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = rebuild()

    // Importance, DND and other ranking changes can add or hide notifications without a post/remove.
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) = rebuild()

    private fun rebuild() {
        // Throws if we're not bound yet, so treat that as "nothing to show".
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val rankingMap = runCatching { currentRanking }.getOrNull() ?: return

        // 1. Keep what Peek should show, with each notification's rank (0 = most important).
        val ranking = Ranking()
        val candidates = ArrayList<Pair<StatusBarNotification, Int>>()
        for (sbn in active) {
            if (!rankingMap.getRanking(sbn.key, ranking)) continue
            if (!shouldShow(sbn, ranking)) continue
            candidates += sbn to ranking.rank
        }
        candidates.sortBy { it.second }

        // 2. Drop group summaries that have children in the list, so a group isn't counted twice.
        //    A lone summary (an app that posts only the summary) is kept.
        val groupsWithChildren = candidates
            .filter { !isGroupSummary(it.first) }
            .map { it.first.groupKey }
            .toSet()
        val notifs = candidates
            .map { it.first }
            .filter { !isGroupSummary(it) || it.groupKey !in groupsWithChildren }

        // 3. One box per app, in order of its most important notification.
        val byApp = LinkedHashMap<String, MutableList<StatusBarNotification>>()
        for (sbn in notifs) byApp.getOrPut(sbn.packageName) { mutableListOf() } += sbn

        val result = byApp.mapNotNull { (pkg, list) ->
            // Use the icon of the app's most important notification that has a loadable one.
            val icon = list.firstNotNullOfOrNull {
                runCatching { it.notification.smallIcon?.loadDrawable(this) }.getOrNull()
            } ?: return@mapNotNull null
            PeekNotification(key = pkg, icon = icon, count = list.size)
        }

        source.publish(result)
    }

    private fun isGroupSummary(sbn: StatusBarNotification) =
        (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    @Suppress("DEPRECATION") // SUPPRESSED_EFFECT_STATUS_BAR is deprecated but still set by the system
    private fun shouldShow(sbn: StatusBarNotification, ranking: Ranking): Boolean {
        // Peek is visible without unlocking, so never show secret notifications. Private ones are
        // fine: Peek only shows the app icon and a count, never the content.
        if (sbn.notification.visibility == Notification.VISIBILITY_SECRET) return false

        if (!includeOngoing && sbn.isOngoing) return false

        // Silent-and-hidden notifications (the "minimized" ones) don't get a status bar icon either.
        if (ranking.importance < NotificationManager.IMPORTANCE_LOW) return false
        if ((ranking.suppressedVisualEffects and
                NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR) != 0
        ) return false

        return true
    }
}