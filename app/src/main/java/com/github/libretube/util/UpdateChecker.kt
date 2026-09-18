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
     * Parse version string to comparable integer
     * Example: "0.21.1" -> 211, "1.2.3" -> 123
     */
    private fun parseVersionToCode(versionName: String): Int {
        return try {
            val cleanVersion = versionName.filter { char -> char.isDigit() }
            if (cleanVersion.isNotEmpty()) {
                cleanVersion.toInt()
            } else {
                Log.w(TAG(), "Failed to parse version: $versionName")
                0
            }
        } catch (e: NumberFormatException) {
            Log.w(TAG(), "Failed to parse version: $versionName", e)
            0
        }
    }

    suspend fun checkUpdate(isManualCheck: Boolean = false) {
        val currentVersionName = getCurrentVersionName()
        val currentAppVersion = parseVersionToCode(currentVersionName)

        if (currentAppVersion == 0) {
            Log.e(TAG(), "Failed to determine current app version")
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.error)
            }
            return
        }

        try {
            val response = withContext(Dispatchers.IO) {
                RetrofitInstance.externalApi.getLatestRelease()
            }

            val latestVersionName = response.name
            if (latestVersionName.isEmpty()) {  // ✅ .isEmpty() au lieu de .isNullOrEmpty()
                Log.w(TAG(), "Latest release has no version name")
                return
            }

            val latestVersionCode = parseVersionToCode(latestVersionName)

            if (latestVersionCode == 0) {
                Log.w(TAG(), "Failed to parse latest version: $latestVersionName")
                if (isManualCheck) {
                    context.toastFromMainDispatcher(R.string.error)
                }
                return
            }

            // Compare versions
            if (currentAppVersion != latestVersionCode) {
                Log.i(TAG(), "Update available: current=$currentAppVersion, latest=$latestVersionCode")

                withContext(Dispatchers.Main) {
                    // ✅ Accès direct sans .orEmpty() car les types sont non-null
                    showUpdateAvailableDialog(
                        response.body,
                        response.htmlUrl
                    )
                }
            } else if (isManualCheck) {
                Log.i(TAG(), "App is up to date (version $currentAppVersion)")
                context.toastFromMainDispatcher(R.string.app_uptodate)
            }
        } catch (e: Exception) {
            Log.e(TAG(), "Failed to check for updates", e)
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.error)
            }
        }
    }

    private fun showUpdateAvailableDialog(
        changelog: String,
        url: String
    ) {
        // Validate URL for security
        if (!isValidUrl(url)) {
            Log.w(TAG(), "Invalid update URL: $url")
            return
        }

        val dialog = UpdateAvailableDialog()
        val args = Bundle().apply {
            putString(appUpdateChangelog, sanitizeChangelog(changelog))
            putString(appUpdateURL, url)
        }
        dialog.arguments = args

        val fragmentManager = (context as? FragmentActivity)?.supportFragmentManager
        if (fragmentManager != null) {  // ✅ Vérification explicite au lieu de ? let
            dialog.show(fragmentManager, UpdateAvailableDialog::class.java.simpleName)
        } else {
            Log.w(TAG(), "Cannot show dialog: context is not a FragmentActivity")
        }
    }

    /**
     * Validate URL to prevent malicious redirects
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