package com.xx9.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xx9.app.data.ApiException
import com.xx9.app.data.GroupItem
import com.xx9.app.data.VideoItem
import com.xx9.app.data.Xx9Api
import com.xx9.app.ui.EmptyState
import com.xx9.app.ui.ErrorState
import com.xx9.app.ui.LoadingBlock
import com.xx9.app.ui.VideoGrid
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BrowseScreen(
    api: Xx9Api,
    initialTheme: Int = 0,
    initialTitle: String = "全部",
    source: String = "all",
    forceSearch: Boolean = false,
    emptyTitle: String = "暂无内容",
    emptySubtitle: String? = null,
    onOpenVideo: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("new") }
    var group by remember { mutableIntStateOf(0) }
    var theme by remember { mutableIntStateOf(initialTheme) }
    var groups by remember { mutableStateOf<List<GroupItem>>(emptyList()) }
    var items by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val showFilters = source == "all" && !forceSearch
    val showSearch = source == "all" || forceSearch
    val sorts = listOf("new" to "最新", "hot" to "最热", "long" to "最长")

    fun load(reset: Boolean) {
        scope.launch {
            if (reset) {
                loading = true
                err = null
                page = 1
            } else loadingMore = true
            val p = if (reset) 1 else page + 1
            runCatching {
                api.videos(q = q, group = group, theme = theme, sort = sort, source = source, page = p)
            }.onSuccess { res ->
                items = if (reset) res.items else items + res.items
                page = res.page
                hasMore = res.hasMore
                loading = false
                loadingMore = false
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

    LaunchedEffect(Unit) {
        if (showFilters) runCatching { api.groups() }.onSuccess { groups = it }
    }
    LaunchedEffect(sort, group, theme, source, forceSearch) {
        load(true)
    }

    Column(Modifier.fillMaxSize()) {
        if (showSearch) {
            OutlinedTextField(
                value = q,
                onValueChange = {
                    q = it
                    searchJob?.cancel()
                    searchJob = scope.launch {
                        delay(400)
                        load(true)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("搜索片名…", color = Mut) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Acc,
                    unfocusedBorderColor = Line,
                    focusedTextColor = Fg,
                    unfocusedTextColor = Fg,
                    cursorColor = Acc,
                ),
            )
        }
        if (showFilters) {
            LazyRow(Modifier.padding(horizontal = 8.dp)) {
                items(sorts) { (k, label) ->
                    FilterChip(
                        selected = sort == k,
                        onClick = { sort = k },
                        label = { Text(label) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Acc.copy(alpha = 0.15f),
                            selectedLabelColor = Acc,
                        ),
                    )
                }
                item {
                    FilterChip(
                        selected = group == 0 && theme == 0,
                        onClick = { group = 0; theme = 0 },
                        label = { Text("全部") },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(groups) { g ->
                    FilterChip(
                        selected = group == g.id,
                        onClick = { group = g.id; theme = 0 },
                        label = { Text(g.name) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        when {
            loading -> LoadingBlock()
            err != null && items.isEmpty() -> ErrorState(err!!, "请检查网络后重试") { load(true) }
            items.isEmpty() -> EmptyState(emptyTitle, emptySubtitle)
            else -> VideoGrid(
                items = items,
                coverOf = { api.coverUrl(it) },
                onClick = { onOpenVideo(it.id) },
                loadingMore = loadingMore,
                hasMore = hasMore,
                onLoadMore = { load(false) },
            )
        }
    }
}
