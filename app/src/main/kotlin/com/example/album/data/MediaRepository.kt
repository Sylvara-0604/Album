package com.example.album.data

import android.app.PendingIntent
import android.database.ContentObserver
import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/** 查看器窗口：一段连续的媒体项 + 它在时间线里的绝对起始下标。 */
data class MediaWindow(val base: Int, val items: List<MediaItem>)

/** 唯一的仓库：分页 / banner 候选 / 内容变化监听。UI 只跟它打交道，不直接碰 ContentResolver。 */
class MediaRepository(private val source: MediaStoreSource) {

    /**
     * 时间线分页。`bucketId` 非空 = 只看这个文件夹（文件夹网格与文件夹封面复用同一套分页/分组/网格代码）。
     *
     * @param onSource 每次 `pagingSourceFactory` 建出新的 [MediaStorePagingSource] 时回调一次：调用方
     *   （`TimelineViewModel`）拿住它，库内容变化时直接 `invalidte()` —— 那才是 Paging3 的失效语义
     *   （不重建 Pager、不把 `itemCount` 归零，也就没有"刷新闪一下"）。
     */
    fun pagedMedia(
        bucketId: String? = null,
        sortByName: Boolean = false,
        onSource: (MediaStorePagingSource) -> Unit = {},
    ): Flow<PagingData<MediaItem>> = Pager(
        config = PAGING_CONFIG,
        pagingSourceFactory = { MediaStorePagingSource(source, bucketId, sortByName).also(onSource) },
    ).flow

    /**
     * 文件夹列表（一次扫描，按名称升序）。文件夹数量少，UI 自己决定何时拉一次。
     *
     * = MediaStore 里有图片/视频的 bucket，**没有别的来源**（用户 2026-10-18 令：
     * 「文件夹列表只查询/显示包含图片/视频文件的目录。空目录不要获取」⇒ 不再问文件系统列空目录）。
     */
    suspend fun buckets(): List<MediaBucket> = source.buckets()

    /**
     * 查看器窗口：以某个**绝对下标**为中心取一段（`base` 是 items[0] 的绝对下标）。
     * 查看器用它一次拿齐可左右翻的邻域，翻页不重查。
     */
    suspend fun window(
        centerIndex: Int,
        before: Int = 100,
        after: Int = 100,
        bucketId: String? = null,
        sortByName: Boolean = false,
    ): MediaWindow {
        val base = (centerIndex - before).coerceAtLeast(0)
        // 下标空间必须和来源一致：文件夹网格里的下标只有配上同一个 bucketId 才对得上
        val items = source.page(base, before + after + 1, bucketId, sortByName)
        return MediaWindow(base = base, items = items)
    }

    /** banner 候选池（最近 [BANNER_POOL_SIZE] 张静态图片），一次查询后缓存复用。 */
    suspend fun bannerCandidates(): List<MediaItem> = source.bannerCandidates(BANNER_POOL_SIZE)

    /** 选中 id → 本卷的类型化媒体 URI（分享 / 删除 / 移动都用它，见 `MediaStoreSource.mediaUris`）。 */
    suspend fun mediaUris(ids: List<Long>): List<Uri> = source.mediaUris(ids)

    /** 新建文件夹流程的第一步：`MediaStore.createWriteRequest` 的系统确认弹窗。 */
    fun writeRequest(uris: List<Uri>): PendingIntent = source.writeRequest(uris)

    /** 新建文件夹流程的第二步：把已授权的媒体移到 `Pictures/<folderName>/`，返回成功条数。 */
    suspend fun moveToPictures(uris: List<Uri>, folderName: String): Int =
        source.moveToPictures(uris, folderName)

    fun addObserver(observer: ContentObserver) = source.addObserver(observer)

    fun removeObserver(observer: ContentObserver) = source.removeObserver(observer)

    private companion object {
        const val PAGE_SIZE = 60
        const val INITIAL_LOAD_SIZE = 120
        const val PREFETCH_DISTANCE = 30

        /** 与蓝图 §6.2.1 #2 一致：只在"最近 500 张"里随机，不做全表随机 */
        const val BANNER_POOL_SIZE = 500

        val PAGING_CONFIG = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false,
            // 提前 30 项开始取下一页：滚动到末尾前数据已经在路上，避免"滑到底再等"
            prefetchDistance = PREFETCH_DISTANCE,
        )
    }
}
