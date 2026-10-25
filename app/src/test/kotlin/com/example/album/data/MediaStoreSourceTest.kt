package com.example.album.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MediaStore 查询与映射的 Robolectric 单测：用一个内存里的假 provider 顶替 `content://media`，
 * 断言 ① Bundle 查询参数 ② Cursor → MediaItem 的映射（含 datetaken 缺失回退、按类型+卷拼 URI、非图片视频跳过）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class MediaStoreSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeMediaProvider.resultRows = emptyList()
        // 第二个参数是 authority：Robolectric 会把 provider 注册给 content://media
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        FakeMediaProvider.lastQueryArgs = null
    }

    @Test
    fun `一页时间线的查询参数是官方 Bundle 形态`() {
        val source = MediaStoreSource(context)
        val args = source.pageQueryArgs(offset = 120, limit = 60)

        assertEquals(60, args.getInt(android.content.ContentResolver.QUERY_ARG_LIMIT))
        assertEquals(120, args.getInt(android.content.ContentResolver.QUERY_ARG_OFFSET))
        assertEquals(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            args.getString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION),
        )
        assertEquals(
            listOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ),
            args.getStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.toList(),
        )
        // 排序走 SQL_SORT_ORDER（Files 集合上 QUERY_ARG_SORT_COLUMNS 实测不生效），
        // 且排序键 = 显示用的日期：DATE_TAKEN 缺失时用 DATE_ADDED 顶上
        assertEquals(
            "IFNULL(${MediaStore.MediaColumns.DATE_TAKEN}, ${MediaStore.MediaColumns.DATE_ADDED} * 1000) DESC, " +
                "${MediaStore.MediaColumns._ID} DESC",
            args.getString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
        )
    }

    @Test
    fun `banner 候选池只取静态图片并限量`() {
        val source = MediaStoreSource(context)
        val args = source.bannerQueryArgs(limit = 500)

        assertEquals(500, args.getInt(android.content.ContentResolver.QUERY_ARG_LIMIT))
        val selection = args.getString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION).orEmpty()
        assertTrue(selection.contains("media_type = ?"))
        assertTrue(selection.contains("mime_type != ?"))
        assertEquals(
            listOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                "image/gif",
            ),
            args.getStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.toList(),
        )
        // 2026-10-24 r34：排序改走 SQL_SORT_ORDER（Files 集合上 SORT_COLUMNS 不生效），
        // 否则"最近 500 张"实际是表顺序的前 500 行（banner 会挑到很老的照片）
        assertEquals(
            "IFNULL(${MediaStore.MediaColumns.DATE_TAKEN}, ${MediaStore.MediaColumns.DATE_ADDED} * 1000) DESC, " +
                "${MediaStore.MediaColumns._ID} DESC",
            args.getString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
        )
        assertNull(args.getStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS))
    }

    @Test
    fun `Cursor 映射成 MediaItem：类型 卷 URI datetaken 回退 duration`() = runBlocking {
        FakeMediaProvider.resultRows = listOf(
            mapOf(
                MediaStore.MediaColumns._ID to 101L,
                MediaStore.Files.FileColumns.MEDIA_TYPE to MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                MediaStore.MediaColumns.DATE_TAKEN to 1_700_000_000_000L,
                MediaStore.MediaColumns.DATE_ADDED to 1_700_000_000L,
                MediaStore.MediaColumns.DURATION to null,
                MediaStore.MediaColumns.VOLUME_NAME to "external_primary",
            ),
            mapOf(
                MediaStore.MediaColumns._ID to 202L,
                MediaStore.Files.FileColumns.MEDIA_TYPE to MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                MediaStore.MediaColumns.DATE_TAKEN to null,
                MediaStore.MediaColumns.DATE_ADDED to 1_700_000_100L,
                MediaStore.MediaColumns.DURATION to 12_000L,
                MediaStore.MediaColumns.VOLUME_NAME to "external_primary",
            ),
            // 非图片/视频（文档）：必须被跳过
            mapOf(
                MediaStore.MediaColumns._ID to 303L,
                MediaStore.Files.FileColumns.MEDIA_TYPE to MediaStore.Files.FileColumns.MEDIA_TYPE_NONE,
                MediaStore.MediaColumns.DATE_TAKEN to 1_700_000_200_000L,
                MediaStore.MediaColumns.DATE_ADDED to 1_700_000_200L,
                MediaStore.MediaColumns.DURATION to null,
                MediaStore.MediaColumns.VOLUME_NAME to "external_primary",
            ),
        )

        val items = MediaStoreSource(context).page(offset = 0, limit = 10)

        assertEquals(2, items.size)
        assertEquals("content://media/external_primary/images/media/101", items[0].uri.toString())
        assertEquals(MediaKind.Image, items[0].kind)
        assertEquals(1_700_000_000_000L, items[0].dateTakenMillis)
        assertEquals(0L, items[0].durationMillis)

        assertEquals("content://media/external_primary/video/media/202", items[1].uri.toString())
        assertEquals(MediaKind.Video, items[1].kind)
        // datetaken 缺失 ⇒ 用 date_added × 1000，保证排序键与分组键一致
        assertEquals(1_700_000_100_000L, items[1].dateTakenMillis)
        assertEquals(12_000L, items[1].durationMillis)
    }

    @Test
    fun `空结果返回空列表`() = runBlocking {
        FakeMediaProvider.resultRows = emptyList()
        assertEquals(emptyList<MediaItem>(), MediaStoreSource(context).page(0, 10))
    }

    @Test
    fun `文件夹列表只留用户相册：剔除 Android data obb 与隐藏目录`() {
        val source = MediaStoreSource(context)
        // 用户 2026-10-18 报的那两个（`uperf`/`yc` 这类）就落在私有目录下
        assertTrue(source.isPrivateAppPath("Android/data/com.example/files/uperf/"))
        assertTrue(source.isPrivateAppPath("android/data/com.example/cache/yc/"))
        assertTrue(source.isPrivateAppPath("/Android/obb/com.example/"))
        assertTrue(source.isPrivateAppPath("Pictures/.thumbnails/"))
        // 正常相册目录不能被误杀
        assertFalse(source.isPrivateAppPath("DCIM/Camera/"))
        assertFalse(source.isPrivateAppPath("Pictures/yc/"))
        assertFalse(source.isPrivateAppPath("Download/"))
        assertFalse(source.isPrivateAppPath(""))
    }

    @Test
    fun `banner 查询复用同一条映射路径`() = runBlocking {
        FakeMediaProvider.resultRows = listOf(
            mapOf(
                MediaStore.MediaColumns._ID to 7L,
                MediaStore.Files.FileColumns.MEDIA_TYPE to MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                MediaStore.MediaColumns.DATE_TAKEN to 1L,
                MediaStore.MediaColumns.DATE_ADDED to 1L,
                MediaStore.MediaColumns.DURATION to null,
                MediaStore.MediaColumns.VOLUME_NAME to "external_primary",
            ),
        )
        val items = MediaStoreSource(context).bannerCandidates(limit = 500)
        assertEquals(1, items.size)
        assertEquals(7L, items.single().id)
        assertEquals(MediaKind.Image, items.single().kind)
    }
}

/** 内存假 provider：按投影返回预设行，不碰真实媒体库。 */
class FakeMediaProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // 查询参数由 ContentProvider 的 Bundle 重载翻译成这套老参数，这里记录下来供断言（可选）
        lastQueryArgs = QuerySnapshot(selection, selectionArgs?.toList(), sortOrder)
        val columns = projection?.toList() ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(columns.toTypedArray(), resultRows.size)
        for (row in resultRows) {
            cursor.addRow(columns.map { name -> row[name] }.toTypedArray())
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    data class QuerySnapshot(
        val selection: String?,
        val selectionArgs: List<String>?,
        val sortOrder: String?,
    )

    companion object {
        var resultRows: List<Map<String, Any?>> = emptyList()
        var lastQueryArgs: QuerySnapshot? = null

        private val DEFAULT_PROJECTION = listOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.VOLUME_NAME,
        )
    }
}
