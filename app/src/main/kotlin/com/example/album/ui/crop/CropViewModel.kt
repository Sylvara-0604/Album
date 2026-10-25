package com.example.album.ui.crop

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import com.example.album.data.CropSaver
import com.example.album.data.MediaItem
import com.example.album.data.fitWithin
import com.example.album.data.orientedImageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** 预览解码上限：长边 2048 已经比任何手机屏幕都大，再解只会白占 ARGB */
private const val MAX_PREVIEW_EDGE = 2048

/** 裁剪框最小边长（屏幕侧 48dp）：防止拖成 0 像素，与无障碍无关 */
private const val MIN_CROP_DP = 48f

/**
 * 裁剪页状态：预览位图、图片显示矩形、裁剪框、保存进度/错误。
 *
 * 三个坐标空间必须分清：
 * 1. **显示像素**（[imageRect] / [crop]）：原点在图区左上角，UI 直接拿它画；
 * 2. **摆正后的原图像素**（[sourceSize]）：只用于保存换算，见 [toSourceRect]；
 * 3. **采样位图像素**：由 [CropSaver] 内部再按 `info.size` 缩一次，本类不关心。
 *
 * 生命周期：裁剪是一次性操作，不跨 Activity 重建保留 —— [CropScreen] 用 `remember` 持有本类，
 * 离开组合时调用 [close] 取消在飞任务、释放预览位图（否则一张 ≤2048px 的预览会常驻到进程结束）。
 */
