package com.example.album.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.example.album.R
import com.example.album.data.MediaItem
import com.example.album.util.Formatters

/**
 * 进度轮询间隔。250ms：进度条与时间肉眼看是连续的，又不至于每帧都去问播放器
 * （media3 官方 `ProgressState` 也只在"需要刷新"时才唤醒，这个值是它的采样周期）。
 */
private const val PROGRESS_TICK_MILLIS = 250L

/** 控制条显隐时长（与查看器 chrome 一致，9.9 `AnimationHelper.DURATION` = 300ms） */
private const val CHROME_FADE_MILLIS = 300

/**
 * 视频页（蓝图 §5 S5 蓝本：`media3-ui-compose` 的 `PlayerSurface` + 自绘控制条）。
 *
 * **进页面绝不自动播放**（v5.6 用户令）：只 prepare 出首帧 + 中央播放按钮，用户点了才解码出声。
 * 理由是查看器用 `HorizontalPager` 且预组合相邻页，自动播放等于"滑到哪儿哪儿开始响"。
 *
 * 一个实现要点：**画面用 `ContentFrame` 而不是裸 `PlayerSurface`**。
 * 1.11.1 里 `PlayerSurface(player, modifier, surfaceType)` 只负责把 surface 挂到窗口上，
 * 不处理宽高比 —— 直接把 surface 铺满整屏，4:3 的片子会被横向拉长。
 * `ContentFrame` 是同文件里的官方包装（内部就调 `PlayerSurface`），
 * 由它按 `PresentationState.videoSizeDp` 把 surface 摆进按比例算出的矩形，
 * 默认 `contentScale = ContentScale.Fit`，正是"等比缩放 + 留黑边"的观感。
 *
 * 控制条按定案只留四项：播放/暂停、可拖进度条、当前时间/总时长、缓冲状态。
 * **明确不做**：快退/快进 10 秒、横竖屏切换、播放模式、双击快进。
 *
 * @param onTap 单击画面（中央按钮与控制条以外的区域）—— 通知外层切换 chrome
 */
