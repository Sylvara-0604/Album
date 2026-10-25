package com.example.album.ui.folders

import android.provider.MediaStore
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import coil3.compose.rememberAsyncImagePainter
import kotlin.math.max
import kotlin.math.pow
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.example.album.AlbumApplication
import com.example.album.R
import com.example.album.ui.common.CellBounds
import com.example.album.ui.common.CellBoundsHolder
import com.example.album.ui.common.ItemDepthKind
import com.example.album.ui.common.LocalPageSwitch
import com.example.album.ui.common.PageId
import com.example.album.ui.common.PageItemState
import com.example.album.ui.common.PageRole
import com.example.album.ui.common.pageItemState
import com.example.album.ui.common.pageScaleFromZ
import com.example.album.ui.common.rememberPageRole
import com.example.album.data.MediaBucket
import com.example.album.data.MediaThumbnail
import com.example.album.ui.common.AlbumEaseOut
import com.example.album.ui.components.EmptyState
import com.example.album.ui.components.pressRipple

/**
 * 可见行高（原版真值，二轮取证 E13）：**cell 108dp、可见行高 105dp、pitch 108**
 * ⇒ 行高取 105dp、行距 3dp（pitch 仍是 108）。实测封面的可见高度 288px@2.75 ≈ 104.7dp。
 */
private val RowHeight = 105.dp

/** 行距：pitch 108 − 可见行高 105 = 3dp（实测 pitch 297px − 封面 288px = 9px @2.75 ≈ 3.3dp） */
private val RowGap = 3.dp

/** 文字左内缩：9.9 实测 48px = 17.5dp（与日期头同一个内缩量） */
private val TextStartInset = 17.dp

/** 名称字号：18sp、**常规字重**（原版不是 Medium，二轮取证 E13），最多 2 行 */
private val NameSize = 18.sp

/** 白字压图的阴影：`Shadow(Black, offset(1,1), blur 2px)` 量级，保证亮封面上也能看清 */
private val NameShadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 2f)

/** 副行字号：原版 14sp（二轮取证 E13） */
private val SubtitleSize = 14.sp

/** 9.9 `pinch_grid_list_item_storage_type_margin = 5dp`：存储图标与「N 个项目」的间距 */
private val StorageIconGap = 5.dp

/** 副行存储图标：原版 **11dp**（二轮取证 E25），沿用现有 `Memory` / `SdCard` vector，不找新资源 */
private val StorageIconSize = 11.dp

/** 视差强度除数（原版 4；我们取 2.5 让可见行的位移更明显，见 `RowCover` 的注释） */
private const val PARALLAX_DIVISOR = 2.5f

/** 9.9 `GradientEffect`：左侧压暗渐变的不透明度（`GRADIENT_LEVEL = 0.4f`） */
private const val SCRIM_LEVEL = 0.4f

/** 9.9 `GradientEffect`：渐变占行宽的比例（`GRADIENT_SIZE = 0.5f`） */
private const val SCRIM_SIZE = 0.5f

/**
 * 文件夹列表（蓝图 P2 · S3）。
 *
 * 版式对齐 **9.9 实测**（不是 8.0 的 `list_item_info.xml`）：每行是一张**满幅图片**（该文件夹最新一张），
 * 可见行高 105dp、行距 3dp（pitch 108dp，二轮取证 E13）；**名称 + 存储图标 + `N 个项目` 用白字压在图左侧（垂直居中）**。
 *
 * **没有顶栏、没有返回按钮**（用户 2026-10-17 令）：内容从状态栏下开始，进出靠系统返回手势/返回键。
 *
 * @param onOpenFolder 点某一行：带上 bucketId（目标页按它取数据）
 */
