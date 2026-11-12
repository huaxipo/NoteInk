package com.noteink.app.store

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PPTX 形状的「外观」模型：主题色、填充、描边、预设几何。
 *
 * 为什么单独一个文件：`a:spPr` 里的东西组合爆炸（preset 几何 180 多种 × 填充 4 种 ×
 * 描边/箭头/虚线），塞进 [OfficeRender] 那个流式解析器会把它撑爆。这里只放纯函数与
 * 纯数据，解析状态机留在 OfficeRender。
 */
internal object PptxShape {

    // ======================= 主题色 =======================

    /** Office 默认主题（`Office 主题`）。主题文件缺失时用它兜底，否则 schemeClr 会全黑。 */
    private val DEFAULT_SCHEME = mapOf(
        "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(),
        "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
        "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(),
        "accent3" to 0xFFA5A5A5.toInt(), "accent4" to 0xFFFFC000.toInt(),
        "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt(),
        "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt()
    )

    /**
     * 主题色方案 + 母版的 `p:clrMap`。
     *
     * `clrMap` 是「槽位 → 主题色」的重定向：母版里写 `bg1="lt1"`，幻灯片里的
     * `<a:schemeClr val="bg1"/>` 就要落到 lt1。漏了这层，深色背景的模板会整个反色。
     */
    internal class Theme(
        private val scheme: Map<String, Int>,
        private val cmap: Map<String, String>
    ) {
        /** 主题的填充样式表（`a:fillStyleLst`），供 `a:fillRef` 用；1 起算 */
        var fillStyles: List<FillStyle> = emptyList()
        /** 主题的线样式表（`a:lnStyleLst`），供 `a:lnRef` 用；1 起算 */
        var lnStyles: List<LnStyle> = emptyList()
        /** 主题的背景填充样式表，供 `a:bgRef idx="1001.."` 用 */
        var bgStyles: List<FillStyle> = emptyList()

        fun resolve(name: String?): Int? {
            if (name == null) return null
            val mapped = cmap[name] ?: name
            return scheme[mapped] ?: scheme[name] ?: DEFAULT_SCHEME[mapped]
        }

        companion object {
            val EMPTY = Theme(DEFAULT_SCHEME, emptyMap())

            fun parse(themeXml: String?, clrMap: Map<String, String>): Theme {
                if (themeXml == null) return Theme(DEFAULT_SCHEME, clrMap)
                val found = HashMap<String, Int>()
                val keys = setOf(
                    "dk1", "lt1", "dk2", "lt2",
                    "accent1", "accent2", "accent3", "accent4", "accent5", "accent6",
                    "hlink", "folHlink"
                )
                try {
                    val p = Xml.newPullParser()
                    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    p.setInput(ByteArrayInputStream(themeXml.toByteArray()), "UTF-8")
                    var slot: String? = null
                    var event = p.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> when (p.name) {
                                in keys -> slot = p.name
                                "srgbClr" -> {
                                    val v = attr(p, "val")
                                    if (slot != null && v != null) {
                                        parseIntColor(v)?.let { found[slot!!] = it }
                                    }
                                }
                                "sysClr" -> {
                                    val v = attr(p, "lastClr")
                                    if (slot != null && v != null) {
                                        parseIntColor(v)?.let { found[slot!!] = it }
                                    }
                                }
                            }
                            XmlPullParser.END_TAG -> if (p.name in keys) slot = null
                        }
                        event = p.next()
                    }
                } catch (_: Throwable) {
                }
                val merged = HashMap(DEFAULT_SCHEME)
                merged.putAll(found)
                val theme = Theme(merged, clrMap)
                val (fills, lines, bgs) = parseFmt(themeXml) { name ->
                    theme.resolve(name)
                }
                theme.fillStyles = fills
                theme.lnStyles = lines
                theme.bgStyles = bgs
                return theme
            }
        }
    }

    /** 母版 `p:clrMap` 的槽位映射；缺省时槽位名就是主题色名 */
    fun parseClrMap(masterXml: String?): Map<String, String> {
        if (masterXml == null) return emptyMap()
        val out = HashMap<String, String>()
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(ByteArrayInputStream(masterXml.toByteArray()), "UTF-8")
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && p.name == "clrMap") {
                    for (i in 0 until p.attributeCount) {
                        val k = p.getAttributeName(i)
                        if (k == "val" || k == "hlinkClick" || k == "hlinkMouseOver") continue
                        if (k.startsWith("xmlns")) continue
                        out[k] = p.getAttributeValue(i)
                    }
                    return out
                }
                event = p.next()
            }
        } catch (_: Throwable) {
        }
        return out
    }

    // ======================= 主题格式方案（p:style 引用） =======================

    /**
     * 主题色令牌。`<a:schemeClr val="phClr">` 是占位符，真正的颜色由引用方
     * （`a:fillRef` 里的子颜色）填进来 —— 这是「形状没写填充、但通过样式取色」的实现方式。
     */
    internal class ColorTok(
        val base: Int,
        val lumMod: Float,
        val lumOff: Float,
        val alpha: Int
    ) {
        fun resolve(ph: Int): Int = modColor(if (base < 0) ph else base, lumMod, lumOff, alpha)
    }

    internal class FillStyle(
        val kind: Int,
        val stops: List<ColorTok>,
        val pos: FloatArray,
        val angle: Float
    ) {
        fun make(ph: Int): Fill? {
            if (stops.isEmpty()) return null
            return when (kind) {
                1 -> Fill.solid(stops[0].resolve(ph))
                2 -> Fill(
                    2,
                    stops = IntArray(stops.size) { stops[it].resolve(ph) },
                    stopPos = pos,
                    angle = angle
                )
                else -> null
            }
        }
    }

    internal class LnStyle(val widthEmu: Float, val dashed: Boolean, val color: ColorTok?)

    /** 解析 `a:fmtScheme`：填充样式表、线样式表、背景填充样式表 */
    private fun parseFmt(
        themeXml: String,
        resolve: (String) -> Int?
    ): Triple<List<FillStyle>, List<LnStyle>, List<FillStyle>> {
        val fills = ArrayList<FillStyle>()
        val lines = ArrayList<LnStyle>()
        val bgs = ArrayList<FillStyle>()
        try {
            val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = true
            val doc = dbf.newDocumentBuilder()
                .parse(ByteArrayInputStream(themeXml.toByteArray()))
            val root = doc.documentElement ?: return Triple(fills, lines, bgs)
            // fmtScheme 挂在 a:themeElements 下面，不是 a:theme 的直接子元素。
            // 以前直接找 root 的子节点永远返回 null，整张填充/描边样式表都是空的，
            // 于是所有「没写颜色、靠 fillRef/lnRef 取主题」的形状全部无色透明。
            val fmt = root.findChild("themeElements")?.findChild("fmtScheme")
                ?: root.findChild("fmtScheme")
                ?: return Triple(fills, lines, bgs)
            fmt.findChild("fillStyleLst")?.let { lst ->
                for (i in 0 until lst.childNodes.length) {
                    val e = lst.childNodes.item(i) as? org.w3c.dom.Element ?: continue
                    readFillStyle(e, resolve)?.let { fills.add(it) }
                }
            }
            fmt.findChild("lnStyleLst")?.let { lst ->
                for (i in 0 until lst.childNodes.length) {
                    val e = lst.childNodes.item(i) as? org.w3c.dom.Element ?: continue
                    val w = e.getAttribute("w").toFloatOrNull() ?: 12700f
                    val dash = e.findChild("prstDash")?.getAttribute("val")?.let { it != "solid" } ?: false
                    val color = e.findChild("solidFill")?.let { sf -> domColor(sf, resolve) }
                    lines.add(LnStyle(w, dash, color))
                }
            }
            fmt.findChild("bgFillStyleLst")?.let { lst ->
                for (i in 0 until lst.childNodes.length) {
                    val e = lst.childNodes.item(i) as? org.w3c.dom.Element ?: continue
                    readFillStyle(e, resolve)?.let { bgs.add(it) }
                }
            }
        } catch (_: Throwable) {
        }
        return Triple(fills, lines, bgs)
    }

    private fun readFillStyle(e: org.w3c.dom.Element, resolve: (String) -> Int?): FillStyle? {
        val name = e.localName ?: e.nodeName.substringAfter(':')
        if (name == "solidFill") {
            val tok = domColor(e, resolve) ?: return null
            return FillStyle(1, listOf(tok), floatArrayOf(0f), 0f)
        }
        if (name != "gradFill") return null
        val stops = ArrayList<ColorTok>()
        val pos = ArrayList<Float>()
        val gsLst = e.findChild("gsLst") ?: return null
        for (i in 0 until gsLst.childNodes.length) {
            val gs = gsLst.childNodes.item(i) as? org.w3c.dom.Element ?: continue
            val tok = domColor(gs, resolve) ?: continue
            stops.add(tok)
            pos.add((gs.getAttribute("pos").toFloatOrNull() ?: 0f) / 100000f)
        }
        if (stops.isEmpty()) return null
        val ang = e.findChild("lin")?.getAttribute("ang")?.toFloatOrNull() ?: 0f
        return FillStyle(2, stops, pos.toFloatArray(), ang / 60000f)
    }

    /** 从一个容器元素里取第一个颜色（含 lumMod/lumOff 子元素） */
    private fun domColor(parent: org.w3c.dom.Element, resolve: (String) -> Int?): ColorTok? {
        for (i in 0 until parent.childNodes.length) {
            val e = parent.childNodes.item(i) as? org.w3c.dom.Element ?: continue
            val name = (e.localName ?: e.nodeName.substringAfter(':')).let {
                if (it.contains(':')) it.substringAfter(':') else it
            }
            val base = when (name) {
                "srgbClr" -> parseIntColor(e.getAttribute("val"))
                "schemeClr" -> {
                    val v = e.getAttribute("val")
                    if (v == "phClr") -1 else resolve(v)
                }
                "sysClr" -> parseIntColor(e.getAttribute("lastClr"))
                else -> continue
            }
            var lumMod = 1f
            var lumOff = 0f
            var alpha = 255
            for (j in 0 until e.childNodes.length) {
                val m = e.childNodes.item(j) as? org.w3c.dom.Element ?: continue
                val mn = m.localName ?: m.nodeName.substringAfter(':')
                val v = m.getAttribute("val").toFloatOrNull() ?: 100000f
                when (mn) {
                    "lumMod" -> lumMod = v / 100000f
                    "lumOff" -> lumOff = v / 100000f
                    "shade" -> lumMod *= v / 100000f
                    "alpha" -> alpha = (v / 100000f * 255f).toInt().coerceIn(0, 255)
                    "tint" -> lumOff += (1f - v / 100000f) * 0.55f
                }
            }
            // schemeClr 且不是 phClr 时，把主题色当基色（此处拿不到主题表，交给调用方覆盖）
            return ColorTok(base ?: -1, lumMod, lumOff, alpha)
        }
        return null
    }

    private fun org.w3c.dom.Element.findChild(name: String): org.w3c.dom.Element? {
        for (i in 0 until childNodes.length) {
            val e = childNodes.item(i) as? org.w3c.dom.Element ?: continue
            val n = e.localName ?: e.nodeName.substringAfter(':')
            if (n == name) return e
        }
        return null
    }

    // ======================= 颜色修饰 =======================

    /**
     * 亮度修饰。Office 的 lumMod / lumOff 作用在 HSL 的亮度轴上，这里用
     * 「RGB 等比缩放 + 偏移」近似 —— 对渐变、色带这类大面积填充，肉眼看不出差别，
     * 而完整的 HSL 换算要把每个形状都算一遍三角函数，导入 127 页时是纯浪费。
     */
    fun modColor(base: Int, lumMod: Float, lumOff: Float, alpha: Int): Int {
        val r = (Color.red(base) * lumMod + 255f * lumOff).roundToInt().coerceIn(0, 255)
        val g = (Color.green(base) * lumMod + 255f * lumOff).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(base) * lumMod + 255f * lumOff).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, r, g, b)
    }

    fun parseIntColor(v: String): Int? {
        val s = v.trim().removePrefix("#")
        return try {
            when (s.length) {
                6 -> 0xFF000000.toInt() or s.toLong(16).toInt()
                8 -> s.toLong(16).toInt()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ======================= 填充 =======================

    internal class Fill(
        val kind: Int, // 1 纯色 / 2 渐变 / 3 图片
        val color: Int = Color.BLACK,
        val stops: IntArray? = null,
        val stopPos: FloatArray? = null,
        /** 渐变角度，单位度；0 = 从左到右 */
        val angle: Float = 0f,
        val ref: OfficeRender.ImgRef? = null,
        val crop: RectF? = null
    ) {
        /** 依包围盒造一支已配好的画笔；图片填充返回 null（调用方走 drawBitmap） */
        fun paint(bounds: RectF): Paint? {
            when (kind) {
                1 -> return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = this@Fill.color
                }
                2 -> {
                    val st = stops
                    val po = stopPos
                    if (st == null || po == null || st.isEmpty() || st.size != po.size) return null
                    val (x0, y0, x1, y1) = gradLine(bounds, angle)
                    val colors = if (st.size < 2) intArrayOf(st[0], st[0]) else st
                    val pos = if (po.size < 2) floatArrayOf(0f, 1f) else po
                    val shader = LinearGradient(
                        x0, y0, x1, y1, colors, pos, Shader.TileMode.CLAMP
                    )
                    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.shader = shader
                    }
                }
            }
            return null
        }

        companion object {
            fun solid(c: Int) = Fill(1, c)
        }
    }

    /** 渐变轴：以包围盒中心为原点，沿角度取半径长度的两端 */
    private fun gradLine(b: RectF, angleDeg: Float): FloatArray {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        // 投影到包围盒对角线的一半，保证两端都落在形状外
        val half = (abs(dx) * b.width() + abs(dy) * b.height()) * 0.5f
        val cx = b.centerX()
        val cy = b.centerY()
        return floatArrayOf(
            cx - dx * half, cy - dy * half,
            cx + dx * half, cy + dy * half
        )
    }

    // ======================= 描边 =======================

    internal class Line(
        val color: Int,
        val width: Float,
        val dashed: Boolean,
        /** 0 无 / 1 实心三角 / 2 开口箭头 / 3 圆点 */
        val head: Int,
        val tail: Int
    )

    // ======================= 预设几何 =======================

    /**
     * 把 `a:prstGeom` 的预设名画成**单位方形（0..1）**里的路径，绘制时再按形状的
     * w/h 缩放。这样同一套几何既能用在导入的 1654px 宽画布上，也能用在高清重渲染的
     * 任意倍率上，不用为两者各写一份坐标。
     *
     * 覆盖的是课件里真正会出现的形状：矩形/圆角矩形/椭圆/三角形/菱形/箭头/星形/
     * 流程图形状/连接线。没见过的名字退回矩形 —— 位置对了，至少不会丢内容。
     */
    fun buildPreset(prst: String?, adj: Map<String, Float>, out: Path, w: Float = 0f, h: Float = 0f): Boolean {
        out.reset()
        out.fillType = Path.FillType.WINDING
        val a1 = adj["adj"] ?: adj["adj1"] ?: 0f
        // 花括号的两个手柄按 OOXML 的公式来：
        //   a2      = pin(adj2, 0, 100000)          → 中间那个尖的纵向位置
        //   maxAdj1 = min(100000-a2, a2)/2 * h/ss   → 弧高上限（ss = min(w,h)）
        //   y1      = ss*a1/100000                  → 上下两段弧的高度
        // 注意 maxAdj1 是**跟着 h/ss 变**的：细长的 `{` 上限非常大，
        // 所以 adj1="79242" 这种值完全合法。上一版把它硬钳到 12500，
        // 弧高被压成 0.03 英寸，画出来就是「一根直线加两个小钩」。
        // 换算进单位方框时 y1 要除以 h，水平方向则铺满整个 w。
        val a2 = (adj["adj2"] ?: 50000f).coerceIn(0f, 100000f)
        val ss = if (w > 0f && h > 0f) min(w, h) else 1f
        val q3 = min(100000f - a2, a2) / 2f
        val maxA1 = if (w > 0f && h > 0f) q3 * h / ss else q3
        val a1c = (if (a1 > 0f) a1 else 8333f).coerceIn(0f, maxA1.coerceAtLeast(1f))
        val rPx = ss * a1c / 100000f
        val ry = (if (h > 0f) rPx / h else 0.08f).coerceIn(0.002f, 0.49f)
        val ryMid = if (a2 > 0f) (a2 / 100000f) else 0.5f
        when (prst) {
            null, "", "rect", "textBox", "flowChartProcess", "flowChartAlternateProcess",
            "flowChartPredefinedProcess", "actionButtonBlank" -> out.addRect(RectF(0f, 0f, 1f, 1f), Path.Direction.CW)

            "roundRect", "flowChartTerminator" -> {
                val r = if (prst == "flowChartTerminator") {
                    0.5f
                } else {
                    (if (a1 > 0f) a1 / 100000f else 0.16667f).coerceIn(0f, 0.5f)
                }
                out.addRoundRect(RectF(0f, 0f, 1f, 1f), r, r, Path.Direction.CW)
            }

            "ellipse", "oval", "flowChartConnector", "flowChartOffpageConnector",
            "actionButtonInformation" -> out.addOval(RectF(0f, 0f, 1f, 1f), Path.Direction.CW)

            "donut", "noSmoking" -> {
                out.fillType = Path.FillType.EVEN_ODD
                out.addOval(RectF(0f, 0f, 1f, 1f), Path.Direction.CW)
                val t = 0.25f
                out.addOval(RectF(t, t, 1f - t, 1f - t), Path.Direction.CW)
            }

            "triangle", "flowChartExtract" -> {
                out.moveTo(0.5f, 0f); out.lineTo(1f, 1f); out.lineTo(0f, 1f); out.close()
            }

            "rtTriangle", "flowChartMerge" -> {
                out.moveTo(0f, 0f); out.lineTo(0f, 1f); out.lineTo(1f, 1f); out.close()
            }

            "diamond", "flowChartDecision" -> {
                out.moveTo(0.5f, 0f); out.lineTo(1f, 0.5f)
                out.lineTo(0.5f, 1f); out.lineTo(0f, 0.5f); out.close()
            }

            "hexagon" -> {
                out.moveTo(0.25f, 0f); out.lineTo(0.75f, 0f); out.lineTo(1f, 0.5f)
                out.lineTo(0.75f, 1f); out.lineTo(0.25f, 1f); out.lineTo(0f, 0.5f)
                out.close()
            }

            "pentagon" -> regular(out, 5, 0f)
            "heptagon" -> regular(out, 7, 0f)
            "octagon" -> regular(out, 8, 22.5f)
            "decagon" -> regular(out, 10, 0f)
            "dodecagon" -> regular(out, 12, 0f)

            "star4" -> star(out, 4, 0.125f, 0f)
            "star5" -> star(out, 5, if (a1 > 0f) a1 / 100000f else 0.19098f, 0f)
            "star6" -> star(out, 6, 0.28867f, 0f)
            "star7" -> star(out, 7, 0.33f, 0f)
            "star8" -> star(out, 8, 0.325f, 0f)
            "star10" -> star(out, 10, 0.42f, 0f)
            "star12" -> star(out, 12, 0.42f, 0f)

            "rightArrow" -> hArrow(out, right = true)
            "leftArrow" -> hArrow(out, right = false)
            "upArrow" -> vArrow(out, down = false)
            "downArrow" -> vArrow(out, down = true)
            "leftRightArrow" -> bothArrow(out, horizontal = true)
            "upDownArrow" -> bothArrow(out, horizontal = false)
            "notchedRightArrow", "stripedRightArrow" -> hArrow(out, right = true)

            "chevron" -> {
                val t = 0.5f
                out.moveTo(0f, 0f); out.lineTo(1f, 0f); out.lineTo(1f - t, 0.5f)
                out.lineTo(1f, 1f); out.lineTo(0f, 1f); out.lineTo(t, 0.5f); out.close()
            }

            "homePlate" -> {
                out.moveTo(0f, 0f); out.lineTo(0.75f, 0f); out.lineTo(1f, 0.5f)
                out.lineTo(0.75f, 1f); out.lineTo(0f, 1f); out.close()
            }

            "parallelogram", "flowChartInputOutput" -> {
                val t = 0.25f
                out.moveTo(t, 0f); out.lineTo(1f, 0f); out.lineTo(1f - t, 1f)
                out.lineTo(0f, 1f); out.close()
            }

            "trapezoid" -> {
                val t = 0.25f
                out.moveTo(t, 0f); out.lineTo(1f - t, 0f); out.lineTo(1f, 1f)
                out.lineTo(0f, 1f); out.close()
            }

            "flowChartManualInput" -> {
                out.moveTo(0f, 0.2f); out.lineTo(1f, 0f); out.lineTo(1f, 1f)
                out.lineTo(0f, 1f); out.close()
            }

            "flowChartDocument" -> {
                // 底部波浪：用两段三阶贝塞尔近似
                out.moveTo(0f, 0f); out.lineTo(1f, 0f); out.lineTo(1f, 0.82f)
                out.cubicTo(0.75f, 1f, 0.45f, 0.68f, 0f, 0.88f)
                out.close()
            }

            "plus", "mathPlus", "quadArrow" -> {
                val t = 0.33f
                val u = 1f - t
                out.moveTo(t, 0f); out.lineTo(u, 0f); out.lineTo(u, t); out.lineTo(1f, t)
                out.lineTo(1f, u); out.lineTo(u, u); out.lineTo(u, 1f); out.lineTo(t, 1f)
                out.lineTo(t, u); out.lineTo(0f, u); out.lineTo(0f, t); out.lineTo(t, t)
                out.close()
            }

            "mathMinus" -> out.addRect(RectF(0f, 0.4f, 1f, 0.6f), Path.Direction.CW)

            "line", "straightConnector1" -> {
                out.moveTo(0f, 0f); out.lineTo(1f, 1f)
            }

            "bentConnector2", "elbowConnector" -> {
                out.moveTo(0f, 0f); out.lineTo(1f, 0f); out.lineTo(1f, 1f)
            }

            "bentConnector3", "bentConnector4", "bentConnector5",
            "curvedConnector3", "curvedConnector4", "curvedConnector5" -> {
                out.moveTo(0f, 0f); out.lineTo(0.5f, 0f)
                out.lineTo(0.5f, 1f); out.lineTo(1f, 1f)
            }

            "curvedConnector2" -> {
                out.moveTo(0f, 0f); out.lineTo(1f, 0f); out.lineTo(1f, 1f)
            }

            "arc", "blockArc" -> {
                // 只画弧线本身；填充意义不大
                out.moveTo(1f, 0f)
                out.quadTo(0f, 0f, 0f, 1f)
            }

            // 大括号 / 方括号。课件里常用来把一段代码或一组内存块「括起来」，
            // 而且经常配合 rot=90 横过来用（右大括号转 90° 就是横着的 }）。
            // 以前没实现，全部退化成矩形，画出来是一个莫名其妙的方框。
            "leftBrace", "rightBrace", "leftBracket", "rightBracket" -> {
                // gx(0) 落在「开口 / 尖」那一侧，gx(1) 落在「钩」那一侧。
                // leftBrace 是尖朝左、两端钩在右；rightBrace 正好反过来。
                val gx = { x: Float -> if (prst == "leftBrace" || prst == "leftBracket") x else 1f - x }
                if (prst.endsWith("Bracket")) {
                    // [ 和 ] ：一条竖干加两条横杠
                    out.moveTo(gx(1f), 0f)
                    out.lineTo(gx(0f), 0f)
                    out.lineTo(gx(0f), 1f)
                    out.lineTo(gx(1f), 1f)
                } else {
                    // 大括号 = 四段 1/4 椭圆弧（上下钩各一段、中间尖两段）+ 两段直线。
                    //
                    // 关键在**切线方向**。顶部钩从右上角出发时，PowerPoint 画的是一段
                    // 「先水平向左、再拐成垂直向下」的弧；底部钩则是「垂直向下、最后
                    // 转成水平向右」。早先两条都用控制点在 (1, ry) 的二次贝塞尔，
                    // 起点切线成了垂直向下，钩就变成一个生硬的直角 ——
                    // 和 PowerPoint 的渲染逐像素比对时，差别一眼就能看出来。
                    //
                    // 现在换成标准 1/4 椭圆弧的三次贝塞尔近似（k = 0.5523）。椭圆中心
                    // 落在形状框**外面**，只取框内那一段，切线方向自然就对了。
                    // 竖干固定在 0.5：实测 PowerPoint 里竖干正好落在框宽正中。
                    val m = ryMid
                    val k = 0.5523f        // 1/4 圆弧的三次贝塞尔系数
                    val kh = 1f - k        // 0.4477，控制点距端点的高度比例
                    val r = ry
                    out.moveTo(gx(1f), 0f)
                    out.cubicTo(gx(1f - k * 0.5f), 0f, gx(0.5f), r * kh, gx(0.5f), r)
                    out.lineTo(gx(0.5f), m - r)
                    out.cubicTo(gx(0.5f), m - r * kh, gx(k * 0.5f), m, gx(0f), m)
                    out.cubicTo(gx(k * 0.5f), m, gx(0.5f), m + r * kh, gx(0.5f), m + r)
                    out.lineTo(gx(0.5f), 1f - r)
                    out.cubicTo(gx(0.5f), 1f - r * kh, gx(1f - k * 0.5f), 1f, gx(1f), 1f)
                }
            }

            "pie" -> {
                out.moveTo(0.5f, 0.5f); out.lineTo(1f, 0.5f)
                out.arcTo(RectF(0f, 0f, 1f, 1f), 0f, 180f, false)
                out.close()
            }

            "chord" -> {
                out.moveTo(1f, 0.5f)
                out.arcTo(RectF(0f, 0f, 1f, 1f), 0f, 180f, false)
                out.close()
            }

            // 没覆盖到的预设（各种标注框、立体图形）退回矩形：
            // 轮廓不精确，但位置、颜色、尺寸都对，总比整块消失好
            else -> out.addRect(RectF(0f, 0f, 1f, 1f), Path.Direction.CW)
        }
        return true
    }

    private fun regular(out: Path, n: Int, rotDeg: Float) {
        val cx = 0.5f
        val cy = 0.5f
        val r = 0.5f
        val start = -90f + rotDeg
        for (i in 0 until n) {
            val a = Math.toRadians((start + 360f / n * i).toDouble())
            val x = cx + r * cos(a).toFloat()
            val y = cy + r * sin(a).toFloat()
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
    }

    private fun star(out: Path, points: Int, innerRatio: Float, rotDeg: Float) {
        val cx = 0.5f
        val cy = 0.5f
        val ro = 0.5f
        val ri = (innerRatio.coerceIn(0.05f, 0.95f)) * 0.5f
        val start = -90f + rotDeg
        val n = points * 2
        for (i in 0 until n) {
            val r = if (i % 2 == 0) ro else ri
            val a = Math.toRadians((start + 360f / n * i).toDouble())
            val x = cx + r * cos(a).toFloat()
            val y = cy + r * sin(a).toFloat()
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
    }

    /** 单头水平箭头 */
    private fun hArrow(out: Path, right: Boolean) {
        val shaft = 0.22f
        val head = 0.45f
        if (right) {
            out.moveTo(0f, 0.5f - shaft); out.lineTo(1f - head, 0.5f - shaft)
            out.lineTo(1f - head, 0f); out.lineTo(1f, 0.5f)
            out.lineTo(1f - head, 1f); out.lineTo(1f - head, 0.5f + shaft)
            out.lineTo(0f, 0.5f + shaft)
        } else {
            out.moveTo(1f, 0.5f - shaft); out.lineTo(head, 0.5f - shaft)
            out.lineTo(head, 0f); out.lineTo(0f, 0.5f)
            out.lineTo(head, 1f); out.lineTo(head, 0.5f + shaft)
            out.lineTo(1f, 0.5f + shaft)
        }
        out.close()
    }

    private fun vArrow(out: Path, down: Boolean) {
        val shaft = 0.22f
        val head = 0.45f
        if (down) {
            out.moveTo(0.5f - shaft, 0f); out.lineTo(0.5f + shaft, 0f)
            out.lineTo(0.5f + shaft, 1f - head); out.lineTo(1f, 1f - head)
            out.lineTo(0.5f, 1f); out.lineTo(0f, 1f - head)
            out.lineTo(0.5f - shaft, 1f - head)
        } else {
            out.moveTo(0.5f - shaft, 1f); out.lineTo(0.5f + shaft, 1f)
            out.lineTo(0.5f + shaft, head); out.lineTo(1f, head)
            out.lineTo(0.5f, 0f); out.lineTo(0f, head)
            out.lineTo(0.5f - shaft, head)
        }
        out.close()
    }

    private fun bothArrow(out: Path, horizontal: Boolean) {
        val shaft = 0.22f
        val head = 0.32f
        if (horizontal) {
            out.moveTo(0f, 0.5f); out.lineTo(head, 0f); out.lineTo(head, 0.5f - shaft)
            out.lineTo(1f - head, 0.5f - shaft); out.lineTo(1f - head, 0f)
            out.lineTo(1f, 0.5f); out.lineTo(1f - head, 1f)
            out.lineTo(1f - head, 0.5f + shaft); out.lineTo(head, 0.5f + shaft)
            out.lineTo(head, 1f)
        } else {
            out.moveTo(0.5f, 0f); out.lineTo(0f, head); out.lineTo(0.5f - shaft, head)
            out.lineTo(0.5f - shaft, 1f - head); out.lineTo(0f, 1f - head)
            out.lineTo(0.5f, 1f); out.lineTo(1f, 1f - head)
            out.lineTo(0.5f + shaft, 1f - head); out.lineTo(0.5f + shaft, head)
            out.lineTo(1f, head)
        }
        out.close()
    }

    // ======================= 自定义几何 =======================

    /**
     * `a:custGeom` 的路径收集器。坐标写在路径自己的 `w`/`h` 空间里，这里直接归一化到
     * 0..1，绘制时按形状尺寸缩放。
     */
    internal class CustPath(
        private val pathW: Float,
        private val pathH: Float
    ) {
        val path = Path()
        private var started = false

        private fun nx(v: Float) = if (pathW > 0f) v / pathW else 0f
        private fun ny(v: Float) = if (pathH > 0f) v / pathH else 0f

        fun moveTo(x: Float, y: Float) {
            path.moveTo(nx(x), ny(y))
            started = true
        }

        fun lineTo(x: Float, y: Float) {
            if (!started) moveTo(x, y) else path.lineTo(nx(x), ny(y))
        }

        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            if (!started) moveTo(x1, y1)
            path.cubicTo(nx(x1), ny(y1), nx(x2), ny(y2), nx(x3), ny(y3))
        }

        fun quad(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (!started) moveTo(x1, y1)
            path.quadTo(nx(x1), ny(y1), nx(x2), ny(y2))
        }

        fun close() = path.close()
    }

    fun attr(p: XmlPullParser, local: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            if (n == local || n.endsWith(":$local")) return p.getAttributeValue(i)
        }
        return null
    }
}
