package com.noteink.app.store

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.noteink.app.ink.HiResSource
import com.noteink.app.ink.HiResTile
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF 页面的高清瓦片渲染器。
 *
 * 为什么不是「把导入时的图渲染得更大」：一张 A4 页面想撑到 8 倍放大需要 13000px 宽，
 * 单页就是 200MB 级别，不可能常驻内存。所以按视口切块，只渲染当前看到的区域，
 * 每块按当前缩放渲染到屏幕像素密度，内存里最多留几块。
 *
 * 一张渲染好的块可以覆盖「缩放变化不超过一个档位、平移不超出该块」的情况，
 * 因此档位按 0.5 倍量化，命中时零开销。
 */
class PdfHiRes(
    pdf: File,
    private val pageIndex: Int,
    private val pageW: Int,
    private val pageH: Int,
    private val onReady: () -> Unit
) : HiResSource {

    private val file = pdf
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
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var failed = false

    override fun drawInto(canvas: Canvas, visible: RectF, scale: Float) {
        if (closed || failed) return
        if (scale <= 0f) return

        // 档位量化：0.5 倍一档，避免缩放过程中每帧都重渲染
        val band = (ceil(scale * 2f) / 2f).coerceIn(1f, MAX_BAND)
        val bandKey = (band * 10f).roundToInt()
        val cell = TILE_PX / band

        val x0 = floor(visible.left / cell).toInt()
        val x1 = floor(visible.right / cell).toInt()
        val y0 = floor(visible.top / cell).toInt()
        val y1 = floor(visible.bottom / cell).toInt()

        // 只处理与页面相交的块；最多 MAX_TILES_VISIBLE 块，避免极端缩放下一次请求几十块
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
                    request(key, RectF(left, top, right, bottom), band, cell)
                }
            }
        }
    }

    private fun request(key: Key, rect: RectF, band: Float, cell: Float) {
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

    /** 在后台线程调用。把页面坐标的一段映射成位图，交给 PdfRenderer 用 Matrix 完成裁剪 */
    private fun renderTile(rect: RectF, band: Float): Bitmap? {
        val r = ensureRenderer() ?: return null
        val page = r.openPage(pageIndex)
        try {
            // 页面坐标（pageW 像素宽）→ PDF 点坐标
            val k = page.width.toFloat() / pageW
            val outW = (rect.width() * band).roundToInt().coerceIn(1, MAX_TILE_PX)
            val outH = (rect.height() * band).roundToInt().coerceIn(1, MAX_TILE_PX)
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)

            val m = Matrix()
            val s = outW.toFloat() / (rect.width() * k)
            m.setScale(s, s)
            m.postTranslate(-rect.left * k * s, -rect.top * k * s)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    private fun ensureRenderer(): PdfRenderer? {
        synchronized(lock) {
            if (closed) return null
            renderer?.let { return it }
            return try {
                val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = p
                val r = PdfRenderer(p)
                if (pageIndex !in 0 until r.pageCount) {
                    r.close()
                    failed = true
                    return null
                }
                renderer = r
                r
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
                try {
                    renderer?.close()
                } catch (_: Throwable) {
                }
                renderer = null
                try {
                    pfd?.close()
                } catch (_: Throwable) {
                }
                pfd = null
            }
        }
        exec.shutdown()
    }

    companion object {
        /** 单块边长（屏幕像素）。越大越省请求次数，但单块内存也越大 */
        private const val TILE_PX = 768f
        private const val MAX_TILE_PX = 1600
        private const val MAX_TILES = 14
        private const val MAX_TILES_VISIBLE = 9
        private const val MAX_BAND = 8f
    }
}