@Composable
fun FolderListScreen(
    onOpenFolder: (bucketId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = rememberFoldersViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 回到本屏（从文件夹网格 pop / 从桌面切回来）静默重扫一次：新加的文件夹不能等到进程重启才出现。
    // 静默 = 不切 loading 态，用 1.5s 节流挡住切页/转屏的连扫（用户 2026-10-24 报「新增文件夹不刷新」）。
    LifecycleResumeEffect(Unit) {
        viewModel.refreshOnReturn()
        onPauseOrDispose { }
    }

    val listState = rememberLazyListState()

    // 没有顶栏：列表从状态栏下开始
    val contentTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()


    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        when {
            // 首次扫描：留白，不闪"还没有照片"
            state.buckets.isEmpty() && state.loading -> Unit

            state.buckets.isEmpty() && state.failed -> FolderLoadFailed(
                onRetry = viewModel::refresh,
                contentTop = contentTop,
            )

            state.buckets.isEmpty() -> EmptyState(modifier = Modifier.padding(top = contentTop))

            else -> FolderList(
                buckets = state.buckets,
                contentTop = contentTop,
                listState = listState,
                onOpenFolder = onOpenFolder,
            )
        }

    }
}

/** 文件夹行列表：108dp 满幅图行，行距 3dp（9.9 实测）。 */
@Composable
private fun FolderList(
    buckets: List<MediaBucket>,
    contentTop: Dp,
    listState: LazyListState,
    onOpenFolder: (bucketId: String) -> Unit,
) {
    BoxWithConstraints {
        val rowWidth = maxWidth
        val contentPadding = remember(contentTop) { PaddingValues(top = contentTop) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            items(items = buckets, key = { it.id }) { bucket ->
                // 名字在组合期算好（根目录要走 stringResource），不能塞进 onClick 的 lambda 里
                val title = bucket.displayName()
                FolderRow(
                    bucket = bucket,
                    title = title,
                    rowWidth = rowWidth,
                    onClick = { onOpenFolder(bucket.id) },
                )
            }
        }
    }
}