class CropViewModel(
    application: Application,
    private val source: MediaItem,
) : AndroidViewModel(application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var preview by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 预览图按 `ContentScale.Fit` 居中铺开后的矩形（显示像素） */
    var imageRect by mutableStateOf(CropRect.Zero)
        private set

    /** 裁剪框（显示像素）：手势唯一的写入口是 [onDrag] / [onPinch] */
    var crop by mutableStateOf(CropRect.Zero)
        private set

    var saving by mutableStateOf(false)
        private set

    var error by mutableStateOf<CropError?>(null)
        private set

    /** 保存成功：页面据此回调 `onDone` */
    var isSaved by mutableStateOf(false)
        private set

    private var viewportWidth = 0f
    private var viewportHeight = 0f

    /** 48dp 换算成像素后交给 [CropMath]；换算跟着 density 走，不由纯函数层做 */
    private var minSizePx = 0f

    /** 摆正后的原图尺寸：显示矩形 → 原图像素的换算基准（预览解码时从 `ImageDecoder` 的 info 得到） */
    private var sourceSize: Size? = null

    init {
        scope.launch { loadPreview() }
    }

    /**
     * 图区尺寸变化（首次布局、转屏、分屏）时重算几何。
     * 裁剪框按比例搬到新位置，用户已经调好的构图不会因为转屏被重置。
     */
    fun onViewportChanged(widthPx: Float, heightPx: Float, density: Float) {
        if (widthPx <= 0f || heightPx <= 0f || density <= 0f) return
        val minSize = MIN_CROP_DP * density
        if (widthPx == viewportWidth && heightPx == viewportHeight && minSize == minSizePx) return
        viewportWidth = widthPx
        viewportHeight = heightPx
        minSizePx = minSize
        updateGeometry()
    }

    /**
     * 单指手势：`handle == null` = 框内平移，否则拖动对应手柄。
     * 增量由 UI 层按帧传入，这里只做纯几何换算，不存手势状态。
     */
    fun onDrag(handle: Handle?, deltaX: Float, deltaY: Float) {
        if (!imageRect.isValid || !crop.isValid) return
        crop = if (handle == null) {
            CropMath.move(crop, deltaX, deltaY, imageRect)
        } else {
            CropMath.resize(crop, handle, deltaX, deltaY, imageRect, minSizePx)
        }
    }

    /** 双指：整框等比缩放（宽高比保持用户拖出来的样子） */
    fun onPinch(zoom: Float) {
        if (!imageRect.isValid || !crop.isValid || !zoom.isFinite()) return
        crop = CropMath.scale(crop, zoom, imageRect, minSizePx)
    }

    fun dismissError() {
        error = null
    }

    /**
     * 保存：把"显示像素"的框换成"摆正后原图像素"的框交给 [CropSaver]。
     * IO 在 `CropSaver` 内部的 `Dispatchers.IO`；失败只置错误状态，异常不往上抛（UI 不该被崩）。
     */
    fun save(overwrite: Boolean) {
        if (saving || !crop.isValid || !imageRect.isValid) return
        val space = sourceSize ?: return
        val target = toSourceRect(crop, imageRect, space)
        saving = true
        error = null
        scope.launch {
            try {
                CropSaver.save(getApplication<Application>(), source, target, overwrite)
                isSaved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (denied: SecurityException) {
                error = CropError.Denied
            } catch (failure: Exception) {
                error = CropError.SaveFailed
            } finally {
                saving = false
            }
        }
    }

    /** 离开页面：取消在飞的解码/保存（保存被取消时 `CropSaver` 会自己清掉 pending 项） */
    fun close() {
        scope.cancel()
    }

    private suspend fun loadPreview() {
        try {
            val decoded = withContext(Dispatchers.IO) { decodePreview() }
            preview = decoded.bitmap.asImageBitmap()
            sourceSize = decoded.space
            // 解码在 IO 线程，可能晚于首次布局：到货后补算一次几何
            updateGeometry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (decodeFailed: IOException) {
            error = CropError.DecodeFailed
        } catch (denied: SecurityException) {
            error = CropError.DecodeFailed
        }
    }

    private fun updateGeometry() {
        val space = sourceSize ?: return
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        if (space.width <= 0 || space.height <= 0) return
        val bounds = fitRect(space, viewportWidth, viewportHeight)
        val previous = imageRect
        imageRect = bounds
        crop = if (previous.isValid && crop.isValid) remap(crop, previous, bounds) else CropMath.initial(bounds)
        crop = CropMath.clamp(crop, bounds, minSizePx)
    }

    /** 图片按 `ContentScale.Fit` 居中铺进视口后的矩形：绘制与换算共用这一个算法，保证所见即所裁。 */
    private fun fitRect(space: Size, viewportWidth: Float, viewportHeight: Float): CropRect {
        val scale = minOf(viewportWidth / space.width, viewportHeight / space.height)
        val width = space.width * scale
        val height = space.height * scale
        val left = (viewportWidth - width) / 2f
        val top = (viewportHeight - height) / 2f
        return CropRect(left, top, left + width, top + height)
    }

    /** 按比例把框从旧显示矩形搬到新显示矩形（转屏用），保持相对位置与相对大小。 */
    private fun remap(rect: CropRect, from: CropRect, to: CropRect): CropRect {
        val scaleX = to.width / from.width
        val scaleY = to.height / from.height
        return CropRect(
            left = to.left + (rect.left - from.left) * scaleX,
            top = to.top + (rect.top - from.top) * scaleY,
            right = to.left + (rect.right - from.left) * scaleX,
            bottom = to.top + (rect.bottom - from.top) * scaleY,
        )
    }

    /**
     * 显示矩形内的像素 → 原图像素：去掉 `bounds` 原点得到框在图内的偏移，
     * 除以显示尺寸得 0..1 比例，再乘原图尺寸。
     * 显示矩形的宽高比 = 原图宽高比（[fitRect] 保证），所以 x/y 比例一致，框不会被拉变形。
     */
    private fun toSourceRect(rect: CropRect, bounds: CropRect, space: Size): Rect {
        val scaleX = space.width / bounds.width
        val scaleY = space.height / bounds.height
        return Rect(
            left = (rect.left - bounds.left) * scaleX,
            top = (rect.top - bounds.top) * scaleY,
            right = (rect.right - bounds.left) * scaleX,
            bottom = (rect.bottom - bounds.top) * scaleY,
        )
    }

    private fun decodePreview(): Preview {
        val resolver = getApplication<Application>().contentResolver
        var infoSize: Size? = null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source.uri)) { decoder, info, _ ->
            infoSize = info.size
            // 软件分配：预览要被画到 Canvas 上（纹理上传），且随时可能被丢弃重建
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val target = fitWithin(info.size, MAX_PREVIEW_EDGE)
            decoder.setTargetSize(target.width, target.height)
        }
        return Preview(
            bitmap = bitmap,
            space = orientedImageSize(
                info = infoSize ?: Size(bitmap.width, bitmap.height),
                decodedWidth = bitmap.width,
                decodedHeight = bitmap.height,
            ),
        )
    }

    private class Preview(val bitmap: Bitmap, val space: Size)
}

/**
 * 页面级错误。VM 只说"哪一类"，文案由 UI 用 `stringResource` 映射：
 * 改文案不用动 VM，VM 也不掺任何 res id。
 */
enum class CropError { DecodeFailed, SaveFailed, Denied }
