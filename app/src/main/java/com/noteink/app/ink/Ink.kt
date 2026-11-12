package com.noteink.app.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.noteink.app.model.InkPoint
import com.noteink.app.model.InkTool
import com.noteink.app.model.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 墨水引擎：负责把一串采样点变成「有压感粗细变化」的平滑笔迹。
 *
 * 做法：Chaikin 细分平滑 -> 沿点序列生成梯形条带（quad strip）-> 首尾补圆头。
 * 全部梯形方向一致，所以用默认的 WINDING 填充不会出现空洞。
 *
 * 渲染方式：直接按当前视图变换绘制矢量路径（不再缓存整页位图），
 * 因此任意缩放倍率下都是清晰的；路径对象缓存在 [Stroke] 上，重复绘制零重建成本。
 */
object Ink {

    /** 调色盘：24 色，按墨水/荧光两套分组 */
    val palette: List<Int> = listOf(
        0xFF1B1B1B.toInt(), 0xFF5A5A5A.toInt(), 0xFF9E9E9E.toInt(), 0xFFD9D9D9.toInt(),
        0xFF8C1C1C.toInt(), 0xFFD64545.toInt(), 0xFFF08A5D.toInt(), 0xFFE8B23A.toInt(),
        0xFFB5C93C.toInt(), 0xFF4E9A51.toInt(), 0xFF2E8B84.toInt(), 0xFF3BA3C9.toInt(),
        0xFF2F5FA8.toInt(), 0xFF4B4FA8.toInt(), 0xFF7B4EA8.toInt(), 0xFFB0479A.toInt(),
        0xFFD96A8F.toInt(), 0xFF8A6A4B.toInt(), 0xFF5B4636.toInt(), 0xFFFFFFFF.toInt()
    )

    /** 荧光笔专用色（低饱和、亮） */
    val highlighterPalette: List<Int> = listOf(
        0xFFFFE066.toInt(), 0xFFB8E986.toInt(), 0xFF8FD9F0.toInt(),
        0xFFFFB3C7.toInt(), 0xFFD8B4F0.toInt(), 0xFFFFC98F.toInt()
    )

    /** 各工具在「页面像素」单位下的默认线宽 */
    fun defaultWidth(tool: InkTool): Float = when (tool) {
        InkTool.PEN -> 2.6f
        InkTool.PENCIL -> 3.0f
        InkTool.MARKER -> 9f
        InkTool.HIGHLIGHTER -> 26f
        InkTool.ERASER -> 24f
        InkTool.LASSO -> 2f
        InkTool.LASSO_ERASE -> 2f
    }

    fun clampWidth(tool: InkTool, w: Float): Float = when (tool) {
        InkTool.PEN -> w.coerceIn(1f, 12f)
        InkTool.PENCIL -> w.coerceIn(1f, 12f)
        InkTool.MARKER -> w.coerceIn(3f, 40f)
        InkTool.HIGHLIGHTER -> w.coerceIn(8f, 80f)
        else -> w
    }

    /** 压感 -> 实际线宽 */
    fun widthAt(tool: InkTool, base: Float, pressure: Float): Float {
        val p = pressure.coerceIn(0.03f, 1f)
        return when (tool) {
            InkTool.PEN -> base * (0.30f + 0.70f * p)
            InkTool.PENCIL -> base * (0.42f + 0.58f * p)
            InkTool.MARKER -> base * (0.85f + 0.15f * p)
            InkTool.HIGHLIGHTER -> base * (0.95f + 0.05f * p)
            else -> base
        }
    }

