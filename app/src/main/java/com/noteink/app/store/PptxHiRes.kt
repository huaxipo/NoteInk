package com.noteink.app.store

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.noteink.app.ink.HiResSource
import com.noteink.app.ink.HiResTile
import com.noteink.app.ink.tileGrid
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PPT 页面的高清瓦片渲染器。
 *
 * PPT 页在导入时是「按 1240px 宽把文字排版画成一张位图」，放大就是位图插值 ——
 * 这是截图里字变马赛克的原因。这里保留原始 pptx，放大时按可见区域重新解析该页的
 * 形状（文字用 StaticLayout、图片用原始位图），在带缩放的 Canvas 上重画：
 * 文字在任意倍率下都是矢量排版的锐利边缘。
 *
 * 解析一页需要读 zip 里的 slide/layout/master，所以结果缓存一份（一页只有一份），
 * 瓦片本身按 0.5 倍档位量化缓存，内存占用与 [PdfHiRes] 同一量级。
 */
class PptxHiRes(
    pptx: File,
    private val slideIndex: Int,
    private val pageW: Int,
    private val pageH: Int,
    private val onReady: () -> Unit
) : HiResSource {

    private val file = pptx
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private class Key(val band: Int, val gx: Int, val gy: Int) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.band == band && other.gx == gx && other.gy == gy

        override fun hashCode(): Int = (band * 31 + gx) * 31 + gy
    }

    private val cache = object : LinkedHashMap<Key, HiResTile>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, HiResTile>): Boolean {
            val over = size > MAX_TILES
            if (over) {
                val t = eldest.value
                if (!t.bmp.isRecycled) t.bmp.recycle()
            }
            return over
        }
    }
    private val pending = HashSet<Key>()
    private val exec = Executors.newSingleThreadExecutor()
    private val lock = Any()

    @Volatile
    private var closed = false
    private var art: OfficeRender.SlideArt? = null
    private var failed = false

    override fun drawInto(canvas: Canvas, visible: RectF, scale: Float) {
        if (closed || failed) return
        if (scale <= 0f) return

        val band = (ceil(scale * 2f) / 2f).coerceIn(1f, MAX_BAND)
        val bandKey = (band * 10f).roundToInt()
        val cell = TILE_PX / band

        // 页面居中时可见区域比页面大，left/top 为负。瓦片是先整块涂白再画内容的，
        // 绝不能生成页面外的瓦片，否则那块白色会盖在米色画布上，像页面缺了一角。
        val grid = tileGrid(visible, cell, pageW, pageH) ?: return
        val (x0, y0, x1, y1) = grid

        var count = 0
        for (gy in y0..y1) {
            for (gx in x0..x1) {
                if (count++ >= MAX_TILES_VISIBLE) return
                val left = gx * cell
                val top = gy * cell
                val right = min(left + cell, pageW.toFloat())
                val bottom = min(top + cell, pageH.toFloat())
                if (right - left < 1f || bottom - top < 1f) continue

                val key = Key(bandKey, gx, gy)
                val hit = synchronized(cache) { cache[key] }
                if (hit != null && !hit.bmp.isRecycled) {
                    canvas.drawBitmap(hit.bmp, null, hit.rect, paint)
                } else {
                    request(key, RectF(left, top, right, bottom), band)
                }
            }
        }
    }

    private fun request(key: Key, rect: RectF, band: Float) {
        synchronized(pending) {
            if (pending.size > MAX_TILES_VISIBLE * 2) return
            if (!pending.add(key)) return
        }
        exec.execute {
            var bmp: Bitmap? = null
            try {
                if (!closed) bmp = renderTile(rect, band)
            } catch (t: Throwable) {
                failed = true
            } finally {
                synchronized(pending) { pending.remove(key) }
            }
            if (bmp != null) {
                synchronized(cache) {
                    val old = cache.put(key, HiResTile(bmp, rect))
                    if (old != null && !old.bmp.isRecycled && old.bmp !== bmp) old.bmp.recycle()
                }
                if (!closed) onReady()
            }
        }
    }

    /** 后台线程：把页面坐标系里的一块放大 band 倍画出来 */
    private fun renderTile(rect: RectF, band: Float): Bitmap? {
        val a = ensureArt() ?: return null
        val outW = (rect.width() * band).roundToInt().coerceIn(1, MAX_TILE_PX)
        val outH = (rect.height() * band).roundToInt().coerceIn(1, MAX_TILE_PX)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val s = outW.toFloat() / rect.width()
        c.scale(s, s)
        c.translate(-rect.left, -rect.top)
        OfficeRender.drawSlideContent(c, a.ops, a.pageW, a.pageH, a.bg, a.back)
        return bmp
    }

    private fun ensureArt(): OfficeRender.SlideArt? {
        synchronized(lock) {
            if (closed) return null
            art?.let { return it }
            return try {
                val a = OfficeRender.loadSlideArt(file, slideIndex, pageW)
                if (a == null) {
                    failed = true
                    null
                } else {
                    art = a
                    a
                }
            } catch (t: Throwable) {
                failed = true
                null
            }
        }
    }

    override fun release() {
        closed = true
        exec.execute {
            synchronized(cache) {
                for (t in cache.values) if (!t.bmp.isRecycled) t.bmp.recycle()
                cache.clear()
            }
            synchronized(lock) {
                art?.release()
                art = null
            }
        }
        exec.shutdown()
    }

    companion object {
        private const val TILE_PX = 768f
        private const val MAX_TILE_PX = 1600
        private const val MAX_TILES = 12
        private const val MAX_TILES_VISIBLE = 9
        private const val MAX_BAND = 8f
    }
}
