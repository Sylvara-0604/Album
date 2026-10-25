package com.example.album.ui.common

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * **平台默认 Activity 切换动画**（用户 2026-10-24 令：首页↔设置、查看器进出「直接抄 Glimpse」）。
 *
 * ## 为什么是这一套
 * Glimpse（`LineageOS/android_packages_apps_Glimpse` @ `lineage-24.0`）的查看器/设置页都是**普通
 * `startActivity`**（`ViewActivity.kt`、`MainFragment.kt:49` 的 `startActivity(intent)`、`SettingsActivity`），
 * 全仓库**没有**任何 `res/anim`、`TransitionManager`、`MaterialSharedAxis`、`overrideActivityTransition`
 * ⇒ 它用的就是 **framework 默认的 Activity 动画**。所以"抄 Glimpse" = 照抄 AOSP 那一套窗口动画：
 *
 * 打开（AOSP `core/res/res/anim/activity_open_enter.xml` + `activity_open_exit.xml`）
 *  - 新页：`translate 96dp → 0`，450ms，`@interpolator/fast_out_extra_slow_in`；
 *          `alpha 0 → 1`，83ms，`linear`，**延迟 50ms**（前 50ms 完全透明）；
 *  - 旧页：`translate 0 → −96dp`，450ms，同曲线；**alpha 恒 1**。
 *
 * 关闭（`activity_close_enter.xml` + `activity_close_exit.xml`）
 *  - 父页：`translate −96dp → 0`，450ms，同曲线；alpha 恒 1；
 *  - 关闭页：`translate 0 → 96dp`，450ms；`alpha 1 → 0`，83ms，`linear`，**延迟 35ms**。
 *
 * `fast_out_extra_slow_in` 是**路径插值器**（`core/res/res/interpolator/fast_out_extra_slow_in.xml`）：
 * `M 0,0 C 0.05,0 0.133333,0.06 0.166666,0.4 C 0.208333,0.82 0.25,1 1,1`
 * —— 不是 cubic-bezier，所以这里用 [PathEasing] 原样复刻（Compose 的 `FastOutSlowInEasing` 不是这条曲线）。
 *
 * **只用于**：首页 ↔ 设置、网格/文件夹 → 查看器及其返回。其余换页仍是 9.9 的逐项 z 动画（用户令）。
 */
val PLATFORM_SLIDE = 96.dp

/** 平台动画时长（AOSP 四个 XML 里都是 450ms） */
const val PLATFORM_SLIDE_MILLIS = 450

/** 打开时新页淡入：83ms，延迟 50ms */
const val PLATFORM_FADE_IN_MILLIS = 83
const val PLATFORM_FADE_IN_DELAY_MILLIS = 50

/** AOSP `fast_out_extra_slow_in`（两段三次贝塞尔组成的 pathInterpolator） */
val PlatformSlideEasing: Easing = PathEasing(
    Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    },
)

/**
 * 平台"打开"：新页从右滑入 + 延迟 50ms 的 83ms 淡入。
 *
 * @param slidePx [PLATFORM_SLIDE] 的像素值（在组合里用 `LocalDensity` 算好再传进来 —— 这些函数
 *   要在 Nav3 的 `transitionSpec` 里调用，那里不是 @Composable 上下文）。
 */
fun platformOpenEnter(slidePx: Int): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(PLATFORM_SLIDE_MILLIS, easing = PlatformSlideEasing),
        initialOffsetX = { slidePx },
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = PLATFORM_FADE_IN_MILLIS,
            delayMillis = PLATFORM_FADE_IN_DELAY_MILLIS,
            easing = LinearEasing,
        ),
    )

/** 平台"打开"：旧页向左滑 96dp（alpha 恒 1）。 */
fun platformOpenExit(slidePx: Int): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(PLATFORM_SLIDE_MILLIS, easing = PlatformSlideEasing),
        targetOffsetX = { -slidePx },
    )

/**
 * 平台"关闭"：父页**整幅从左侧滑入**（alpha 恒 1）。
 *
 * 为什么改成"整幅 wipe"而不是 AOSP 的"−96dp 滑回"：Nav3 的 `AnimatedContent` 里**目标页画在上层**，
 * 关闭页的位移被父页盖住、看不见，只剩父页"压"在关闭页上的那点透明度 = 用户 2026-10-24 报的
 * 「C1 返回时有重影」。整幅 wipe 之后每一帧都是"左块父页 / 右块关闭页"、两边都不透明，
 * 结构上不可能出现两层叠影。
 *
 * **方向开关只有这一行**：`initialOffsetX = { it }` / `{ -it }`。
 * 实测（2026-10-24 r35，慢动作 3000ms + 中途截图）：`{ -it }` 表现为"父页从**右**侧进入"，
 * 所以这里取 `{ it }` 让父页从**左**侧回来（back 的方向感）。若用户觉得方向反了，
 * 把这一行取负即可，其余不用动。
 */
fun platformCloseEnter(slidePx: Int): EnterTransition {
    // 形参保留：调用方（popContent）按同一份签名传 96dp 的像素值，便于以后回到 AOSP 版
    check(slidePx >= 0)
    return slideInHorizontally(
        animationSpec = tween(PLATFORM_SLIDE_MILLIS, easing = PlatformSlideEasing),
        // 位移量取"自身宽度"（lambda 形参就是这个内容的全宽）
        initialOffsetX = { it },
    )
}

/**
 * 关闭时旧页的离场：**整幅右滑出去，不淡出**。
 *
 * ⚠️ 2026-10-24 r35 改（用户报「C1 返回时有重影」）：原来照 AOSP 用"96dp 右滑 + 尾段 170ms 淡出"。
 * 问题在**淡出这一步**：AOSP 能用它，是因为它在真窗口栈里、关闭页跑在父页之上、并靠
 * `zAdjustment` 排序；我们在同一个 Compose 场景里，两页都是 `fillMaxSize` 不透明，
 * 只要关闭页 α 降到 1 以下，就会**透出背后的父页** = 两层同时可见 = 用户说的"重影"。
 *
 * r35 定稿：**关闭页原地不动、最后 1ms 移除**，整段由父页整幅 wipe 盖过去。
 * 这样"任一帧都只有一条边界、两边都不透明"⇒ 结构上不可能出现两层叠影。
 * （原打算让关闭页自己右滑出去，但 Nav3 的 `AnimatedContent` 里**目标页画在上层**，
 *  关闭页的位移根本看不见，写了也白写。）
 */
fun platformCloseExit(): ExitTransition =
    // 关闭页原地不动，只在最后 1ms 被移除（整段过程它都被父页 wipe 盖着，看不见也摸不到）
    fadeOut(tween(durationMillis = 1, delayMillis = PLATFORM_SLIDE_MILLIS - 1))
