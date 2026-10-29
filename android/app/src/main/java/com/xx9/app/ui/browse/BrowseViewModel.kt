package com.xx9.app.ui.browse

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xx9.app.data.ApiException
import com.xx9.app.data.GroupItem
import com.xx9.app.data.VideoItem
import com.xx9.app.data.Xx9Api
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 保活浏览/搜索页：query、筛选、列表与滚动位置随 NavBackStackEntry 存活，
 * 进播放再 pop 回来不丢词、不误刷空查询默认列表（对齐 HomeViewModel）。
 */
class BrowseViewModel(
    private val api: Xx9Api,
    val source: String,
    initialTheme: Int,
    val forceSearch: Boolean,
) : ViewModel() {
    var q by mutableStateOf("")
        private set
    var sort by mutableStateOf("new")
        private set
    var group by mutableIntStateOf(0)
        private set
    var theme by mutableIntStateOf(initialTheme)
        private set
    var groups by mutableStateOf<List<GroupItem>>(emptyList())
        private set
    var items by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var page by mutableIntStateOf(1)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var err by mutableStateOf<String?>(null)
        private set

    val showFilters: Boolean = source == "all" && !forceSearch
    val showSearch: Boolean = source == "all" || forceSearch
    val gridState = LazyGridState()

    private var searchJob: Job? = null
    private var loadJob: Job? = null
    /** 是否已完成至少一次加载；返回时不再因重组误触发空查询。 */
    private var bootstrapped = false

    init {
        if (showFilters) {
            viewModelScope.launch {
                runCatching { api.groups() }.onSuccess { groups = it }
            }
        }
        // 首次进入：forceSearch 空词不拉默认全库，避免「空栏+不明列表」；
        // 其它列表页照常首刷。
        if (!forceSearch) {
            bootstrapIfNeeded()
        } else {
            bootstrapped = true
            loading = false
        }
    }

    private fun bootstrapIfNeeded() {
        if (bootstrapped) return
        bootstrapped = true
        load(reset = true)
    }

    fun onQueryChange(value: String) {
        q = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            bootstrapped = true
            load(reset = true, scrollTop = true)
        }
    }

    fun selectSort(value: String) {
        if (sort == value) return
        sort = value
        load(reset = true, scrollTop = true)
    }

    fun setGroupTheme(groupId: Int, themeId: Int) {
        if (group == groupId && theme == themeId) return
        group = groupId
        theme = themeId
        load(reset = true, scrollTop = true)
    }

    fun load(reset: Boolean, scrollTop: Boolean = false) {
        if (!reset && (loadingMore || loading || !hasMore)) return
        // 搜索页空词：不请求，清空结果（避免默认全库冒充搜索结果）
        if (forceSearch && q.isBlank()) {
            loadJob?.cancel()
            items = emptyList()
            page = 1
            hasMore = false
            loading = false
            loadingMore = false
            err = null
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (reset) {
                loading = true
                err = null
                page = 1
                if (scrollTop) {
                    // requestScrollToItem 是非挂起 API（登记目标位置）：grid 尚未组合时
                    // scrollToItem 会 waitForFirstMeasure 永久挂死 —— 搜索页空 items 即中招。
                    runCatching { gridState.requestScrollToItem(0) }
                }
            } else {
                loadingMore = true
            }
            val p = if (reset) 1 else page + 1
            runCatching {
                api.videos(q = q, group = group, theme = theme, sort = sort, source = source, page = p)
            }.onSuccess { res ->
                items = if (reset) res.items else items + res.items
                page = res.page
                hasMore = res.hasMore
                loading = false
                loadingMore = false
                err = null
            }.onFailure {
                err = when (it) {
                    is ApiException -> "无法连接服务器"
                    else -> "网络异常"
                }
                loading = false
                loadingMore = false
            }
        }
    }

    fun loadMore() = load(reset = false)

    fun retry() = load(reset = true, scrollTop = false)

    class Factory(
        private val api: Xx9Api,
        private val source: String = "all",
        private val initialTheme: Int = 0,
        private val forceSearch: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowseViewModel(api, source, initialTheme, forceSearch) as T
    }
}
