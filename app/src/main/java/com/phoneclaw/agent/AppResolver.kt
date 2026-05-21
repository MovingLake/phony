package com.phoneclaw.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

private const val TAG = "AppResolver"

/**
 * Resolves natural app names to installed package names.
 *
 * Strategy:
 * 1. Check well-known aliases (Maps → com.google.android.apps.maps)
 * 2. Fuzzy-match the app name against installed app labels
 * 3. Return the best match's package name
 */
class AppResolver(private val context: Context) {

    // Well-known app name → package name mappings for the most common apps
    private val knownApps = mapOf(
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "navigation" to "com.google.android.apps.maps",
        "waze" to "com.waze",
        "uber" to "com.ubercab",
        "lyft" to "me.lyft.android",

        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "mail" to "com.google.android.gm",
        "outlook" to "com.microsoft.office.outlook",

        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "texting" to "com.google.android.apps.messaging",
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "signal" to "org.thoughtcrime.securesms",
        "discord" to "com.discord",
        "slack" to "com.slack",

        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "call" to "com.google.android.dialer",

        "camera" to "com.google.android.GoogleCamera",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",

        "calendar" to "com.google.android.calendar",
        "google calendar" to "com.google.android.calendar",

        "contacts" to "com.google.android.contacts",

        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "firefox" to "org.mozilla.firefox",
        "safari" to "com.android.chrome",  // redirect to chrome on android

        "youtube" to "com.google.android.youtube",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "amazon prime" to "com.amazon.avod.thirdpartyclient",
        "disney plus" to "com.disney.disneyplus",
        "hulu" to "com.hulu.plus",
        "twitch" to "tv.twitch.android.app",
        "tiktok" to "com.zhiliaoapp.musically",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "reddit" to "com.reddit.frontpage",
        "linkedin" to "com.linkedin.android",

        "notes" to "com.google.android.keep",
        "keep" to "com.google.android.keep",
        "google keep" to "com.google.android.keep",
        "notion" to "com.notion.id",
        "obsidian" to "md.obsidian",

        "clock" to "com.google.android.deskclock",
        "alarm" to "com.google.android.deskclock",
        "timer" to "com.google.android.deskclock",
        "stopwatch" to "com.google.android.deskclock",

        "calculator" to "com.google.android.calculator",

        "settings" to "com.android.settings",
        "wifi" to "com.android.settings",
        "bluetooth" to "com.android.settings",

        "files" to "com.google.android.apps.nbu.files",
        "file manager" to "com.google.android.apps.nbu.files",
        "drive" to "com.google.android.apps.docs",
        "google drive" to "com.google.android.apps.docs",
        "docs" to "com.google.android.apps.docs.editors.docs",
        "sheets" to "com.google.android.apps.docs.editors.sheets",
        "slides" to "com.google.android.apps.docs.editors.slides",

        "weather" to "com.google.android.GoogleCamera",  // falls back to search
        "clock" to "com.google.android.deskclock",

        "play store" to "com.android.vending",
        "app store" to "com.android.vending",
        "google play" to "com.android.vending",
        "amazon" to "com.amazon.mShop.android.shopping",

        "google" to "com.google.android.googlequicksearchbox",
        "search" to "com.google.android.googlequicksearchbox",
        "assistant" to "com.google.android.googlequicksearchbox",
    )

    /**
     * Find the launch intent for a given natural app name.
     * Returns null if no app could be found.
     */
    fun resolve(appName: String): Pair<String, Intent>? {
        val normalized = appName.trim().lowercase()

        // 1. Check known aliases first
        knownApps[normalized]?.let { pkg ->
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                Log.d(TAG, "Resolved '$appName' via known alias → $pkg")
                return Pair(pkg, intent)
            }
        }

        // 2. Search installed apps
        val installedApps = getInstalledApps()

        // Exact label match
        installedApps.firstOrNull { (_, label) ->
            label.lowercase() == normalized
        }?.let { (pkg, label) ->
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                Log.d(TAG, "Resolved '$appName' via exact label match '$label' → $pkg")
                return Pair(pkg, intent)
            }
        }

        // Partial label match
        installedApps.firstOrNull { (_, label) ->
            label.lowercase().contains(normalized) || normalized.contains(label.lowercase())
        }?.let { (pkg, label) ->
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                Log.d(TAG, "Resolved '$appName' via partial label match '$label' → $pkg")
                return Pair(pkg, intent)
            }
        }

        Log.w(TAG, "Could not resolve app: '$appName'")
        return null
    }

    /** Returns list of (packageName, displayLabel) for all launchable apps. */
    private fun getInstalledApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { resolveInfo ->
                Pair(
                    resolveInfo.activityInfo.packageName,
                    resolveInfo.loadLabel(pm).toString()
                )
            }
    }

    /** Returns all installed app labels, for debugging / UI display. */
    fun listAllApps(): List<String> = getInstalledApps().map { it.second }.sorted()
}
