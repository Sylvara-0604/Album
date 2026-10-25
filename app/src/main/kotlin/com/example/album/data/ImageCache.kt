package com.example.album.data

import android.content.Context
import coil3.SingletonImageLoader

/**
 * Coil 缓存（内存位图 + 磁盘缓存）的统计与清除。
 *
 * 缩略图请求本身是 `diskCachePolicy(DISABLED)`（系统能随时重建），
 * 但查看器/裁剪的全尺寸请求走默认磁盘缓存 —— 这里两处一起清，才是用户看到的"清除缓存"。
 */
object ImageCache {

    /** 内存 + 磁盘的总占用（字节）。 */
    fun sizeBytes(context: Context): Long {
        val loader = SingletonImageLoader.get(context.applicationContext)
        return (loader.memoryCache?.size ?: 0L) + (loader.diskCache?.size ?: 0L)
    }

    /** 清空内存缓存与磁盘缓存。 */
    fun clear(context: Context) {
        val loader = SingletonImageLoader.get(context.applicationContext)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        // 我们自己那份"同步占位"缓存（`MediaThumbnails.ThumbnailPlaceholders`）一起清，
        // 否则"清除缓存"之后网格还能靠它画出旧图，看起来像没清干净。
        ThumbnailPlaceholders.clear()
    }
}
