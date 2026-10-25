package com.example.album.ui.crop

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.album.R
import com.example.album.data.MediaItem
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 手柄的触控热区边长：48dp。视觉只有一条短线，热区放大才按得住（蓝图定案，与无障碍无关） */
private const val HANDLE_TOUCH_DP = 48f

/** 角手柄的臂长（L 形的两条短边） */
private const val HANDLE_ARM_DP = 18f

/** 边手柄的短边长度 */
private const val HANDLE_BAR_DP = 20f

/** 手柄线宽 */
private const val HANDLE_STROKE_DP = 2.5f

/** 裁剪框线宽：1dp 白线 */
private const val FRAME_STROKE_DP = 1f

/** 框外压暗的不透明度：够暗能看清构图，又不至于看不出外面还有内容 */
private const val MASK_ALPHA = 0.55f

/** 保存中的遮罩：让"已经点了保存"这件事一眼可见，且挡住重复点击 */
private const val SAVING_SCRIM_ALPHA = 0.45f

/**
 * 裁剪页（蓝图 P5 / S6）：**跟随深浅模式的 `surface` 底** + 居中预览 + 框外压暗 + 8 手柄，**纯自由拖拽，没有任何固定比例**。
 *
 * 交互：
 * - 单指按在手柄（48dp 热区，取最近的一个）上 = 拖那条边/那个角，对边/对角固定；
 * - 单指按在框内 = 整框平移；按在框外 = 不动（避免误触把框甩飞）；
 * - 双指 = 整框等比缩放（以框中心为基准，宽高比保持用户拖出来的样子）；
 * - 框永远不超出图片显示区，宽高不小于 48dp —— 三条都由 [CropMath] 的纯函数保证。
 *
 * 几何只有一个真源：`imageRect`（预览图铺开后的矩形）与 `crop` 都在 [CropViewModel] 里，
 * 预览、遮罩、框线、手柄全部按这两份数据绘制，所以"看到哪儿"和"裁到哪儿"不会漂。
 *
 * 保存走 [CropViewModel.save]：先弹「另存为副本 / 覆盖原图」，执行中盖一层进度遮罩，失败弹错误框。
 *
 * @param item 当前要裁的媒体项（由导航层按窗口取回来）
 * @param onDone 保存成功：返回上一页
 * @param onCancel 取消/返回：不写任何文件
 */
@Composable
fun CropScreen(
    item: MediaItem,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Application
    // 裁剪是一次性操作：本页状态用 remember 持有，离开组合就 close（不往 Activity 级 ViewModelStore 里塞大位图）
    val viewModel = remember(item.id) { CropViewModel(application, item) }
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    val preview = viewModel.preview
    val error = viewModel.error
    var saveChoiceVisible by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.isSaved) {
        if (viewModel.isSaved) onDone()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // ⚠️ 2026-10-24 r47（用户令「裁剪页底色仍黑」）：以前写死 `Color.Black` ⇒
            // 浅色模式下整页纯黑，和查看器（r43 已改 `surface`）不一致。
            // 现在与查看器/视频页同一套：深色模式下 `surface` 本身就是近黑，浅色模式下是动态取色出来的浅底。
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize()) {
            CropTopBar()
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val viewportWidth = constraints.maxWidth.toFloat()
                val viewportHeight = constraints.maxHeight.toFloat()
                val density = LocalDensity.current
                // 48dp 热区的半径：命中判定取"最近且不超过半径"的手柄，手柄挨得近时也不会乱抢
                val handleRadius = with(density) { (HANDLE_TOUCH_DP / 2f).dp.toPx() }
                LaunchedEffect(viewportWidth, viewportHeight, density.density) {
                    viewModel.onViewportChanged(viewportWidth, viewportHeight, density.density)
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(viewModel, handleRadius) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val grabbedHandle = hitHandle(down.position, viewModel.crop, handleRadius)
                                val insideFrame = grabbedHandle == null &&
                                    CropMath.contains(viewModel.crop, down.position.x, down.position.y)
                                var pinching = false
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } >= 2) pinching = true
                                    if (pinching) {
                                        // 双指：整框等比缩放（不加平移，避免和单指平移互相打架）
                                        val zoom = event.calculateZoom()
                                        if (zoom != 1f) viewModel.onPinch(zoom)
                                    } else {
                                        val pan = event.calculatePan()
                                        val moved = pan.x != 0f || pan.y != 0f
                                        if (grabbedHandle != null) {
                                            if (moved) viewModel.onDrag(grabbedHandle, pan.x, pan.y)
                                        } else if (insideFrame && moved) {
                                            viewModel.onDrag(null, pan.x, pan.y)
                                        }
                                    }
                                    // 整块区域都是裁剪 UI，手势不留给别人
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                ) {
                    // 在绘制阶段读状态：拖动时只重绘，不重组整页
                    drawCropContent(
                        preview = viewModel.preview,
                        imageRect = viewModel.imageRect,
                        crop = viewModel.crop,
                    )
                }
            }
            CropBottomBar(
                canSave = preview != null,
                saving = viewModel.saving,
                onCancel = onCancel,
                onSave = { saveChoiceVisible = true },
            )
        }

        if (viewModel.saving) {
            SavingOverlay()
        }

        error?.let { current ->
            CropErrorDialog(error = current, onDismiss = viewModel::dismissError)
        }

        if (saveChoiceVisible) {
            SaveChoiceDialog(
                onDismiss = { saveChoiceVisible = false },
                onSaveCopy = {
                    saveChoiceVisible = false
                    viewModel.save(overwrite = false)
                },
                onOverwrite = {
                    saveChoiceVisible = false
                    viewModel.save(overwrite = true)
                },
            )
        }
    }
}

