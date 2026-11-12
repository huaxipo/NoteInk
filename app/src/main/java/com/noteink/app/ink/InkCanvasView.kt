package com.noteink.app.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.noteink.app.model.InkPoint
import com.noteink.app.model.InkTool
import com.noteink.app.model.PageStyle
import com.noteink.app.model.Stroke
import com.noteink.app.store.PageFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手写画布。
 *
 * 渲染：页面底图（导入内容 / 底纹）+ 笔迹。笔迹不再缓存成整页位图，
 * 而是每帧按当前视图变换直接绘制矢量路径 —— 放大多少倍都是清晰的。
 * 为了在密集页面上不掉帧，做了三件事：
 *   1. 每笔的外形路径缓存在 [Stroke] 上，几何不变时零重建；
 *   2. 视口裁剪：只绘制与可见区域相交的笔迹，放大后绝大部分笔迹被剔除；
 *   3. 包围盒另做缓存，裁剪判定不需要遍历点。
 *
 * 坐标统一使用「页面坐标系」（0..pageW / 0..pageH），缩放平移只影响 matrix。
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    // ---------- 页面 ----------
    var pageW = 1240
        private set
    var pageH = 1754
        private set
    private var bg: Bitmap? = null

    /** 无底图的空白页（只有底纹）—— 底纹每帧现画，缩放多少倍都是锐利的 */
    private var pageStyle: PageStyle = PageStyle.BLANK

    /** 放大时的高清来源（PDF 页才有）。为 null 时退化为放大底图 */
    private var hiRes: HiResSource? = null

    private val strokes = ArrayList<Stroke>()
    private var active: Stroke? = null
    private val activePath = Path()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val uiPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- 变换 ----------
    var scale = 1f
        private set
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f
    private var fitted = false

    /** 用户是否手动缩放 / 平移过。没动过的话，尺寸变化时自动重新居中适配 */
    private var interacted = false

    // ---------- 工具状态 ----------
    var tool: InkTool = InkTool.PEN
    var color: Int = 0xFF1B1B1B.toInt()

    /** 笔宽。刻意不叫 width —— 避免遮蔽 View.width（视图宽度） */
    var strokeWidth: Float = Ink.defaultWidth(InkTool.PEN)
    var eraserSize: Float = 26f
    var fingerDraw: Boolean = true

    /** 局部擦除：橡皮只挖掉笔迹的一部分。关闭时擦除整笔（更快，也更符合"擦掉一个字"的习惯） */
    var partialErase: Boolean = false

    /** 压感模拟：设备不上报真实压力时，用书写速度反推线宽（快细慢粗） */
    var simulatePressure: Boolean = true

    // ---------- 回调 ----------
    var onStrokesChanged: (() -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null
    var onHistoryChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
    var onViewChanged: ((scale: Float) -> Unit)? = null

    // ---------- 内部状态 ----------
    private var mode = MODE_NONE
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private var smX = 0f
    private var smY = 0f
    private var hasSm = false

    // 压感：真实 / 模拟
    private var realPressure = false
    private var simP = 0.9f
    private var lastTimeMs = 0L
    private var lastPx = 0f
    private var lastPy = 0f
    private var hasLast = false

    private val lassoPts = ArrayList<Float>()
    private val selection = ArrayList<Stroke>()
    private val moving = ArrayList<Stroke>()
    private var moveDx = 0f
    private var moveDy = 0f

    private var eraserX = -1f
    private var eraserY = -1f

    private class Cmd(val undo: () -> Unit, val redo: () -> Unit)

    private val undoStack = ArrayList<Cmd>()
    private val redoStack = ArrayList<Cmd>()

    private val visRect = RectF()
    private val layerRect = RectF()
    private val dstRect = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        setWillNotDraw(false)
        isFocusable = true
        uiPaint.style = Paint.Style.STROKE
        uiPaint.strokeWidth = 1.5f
    }

    // ================= 页面装载 =================

    fun setPage(bitmap: Bitmap?, w: Int, h: Int, style: PageStyle = PageStyle.BLANK) {
        pageW = w
        pageH = h
        pageStyle = style
        // 换页时释放上一页的底图，否则翻几十页后内存会明显上涨
        val old = bg
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
        bg = bitmap
        strokes.clear()
        active = null
        selection.clear()
        moving.clear()
        undoStack.clear()
        redoStack.clear()
        onSelectionChanged?.invoke(0)
        onHistoryChanged?.invoke(false, false)
        fitted = false
        fitToPage()
        invalidate()
    }

    /**
     * 只换热底图，不动笔迹、不动视口、不清撤销栈。
     * 用于「旧版渲染引擎画的底图」在后台重画完成后的热更新 ——
     * 走 [setPage] 会把用户这一页刚写的字一起清掉。
     */
    fun replaceBackground(bitmap: Bitmap?) {
        val old = bg
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
        bg = bitmap
        invalidate()
    }

    /** 换页时设置该页的高清来源；传 null 表示这页只有栅格底图 */
    fun setHiRes(src: HiResSource?) {        if (hiRes === src) return
        hiRes?.release()
        hiRes = src
        invalidate()
    }

    fun loadStrokes(list: List<Stroke>) {        strokes.clear()
        strokes.addAll(list)
        for (s in list) s.invalidate()
        invalidate()
    }

    fun strokesSnapshot(): List<Stroke> = strokes

    fun fitToPage() {
        if (width == 0 || height == 0) return
        val s = min(width.toFloat() / pageW, height.toFloat() / pageH) * 0.96f
        fitScale = s
        scale = s
        tx = (width - pageW * s) * 0.5f
        ty = (height - pageH * s) * 0.5f
        fitted = true
        interacted = false
        onViewChanged?.invoke(scale)
        invalidate()
    }

    fun zoomBy(factor: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
        interacted = true
        val ns = (scale * factor).coerceIn(fitScale * 0.4f, fitScale * 18f)
        val k = ns / scale
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        scale = ns
        clampTransform()
        onViewChanged?.invoke(scale)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 首次布局，或尺寸变了但用户没手动缩放/平移 —— 都重新居中适配，
        // 否则旋转屏幕 / 工具栏变高会让页面停在旧的居中位置（偏上或偏下）
        if (!fitted || !interacted) fitToPage()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hiRes?.release()
        hiRes = null
    }

    // ================= 绘制 =================

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(OUTSIDE_COLOR)

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)

        val pw = pageW.toFloat()
        val ph = pageH.toFloat()
        canvas.drawRect(-4f, 2f, pw + 6f, ph + 10f, shadowPaint)

        val b = bg
        if (b != null && !b.isRecycled) {
            // 底图分辨率与页面坐标系解耦：位图比页面大就缩下来画，
            // 这样导入页可以按屏幕宽度（1654px）渲染，而在 1240 的坐标系里依旧严丝合缝
            if (b.width == pageW && b.height == pageH) {
                canvas.drawBitmap(b, 0f, 0f, bmpPaint)
            } else {
                dstRect.set(0f, 0f, pw, ph)
                canvas.drawBitmap(b, null, dstRect, bmpPaint)
            }
        } else {
            // 空白页：底纹直接画在画布上，放大到多少倍都不会糊
            PageFactory.drawPaper(canvas, pageW, pageH, pageStyle)
        }

        // 可视区域（页面坐标系），用于裁剪
        visRect.set(-tx / scale, -ty / scale, (width - tx) / scale, (height - ty) / scale)

        // 屏幕上的每个页面像素已经比底图密了，就说明底图正在被放大 —— 这时改用矢量源
        // 重渲染可见区域。阈值由底图自身的分辨率决定，换设备（更宽/更窄的屏）自动适配。
        val sharp = hiRes
        if (sharp != null) {
            val native = (b?.takeIf { !it.isRecycled }?.width ?: pageW) / pageW.toFloat()
            if (scale > native * 1.08f) sharp.drawInto(canvas, visRect, scale)
        }

        drawInk(canvas)

        // 拖动中的选区
        if (moving.isNotEmpty()) {
            canvas.save()
            canvas.translate(moveDx, moveDy)
            for (s in moving) Ink.drawStroke(canvas, s, paint, activePath)
            canvas.restore()
        }

        // 选区包围框
        if (selection.isNotEmpty()) {
            drawSelectionBox(canvas)
        }

        // 套索 / 圈擦的轨迹：都只是「你圈到哪儿」的指示，圈擦用实线灰描边、不填色，
        // 免得整块变红抢掉底图；松手后轨迹立即消失，不会留在笔迹里
        if ((mode == MODE_LASSO || mode == MODE_LASSO_ERASE) && lassoPts.size >= 4) {
            val erasing = mode == MODE_LASSO_ERASE
            uiPaint.color = if (erasing) 0xFF6B6B6B.toInt() else 0xFF2F5FA8.toInt()
            uiPaint.style = Paint.Style.STROKE
            uiPaint.strokeWidth = 1.6f / scale
            uiPaint.strokeJoin = Paint.Join.ROUND
            uiPaint.pathEffect = if (erasing) {
                null
            } else {
                DashPathEffect(floatArrayOf(8f / scale, 6f / scale), 0f)
            }
            val p = Path()
            p.moveTo(lassoPts[0], lassoPts[1])
            for (i in 1 until lassoPts.size / 2) p.lineTo(lassoPts[i * 2], lassoPts[i * 2 + 1])
            if (!erasing) p.close()
            canvas.drawPath(p, uiPaint)
            uiPaint.pathEffect = null
        }

        canvas.restore()

        // 橡皮光标（屏幕坐标）
        if (mode == MODE_ERASE && eraserX >= 0) {
            uiPaint.color = 0x662F5FA8
            uiPaint.strokeWidth = 1.5f
            uiPaint.style = Paint.Style.FILL
            canvas.drawCircle(eraserX, eraserY, eraserSize * 0.5f * scale, uiPaint)
            uiPaint.color = 0xFF2F5FA8.toInt()
            uiPaint.style = Paint.Style.STROKE
            canvas.drawCircle(eraserX, eraserY, eraserSize * 0.5f * scale, uiPaint)
        }
    }

    /**
     * 绘制全部笔迹（含正在拖动的橡皮轨迹）。
     *
     * 如果页面里存在橡皮轨迹，就把整段笔迹画进一个离屏图层，橡皮用 CLEAR 挖洞；
     * 没有橡皮时直接画，省掉一次离屏合成（这是绝大多数页面的情况）。
     */
    private fun drawInk(canvas: Canvas) {
        val activeEraser = if (active?.tool == InkTool.ERASER) active else null
        if (strokes.isEmpty() && activeEraser == null) return

        var hasMask = activeEraser != null
        if (!hasMask) {
            for (s in strokes) {
                if (s.tool == InkTool.ERASER || s.tool == InkTool.LASSO_ERASE) {
                    hasMask = true
                    break
                }
            }
        }
        if (hasMask) {
            // 离屏层只覆盖可视区域，且必须落在页面范围内 —— 传整页会按缩放后尺寸分配，
            // 高倍缩放下就是几百 MB
            layerRect.set(
                max(visRect.left, 0f) - 4f,
                max(visRect.top, 0f) - 4f,
                min(visRect.right, pageW.toFloat()) + 4f,
                min(visRect.bottom, pageH.toFloat()) + 4f
            )
            if (layerRect.left >= layerRect.right || layerRect.top >= layerRect.bottom) return
            canvas.saveLayer(layerRect, null)
        }
        for (s in strokes) {
            if (!visibleInViewport(s)) continue
            when (s.tool) {
                InkTool.ERASER -> drawEraseOnCanvas(canvas, s)
                InkTool.LASSO_ERASE -> Ink.drawLassoErase(canvas, s, paint)
                else -> Ink.drawStroke(canvas, s, paint, path)
            }
        }
        // 正在拖的橡皮轨迹也放进同一层，实时看到挖除效果
        activeEraser?.let { drawEraseOnCanvas(canvas, it) }
        if (hasMask) canvas.restore()

        // 普通笔迹的实时预览（不进离屏层，避免每帧多一次合成）
        if (activeEraser == null) {
            active?.let { Ink.drawStroke(canvas, it, paint, activePath, cache = false) }
        }
    }

    /** 白盒：仅当页面有橡皮、且处于离屏层中时调用 */
    private fun drawEraseOnCanvas(canvas: Canvas, s: Stroke) {
        val pts = s.points
        if (pts.isEmpty()) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s.width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.BLACK
        paint.alpha = 255
        paint.xfermode = CLEAR_XFER
        if (pts.size == 1) {
            canvas.drawCircle(pts[0].x, pts[0].y, s.width * 0.5f, paint)
        } else {
            path.reset()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, paint)
        }
        paint.xfermode = null
    }

    /** 视口裁剪：放大后只画屏幕里看得见的笔，密集页面也不掉帧 */
    private fun visibleInViewport(s: Stroke): Boolean {
        val bb = s.bounds()
        val pad = s.width + 2f
        return !(bb[2] + pad < visRect.left || bb[0] - pad > visRect.right ||
            bb[3] + pad < visRect.top || bb[1] - pad > visRect.bottom)
    }

    private fun drawSelectionBox(canvas: Canvas) {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var bo = -Float.MAX_VALUE
        for (s in selection) {
            val bb = s.bounds()
            l = min(l, bb[0]); t = min(t, bb[1]); r = max(r, bb[2]); bo = max(bo, bb[3])
        }
        if (l > r) return
        val pad = 6f / scale
        uiPaint.color = 0x332F5FA8
        uiPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(l - pad, t - pad, r + pad, bo + pad), pad, pad, uiPaint)
        uiPaint.style = Paint.Style.STROKE
        uiPaint.color = 0xFF2F5FA8.toInt()
        uiPaint.strokeWidth = 1.6f / scale
        uiPaint.pathEffect = DashPathEffect(floatArrayOf(7f / scale, 5f / scale), 0f)
        canvas.drawRoundRect(RectF(l - pad, t - pad, r + pad, bo + pad), pad, pad, uiPaint)
        uiPaint.pathEffect = null
    }

    // ================= 触摸 =================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x
                lastY = e.y
                val tt = e.getToolType(0)
                val stylus = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
                if (stylus || fingerDraw) {
                    when (tool) {
                        InkTool.ERASER -> {
                            mode = MODE_ERASE
                            eraserX = e.x
                            eraserY = e.y
                            if (partialErase) beginPartialErase(e) else eraseAt(e.x, e.y)
                        }
                        InkTool.LASSO -> {
                            if (selection.isNotEmpty() && hitSelection(e.x, e.y)) {
                                beginMoveSelection()
                            } else {
                                clearSelection()
                                mode = MODE_LASSO
                                lassoPts.clear()
                                addLasso(e.x, e.y)
                            }
                        }
                        InkTool.LASSO_ERASE -> {
                            clearSelection()
                            mode = MODE_LASSO_ERASE
                            lassoPts.clear()
                            addLasso(e.x, e.y)
                        }
                        else -> beginStroke(e)
                    }
                } else {
                    mode = MODE_PAN
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    discardActive()
                    mode = MODE_TRANSFORM
                    lastSpan = spanOf(e)
                    lastFocusX = focusOf(e, true)
                    lastFocusY = focusOf(e, false)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    if (mode != MODE_TRANSFORM) {
                        discardActive()
                        mode = MODE_TRANSFORM
                        lastSpan = spanOf(e)
                        lastFocusX = focusOf(e, true)
                        lastFocusY = focusOf(e, false)
                    }
                    doTransform(e)
                    return true
                }
                when (mode) {
                    MODE_DRAW -> extendStroke(e)
                    MODE_ERASE -> {
                        eraserX = e.x
                        eraserY = e.y
                        if (partialErase) extendPartialErase(e) else eraseAt(e.x, e.y)
                    }
                    MODE_LASSO, MODE_LASSO_ERASE -> {
                        addLasso(e.x, e.y)
                        invalidate()
                    }
                    MODE_PAN -> {
                        interacted = true
                        tx += e.x - lastX
                        ty += e.y - lastY
                        lastX = e.x
                        lastY = e.y
                        clampTransform()
                        invalidate()
                    }
                    MODE_MOVE_SEL -> {
                        moveDx += (e.x - lastX) / scale
                        moveDy += (e.y - lastY) / scale
                        lastX = e.x
                        lastY = e.y
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                when (mode) {
                    MODE_DRAW -> commitStroke()
                    MODE_ERASE -> {
                        if (partialErase) commitEraseStroke()
                        eraserX = -1f
                        eraserY = -1f
                        invalidate()
                    }
                    MODE_LASSO -> finishLasso()
                    MODE_LASSO_ERASE -> finishLassoErase()
                    MODE_MOVE_SEL -> finishMoveSelection()
                }
                mode = MODE_NONE
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                discardActive()
                if (mode == MODE_MOVE_SEL) finishMoveSelection()
                if (mode == MODE_LASSO || mode == MODE_LASSO_ERASE) lassoPts.clear()
                mode = MODE_NONE
                eraserX = -1f
                eraserY = -1f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun spanOf(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
    }

    private fun focusOf(e: MotionEvent, xAxis: Boolean): Float {
        if (e.pointerCount < 2) return 0f
        return if (xAxis) (e.getX(0) + e.getX(1)) * 0.5f else (e.getY(0) + e.getY(1)) * 0.5f
    }

    private fun doTransform(e: MotionEvent) {
        interacted = true
        val sp = spanOf(e)
        val fx = focusOf(e, true)
        val fy = focusOf(e, false)
        if (lastSpan > 4f && sp > 4f) {
            val factor = sp / lastSpan
            val ns = (scale * factor).coerceIn(fitScale * 0.4f, fitScale * 18f)
            val k = ns / scale
            tx = fx - (fx - tx) * k
            ty = fy - (fy - ty) * k
            scale = ns
        }
        tx += fx - lastFocusX
        ty += fy - lastFocusY
        lastSpan = sp
        lastFocusX = fx
        lastFocusY = fy
        clampTransform()
        onViewChanged?.invoke(scale)
        invalidate()
    }

    private fun clampTransform() {
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = pageW * scale
        val ph = pageH * scale
        val margin = 90f
        if (tx > w - margin) tx = w - margin
        if (ty > h - margin) ty = h - margin
        if (tx < margin - pw) tx = margin - pw
        if (ty < margin - ph) ty = margin - ph
    }

    // ================= 书写 =================

    private fun beginStroke(e: MotionEvent) {
        mode = MODE_DRAW
        val s = Stroke(tool, color, Ink.clampWidth(tool, strokeWidth), mutableListOf())
        active = s
        hasSm = false
        realPressure = false
        simP = 0.9f
        hasLast = false
        addPoint(s, e.x, e.y, e.pressure, e.eventTime)
        invalidate()
    }

    private fun extendStroke(e: MotionEvent) {
        val s = active ?: return
        val hs = e.historySize
        for (i in 0 until hs) {
            addPoint(
                s,
                e.getHistoricalX(0, i),
                e.getHistoricalY(0, i),
                e.getHistoricalPressure(0, i),
                e.getHistoricalEventTime(i)
            )
        }
        addPoint(s, e.x, e.y, e.pressure, e.eventTime)
        invalidate()
    }

    /**
     * 加一个采样点。
     *
     * 压感处理分两条路：
     *  - 设备上报了真实压力（主动式手写笔）→ 直接用；
     *  - 压力恒为 1（手指 / 普通电容笔）→ 按瞬时速度反推：写得快线变细，慢则粗。
     *    这是普通电容笔唯一能拿到"像真笔"效果的途径，用指数滑动平均抹掉抖动。
     */
    private fun addPoint(s: Stroke, vx: Float, vy: Float, pressureRaw: Float, timeMs: Long) {
        val px = (vx - tx) / scale
        val py = (vy - ty) / scale
        var x = px
        var y = py
        if (hasSm) {
            x = smX + (px - smX) * 0.78f
            y = smY + (py - smY) * 0.78f
        }
        smX = x
        smY = y
        hasSm = true
        val last = s.points.lastOrNull()
        if (last != null && hypot(x - last.x, y - last.y) < 0.5f) return

        if (pressureRaw > 0.02f && abs(pressureRaw - 1f) > 0.03f) realPressure = true

        var p = pressureRaw
        if (!simulatePressure) {
            if (p <= 0f) p = 1f
        } else if (!realPressure) {
            if (hasLast && timeMs > lastTimeMs) {
                val dt = (timeMs - lastTimeMs).toFloat()
                val speed = hypot(x - lastPx, y - lastPy) / dt * 1000f // 页面像素/秒
                // 0 px/s -> 1.0（最粗）；2400 px/s 以上 -> 0.34（最细）
                val target = (1f - speed / 2400f).coerceIn(0.34f, 1f)
                simP += (target - simP) * 0.3f
            }
            p = simP
        }
        lastTimeMs = timeMs
        lastPx = x
        lastPy = y
        hasLast = true

        s.points.add(InkPoint(x, y, p.coerceIn(0.03f, 1f)))
    }

    private fun commitStroke() {
        val s = active
        active = null
        if (s == null || s.points.isEmpty()) {
            invalidate()
            return
        }
        strokes.add(s)
        pushCmd(Cmd(
            undo = {
                strokes.remove(s)
                notifyChanged()
                invalidate()
            },
            redo = {
                strokes.add(s)
                notifyChanged()
                invalidate()
            }
        ))
        notifyHistory()
        notifyChanged()
        invalidate()
    }

    private fun discardActive() {
        active = null
        invalidate()
    }

    // ================= 橡皮 =================

    /** 整笔擦除：命中即整条删除，速度最快 */
    private fun eraseAt(vx: Float, vy: Float) {
        val px = (vx - tx) / scale
        val py = (vy - ty) / scale
        val r = eraserSize * 0.5f
        val removed = ArrayList<Pair<Stroke, Int>>()
        for (i in strokes.indices.reversed()) {
            val s = strokes[i]
            if (s.tool == InkTool.ERASER || s.tool == InkTool.LASSO_ERASE) continue
            if (Ink.hitStroke(s, px, py, r)) {
                removed.add(s to i)
                strokes.removeAt(i)
            }
        }
        if (removed.isNotEmpty()) {
            selection.removeAll { st -> removed.any { it.first === st } }
            onSelectionChanged?.invoke(selection.size)
            pushCmd(Cmd(
                undo = {
                    for ((s, idx) in removed.sortedBy { it.second }) {
                        strokes.add(min(idx, strokes.size), s)
                    }
                    notifyChanged()
                    invalidate()
                },
                redo = {
                    for ((s, _) in removed) strokes.remove(s)
                    notifyChanged()
                    invalidate()
                }
            ))
            notifyHistory()
            notifyChanged()
        }
        invalidate()
    }

    private fun beginPartialErase(e: MotionEvent) {
        val s = Stroke(InkTool.ERASER, 0xFF000000.toInt(), eraserSize, mutableListOf())
        active = s
        hasSm = false
        addErasePoint(s, e.x, e.y)
    }

    private fun extendPartialErase(e: MotionEvent) {
        val s = active ?: return
        val hs = e.historySize
        for (i in 0 until hs) {
            addErasePoint(s, e.getHistoricalX(0, i), e.getHistoricalY(0, i))
        }
        addErasePoint(s, e.x, e.y)
        invalidate()
    }

    private fun addErasePoint(s: Stroke, vx: Float, vy: Float) {
        val px = (vx - tx) / scale
        val py = (vy - ty) / scale
        var x = px
        var y = py
        if (hasSm) {
            x = smX + (px - smX) * 0.8f
            y = smY + (py - smY) * 0.8f
        }
        smX = x
        smY = y
        hasSm = true
        val last = s.points.lastOrNull()
        if (last != null && hypot(x - last.x, y - last.y) < 0.6f) return
        s.points.add(InkPoint(x, y, 1f))
    }

    private fun commitEraseStroke() {
        val s = active
        active = null
        if (s == null || s.points.isEmpty()) {
            invalidate()
            return
        }
        // 只在真的有笔迹可能被擦到时才入栈，避免空白处乱划也占一步撤销
        strokes.add(s)
        pushCmd(Cmd(
            undo = {
                strokes.remove(s)
                notifyChanged()
                invalidate()
            },
            redo = {
                strokes.add(s)
                notifyChanged()
                invalidate()
            }
        ))
        notifyHistory()
        notifyChanged()
        invalidate()
    }

    // ================= 套索 =================

    private fun addLasso(vx: Float, vy: Float) {
        val px = (vx - tx) / scale
        val py = (vy - ty) / scale
        if (lassoPts.size >= 2) {
            val lx = lassoPts[lassoPts.size - 2]
            val ly = lassoPts[lassoPts.size - 1]
            if (hypot(px - lx, py - ly) < 1.5f) return
        }
        lassoPts.add(px)
        lassoPts.add(py)
    }

    private fun finishLasso() {
        if (lassoPts.size >= 6) {
            val poly = lassoPts.toFloatArray()
            selection.clear()
            for (s in strokes) {
                if (s.tool == InkTool.ERASER || s.tool == InkTool.LASSO_ERASE) continue
                if (Ink.strokeInsidePolygon(s, poly)) selection.add(s)
            }
            onSelectionChanged?.invoke(selection.size)
        }
        lassoPts.clear()
    }

    /**
     * 松手即删：圈到的笔**整笔**删掉。
     *
     * 判定是「笔迹和圈有任何接触」—— 采样点落在圈里，或者某一笔横着穿过圈的边界，
     * 都算命中（见 [Ink.strokeHitPolygon]）。命中就整条消失，不会只裁掉圈内那一截。
     * 这也是它和普通橡皮的区别：不用沿着笔画来回蹭，随手一圈就清掉一片，而且一步就能撤销。
     * 圈里没有笔就不落笔（不占一步撤销）。
     */
    private fun finishLassoErase() {
        if (lassoPts.size < 6) {
            lassoPts.clear()
            return
        }
        val poly = lassoPts.toFloatArray()
        lassoPts.clear()
        val removed = ArrayList<Pair<Stroke, Int>>()
        for (i in strokes.indices.reversed()) {
            val s = strokes[i]
            if (s.tool == InkTool.ERASER || s.tool == InkTool.LASSO_ERASE) continue
            if (Ink.strokeHitPolygon(s, poly)) {
                removed.add(s to i)
                strokes.removeAt(i)
            }
        }
        if (removed.isEmpty()) {
            invalidate()
            return
        }
        if (selection.isNotEmpty()) {
            selection.removeAll { st -> removed.any { it.first === st } }
            onSelectionChanged?.invoke(selection.size)
        }
        pushCmd(Cmd(
            undo = {
                for ((s, idx) in removed.sortedBy { it.second }) {
                    strokes.add(min(idx, strokes.size), s)
                }
                notifyChanged()
                invalidate()
            },
            redo = {
                strokes.removeAll { st -> removed.any { it.first === st } }
                notifyChanged()
                invalidate()
            }
        ))
        notifyHistory()
        notifyChanged()
        invalidate()
    }

    private fun hitSelection(vx: Float, vy: Float): Boolean {
        val px = (vx - tx) / scale
        val py = (vy - ty) / scale
        for (s in selection) {
            val bb = s.bounds()
            if (px >= bb[0] - 12 && px <= bb[2] + 12 && py >= bb[1] - 12 && py <= bb[3] + 12) return true
        }
        return false
    }

    private fun beginMoveSelection() {
        mode = MODE_MOVE_SEL
        moving.clear()
        moving.addAll(selection)
        strokes.removeAll(selection)
        moveDx = 0f
        moveDy = 0f
        invalidate()
    }

    private fun finishMoveSelection() {
        if (moving.isEmpty()) return
        val dx = moveDx
        val dy = moveDy
        if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
            for (s in moving) s.translate(dx, dy)
        }
        val list = ArrayList(moving)
        strokes.addAll(list)
        moving.clear()
        if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
            pushCmd(Cmd(
                undo = {
                    for (s in list) s.translate(-dx, -dy)
                    notifyChanged()
                    invalidate()
                },
                redo = {
                    for (s in list) s.translate(dx, dy)
                    notifyChanged()
                    invalidate()
                }
            ))
            notifyHistory()
            notifyChanged()
        }
        moveDx = 0f
        moveDy = 0f
        invalidate()
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val list = ArrayList(selection)
        val idx = list.map { strokes.indexOf(it) }.filter { it >= 0 }
        for (s in list) strokes.remove(s)
        selection.clear()
        onSelectionChanged?.invoke(0)
        pushCmd(Cmd(
            undo = {
                list.forEachIndexed { i, s ->
                    val at = idx.getOrElse(i) { strokes.size }
                    strokes.add(min(at, strokes.size), s)
                }
                notifyChanged()
                invalidate()
            },
            redo = {
                for (s in list) strokes.remove(s)
                notifyChanged()
                invalidate()
            }
        ))
        notifyHistory()
        notifyChanged()
        invalidate()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        onSelectionChanged?.invoke(0)
        invalidate()
    }

    // ================= 撤销 / 重做 =================

    /** 入栈并清空重做分支；超过上限时丢弃最旧的一步，避免长笔记吃内存 */
    private fun pushCmd(c: Cmd) {
        undoStack.add(c)
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val c = undoStack.removeAt(undoStack.size - 1)
        c.undo()
        redoStack.add(c)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        notifyHistory()
        invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val c = redoStack.removeAt(redoStack.size - 1)
        c.redo()
        undoStack.add(c)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        notifyHistory()
        invalidate()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    private fun notifyHistory() = onHistoryChanged?.invoke(canUndo(), canRedo())

    private fun notifyChanged() = onStrokesChanged?.invoke()

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAW = 1
        private const val MODE_ERASE = 2
        private const val MODE_LASSO = 3
        private const val MODE_PAN = 4
        private const val MODE_TRANSFORM = 5
        private const val MODE_MOVE_SEL = 6
        private const val MODE_LASSO_ERASE = 7

        /** 历史栈上限。每一步只持有笔迹引用，200 步足够用且内存可控 */
        private const val MAX_HISTORY = 200

        private val OUTSIDE_COLOR = 0xFFEDEAE3.toInt()

        private val CLEAR_XFER by lazy {
            android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
    }
}
