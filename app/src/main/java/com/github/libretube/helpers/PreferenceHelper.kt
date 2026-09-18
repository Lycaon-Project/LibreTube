package com.github.libretube.helpers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.TrendingCategory
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.helpers.LocaleHelper.getDetectedCountry
import kotlin.math.roundToInt

object PreferenceHelper {
    private val TAG = PreferenceHelper::class.simpleName

    /**
     * Preference migration from [fromVersion] to [toVersion].
     */
    private class PreferenceMigration(
        val fromVersion: Int, val toVersion: Int, val onMigration: () -> Unit
    )

    /**
     * for normal preferences
     */
    lateinit var settings: SharedPreferences

    /**
     * For sensitive data (like token)
     */
    private lateinit var authSettings: SharedPreferences

    /**
     * Possible chars to use for the SB User ID
     */
    private const val USER_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

    /**
     * Cache for ignorable notification channels to avoid repeated string splitting
     */
    private var ignorableChannelsCache: List<String>? = null

    /**
     * Migrations required to migrate the application to a newer preference version.
     * The version is automatically determined from the number of migrations available.
     */
    private val MIGRATIONS = arrayOf(
        PreferenceMigration(0, 1) {
            LibreTubeApp.instance.resources
                .getStringArray(R.array.sponsorBlockSegments)
                .forEach { category ->
                    val key = "${category}_category"
                    val stored = getString(key, "visible")
                    if (stored == "visible") {
                        putString(key, SbSkipOptions.MANUAL.name.lowercase())
                    }
                }
        },
        PreferenceMigration(1, 2) {
            // select a random category as the new value
            putString(PreferenceKeys.TRENDING_CATEGORY, TrendingCategory.LIVE.name)
        },
        PreferenceMigration(2, 3) {
            // git log -p -- app/src/main/java/com/git_hub/libretube/constants/PreferenceKeys.kt | rg "^-.*const val [A-Z_]+" | awk '{printf("%s,", $6);}' | sort | uniq
            // Batch removal in a single edit operation for better performance
            settings.edit(commit = true) {
                setOf(
                    "player_audio_format",
                    "lbry_hls",
                    "confirm_unsubscribing",
                    "legacy_subscriptions",
                    "legacy_subscriptions_columns",
                    "filter_history_type",
                    "use_hls",
                    "last_stream_video_id",
                    "last_watched_feed_time",
                    "last_feed_refresh_timestamp_millis",
                    "custom_playback_speed",
                    "background_playback_speed",
                    "player_resize_mode",
                    "alternative_videos_layout",
                    "clearCustomInstances",
                    "auth",
                    "image_proxy_url",
                    "dearrow",
                    "unlimited_search_history",
                    "sb_highlights",
                    "audio_only_mode",
                    "sb_contribute_key",
                    "dearrow_contribute_key",
                    "sb_user_id",
                    "fallback_piped_proxy",
                    "picture_in_picture",
                    "pause_on_quit",
                    "save_feed",
                    "filer_feed",
                    "max_concurrent_downloads",
                    "grid",
                    "sleep_timer_toggle",
                    "sleep_timer_delay",
                    "alternative_player_layout",
                    "auto_rotation",
                    "sb_show_markers",
                    "autoplay",
                    "sb_skip_manually_key",
                    "player_screen_brightness",
                    "selected_filer_feed",
                    "selected_feed_filer",
                    "feed_sort_oder",
                    "player_video_format",
                    "watch_position_toggle",
                    "break_reminder_toggle",
                    "break_reminder",
                    "notification_open_queue",
                    "data_saver_mode",
                    "import_from_yt",
                    "export_subs",
                    "show_open_with",
                    "player_swipe_control",
                    "progressive_loading_interval",
                    "limit_hls",
                    "nav_bar_items",
                    "trending_layout",
                    "default_tab",
                    "backup_settings",
                    "restore_settings",
                    "hide_trending_page",
                    "sb_skip_manually",
                    "download_location",
                    "download_folder",
                ).forEach { key -> remove(key) }
            }
        },
        PreferenceMigration(3, 4) {
            // Batch removal in a single edit operation
            settings.edit(commit = true) {
                listOf("video_codecs", "audio_codecs").forEach { remove(it) }
            }
        },
        PreferenceMigration(4, 5) {
            remove("remember_playback_speed")
        },
        PreferenceMigration(5, 6) {
            val currentSpeed = (settings.getString(PreferenceKeys.PLAYBACK_SPEED, null)
                ?: return@PreferenceMigration).replace("F", "").toFloat()
            // round to the nearest .25 playback speed
            val speed = (currentSpeed * 4f).roundToInt() / 4f
            putString(PreferenceKeys.PLAYBACK_SPEED, speed.toString())
        },
        PreferenceMigration(6, 7) {
            remove("disable_video_image_proxy")
        },
        PreferenceMigration(7, 8) {
            // Batch removal in a single edit operation
            settings.edit(commit = true) {
                remove("image_cache_size")
                remove("max_parallel_downloads")
            }
        },
        PreferenceMigration(8, 9) {
            // Batch removal in a single edit operation
            settings.edit(commit = true) {
                remove("label_visibility")
                remove("playback_during_call")
                remove("behavior_when_minimized")
                remove("show_stream_thumbnails")
                remove("watch_history_size")
            }
        },
        PreferenceMigration(9, 10) {
            // Batch removal in a single edit operation
            settings.edit(commit = true) {
                remove("autoplay_playlists")
                remove("queue_insert_related_videos")
            }
        },
        PreferenceMigration(10, 11) {
            remove("skip_buttons")
        }
    )