/** 一行：满幅封面 + 压在左侧的白字（名称 / 存储图标 + 「N 个项目」）；整行可点（默认 ripple）。 */
@Composable
private fun FolderRow(
    bucket: MediaBucket,
    title: String,
    rowWidth: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 浏览态的按下反馈**只有 ripple**（二轮取证 E14）：原版那个 `ListSpringAnimation` 的 spring
    // 只服务"多选态选中"的缩放，不在普通点按里跑。所以这里只收 pressed 给 `pressRipple` 用，
    // 不再有 pull / pressedScale 那一套动画。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val widthPx = with(density) { rowWidth.roundToPx() }
    val heightPx = with(density) { RowHeight.roundToPx() }
    // 走网格那套缩略图 fetcher；禁磁盘缓存（系统能随时重建，省 IO 与电量）
    val coverRequest = remember(context, bucket.coverUri, widthPx, heightPx) {
        ImageRequest.Builder(context)
            // id 只用于"同步占位"缓存（文件夹封面不参与网格占位，给 0 = 不登记）
            .data(MediaThumbnail(bucket.coverUri, 0L))
            .size(widthPx, heightPx)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }

    // 9.9 `AlbumAnimator.sRandom`：每行一个随机深度，换页时各行错开（reason 10/12/13）。
    // 原版**每次换页重抽**（见 MediaGrid 里同一条注释），所以 key 带上 generation。
    val pageSwitch = LocalPageSwitch.current
    val depth = remember(bucket.id, pageSwitch?.generation) { kotlin.random.Random.nextFloat() }
    val role = rememberPageRole(PageId.FOLDER_LIST)
    val windowSize = LocalWindowInfo.current.containerSize
    val bounds = remember(bucket.id) { CellBoundsHolder() }
    /**
     * 行顶在窗口里的 y（**快照状态**）。
     *
     * ⚠️ 视差必须读它、不能读 [bounds]：`CellBoundsHolder.value` 是普通字段（为了省滚动热路径上的状态写入），
     * 而滚动时 `LazyColumn` 只改**摆放位置**、不会失效本行 `Canvas` 的绘制节点 ⇒ 读普通字段等于读到
     * 进组合时那个旧值，封面永远不动（用户 2026-10-24：「文件夹视差没有」= 一直是死的）。
     */
    var topInWindow by remember(bucket.id) { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInWindow()
                if (topInWindow != rect.top) topInWindow = rect.top
                bounds.value = CellBounds(
                    left = rect.left.roundToInt(),
                    top = rect.top.roundToInt(),
                    width = rect.width.roundToInt(),
                    height = rect.height.roundToInt(),
                )
            }
            .graphicsLayer {
                // 换页（9.9 reason 10/12/13）：每行沿 z 平移 → 透视缩放 `1/(1−z)`，
                // 且投影中心是**屏幕中心**（离屏心越远位移越大）；α = f³（进）/ (1−f)³（出）。
                val state = if (pageSwitch != null && role != PageRole.IDLE) {
                    pageItemState(
                        role = role,
                        direction = pageSwitch.direction,
                        enterProgress = pageSwitch.enter.value,
                        exitProgress = pageSwitch.exit.value,
                        depth = depth,
                        kind = ItemDepthKind.NORMAL,
                        alphaPower = pageSwitch.enterAlphaPower,
                    )
                } else {
                    PageItemState(z = 0f, alpha = 1f)
                }
                val zScale = pageScaleFromZ(state.z)
                // 浏览态没有按下缩放（E14）：scale 只由换页的 z 透视决定
                scaleX = zScale
                scaleY = zScale
                val box = bounds.value
                if (box.width > 0 && windowSize.width > 0) {
                    val centerX = box.left + box.width / 2f
                    val centerY = box.top + box.height / 2f
                    translationX = (1f - zScale) * (windowSize.width / 2f - centerX)
                    translationY = (1f - zScale) * (windowSize.height / 2f - centerY)
                }
                alpha = state.alpha
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                // 按下反馈由 `pressRipple` 按 9.9 的 shader 自绘（加法叠灰 + 椭圆），关掉 Material ripple
                indication = null,
                onClick = onClick,
            ),
    ) {
        // 9.9 列表行的按下反馈 = **整张封面加法叠灰 0.2（200ms）+ 触点椭圆内再叠 0.2（t² 1.5s）**，
        // 与网格格子共用同一份实现（`pressRipple`）；松手剩 0.7s 收完。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 同网格格子：Compose 的 draw 默认不裁剪，原版涟漪只覆盖整张封面 quad ⇒ 必须裁到行内，
                // 否则椭圆会糊到上下相邻的行上
                .clipToBounds()
                .pressRipple(pressed = pressed),
        ) {
            RowCover(request = coverRequest, topInWindow = { topInWindow })
        }
        // 9.9 `GradientEffect`：左侧 40% 黑、到行宽 50% 处淡完 —— 白字压图的可读性靠它。
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = SCRIM_LEVEL),
                                Color.Transparent,
                            ),
                            startX = 0f,
                            endX = size.width * SCRIM_SIZE,
                        ),
                    )
                },
        )
        // 白字压图（列表里只会有"含媒体的文件夹"，人人都有封面）
        val foreground = Color.White
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = TextStartInset, end = TextStartInset),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = NameSize,
                    // 原版是常规字重（不是 Medium），二轮取证 E13
                    fontWeight = FontWeight.Normal,
                    shadow = NameShadow,
                ),
                color = foreground,
                // 原版标题最多 2 行（长文件夹名换行而不是直接省略）
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = bucket.storageIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(StorageIconSize),
                    tint = foreground,
                )
                Spacer(Modifier.width(StorageIconGap))
                Text(
                    text = pluralStringResource(R.plurals.folder_item_count, bucket.count, bucket.count),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = SubtitleSize),
                    color = foreground,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 行/顶栏上显示的名字：bucket_display_name 为空的是存储根目录（`relative_path = /`），
 * 9.9 显示「根目录」/「Root」（`album_root_folder_txt`），直接显示 bucket id 是错的。
 */
