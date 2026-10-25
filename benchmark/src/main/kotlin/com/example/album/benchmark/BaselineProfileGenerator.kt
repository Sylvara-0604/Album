package com.example.album.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 采集。
 *
 * 跑法（需要真机在线且已解锁）：
 *   gradle :app:generateReleaseBaselineProfile
 *
 * 覆盖：冷启动 + 首屏出图（P0 路径）。
 *
 * ⚠️ 不要在这里加 `device.waitForIdle()`：本 App 有"banner 每 5s 换一张 + 1s 交叉淡化"，
 * 首轮实测 `waitForIdle` 会把采集卡死 17 分钟（2026-10-17）。用 `Until.hasObject` + 固定短等待代替。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.example.album", maxIterations = 10) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("com.example.album")), 5_000)
        // 首屏缩略图解码是冷启动路径的一部分，给固定 1.5s 让它跑起来（不等"完全 idle"）
        Thread.sleep(1_500)
    }
}
