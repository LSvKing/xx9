package com.xx9.app.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import com.xx9.app.Xx9App
import com.xx9.app.cast.CastState
import com.xx9.app.data.ApiException
import com.xx9.app.data.VideoDetail
import com.xx9.app.data.Xx9Api
import com.xx9.app.ui.ErrorBlock
import com.xx9.app.ui.LoadingBlock
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.Bg
import com.xx9.app.ui.theme.CastBlock
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Mut
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    videoId: Int,
    api: Xx9Api,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Xx9App
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<VideoDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var lineIndex by remember { mutableIntStateOf(0) }
    var playErr by remember { mutableStateOf<String?>(null) }
    var showCast by remember { mutableStateOf(false) }
    var castState by remember { mutableStateOf<CastState>(app.castManager.state) }
    var fav by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.castManager.let { /* snapshot */ }
    }

    // poll cast state lightly via reassignment when sheet opens / actions
    fun refreshCast() { castState = app.castManager.state }

    LaunchedEffect(videoId) {
        loading = true
        err = null
        runCatching { api.video(videoId) }
            .onSuccess {
                detail = it
                fav = it.fav
                lineIndex = 0
                loading = false
                runCatching { api.recordHistory(videoId) }
            }
            .onFailure {
                err = when (it) {
                    is ApiException -> "详情失败（${it.code}）：${it.message}"
                    else -> "网络错误：${it.message ?: "未知"}"
                }
                loading = false
            }
    }

    val exo = remember {
        ExoPlayer.Builder(context).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playErr = "播放失败：${error.message ?: error.errorCodeName}"
                }
            })
        }
    }
    DisposableEffect(Unit) {
        onDispose { exo.release() }
    }

    val lines = detail?.lines.orEmpty()
    LaunchedEffect(detail, lineIndex) {
        val url = lines.getOrNull(lineIndex)?.url
        playErr = null
        if (url.isNullOrBlank()) {
            if (detail != null) playErr = "无可用播放线路"
            return@LaunchedEffect
        }
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent("xx9-android-native/2.0")
            .setAllowCrossProtocolRedirects(true)
        val source = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Fg)
            }
            Text(
                detail?.title ?: "播放",
                color = Fg,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                scope.launch {
                    runCatching { api.toggleFavorite(videoId) }.onSuccess { fav = it }
                }
            }) {
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (fav) CastBlock else Fg,
                )
            }
            IconButton(onClick = { refreshCast(); showCast = true }) {
                Icon(Icons.Filled.Cast, contentDescription = "投屏", tint = Acc)
            }
        }

        when {
            loading -> LoadingBlock()
            err != null -> ErrorBlock(err!!) { /* reload via parent */ }
            else -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(androidx.compose.ui.graphics.Color.Black),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exo
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                useController = true
                            }
                        },
                        update = { it.player = exo },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (playErr != null) {
                    Text(playErr!!, color = CastBlock, modifier = Modifier.padding(12.dp))
                    if (lineIndex + 1 < lines.size) {
                        TextButton(onClick = { lineIndex += 1; playErr = null }) {
                            Text("切换下一线路", color = Acc)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("线路", color = Mut, modifier = Modifier.padding(horizontal = 12.dp))
                LazyRow(
                    Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(lines) { idx, ln ->
                        FilterChip(
                            selected = idx == lineIndex,
                            onClick = { lineIndex = idx },
                            label = { Text(ln.name) },
                        )
                    }
                }
                val st = castState
                if (st is CastState.Playing) {
                    Text(
                        "投屏中 · ${st.deviceName}",
                        color = Acc,
                        modifier = Modifier.padding(12.dp),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            // 切线后显式同步
                            val url = lines.getOrNull(lineIndex)?.url
                            val d = app.castManager.currentDevice
                            if (url != null && d != null) {
                                app.castManager.castTo(d, url)
                                refreshCast()
                            }
                        }
                    }) { Text("同步到电视", color = Acc) }
                }
                if (st is CastState.Failed) {
                    Text(st.message, color = CastBlock, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    if (showCast) {
        CastSheet(
            currentUrl = lines.getOrNull(lineIndex)?.url,
            castManager = app.castManager,
            onClose = { refreshCast(); showCast = false },
            onState = { castState = it },
            onPauseLocal = { exo.pause() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(
    currentUrl: String?,
    castManager: com.xx9.app.cast.CastManager,
    onClose: () -> Unit,
    onState: (CastState) -> Unit,
    onPauseLocal: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var devices by remember { mutableStateOf<List<com.xx9.app.cast.DlnaDevice>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(castManager.state) }

    fun emit(s: CastState) {
        state = s
        onState(s)
    }

    LaunchedEffect(Unit) {
        discovering = true
        msg = null
        emit(CastState.Discovering)
        val list = runCatching { castManager.discoverDevices() }.getOrDefault(emptyList())
        devices = list
        discovering = false
        emit(castManager.state)
        if (list.isEmpty() && castManager.state !is CastState.Failed) {
            msg = "未发现 DLNA 设备。请确认手机与电视同一 Wi‑Fi，且未开启 AP 隔离。"
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("投屏（DLNA）", color = Acc)
            Spacer(Modifier.height(8.dp))
            when {
                discovering || state is CastState.Discovering -> Text("正在搜索设备…", color = Mut)
                state is CastState.Connecting -> Text("连接 ${(state as CastState.Connecting).deviceName}…", color = Mut)
                state is CastState.Playing -> {
                    Text("播放中 · ${(state as CastState.Playing).deviceName}", color = Acc)
                    Row {
                        TextButton(onClick = { scope.launch { castManager.pause(); emit(castManager.state) } }) {
                            Text("暂停")
                        }
                        TextButton(onClick = { scope.launch { castManager.resume(); emit(castManager.state) } }) {
                            Text("继续")
                        }
                        TextButton(onClick = {
                            scope.launch {
                                castManager.stop()
                                emit(castManager.state)
                                onClose()
                            }
                        }) { Text("停止投屏") }
                    }
                }
                state is CastState.Failed -> Text((state as CastState.Failed).message, color = CastBlock)
            }
            if (msg != null) Text(msg!!, color = Mut, modifier = Modifier.padding(vertical = 8.dp))
            devices.forEach { d ->
                TextButton(
                    onClick = {
                        scope.launch {
                            if (currentUrl.isNullOrBlank()) {
                                msg = "当前无播放地址，无法投屏"
                                return@launch
                            }
                            onPauseLocal()
                            val ok = castManager.castTo(d, currentUrl)
                            emit(castManager.state)
                            if (!ok) msg = castManager.state.messageOrNull()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(d.friendlyName, color = Fg) }
            }
            TextButton(onClick = {
                scope.launch {
                    discovering = true
                    devices = castManager.discoverDevices()
                    discovering = false
                    emit(castManager.state)
                    msg = if (devices.isEmpty()) "未发现设备" else null
                }
            }) { Text("重新搜索", color = Acc) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
