package com.example.album.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot
import kotlinx.coroutines.delay

/**
 * 9.9 的「按下高亮 + 波纹」1:1（网格格子与文件夹列表行**共用这一份**）。
 *
 * 取证（`image_mesh_touch_frag.glsl` / `list_item_touch_mesh_frag.glsl` + `RippleAnimation.java`）：
 * - 整张图**加法**叠灰 0.2，0→0.2 **线性** 200ms（`GreyToneAnimation`，`sGreyTone = (0.2,0.2,0.2,0)`）；
 * - 同时从触点长出一个椭圆：半径 = 进度 × **纹理矩形对角线**，`p = t²`（`AccelerateInterpolator(1.0)`），1.5s 长满；
 *   圆心按 p 从触点插值到矩形中心（原版 y 用 `centerX()` 是它的 bug，这里用 centerY）；
 * - 圆内**再叠一次**同样的灰（`if (d<1) gl_FragColor += u_GreyColor`）⇒ 圆内合计 +0.4；
 * - 灰与椭圆都满（0.2+1.5=1.7s）后 `pause()` **停在最大**；
 * - 松手 / 进入多选：`finishUp(0.7f)` ⇒ 剩余部分压缩进 **700ms** 走完，随后 uniform 复位（`TIME = 2.7`）；
 * - 起手延迟 **80ms**（`ScrollUiBase` 的 `postDelayed` 抗抖）。
 *
 * 用 [BlendMode.Plus] 而不是叠半透明白：原版是 `gl_FragColor += u_GreyColor`（加法），
 * 叠白会把亮部压暗、暗部又补不够，观感对不上。
 *
 * @param pressed 是否处于按下态（来自 `interactionSource.collectIsPressedAsState()`）
 * @param fastFinish 需要立刻收尾（例如进入多选、格子被选中）
 */
@Composable
internal fun Modifier.pressRipple(pressed: Boolean, fastFinish: Boolean = false): Modifier {
    val clock = remember { Animatable(0f) }
    var running by remember { mutableStateOf(false) }
    // 触点（相对本节点；`Initial` 阶段只看不消费，不抢 clickable 的手势）
    var point by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(pressed) {
        if (pressed) {
            delay(RIPPLE_PRESS_DELAY_MS)
            clock.snapTo(0f)
            running = true
            clock.animateTo(RIPPLE_HOLD_MS, tween(RIPPLE_HOLD_MS.toInt(), easing = LinearEasing))
        } else if (running) {
            clock.animateTo(RIPPLE_TOTAL_MS, tween(RIPPLE_FINISH_MS, easing = LinearEasing))
            running = false
        }
    }
    LaunchedEffect(fastFinish) {
        if (fastFinish && running) {
            clock.animateTo(RIPPLE_TOTAL_MS, tween(RIPPLE_FINISH_MS, easing = LinearEasing))
            running = false
        }
    }

    val t = clock.value
    val grey = when {
        t <= 0f -> 0f
        t < RIPPLE_HOLD_MS -> 0.2f * (t / RIPPLE_GREY_RAMP_MS).coerceIn(0f, 1f)
        else -> 0.2f * (1f - ((t - RIPPLE_HOLD_MS) / RIPPLE_FADE_MS).coerceIn(0f, 1f))
    }
    val p = (t / RIPPLE_ELLIPSE_MS).coerceIn(0f, 1f).let { it * it }

    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        event.changes.firstOrNull()?.let { point = it.position }
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (grey > 0.002f) {
                val tint = Color(grey, grey, grey, 1f)
                drawRect(color = tint, blendMode = BlendMode.Plus)
                if (p > 0.02f) {
                    // 半径 = 进度 × 对角线（原版 `calcHypo(rect) * 2` 直接当半轴用）
                    val radius = p * hypot(size.width, size.height)
                    val cx = point.x + (size.width / 2f - point.x) * p
                    val cy = point.y + (size.height / 2f - point.y) * p
                    drawOval(
                        color = tint,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(radius * 2f, radius * 2f),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
}

// ---------- 9.9 `RippleAnimation` 的数值（与格子/列表共用）----------

/** `ScrollUiBase`：touch down 后 `postDelayed(…, 80)` 才进 highlight */
internal const val RIPPLE_PRESS_DELAY_MS = 80L

/** `TO_GREY_TIME = 0.2`：叠灰 0→0.2 用时 200ms（线性） */
internal const val RIPPLE_GREY_RAMP_MS = 200f

/** `CIRCLE_TIME = 1.5`：椭圆长满用时 1500ms（`p = t²`） */
internal const val RIPPLE_ELLIPSE_MS = 1500f

/** 长满后停在最大（`pauseAtMaximum`）：0.2 + 1.5 = 1.7s */
internal const val RIPPLE_HOLD_MS = 1700f

/** `TIME = 2.7`；1.7s 之后再用 1.0s 把叠灰收干净 */
internal const val RIPPLE_TOTAL_MS = 2700f
internal const val RIPPLE_FADE_MS = 1000f

/** `FINISH_DURATION = 700`：松手/进入选中后剩余部分压到 700ms 内走完 */
internal const val RIPPLE_FINISH_MS = 700
