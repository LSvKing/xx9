package com.xx9.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 直调 FastAPI。请求仍带 X-XX9-App:1（可选旁路）；服务端 auth_open=true 全免密时无需密码、也不依赖该头。
 * 优先 POST /api/app/session；404/405 忽略。无密码 UI。
 */
class Xx9Api(private val baseUrlProvider: () -> String) {
    private val jar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host].orEmpty()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun base(): String = baseUrlProvider().trimEnd('/')

    fun absoluteUrl(pathOrUrl: String?): String? {
        if (pathOrUrl.isNullOrBlank()) return null
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
        return base() + (if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl")
    }

    fun coverUrl(item: VideoItem): String? =
        absoluteUrl(item.coverLocal) ?: absoluteUrl(item.cover)

    private fun req(path: String): Request.Builder {
        val url = if (path.startsWith("http")) path else base() + path
        return Request.Builder()
            .url(url)
            .header("X-XX9-App", "1")
            .header("Accept", "application/json")
    }

    private fun execute(builder: Request.Builder): String {
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw ApiException(resp.code, detail?.takeIf { it.isNotBlank() } ?: body.take(200).ifBlank { "HTTP ${resp.code}" })
            }
            return body
        }
    }

    suspend fun ensureSession() = withContext(Dispatchers.IO) {
        // 尝试 App 会话；405/404 表示旧后端未部署 — 后续靠信任头
        runCatching {
            execute(
                req("/api/app/session").post("{}".toRequestBody("application/json".toMediaType())),
            )
        }
        Unit
    }

    suspend fun me(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(execute(req("/api/me").get()))
    }

    suspend fun home(): List<HomeBlock> = withContext(Dispatchers.IO) {
        ensureSession()
        val raw = execute(req("/api/home").get())
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    HomeBlock(
                        id = when {
                            !o.has("id") || o.isNull("id") -> null
                            else -> o.get("id").toString()
                        },
                        title = o.optString("title", o.optString("name", "专题")),
                        source = o.optString("source").takeIf { it.isNotBlank() },
                        items = parseItems(o.optJSONArray("items") ?: o.optJSONArray("videos") ?: JSONArray()),
                    ),
                )
            }
        }
    }

    suspend fun videos(
        q: String = "",
        group: Int = 0,
        theme: Int = 0,
        sort: String = "new",
        source: String = "all",
        page: Int = 1,
        pageSize: Int = 30,
    ): VideoPage = withContext(Dispatchers.IO) {
        ensureSession()
        val url = base().toHttpUrl().newBuilder()
            .addPathSegments("api/videos")
            .addQueryParameter("q", q)
            .addQueryParameter("group", group.toString())
            .addQueryParameter("theme", theme.toString())
            .addQueryParameter("sort", sort)
            .addQueryParameter("source", source)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .build()
        val o = JSONObject(execute(Request.Builder().url(url).header("X-XX9-App", "1").header("Accept", "application/json").get()))
        VideoPage(
            page = o.optInt("page", page),
            pageSize = o.optInt("page_size", pageSize),
            items = parseItems(o.optJSONArray("items") ?: JSONArray()),
            hasMore = o.optBoolean("has_more", false),
        )
    }

    suspend fun video(id: Int): VideoDetail = withContext(Dispatchers.IO) {
        ensureSession()
        val o = JSONObject(execute(req("/api/video/$id").get()))
        val linesArr = o.optJSONArray("lines") ?: JSONArray()
        val lines = buildList {
            for (i in 0 until linesArr.length()) {
                val ln = linesArr.getJSONObject(i)
                add(PlayLine(ln.optString("name", "线路${i + 1}"), ln.optString("url")))
            }
        }
        val tagsArr = o.optJSONArray("tags") ?: JSONArray()
        val tags = buildList {
            for (i in 0 until tagsArr.length()) add(tagsArr.optString(i))
        }
        VideoDetail(
            id = o.getInt("id"),
            title = o.optString("title"),
            cover = o.optString("cover").takeIf { it.isNotBlank() },
            duration = o.optInt("duration").takeIf { o.has("duration") },
            author = o.optString("author").takeIf { it.isNotBlank() },
            lines = lines,
            fav = o.optBoolean("fav", false),
            tags = tags,
        )
    }

    suspend fun groups(): List<GroupItem> = withContext(Dispatchers.IO) {
        ensureSession()
        val arr = JSONArray(execute(req("/api/groups").get()))
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(GroupItem(o.getInt("id"), o.optString("name", "分类")))
            }
        }
    }

    suspend fun toggleFavorite(id: Int): Boolean = withContext(Dispatchers.IO) {
        ensureSession()
        val o = JSONObject(execute(req("/api/favorite/$id").post("".toRequestBody(null))))
        o.optBoolean("fav", false)
    }

    suspend fun recordHistory(id: Int, position: Int = 0) = withContext(Dispatchers.IO) {
        ensureSession()
        execute(req("/api/history/$id?position=$position").post("".toRequestBody(null)))
        Unit
    }

    private fun parseItems(arr: JSONArray): List<VideoItem> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(
                VideoItem(
                    id = o.getInt("id"),
                    title = o.optString("title"),
                    coverLocal = o.optString("cover_local").takeIf { it.isNotBlank() },
                    cover = o.optString("cover").takeIf { it.isNotBlank() },
                    duration = o.optInt("duration").takeIf { o.has("duration") && !o.isNull("duration") },
                    author = o.optString("author").takeIf { it.isNotBlank() },
                    readNumber = o.optLong("readNumber").takeIf { o.has("readNumber") },
                ),
            )
        }
    }
}

class ApiException(val code: Int, message: String) : Exception(message)
