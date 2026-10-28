package com.xx9.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.xx9.app.data.MediaRules
import com.xx9.app.data.VideoItem
import com.xx9.app.cover.CoverRequest
import com.xx9.app.cover.CoverCache
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.AccSoft
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut
import com.xx9.app.ui.theme.Surface
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Acc, fontWeight = FontWeight.Bold)) { append("xx") }
            withStyle(SpanStyle(color = Fg, fontWeight = FontWeight.Bold)) { append("9") }
        },
        fontSize = 20.sp,
        modifier = modifier,
    )
}

@Composable
fun Xx9TopBar(
    title: String? = null,
    showBrand: Boolean = title == null,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
) {
    // §0.2：statusBars inset 在 56dp 栏身之外，避免与状态栏叠层
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon?.invoke()
            if (showBrand) {
                BrandMark(Modifier.padding(start = 8.dp))
            } else if (title != null) {
                Text(
                    title,
                    color = Fg,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (showBrand) Spacer(Modifier.weight(1f))
            if (onSearch != null) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索", tint = Mut)
                }
            }
            if (onSettings != null) {
                TextButton(onClick = onSettings) {
                    Text("设置", color = Mut, fontSize = 13.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

data class BottomTab(val route: String, val label: String, val icon: ImageVector)

val MainTabs = listOf(
    BottomTab("home", "首页", Icons.Filled.Home),
    BottomTab("all", "全部", Icons.Filled.GridView),
    BottomTab("fav", "收藏", Icons.Filled.Star),
    BottomTab("hist", "历史", Icons.Filled.History),
)

@Composable
fun Xx9BottomBar(currentRoute: String, onSelect: (String) -> Unit) {
    // §0.2：navigationBars inset 在 56dp 栏身之外，标签不被手势条裁切
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        NavigationBar(
            containerColor = Surface,
            tonalElevation = 0.dp,
            modifier = Modifier.height(56.dp),
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            MainTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab.route) },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label, fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Acc,
                        selectedTextColor = Acc,
                        unselectedIconColor = Mut,
                        unselectedTextColor = Mut,
                        indicatorColor = AccSoft,
                    ),
                )
            }
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = "重试",
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Mut, fontSize = 12.sp)
        }
        if (onAction != null && actionLabel != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onAction) { Text(actionLabel, color = Acc) }
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Mut, fontSize = 14.sp)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Mut, fontSize = 12.sp)
        }
    }
}

@Composable
fun LoadingBlock() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Acc)
    }
}

/** 封面：短 9:16 / 长 16:10（AMEND-3）。首页短卡宽 132dp。 */
@Composable
fun VideoCard(
    item: VideoItem,
    coverRequest: CoverRequest?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compactHomeShort: Boolean = false,
    coverUrl: String? = null, // 兼容旧调用；优先 coverRequest
) {
    val short = MediaRules.isShort(item)
    val aspect = MediaRules.coverAspect(item.duration)
    val ctx = LocalContext.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .background(Line),
        ) {
            val model = when {
                coverRequest != null -> ImageRequest.Builder(ctx)
                    .data(coverRequest)
                    .memoryCacheKey("cover-${coverRequest.videoId}")
                    .diskCacheKey("cover-${coverRequest.videoId}")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(true)
                    .build()
                !coverUrl.isNullOrBlank() -> coverUrl
                else -> null
            }
            if (model != null) {
                AsyncImage(
                    model = model,
                    imageLoader = CoverCache.imageLoader(ctx),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val dur = item.duration
            if (dur != null && dur > 0) {
                Text(
                    formatDuration(dur),
                    color = Acc,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            item.title,
            color = Fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        val meta = buildList {
            item.readNumber?.let { add(formatReads(it)) }
            item.author?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                color = Mut,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (compactHomeShort && short) {
            // marker for callers; width applied outside
        }
    }
}

fun homeCardWidth(item: VideoItem): Dp =
    if (MediaRules.isShort(item)) 132.dp else 168.dp

@Composable
fun VideoGrid(
    items: List<VideoItem>,
    coverRequestOf: (VideoItem) -> CoverRequest,
    onClick: (VideoItem) -> Unit,
    loadingMore: Boolean = false,
    hasMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    gridState: LazyGridState? = null,
) {
    val state = gridState ?: rememberLazyGridState()
    if (onLoadMore != null) {
        LaunchedEffect(state, items.size, hasMore) {
            snapshotFlow {
                val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= items.size - 4
            }.distinctUntilChanged().collect { nearEnd ->
                if (nearEnd && hasMore && !loadingMore) onLoadMore()
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            VideoCard(item = item, coverRequest = coverRequestOf(item), onClick = { onClick(item) })
        }
        if (loadingMore) {
            item { LoadingBlock() }
        }
    }
}

fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatReads(n: Long): String = when {
    n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
    n >= 1_000 -> String.format("%.1f千", n / 1_000.0)
    else -> n.toString()
}
