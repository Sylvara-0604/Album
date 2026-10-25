package com.example.album

import android.content.Context
import com.example.album.data.AppSettings
import com.example.album.data.GridColumnPreference
import com.example.album.data.MediaRepository
import com.example.album.data.MediaStoreSource

/**
 * 手写装配（不引 DI 框架）。依赖全部走构造器注入：今天手动 new，明天换 Hilt 是机械改动。
 */
class AppContainer(context: Context) {

    private val source = MediaStoreSource(context)

    val repository = MediaRepository(source)

    val gridColumns = GridColumnPreference(context)

    /** 应用设置（主题模式 / banner 换图间隔）：设置页写、主题与时间线读。 */
    val settings = AppSettings(context)
}
