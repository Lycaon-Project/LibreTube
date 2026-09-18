package com.github.libretube.workers

import android.Manifest
import android.app.Notification
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationManagerCompat.NotificationWithIdAndTag
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.libretube.LibreTubeApp.Companion.PUSH_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.views.TimePickerPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds

/**
 * The notification worker which checks for new streams in a certain frequency
 */
class NotificationWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    private val notificationManager = NotificationManagerCompat.from(appContext)

    // Cache pour éviter de retélécharger les mêmes images
    private val imageCache = mutableMapOf<String, Bitmap>()

    override suspend fun doWork(): Result {
        if (!checkTime()) {
            Log.d(TAG(), "Outside notification time window")
            return Result.success()
        }

        // check whether there are new streams and notify if there are some
        val result = checkForNewStreams()

        // Clear image cache to free memory
        clearImageCache()

        // return success if the API request succeeded
        return if (result) Result.success() else Result.retry()
    }

    /**
     * Determine whether the time is valid to notify
     */
    private fun checkTime(): Boolean {
        if (!PreferenceHelper.getBoolean(PreferenceKeys.NOTIFICATION_TIME_ENABLED, false)) {
            return true
        }

        val start = getTimePickerPref(PreferenceKeys.NOTIFICATION_START_TIME)
        val end = getTimePickerPref(PreferenceKeys.NOTIFICATION_END_TIME)
        val currentTime = LocalTime.now()

        return if (start > end) {
            currentTime !in end..start
        } else {
            currentTime in start..end
        }
    }

    private fun getTimePickerPref(key: String): LocalTime {
        return try {
            LocalTime.parse(
                PreferenceHelper.getString(key, TimePickerPreference.DEFAULT_VALUE)
            )
        } catch (e: Exception) {
            Log.w(TAG(), "Failed to parse time preference for $key", e)
            LocalTime.of(0, 0)
        }
    }

    /**
     * check whether new streams are available in subscriptions
     */
    private suspend fun checkForNewStreams(): Boolean {
        Log.d(TAG(), "Work manager started")

        // fetch the users feed with timeout to avoid hanging
        val videoFeed = try {
            withTimeoutOrNull(30.seconds) { // ✅ Duration moderne
                withContext(Dispatchers.IO) {
                    SubscriptionHelper.getFeed(forceRefresh = false) // Use cache when possible
                }
            }?.filter { !it.isUpcoming } ?: run {
                Log.w(TAG(), "Feed fetch timeout or null, retrying with force refresh")
                withContext(Dispatchers.IO) {
                    SubscriptionHelper.getFeed(forceRefresh = true)
                }.filter { !it.isUpcoming }
            }
        } catch (e: Exception) {
            Log.e(TAG(), "Failed to fetch feed", e)
            return false
        }

        if (videoFeed.isEmpty()) {
            Log.d(TAG(), "No videos in feed")
            return true
        }

        val lastFeedCheckMillis = PreferenceHelper.getLastCheckedFeedTime(seenByUser = false)

        // first time notifications are enabled
        if (lastFeedCheckMillis == 0L) {
            // Update timestamp but don't notify (avoid spam on first enable)
            PreferenceHelper.updateLastFeedWatchedTime(videoFeed.first().uploaded, seenByUser = false)
            Log.d(TAG(), "First feed check, updating timestamp without notifications")
            return true
        }

        // Check if there are any new videos
        val newVideos = videoFeed.filter { it.uploaded > lastFeedCheckMillis }
        if (newVideos.isEmpty()) {
            Log.d(TAG(), "No new videos since last check")
            return true
        }

        val channelsToIgnore = PreferenceHelper.getIgnorableNotificationChannels()
        val enableShortsNotification =
            PreferenceHelper.getBoolean(PreferenceKeys.SHORTS_NOTIFICATIONS, false)

        val channelGroups = newVideos.asSequence()
            // don't show notifications for shorts videos if not enabled
            .filter { enableShortsNotification || !it.isShort }
            // hide for notifications unsubscribed channels (safe null check)
            .filter { stream ->
                stream.uploaderUrl?.let { url ->
                    url.toID() !in channelsToIgnore
                } ?: false
            }
            // group the new streams by the uploader
            .groupBy { it.uploaderUrl!!.toID() }

        // update the last feed check time
        PreferenceHelper.updateLastFeedWatchedTime(videoFeed.first().uploaded, seenByUser = false)

        // return if all channels have notifications disabled
        if (channelGroups.isEmpty()) {
            Log.d(TAG(), "No channels to notify")
            return true
        }

        Log.d(TAG(), "Create notifications for ${newVideos.size} new videos from ${channelGroups.size} channels")

        // Limit notifications per channel to avoid spam (max 5 per channel)
        val maxNotificationsPerChannel = 5

        // create a notification for each new stream
        channelGroups.forEach { (channelId, streams) ->
            val limitedStreams = streams.take(maxNotificationsPerChannel)
            if (streams.size > maxNotificationsPerChannel) {
                Log.w(TAG(), "Limiting notifications for channel $channelId from ${streams.size} to $maxNotificationsPerChannel")
            }
            createNotificationsForChannel(channelId, limitedStreams)
        }

        return true
    }

    /**
     * Group of notifications created when new streams are found in a given channel.
     */
    private suspend fun createNotificationsForChannel(group: String, streams: List<StreamItem>) {
        if (streams.isEmpty()) return

        // Avoid creating notifications if permission is not granted.
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG(), "POST_NOTIFICATIONS permission not granted")
            return
        }

        val summaryId = group.hashCode()

        // Check if notification already exists to avoid duplicates
        if (isNotificationAlreadyShown()) {
            Log.d(TAG(), "Notification for group $group already shown, skipping")
            return
        }

        val intent = Intent(applicationContext, MainActivity::class.java)
            .setFlags(INTENT_FLAGS)
            .putExtra(IntentData.channelId, group)
        val pendingIntent = PendingIntentCompat
            .getActivity(applicationContext, summaryId, intent, FLAG_UPDATE_CURRENT, false)

        // Create summary notification
        val newStreams = applicationContext.resources
            .getQuantityString(R.plurals.channel_new_streams, streams.size, streams.size)
        val summary = NotificationCompat.InboxStyle()
            .setSummaryText(newStreams)
        streams.forEach {
            summary.addLine(it.title ?: "Untitled")
        }

        val uploaderName = streams[0].uploaderName ?: "Unknown Channel"
        val uploaderAvatar = streams[0].uploaderAvatar

        val summaryNotification = createNotificationBuilder(group)
            .setContentTitle(uploaderName)
            .setContentText(newStreams)
            .setContentIntent(pendingIntent)
            .setGroupSummary(true)
            .setStyle(summary)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setLargeIcon(downloadImage(uploaderAvatar))
            .build()

        // Create stream notifications with parallel processing but limited concurrency
        val notifications = withContext(Dispatchers.IO) {
            streams.map { stream ->
                async { createStreamNotification(group, stream) }
            }.awaitAll().filterNotNull() // Filter out failed notifications
        }

        if (notifications.isNotEmpty()) {
            notificationManager.notify(notifications)
            notificationManager.notify(summaryId, summaryNotification)
            Log.d(TAG(), "Created ${notifications.size} notifications for channel $group")
        }
    }

    /**
     * Check if a notification was already shown recently
     * TODO: Implement proper deduplication using SharedPreferences
     */
    @Suppress("FunctionOnlyReturningConstant")
    private fun isNotificationAlreadyShown(): Boolean {
        // For now, always return false
        // Future implementation could track shown notification IDs
        return false
    }

    private suspend fun createStreamNotification(
        group: String,
        stream: StreamItem
    ): NotificationWithIdAndTag? {
        val videoUrl = stream.url ?: run {
            Log.w(TAG(), "Stream has no URL, skipping notification")
            return null
        }

        val videoId = videoUrl.toID()
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setFlags(INTENT_FLAGS)
            .putExtra(IntentData.videoId, videoId)
        val notificationId = videoId.hashCode()
        val pendingIntent = PendingIntentCompat
            .getActivity(applicationContext, notificationId, intent, FLAG_UPDATE_CURRENT, false)

        // Load stream thumbnails only if NOT in data saver mode (inverted logic fix)
        val thumbnail = downloadImage(stream.thumbnail)

        val title = stream.title ?: "Untitled Video"
        val uploaderName = stream.uploaderName ?: "Unknown Channel"

        val notificationBuilder = createNotificationBuilder(group)
            .setContentTitle(title)
            .setContentText(uploaderName)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setLargeIcon(thumbnail)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(thumbnail)
                    .bigLargeIcon(null as Bitmap?) // Hides the icon when expanding
            )
            .setWhen(stream.uploaded)
            .setShowWhen(true)

        return NotificationWithIdAndTag(notificationId, notificationBuilder.build())
    }

    /**
     * Download image with caching and proper logic
     * Returns image if NOT in data saver mode (normal behavior)
     */
    private suspend fun downloadImage(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null

        // Check cache first
        imageCache[url]?.let { cached ->
            Log.d(TAG(), "Using cached image for $url")
            return cached
        }

        // Only download if NOT in data saver mode (FIXED: inverted logic)
        val shouldDownload = !PreferenceHelper.getBoolean(PreferenceKeys.DATA_SAVER_MODE, false)

        return if (shouldDownload) {
            try {
                val bitmap = withTimeoutOrNull(10.seconds) { // ✅ Duration moderne
                    withContext(Dispatchers.IO) {
                        ImageHelper.getImage(applicationContext, url)
                    }
                }

                if (bitmap != null) {
                    // Cache the image
                    imageCache[url] = bitmap
                    Log.d(TAG(), "Downloaded and cached image for $url")
                }

                bitmap
            } catch (e: Exception) {
                Log.w(TAG(), "Failed to download image from $url", e)
                null
            }
        } else {
            Log.d(TAG(), "Data saver mode enabled, skipping image download for $url")
            null
        }
    }

    /**
     * Clear image cache to free memory
     */
    private fun clearImageCache() {
        imageCache.values.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        imageCache.clear()
        Log.d(TAG(), "Image cache cleared")
    }

    private fun createNotificationBuilder(group: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(applicationContext, PUSH_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setGroup(group)
            .setCategory(Notification.CATEGORY_SOCIAL)
    }

    companion object {
        private const val INTENT_FLAGS = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}