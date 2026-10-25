package com.example.album.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Log
import android.util.Size
import androidx.compose.runtime.Immutable
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.asImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Coil 的 model：只说明"要哪张系统缩略图"，目标像素由 `ImageRequest.size` 决定。
 * 用 data class 是为了让 Coil 的默认相等判定稳定（同一张图不同尺寸靠 size 区分）。
 *
 * `id` 只用于把解码结果登记到 [ThumbnailPlaceholders]（占位缓存按 media id 命中，与档位无关）。
 */
@Immutable
data class MediaThumbnail(val uri: Uri, val id: Long)

/**
 * 缩略图的内存缓存键。**唯一判据**：网格画格子和查看器「先出图」必须用同一个键，
 * 这样点开图片时查看器第一帧直接命中网格已经解好的那张位图（0ms，不必等全尺寸解码）。
 * 键里带像素：列数变了尺寸就变，不能让 7 列的图复用 3 列的解码结果（会糊）。
 */
fun thumbnailMemoryKey(mediaId: Long, px: Int): String = "thumb:$mediaId:$px"

/**
 * 「刚刚画出来的那张缩略图」——给 `AsyncImage` 当**同步占位**用的极小 LRU（按字节计）。
 *
 * ⚠️ 2026-10-24 起它**只是兜底**（"重新进入页面、本格还没画过图"时用）。
 * 主占位改成**每格自己记的上一张 painter**（见 `MediaGrid.MediaCell`）——原因是用户录屏逐帧取证：
 * 换档提交那一刻整屏变纯灰 `surfaceVariant` 一帧，根因就是这份全局 LRU 的容量按**字节**算：
 * 1–2 列（640 档 ≈1.6MB/张）一屏 8 张就超 12MB、7 列一屏 100+ 张（≈0.1MB/张）也贴着上限
 * ⇒ 提交时大批/全部格子被淘汰、命中不到。每格留一份没有预算问题。
 *
 * 即便有了主占位，这份兜底仍然有用：`AsyncImage` 换 model 会落到 Loading，此时没有"上一张"可画。
 */
