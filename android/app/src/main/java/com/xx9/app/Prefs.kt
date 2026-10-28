package com.xx9.app

import android.content.Context

/** Base URL 持久化；首次默认线上后端，设置可改。 */
object Prefs {
    private const val NAME = "xx9"
    private const val KEY_BASE = "base_url"
    private const val KEY_SETUP_DONE = "setup_done"

    const val DEFAULT_BASE_URL = "http://49.232.9.67:8000"

    fun baseUrl(ctx: Context): String {
        val stored = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE, null)?.trim().orEmpty()
        return if (stored.isNotEmpty()) stored else DEFAULT_BASE_URL
    }

    fun setBaseUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE, url.trim().trimEnd('/'))
            .putBoolean(KEY_SETUP_DONE, true)
            .apply()
    }

    fun setupDone(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_DONE, false)

    fun markSetupDone(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun normalizeOrNull(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null
        return t
    }
}