// 注意：这里的 `@OptIn` 是 **androidx.annotation.OptIn**，不是 Kotlin 那个。
// media3 的 `UnstableApi` 用的是 `androidx.annotation.RequiresOptIn`（不是 kotlin.RequiresOptIn），
// 写 kotlin 的 `@OptIn` 编译器会警告"该标记没有 @RequiresOptIn，@OptIn 无效"，
// 而本工程 allWarningsAsErrors，那等于编译失败；lint 的 UnsafeOptInUsageError 也只认这一个。
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    item: MediaItem,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** 外层 chrome（顶栏返回键 + 底部工具条）是否可见；视频自己的控制条跟它一起显隐，避免"一半在、一半不在" */
    chromeVisible: Boolean = true,
    /**
     * 播放层画出**第一帧**时回调。上层拿它决定"盖在上面的缩略图什么时候交叉淡出"——
     * 播放层出第一帧之前是黑的（用户 2026-10-24 录屏：整页黑约 380ms）。
     */
    onFirstFrameRendered: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 播放器与这一页同生命周期：item 变了就换新 player，旧的由下面的 DisposableEffect 释放。
    // 用 remember 而不是 rememberCoroutineScope/LaunchedEffect 建播放器，是因为
    // 首帧必须在第一帧之前就 prepare 上，否则会空等一个 vsync 才出图。
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(PlayerMediaItem.fromUri(item.uri))
            // v5.6：playWhenReady = false 是第一位的，先钉住它再 prepare，
            // 免得某些实现 prepare 后立刻起播。
            playWhenReady = false
            prepare()
            // 先 prepare 再 seekTo(0)：归位到第 0 帧（偶发上次残留的位置不会被带进来）
            seekTo(0)
        }
    }

    // 缓冲状态：官方 state 辅助函数里没有它（`rememberProgressStateWithTickInterval` 只给
    // 位置/时长），所以自己听 playbackState。这里和释放写在同一个 DisposableEffect 里，
    // 顺序明确：先摘监听，再 pause → seekTo(0) → release。
    var playbackState by remember(item.id) { mutableIntStateOf(player.playbackState) }
    val latestFirstFrame by rememberUpdatedState(onFirstFrameRendered)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
            }

            override fun onRenderedFirstFrame() {
                latestFirstFrame()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // 离开页面：暂停 + 回到 0 + 释放，不记忆播放进度（再次进来仍是首帧待播）
            player.pause()
            player.seekTo(0)
            player.release()
        }
    }

    // 官方 Compose 状态件：都是 suspend observe()，要自己丢进 LaunchedEffect 里跑。
    val playPauseState = rememberPlayPauseButtonState(player)
    LaunchedEffect(playPauseState) { playPauseState.observe() }

    val progressState = rememberProgressStateWithTickInterval(player, PROGRESS_TICK_MILLIS, scope)
    LaunchedEffect(progressState) { progressState.observe() }

    // 用户是否已经点过"开始"：点过之后中央大按钮永久消失（暂停只走控制条），
    // 这是蓝图里"之后中央按钮消失"的字面行为。
    var started by remember(item.id) { mutableStateOf(false) }

    // 时长优先用播放器解析出来的（更准）；还没 ready 时退回 MediaStore 的时长，进度条不至于空着。
    val totalMs = if (progressState.durationMs > 0L) progressState.durationMs else item.durationMillis

    // 进度条自己持值（SliderState 是 M3 1.5 的非弃用入口；老的重载已标记 @Deprecated，
    // 工程开了 allWarningsAsErrors，用了直接编译失败）。
    val sliderState = rememberSliderState(value = 0f)
    // 播放进度 → 进度条。**拖动中不写**，否则手指会被播放器的 tick 顶回去。
    // 只依赖位置与总长，位置没变就不会重复写，所以拖动松手后也不会闪回旧位置。
    LaunchedEffect(progressState.currentPositionMs, totalMs) {
        if (totalMs > 0L && !sliderState.isDragging) {
            sliderState.value = (progressState.currentPositionMs.toFloat() / totalMs).coerceIn(0f, 1f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 底色跟查看器同一套（用户 2026-10-24：「视频查看页也无浅色底」）：
            // 以前用 `scrim`（M3 的纯黑遮罩角色）⇒ 浅色模式下也是一块黑。
            .background(MaterialTheme.colorScheme.surface),
    ) {
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            // ⚠️ **TextureView 而不是 SurfaceView**（2026-10-24 r44，用户报「点进视频闪一下 / UI 跳出」）：
            // SurfaceView 是独立图层、不参与 Compose 的位移/alpha，而且**出第一帧之前整块是黑的**——
            // 录屏量到整页黑约 380ms（216 → 32 → 188）。TextureView 在窗口内合成 ⇒
            // ① 能跟着页面滑入（不再需要"转场期间只画缩略图"的闸门）；② 上层缩略图能真的盖住它，
            // 等首帧到了再交叉淡出 ⇒ 既无黑帧也无硬切。代价是每帧多一次 GPU 拷贝，全屏播放可接受。
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        )

        // 点击层：只认单击。用 detectTapGestures 而不是 clickable，
        // 是因为不能让这层吃掉左右拖动 —— 翻页手势要留给外层 Pager。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.id) { detectTapGestures { onTap() } },
        )

        // 进页面时 chrome 淡入（AnimatedVisibility 的 visible 初值为 true 时不播进场动画 ⇒ 先 false）
        var chromeAppeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { chromeAppeared = true }

        androidx.compose.animation.AnimatedVisibility(
            visible = chromeAppeared && !started && chromeVisible,
            modifier = Modifier.align(Alignment.Center),
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(CHROME_FADE_MILLIS),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(CHROME_FADE_MILLIS),
            ),
        ) {
            IconButton(
                onClick = {
                    started = true
                    player.play()
                },
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    // v5.5：无障碍整体不做，contentDescription 一律 null
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = chromeAppeared && chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(CHROME_FADE_MILLIS),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(CHROME_FADE_MILLIS),
            ),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 不压到系统导航条；外层 chrome 只管状态栏，这里各管一边
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                // 胶囊（用户 2026-10-24 令：按钮/控制条走取色胶囊）：
                // 底色是主题容器角色、形状是真胶囊；以前是 `shapes.large` 的圆角方块。
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    // 从控制条起播也算"开始过"：否则中央大按钮会和控制条上的暂停键同屏
                    started = true
                    playPauseState.onClick()
                },
                enabled = playPauseState.isEnabled,
            ) {
                Icon(
                    imageVector = if (playPauseState.showPlay) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            Slider(
                state = sliderState,
                onValueChange = { sliderState.value = it },
                onValueChangeFinished = { player.seekTo((sliderState.value * totalMs).toLong()) },
                // 时长未知时拖动没有意义（也避免 0..0 的空区间）
                enabled = totalMs > 0L,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )

            Text(
                text = stringResource(
                    R.string.viewer_video_time,
                    clock(if (sliderState.isDragging) (sliderState.value * totalMs).toLong() else progressState.currentPositionMs),
                    clock(totalMs),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 缓冲状态（第四项）：缓冲中才出现，稳态下不占位置
            if (playbackState == Player.STATE_BUFFERING) {
                Text(
                    text = stringResource(R.string.viewer_video_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        }
    }
}

/**
 * 进度条上要显示 `0:00`，而 `Formatters.duration` 对 ≤0 返回空串
 * （那是给网格角标用的语义："未知就别显示"）。这里补一个零值文案。
 */
private fun clock(millis: Long): String = Formatters.duration(millis).ifEmpty { "0:00" }
