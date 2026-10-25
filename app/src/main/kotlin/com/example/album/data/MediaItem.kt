package com.example.album.data

import android.net.Uri
import androidx.compose.runtime.Immutable

/** 网格里一项的类型（决定角标与解码方式；GIF 也在 Image 里，只出静帧）。 */
enum class MediaKind { Image, Video }

/**
 * 时间线上的一个媒体项。轻量：只带 UI 需要的列，Cursor 映射完立刻关闭，不持有 Cursor。
 *
 * @param dateTakenMillis **排序键与分组键是同一个值**：`DATE_TAKEN` 缺失（部分视频没有拍摄时间）时
 *   退回 `DATE_ADDED × 1000`。两者若不一致，同一天会被切成两段、日期头重复出现。
 * @param durationMillis 仅视频有意义；图片为 0。
 * @param widthPx/heightPx 原始像素尺寸（查看器用它算"图在屏上的矩形"，共享元素要靠这个几何连续）。
 * @param sizeBytes 文件字节数（详情页只读展示）。
 * @param bucketId/bucketName 所属文件夹（P2 文件夹网格按它过滤）。
 * @param volumeName 卷名（`external_primary` = 内置；其它 = 外置存储卡）
 */
@Immutable
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val kind: MediaKind,
    val dateTakenMillis: Long,
    val durationMillis: Long,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val sizeBytes: Long = 0L,
    val displayName: String = "",
    val bucketId: String = "",
    val bucketName: String = "",
    val volumeName: String = "",
)

/**
 * 显示用宽高比（宽 / 高）。9.9 `AggregatorUtil.getAspectRatio`：宽或高未知时按 **1.0**；
 * 原版还会按 EXIF `ORIENTATION` 交换宽高，但 MediaStore 的 `WIDTH/HEIGHT` 已经给的是摆正后的尺寸。
 *
 * 只有 1 列版式用它（`LinearLayout.java:209`，图高 = 图宽 / 比例）。
 */
fun MediaItem.aspectRatio(): Float =
    if (widthPx <= 0 || heightPx <= 0) 1f else widthPx.toFloat() / heightPx.toFloat()