/**
 * 顶栏：只有标题。
 *
 * **没有返回箭头**（用户 2026-10-17 令：全部界面不要那种"灰底圆角矩形 + 返回箭头"的提示）；
 * 退出走系统返回，或者底部那个「取消」按钮。
 */
@Composable
private fun CropTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.crop_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** 底部按钮条：取消（左）/ 保存（右）。保存要先选"另存还是覆盖"，所以点它只是弹框。 */
@Composable
private fun CropBottomBar(
    canSave: Boolean,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, enabled = !saving) {
            Text(text = stringResource(R.string.crop_cancel), color = Color.White)
        }
        Button(onClick = onSave, enabled = canSave && !saving) {
            Text(text = stringResource(R.string.crop_save))
        }
    }
}

/** 保存中：半透明遮罩 + 转圈 + 一行字，同时挡住重复点击。 */
@Composable
private fun SavingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SAVING_SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)
            Text(
                text = stringResource(R.string.crop_saving),
                modifier = Modifier.padding(top = 12.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 保存前二选一：另存为副本 / 覆盖原图。取消在 confirmButton 上（Material3 只留两个按钮位）。 */
@Composable
private fun SaveChoiceDialog(
    onDismiss: () -> Unit,
    onSaveCopy: () -> Unit,
    onOverwrite: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.crop_save_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveCopy, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.crop_save_copy))
                }
                OutlinedButton(onClick = onOverwrite, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.crop_save_overwrite))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.crop_cancel))
            }
        },
    )
}

/** 失败提示：只显示一个"确定"，不让用户以为裁剪成功。 */
@Composable
private fun CropErrorDialog(error: CropError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.crop_error_title)) },
        text = { Text(text = stringResource(errorMessageRes(error))) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.crop_error_dismiss))
            }
        },
    )
}

/** 错误类型 → 文案：VM 只报类型，文案全在这里，改文案不碰逻辑。 */
private fun errorMessageRes(error: CropError): Int = when (error) {
    CropError.DecodeFailed -> R.string.crop_error_decode
    CropError.Denied -> R.string.crop_error_denied
    CropError.SaveFailed -> R.string.crop_error_save
}

/**
 * 一次画完四层：预览图 → 框外压暗 → 1dp 白框 → 8 个手柄。
 * 所有位置都取自同一份 `imageRect` / `crop`，与手势换算共用几何，不会出现"框和图画错位"。
 */