@Composable
internal fun MediaBucket.displayName(): String =
    if (name.isBlank()) stringResource(R.string.folder_root) else name

/** 存储类型图标：内置存储 = 芯片 glyph（8.0 `list_indicator_internal`），其它卷 = SD 卡（`list_indicator_sdcard`）。 */
private fun MediaBucket.storageIcon(): ImageVector =
    if (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) Icons.Outlined.Memory else Icons.Outlined.SdCard

/** 文件夹两屏共用的失败态：一句说明 + 重试按钮（让开顶栏高度，不让文字钻到栏下）。 */
@Composable
internal fun FolderLoadFailed(onRetry: () -> Unit, contentTop: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentTop)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.grid_load_failed),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun rememberFoldersViewModel(): FoldersViewModel {
    val container = (LocalContext.current.applicationContext as AlbumApplication).container
    val factory = remember(container) {
        viewModelFactory {
            initializer { FoldersViewModel(container.repository) }
        }
    }
    return viewModel(factory = factory)
}


/**
 * 行封面 + **滚动视差**（1:1 照 9.9 的列表行视差）。
 *
 * 取证（`list_item_mesh_vertex.glsl:36` + `ParallaxEffect.java:64`）：原版在顶点着色器里给纹理坐标加偏移
 * `v_TexCoord0.y += u_PositionOnScreen`（quad 不动 ⇒ 天然裁在行内）：
 * ```
 * x = 行在视口里的相对位置（视口顶 0、底接近 1）
 * d = clamp( pow(max(0,x), 1.6) / PARALLAX_DIVISOR · imageAspect , 0, 底边余量 )
 *
 * ⚠️ 原版 `ANTIMOVE_FACTOR = 4`（`ParallaxEffect.java:17,64`），但原版的 `f` 覆盖**整屏**且被
 * `getParallaxLimit()` 夹住 —— 我们实测可见行的位移只有十几 px，用户 2026-10-24 反馈"看不出封面跟着动"，
 * 所以除数取 2.5（≈1.6 倍强度），公式结构不变。要再调只改这一个常量。
 * ```
 * `1.6` = `AccelerateInterpolator(0.8)` 的指数（2×0.8），`4` = `ANTIMOVE_FACTOR`。
 * 采样点下移 d ⇒ 封面在行内**向上**滑动、相对行"滞后"；偏移被 Cover 余量夹住，不会露底。
 */
@Composable
private fun RowCover(request: ImageRequest, topInWindow: () -> Float) {
    val painter = rememberAsyncImagePainter(model = request)
    val viewportH = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val iw = painter.intrinsicSize.width
        val ih = painter.intrinsicSize.height
        // ⚠️ 图片还没解出来时 `intrinsicSize` 是 `Size.Unspecified`（NaN），而 `NaN <= 0f` 恒为 false
        // ⇒ 只判 `<= 0` 挡不住，`Size(NaN, NaN)` 进 `Painter.draw` 后 `DrawTransform.inset` 会抛
        // `IllegalArgumentException: Width and height must be greater than or equal to zero`
        // （2026-10-18 实测：点胶囊文件夹图标进文件夹页必崩）。必须显式挡掉非有限值。
        if (!iw.isFinite() || !ih.isFinite() || iw <= 0f || ih <= 0f || size.width <= 0f || size.height <= 0f) {
            return@Canvas
        }
        val cover = max(size.width / iw, size.height / ih)
        val dw = iw * cover
        val dh = ih * cover
        val overflowY = ((dh - size.height) / 2f).coerceAtLeast(0f)
        val x = (topInWindow() / viewportH).coerceIn(0f, 1f)
        val d = (x.pow(1.6f) / PARALLAX_DIVISOR * (iw / ih)).coerceIn(0f, 1f)
        val shift = (d * dh).coerceIn(0f, overflowY)
        translate(left = (size.width - dw) * 0.5f, top = (size.height - dh) * 0.5f - shift) {
            with(painter) { draw(size = Size(dw, dh)) }
        }
    }
}
