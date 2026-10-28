package com.xx9.app

import android.content.Context

/** Base URL 持久化；改 URL 时清会话标记由调用方处理。 */
object Prefs {
    private const val NAME = "xx9"
    private const val KEY_BASE = "base_url"

    fun baseUrl(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE, "")?.trim().orEmpty()

    fun setBaseUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE, url.trim().trimEnd('/'))
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 校验并规范化：必须带 http/https。 */
    fun normalizeOrNull(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null
        return t
    }
}
