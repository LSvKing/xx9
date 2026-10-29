package com.xx9.app.ui.screens

import android.view.LayoutInflater
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.xx9.app.R
import com.xx9.app.Xx9App
import com.xx9.app.cast.CastManager
import com.xx9.app.cast.CastState
import com.xx9.app.cast.DlnaDevice
import com.xx9.app.data.ApiException
import com.xx9.app.data.MediaRules
import com.xx9.app.data.VideoDetail
import com.xx9.app.data.Xx9Api
import com.xx9.app.ui.ErrorState
import com.xx9.app.ui.LoadingBlock
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.Bg
import com.xx9.app.ui.theme.CastBlock
import com.xx9.app.ui.theme.CastSoft
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut
import com.xx9.app.ui.theme.Surface
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
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
    var autoSwitchedFrom by remember { mutableIntStateOf(-1) }
    var showCast by remember { mutableStateOf(false) }
    var castState by remember { mutableStateOf(app.castManager.state) }
    var fav by remember { mutableStateOf(false) }
    var surfaceVisible by remember { mutableStateOf(true) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    fun refreshCast() { castState = app.castManager.state }

    LaunchedEffect(videoId, loading) {
        if (!loading) return@LaunchedEffect
        err = null
        playErr = null
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
                    is ApiException -> "无法连接服务器"
                    else -> "网络异常"
                }
                loading = false
            }
    }

    val lines = detail?.lines.orEmpty()
    val short = MediaRules.isShort(detail?.duration)
    val casting = castState is CastState.Playing
    val activity = remember(context) { context.findActivity() }
    var fullscreen by remember { mutableStateOf(false) }

    fun restorePortraitUi() {
        val act = activity ?: return
        runCatching {
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun applyFullscreenUi(enable: Boolean) {
        val act = activity ?: return
        if (!enable) {
            restorePortraitUi()
            return
        }
        runCatching {
            act.requestedOrientation = if (short) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    fun setFullscreen(enable: Boolean) {
        fullscreen = enable
        applyFullscreenUi(enable)
    }

    // §0.3：进页默认竖屏，勿自动 immersive；仅 fullscreen=true 时改取向/藏栏
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { restorePortraitUi() }
    }
    DisposableEffect(fullscreen, short) {
        if (fullscreen) applyFullscreenUi(true)
        else restorePortraitUi()
        onDispose { }
    }

    val exo = remember {
        ExoPlayer.Builder(context).build()
    }
    DisposableEffect(exo, lines.size, lineIndex) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playErr = "播放失败：${error.message ?: error.errorCodeName}"
                // 对齐 Web：致命错误自动切下一线（每条线最多自动切一次）
                if (lineIndex + 1 < lines.size && autoSwitchedFrom != lineIndex) {
                    autoSwitchedFrom = lineIndex
                    lineIndex += 1
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
    fun teardownPlayback() {
        surfaceVisible = false
        runCatching {
            exo.playWhenReady = false
            exo.pause()
            exo.stop()
            exo.clearVideoSurface()
            exo.clearMediaItems()
        }
        playerViewRef?.player = null
    }

    fun safeBack() {
        if (fullscreen) {
            setFullscreen(false)
            return
        }
        teardownPlayback()
        restorePortraitUi()
        onBack()
    }

    BackHandler { safeBack() }

    // 退到后台 / 离开页：先清画面，避免 Surface 残留一帧盖住下层
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exo) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exo.playWhenReady = false
                runCatching { exo.clearVideoSurface() }
                surfaceVisible = false
            } else if (event == Lifecycle.Event.ON_RESUME) {
                if (detail != null && !casting) {
                    surfaceVisible = true
                    exo.playWhenReady = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            teardownPlayback()
            runCatching { exo.release() }
        }
    }

    LaunchedEffect(detail, lineIndex) {
        val url = lines.getOrNull(lineIndex)?.url
        playErr = null
        if (url.isNullOrBlank()) {
            if (detail != null) playErr = "暂无播放地址（线路为空）。可能是上游签址失败，请稍后重试或换片。"
            return@LaunchedEffect
        }
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent("xx9-android-native/2.0")
            .setAllowCrossProtocolRedirects(true)
        val source = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = !casting
    }

    if (fullscreen && !loading && err == null) {
        // 显式全屏：短竖 immersive / 长横 immersive
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerSurface(
                short = short,
                casting = casting,
                exo = exo,
                surfaceVisible = surfaceVisible && !casting,
                fullscreen = true,
                onPlayerView = { playerViewRef = it },
            )
            if (casting) {
                CastBanner(
                    deviceName = (castState as CastState.Playing).deviceName,
                    onDisconnect = {
                        scope.launch {
                            app.castManager.stop()
                            refreshCast()
                            exo.playWhenReady = true
                        }
                    },
                )
            }
            IconButton(
                onClick = { setFullscreen(false) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
            ) {
                Icon(
                    Icons.Filled.FullscreenExit,
                    contentDescription = "退出全屏",
                    tint = Color.White,
                    modifier = Modifier.height(24.dp).width(24.dp),
                )
            }
            IconButton(
                onClick = { safeBack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .statusBarsPadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { safeBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Fg)
                    }
                    Text(
                        detail?.title ?: "播放",
                        color = Fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            when {
                loading -> LoadingBlock()
                err != null -> ErrorState(err!!, "请确认 Base URL 与服务是否可用", "重试") {
                    loading = true
                }
                else -> {
                    if (casting) {
                        CastBanner(
                            deviceName = (castState as CastState.Playing).deviceName,
                            onDisconnect = {
                                scope.launch {
                                    app.castManager.stop()
                                    refreshCast()
                                    exo.playWhenReady = true
                                }
                            },
                        )
                    }

                    Box(Modifier.fillMaxWidth()) {
                        PlayerSurface(
                            short = short,
                            casting = casting,
                            exo = exo,
                            surfaceVisible = surfaceVisible && !casting,
                            fullscreen = false,
                            onPlayerView = { playerViewRef = it },
                        )
                        // §0.3：播放器 overlay 控制条右下；勿放进 PlayerActions
                        if (!casting) {
                            IconButton(
                                onClick = { setFullscreen(true) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
                            ) {
                                Icon(
                                    Icons.Filled.Fullscreen,
                                    contentDescription = "全屏",
                                    tint = Color.White,
                                    modifier = Modifier.height(24.dp).width(24.dp),
                                )
                            }
                        }
                    }

                    Column(
                        Modifier
                            .weight(1f, fill = true)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .then(if (!casting) Modifier.navigationBarsPadding() else Modifier),
                    ) {
                        Text(detail?.title.orEmpty(), color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(lines) { idx, ln ->
                                FilterChip(
                                    selected = idx == lineIndex,
                                    onClick = { lineIndex = idx },
                                    label = { Text(ln.name) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Acc.copy(alpha = 0.15f),
                                        selectedLabelColor = Acc,
                                    ),
                                )
                            }
                        }
                        if (playErr != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(playErr!!, color = CastBlock, fontSize = 12.sp)
                            if (lineIndex + 1 < lines.size) {
                                TextButton(onClick = { lineIndex += 1 }) {
                                    Text("切换线路", color = Acc)
                                }
                            }
                        }
                        if (lines.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("暂无播放线路", color = CastBlock, fontSize = 14.sp)
                            Text("服务已免密，但取流地址为空。可重试详情或稍后再试。", color = Mut, fontSize = 12.sp)
                            TextButton(onClick = { loading = true }) { Text("重新拉取详情", color = Acc) }
                        }
                        Spacer(Modifier.height(12.dp))
                        PlayerActions(
                            fav = fav,
                            casting = casting,
                            onFav = {
                                scope.launch {
                                    runCatching { api.toggleFavorite(videoId) }.onSuccess { fav = it }
                                }
                            },
                            onCast = { refreshCast(); showCast = true },
                            onSync = if (casting) {
                                {
                                    scope.launch {
                                        val url = lines.getOrNull(lineIndex)?.url
                                        val d = app.castManager.currentDevice
                                        if (url != null && d != null) {
                                            app.castManager.castTo(d, videoId, lineIndex, url)
                                            refreshCast()
                                        }
                                    }
                                }
                            } else null,
                        )
                        if (!casting) {
                            Spacer(Modifier.height(8.dp))
                            Text("点「投屏」选择同一 Wi‑Fi 下的电视 / 盒子 (DLNA)", color = Mut, fontSize = 12.sp)
                        }
                    }

                    if (casting) {
                        CastRemoteBar(
                            onPause = { scope.launch { app.castManager.pause(); refreshCast() } },
                            onResume = { scope.launch { app.castManager.resume(); refreshCast() } },
                            onStop = {
                                scope.launch {
                                    app.castManager.stop()
                                    refreshCast()
                                    exo.playWhenReady = true
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCast) {
        CastSheet(
            videoId = videoId,
            lineIndex = lineIndex,
            currentUrl = lines.getOrNull(lineIndex)?.url,
            castManager = app.castManager,
            onClose = { refreshCast(); showCast = false },
            onState = { castState = it },
            onPauseLocal = { exo.pause() },
        )
    }
}

@Composable
private fun PlayerSurface(
    short: Boolean,
    casting: Boolean,
    exo: ExoPlayer,
    surfaceVisible: Boolean,
    fullscreen: Boolean = false,
    onPlayerView: (PlayerView?) -> Unit,
) {
    Box(
        Modifier
            .then(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val boxMod = when {
            fullscreen -> Modifier.fillMaxSize()
            short -> Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.72f)
                .aspectRatio(9f / 16f)
                .heightIn(max = 520.dp)
            else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        }
        Box(boxMod, contentAlignment = Alignment.Center) {
            when {
                casting || !surfaceVisible -> {
                    Text(
                        if (casting) "画面在电视上" else "",
                        color = Mut,
                        fontSize = 13.sp,
                    )
                }
                else -> {
                    AndroidView(
                        factory = { ctx ->
                            val pv = LayoutInflater.from(ctx)
                                .inflate(R.layout.xx9_player_view, null, false) as PlayerView
                            // FIT：短竖/长横全屏时完整入画，不裁切
                            pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            pv.player = exo
                            onPlayerView(pv)
                            pv
                        },
                        update = { pv ->
                            if (surfaceVisible) {
                                pv.player = exo
                                onPlayerView(pv)
                            } else {
                                pv.player = null
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun CastBanner(deviceName: String, onDisconnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(CastSoft),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(CastBlock))
        Text(
            "投屏至 $deviceName",
            color = Fg,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onDisconnect) {
            Text("断开", color = CastBlock, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PlayerActions(
    fav: Boolean,
    casting: Boolean,
    onFav: () -> Unit,
    onCast: () -> Unit,
    onSync: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionChip(
            label = if (fav) "已收藏" else "收藏",
            onClick = onFav,
            leading = {
                Icon(
                    if (fav) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = null,
                    tint = Fg,
                    modifier = Modifier.height(18.dp),
                )
            },
        )
        ActionChip(
            label = if (casting) "已投屏" else "投屏",
            onClick = onCast,
            accentCast = casting,
            leading = {
                Box {
                    Icon(Icons.Filled.Cast, contentDescription = null, tint = if (casting) CastBlock else Fg, modifier = Modifier.height(18.dp))
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 0.dp, start = 0.dp)
                            .width(6.dp)
                            .height(6.dp)
                            .background(CastBlock, RoundedCornerShape(1.dp)),
                    )
                }
            },
        )
        if (onSync != null) {
            ActionChip(label = "同步到电视", onClick = onSync, dashed = true)
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    accentCast: Boolean = false,
    dashed: Boolean = false,
) {
    val borderColor = when {
        accentCast -> CastBlock
        dashed -> Mut
        else -> Line
    }
    val bg = if (accentCast) CastSoft else Surface
    Row(
        Modifier
            .height(44.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp)
            .then(ModifierClick(onClick)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(label, color = if (accentCast) CastBlock else Fg, fontSize = 13.sp)
    }
}

private fun ModifierClick(onClick: () -> Unit): Modifier =
    Modifier.clickable(onClick = onClick)

@Composable
private fun CastRemoteBar(
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CastSoft)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(4.dp).height(44.dp).background(CastBlock))
        Text("遥控", color = Fg, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        RemoteBtn("暂停", onPause)
        RemoteBtn("继续", onResume)
        RemoteBtn("停止", onStop, danger = true)
    }
}

@Composable
private fun RemoteBtn(label: String, onClick: () -> Unit, danger: Boolean = false) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, color = if (danger) CastBlock else Fg)
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(
    videoId: Int,
    lineIndex: Int,
    currentUrl: String?,
    castManager: CastManager,
    onClose: () -> Unit,
    onState: (CastState) -> Unit,
    onPauseLocal: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var devices by remember { mutableStateOf<List<DlnaDevice>>(emptyList()) }
    var discovering by remember { mutableStateOf(true) }
    var failMsg by remember { mutableStateOf<String?>(null) }

    fun emit() = onState(castManager.state)

    suspend fun scan() {
        discovering = true
        failMsg = null
        onState(CastState.Discovering)
        devices = runCatching { castManager.discoverDevices() }.getOrDefault(emptyList())
        discovering = false
        emit()
    }

    LaunchedEffect(Unit) { scan() }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = Surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Column {
                Box(Modifier.fillMaxWidth().height(4.dp).background(CastBlock))
                Spacer(Modifier.height(8.dp))
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("投屏到", color = Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { scan() } }, enabled = !discovering) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = if (discovering) Mut else Fg)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Fg)
                }
            }
            Text("与手机同一 Wi‑Fi · DLNA", color = Mut, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))

            when {
                discovering -> {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Acc)
                        Spacer(Modifier.height(12.dp))
                        Text("正在查找设备…", color = Mut)
                    }
                }
                failMsg != null -> {
                    Text("投屏失败", color = CastBlock)
                    Text(failMsg!!, color = Mut, fontSize = 12.sp)
                    TextButton(onClick = { scope.launch { scan() } }) { Text("重试", color = Acc) }
                }
                devices.isEmpty() -> {
                    Text("未找到可投屏设备", color = Fg, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("请确认电视/盒子与手机在同一 Wi‑Fi，并已开启投屏或 DLNA", color = Mut, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { scope.launch { scan() } }) {
                        Text("重新扫描", color = Acc)
                    }
                }
                else -> {
                    devices.forEach { d ->
                        val inUse = castManager.currentDevice?.controlUrl == d.controlUrl
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .then(ModifierClick {
                                    scope.launch {
                                        if (currentUrl.isNullOrBlank()) {
                                            failMsg = "当前无播放地址，无法投屏"
                                            return@launch
                                        }
                                        onPauseLocal()
                                        val ok = castManager.castTo(d, videoId, lineIndex, currentUrl)
                                        emit()
                                        if (!ok) {
                                            failMsg = castManager.state.messageOrNull()
                                                ?: "投屏失败，请重试或换一台设备"
                                        } else onClose()
                                    }
                                }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Tv, contentDescription = null, tint = Acc)
                            Spacer(Modifier.width(12.dp))
                            Text(d.friendlyName, color = Fg, modifier = Modifier.weight(1f))
                            if (inUse) {
                                Text(
                                    "使用中",
                                    color = Acc,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .background(Acc.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    }
                }
            }
        }
    }
}