private fun DrawScope.drawCropContent(
    preview: ImageBitmap?,
    imageRect: CropRect,
    crop: CropRect,
) {
    if (preview != null && imageRect.isValid) {
        val dstWidth = imageRect.width.roundToInt()
        val dstHeight = imageRect.height.roundToInt()
        if (dstWidth > 0 && dstHeight > 0) {
            drawImage(
                image = preview,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(preview.width, preview.height),
                dstOffset = IntOffset(imageRect.left.roundToInt(), imageRect.top.roundToInt()),
                dstSize = IntSize(dstWidth, dstHeight),
            )
        }
    }
    if (!crop.isValid) return

    // 框外压暗：四条矩形拼满整个图区，框内留空 ⇒ 框内透明
    val mask = Color.Black.copy(alpha = MASK_ALPHA)
    val frameTop = crop.top.coerceIn(0f, size.height)
    val frameBottom = crop.bottom.coerceIn(0f, size.height)
    val frameLeft = crop.left.coerceIn(0f, size.width)
    val frameRight = crop.right.coerceIn(0f, size.width)
    val frameHeight = (frameBottom - frameTop).coerceAtLeast(0f)
    drawRect(mask, Offset(0f, 0f), Size(size.width, frameTop))
    drawRect(mask, Offset(0f, frameBottom), Size(size.width, (size.height - frameBottom).coerceAtLeast(0f)))
    drawRect(mask, Offset(0f, frameTop), Size(frameLeft, frameHeight))
    drawRect(mask, Offset(frameRight, frameTop), Size((size.width - frameRight).coerceAtLeast(0f), frameHeight))

    drawRect(
        color = Color.White,
        topLeft = Offset(crop.left, crop.top),
        size = Size(crop.width, crop.height),
        style = Stroke(width = FRAME_STROKE_DP.dp.toPx()),
    )
    drawHandles(crop)
}

/**
 * 8 个手柄的视觉：四角画 L 形短边，四边中点画短横/短竖。
 * 48dp 的触控热区只存在于手势层，视觉保持细，不遮住画面。
 */
private fun DrawScope.drawHandles(crop: CropRect) {
    val stroke = HANDLE_STROKE_DP.dp.toPx()
    // 框很小时把臂长收短，免得四角的线在小框里交叉成一团
    val arm = minOf(HANDLE_ARM_DP.dp.toPx(), crop.width / 2f, crop.height / 2f)
    val bar = minOf(HANDLE_BAR_DP.dp.toPx(), crop.width, crop.height)
    val centerX = (crop.left + crop.right) / 2f
    val centerY = (crop.top + crop.bottom) / 2f

    drawLine(Color.White, Offset(crop.left, crop.top), Offset(crop.left + arm, crop.top), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.left, crop.top), Offset(crop.left, crop.top + arm), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.right, crop.top), Offset(crop.right - arm, crop.top), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.right, crop.top), Offset(crop.right, crop.top + arm), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.right, crop.bottom), Offset(crop.right - arm, crop.bottom), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.right, crop.bottom), Offset(crop.right, crop.bottom - arm), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.left, crop.bottom), Offset(crop.left + arm, crop.bottom), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.left, crop.bottom), Offset(crop.left, crop.bottom - arm), stroke, StrokeCap.Round)

    drawLine(Color.White, Offset(centerX - bar / 2f, crop.top), Offset(centerX + bar / 2f, crop.top), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(centerX - bar / 2f, crop.bottom), Offset(centerX + bar / 2f, crop.bottom), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.left, centerY - bar / 2f), Offset(crop.left, centerY + bar / 2f), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(crop.right, centerY - bar / 2f), Offset(crop.right, centerY + bar / 2f), stroke, StrokeCap.Round)
}

/** 手柄的中心点（显示像素）。 */
private fun handleCenters(crop: CropRect): List<Pair<Handle, Offset>> {
    val centerX = (crop.left + crop.right) / 2f
    val centerY = (crop.top + crop.bottom) / 2f
    return listOf(
        Handle.TopLeft to Offset(crop.left, crop.top),
        Handle.Top to Offset(centerX, crop.top),
        Handle.TopRight to Offset(crop.right, crop.top),
        Handle.Right to Offset(crop.right, centerY),
        Handle.BottomRight to Offset(crop.right, crop.bottom),
        Handle.Bottom to Offset(centerX, crop.bottom),
        Handle.BottomLeft to Offset(crop.left, crop.bottom),
        Handle.Left to Offset(crop.left, centerY),
    )
}

/**
 * 命中哪个手柄：在 48dp 半径内取**最近**的一个。
 * 用最近距离而不是固定顺序，框被拖得很小时（角与边只隔 24dp）也不会永远只抓到角。
 */
private fun hitHandle(position: Offset, crop: CropRect, radiusPx: Float): Handle? {
    if (!crop.isValid) return null
    var best: Handle? = null
    var bestDistance = Float.MAX_VALUE
    for ((handle, center) in handleCenters(crop)) {
        val distance = hypot(position.x - center.x, position.y - center.y)
        if (distance <= radiusPx && distance < bestDistance) {
            best = handle
            bestDistance = distance
        }
    }
    return best
}
