package com.example.album

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.example.album.data.MediaStoreThumbnailFetcher

/**
 * Application 只做两件"一次就够"的事：装配容器、装好进程级 Coil ImageLoader。
 *
 * 这里刻意不做扫描/预热/建库：冷启动越短越好，数据层全部在用到时再装配。
 */
class AlbumApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                // 网格 / banner 的缩略图统一走系统 loadThumbnail（mediaserver 生成，不解全图）
                .components { add(MediaStoreThumbnailFetcher.Factory()) }
                .build()
        }
    }
}
