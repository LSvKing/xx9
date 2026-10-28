package com.xx9.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xx9.app.data.ApiException
import com.xx9.app.data.HomeBlock
import com.xx9.app.data.VideoItem
import com.xx9.app.data.Xx9Api
import com.xx9.app.ui.ErrorBlock
import com.xx9.app.ui.LoadingBlock
import com.xx9.app.ui.VideoCard
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.Mut

@Composable
fun HomeScreen(
    api: Xx9Api,
    onOpenVideo: (Int) -> Unit,
    onOpenTheme: (id: String?, title: String, source: String?) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var blocks by remember { mutableStateOf<List<HomeBlock>>(emptyList()) }

    fun reload() {
        loading = true
        err = null
    }

    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        runCatching { api.home() }
            .onSuccess { blocks = it; loading = false }
            .onFailure {
                err = when (it) {
                    is ApiException -> "加载失败（${it.code}）：${it.message}"
                    else -> "网络错误：${it.message ?: "请检查服务器地址与网络"}"
                }
                loading = false
            }
    }

    when {
        loading -> LoadingBlock()
        err != null -> ErrorBlock(err!!) { reload(); loading = true }
        blocks.isEmpty() -> ErrorBlock("首页暂无内容") { reload(); loading = true }
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(blocks, key = { "${it.id}-${it.title}" }) { block ->
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(block.title, color = Acc)
                        if (block.id != null) {
                            TextButton(onClick = { onOpenTheme(block.id, block.title, block.source) }) {
                                Text("更多", color = Mut)
                            }
                        }
                    }
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        block.items.take(12).forEach { item: VideoItem ->
                            Column(Modifier.width(148.dp).height(180.dp)) {
                                VideoCard(item, api.coverUrl(item)) { onOpenVideo(item.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}
