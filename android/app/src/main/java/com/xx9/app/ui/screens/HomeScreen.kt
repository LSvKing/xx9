package com.xx9.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xx9.app.data.Xx9Api
import com.xx9.app.ui.EmptyState
import com.xx9.app.ui.ErrorState
import com.xx9.app.ui.LoadingBlock
import com.xx9.app.ui.VideoCard
import com.xx9.app.ui.home.HomeViewModel
import com.xx9.app.ui.homeCardWidth
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Mut

@Composable
fun HomeScreen(
    api: Xx9Api,
    vm: HomeViewModel,
    onOpenVideo: (Int) -> Unit,
    onOpenTheme: (id: String?, title: String, source: String?) -> Unit,
) {
    when {
        vm.loading && vm.blocks.isEmpty() -> LoadingBlock()
        vm.err != null && vm.blocks.isEmpty() -> ErrorState(
            title = vm.err!!,
            subtitle = "请检查网络后重试",
            onAction = { vm.refresh(force = true) },
        )
        vm.blocks.isEmpty() -> EmptyState("暂无专题", "请确认 Base URL 与服务是否可用")
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(vm.blocks, key = { "${it.id}-${it.title}" }) { block ->
                Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(block.title, color = Fg, fontSize = 16.sp)
                            Text("专题", color = Mut, fontSize = 12.sp)
                        }
                        if (block.id != null || block.source != null) {
                            TextButton(onClick = { onOpenTheme(block.id, block.title, block.source) }) {
                                Text("更多", color = Acc, fontSize = 13.sp)
                            }
                        }
                    }
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        block.items.take(12).forEach { item ->
                            VideoCard(
                                item = item,
                                coverRequest = api.coverRequest(item),
                                onClick = { onOpenVideo(item.id) },
                                modifier = Modifier.width(homeCardWidth(item)),
                                compactHomeShort = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
