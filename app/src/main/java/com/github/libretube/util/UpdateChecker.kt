package com.github.libretube.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.IntentData.appUpdateChangelog
import com.github.libretube.constants.IntentData.appUpdateURL
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.ui.dialogs.UpdateAvailableDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class UpdateChecker(private val context: Context) {

    /**
     * Get the current app version name using PackageInfo
     */
    private fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG(), "Failed to get package info", e)
            "0.0.0"
        }
    }

    /**
     * Parse version string to comparable integer using weighted algorithm
     * Examples:
     *   "0.26.1" -> 26001
     *   "1.2.3"  -> 1002003
     *   "10.0.0" -> 10000000
     *
     * This ensures correct comparison even with multi-digit version numbers
     */
    private fun parseVersionToCode(versionName: String): Int {
        return try {
            val cleanVersion = versionName.trim()

            // Try semantic versioning first (e.g., "0.26.1")
            val parts = cleanVersion.split(".")
            if (parts.size >= 2) {
                var code = 0
                var multiplier = 1000000  // Start with high multiplier

                for (part in parts.take(3)) {  // Max 3 parts: major.minor.patch
                    val number = part.filter { it.isDigit() }.toIntOrNull() ?: 0
                    code += number * multiplier
                    multiplier /= 1000  // Decrease multiplier for next part
                }

                return code
            }

            // Fallback: extract all digits
            val digits = cleanVersion.filter { it.isDigit() }
            if (digits.isNotEmpty()) {
                digits.toIntOrNull() ?: 0
            } else {
                Log.w(TAG(), "Failed to parse version: $versionName")
                0
            }
        } catch (e: NumberFormatException) {
            Log.w(TAG(), "Failed to parse version: $versionName", e)
            0
        } catch (e: Exception) {
            Log.e(TAG(), "Unexpected error parsing version: $versionName", e)
            0
        }
    }

    suspend fun checkUpdate(isManualCheck: Boolean = false) {
        val currentVersionName = getCurrentVersionName()
        val currentAppVersion = parseVersionToCode(currentVersionName)

        if (currentAppVersion == 0) {
            Log.e(TAG(), "Failed to determine current app version")
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.error_occurred)
            }
            return
        }

        try {
            val response = withContext(Dispatchers.IO) {
                RetrofitInstance.externalApi.getLatestRelease()
            }

            val latestVersionName = response.name

            // ✅ CORRECTION : isEmpty() au lieu de isNullOrEmpty()
            // car response.name est de type String non-nullable
            if (latestVersionName.isEmpty()) {
                Log.w(TAG(), "Latest release has no version name")
                if (isManualCheck) {
                    context.toastFromMainDispatcher(R.string.error_occurred)
                }
                return
            }

            val latestVersionCode = parseVersionToCode(latestVersionName)

            if (latestVersionCode == 0) {
                Log.w(TAG(), "Failed to parse latest version: $latestVersionName")
                if (isManualCheck) {
                    context.toastFromMainDispatcher(R.string.error_occurred)
                }
                return
            }

            // ✅ CORRECTION CRITIQUE : Utiliser < au lieu de != pour ne proposer
            // une mise à jour que si la version GitHub est PLUS RÉCENTE
            when {
                currentAppVersion < latestVersionCode -> {
                    Log.i(
                        TAG(),
                        "Update available: current=$currentAppVersion ($currentVersionName), " +
                                "latest=$latestVersionCode ($latestVersionName)"
                    )

                    withContext(Dispatchers.Main) {
                        showUpdateAvailableDialog(
                            response.body,
                            response.htmlUrl
                        )
                    }
                }

                currentAppVersion > latestVersionCode -> {
                    Log.i(
                        TAG(),
                        "Running newer version than GitHub release: " +
                                "current=$currentAppVersion, latest=$latestVersionCode"
                    )
                    if (isManualCheck) {
                        context.toastFromMainDispatcher(R.string.app_uptodate)
                    }
                }

                else -> {
                    Log.i(TAG(), "App is up to date (version $currentAppVersion)")
                    if (isManualCheck) {
                        context.toastFromMainDispatcher(R.string.app_uptodate)
                    }
                }
            }
        } catch (e: retrofit2.HttpException) {
            Log.e(TAG(), "HTTP error during update check: ${e.code()} - ${e.message()}", e)
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.error_occurred)
            }
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG(), "No internet connection during update check", e)
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.turnInternetOn)
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG(), "Timeout during update check", e)
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.error_occurred)
            }
        } catch (e: Exception) {
            Log.e(TAG(), "Failed to check for updates", e)
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.error_occurred)
            }
        }
    }

    private fun showUpdateAvailableDialog(
        changelog: String?,
        url: String?
    ) {
        // Validate URL for security
        if (url == null || !isValidUrl(url)) {
            Log.w(TAG(), "Invalid or null update URL: $url")
            return
        }

        val dialog = UpdateAvailableDialog()
        val args = Bundle().apply {
            putString(appUpdateChangelog, sanitizeChangelog(changelog ?: ""))
            putString(appUpdateURL, url)
        }
        dialog.arguments = args

        val fragmentManager = (context as? FragmentActivity)?.supportFragmentManager
        if (fragmentManager != null) {
            dialog.show(fragmentManager, UpdateAvailableDialog::class.java.simpleName)
        } else {
            Log.w(TAG(), "Cannot show dialog: context is not a FragmentActivity")
        }
    }

    /**
     * Validate URL to prevent malicious redirects
     * Only allows HTTPS URLs from GitHub domain
     */
    private fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false

        return try {
            val uri = url.toUri()
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)

            // Only allow HTTPS
            scheme == "https" &&
                    // Only allow GitHub domain
                    (host == "github.com" || host?.endsWith(".github.com") == true)
        } catch (e: Exception) {
            Log.w(TAG(), "Failed to parse URL: $url", e)
            false
        }
    }

    /**
     * Sanitize changelog text for display in the dialog
     * Removes GitHub-specific formatting and cleans up the text
     */
    private fun sanitizeChangelog(changelog: String): String {
        if (changelog.isBlank()) return ""

        return changelog
            .substringBeforeLast("**Full Changelog**")
            .replace(Regex("in https://github\\.com/\\S+"), "")
            .lines()
            .joinToString("\n") { line ->
                if (line.startsWith("##")) {
                    line.uppercase(Locale.ROOT) + " :"
                } else {
                    line
                }
            }
            .replace("## ", "")
            .replace(">", "")
            .replace("*", "•")
            .lines()
            .joinToString("\n") { line -> line.trim() }
            .trim()
    }
}