internal object ThumbnailPlaceholders {
    private val cache = object : LruCache<Long, Bitmap>(PLACEHOLDER_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    fun put(mediaId: Long, bitmap: Bitmap) {
        if (mediaId > 0L && bitmap.byteCount in 1..(PLACEHOLDER_CACHE_BYTES / 4)) {
            cache.put(mediaId, bitmap)
        }
    }

    fun get(mediaId: Long): Bitmap? = cache.get(mediaId)

    fun clear() = cache.evictAll()
}

/** 12 MB：约 40 张 512² ARGB_8888，够铺满一屏多、又不会和 Coil 自己的缓存叠太多。 */
private const val PLACEHOLDER_CACHE_BYTES = 12 * 1024 * 1024

/**
 * 缩略图取样尺寸**按档位取整**（向上取到最近的档）。
 *
 * 为什么（用户 2026-10-24 报「有时候页面还是会闪（也可能是刷新）」）：换列后 `cellPx` 变一点，
 * 键就变 ⇒ 整屏格子同时换请求、全部 miss 内存缓存 ⇒ 那几帧只剩灰底（看着像闪一下）。
 * 换成档位后，"7 列↔6 列"这种小变化落在同一档 ⇒ 直接命中已解好的位图；档位向上取整，
 * 不会出现"解小了再放大"的糊。
 */
internal fun bucketSamplePx(px: Int): Int {
    val p = px.coerceAtLeast(1)
    for (bucket in SAMPLE_BUCKETS) if (p <= bucket) return bucket
    return SAMPLE_BUCKETS.last()
}

private val SAMPLE_BUCKETS = intArrayOf(
    128, 160, 192, 256, 320, 384, 512, 640, 768, 1024, 1280, 1536, 2048, 2560,
)

/**
 * 网格与查看器共用的缩略图请求：精确到显示像素，禁磁盘缓存（系统随时能重建，省 IO 与电量）。
 */
fun thumbnailRequest(context: Context, item: MediaItem, px: Int): ImageRequest =
    thumbnailRequest(context, item.id, item.uri, px)

/**
 * 同上，但只要 `id + uri`。
 *
 * 查看器的**首帧**要在"窗口数据还没查回来"时就画出来（此时手里只有点击时带过来的 id/uri），
 * 内存缓存键必须与网格那一格完全一致（`thumb:<id>:<px>`），这样第一帧直接命中已解好的位图。
 */
fun thumbnailRequest(context: Context, mediaId: Long, uri: Uri, requestedPx: Int): ImageRequest {
    val px = bucketSamplePx(requestedPx)
    return ImageRequest.Builder(context)
        .data(MediaThumbnail(uri, mediaId))
        .size(px, px)
        .memoryCacheKey(thumbnailMemoryKey(mediaId, px))
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()
}

/**
 * 缩略图走系统 `ContentResolver.loadThumbnail`：由 mediaserver 生成并缓存，比全图解码快一个量级，
 * 视频取帧也走同一条路。解码尺寸精确到显示像素（不让 Coil 猜、也不解全尺寸）。
 *
 * 返回的是 `ImageFetchResult`（已解码位图），Coil 的缓存/复用/优先级仍由官方管线负责，
 * 磁盘缓存由调用方逐请求关闭（系统能随时重建，省 IO）。
 */
class MediaStoreThumbnailFetcher private constructor(
    private val data: MediaThumbnail,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // 没有明确尺寸就不猜：宁可不显示，也不退化成全尺寸解码
        val px = options.size.width.pxOrElse { 0 }
        if (px <= 0) return null

        val bitmap = withContext(Dispatchers.IO) { loadThumbnail(px) }

        // 登记"刚画出来的这张"：网格/查看器下次同步占位直接命中（见 [ThumbnailPlaceholders]）
        ThumbnailPlaceholders.put(data.id, bitmap)

        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * 取系统缩略图，**失败重试**后再抛出。
     *
     * 为什么必须重试（用户 2026-10-18 报「列数最多时首段那 10 格里最靠后 4 个有概率空白」）：
     * 原来这里 catch 住 `IOException` 直接返回 null ⇒ Coil 当成"取不到数据"画空白，而且**一次失败就永远
     * 不会重来**（调度器不会为失败请求排队重试），只能等这一格被回收重组才可能恢复。缩略图由
     * mediaserver 现生成，刚扫到的新图/瞬时失败很常见 ⇒ 表现为"偶发、位置随机、但总是后请求的那几格"。
     * 现在：瞬时失败重试 [LOAD_ATTEMPTS] 次（间隔 [RETRY_DELAY_MILLIS]），仍然失败才抛出，
     * 让 Coil 走错误通道并在 logcat 留下明确的 uri + 尺寸。
     */
    private suspend fun loadThumbnail(px: Int): Bitmap {
        var last: Throwable? = null
        for (attempt in 1..LOAD_ATTEMPTS) {
            try {
                return options.context.contentResolver.loadThumbnail(data.uri, Size(px, px), null)
            } catch (e: SecurityException) {
                // 权限不会因为等待而出现，重试没有意义
                Log.w(TAG, "no permission to load thumbnail uri=${data.uri} px=$px", e)
                throw IOException("cannot read ${data.uri}", e)
            } catch (e: IOException) {
                last = e
                if (attempt < LOAD_ATTEMPTS) delay(RETRY_DELAY_MILLIS)
            }
        }
        Log.w(TAG, "loadThumbnail failed after $LOAD_ATTEMPTS attempts uri=${data.uri} px=$px", last)
        throw IOException("loadThumbnail failed: ${data.uri} @${px}px", last)
    }

    class Factory : Fetcher.Factory<MediaThumbnail> {
        override fun create(data: MediaThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaStoreThumbnailFetcher(data, options)
    }

    private companion object {
        /** 只重试"瞬时失败"：同一格连续的失败再多次也一样，避免无谓 IO */
        const val LOAD_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 120L
        const val TAG = "MediaThumbnail"
    }
}
