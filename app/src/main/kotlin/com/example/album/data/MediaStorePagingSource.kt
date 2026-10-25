package com.example.album.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.io.IOException

/**
 * MediaStore 分页源。
 *
 * 页键 = **偏移量**（MediaStore 唯一支持的定位方式：Bundle 查询只认 LIMIT/OFFSET）。
 * `getRefreshKey` 用 Paging 官方配方（锚点项所在页的起止推算），保证库内容变化后
 * 刷新仍落在"用户正在看的那一条"附近，而不是跳回顶部。
 */
class MediaStorePagingSource(
    private val source: MediaStoreSource,
    private val bucketId: String? = null,
    /** 文件夹内容页按名称升序（首页时间线用日期倒序） */
    private val sortByName: Boolean = false,
) : PagingSource<Int, MediaItem>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> {
        val offset = params.key ?: 0
        return try {
            val items = source.page(offset, params.loadSize, bucketId, sortByName)
            LoadResult.Page(
                data = items,
                prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                nextKey = if (items.isEmpty()) null else offset + items.size,
            )
        } catch (e: IOException) {
            LoadResult.Error(e)
        } catch (e: SecurityException) {
            // 权限被收回（部分授权 / 用户在设置里撤销）时会抛这个，交给 UI 显示重试
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
    }
}
