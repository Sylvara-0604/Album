package com.example.album.data

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * 一个文件夹（MediaStore 的 bucket）。
 *
 * 只来自 MediaStore 里有媒体文件的行 ⇒ **不存在"空文件夹"**（用户 2026-10-18 令：
 * 「文件夹列表只查询/显示包含图片/视频文件的目录。空目录不要获取（没有图片你显示它干嘛）」）。
 *
 * @param name 文件夹名；**空串 = 存储根目录**（`bucket_display_name` 为空），显示文案由 UI 按资源给
 * @param count 该文件夹里的图片 + 视频总数（恒 ≥ 1）
 * @param coverUri 最新一张的缩略图来源 URI（整行满幅封面 / 网格首格），恒非空
 * @param volumeName `external_primary` = 内置存储；其它 = 外置卡（决定行里画芯片还是 SD 卡图标）
 */
@Immutable
data class MediaBucket(
    val id: String,
    val name: String,
    val count: Int,
    val coverUri: Uri,
    val volumeName: String,
)
