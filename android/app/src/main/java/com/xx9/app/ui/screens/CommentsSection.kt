package com.xx9.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xx9.app.data.CommentItem
import com.xx9.app.data.Xx9Api
import com.xx9.app.ui.EmptyState
import com.xx9.app.ui.LoadingBlock
import com.xx9.app.ui.formatDate
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut
import com.xx9.app.ui.theme.Surface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val PREVIEW_COUNT = 3
private const val PAGE_SIZE = 12

/** 播放器信息区下的评论块：头部「评论 · N」+ 预览 3 条 + 查看全部。 */
@Composable
fun CommentsSection(api: Xx9Api, videoId: Int, dbCommentCount: Int?) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(dbCommentCount ?: 0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(videoId, dbCommentCount) {
        // DB 计数为 0 → 上游必然空：直接短路，不为空评论区占 pace 队（详情提速）
        if (dbCommentCount != null && dbCommentCount <= 0) {
            items = emptyList(); total = 0; loading = false; failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        runCatching { api.comments(videoId, page = 1, pageSize = PAGE_SIZE) }
            .onSuccess {
                items = it.items
                total = it.total
            }
            .onFailure { failed = true }
        loading = false
    }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("评论", color = Fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (total > 0) Text("$total", color = Mut, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Acc, modifier = Modifier.size(22.dp))
            }
            failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("评论加载失败", color = Mut, fontSize = 12.sp)
                TextButton(onClick = {
                    scope.launch {
                        loading = true; failed = false
                        runCatching { api.comments(videoId, page = 1, pageSize = PAGE_SIZE) }
                            .onSuccess { items = it.items; total = it.total }
                            .onFailure { failed = true }
                        loading = false
                    }
                }) { Text("重试", color = Acc, fontSize = 12.sp) }
            }
            items.isEmpty() -> Text("还没有评论", color = Mut, fontSize = 12.sp)
            else -> {
                items.take(PREVIEW_COUNT).forEach { c ->
                    CommentRow(c)
                    HorizontalDivider(color = Line, thickness = 0.5.dp)
                }
                if (total > items.size || total > PREVIEW_COUNT) {
                    TextButton(onClick = { showAll = true }) {
                        Text("查看全部 $total 条评论", color = Acc, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showAll) {
        CommentSheet(api = api, videoId = videoId, initialTotal = total, onClose = { showAll = false })
    }
}

@Composable
private fun CommentRow(c: CommentItem, indent: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 44.dp else 0.dp, top = 10.dp, bottom = 2.dp)) {
        if (c.avatar != null) {
            AsyncImage(
                model = c.avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (indent) 24.dp else 32.dp)
                    .clip(CircleShape)
                    .background(Line),
            )
        } else {
            Box(
                Modifier
                    .size(if (indent) 24.dp else 32.dp)
                    .clip(CircleShape)
                    .background(Line),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.author.ifBlank { "匿名" },
                    color = Mut, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                c.createTime?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(formatDate(it).take(10), color = Mut, fontSize = 11.sp)
                }
            }
            Text(c.content, color = Fg, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Outlined.ThumbUp, contentDescription = null, tint = Mut,
                    modifier = Modifier.size(13.dp))
                if (c.likeNum > 0) {
                    Text("${c.likeNum}", color = Mut, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
                }
            }
        }
    }
}

/** 单条评论 + 可展开回复。 */
@Composable
private fun CommentNode(
    api: Xx9Api,
    videoId: Int,
    c: CommentItem,
    expanded: MutableMap<Long, List<CommentItem>>,
    replyState: MutableMap<Long, Pair<Int, Boolean>>, // cid -> (loadedPages, hasMore)
) {
    val scope = rememberCoroutineScope()
    Column {
        CommentRow(c)
        if (c.childNum > 0) {
            val replies = expanded[c.id]
            if (replies == null) {
                Text(
                    "查看 ${c.childNum} 条回复",
                    color = Acc, fontSize = 12.sp,
                    modifier = Modifier
                        .padding(start = 42.dp, top = 4.dp)
                        .clickable {
                            scope.launch {
                                runCatching { api.commentReplies(videoId, c.id, page = 1, pageSize = PAGE_SIZE) }
                                    .onSuccess {
                                        expanded[c.id] = it.items
                                        replyState[c.id] = it.page to it.hasMore
                                    }
                            }
                        },
                )
            } else {
                replies.forEach { r -> CommentRow(r, indent = true) }
                val st = replyState[c.id]
                if (st != null && st.second) {
                    Text(
                        "加载更多回复",
                        color = Acc, fontSize = 12.sp,
                        modifier = Modifier
                            .padding(start = 42.dp, top = 4.dp)
                            .clickable {
                                scope.launch {
                                    runCatching {
                                        api.commentReplies(videoId, c.id, page = st.first + 1, pageSize = PAGE_SIZE)
                                    }.onSuccess {
                                        expanded[c.id] = replies + it.items
                                        replyState[c.id] = it.page to it.hasMore
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentSheet(api: Xx9Api, videoId: Int, initialTotal: Int, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    var items by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(initialTotal) }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<Long, List<CommentItem>>() }
    val replyState = remember { mutableStateMapOf<Long, Pair<Int, Boolean>>() }

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        err = null
        scope.launch {
            runCatching { api.comments(videoId, page = page + 1, pageSize = PAGE_SIZE) }
                .onSuccess {
                    items = items + it.items
                    page = it.page
                    total = it.total
                    hasMore = it.hasMore
                }
                .onFailure { err = "网络异常" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadMore() }

    // 滚动到底自动加载
    LaunchedEffect(listState) {
        snapshotFlowCompat(listState) { it >= items.size - 3 }
            .collect { near -> if (near) loadMore() }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = Surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("全部评论 · $total", color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Fg)
                }
            }
            when {
                items.isEmpty() && loading -> LoadingBlock()
                items.isEmpty() && err != null -> EmptyState("加载失败", "请检查网络后重试")
                items.isEmpty() -> EmptyState("还没有评论")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    items(items, key = { it.id }) { c ->
                        CommentNode(api, videoId, c, expanded, replyState)
                        HorizontalDivider(color = Line, thickness = 0.5.dp)
                    }
                    if (loading) {
                        item { LoadingBlock() }
                    } else if (err != null) {
                        item {
                            TextButton(onClick = { loadMore() }) { Text("加载失败，点击重试", color = Acc) }
                        }
                    }
                }
            }
        }
    }
}

/** 读 LazyListState 最后可见 index → 是否接近尾部。 */
private fun snapshotFlowCompat(
    state: androidx.compose.foundation.lazy.LazyListState,
    nearEnd: (Int) -> Boolean,
) = snapshotFlow {
    val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    nearEnd(last)
}.distinctUntilChanged()