    fun configurePaint(paint: Paint, stroke: Stroke) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = stroke.color
        paint.alpha = when (stroke.tool) {
            InkTool.HIGHLIGHTER -> 92
            InkTool.PENCIL -> 205
            InkTool.MARKER -> 245
            else -> 255
        }
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
    }

    private val clearXfer: PorterDuffXfermode by lazy { PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    /** 曲线重采样的容差（页面像素）：弯得越厉害分得越细，直的地方只留端点 */
    private const val SPLINE_TOL = 0.3f

    private fun configureErasePaint(paint: Paint, width: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.BLACK
        paint.alpha = 255
        paint.xfermode = clearXfer
    }

    /**
     * Catmull-Rom 平滑采样：把采样点变成一条真正穿过这些点的平滑曲线。
     *
     * 以前是 Chaikin 细分一次、再按折线连。放大 4 倍就能看出笔迹是一段段直线拼的。
     * 这里按曲率自适应细分：直的地方一步走到下一个点，弯的地方最多分 16 段 ——
     * 点数还是同一个量级，视觉上却是连续曲线。
     */
    private fun smooth(pts: List<InkPoint>): List<InkPoint> {
        val n = pts.size
        if (n < 3) return pts
        val out = ArrayList<InkPoint>(n * 4)
        out.add(pts[0])
        for (i in 0 until n - 1) {
            val p0 = pts[if (i > 0) i - 1 else 0]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[if (i + 2 < n) i + 2 else n - 1]
            val k = hypot(p0.x - 2f * p1.x + p2.x, p0.y - 2f * p1.y + p2.y)
            val steps = (sqrt(k / SPLINE_TOL).toInt() + 1).coerceIn(1, 16)
            for (j in 1..steps) {
                val t = j.toFloat() / steps
                val t2 = t * t
                val t3 = t2 * t
                val x = 0.5f * (
                    2f * p1.x + (p2.x - p0.x) * t +
                        (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                        (3f * p1.x - p0.x - 3f * p2.x + p3.x) * t3
                    )
                val y = 0.5f * (
                    2f * p1.y + (p2.y - p0.y) * t +
                        (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                        (3f * p1.y - p0.y - 3f * p2.y + p3.y) * t3
                    )
                out.add(InkPoint(x, y, p1.p + (p2.p - p1.p) * t))
            }
        }
        return out
    }

    /**
     * 生成笔迹外形：**一条**闭合轮廓（左边界 → 末端圆头 → 右边界 → 起点圆头）。
     *
     * 旧实现是「每两个点画一个梯形」。梯形之间既不留缝也不共用边，于是：
     * 拐弯外侧留楔形缺口、内部则每条接缝都被抗锯齿画两次 —— 放大 4 倍就是
     * 截图里那种横穿笔迹的「竹节纹」。合并成一条轮廓后内部没有边，接缝自然消失；
     * 圆头也并进轮廓，荧光笔不会再在两端叠出深色圆斑。
     */
    fun buildPath(out: Path, pts: List<InkPoint>, tool: InkTool, base: Float) {
        out.reset()
        if (pts.size < 2) return
        val s = smooth(pts)
        val n = s.size
        if (n < 2) return

        val half = FloatArray(n)
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            val a = s[if (i > 0) i - 1 else 0]
            val b = s[if (i < n - 1) i + 1 else n - 1]
            var dx = b.x - a.x
            var dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 1e-5f) {
                dx = 1f
                dy = 0f
            } else {
                dx /= len
                dy /= len
            }
            // 用前后两点的中心差分求切线：拐角处两侧共用同一条法线，轮廓天然连续
            nx[i] = -dy
            ny[i] = dx
            half[i] = max(widthAt(tool, base, s[i].p) * 0.5f, 0.25f)
        }

        out.moveTo(s[0].x + nx[0] * half[0], s[0].y + ny[0] * half[0])
        for (i in 1 until n) out.lineTo(s[i].x + nx[i] * half[i], s[i].y + ny[i] * half[i])
        addCap(out, s[n - 1].x, s[n - 1].y, nx[n - 1], ny[n - 1], half[n - 1])
        for (i in n - 1 downTo 0) out.lineTo(s[i].x - nx[i] * half[i], s[i].y - ny[i] * half[i])
        addCap(out, s[0].x, s[0].y, -nx[0], -ny[0], half[0])
        out.close()
    }

    /**
     * 半圆圆头：从当前所在的 +法线 一侧绕到 -法线 一侧。
     * 切线 = 法线顺时针转 90°，所以扫掠角恒为 -180°（中间点按 -π·k/steps 取）。
     */
    private fun addCap(out: Path, cx: Float, cy: Float, ux: Float, uy: Float, r: Float) {
        if (r <= 0.01f) return
        val a0 = atan2(uy, ux)
        val steps = 10
        for (k in 1 until steps) {
            val a = a0 - PI.toFloat() * k / steps
            out.lineTo(cx + cos(a) * r, cy + sin(a) * r)
        }
    }

    private fun takePath(stroke: Stroke, tmp: Path, cache: Boolean): Path {
        if (cache) {
            stroke.cachedPath?.let { return it }
            val p = Path()
            buildPath(p, stroke.points, stroke.tool, stroke.width)
            stroke.cachedPath = p
            return p
        }
        buildPath(tmp, stroke.points, stroke.tool, stroke.width)
        return tmp
    }

    /**
     * 把整笔画到 canvas 上（含首尾圆头）。
     *
     * [cache] 为 true 时使用/填充笔迹上的路径缓存；书写过程中的临时笔迹必须传 false，
     * 因为它的点会持续增长，缓存会失效。
     */
    fun drawStroke(canvas: Canvas, stroke: Stroke, paint: Paint, tmp: Path, cache: Boolean = true) {
        val pts = stroke.points
        if (pts.isEmpty()) return
        configurePaint(paint, stroke)
        if (pts.size == 1) {
            val r = max(widthAt(stroke.tool, stroke.width, pts[0].p) * 0.5f, 0.5f)
            canvas.drawCircle(pts[0].x, pts[0].y, r, paint)
            return
        }
        val p = takePath(stroke, tmp, cache)
        canvas.drawPath(p, paint)
    }

    /**
     * 橡皮轨迹：用带圆头的粗描边画一条线，配合 CLEAR 混合模式把下面已有的笔迹挖掉。
     * 必须在 saveLayer 里执行，否则会把页面底图一起擦穿。
     */
    private fun drawEraserStroke(canvas: Canvas, s: Stroke, paint: Paint, tmp: Path) {
        val pts = s.points
        if (pts.isEmpty()) return
        configureErasePaint(paint, s.width)
        if (pts.size == 1) {
            canvas.drawCircle(pts[0].x, pts[0].y, s.width * 0.5f, paint)
            return
        }
        tmp.reset()
        tmp.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) tmp.lineTo(pts[i].x, pts[i].y)
        canvas.drawPath(tmp, paint)
    }

    /**
     * 圈擦：把多边形内部的墨清空。
     *
     * **只用于兼容旧数据**：1.3.2 的圈擦是把「圈」存成一条擦除轨迹（按形状裁），
     * 1.3.3 起改成「圈到即整笔删除」，不再产生这种轨迹。但旧笔记里已经存下的
     * 轨迹仍要按原样渲染，否则那部分擦除会失效、墨迹重新冒出来。
     *
     * 和橡皮一样必须在 saveLayer 里做 —— CLEAR 会把页面底图一起擦穿。
     * 只清墨不清底图，所以圈住 PPT 的印刷字不会把那一页挖成白洞。
     */
    fun drawLassoErase(canvas: Canvas, s: Stroke, paint: Paint) {
        if (s.points.size < 3) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = 255
        paint.xfermode = clearXfer
        canvas.drawPath(lassoPath(s), paint)
        paint.xfermode = null
    }

    private fun lassoPath(s: Stroke): Path {
        s.cachedPath?.let { return it }
        val p = Path()
        val pts = s.points
        if (pts.isEmpty()) return p
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
        p.close()
        s.cachedPath = p
        return p
    }

    /**
     * 按时间顺序渲染整串笔迹，正确处理「局部橡皮」与「圈擦」。
     *
     * 只要存在擦除轨迹，就把整段笔迹画进一个离屏图层，橡皮用 CLEAR 挖洞，
     * 最后整层合成到页面上 —— 这样擦掉的只有墨，不会伤到背景。
     * [clip] 是这个图层的范围（视图里传可视区域，导出时传整页），必须显式给出，
     * 否则带缩放的画布会按整页尺寸分配离屏缓冲，高倍缩放下直接 OOM。
     */
    fun renderAll(
        canvas: Canvas,
        strokes: List<Stroke>,
        paint: Paint,
        tmp: Path,
        clip: RectF
    ) {
        var hasMask = false
        for (s in strokes) {
            if (s.tool == InkTool.ERASER || s.tool == InkTool.LASSO_ERASE) {
                hasMask = true
                break
            }
        }
        if (hasMask) canvas.saveLayer(clip, null)
        for (s in strokes) {
            when (s.tool) {
                InkTool.ERASER -> drawEraserStroke(canvas, s, paint, tmp)
                InkTool.LASSO_ERASE -> drawLassoErase(canvas, s, paint)
                else -> drawStroke(canvas, s, paint, tmp)
            }
        }
        if (hasMask) canvas.restore()
    }

    /** 橡皮命中：点到笔迹任一线段的距离小于阈值 */
    fun hitStroke(stroke: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val pts = stroke.points
        if (pts.isEmpty()) return false
        val extra = stroke.width * 0.5f
        val r = radius + extra
        val r2 = r * r
        if (pts.size == 1) {
            val dx = pts[0].x - x
            val dy = pts[0].y - y
            return dx * dx + dy * dy <= r2
        }
        for (i in 0 until pts.size - 1) {
            if (distToSegmentSq(x, y, pts[i], pts[i + 1]) <= r2) return true
        }
        return false
    }

    private fun distToSegmentSq(px: Float, py: Float, a: InkPoint, b: InkPoint): Float {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val wx = px - a.x
        val wy = py - a.y
        val len2 = vx * vx + vy * vy
        var t = if (len2 <= 1e-6f) 0f else (wx * vx + wy * vy) / len2
        t = t.coerceIn(0f, 1f)
        val dx = px - (a.x + vx * t)
        val dy = py - (a.y + vy * t)
        return dx * dx + dy * dy
    }

    /** 套索判定：笔迹里多数点落在多边形内即视为选中 */
    fun strokeInsidePolygon(stroke: Stroke, poly: FloatArray): Boolean {        val pts = stroke.points
        if (pts.isEmpty() || poly.size < 6) return false
        var inside = 0
        for (pt in pts) {
            if (pointInPolygon(pt.x, pt.y, poly)) inside++
        }
        return inside >= max(1, (pts.size * 0.7f).toInt())
    }

    /** 圈擦用：只要有一个采样点落在圈里就算擦到了（用于避免空圈占一步撤销） */
    fun anyPointInside(stroke: Stroke, poly: FloatArray): Boolean {
        if (poly.size < 6) return false
        for (pt in stroke.points) if (pointInPolygon(pt.x, pt.y, poly)) return true
        return false
    }

    /**
     * 圈擦命中判定：笔迹与圈**有任何接触**就算命中。
     *
     * 三种情况都算：
     *  1. 笔迹有采样点落在圈内（圈把这一笔包住了）；
     *  2. 笔迹的某一段穿过了圈的边（笔横着划过圈外，两头都在圈外）；
     *  3. 圈整个落在一笔的包围盒里且笔迹贴着圈（一笔很大的圈套小圈）—— 由 1、2 覆盖，
     *     因为采样点密集时必然有段穿过边界。
     *
     * 命中就整笔删除，所以判定可以宽松一点：宁可多删一笔，也不要「圈明明碰到了却没反应」。
     */
    fun strokeHitPolygon(stroke: Stroke, poly: FloatArray): Boolean {
        val pts = stroke.points
        if (pts.isEmpty() || poly.size < 6) return false
        for (pt in pts) if (pointInPolygon(pt.x, pt.y, poly)) return true
        val n = poly.size / 2
        for (i in 0 until pts.size - 1) {
            val ax = pts[i].x
            val ay = pts[i].y
            val bx = pts[i + 1].x
            val by = pts[i + 1].y
            for (j in 0 until n) {
                val k = if (j + 1 == n) 0 else j + 1
                if (segmentsCross(ax, ay, bx, by, poly[j * 2], poly[j * 2 + 1], poly[k * 2], poly[k * 2 + 1])) {
                    return true
                }
            }
        }
        return false
    }

    private fun segmentsCross(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float
    ): Boolean {
        val d1 = cross(x3, y3, x4, y4, x1, y1)
        val d2 = cross(x3, y3, x4, y4, x2, y2)
        val d3 = cross(x1, y1, x2, y2, x3, y3)
        val d4 = cross(x1, y1, x2, y2, x4, y4)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        ) return true
        // 共线/贴边的情况也当作相交（判定宽松，符合「碰到了就删」）
        return (d1 == 0f && onSeg(x3, y3, x4, y4, x1, y1)) ||
            (d2 == 0f && onSeg(x3, y3, x4, y4, x2, y2)) ||
            (d3 == 0f && onSeg(x1, y1, x2, y2, x3, y3)) ||
            (d4 == 0f && onSeg(x1, y1, x2, y2, x4, y4))
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun onSeg(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
        px >= min(ax, bx) - 0.01f && px <= max(ax, bx) + 0.01f &&
            py >= min(ay, by) - 0.01f && py <= max(ay, by) + 0.01f

    private fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** 供 UI 显示的色块文字色 */
    fun onColor(color: Int): Int {
        val lum = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
        return if (lum > 0.62f) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
    }

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(
            min(255, max(0, r)),
            min(255, max(0, g)),
            min(255, max(0, bl))
        )
    }

    fun hsv(h: Float, s: Float, v: Float): Int = Color.HSVToColor(floatArrayOf(h, s, v))

    fun sameColor(a: Int, b: Int): Boolean = abs(a - b) == 0
}
