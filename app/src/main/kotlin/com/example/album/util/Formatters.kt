package com.example.album.util

import java.util.Locale

/** 纯格式化函数（单测对象）。 */
object Formatters {

    /**
     * 缓存/文件大小：`512 B` / `1.5 KB` / `12.3 MB` / `1.2 GB`。
     * 0 或负数返回空串（宁可不显示，也不显示 `0 B` 骗人）。
     */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val value = bytes.toDouble()
        return when {
            value < kb -> "$bytes B"
            value < mb -> String.format(Locale.getDefault(), "%.1f KB", value / kb)
            value < gb -> String.format(Locale.getDefault(), "%.1f MB", value / mb)
            else -> String.format(Locale.getDefault(), "%.1f GB", value / gb)
        }
    }

    /**
     * 视频时长角标：`m:ss`，满 1 小时才变 `h:mm:ss`（与 9.9 一致）。
     * 未知/非法（≤0）返回空串 —— 宁可不出角标，也不显示 `0:00` 骗人。
     */
    fun duration(millis: Long): String {
        if (millis <= 0L) return ""
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "$hours:${pad(minutes)}:${pad(seconds)}"
        } else {
            "$minutes:${pad(seconds)}"
        }
    }

    private fun pad(value: Long): String = if (value < 10L) "0$value" else value.toString()
}