    /**
     * set the context that is being used to access the shared preferences
     */
    fun initialize(context: Context) {
        settings = getDefaultSharedPreferences(context)
        authSettings = getAuthenticationPreferences(context)
    }

    /**
     * Migrate preference to a new version.
     */
    fun migrate() {
        var currentPrefVersion = getInt(PreferenceKeys.PREFERENCE_VERSION, 0)

        while (currentPrefVersion < MIGRATIONS.count()) {
            val next = currentPrefVersion + 1

            val migration =
                MIGRATIONS.find { it.fromVersion == currentPrefVersion && it.toVersion == next }
            Log.i(TAG, "Performing migration from $currentPrefVersion to $next")
            migration?.onMigration?.invoke()

            currentPrefVersion++
            // mark as successfully migrated
            putInt(PreferenceKeys.PREFERENCE_VERSION, currentPrefVersion)
        }
    }

    /**
     * Write a string value to preferences.
     * Uses apply() for async write to avoid blocking the UI thread.
     */
    fun putString(key: String, value: String) {
        settings.edit { putString(key, value) }
    }

    /**
     * Write a boolean value to preferences.
     * Uses apply() for async write to avoid blocking the UI thread.
     */
    fun putBoolean(key: String, value: Boolean) {
        settings.edit { putBoolean(key, value) }
    }

    /**
     * Write an int value to preferences.
     * Uses apply() for async write to avoid blocking the UI thread.
     */
    fun putInt(key: String, value: Int) {
        settings.edit { putInt(key, value) }
    }

    /**
     * Write a long value to preferences.
     * Uses apply() for async write to avoid blocking the UI thread.
     */
    fun putLong(key: String, value: Long) {
        settings.edit { putLong(key, value) }
    }

    /**
     * Write a string set to preferences.
     * Uses apply() for async write to avoid blocking the UI thread.
     */
    fun putStringSet(key: String, value: Set<String>) {
        settings.edit { putStringSet(key, value) }
    }

    /**
     * Remove a preference key.
     * Uses apply() for async write to avoid blocking the UI thread.
     */
    fun remove(key: String) {
        settings.edit { remove(key) }
    }

    /**
     * Get a string value from preferences.
     * @param key the preference key (must not be null)
     * @param defValue the default value to return if the key is not found
     */
    fun getString(key: String?, defValue: String): String {
        if (key == null) return defValue
        return settings.getString(key, defValue) ?: defValue
    }

