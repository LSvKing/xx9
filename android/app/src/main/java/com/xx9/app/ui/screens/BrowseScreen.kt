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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xx9.app.data.Xx9Api
import com.xx9.app.ui.EmptyState
import com.xx9.app.ui.ErrorState
import com.xx9.app.ui.LoadingBlock
import com.xx9.app.ui.VideoGrid
import com.xx9.app.ui.browse.BrowseViewModel
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut

@Composable
fun BrowseScreen(
    api: Xx9Api,
    vm: BrowseViewModel,
    emptyTitle: String = "暂无内容",
    emptySubtitle: String? = null,
    onOpenVideo: (Int) -> Unit,
) {
    val sorts = listOf("new" to "最新", "hot" to "最热", "long" to "最长")

    Column(Modifier.fillMaxSize()) {
        if (vm.showSearch) {
            OutlinedTextField(
                value = vm.q,
                onValueChange = { vm.onQueryChange(it) },
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
        if (vm.showFilters) {
            LazyRow(Modifier.padding(horizontal = 8.dp)) {
                items(sorts) { (k, label) ->
                    FilterChip(
                        selected = vm.sort == k,
                        onClick = { vm.selectSort(k) },
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
                        selected = vm.group == 0 && vm.theme == 0,
                        onClick = { vm.setGroupTheme(0, 0) },
                        label = { Text("全部") },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(vm.groups) { g ->
                    FilterChip(
                        selected = vm.group == g.id,
                        onClick = { vm.setGroupTheme(g.id, 0) },
                        label = { Text(g.name) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        when {
            vm.loading -> LoadingBlock()
            vm.err != null && vm.items.isEmpty() -> ErrorState(vm.err!!, "请检查网络后重试") { vm.retry() }
            vm.items.isEmpty() -> EmptyState(
                if (vm.forceSearch && vm.q.isBlank()) "输入关键词搜索" else emptyTitle,
                if (vm.forceSearch && vm.q.isBlank()) null else emptySubtitle,
            )
            else -> VideoGrid(
                items = vm.items,
                coverRequestOf = { api.coverRequest(it) },
                onClick = { onOpenVideo(it.id) },
                loadingMore = vm.loadingMore,
                hasMore = vm.hasMore,
                onLoadMore = { vm.loadMore() },
                gridState = vm.gridState,
            )
        }
    }
}
