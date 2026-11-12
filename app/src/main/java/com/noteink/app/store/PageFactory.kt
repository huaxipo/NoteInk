package com.noteink.app.store

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.noteink.app.model.PageStyle

/** 生成空白页面底图（纸张色 + 底纹） */
object PageFactory {

    const val DEFAULT_W = 1240
    const val DEFAULT_H = 1754

    private const val PAPER = 0xFFFFFDF8.toInt()
    private const val GRID_COLOR = 0xFFDCE3EA.toInt()
    private const val LINE_COLOR = 0xFFD7DEE6.toInt()
    private const val MARGIN_COLOR = 0xFFE7C0C0.toInt()

    fun blank(w: Int, h: Int, style: PageStyle): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawPaper(Canvas(bmp), w, h, style)
        return bmp
    }

    /**
     * 直接在画布上画纸面与底纹（不经过位图）。
     *
     * 空白页以前是把底纹画进一张 1240px 的位图，再贴到屏幕上；屏幕适配后在
     * 1600px 宽的机器上会被放大 1.24 倍，格线就发虚。改成每帧现画之后，
     * 无论缩放多少倍，线永远是 1 个屏幕像素宽、永远锐利。
     */
    fun drawPaper(c: Canvas, w: Int, h: Int, style: PageStyle) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // 用矩形而不是 drawColor：drawColor 会填满整个裁剪区，画在视图画布上会把页面外的桌面也刷白
        p.color = PAPER
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        when (style) {
            PageStyle.BLANK -> Unit
            PageStyle.GRID -> {
                p.color = GRID_COLOR
                p.strokeWidth = 1.4f
                val step = 40f
                var x = step
                while (x < w) {
                    c.drawLine(x, 0f, x, h.toFloat(), p)
                    x += step
                }
                var y = step
                while (y < h) {
                    c.drawLine(0f, y, w.toFloat(), y, p)
                    y += step
                }
            }
            PageStyle.LINES -> {
                p.color = LINE_COLOR
                p.strokeWidth = 1.6f
                val step = 58f
                var y = step
                while (y < h) {
                    c.drawLine(0f, y, w.toFloat(), y, p)
                    y += step
                }
                p.color = MARGIN_COLOR
                p.strokeWidth = 1.8f
                c.drawLine(96f, 0f, 96f, h.toFloat(), p)
            }
            PageStyle.DOTS -> {
                p.color = 0xFFC9D3DC.toInt()
                val step = 40f
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) {
                        c.drawCircle(x, y, 1.7f, p)
                        x += step
                    }
                    y += step
                }
            }
        }
    }

    /**
     * 导入内容统一的白底画布尺寸。
     *
     * 1654 这个值不是随手取的：机器屏幕宽 1600px、页面宽度按 fit 缩放到 96% 后约占
     * 1536px，底图 1654px 放上去是「略微缩小」，字不会因为放大而发虚；换到更宽的屏
     * 也只是继续缩小，或交给放大时的矢量重渲染兜底。
     */
    fun importCanvasWidth() = 1654

    /** 与 [importCanvasWidth] 同比例的竖版高度（A4 比例） */
    fun importCanvasHeight() = (importCanvasWidth().toFloat() * DEFAULT_H / DEFAULT_W).toInt()

    fun fitHeight(srcW: Int, srcH: Int, targetW: Int): Int =
        (targetW.toFloat() * srcH / srcW).toInt().coerceAtLeast(1)

    fun paperColor() = PAPER

    fun white() = Color.WHITE
}
