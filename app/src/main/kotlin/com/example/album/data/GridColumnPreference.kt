package com.example.album.data

import android.content.Context
import androidx.core.content.edit

/**
 * 网格列数的持久化。
 *
 * 全工程**唯一**的持久化用途（9.9 同款 key `prefs_nbr_of_columns`）：官方 SharedPreferences 存一个 int，
 * 不引 DataStore / Room。范围照 9.9：**1..7**（`GroupLayout.mMinNbrOfColumns = 1`、
 * `pinch_grid_max_nbr_of_columns = 7`），默认起始 3（`pinch_grid_start_nbr_of_columns = 3`）。
 */
class GridColumnPreference(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, START_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        set(value) {
            prefs.edit { putInt(KEY_COLUMNS, value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)) }
        }

    companion object {
        /** 9.9 的资源名：`prefs_nbr_of_columns` */
        const val KEY_COLUMNS = "prefs_nbr_of_columns"

        /** 9.9 `pinch_grid_start_nbr_of_columns = 3`：没存过时的起始列数 */
        const val START_COLUMNS = 3

        /** 9.9 `GroupLayout` 的 `mMinNbrOfColumns = 1`：最大能放大到 1 列（单列大图） */
        const val MIN_COLUMNS = 1

        /** 9.9 `pinch_grid_max_nbr_of_columns = 7`：最小缩略图停在 7 列 */
        const val MAX_COLUMNS = 7

        private const val FILE_NAME = "album_grid"
    }
}
