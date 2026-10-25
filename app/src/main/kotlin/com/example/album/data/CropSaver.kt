package com.example.album.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.compose.ui.geometry.Rect
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 输出长边上限：见 [CropSaver] 的取舍说明 */
private const val MAX_OUTPUT_EDGE = 2048

/** JPEG 质量：95 是"肉眼看不出二次压缩、体积还压得住"的那一档 */
private const val JPEG_QUALITY = 95

private const val PNG_MIME = "image/png"
private const val JPEG_MIME = "image/jpeg"
private const val PNG_EXT = ".png"
private const val JPG_EXT = ".jpg"
private const val CROP_SUFFIX = "_crop"
private const val FALLBACK_BASE_NAME = "image"

/**
 * 尽力搬过去的 EXIF：拍摄时间（含时区）+ 机型 + 拍摄地点。
 *
 * **故意不搬 `TAG_ORIENTATION`**：`ImageDecoder` 解码时已经按 EXIF 摆正，再写一遍方向
 * 等于让看图工具摆第二次（图会横躺）。
 */
private val EXIF_TAGS = listOf(
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
)

/**
 * 裁剪结果落库：`ImageDecoder` 采样解码 → 取裁剪区域 → `MediaStore` 插入（`IS_PENDING=1` → 写完 → `IS_PENDING=0`）。
 *
 * 三条取舍：
 * 1. **输出长边上限 [MAX_OUTPUT_EDGE]（2048）**：先按目标尺寸 `setTargetSize` 解码整图，再从解码结果里取区域，
 *    而不是"解全尺寸再裁"。一张 48MP 照片全尺寸 ARGB 峰值约 190MB；2048 长边最多约 16MB。
 *    代价是输出分辨率有上限（不是无损裁剪）——想更大改这一个常量，内存按平方增长。
 * 2. **格式跟着源走**：源是 PNG 就写 PNG（保住透明通道），其余一律 JPEG q95。
 * 3. **覆盖 = 先写新项、成功后再删旧项**：任何一步失败都不会出现"旧的没了、新的没写成"。
 *
 * EXIF 只搬拍摄时间/机型/GPS，且**尽力而为**（取不到就跳过，绝不让保存失败）。
 * 方向由 `ImageDecoder` 在解码阶段归一，不再写 Orientation。
 *
 * 坐标约定：[save] 的 `crop` 是"**摆正后的原图像素**"（原点在原图左上角），坐标系见 [orientedImageSize]；
 * 采样后的实际位图更小，函数内部按比例缩过去。全部 IO 在 `Dispatchers.IO`，失败抛异常交给 UI 显示。
 */
object CropSaver {

