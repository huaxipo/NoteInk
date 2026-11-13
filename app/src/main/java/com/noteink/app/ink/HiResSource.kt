package com.noteink.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 「放大后仍然清晰」的来源。
 *
 * 页面底图是一张固定分辨率的栅格图（A4 约 1654×2339），放大到 2 倍以上必然糊。
 * 对于有矢量来源的页面（目前是 PDF），实现这个接口就能在放大时按需重渲染可见区域，
 * 而不是把原图放大 —— 文字在任意倍率下都是锐利的。
 */
interface HiResSource {

    /**
     * 在**页面坐标系**下绘制可见区域的高清内容（调用方已完成 translate/scale）。
     * 还没渲染好的块不会阻塞，渲染完成后会通过构造时传入的回调触发重绘。
     */
    fun drawInto(canvas: Canvas, visible: RectF, scale: Float)

    /** 释放底层资源（位图、渲染器）。调用后不能再 drawInto */
    fun release()
}

/** 一块已渲染好的高清区域。rect 是它在页面坐标系中的位置 */
class HiResTile(val bmp: Bitmap, val rect: RectF)

/**
 * 把可见区域换算成瓦片下标 [x0, y0, x1, y1]，并保证瓦片严格落在页面内。
 *
 * 页面居中显示时，可见区域比页面大：left / top 会是负数，right / bottom 会超出页宽页高。
 * 瓦片位图是「先整块涂白，再画这一块的页面内容」，负坐标的瓦片左半边没有任何内容，
 * 贴回画布就成了一块盖在页面外的纯白矩形。这里直接不生成页面外的瓦片。
 */
internal fun tileGrid(visible: RectF, cell: Float, pageW: Int, pageH: Int): IntArray? {
    if (cell <= 0f || pageW <= 0 || pageH <= 0) return null
    val x0 = max(0, floor(visible.left / cell).toInt())
    val y0 = max(0, floor(visible.top / cell).toInt())
    val x1 = floor(min(visible.right, pageW.toFloat()) / cell).toInt()
    val y1 = floor(min(visible.bottom, pageH.toFloat()) / cell).toInt()
    if (x1 < x0 || y1 < y0) return null
    return intArrayOf(x0, y0, x1, y1)
}
