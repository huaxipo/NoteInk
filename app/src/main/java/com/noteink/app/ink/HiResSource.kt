package com.noteink.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF

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
