package com.example.album.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.app.PendingIntent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore 真值源：唯一的数据库入口，全部查询都用**官方 Bundle 查询参数**
 * （`ContentResolver.QUERY_ARG_LIMIT / OFFSET / SORT_COLUMNS / SORT_DIRECTION / SQL_SELECTION`），
 * 不拼 `sortOrder = "x LIMIT 100"` 这类非官方后缀，也不手工 offset 全表扫描。
 */
class MediaStoreSource(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** 图片 + 视频统一走 Files 集合：一条分页查询就能出混合时间线（DURATION 列也只有这里通用）。 */
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.VOLUME_NAME,
        // 下面这些给查看器/详情/文件夹用：尺寸算共享元素几何、详情只读四项、文件夹分组
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
    )

    /**
     * 一页时间线（倒序：新的在前）。`bucketId` 非空时只取该文件夹里的项（文件夹网格复用同一条路径）。
     *
     * @param sortByName 文件夹内容页按**名称升序**（用户 2026-10-18 令「全部文件都按名称排序」）；
     *   首页时间线仍是日期倒序（否则首页就不成"相册"了）。
     * 失败抛 IOException / SecurityException，由 PagingSource 转成 LoadResult.Error。
     */
    suspend fun page(
        offset: Int,
        limit: Int,
        bucketId: String? = null,
        sortByName: Boolean = false,
    ): List<MediaItem> =
        withContext(Dispatchers.IO) {
            resolver.query(collection, projection, pageQueryArgs(offset, limit, bucketId, sortByName), null)
                ?.use { it.readItems() }
                .orEmpty()
        }

    /** 时间线一页的查询参数（抽出来便于单测断言，不在测试里重复 SQL 字面量）。 */
    internal fun pageQueryArgs(
        offset: Int,
        limit: Int,
        bucketId: String? = null,
        sortByName: Boolean = false,
    ): Bundle = Bundle().apply {
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)" +
            if (bucketId == null) "" else " AND ${MediaStore.MediaColumns.BUCKET_ID} = ?"
        val selectionArgs = buildList {
            add(TYPE_IMAGE.toString())
            add(TYPE_VIDEO.toString())
            if (bucketId != null) add(bucketId)
        }.toTypedArray()
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        // 多列排序：DATE_TAKEN 缺失的行（NULL）在 DESC 里排到最后，组内再按 DATE_ADDED 倒序 —— 与分组键一致。
        // 注意：Files 集合（image/video 的并集）上 QUERY_ARG_SORT_COLUMNS 实测**不生效**（返回顺序按表来），
        // 必须走 QUERY_ARG_SQL_SORT_ORDER 这个官方 bundle 形式的 sortOrder。
        // 排序键必须与"显示用的日期"一致：DATE_TAKEN 缺失时 UI 用 DATE_ADDED 顶上，
        // 所以排序也按 IFNULL(date_taken, date_added×1000)，否则缺 EXIF 的行会排到末尾却显示成今天的日期
        putString(
            ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
            if (sortByName) {
                // 文件夹内容页：按文件名升序（忽略大小写，与文件夹列表同一个 COLLATE 口径）
                "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC, ${MediaStore.MediaColumns._ID} ASC"
            } else {
                "IFNULL(${MediaStore.MediaColumns.DATE_TAKEN}, ${MediaStore.MediaColumns.DATE_ADDED} * 1000) DESC, " +
                    "${MediaStore.MediaColumns._ID} DESC"
            },
        )
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
    }

    /**
     * banner 的候选池：**最近 N 张静态图片**（`IMAGE` 且非 GIF），一次取回缓存，
     * 之后换图只是从这个池子里随机挑 —— 不做 `ORDER BY RANDOM()` / 大 OFFSET 的全表扫。
     */
    suspend fun bannerCandidates(limit: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        resolver.query(collection, projection, bannerQueryArgs(limit), null)
            ?.use { it.readItems() }
            .orEmpty()
    }

    /** banner 候选池的查询参数（同样抽出来可单测）。 */
    internal fun bannerQueryArgs(limit: Int): Bundle = Bundle().apply {
        putString(
            ContentResolver.QUERY_ARG_SQL_SELECTION,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} != ?",
        )
        putStringArray(
            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            arrayOf(TYPE_IMAGE.toString(), MIME_GIF),
        )
        // 与 `pageQueryArgs` 同一口径：Files 集合上 QUERY_ARG_SORT_COLUMNS **不生效**
        //（本文件 `pageQueryArgs` 的注释实测过），必须走 QUERY_ARG_SQL_SORT_ORDER，
        // 否则这个"最近 N 张"实际是表顺序里的前 N 张（banner 会挑到很老的照片）。
        putString(
            ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
            "IFNULL(${MediaStore.MediaColumns.DATE_TAKEN}, ${MediaStore.MediaColumns.DATE_ADDED} * 1000) DESC, " +
                "${MediaStore.MediaColumns._ID} DESC",
        )
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }

    /**
     * 文件夹列表：**一次扫描**取回 [每个文件夹 id / 名称 / 卷 / 最新一张] + 计数。
     *
     * 不用 `GROUP BY` + 聚合（MediaProvider 对投影列有校验、`COUNT(*)` 这类别名不稳），
     * 也不按文件夹逐个查 count（几十个文件夹 = 几十次查询）。这里只取 4 列扫一遍：
     * 行数 = 媒体总数，1 万条量级几十毫秒，在 IO 线程做一次（文件夹在抽屉里，不是首屏）。
     */
    suspend fun buckets(): List<MediaBucket> = withContext(Dispatchers.IO) {
        val rows = ArrayList<MediaBucket>(32)
        resolver.query(collection, bucketProjection, bucketQueryArgs(), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val volumeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                val mediaIdColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val typeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (idColumn >= 0 && mediaIdColumn >= 0) {
                    val order = LinkedHashMap<String, MutableEntry>()
                    do {
                        val bucketId = cursor.getStringOrNull(idColumn) ?: continue
                        val relativePath = if (pathColumn >= 0) cursor.getStringOrNull(pathColumn).orEmpty() else ""
                        // 用户 2026-10-18 报「相册文件夹页显示了不应该显示的文件夹（uperf、yc，data 里的）」——
                        // 当时是 `MANAGE_EXTERNAL_STORAGE` 让 MediaProvider 把 `Android/data|obb/` 下的应用私有
                        // 媒体也吐了出来。那条权限已于同日随「删除新建文件夹/空目录」一起移除，这里保留过滤是兜底
                        // （别的设备/系统版本可能仍会把私有目录算成 bucket），只剔列表、不动时间线。
                        if (isPrivateAppPath(relativePath)) continue
                        val mediaId = cursor.getLong(mediaIdColumn)
                        val volume = cursor.getStringOrNull(volumeColumn)
                            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        val isVideo = typeColumn >= 0 && cursor.getInt(typeColumn) == TYPE_VIDEO
                        val existing = order[bucketId]
                        if (existing == null) {
                            // 空名 = 存储根目录（`relative_path = /`）；显示文案（「根目录」/「Root」）由 UI 按资源给，
                            // 这里保留空串，不拿 bucket id 冒充名字
                            val name = cursor.getStringOrNull(nameColumn)?.takeIf { it.isNotBlank() }.orEmpty()
                            order[bucketId] = MutableEntry(
                                name = name,
                                volume = volume,
                                // 封面按类型构造：视频 id 落在 images 集合上 loadThumbnail 会失败（整行空着）
                                coverUri = if (isVideo) {
                                    MediaStore.Video.Media.getContentUri(volume, mediaId)
                                } else {
                                    MediaStore.Images.Media.getContentUri(volume, mediaId)
                                },
                                count = 1,
                            )
                        } else {
                            existing.count += 1
                        }
                    } while (cursor.moveToNext())
                    order.forEach { (bucketId, entry) ->
                        rows += MediaBucket(
                            id = bucketId,
                            name = entry.name,
                            count = entry.count,
                            coverUri = entry.coverUri,
                            volumeName = entry.volume,
                        )
                    }
                }
            }
        }
        // 用户拍板：按名称升序（忽略大小写），不提供排序控件
        rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** 文件夹扫描的查询参数（按拍摄时间倒序 ⇒ 每个文件夹第一次遇到的那张就是封面）。 */
    internal fun bucketQueryArgs(): Bundle = Bundle().apply {
        putStringArray(
            ContentResolver.QUERY_ARG_SORT_COLUMNS,
            arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns._ID),
        )
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
    }

    /**
     * 把一批 id 换成本卷的**类型化 URI**（用户 2026-10-24 令：多选 → 新建文件夹 → 移入）。
     *
     * ⚠️ 不能像 `SelectionActions.mediaUri()` 那样用 `Files.getContentUri(VOLUME_EXTERNAL, id)`：
     * 那是合并卷，`_ID` 只在卷内唯一，多卷设备（SD 卡）上会指向错行。这里按查询回来的
     * `VOLUME_NAME + MEDIA_TYPE` 拼 `images/media` 或 `video/media`（= 本类 `readItems()` 的口径）。
     */
    suspend fun mediaUris(ids: List<Long>): List<Uri> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map(Long::toString).toTypedArray()
        val out = ArrayList<Uri>(ids.size)
        resolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.VOLUME_NAME,
            ),
            "$placeholders".let { "${MediaStore.MediaColumns._ID} IN ($it)" },
            args,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val volumeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val volume = cursor.getString(volumeColumn) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val type = cursor.getInt(typeColumn)
                out += if (type == TYPE_VIDEO) {
                    MediaStore.Video.Media.getContentUri(volume, id)
                } else {
                    MediaStore.Images.Media.getContentUri(volume, id)
                }
            }
        }
        out
    }

    /**
     * `MediaStore.createWriteRequest` 的系统确认弹窗（**不新增任何 manifest 权限**）。
     *
     * 语义（官方 javadoc）：授权绑定发起请求的 Activity 生命周期、**不是一次消费**，弹窗每批一次；
     * targetSdk ≥36 时单批上限 2000 条（超了抛 IllegalArgumentException）。
     */
    fun writeRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createWriteRequest(resolver, uris)

    /**
     * 把一批已授权 URI 移到 `Pictures/<folderName>/`（官方没有批量移动 API，只能逐条 `update(RELATIVE_PATH)`）。
     *
     * - MediaProvider 在 update 时做磁盘 rename ⇒ `_ID / DATE_TAKEN / DATE_ADDED` 全部保留（时间线不跳位）；
     * - 目录由 MediaProvider **隐式**创建（没有"建目录"API）；
     * - 不要给别人的行设 `IS_PENDING`（那是 insert 自己写字节的路径）；也不要设置，保持原样。
     * - API 30+ 失败是普通 `SecurityException`（`RecoverableSecurityException` 是 API 29/旧 target 的路径）。
     *
     * @return 成功移动的条数
     */
    suspend fun moveToPictures(uris: List<Uri>, folderName: String): Int = withContext(Dispatchers.IO) {
        val relativePath = "Pictures/$folderName/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        uris.count { uri ->
            try {
                resolver.update(uri, values, null, null) > 0
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }

    fun addObserver(observer: ContentObserver) {
        // 注册在 `content://media` 根上：MediaProvider 通知的是 items/media 等具体 URI，
        // 它们不是 `/file` 的后代，注册在 Files 集合上会**漏掉**新增照片。
        resolver.registerContentObserver(MediaStore.AUTHORITY_URI, true, observer)
    }

    fun removeObserver(observer: ContentObserver) {
        resolver.unregisterContentObserver(observer)
    }

    private fun Cursor.readItems(): List<MediaItem> {
        val idColumn = getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val typeColumn = getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val takenColumn = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val addedColumn = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val durationColumn = getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val volumeColumn = getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
        // 新增列用 getColumnIndex（宽容）：假 provider / 老数据少了列也不至于整页失败
        val widthColumn = getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightColumn = getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val sizeColumn = getColumnIndex(MediaStore.MediaColumns.SIZE)
        val nameColumn = getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val bucketIdColumn = getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
        val bucketNameColumn = getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

        val items = ArrayList<MediaItem>(count)
        while (moveToNext()) {
            val type = getInt(typeColumn)
            if (type != TYPE_IMAGE && type != TYPE_VIDEO) continue
            val id = getLong(idColumn)
            val taken = if (isNull(takenColumn)) 0L else getLong(takenColumn)
            val isVideo = type == TYPE_VIDEO
            // 读/取缩略图必须用**按类型 + 卷**的内容 URI：Files 集合是虚拟表，
            // content://media/external/file/<id> 不能直接 loadThumbnail / openInputStream。
            val volume = getStringOrNull(volumeColumn) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            val mediaUri = if (isVideo) {
                MediaStore.Video.Media.getContentUri(volume, id)
            } else {
                MediaStore.Images.Media.getContentUri(volume, id)
            }
            items += MediaItem(
                id = id,
                uri = mediaUri,
                kind = if (isVideo) MediaKind.Video else MediaKind.Image,
                dateTakenMillis = if (taken > 0L) taken else getLong(addedColumn) * 1000L,
                durationMillis = getLongOrZero(durationColumn),
                widthPx = getIntOrZero(widthColumn),
                heightPx = getIntOrZero(heightColumn),
                sizeBytes = getLongOrZero(sizeColumn),
                displayName = getStringOrNull(nameColumn).orEmpty(),
                bucketId = getStringOrNull(bucketIdColumn).orEmpty(),
                bucketName = getStringOrNull(bucketNameColumn).orEmpty(),
                volumeName = volume,
            )
        }
        return items
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (index < 0 || isNull(index)) null else getString(index)

    private fun Cursor.getIntOrZero(index: Int): Int = if (index < 0 || isNull(index)) 0 else getInt(index)

    private fun Cursor.getLongOrZero(index: Int): Long = if (index < 0 || isNull(index)) 0L else getLong(index)

    private class MutableEntry(
        val name: String,
        val volume: String,
        val coverUri: Uri,
        var count: Int,
    )

    private val bucketProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
    )

    /**
     * 不属于"用户相册"的路径（= 9.9 的有效可见范围）：
     * ① `Android/data|obb/` 下是**应用私有**目录 —— 9.9 只有 `READ_MEDIA_IMAGES`，MediaStore 根本不会给出这些行。
     *    本项目曾因「空目录检测」拿过 `MANAGE_EXTERNAL_STORAGE`，那段时间 provider 会把它们吐出来
     *    （用户 2026-10-18 报的 `uperf`、`yc` 就是这类）；该权限同日已随功能一起删除，这里作为兜底保留。
     * ② `relative_path` 里出现 `.` 开头的目录 = 隐藏目录（原版 `isprivate=0` 过滤掉的那批）。
     */
    internal fun isPrivateAppPath(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        val segments = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.any { it.startsWith(".") }) return true
        return segments.take(2).joinToString("/").lowercase().let {
            it == "android/data" || it == "android/obb"
        }
    }

    private companion object {
        val TYPE_IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val TYPE_VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        const val MIME_GIF = "image/gif"
    }
}