    /**
     * 保存裁剪结果。
     *
     * @param crop 摆正后原图像素坐标系里的裁剪矩形（由 UI 层从"显示矩形内的像素"按比例换算而来）
     * @param overwrite true = 新图写成功后再删掉原图；false = 只新增一份副本，原图不动
     * @return 新项的 `content://` Uri（`IS_PENDING` 已复位，相册立即可见）
     */
    suspend fun save(context: Context, source: MediaItem, crop: Rect, overwrite: Boolean): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val decoded = decode(resolver, source.uri)
            val region = regionOf(crop, decoded.space, decoded.bitmap.width, decoded.bitmap.height)
            // 区域已被夹进位图内且宽高 ≥ 1，不会越界
            val cropped = Bitmap.createBitmap(decoded.bitmap, region.left, region.top, region.width, region.height)
            writeItem(resolver, source, decoded, cropped, overwrite)
        }

    /** 把裁剪矩形映射到采样位图的像素区域：左/上向下取整、右/下向上取整，宁可多留一像素也不切掉内容。 */
    private fun regionOf(crop: Rect, space: Size, outWidth: Int, outHeight: Int): Region {
        val scaleX = outWidth.toFloat() / space.width.coerceAtLeast(1)
        val scaleY = outHeight.toFloat() / space.height.coerceAtLeast(1)
        val left = floor(crop.left * scaleX).toInt().coerceIn(0, outWidth - 1)
        val top = floor(crop.top * scaleY).toInt().coerceIn(0, outHeight - 1)
        val right = ceil(crop.right * scaleX).toInt().coerceIn(left + 1, outWidth)
        val bottom = ceil(crop.bottom * scaleY).toInt().coerceIn(top + 1, outHeight)
        return Region(left, top, right - left, bottom - top)
    }

    private fun writeItem(
        resolver: ContentResolver,
        source: MediaItem,
        decoded: Decoded,
        bitmap: Bitmap,
        overwrite: Boolean,
    ): Uri {
        val png = decoded.mimeType.equals(PNG_MIME, ignoreCase = true)
        val volume = source.volumeName.ifEmpty { MediaStore.VOLUME_EXTERNAL_PRIMARY }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, targetDisplayName(source, png))
            put(MediaStore.MediaColumns.MIME_TYPE, if (png) PNG_MIME else JPEG_MIME)
            // 先挂 pending：写一半被杀掉也不会在相册里留一张坏图
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // DATE_TAKEN 必须跟着搬：时间线按它排序/分天，丢了它裁剪结果会跳到"今天"
            if (source.dateTakenMillis > 0L) put(MediaStore.MediaColumns.DATE_TAKEN, source.dateTakenMillis)
            // 放回原来的文件夹（拿不到就用系统默认 Pictures/）
            relativePathOf(resolver, source.uri)?.let { put(MediaStore.MediaColumns.RELATIVE_PATH, it) }
        }
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val target = resolver.insert(collection, values)
            ?: throw IOException("相册拒绝了新文件（MediaStore.insert 返回 null）")

        try {
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            // JPEG 没有 alpha：半透明直接压会变黑，先合成到白底上
            val toWrite = if (!png && bitmap.hasAlpha()) flattenOnWhite(bitmap) else bitmap
            val stream = resolver.openOutputStream(target, "w")
                ?: throw IOException("打不开新文件的输出流")
            stream.use {
                if (!toWrite.compress(format, JPEG_QUALITY, it)) throw IOException("图片编码失败")
            }
            copyExif(resolver, source.uri, target)
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, published, null, null)
        } catch (failure: Exception) {
            // 半成品不能让用户永远看不到又白占空间：先删掉 pending 项，再把错误交给 UI
            runCatching { resolver.delete(target, null, null) }
            throw failure
        }

        if (overwrite) {
            // 先写成功再删旧（顺序不能反）：删这一步失败会留下两份，所以如实报错；
            // 此时新副本已经落库，用户的裁剪结果不会丢。
            // 注意：删"非本应用创建"的项需要用户确认 —— API 30+ 的 `ContentResolver.delete`
            // 抛的是普通 `SecurityException`（`RecoverableSecurityException` 只是 API 29/旧 target 的路径，
            // 且它本身继承 SecurityException）。本页没有确认流程，于是把失败抛给 UI 处理。
            val removed = resolver.delete(source.uri, null, null)
            if (removed <= 0) throw IOException("新副本已写入，但原图没有被删除")
        }
        return target
    }

    private fun decode(resolver: ContentResolver, uri: Uri): Decoded {
        var infoSize: Size? = null
        var mimeType: String? = null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            infoSize = info.size
            mimeType = info.mimeType
            // 硬件位图读不了像素、也压不回文件；裁剪必须走软件分配（代价是占普通内存）
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val target = fitWithin(info.size, MAX_OUTPUT_EDGE)
            decoder.setTargetSize(target.width, target.height)
        }
        return Decoded(
            bitmap = bitmap,
            space = orientedImageSize(
                info = infoSize ?: Size(bitmap.width, bitmap.height),
                decodedWidth = bitmap.width,
                decodedHeight = bitmap.height,
            ),
            mimeType = mimeType ?: JPEG_MIME,
        )
    }

    /** EXIF 是加分项：读不到/写不进都只意味着新图少几条元数据，绝不能让已经写好的文件失败。 */
    private fun copyExif(resolver: ContentResolver, source: Uri, target: Uri) {
        val attributes = runCatching { readExif(resolver, source) }.getOrNull().orEmpty()
        if (attributes.isEmpty()) return
        runCatching {
            resolver.openFileDescriptor(target, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                attributes.forEach { (tag, value) -> exif.setAttribute(tag, value) }
                exif.saveAttributes()
            }
        }
    }

    /** 先把要留的标签读进内存再关 fd：不长期持有 ParcelFileDescriptor。 */
    private fun readExif(resolver: ContentResolver, source: Uri): List<Pair<String, String>> {
        val pfd = resolver.openFileDescriptor(source, "r") ?: return emptyList()
        return pfd.use {
            val exif = ExifInterface(it.fileDescriptor)
            EXIF_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { value -> tag to value } }
        }
    }

    private fun relativePathOf(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun targetDisplayName(source: MediaItem, png: Boolean): String {
        val base = source.displayName
            .substringBeforeLast('.', missingDelimiterValue = source.displayName)
            .ifBlank { FALLBACK_BASE_NAME }
        return base + CROP_SUFFIX + if (png) PNG_EXT else JPG_EXT
    }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        val flattened = createBitmap(bitmap.width, bitmap.height)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flattened
    }

    private class Decoded(val bitmap: Bitmap, val space: Size, val mimeType: String)

    private class Region(val left: Int, val top: Int, val width: Int, val height: Int)
}

/**
 * 裁剪坐标空间的唯一基准：把 `ImageDecoder` 报的 `info.size` 折算成"**摆正后的原图像素**"尺寸。
 *
 * 为什么需要这一步：`ImageDecoder` 解码时会按 EXIF 摆正，但个别机型/编码器在 `info.size` 里报的是
 * **未摆正**的宽高。判据只能是宽高比——两者的宽高比不一致（90° 对调会把比例翻成倒数）就把宽高对调。
 * 预览解码（`CropViewModel`）与保存解码（[CropSaver]）都用这一个函数，两边的裁剪矩形才落在同一坐标系。
 *
 * 采样带回 ±1px 取整误差，所以用相对误差判等（10%：远大于取整误差，又远小于对调造成的差距）。
 */
internal fun orientedImageSize(info: Size, decodedWidth: Int, decodedHeight: Int): Size {
    if (info.width <= 0 || info.height <= 0) return Size(decodedWidth, decodedHeight)
    if (decodedWidth <= 0 || decodedHeight <= 0) return info
    val infoAspect = info.width.toFloat() / info.height
    val decodedAspect = decodedWidth.toFloat() / decodedHeight
    val sameOrientation = abs(infoAspect - decodedAspect) <= 0.1f * decodedAspect
    return if (sameOrientation) info else Size(info.height, info.width)
}

/** 等比缩到长边 ≤ [maxEdge]（用于 `ImageDecoder.setTargetSize`，永不返回 0 边长）。 */
internal fun fitWithin(size: Size, maxEdge: Int): Size {
    val longest = maxOf(size.width, size.height)
    if (longest <= maxEdge || longest <= 0 || maxEdge <= 0) return size
    val ratio = maxEdge.toFloat() / longest
    return Size(
        (size.width * ratio).roundToInt().coerceAtLeast(1),
        (size.height * ratio).roundToInt().coerceAtLeast(1),
    )
}