    /**
     * Get a boolean value from preferences.
     * @param key the preference key (must not be null)
     * @param defValue the default value to return if the key is not found
     */
    fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key == null) return defValue
        return settings.getBoolean(key, defValue)
    }

    /**
     * Get an int value from preferences.
     * Handles migration from Long to Int gracefully.
     * @param key the preference key (must not be null)
     * @param defValue the default value to return if the key is not found
     */
    fun getInt(key: String?, defValue: Int): Int {
        if (key == null) return defValue
        return runCatching {
            settings.getInt(key, defValue)
        }.getOrElse { settings.getLong(key, defValue.toLong()).toInt() }
    }

    /**
     * Get a long value from preferences.
     * @param key the preference key (must not be null)
     * @param defValue the default value to return if the key is not found
     */
    fun getLong(key: String?, defValue: Long): Long {
        if (key == null) return defValue
        return settings.getLong(key, defValue)
    }

    /**
     * Get a string set from preferences.
     * @param key the preference key (must not be null)
     * @param defValue the default value to return if the key is not found
     */
    fun getStringSet(key: String?, defValue: Set<String>): Set<String> {
        if (key == null) return defValue
        return settings.getStringSet(key, defValue).orEmpty()
    }

    fun clearPreferences() {
        settings.edit { clear() }
        ignorableChannelsCache = null
    }

    fun getToken(): String {
        return authSettings.getString(PreferenceKeys.TOKEN, "")!!
    }

    fun setToken(newValue: String) {
        authSettings.edit { putString(PreferenceKeys.TOKEN, newValue) }
    }

    fun getUsername(): String {
        return authSettings.getString(PreferenceKeys.USERNAME, "")!!
    }

    fun setUsername(newValue: String) {
        authSettings.edit { putString(PreferenceKeys.USERNAME, newValue) }
    }

    fun updateLastFeedWatchedTime(time: Long, seenByUser: Boolean) {
        // only update the time if the time is newer
        // this avoids cases, where the user last saw an older video, which had already been seen,
        // causing all following video to be incorrectly marked as unseen again
        if (getLastCheckedFeedTime(false) < time)
            putLong(PreferenceKeys.LAST_REFRESHED_FEED_TIME, time)

        // this value holds the last time the user opened the subscriptions feed
        // whereas [LAST_REFRESHED_FEED_TIME] considers the last time the feed was loaded,
        // which could also be possible in the background (e.g. via notifications)
        if (seenByUser && getLastCheckedFeedTime(true) < time)
            putLong(PreferenceKeys.LAST_USER_SEEN_FEED_TIME, time)
    }

    fun getLastCheckedFeedTime(seenByUser: Boolean): Long {
        val key =
            if (seenByUser) PreferenceKeys.LAST_USER_SEEN_FEED_TIME else PreferenceKeys.LAST_REFRESHED_FEED_TIME
        return getLong(key, 0)
    }

    fun saveErrorLog(log: String) {
        putString(PreferenceKeys.ERROR_LOG, log)
    }

    fun getErrorLog(): String {
        return getString(PreferenceKeys.ERROR_LOG, "")
    }

    /**
     * Get the list of ignorable notification channels.
     * Uses caching to avoid repeated string splitting.
     */
    fun getIgnorableNotificationChannels(): List<String> {
        ignorableChannelsCache?.let { return it }

        val channels = getString(PreferenceKeys.IGNORED_NOTIFICATION_CHANNELS, "")
            .split(",")
            .filter { it.isNotEmpty() }

        ignorableChannelsCache = channels
        return channels
    }

    fun isChannelNotificationIgnorable(channelId: String): Boolean {
        return getIgnorableNotificationChannels().any { it == channelId }
    }

    /**
     * Toggle whether a channel's notifications should be ignored.
     * Optimized to minimize allocations.
     */
    fun toggleIgnorableNotificationChannel(channelId: String) {
        val currentChannels = getString(PreferenceKeys.IGNORED_NOTIFICATION_CHANNELS, "")
        val channelsList = if (currentChannels.isEmpty()) {
            mutableListOf()
        } else {
            currentChannels.split(",").toMutableList()
        }

        if (channelsList.contains(channelId)) {
            channelsList.remove(channelId)
        } else {
            channelsList.add(channelId)
        }

        val channelsString = channelsList.joinToString(",")
        settings.edit {
            putString(PreferenceKeys.IGNORED_NOTIFICATION_CHANNELS, channelsString)
        }

        // Invalidate cache
        ignorableChannelsCache = null
    }

    /**
     * Get or generate a SponsorBlock user ID.
     * Optimized to minimize allocations using buildString.
     */
    fun getSponsorBlockUserID(): String {
        val existingUuid = getString(PreferenceKeys.SB_USER_ID, "")
        if (existingUuid.isNotEmpty()) return existingUuid

        // generate a new user id to use for submitting SponsorBlock segments
        val newUuid = buildString(30) {
            repeat(30) {
                append(USER_ID_CHARS.random())
            }
        }
        putString(PreferenceKeys.SB_USER_ID, newUuid)
        return newUuid
    }

    fun getTrendingRegion(context: Context): String {
        val regionPref = getString(PreferenceKeys.REGION, "sys")

        // get the system default country if auto region selected
        return if (regionPref == "sys") {
            getDetectedCountry(context).uppercase()
        } else {
            regionPref
        }
    }

    private fun getDefaultSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    private fun getAuthenticationPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PreferenceKeys.AUTH_PREF_FILE, Context.MODE_PRIVATE)
    }
}