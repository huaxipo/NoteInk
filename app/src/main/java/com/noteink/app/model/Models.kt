package com.noteink.app.model

import android.graphics.Path

/**
 * 可用的书写工具。ERASER / LASSO 属于操作工具，不产生笔迹。
 *
 * 注意：开启「局部擦除」后，ERASER 类型的笔迹会作为一条「擦除轨迹」留在笔迹流里，
 * 按时间顺序参与渲染（把前面画过的墨挖掉），因此它需要被存储。
 */
enum class InkTool(val label: String) {
    PEN("钢笔"),
    PENCIL("铅笔"),
    MARKER("马克"),
    HIGHLIGHTER("荧光"),
    ERASER("橡皮"),
    LASSO("套索"),

    /**
     * 圈擦：画一个圈，圈里的墨全部清掉（不伤底图）。和橡皮一样作为一条「擦除轨迹」
     * 存在笔迹流里，按时间顺序参与渲染，所以可以撤销，也能跟着备份一起走。
     * 新值必须加在末尾 —— 笔迹文件按 ordinal 存工具类型。
     */
    LASSO_ERASE("圈擦");

    val isInk: Boolean get() = this == PEN || this == PENCIL || this == MARKER || this == HIGHLIGHTER
}

/**
 * 一个采样点。坐标使用「页面坐标系」，与屏幕无关。
 * p = 压感 0..1（真实压感来自主动式手写笔；普通电容笔/手指由速度模拟得到）
 */
data class InkPoint(val x: Float, val y: Float, val p: Float)

/**
 * 一笔。用类而不是 data class，便于在集合里按引用判等。
 *
 * 内含两个渲染缓存（包围盒 / 外形路径）：笔迹提交后点不再变化，
 * 缓存让「每帧重绘全部笔迹」的代价降到只有 drawPath 调用本身。
 * 任何几何变更（平移、重算）都必须调用 [invalidate]。
 */
class Stroke(
    val tool: InkTool,
    var color: Int,
    var width: Float,
    val points: MutableList<InkPoint>
) {
    /** 包围盒缓存，用于视口裁剪。只读，调用方不要修改 */
    var cachedBounds: FloatArray? = null
        private set

    /** 外形路径缓存，由 [com.noteink.app.ink.Ink] 在首次绘制时填充 */
    var cachedPath: Path? = null

    fun invalidate() {
        cachedBounds = null
        cachedPath = null
    }

    fun bounds(): FloatArray {
        cachedBounds?.let { return it }
        if (points.isEmpty()) return ZERO
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (pt in points) {
            if (pt.x < l) l = pt.x
            if (pt.x > r) r = pt.x
            if (pt.y < t) t = pt.y
            if (pt.y > b) b = pt.y
        }
        val arr = floatArrayOf(l, t, r, b)
        cachedBounds = arr
        return arr
    }

    fun translate(dx: Float, dy: Float) {
        for (i in points.indices) {
            val pt = points[i]
            points[i] = InkPoint(pt.x + dx, pt.y + dy, pt.p)
        }
        invalidate()
    }

    companion object {
        private val ZERO = floatArrayOf(0f, 0f, 0f, 0f)
    }
}

/** 空白页的底纹样式 */
enum class PageStyle(val label: String) {
    BLANK("空白"),
    GRID("方格"),
    LINES("横线"),
    DOTS("点阵")
}

/**
 * 一页的元信息。
 * bgFile 为导入内容（PDF 页 / 图片 / 文本排版结果）栅格化后的图片文件名；
 * 为 null 表示空白页，按 style 生成底纹。
 */
data class PageMeta(
    val id: String,
    val bgFile: String?,
    val width: Int,
    val height: Int,
    val style: PageStyle = PageStyle.BLANK,
    /**
     * 该页的来源文件（笔记本目录内的相对文件名，目前只有 PDF 用）。
     * 保留它就能在放大时按需重渲染矢量区域，而不是把栅格图放大。
     */
    val src: String? = null,
    /** 在来源文件中的页序号，-1 表示无 */
    val srcPage: Int = -1
)

data class NotebookMeta(
    val id: String,
    var title: String,
    var pages: MutableList<PageMeta>,
    var updatedAt: Long,
    /**
     * 底图是用哪一版渲染引擎画出来的。
     * 老笔记没有这个字段，读出来是 0 —— 打开时会用源文件自动重画一遍底图。
     */
    var renderV: Int = 0
)
