package com.example.album.ui.common

import androidx.compose.animation.core.Easing

/**
 * 9.9 的 easeOut：`1 − (1 − t)³`（`AnimationTimePosFunctions.EaseOutTimePosFunc`）。
 * Compose 自带的 `EaseOut` 是 Material 的三次贝塞尔，和索尼这条曲线不是一回事，别混用。
 *
 * （原文件里"共享元素"的键/时长已随用户 2026-10-24 令——查看器改用平台默认 Activity 动画——删除。）
 */
val AlbumEaseOut: Easing = Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) }
