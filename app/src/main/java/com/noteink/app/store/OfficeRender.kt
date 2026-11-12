package com.noteink.app.store

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.util.Xml
import com.noteink.app.store.Importers.PageSink
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.IdentityHashMap
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Office 文档的本地保真渲染。
 *
 * 目标不是 100% 还原（那需要完整排版引擎），而是把用户真正在意的信息留住：
 *  - DOCX：段落对齐、段前段后距、首行缩进、粗体/斜体/字号/颜色、内嵌图片、表格网格；
 *  - PPTX：按形状在幻灯片上的真实坐标摆放文本框与图片，保留字号/颜色/对齐，表格转网格。
 *
 * 换行交给系统的 StaticLayout（通过 Spannable 承载每段文字的样式），分页由 [_Renderer] 完成。
 */
internal object OfficeRender {

    // A4 页面 1240px 宽 = 8.27in，即 150 DPI
    private const val DPI = 150f
    private const val EMU_PER_INCH = 914_400f
    private const val TWIPS_PER_INCH = 1440f
    /** 不换行空格：制表位用它当「垫片」 */
    private const val NBSP = "\u00A0"
    /**
     * 默认制表位宽度（EMU）。
     *
     * OOXML 规范给的是 1 英寸，但课件里用 `\t` 拍出来的树状图/目录，1 英寸会把
     * 后面几列顶到花括号右边去（第 2 章第 13 页的 C++ 数据类型树最典型：6 列
     * 塞进 8.8 英寸宽的框）。0.82 英寸是拿这一页的花括号位置反解出来的：
     * 6 列正好落在 3.09 / 3.91 / 4.73 / 5.55 / 6.37 / 7.19 英寸，
     * 而五组花括号分别在 3.92~4.16、5.81~6.06、7.06~7.19 三处。
     */
    private const val DEFAULT_TAB_EMU = 750_000f
    private const val DEFAULT_PT = 10.5f
    private const val SPAN_FLAG = 33 // SPAN_EXCLUSIVE_EXCLUSIVE

    private const val MAX_XML_BYTES = 24 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val MAX_IMAGES = 120

    // ======================= 中间表示 =======================

    private class Run(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val sizePt: Float,
        val color: Int
    )

    private sealed class Block {
        class Para(
            val runs: List<Run>,
            val align: Layout.Alignment,
            val spaceBefore: Float,
            val spaceAfter: Float,
            val indent: Float,
            val lineSpacing: Float
        ) : Block()

        class Pic(val ref: ImgRef) : Block()

        class Tbl(val rows: List<List<String>>) : Block()
    }

    internal class ImgRef(val zipPath: String, val wPx: Float, val hPx: Float) {
        var bmp: Bitmap? = null
    }

    // ======================= 对外入口 =======================

    fun renderDocx(file: File, sink: PageSink, w: Int, h: Int) {
        val xml = readEntry(file, "word/document.xml")
            ?: throw IllegalStateException("这不是有效的 docx 文件")
        val rels = readRels(file, "word/_rels/document.xml.rels")
        val blocks = parseDocx(xml, rels, w)
        if (blocks.isEmpty()) throw IllegalStateException("docx 中没有可渲染内容")
        loadDocxImages(file, blocks)
        try {
            _Renderer(sink, w, h).render(blocks)
        } finally {
            recycleImages(blocks)
        }
    }

    fun renderPptx(file: File, sink: PageSink, w: Int) {
        val presXml = readEntry(file, "ppt/presentation.xml")
            ?: throw IllegalStateException("这不是有效的 pptx 文件")
        val (slideCx, slideCy) = readSlideSize(presXml)
        val pageW = w.toFloat()
        val pxPerEmu = pageW / slideCx.coerceAtLeast(1f)
        val slideH = slideCy * pxPerEmu
        val pageH = slideH.toInt().coerceAtLeast(240)

        val slideNames = listSlideEntries(file)
        if (slideNames.isEmpty()) throw IllegalStateException("这不是有效的 pptx 文件")

        sink.hintTotal(slideNames.size)
        // 版式 / 母版 / 母版文字样式按路径缓存：127 页的课件通常只用到两三个版式。
        // 背景层（背景填充 + 母版装饰）也一并缓存，它每页都一样，重复解析纯属浪费。
        val geomCache = HashMap<String, PhGeom>()
        val styleCache = HashMap<String, TxStyles>()
        val backCache = HashMap<String, BackLayer>()
        var idx = 0
        try {
            for ((slideIdx, name) in slideNames.withIndex()) {
                if (idx >= 400) break
                val xml = readEntry(file, name) ?: continue
                val rels = readRels(file, "ppt/slides/_rels/${name.substringAfterLast('/')}.rels")

                val layoutPath = rels.values.firstOrNull { it.contains("slideLayouts/") }
                val layout = if (layoutPath != null) {
                    geomCache.getOrPut(layoutPath) { parsePhGeom(readEntry(file, layoutPath) ?: "") }
                } else PhGeom(emptyMap(), emptyMap())

                val masterPath = layoutPath?.let { lp ->
                    readRels(file, "ppt/slideLayouts/_rels/${lp.substringAfterLast('/')}.rels")
                        .values.firstOrNull { it.contains("slideMasters/") }
                } ?: "ppt/slideMasters/slideMaster1.xml"

                val master = geomCache.getOrPut(masterPath) {
                    parsePhGeom(readEntry(file, masterPath) ?: "")
                }
                val styles = styleCache.getOrPut(masterPath) {
                    parseTxStyles(readEntry(file, masterPath) ?: "")
                }

                val backKey = (layoutPath ?: "-") + "|" + masterPath
                val back = backCache.getOrPut(backKey) {
                    buildBackLayer(file, layoutPath, masterPath, pxPerEmu, pageW, slideH)
                }

                val theme = back.theme
                val ops = parseSlide(
                    xml, rels, layout, master, styles, pxPerEmu, pageW, slideH, theme
                )
                // 幻灯片自己的背景优先于版式/母版
                val bg = parseBgFill(xml, theme, rels) ?: back.bg
                val owned = ArrayList<Bitmap>()
                if (!back.loaded) {
                    back.owned = loadSlideImages(file, back.ops, back.bg)
                    back.loaded = true
                }
                owned.addAll(loadSlideImages(file, ops, bg))
                try {
                    sink.render(slideIdx) { drawSlide(ops, back.ops, bg, pageW.toInt(), pageH) }
                } finally {
                    // 装饰层与背景的位图要活到整本导完，只回收本页自己的
                    for (b in owned) if (!b.isRecycled) b.recycle()
                }
                idx++
            }
        } finally {
            for (layer in backCache.values) {
                for (b in layer.owned) if (!b.isRecycled) b.recycle()
                for (op in layer.ops) op.ref?.bmp = null
                layer.bg?.ref?.bmp = null
            }
        }
    }

    /** 版式 + 母版的背景层：背景填充、装饰形状（色带 / LOGO / 边框）与主题色 */
    private class BackLayer(
        val theme: PptxShape.Theme,
        val bg: PptxShape.Fill?,
        val ops: List<SlideOp>
    ) {
        var owned: List<Bitmap> = emptyList()

        /** 装饰层没有图片时也要记住「已经扫过 zip 了」，否则每页都会重扫一遍压缩包 */
        var loaded = false
    }

    private fun buildBackLayer(
        file: File,
        layoutPath: String?,
        masterPath: String,
        pxPerEmu: Float,
        pageW: Float,
        pageH: Float
    ): BackLayer {
        val masterXml = readEntry(file, masterPath) ?: ""
        val masterRels = readRels(
            file, "ppt/slideMasters/_rels/${masterPath.substringAfterLast('/')}.rels"
        )
        val themePath = masterRels.values.firstOrNull { it.contains("theme/") }
            ?: "ppt/theme/theme1.xml"
        val theme = PptxShape.Theme.parse(readEntry(file, themePath), PptxShape.parseClrMap(masterXml))
        val noStyles = TxStyles(28f, emptyList())
        val blank = PhGeom(emptyMap(), emptyMap())

        // 母版装饰：画在最底层。母版里的占位符只是「提示框」，不是要画的东西
        val masterOps = try {
            parseSlide(masterXml, masterRels, blank, blank, noStyles, pxPerEmu, pageW, pageH, theme, decor = true)
        } catch (_: Throwable) {
            emptyList()
        }

        val layoutXml = layoutPath?.let { readEntry(file, it) } ?: ""
        val layoutRels = layoutPath?.let {
            readRels(file, "ppt/slideLayouts/_rels/${it.substringAfterLast('/')}.rels")
        } ?: emptyMap()
        val layoutOps = try {
            parseSlide(layoutXml, layoutRels, blank, blank, noStyles, pxPerEmu, pageW, pageH, theme, decor = true)
        } catch (_: Throwable) {
            emptyList()
        }

        val bg = parseBgFill(layoutXml, theme, layoutRels) ?: parseBgFill(masterXml, theme, masterRels)
        // 版式里写 showMasterSp="0" 表示这一套版式不叠母版上的形状
        val showMaster = !layoutXml.contains("showMasterSp=\"0\"")
        return BackLayer(theme, bg, if (showMaster) layoutOps + masterOps else layoutOps)
    }

    // ======================= DOCX 解析 =======================

    private fun parseDocx(
        xml: String,
        rels: Map<String, String>,
        pageW: Int
    ): List<Block> {
        val blocks = ArrayList<Block>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")

        var para: ParaAcc? = null
        var run: RunAcc? = null
        var table: TblAcc? = null
        var cell: StringBuilder? = null
        var pendingPic: ImgRef? = null
        var extCx = 0f
        var extCy = 0f

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "p" -> if (table == null) para = ParaAcc()

                    // 段落属性是子元素，不是属性
                    "jc" -> para?.let {
                        it.align = when (attr(p, "val")) {
                            "center" -> Layout.Alignment.ALIGN_CENTER
                            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        }
                    }
                    "spacing" -> para?.let {
                        attr(p, "before")?.toFloatOrNull()?.let { v -> it.spaceBefore = twipsToPx(v) }
                        attr(p, "after")?.toFloatOrNull()?.let { v -> it.spaceAfter = twipsToPx(v) }
                        attr(p, "line")?.toFloatOrNull()?.let { v ->
                            if (v > 0) it.lineSpacing = (v / 240f).coerceIn(1f, 3f)
                        }
                    }
                    "ind" -> para?.let {
                        attr(p, "firstLine")?.toFloatOrNull()?.let { v -> it.indent = twipsToPx(v) }
                            ?: attr(p, "left")?.toFloatOrNull()?.let { v -> it.indent = twipsToPx(v) }
                    }

                    "r" -> run = RunAcc(para?.styleSize ?: DEFAULT_PT, para?.styleBold ?: false)
                    "pStyle" -> para?.let { acc -> applyStyle(attr(p, "val"), acc) }
                    "b" -> if (run != null) run!!.bold = flag(p) else para?.styleBold = flag(p)
                    "i" -> if (run != null) run!!.italic = flag(p)
                    "sz" -> attr(p, "val")?.toFloatOrNull()?.let { v ->
                        if (run != null) run!!.sizePt = v / 2f else para?.styleSize = (v / 2f)
                    }
                    "color" -> attr(p, "val")?.let { v ->
                        run?.let { r -> parseColor(v)?.let { r.color = it } }
                    }
                    "tab" -> if (table != null) cell?.append('\t') else para?.runs?.add(
                        Run("\t", false, false, DEFAULT_PT, 0xFF232323.toInt())
                    )
                    "br", "cr" -> if (table != null) cell?.append('\n')
                    "t" -> {
                        val s = p.nextText()
                        if (run != null) run!!.text.append(s) else cell?.append(s)
                    }

                    "tbl" -> {
                        para?.let { if (it.hasContent()) blocks.add(it.build()) }
                        para = null
                        table = TblAcc()
                    }
                    "tr" -> table?.newRow()
                    "tc" -> cell = StringBuilder()

                    "extent" -> {
                        extCx = attr(p, "cx")?.toFloatOrNull() ?: 0f
                        extCy = attr(p, "cy")?.toFloatOrNull() ?: 0f
                    }
                    "blip" -> {
                        val path = attr(p, "embed")?.let { rels[it] }
                        if (path != null) {
                            val maxW = pageW * 0.92f
                            var wx = extCx / EMU_PER_INCH * DPI
                            var wy = extCy / EMU_PER_INCH * DPI
                            if (wx <= 0f) wx = pageW * 0.6f
                            if (wy <= 0f) wy = wx * 0.66f
                            if (wx > maxW) {
                                wy = wy * (maxW / wx)
                                wx = maxW
                            }
                            pendingPic = ImgRef(path, wx, wy)
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (p.name) {
                    "r" -> {
                        run?.let { r ->
                            if (r.text.isNotEmpty()) {
                                if (table != null) cell?.append(r.text)
                                else para?.runs?.add(r.build())
                            }
                        }
                        run = null
                    }
                    "p" -> {
                        if (table == null) {
                            para?.let { if (it.hasContent()) blocks.add(it.build()) }
                            para = null
                        } else {
                            cell?.append('\n')
                        }
                    }
                    "tc" -> {
                        table?.addCell(cell?.toString()?.trim() ?: "")
                        cell = null
                    }
                    "tbl" -> {
                        table?.let { if (it.hasContent()) blocks.add(it.build()) }
                        table = null
                    }
                    "drawing", "pict" -> {
                        pendingPic?.let { ref ->
                            // 图片独占一个块，避免和同段文字错位
                            para?.let {
                                if (it.hasContent()) blocks.add(it.build())
                                it.clear()
                            }
                            blocks.add(Block.Pic(ref))
                        }
                        pendingPic = null
                        extCx = 0f
                        extCy = 0f
                    }
                }

                XmlPullParser.TEXT -> {
                    val t = p.text
                    if (!t.isNullOrEmpty()) {
                        if (run != null) run!!.text.append(t) else cell?.append(t)
                    }
                }
            }
            event = p.next()
        }
        return blocks
    }

    private class ParaAcc {
        val runs = ArrayList<Run>()
        var align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        var spaceBefore = 0f
        var spaceAfter = -1f
        var indent = 0f
        var lineSpacing = 1.3f

        /** 由段落样式 / 段落标记属性推导出的默认字号与粗体，供没有显式设置的 run 继承 */
        var styleSize = DEFAULT_PT
        var styleBold = false

        fun hasContent(): Boolean = runs.any { it.text.isNotBlank() }
        fun clear() {
            runs.clear()
            indent = 0f
        }

        fun build(): Block.Para = Block.Para(
            runs = ArrayList(runs),
            align = align,
            spaceBefore = spaceBefore,
            spaceAfter = if (spaceAfter < 0f) 8f else spaceAfter,
            indent = indent,
            lineSpacing = lineSpacing
        )
    }

    private class RunAcc(sizePt: Float = DEFAULT_PT, bold: Boolean = false) {
        val text = StringBuilder()
        var bold = bold
        var italic = false
        var sizePt = sizePt
        var color = 0xFF232323.toInt()

        fun build(): Run = Run(text.toString(), bold, italic, sizePt, color)
    }

    private class TblAcc {
        private val rows = ArrayList<ArrayList<String>>()
        private var cur: ArrayList<String>? = null

        fun newRow() {
            cur = ArrayList()
            rows.add(cur!!)
        }

        fun addCell(t: String) {
            cur?.add(t)
        }

        fun hasContent(): Boolean = rows.any { r -> r.any { it.isNotBlank() } }

        fun build(): Block.Tbl = Block.Tbl(rows.map { ArrayList(it) })
    }

    // ======================= PPTX 解析 =======================

    private fun readSlideSize(xml: String): Pair<Float, Float> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "sldSz") {
                val cx = attr(p, "cx")?.toFloatOrNull() ?: 12192000f
                val cy = attr(p, "cy")?.toFloatOrNull() ?: 6858000f
                return cx to cy
            }
            event = p.next()
        }
        return 12192000f to 6858000f
    }

    /** 按 slideN 的数字顺序返回，避免 slide10 排在 slide2 前面 */
    private fun listSlideEntries(file: File): List<String> {
        val found = ArrayList<Pair<Int, String>>()
        val re = Regex("^ppt/slides/slide(\\d+)\\.xml$")
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val m = re.find(e.name)
                if (m != null) found.add((m.groupValues[1].toIntOrNull() ?: 0) to e.name)
                e = zip.nextEntry
            }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    // ======================= 版式继承（占位符几何 / 字号 / 项目符号） =======================

    /**
     * 幻灯片里的标题与正文常常**不带** `<a:off>/<a:ext>`：位置与大小是从版式
     * （slideLayout）乃至母版（slideMaster）里的同名占位符继承来的。
     *
     * 不解析这一层，这些形状就只能落到同一个兜底坐标上 —— 这是「文字叠在一起、
     * 标题和正文糊成一团」的根因。真实课件（C++ 第 4 章）里 127 页幻灯片几乎
     * 全都依赖继承，所以这一步不是优化而是必需。
     */
    /**
     * 一份「颜色怎么写」的描述。版式和母版解析时主题还没读出来，
     * 所以这里只记原始值（`accent2` 或 `FFFFFF`）与修饰量，真正换算留到绘制时用主题做。
     */
    private class ColorSpec(
        val spec: String,
        val scheme: Boolean,
        val lumMod: Float = 1f,
        val lumOff: Float = 0f,
        val alpha: Int = 255
    )

    /**
     * 一级段落样式。
     *
     * 除 [sizePt] / [marLEmu] 用 0 表示「这一级没写、要回落到母版」之外，
     * 其余 null / false 都表示「没写」。
     * 艺术字就是 [color] 配 [lnEmu] / [lnColor]：白字 + 深色描边，
     * 以前只取字号，所以标题被画成纯黑宋体。
     */
    private class LvlStyle(
        val sizePt: Float,
        val marLEmu: Float,
        val bullet: String?,
        val bold: Boolean = false,
        val color: ColorSpec? = null,
        /** 文字描边宽度（EMU），0 表示没有描边 */
        val lnEmu: Float = 0f,
        val lnColor: ColorSpec? = null
    )

    private class PhBox(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        /** 版式 / 母版里这个占位符自带的 lstStyle；null 表示没写 */
        val lvls: List<LvlStyle>? = null
    )

    private class PhGeom(val byType: Map<String, PhBox>, val byIdx: Map<Int, PhBox>) {
        fun lookup(type: String?, idx: Int): PhBox? {
            if (type != null) {
                byType[type]?.let { return it }
                // 标题占位符在不同模板里写作 title / ctrTitle，正文写作 body / obj
                when (type) {
                    "title" -> byType["ctrTitle"]?.let { return it }
                    "ctrTitle" -> byType["title"]?.let { return it }
                    "body", "obj" -> byType["subTitle"]?.let { return it }
                }
            }
            if (idx >= 0) byIdx[idx]?.let { return it }
            return null
        }

        /** 只要样式（版式的 lstStyle 优先于母版样式表） */
        fun levels(type: String?, idx: Int): List<LvlStyle>? = lookup(type, idx)?.lvls
    }

    private class TxStyles(val titleSz: Float, val body: List<LvlStyle>) {
        fun at(lvl: Int): LvlStyle =
            body.getOrNull(lvl) ?: body.lastOrNull() ?: LvlStyle(18f, 0f, null)
    }

    private val TX_BODY = Regex("<p:bodyStyle[^>]*>([\\s\\S]*?)</p:bodyStyle>")
    private val TX_TITLE = Regex("<p:titleStyle[^>]*>([\\s\\S]*?)</p:titleStyle>")
    private val TX_LVL = Regex("<a:lvl(\\d)pPr([^>]*)>([\\s\\S]*?)</a:lvl\\1pPr>")

    /** 解析母版里的标题字号与正文各级样式（字号 / 缩进 / 项目符号） */
    private fun parseTxStyles(xml: String): TxStyles {
        val titleSz = TX_TITLE.find(xml)?.groupValues?.get(1)
            ?.let { Regex("sz=\"(\\d+)\"").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
            ?.let { it / 100f } ?: 32f

        val body = ArrayList<LvlStyle>()
        val bodyXml = TX_BODY.find(xml)?.groupValues?.get(1) ?: ""
        for (m in TX_LVL.findAll(bodyXml)) {
            val lvl = m.groupValues[1].toIntOrNull() ?: continue
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            val sz = Regex("defRPr[^>]*sz=\"(\\d+)\"").find(inner)
                ?.groupValues?.get(1)?.toFloatOrNull()?.let { it / 100f } ?: 18f
            val marL = Regex("marL=\"(\\d+)\"").find(attrs)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val bu = Regex("buChar char=\"([^\"]*)\"").find(inner)?.groupValues?.get(1)
            val bold = Regex("defRPr[^>]*\\bb=\"(1|true)\"").containsMatchIn(inner)
            while (body.size < lvl - 1) body.add(LvlStyle(18f, 0f, null))
            body.add(LvlStyle(sz, marL, bu, bold))
        }
        return TxStyles(titleSz, body)
    }

    private val SP_BLOCK = Regex("<p:sp>([\\s\\S]*?)</p:sp>")
    private val PH_TAG = Regex("<p:ph([^/>]*)/?>")
    private val XFRM_OFF = Regex("<a:off x=\"(-?\\d+)\" y=\"(-?\\d+)\"/>")
    private val XFRM_EXT = Regex("<a:ext cx=\"(-?\\d+)\" cy=\"(-?\\d+)\"/>")
    private val LST_BLOCK = Regex("<a:lstStyle>([\\s\\S]*?)</a:lstStyle>")
    private val LVL_BLOCK = Regex("<a:lvl([1-9])pPr([^>]*)>([\\s\\S]*?)</a:lvl\\1pPr>")
    private val CLR_TAG = Regex(
        "<a:(srgbClr|schemeClr|sysClr|prstClr)([^>]*?)(/>|>([\\s\\S]*?)</a:\\1>)"
    )

    /** 从一段包含颜色标签的 XML 里读出颜色描述（含 lumMod / lumOff / alpha 修饰） */
    private fun parseColorSpec(block: String): ColorSpec? {
        val m = CLR_TAG.find(block) ?: return null
        val kind = m.groupValues[1]
        val attrs = m.groupValues[2]
        val inner = m.groupValues[4]
        val spec = Regex("val=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: return null
        fun num(tag: String, dflt: Float): Float =
            Regex("<a:$tag val=\"(\\d+)\"").find(inner)
                ?.groupValues?.get(1)?.toFloatOrNull()?.div(100000f) ?: dflt
        val lumMod = num("lumMod", 1f)
        val lumOff = num("lumOff", 0f)
        val alpha = Regex("<a:alpha val=\"(\\d+)\"").find(inner)
            ?.groupValues?.get(1)?.toIntOrNull()?.div(1000) ?: 255
        // shade/tint 是「把颜色压暗/提亮」，用亮度修饰近似即可（课件里只出现在描边上）
        val shade = num("shade", 1f)
        val lm = if (shade < 1f) lumMod * shade else lumMod
        return ColorSpec(spec, kind == "schemeClr", lm, lumOff, alpha)
    }

    /**
     * 读取一份 slideLayout / slideMaster 里所有占位符的几何与 `<a:lstStyle>`。
     *
     * lstStyle 这一层以前整个被忽略：版式里明明写着「正文 24pt」，
     * 我们却套母版 bodyStyle 的 28pt，整段行距被撑大、文字压到线上；
     * 版式里写着「白字 + 深色描边」的艺术字标题，被画成纯黑宋体。
     */
    private fun parsePhGeom(xml: String): PhGeom {
        if (xml.isBlank()) return PhGeom(emptyMap(), emptyMap())
        val byType = HashMap<String, PhBox>()
        val byIdx = HashMap<Int, PhBox>()
        for (block in SP_BLOCK.findAll(xml)) {
            val b = block.groupValues[1]
            val ph = PH_TAG.find(b) ?: continue
            val phAttrs = ph.groupValues[1]
            val type = Regex("type=\"([^\"]*)\"").find(phAttrs)
                ?.groupValues?.get(1) ?: "body"
            val idx = Regex("idx=\"(\\d+)\"").find(phAttrs)
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
            // 只认 <a:xfrm> 里的几何，别被 a:ext 扩展容器再骗一次（见 1.4.2）
            val geom = b.substringBefore("<a:extLst>")
            val off = XFRM_OFF.find(geom) ?: continue
            val ext = XFRM_EXT.find(geom) ?: continue
            val bx = off.groupValues[1].toFloatOrNull() ?: continue
            val by = off.groupValues[2].toFloatOrNull() ?: continue
            val cx = ext.groupValues[1].toFloatOrNull() ?: continue
            val cy = ext.groupValues[2].toFloatOrNull() ?: continue
            if (cx <= 0f || cy <= 0f) continue
            val box = PhBox(bx, by, cx, cy, parseLvls(LST_BLOCK.find(b)?.groupValues?.get(1)))
            byType.putIfAbsent(type, box)
            if (idx >= 0) byIdx.putIfAbsent(idx, box)
        }
        return PhGeom(byType, byIdx)
    }

    /** 解析 `<a:lstStyle>` 里 lvl1pPr..lvl9pPr 的段落样式 */
    private fun parseLvls(lst: String?): List<LvlStyle>? {
        if (lst.isNullOrBlank()) return null
        val out = ArrayList<LvlStyle>()
        for (m in LVL_BLOCK.findAll(lst)) {
            val lvl = m.groupValues[1].toIntOrNull() ?: continue
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            // defRPr 里既可能有 <a:ln>（艺术字描边）又有文字填充色。
            // 先在**整块** defRPr 上取属性，再把 <a:ln> 整段摘出去，
            // 免得先按 <a:ln 截断而把 defRPr 的闭合标签也切掉（那样 sz/b 全读不到）。
            val defM = Regex("<a:defRPr([^>]*?)/>|<a:defRPr([^>]*)>([\\s\\S]*?)</a:defRPr>")
                .find(inner)
            val dAttrs = (defM?.groupValues?.get(1) ?: "") + (defM?.groupValues?.get(2) ?: "")
            var dInner = defM?.groupValues?.get(3) ?: ""
            val lnBlock = Regex("<a:ln([^>]*)>([\\s\\S]*?)</a:ln>").find(dInner)
            val lnAttrs = lnBlock?.groupValues?.get(1) ?: ""
            val lnInner = lnBlock?.groupValues?.get(2) ?: ""
            if (lnBlock != null) dInner = dInner.removeRange(lnBlock.range)
            val sz = Regex("sz=\"(\\d+)\"").find(dAttrs)
                ?.groupValues?.get(1)?.toFloatOrNull()?.div(100f) ?: 0f
            val bold = Regex("b=\"(1|true)\"").containsMatchIn(dAttrs)
            val marL = Regex("marL=\"(\\d+)\"").find(attrs)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val bu = Regex("<a:buChar char=\"([^\"]*)\"").find(inner)?.groupValues?.get(1)
            val lnW = Regex("w=\"(\\d+)\"").find(lnAttrs)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val color = parseColorSpec(
                Regex("<a:solidFill>([\\s\\S]*?)</a:solidFill>").find(dInner)
                    ?.groupValues?.get(1) ?: ""
            )
            val lnColor = if (lnInner.isNotEmpty()) parseColorSpec(lnInner) else null
            while (out.size < lvl - 1) out.add(LvlStyle(0f, 0f, null))
            out.add(LvlStyle(sz, marL, bu, bold, color, lnW, lnColor))
        }
        return out.ifEmpty { null }
    }

    /** 纯缩放 + 平移的仿射（Office 里的分组不需要处理旋转） */
    private class Aff(val a: Float, val d: Float, val e: Float, val f: Float) {
        fun x(v: Float) = a * v + e
        fun y(v: Float) = d * v + f

        /** this ∘ inner：先 inner 再 this */
        fun compose(inner: Aff) = Aff(a * inner.a, d * inner.d, a * inner.e + e, d * inner.f + f)
    }

    private val IDENT = Aff(1f, 1f, 0f, 0f)

    internal class SlideOp(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val text: CharSequence?,
        val align: Layout.Alignment,
        val ref: ImgRef?,
        val table: TableData?,
        /** 0 顶对齐 / 1 垂直居中 / 2 底对齐（来自 bodyPr anchor） */
        val anchor: Int,
        /** bodyPr wrap="none"：单行不折行 */
        val noWrap: Boolean,
        /** 整页预览图：直接用这张图当整页，跳过其它形状，避免文字重影 */
        val bgOnly: Boolean = false,
        /**
         * 几何来自幻灯片/版式（不是兜底坐标）。只有在这种情况下才允许按框高
         * 自动缩字号 —— 兜底框的尺寸是猜的，缩了反而失真。
         */
        val fit: Boolean = true,
        /** 行距倍数，已经折算过 bodyPr 的 lnSpcReduction */
        val lineMul: Float = 1f,
        /** 形状轮廓（单位方形 0..1），null 表示只是一段文字/图片 */
        val shape: Path? = null,
        val fill: PptxShape.Fill? = null,
        val line: PptxShape.Line? = null,
        /** 旋转角度（度）与镜像，来自 a:xfrm */
        val rot: Float = 0f,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
        /** 图片裁剪（左/上/右/下，0..1），来自 a:srcRect */
        val crop: RectF? = null,
        /** 文字描边宽度（px）与颜色；艺术字标题就是「白字 + 深色描边」 */
        val textLineW: Float = 0f,
        val textLineColor: Int = 0,
        /** 形状名称，用于排查 */
        val name: String = "",
        /**
         * 一个制表位的宽度（px）与第一个制表位相对段落左边界的偏移（px）。
         *
         * PPTX 没有「列」的概念，树状图/目录这类排版全靠作者敲 `\t` 跳到下一个
         * 制表位。制表位是按 lIns 起算、等距铺开的，而段落本身又被 marL 缩进了一段，
         * 所以「布局坐标里的第 k 个制表位」= k*tabPx - marL，直接把 -marL 放在这里。
         */
        val tabPx: Float = 0f,
        val tabOff: Float = 0f
    )

    /** 表格单元格。文字带上 span（字号/颜色/粗体），跨列由 [span] 表示 */
    internal class TCell(
        val text: CharSequence,
        val span: Int,
        /** 被合并吃掉的后续单元格：既不画边框也不画字 */
        val merged: Boolean,
        val align: Layout.Alignment,
        val anchor: Int,
        val padL: Float,
        val padR: Float,
        val padT: Float,
        val padB: Float
    )

    internal class TRow(val height: Float, val cells: List<TCell>)

    internal class TableData(val colW: FloatArray, val rows: List<TRow>) {
        /** 缺 gridCol 时按最多单元格数均分，避免整张表消失 */
        fun cols(): FloatArray {
            if (colW.isNotEmpty()) return colW
            val n = rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 0
            return FloatArray(n.coerceAtLeast(1)) { 1f }
        }
    }

    /** 一页 PPT 的绘制指令 + 它占用的位图，放大重渲染时可复用 */
    internal class SlideArt(
        val ops: List<SlideOp>,
        val pageW: Int,
        val pageH: Int,
        /** 幻灯片背景（纯色 / 渐变 / 图片），来自 slide → layout → master 逐级继承 */
        val bg: PptxShape.Fill? = null,
        /** 母版与版式里的装饰形状（色带、LOGO、边框），画在幻灯片内容之下 */
        val back: List<SlideOp> = emptyList(),
        private val owned: List<Bitmap>
    ) {
        fun release() {
            for (op in ops) op.ref?.bmp = null
            for (op in back) op.ref?.bmp = null
            bg?.ref?.bmp = null
            for (b in owned) if (!b.isRecycled) b.recycle()
        }

        /** 这一页涉及的所有图片引用（含背景与装饰），用于一起加载/释放 */
        fun allOps(): List<SlideOp> = back + ops
    }

    /**
     * 判定「整页预览图」的线索。很多导出工具会把整张幻灯片先渲染成一张图塞回 pptx，
     * 同时又保留各个文本框；这种文件名/描述通常是 "Image preview" 或 "bg_cover.png"。
     * 命中就只画这张图，避免文字重影。
     */
    private val PREVIEW_LABEL =
        Regex("preview|预览|render|snapshot|\\.(png|jpg|jpeg|webp|bmp)", RegexOption.IGNORE_CASE)

    private fun parseSlide(
        xml: String,
        rels: Map<String, String>,
        layout: PhGeom,
        master: PhGeom,
        styles: TxStyles,
        pxPerEmu: Float,
        pageW: Float,
        pageH: Float,
        theme: PptxShape.Theme = PptxShape.Theme.EMPTY,
        /**
         * 只当背景层解析（母版 / 版式）：占位符不画，没有自身坐标的形状也跳过 ——
         * 否则一页会多出一堆母版里的空框。
         */
        decor: Boolean = false
    ): List<SlideOp> {
        val ops = ArrayList<SlideOp>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")

        val pxPerPt = EMU_PER_INCH * pxPerEmu / 72f
        val inch = EMU_PER_INCH * pxPerEmu
        val sb = SpannableStringBuilder()
        // 正文默认色跟主题走：深色模板的 tx1 是白色，写死黑色会变成黑底黑字
        val defaultText = theme.resolve("tx1") ?: 0xFF1B1B1B.toInt()

        var inSp = false
        var inFrame = false
        var isPic = false
        var x = -1f
        var y = -1f
        var cw = -1f
        var ch = -1f
        var phType: String? = null
        var phIdx = -1
        var anchor = 0
        var noWrap = false
        /** <a:normAutofit fontScale> —— PowerPoint 自动缩小字号的比例，不处理会整段溢出 */
        var fontScale = 1f
        /** <a:normAutofit lnSpcReduction> —— 同时收窄行距 */
        var lnRed = 0f
        var align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        var picRef: ImgRef? = null
        var picLabel = ""
        var cropRect: RectF? = null
        var srcL = 0f
        var srcT = 0f
        var srcR = 0f
        var srcB = 0f

        // ---- 形状外观（spPr）----
        var inSpPr = false
        var inLn = false
        var inEffect = false
        var inGrad = false
        var prstName: String? = null
        val prstAdj = HashMap<String, Float>()
        var shapePath: Path? = null
        var cust: PptxShape.CustPath? = null
        var cmdTag = ""
        val cmdPts = ArrayList<Float>()
        var fillKind = 0 // 0 无 / 1 纯色 / 2 渐变 / 3 图片
        var fillColor = 0
        var fillRef: ImgRef? = null
        val gradStops = ArrayList<Int>()
        val gradPos = ArrayList<Float>()
        var gradAng = 0f
        var gsPos = 0f
        var lineColor = 0
        var lineWidth = 0f
        var lineDash = false
        var lineHead = 0
        var lineTail = 0
        var lineOn = false
        var rot = 0f
        var flipH = false
        var flipV = false
        var shapeName = ""

        // ---- 颜色累加：一次只会有一个颜色元素在解析 ----
        var colBase = 0
        var colTarget = 0 // 0 无 / 1 填充 / 2 描边 / 3 正文 / 4 表格 / 5 渐变停点 / 6 样式引用
        var colLumMod = 1f
        var colLumOff = 0f
        var colAlpha = 255

        // ---- p:style（主题填充/描边/字色引用）----
        var inStyle = false
        var refTarget = 0 // 1 fillRef / 2 lnRef / 3 fontRef
        var refColor = 0
        var refIdx = 0
        var styleFill: PptxShape.Fill? = null
        var styleLine: PptxShape.Line? = null
        var styleFontColor = 0
        var fillExplicit = false
        var lnExplicit = false

        fun resetShape() {
            inSpPr = false
            inLn = false
            inEffect = false
            inGrad = false
            prstName = null
            prstAdj.clear()
            shapePath = null
            cust = null
            cmdTag = ""
            cmdPts.clear()
            fillKind = 0
            fillColor = 0
            fillRef = null
            gradStops.clear()
            gradPos.clear()
            gradAng = 0f
            gsPos = 0f
            lineColor = 0
            lineWidth = 0f
            lineDash = false
            lineHead = 0
            lineTail = 0
            lineOn = false
            rot = 0f
            flipH = false
            flipV = false
            shapeName = ""
            cropRect = null
            srcL = 0f; srcT = 0f; srcR = 0f; srcB = 0f
            colBase = 0
            colTarget = 0
            inStyle = false
            refTarget = 0
            refColor = 0
            refIdx = 0
            styleFill = null
            styleLine = null
            styleFontColor = 0
            fillExplicit = false
            lnExplicit = false
        }

        // 段落 / 文字片段状态
        var paraStart = 0
        var paraSzPt = styles.at(0).sizePt
        var paraMarL = 0f
        var paraBold = false
        var paraColor = 0
        var paraLnW = 0f
        var paraLnColor = 0
        var runStart = -1
        var runSzPt = 18f
        var runBold = false
        var runItalic = false
        var runColor = defaultText
        var inRPr = false
        var inDefRPr = false
        // 文字描边（艺术字）：<a:ln> 在 defRPr / rPr 里，不在 spPr 里
        var inTextLn = false
        var textLnW = 0f
        var textLnColor = 0
        /** 当前占位符在版式 / 母版里查到的 lstStyle */
        var curLvls: List<LvlStyle>? = null

        fun lvAt(lvl: Int): LvlStyle {
            val src = curLvls
            if (!src.isNullOrEmpty()) return src.getOrNull(lvl) ?: src.last()
            return styles.at(lvl)
        }

        fun lvColor(cs: ColorSpec?): Int {
            if (cs == null) return 0
            val base = if (cs.scheme) (theme.resolve(cs.spec) ?: 0)
            else (PptxShape.parseIntColor(cs.spec) ?: 0)
            if (base == 0) return 0
            return PptxShape.modColor(base, cs.lumMod, cs.lumOff, cs.alpha)
        }

        /**
         * 形状自己的 `<p:txBody><a:lstStyle>` 里各级字号（pt，0 表示这一级没写）。
         *
         * 普通文本框（`<p:sp>` 但没有 `<p:ph>`）不继承母版正文样式，字号全写在这一层。
         * 以前一律按 18pt 兜底，第 2 章第 13 页那张树状图就因为字号小了 1/4，
         * 靠 `\t` 撑出来的缩进跟着缩水，看起来还是塌在左边。
         */
        val ownSz = FloatArray(9)

        /** 把一级样式应用到「当前段落」的默认值上；没写的字段回落到母版 */
        fun applyLvl(lv: LvlStyle, plain: Boolean, lvl: Int = 0, isTitle: Boolean = false) {
            if (plain) {
                // 普通形状不继承母版：先看自己 lstStyle 写了什么，再退回 18pt。
                // 缩进/项目符号仍按 PowerPoint 的默认（无）。
paraSzPt = if (ownSz[lvl] > 0f) ownSz[lvl] else 18f
                paraMarL = 0f
                paraBold = false
                paraColor = 0
                paraLnW = 0f
                paraLnColor = 0
                return
            }
            val base = styles.at(lvl)
            paraSzPt = when {
                lv.sizePt > 0f -> lv.sizePt
                isTitle -> styles.titleSz
                else -> base.sizePt
            }
            paraMarL = when {
                lv.marLEmu > 0f -> lv.marLEmu * pxPerEmu
                else -> base.marLEmu * pxPerEmu
            }
            paraBold = lv.bold
            paraColor = lvColor(lv.color)
            paraLnW = lv.lnEmu * pxPerEmu
            paraLnColor = lvColor(lv.lnColor)
        }
        // 项目符号要等 pPr 里的 buChar / buNone 读完才知道，先记下待插入的位置
        var pendBullet: String? = null
        var pendBulletAt = 0
        var bulletDone = true

        // 表格状态
        var gridCols: MutableList<Float>? = null
        var tblRows: MutableList<TRow>? = null
        var rowCells: MutableList<TCell>? = null
        var rowH = 0f
        var cellSb: SpannableStringBuilder? = null
        var cellRunStart = -1
        var cellSzPt = 18f
        var cellBold = false
        var cellColor = defaultText
        var cellAlign: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        var cellAnchor = 0
        var cellSpan = 1
        var cellMerged = false
        var cellMarL = 0.1f * inch
        var cellMarR = 0.1f * inch
        var cellMarT = 0.05f * inch
        var cellMarB = 0.05f * inch
        var pendingTable: TableData? = null

        // 分组变换
        var grp = IDENT
        val grpStack = ArrayList<Aff>()
        var inGrpSpPr = false
        var gOx = 0f; var gOy = 0f; var gCx = 0f; var gCy = 0f
        var gChOx = 0f; var gChOy = 0f; var gChCx = 0f; var gChCy = 0f

        var fallbackY = 0f

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "sp", "pic", "cxnSp" -> {
                        inSp = true
                        isPic = p.name == "pic"
                        x = -1f; y = -1f; cw = -1f; ch = -1f
                        phType = null
                        phIdx = -1
                        anchor = 0
                        noWrap = false
                        fontScale = 1f
                        lnRed = 0f
                        align = Layout.Alignment.ALIGN_NORMAL
                        picRef = null
                        picLabel = ""
                        inRPr = false
                        pendBullet = null
                        bulletDone = true
                        resetShape()
                        sb.clear()
                        paraStart = 0
                        paraMarL = 0f
                        paraSzPt = styles.at(0).sizePt
                        runStart = -1
                        curLvls = null
                        java.util.Arrays.fill(ownSz, 0f)
                        paraColor = 0
                        paraLnW = 0f
                        paraLnColor = 0
                        textLnW = 0f
                        textLnColor = 0
                        inTextLn = false
                        inDefRPr = false
                    }
                    "graphicFrame" -> {
                        inFrame = true
                        x = -1f; y = -1f; cw = -1f; ch = -1f
                    }
                    "ph" -> if (inSp) {
                        phType = attr(p, "type") ?: "body"
                        phIdx = attr(p, "idx")?.toIntOrNull() ?: -1
                        // 版式里的 lstStyle 优先于母版样式表。字号、粗细、文字色、
                        // 艺术字描边都写在这一层，不读它就会「正文大一号、标题变纯黑」
                        curLvls = layout.levels(phType, phIdx) ?: master.levels(phType, phIdx)
                        if (phType == "title" || phType == "ctrTitle") {
                            if (curLvls != null) applyLvl(lvAt(0), false, 0, isTitle = true)
                            else paraSzPt = styles.titleSz
                        }
                    }
                    "bodyPr" -> if (cellSb != null) {
                        cellAnchor = anchorOf(attr(p, "anchor"))
                    } else {
                        anchor = anchorOf(attr(p, "anchor"))
                        noWrap = attr(p, "wrap") == "none"
                        // bodyPr 的子元素 normAutofit 会紧跟其后出现
                        fontScale = 1f
                        lnRed = 0f
                    }
                    // 形状自己的段落样式表，紧跟在 bodyPr 后面、第一个 <a:p> 之前
                    "lstStyle" -> if (inSp && !inSpPr && cellSb == null) parseOwnLst(p, ownSz)
                    // PowerPoint 把「文字太多自动缩小」的结果写在 normAutofit 里，
                    // 不读这个比例，整段文字就会比实际大 30%~50%，直接溢出页面。
                    "normAutofit" -> {
                        fontScale = ((attr(p, "fontScale")?.toFloatOrNull() ?: 100000f) / 100000f)
                            .coerceIn(0.2f, 1f)
                        lnRed = ((attr(p, "lnSpcReduction")?.toFloatOrNull() ?: 0f) / 100000f)
                            .coerceIn(0f, 0.45f)
                    }
                    // ---------- p:style：形状没写填充/描边时，从主题样式表取 ----------
                    "style" -> {
                        inStyle = true
                        styleFill = null
                        styleLine = null
                    }
                    "fillRef" -> if (inStyle) {
                        refTarget = 1
                        refColor = 0
                        refIdx = attr(p, "idx")?.toIntOrNull() ?: 0
                    }
                    "lnRef" -> if (inStyle) {
                        refTarget = 2
                        refColor = 0
                        refIdx = attr(p, "idx")?.toIntOrNull() ?: 0
                    }
                    "fontRef" -> if (inStyle) {
                        refTarget = 3
                        refColor = 0
                    }

                    // ---------- 形状外观：位置/几何/填充/描边 ----------
                    "spPr" -> {
                        inSpPr = true
                        inLn = false
                        inEffect = false
                    }
                    "xfrm" -> if (inSp && !inGrpSpPr) {
                        attr(p, "rot")?.toFloatOrNull()?.let { rot = it / 60000f }
                        attr(p, "flipH")?.let { flipH = it == "1" || it == "true" }
                        attr(p, "flipV")?.let { flipV = it == "1" || it == "true" }
                    }
                    "prstGeom" -> if (inSpPr) {
                        prstName = attr(p, "prst")
                        prstAdj.clear()
                    }
                    // 预设几何的调整值（圆角半径、箭头胖瘦…）：<a:gd name="adj" fmla="val 16667"/>
                    "gd" -> if (inSpPr && prstName != null) {
                        val nm = attr(p, "name")
                        val v = attr(p, "fmla")?.substringAfter(' ', "")?.trim()?.toFloatOrNull()
                        if (nm != null && v != null) prstAdj[nm] = v
                    }
                    "path" -> if (inSpPr) {
                        cust = PptxShape.CustPath(
                            attr(p, "w")?.toFloatOrNull() ?: 0f,
                            attr(p, "h")?.toFloatOrNull() ?: 0f
                        )
                    }
                    "moveTo", "lnTo", "cubicBezTo", "quadBezTo" -> if (cust != null) {
                        cmdTag = p.name
                        cmdPts.clear()
                    }
                    "pt" -> if (cust != null && cmdTag.isNotEmpty()) {
                        cmdPts.add(attr(p, "x")?.toFloatOrNull() ?: 0f)
                        cmdPts.add(attr(p, "y")?.toFloatOrNull() ?: 0f)
                    }
                    "close" -> cust?.close()

                    "ln" -> when {
                        inSpPr -> {
                            inLn = true
                            lineWidth = ((attr(p, "w")?.toFloatOrNull() ?: 12700f) * pxPerEmu)
                                .coerceIn(0f, 80f)
                        }
                        // rPr / defRPr 里的 <a:ln> 是**文字描边**（艺术字），
                        // 不是形状边框，不能碰 lineWidth
                        inRPr -> {
                            inTextLn = true
                            textLnW = ((attr(p, "w")?.toFloatOrNull() ?: 12700f) * pxPerEmu)
                                .coerceIn(0f, 60f)
                        }
                    }
                    // <a:extLst> 是「扩展容器」，里面 a14:hiddenFill / a14:hiddenLine 存的是
                    // 一组「不渲染、只留给新版程序记住原样式」的颜色副本。它们的子标签
                    // 也叫 a:solidFill / a:srgbClr，会被当成真实填充读走 —— 第39页三个
                    // 白底文本框（写了 <a:noFill/>）就是这样被填成黑色的。
                    // 整块跳过，不影响其它任何状态。
                    "extLst" -> skipSubtree(p)
                    "noFill" -> if (inLn) {
                        lineOn = false
                        lnExplicit = true
                    } else if (inSpPr) {
                        fillKind = 0
                        fillExplicit = true
                    }
                    "solidFill" -> if (inLn) {
                        lineOn = true
                        lnExplicit = true
                    } else if (inSpPr) {
                        fillExplicit = true
                    }
                    "gradFill" -> if (inSpPr) {
                        if (inLn) {
                            lineOn = true
                        } else {
                            gradStops.clear()
                            gradPos.clear()
                            gradAng = 0f
                            inGrad = true
                            fillKind = 2
                            fillExplicit = true
                        }
                    }
                    "gs" -> if (inGrad) {
                        gsPos = (attr(p, "pos")?.toFloatOrNull() ?: 0f) / 100000f
                    }
                    "lin" -> if (inGrad) {
                        gradAng = (attr(p, "ang")?.toFloatOrNull() ?: 0f) / 60000f
                    }
                    "blipFill" -> if (inSpPr && !inLn) {
                        fillKind = 3
                        fillExplicit = true
                    }
                    "prstDash" -> if (inLn) lineDash = (attr(p, "val") ?: "solid") != "solid"
                    "headEnd" -> if (inLn) lineHead = arrowOf(attr(p, "type"))
                    "tailEnd" -> if (inLn) lineTail = arrowOf(attr(p, "type"))
                    "effectLst", "effectDag" -> inEffect = true
                    "srcRect" -> {
                        srcL = (attr(p, "l")?.toFloatOrNull() ?: 0f) / 100000f
                        srcT = (attr(p, "t")?.toFloatOrNull() ?: 0f) / 100000f
                        srcR = (attr(p, "r")?.toFloatOrNull() ?: 0f) / 100000f
                        srcB = (attr(p, "b")?.toFloatOrNull() ?: 0f) / 100000f
                        if (srcL > 0f || srcT > 0f || srcR > 0f || srcB > 0f) {
                            cropRect = RectF(srcL, srcT, srcR, srcB)
                        }
                    }

                    "grpSp" -> grpStack.add(grp)
                    "grpSpPr" -> {
                        inGrpSpPr = true
                        gOx = 0f; gOy = 0f; gCx = 0f; gCy = 0f
                        gChOx = 0f; gChOy = 0f; gChCx = 0f; gChCy = 0f
                    }
                    // 只认带坐标属性的 off/ext。<a:ext uri="{...}"> 是扩展容器
                    // （a14:hiddenFill / hiddenLine 之类），没有 cx/cy，却被写成
                    // 「spPr 里排在 xfrm 后面」—— 无脑当几何会把真正的 cx/cy 覆盖成
                    // -1，图片和形状于是掉进「没有坐标」的兜底排版，全部堆到左上角。
                    "off" -> if (inGrpSpPr) {
                        attr(p, "x")?.toFloatOrNull()?.let { gOx = it }
                        attr(p, "y")?.toFloatOrNull()?.let { gOy = it }
                    } else if (inSp || inFrame) {
                        attr(p, "x")?.toFloatOrNull()?.let { x = it * pxPerEmu }
                        attr(p, "y")?.toFloatOrNull()?.let { y = it * pxPerEmu }
                    }
                    "ext" -> if (inGrpSpPr) {
                        attr(p, "cx")?.toFloatOrNull()?.let { gCx = it }
                        attr(p, "cy")?.toFloatOrNull()?.let { gCy = it }
                    } else if (inSp || inFrame) {
                        attr(p, "cx")?.toFloatOrNull()?.let { cw = it * pxPerEmu }
                        attr(p, "cy")?.toFloatOrNull()?.let { ch = it * pxPerEmu }
                    }
                    "chOff" -> {
                        gChOx = attr(p, "x")?.toFloatOrNull() ?: 0f
                        gChOy = attr(p, "y")?.toFloatOrNull() ?: 0f
                    }
                    "chExt" -> {
                        gChCx = attr(p, "cx")?.toFloatOrNull() ?: 0f
                        gChCy = attr(p, "cy")?.toFloatOrNull() ?: 0f
                    }
                    "cNvPr" -> {
                        picLabel = ((attr(p, "name") ?: "") + " " + (attr(p, "descr") ?: "")).trim()
                        shapeName = attr(p, "name") ?: ""
                    }
                    // 段落没写 <a:pPr> 时（课件的第一段常常就没有）也得按第一级样式
                    // 初始化：否则字号、缩进、项目符号全部沿用上一段，一级圆点会整段消失。
                    "p" -> if (inSp && cellSb == null) {
                        val lv0 = lvAt(0)
                        // 普通形状（不是占位符）在 PowerPoint 里不继承母版正文样式：
                        // 默认字号就是 18pt、无缩进、无项目符号。以前一律套 bodyStyle
                        // 的一级样式（这份课件是 28pt），窄框里的说明文字被撑大一倍，
                        // 直接溢到框外面去。
                        val plain = phType == null
                        applyLvl(lv0, plain, 0, phType == "title" || phType == "ctrTitle")
                        align = Layout.Alignment.ALIGN_NORMAL
                        pendBullet = if (phType != null && phType !in NO_BULLET_PH) lv0.bullet else null
                        pendBulletAt = sb.length
                        bulletDone = pendBullet.isNullOrEmpty()
                        paraStart = sb.length
                    }
                    "pPr" -> {
                        val al = when (attr(p, "algn")) {
                            "ctr" -> Layout.Alignment.ALIGN_CENTER
                            "r" -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        }
                        if (cellSb != null) {
                            // 只取对齐；内边距是 tcPr 的事，pPr 的 marL 是单元格内部的缩进，
                            // 拿它当内边距会让表格文字贴死在边框上
                            cellAlign = al
                        } else if (inSp) {
                            val lvl = attr(p, "lvl")?.toIntOrNull() ?: 0
                            val lv = lvAt(lvl)
                            val plain = phType == null
                            applyLvl(lv, plain, lvl, phType == "title" || phType == "ctrTitle")
                            attr(p, "marL")?.toFloatOrNull()?.let { paraMarL = it * pxPerEmu }
                            align = al
                            // 项目符号：等 buChar / buNone 读完再插字面量。
                            // 只有占位符才继承母版正文的符号 —— PowerPoint 里普通文本框/形状
                            // 默认是没有项目符号的，一律套 lvl1 的圆点会在「8B」「4B」这种
                            // 图注色块上凭空多出一个「·」。
                            pendBullet = if (phType != null && phType !in NO_BULLET_PH) lv.bullet else null
                            pendBulletAt = sb.length
                            bulletDone = pendBullet.isNullOrEmpty()
                            paraStart = sb.length
                        }
                    }
                    "buNone" -> pendBullet = null
                    "buChar" -> {
                        pendBullet = attr(p, "char")?.takeIf { it.isNotEmpty() }
                        bulletDone = pendBullet == null
                    }
                    "r" -> if (cellSb != null) {
                        cellRunStart = cellSb!!.length
                        cellBold = false
                        cellColor = defaultText
                    } else {
                        val bl = pendBullet
                        if (bl != null && !bulletDone) {
                            sb.insert(pendBulletAt, "$bl ")
                            bulletDone = true
                        }
                        runStart = sb.length
                        runSzPt = paraSzPt
                        runBold = paraBold
                        runItalic = false
                        // 颜色优先级：run 自己写的 > p:style 的 fontRef > 段落 lstStyle
                        runColor = when {
                            styleFontColor != 0 -> styleFontColor
                            paraColor != 0 -> paraColor
                            else -> 0xFF1B1B1B.toInt()
                        }
                        textLnW = paraLnW
                        textLnColor = paraLnColor
                    }
                    "rPr", "endParaRPr" -> {
                        inRPr = true
                        val sz = attr(p, "sz")?.toFloatOrNull()?.let { it / 100f }
                        val bold = attr(p, "b")?.let { it == "1" || it == "true" }
                        if (cellSb != null) {
                            if (sz != null) cellSzPt = sz
                            if (bold != null) cellBold = bold
                        } else {
                            if (sz != null) runSzPt = sz
                            if (bold != null) runBold = bold
                            attr(p, "i")?.let { runItalic = it == "1" || it == "true" }
                        }
                    }
                    // pPr 里的 defRPr 是整段的默认字号/粗细，run 没有显式 sz 时继承它。
                    // 很多课件把正文字号写在这里，忽略的话整段会按母版的 32pt 排，直接溢出。
                    "defRPr" -> {
                        inRPr = true
                        inDefRPr = true
                        val sz = attr(p, "sz")?.toFloatOrNull()?.let { it / 100f }
                        val bold = attr(p, "b")?.let { it == "1" || it == "true" }
                        if (cellSb != null) {
                            if (sz != null) cellSzPt = sz
                            if (bold != null) cellBold = bold
                        } else if (inSp && runStart < 0) {
                            if (sz != null) paraSzPt = sz
                            if (bold != null) paraBold = bold
                        } else if (inSp) {
                            if (sz != null) runSzPt = sz
                            if (bold != null) runBold = bold
                        }
                    }
                    // 颜色可能出现在四处：形状填充、描边、正文 run、表格单元格。
                    // 值本身在属性里，亮度/透明度修饰在**子元素**里，所以要等颜色元素
                    // 结束才提交。用 colTarget 记住这次该给谁。
                    "srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr" -> {
                        colTarget = when {
                            inStyle && refTarget != 0 -> 6
                            inTextLn -> 7
                            inRPr -> if (cellSb != null) 4 else if (inDefRPr) 8 else 3
                            inEffect -> 0
                            inLn -> 2
                            inGrad -> 5
                            inSpPr -> 1
                            else -> 0
                        }
                        colBase = when (p.name) {
                            "srgbClr" -> attr(p, "val")?.let { PptxShape.parseIntColor(it) }
                            "schemeClr" -> theme.resolve(attr(p, "val"))
                            "sysClr" -> attr(p, "lastClr")?.let { PptxShape.parseIntColor(it) }
                            "prstClr" -> presetColor(attr(p, "val"))
                            else -> null
                        } ?: 0
                        colLumMod = 1f
                        colLumOff = 0f
                        colAlpha = 255
                    }
                    "lumMod" -> if (colBase != 0) {
                        colLumMod = (attr(p, "val")?.toFloatOrNull() ?: 100000f) / 100000f
                    }
                    "lumOff" -> if (colBase != 0) {
                        colLumOff = (attr(p, "val")?.toFloatOrNull() ?: 0f) / 100000f
                    }
                    "shade" -> if (colBase != 0) {
                        colLumMod *= (attr(p, "val")?.toFloatOrNull() ?: 100000f) / 100000f
                    }
                    "tint" -> if (colBase != 0) {
                        colLumOff += (1f - (attr(p, "val")?.toFloatOrNull() ?: 100000f) / 100000f) * 0.55f
                    }
                    "alpha" -> if (colBase != 0) {
                        colAlpha = ((attr(p, "val")?.toFloatOrNull() ?: 100000f) / 100000f * 255f)
                            .toInt().coerceIn(0, 255)
                    }
                    "t" -> {
                        val s = p.nextText()
                        val cs = cellSb
                        if (cs != null) cs.append(s) else sb.append(s)
                    }
                    "blip" -> {
                        val path = attr(p, "embed")?.let { rels[it] }
                        if (path != null) {
                            if (isPic) picRef = ImgRef(path, 0f, 0f)
                            else fillRef = ImgRef(path, 0f, 0f)
                        }
                    }
                    "tbl" -> {
                        gridCols = ArrayList()
                        tblRows = ArrayList()
                    }
                    "gridCol" -> gridCols?.add(
                        (attr(p, "w")?.toFloatOrNull() ?: 0f) * pxPerEmu
                    )
                    "tr" -> if (tblRows != null) {
                        rowCells = ArrayList()
                        rowH = (attr(p, "h")?.toFloatOrNull() ?: 0f) * pxPerEmu
                    }
                    "tc" -> {
                        cellSb = SpannableStringBuilder()
                        cellSpan = (attr(p, "gridSpan")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                        cellMerged = attr(p, "hMerge") == "1" || attr(p, "vMerge") == "1"
                        cellAlign = Layout.Alignment.ALIGN_NORMAL
                        cellAnchor = 0
                        cellSzPt = 18f
                        cellBold = false
                        cellColor = defaultText
                        cellMarL = 0.1f * inch
                        cellMarR = 0.1f * inch
                        cellMarT = 0.05f * inch
                        cellMarB = 0.05f * inch
                        cellRunStart = -1
                    }
                    "tcPr" -> {
                        attr(p, "marL")?.toFloatOrNull()?.let { cellMarL = it * pxPerEmu }
                        attr(p, "marR")?.toFloatOrNull()?.let { cellMarR = it * pxPerEmu }
                        attr(p, "marT")?.toFloatOrNull()?.let { cellMarT = it * pxPerEmu }
                        attr(p, "marB")?.toFloatOrNull()?.let { cellMarB = it * pxPerEmu }
                        attr(p, "anchor")?.let { cellAnchor = anchorOf(it) }
                    }
                }

                XmlPullParser.END_TAG -> when (p.name) {
                    "grpSpPr" -> {
                        inGrpSpPr = false
                        if (gCx > 0f && gCy > 0f && gChCx > 0f && gChCy > 0f) {
                            val sx = gCx / gChCx
                            val sy = gCy / gChCy
                            grp = grp.compose(
                                Aff(
                                    sx, sy,
                                    (gOx - gChOx * sx) * pxPerEmu,
                                    (gOy - gChOy * sy) * pxPerEmu
                                )
                            )
                        }
                    }
                    "grpSp" -> {
                        if (grpStack.isNotEmpty()) grp = grpStack.removeAt(grpStack.size - 1)
                    }

                    "spPr" -> {
                        inSpPr = false
                        inLn = false
                        inEffect = false
                        inGrad = false
                    }
                    "defRPr" -> inDefRPr = false
                    "ln" -> if (inTextLn) {
                        inTextLn = false
                    } else {
                        inLn = false
                        // 文字描边的 </a:ln> 不能算成「这个形状有描边」
                        if (inSpPr) lnExplicit = true
                    }
                    "fillRef" -> {
                        if (refTarget == 1 && refIdx > 0) {
                            val fs = theme.fillStyles.getOrNull(refIdx - 1)
                            styleFill = fs?.make(if (refColor != 0) refColor else 0xFF4472C4.toInt())
                        }
                        refTarget = 0
                    }
                    "lnRef" -> {
                        if (refTarget == 2 && refIdx > 0) {
                            val ls = theme.lnStyles.getOrNull(refIdx - 1)
                            if (ls != null && ls.widthEmu > 0f) {
                                val c = if (refColor != 0) refColor else 0
                                styleLine = PptxShape.Line(
                                    c, ls.widthEmu * pxPerEmu, ls.dashed, 0, 0
                                )
                            }
                        }
                        refTarget = 0
                    }
                    "fontRef" -> {
                        if (refColor != 0) styleFontColor = refColor
                        refTarget = 0
                    }
                    "style" -> inStyle = false
                    "prstGeom" -> {
                        val out = Path()
                        // 传 w/h 进去：括号类的圆角半径要按实际尺寸算
                        if (PptxShape.buildPreset(prstName, prstAdj, out, cw, ch)) shapePath = out
                    }
                    "path" -> {
                        shapePath = cust?.path
                        cust = null
                    }
                    "moveTo", "lnTo", "cubicBezTo", "quadBezTo" -> {
                        val cp = cust
                        if (cp != null) {
                            when (cmdTag) {
                                "moveTo" -> if (cmdPts.size >= 2) cp.moveTo(cmdPts[0], cmdPts[1])
                                "lnTo" -> if (cmdPts.size >= 2) cp.lineTo(cmdPts[0], cmdPts[1])
                                "quadBezTo" -> if (cmdPts.size >= 4) {
                                    cp.quad(cmdPts[0], cmdPts[1], cmdPts[2], cmdPts[3])
                                }
                                "cubicBezTo" -> if (cmdPts.size >= 6) {
                                    cp.cubic(
                                        cmdPts[0], cmdPts[1], cmdPts[2], cmdPts[3],
                                        cmdPts[4], cmdPts[5]
                                    )
                                }
                            }
                            cmdTag = ""
                        }
                    }
                    "effectLst", "effectDag" -> inEffect = false
                    "gradFill" -> inGrad = false
                    "srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr" -> {
                        if (colBase != 0 && colTarget != 0) {
                            val c = PptxShape.modColor(colBase, colLumMod, colLumOff, colAlpha)
                            when (colTarget) {
                                1 -> {
                                    if (fillKind != 3) fillKind = 1
                                    fillColor = c
                                }
                                2 -> {
                                    lineColor = c
                                    lineOn = true
                                }
                                3 -> runColor = c
                                4 -> cellColor = c
                                5 -> {
                                    gradStops.add(c)
                                    gradPos.add(gsPos)
                                }
                                6 -> refColor = c
                                7 -> textLnColor = c
                                8 -> paraColor = c
                            }
                        }
                        colBase = 0
                        colTarget = 0
                    }

                    "rPr", "defRPr", "endParaRPr" -> inRPr = false
                    "r" -> {
                        val cs = cellSb
                        if (cs != null) {
                            if (cellRunStart in 0 until cs.length) {
                                val end = cs.length
                                cs.setSpan(
                                    AbsoluteSizeSpan(
                                        (cellSzPt * pxPerPt).toInt().coerceAtLeast(6), false
                                    ),
                                    cellRunStart, end, SPAN_FLAG
                                )
                                if (cellBold) {
                                    cs.setSpan(StyleSpan(Typeface.BOLD), cellRunStart, end, SPAN_FLAG)
                                }
                                cs.setSpan(
                                    ForegroundColorSpan(cellColor), cellRunStart, end, SPAN_FLAG
                                )
                            }
                            cellRunStart = -1
                        } else if (runStart in 0 until sb.length) {
                            val end = sb.length
                            // fontScale 就是 PowerPoint 自动缩小后的比例
                            val px = (runSzPt * fontScale * pxPerPt).toInt().coerceAtLeast(6)
                            sb.setSpan(AbsoluteSizeSpan(px, false), runStart, end, SPAN_FLAG)
                            if (runBold) sb.setSpan(StyleSpan(Typeface.BOLD), runStart, end, SPAN_FLAG)
                            if (runItalic) sb.setSpan(StyleSpan(Typeface.ITALIC), runStart, end, SPAN_FLAG)
                            sb.setSpan(ForegroundColorSpan(runColor), runStart, end, SPAN_FLAG)
                        }
                        runStart = -1
                    }
                    "p" -> {
                        val cs = cellSb
                        if (cs != null) {
                            if (cs.isNotEmpty()) cs.append('\n')
                        } else if (inSp) {
                            if (sb.length > paraStart) {
                                if (paraMarL > 0.5f) {
                                    sb.setSpan(
                                        LeadingMarginSpan.Standard(paraMarL.toInt(), paraMarL.toInt()),
                                        paraStart, sb.length, SPAN_FLAG
                                    )
                                }
                            } else {
                                // 空段落也必须占一行。PowerPoint 里「例如」和后面正文之间
                                // 常夹着几个空段（课件的表格就是靠这些空行让位的），
                                // 以前整段丢掉，后面的正文就上移压在表格/图片上。
                                // 零宽字符只能加在**空段落**上：加在有字的段尾会把整行行高
                                // 撑到 paraSzPt，行距凭空大一半。
                                sb.append('\u200B')
                                sb.setSpan(
                                    AbsoluteSizeSpan(
                                        (paraSzPt * fontScale * pxPerPt).toInt().coerceAtLeast(6),
                                        false
                                    ),
                                    sb.length - 1, sb.length, SPAN_FLAG
                                )
                            }
                            sb.append('\n')
                            paraStart = sb.length
                        }
                    }
                    "tc" -> {
                        val cs = cellSb
                        if (cs != null) {
                            var e = cs.length
                            while (e > 0 && cs[e - 1] == '\n') e--
                            val txt: CharSequence =
                                if (e == cs.length) cs else SpannableStringBuilder(cs, 0, e)
                            rowCells?.add(
                                TCell(
                                    txt, cellSpan, cellMerged, cellAlign, cellAnchor,
                                    cellMarL, cellMarR, cellMarT, cellMarB
                                )
                            )
                        }
                        cellSb = null
                    }
                    "tr" -> {
                        val rc = rowCells
                        if (rc != null) tblRows?.add(TRow(rowH, rc))
                        rowCells = null
                    }
                    "tbl" -> {
                        val rows = tblRows
                        if (rows != null && rows.isNotEmpty()) {
                            pendingTable = TableData(gridCols?.toFloatArray() ?: FloatArray(0), rows)
                        }
                        gridCols = null
                        tblRows = null
                    }
                    "sp", "pic", "cxnSp" -> {
                        if (inSp) {
                            // 背景层（母版/版式）只画装饰：占位符是提示框，没有自身坐标的也跳过
                            val skip = decor && (phType != null || x < 0f || cw <= 0f)
                            // 自身几何 → 版式 → 母版；三者都没有才用兜底坐标
                            val lr = if (skip || x < 0f || cw <= 0f) {
                                if (decor) null else layout.lookup(phType, phIdx) ?: master.lookup(phType, phIdx)
                            } else null

                            var fx: Float
                            var fy: Float
                            var fw: Float
                            var fh: Float
                            var fromFile = true
                            if (skip) {
                                fx = 0f; fy = 0f; fw = 0f; fh = 0f
                            } else if (lr != null) {
                                fx = grp.x(lr.x * pxPerEmu)
                                fy = grp.y(lr.y * pxPerEmu)
                                fw = lr.w * pxPerEmu * grp.a
                                fh = lr.h * pxPerEmu * grp.d
                            } else if (x >= 0f && cw > 0f) {
                                fx = grp.x(x)
                                fy = grp.y(y)
                                fw = cw * grp.a
                                fh = ch * grp.d
                            } else {
                                // 连版式都没有：按顺序往下排，绝不重叠在同一个点上
                                fw = pageW * 0.84f
                                fh = pageH * 0.16f
                                fx = pageW * 0.08f
                                fy = if (fallbackY <= 0f) pageH * 0.08f else fallbackY
                                fallbackY = fy + fh + 8f
                                fromFile = false
                            }

                            // 空段落会留下零宽占位符，不能据此认为「这个框有文字」
                            val hasText = !skip && sb.isNotEmpty() &&
                                sb.toString().replace("\u200B", "").isNotBlank() &&
                                !isPromptText(sb)
                            // 图片形状：几何只用来定位，不当作填色路径重画一遍
                            val geo = if (isPic) null else shapePath
                            val effFillPresent = if (fillExplicit) fillKind != 0 else
                                (fillKind != 0 || styleFill != null)
                            val ownLine = if (lineWidth > 0f && lineOn) {
                                PptxShape.Line(lineColor, lineWidth, lineDash, lineHead, lineTail)
                            } else null
                            // 只写了 w / 箭头、没写颜色的 <a:ln>，颜色仍然来自 lnRef
                            val lnEff = ownLine ?: styleLine?.let {
                                PptxShape.Line(
                                    it.color,
                                    if (lineWidth > 0f) lineWidth else it.width,
                                    lineDash, lineHead, lineTail
                                )
                            }
                            val hasFill = !isPic && geo != null && effFillPresent
                            val hasLine = geo != null && lnEff != null
                            val hasShape = hasFill || hasLine
                            if (!skip && (picRef != null || hasText || hasShape)) {
                                val full = fw >= pageW * 0.9f && fh >= pageH * 0.9f
                                val bgOnly = picRef != null && full &&
                                    PREVIEW_LABEL.containsMatchIn(picLabel)
                                val textOut: CharSequence? = if (hasText) {
                                    // 去掉末尾多余的换行与空行占位符，避免末尾多出一整行高度
                                    var e = sb.length
                                    while (e > 0 && (sb[e - 1] == '\n' || sb[e - 1] == '\u200B')) e--
                                    SpannableStringBuilder(sb, 0, e)
                                } else null
                                val ownFill = when {
                                    fillKind == 1 -> PptxShape.Fill.solid(fillColor)
                                    fillKind == 2 -> PptxShape.Fill(
                                        2,
                                        stops = gradStops.toIntArray(),
                                        stopPos = gradPos.toFloatArray(),
                                        angle = gradAng
                                    )
                                    fillKind == 3 -> PptxShape.Fill(3, ref = fillRef, crop = cropRect)
                                    else -> null
                                }
                                // 自己没写填充/描边时，用 p:style 从主题样式表里取的那一套
                                val fill = if (fillExplicit) ownFill else (styleFill ?: ownFill)
                                val ln = lnEff
                                ops.add(
                                    SlideOp(
                                        fx, fy, fw, fh, textOut,
                                        align, picRef, null, anchor, noWrap, bgOnly,
                                        fit = fromFile,
                                        lineMul = (1f * (1f - lnRed)).coerceIn(0.75f, 1.5f),
                                        shape = geo,
                                        fill = fill,
                                        line = ln,
                                        rot = rot,
                                        flipH = flipH,
                                        flipV = flipV,
                                        crop = cropRect,
                                        textLineW = if (textLnW > 0f) textLnW else 0f,
                                        textLineColor = textLnColor,
                                        name = shapeName,
                                        tabPx = DEFAULT_TAB_EMU * pxPerEmu,
                                        tabOff = -paraMarL
                                    )
                                )
                            }
                        }
                        inSp = false
                    }
                    "graphicFrame" -> {
                        // 表格的坐标一直没被读取过（off/ext 只对 sp/pic 生效），
                        // 结果所有表格都堆在左上角、压住标题文字。
                        val t = pendingTable
                        if (t != null) {
                            val cols = t.cols()
                            var tw = 0f
                            for (v in cols) tw += v
                            val gx = if (x >= 0f) grp.x(x) else pageW * 0.06f
                            val gy = if (y >= 0f) grp.y(y) else 20f
                            val gw = when {
                                cw > 0f -> cw * grp.a
                                tw > 0f -> tw
                                else -> pageW * 0.88f
                            }
                            ops.add(
                                SlideOp(
                                    gx, gy, gw,
                                    if (ch > 0f) ch * grp.d else 0f,
                                    null, align, null, t, 0, false,
                                    fit = x >= 0f
                                )
                            )
                        }
                        pendingTable = null
                        inFrame = false
                        x = -1f; y = -1f; cw = -1f; ch = -1f
                    }
                }
            }
            event = p.next()
        }
        return ops
    }

    /**
     * 从当前 START_TAG 直接跳到它自己的 END_TAG，中间所有节点都不解析。
     *
     * 离开时解析器停在 END_TAG 上，调用方接着 `p.next()` 就是下一个节点。
     */
    /**
     * 读形状自己的 `<p:txBody><a:lstStyle>`，只取各级 `defRPr` 的 `sz`。
     *
     * 普通文本框（没有 `<p:ph>`）不继承母版正文样式，字号/颜色/项目符号全写在这一层。
     * 以前整块没读，一律按 18pt 兜底 —— 第 2 章第 13 页的树状图正是被这个坑住的：
     * 原文 24pt 被画成 18pt，靠 `\t` 撑出来的缩进跟着缩水 1/4，看上去还是塌在左边。
     *
     * 调用时游标停在 START_TAG `lstStyle`，返回时停在 END_TAG `lstStyle`
     * （和 `skipSubtree` 一样，外层循环再 next() 即可继续）。
     */
    private fun parseOwnLst(p: XmlPullParser, out: FloatArray) {
        // 注意：XmlPullParser 在 END_TAG 时 getDepth() 已经是「父级深度」，
        // 不能拿它跟 START 时的 depth 比。用和 skipSubtree 一样的计数法才稳。
        var depth = 0
        var lvl = -1
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val nm = p.name
                    if (nm.length == 7 && nm.startsWith("lvl") && nm.endsWith("pPr")) {
                        lvl = (nm[3] - '0') - 1
                    } else if (nm == "defRPr" && lvl in 0..8) {
                        val sz = attr(p, "sz")?.toFloatOrNull()
                        if (sz != null && sz > 0f) out[lvl] = sz / 100f
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (depth == 0) return
                    val nm = p.name
                    if (nm.length == 7 && nm.startsWith("lvl") && nm.endsWith("pPr")) lvl = -1
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> return
                else -> {}
            }
        }
    }

    private fun skipSubtree(p: XmlPullParser) {
        var depth = 0
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> {
                    if (depth == 0) return
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> return
                else -> {}
            }
        }
    }

    private fun loadSlideImages(file: File, ops: List<SlideOp>, bg: PptxShape.Fill? = null): List<Bitmap> {
        val names = HashSet<String>()
        for (op in ops) {
            op.ref?.let { names.add(it.zipPath) }
            op.fill?.ref?.let { names.add(it.zipPath) }
        }
        bg?.ref?.let { names.add(it.zipPath) }
        if (names.isEmpty()) return emptyList()

        val byPath = HashMap<String, Bitmap>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name in names) {
                    readBytesCapped(zip, MAX_IMAGE_BYTES)?.let { bytes ->
                        decodeCapped(bytes, 1600)?.let { byPath[e.name] = it }
                    }
                }
                e = zip.nextEntry
            }
        }
        val owned = ArrayList<Bitmap>()
        val seen = IdentityHashMap<Bitmap, Boolean>()
        fun bind(ref: ImgRef?) {
            if (ref == null) return
            val b = byPath[ref.zipPath] ?: return
            ref.bmp = b
            if (seen.put(b, true) == null) owned.add(b)
        }
        for (op in ops) {
            bind(op.ref)
            bind(op.fill?.ref)
        }
        bind(bg?.ref)
        return owned
    }

    /**
     * 排版引擎版本号。
     *
     * PPTX/DOCX 的页面底图是导入时一次性栅格化的，之后升级 App 并不会重画 ——
     * 于是「明明修好了，看到的还是旧样子」。把这个版本号写进 meta.json，
     * 打开笔记本时发现版本落后就用源文件重画一遍底图（笔迹不受影响）。
     */
    const val RENDER_VERSION = 11

    /** 这些占位符在 PowerPoint 里不继承项目符号（标题、副标题、页脚、页码、日期） */
    private val NO_BULLET_PH =
        setOf("title", "ctrTitle", "subTitle", "ftr", "sldNum", "dt")

    /** 用当前引擎把一整页重画成位图，供旧底图升级使用 */
    internal fun renderPage(file: File, slideIndex: Int, pageW: Int): Bitmap? {
        val art = loadSlideArt(file, slideIndex, pageW) ?: return null
        try {
            val bmp = Bitmap.createBitmap(
                pageW, art.pageH.coerceAtLeast(120), Bitmap.Config.ARGB_8888
            )
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            drawSlideContent(c, art.ops, art.pageW, art.pageH, art.bg, art.back)
            return bmp
        } catch (t: Throwable) {
            return null
        } finally {
            art.release()
        }
    }

    /**
     * 解析单张幻灯片，供放大时的按需高清渲染复用。
     * 返回的 [SlideArt] 持有解码后的图片位图，用完必须 [SlideArt.release]。
     */
    internal fun loadSlideArt(file: File, slideIndex: Int, pageW: Int): SlideArt? {
        val presXml = readEntry(file, "ppt/presentation.xml") ?: return null
        val (slideCx, slideCy) = readSlideSize(presXml)
        val pxPerEmu = pageW / slideCx.coerceAtLeast(1f)
        val slideH = slideCy * pxPerEmu

        val name = listSlideEntries(file).getOrNull(slideIndex) ?: return null
        val xml = readEntry(file, name) ?: return null
        val rels = readRels(file, "ppt/slides/_rels/${name.substringAfterLast('/')}.rels")

        val layoutPath = rels.values.firstOrNull { it.contains("slideLayouts/") }
        val layout = if (layoutPath != null) {
            parsePhGeom(readEntry(file, layoutPath) ?: "")
        } else PhGeom(emptyMap(), emptyMap())
        val masterPath = layoutPath?.let { lp ->
            readRels(file, "ppt/slideLayouts/_rels/${lp.substringAfterLast('/')}.rels")
                .values.firstOrNull { it.contains("slideMasters/") }
        } ?: "ppt/slideMasters/slideMaster1.xml"
        val master = parsePhGeom(readEntry(file, masterPath) ?: "")
        val styles = parseTxStyles(readEntry(file, masterPath) ?: "")

        val back = buildBackLayer(file, layoutPath, masterPath, pxPerEmu, pageW.toFloat(), slideH)
        val theme = back.theme
        val ops = parseSlide(xml, rels, layout, master, styles, pxPerEmu, pageW.toFloat(), slideH, theme)
        val bg = parseBgFill(xml, theme, rels) ?: back.bg
        val owned = ArrayList<Bitmap>()
        owned.addAll(loadSlideImages(file, back.ops, back.bg))
        owned.addAll(loadSlideImages(file, ops, bg))
        return SlideArt(
            ops,
            pageW,
            slideH.toInt().coerceAtLeast(240),
            bg,
            back.ops,
            owned
        )
    }

    private fun drawSlide(
        ops: List<SlideOp>,
        back: List<SlideOp>,
        bg: PptxShape.Fill?,
        w: Int,
        h: Int
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        // 有整页预览图时直接用图，其余形状全部跳过 —— 否则文字会和图上的字重影
        val bgOp = ops.firstOrNull { it.bgOnly && it.ref?.bmp != null }
        if (bgOp != null) {
            val b = bgOp.ref!!.bmp!!
            c.drawBitmap(b, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
            return bmp
        }
        drawSlideContent(c, ops, w, h, bg, back)
        return bmp
    }

    /**
     * 在**页面坐标系**里画一页幻灯片。
     * 调用方负责 transform —— 导入时是恒等变换，放大高清渲染时是 "把 rect 放大 band 倍"，
     * 因为这里的文字和形状都是矢量绘制的，放大后依然锐利。
     *
     * 绘制顺序即 pptx 的层叠顺序：背景 → 母版/版式装饰 → 幻灯片形状。
     */
    internal fun drawSlideContent(
        c: Canvas,
        ops: List<SlideOp>,
        w: Int,
        h: Int,
        bg: PptxShape.Fill? = null,
        back: List<SlideOp> = emptyList()
    ) {
        c.save()
        c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
        val tp = TextPaint().apply { isAntiAlias = true }

        drawBg(c, bg, w, h)
        for (op in back) drawOp(c, op, tp)
        for (op in ops) drawOp(c, op, tp)

        c.restore()
    }

    private fun drawBg(c: Canvas, bg: PptxShape.Fill?, w: Int, h: Int) {
        if (bg == null) return
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        if (bg.kind == 3) {
            val bmp = bg.ref?.bmp ?: return
            c.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            return
        }
        val paint = bg.paint(rect) ?: return
        c.drawRect(rect, paint)
    }

    /** 画一个形状：填充 → 描边 → 图片 → 文字。旋转与镜像对整个形状（含文字）生效 */
    private fun drawOp(c: Canvas, op: SlideOp, tp: TextPaint) {
        val table = op.table
        if (table != null) {
            drawTable(c, table, op.x, op.y, op.w, tp)
            return
        }
        if (op.w <= 0f || op.h <= 0f) {
            // 只有文字/表格的形状允许尺寸为 0 的兜底，但形状本身没意义
            if (op.shape != null && op.text == null) return
        }
        val rotated = op.rot != 0f || op.flipH || op.flipV
        if (rotated) {
            c.save()
            val cx = op.x + op.w * 0.5f
            val cy = op.y + op.h * 0.5f
            if (op.rot != 0f) c.rotate(op.rot, cx, cy)
            if (op.flipH || op.flipV) {
                c.scale(if (op.flipH) -1f else 1f, if (op.flipV) -1f else 1f, cx, cy)
            }
        }
        drawShape(c, op)
        drawPicture(c, op)
        drawText(c, op, tp)
        if (rotated) c.restore()
    }

    private fun drawShape(c: Canvas, op: SlideOp) {
        val unit = op.shape ?: return
        val fill = op.fill
        val line = op.line
        if (fill == null && line == null) return

        // 横线 / 竖线：PowerPoint 里这类连接线的高度（或宽度）会被写成 0，
        // 按单位路径缩放会退化成空图形，这里直接画一条线段。
        if ((op.w <= 0.6f || op.h <= 0.6f) && fill == null && line != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = line.width
                color = line.color
                strokeCap = Paint.Cap.ROUND
                if (line.dashed) {
                    pathEffect = DashPathEffect(
                        floatArrayOf(line.width * 3f, line.width * 2.2f), 0f
                    )
                }
            }
            c.drawLine(op.x, op.y, op.x + op.w, op.y + op.h, paint)
            return
        }
        if (op.w <= 0.01f || op.h <= 0.01f) return

        val path = Path()
        path.set(unit)
        val m = Matrix()
        m.setScale(op.w, op.h)
        m.postTranslate(op.x, op.y)
        path.transform(m)
        val bounds = RectF(op.x, op.y, op.x + op.w, op.y + op.h)

        if (fill != null) {
            if (fill.kind == 3) {
                val bmp = fill.ref?.bmp
                if (bmp != null) {
                    c.save()
                    c.clipPath(path)
                    c.drawBitmap(bmp, null, bounds, Paint(Paint.FILTER_BITMAP_FLAG))
                    c.restore()
                }
            } else {
                fill.paint(bounds)?.let { c.drawPath(path, it) }
            }
        }

        if (line != null && line.width > 0f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = line.width
                color = line.color
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (line.dashed) {
                    pathEffect = DashPathEffect(
                        floatArrayOf(line.width * 3f, line.width * 2.2f), 0f
                    )
                }
            }
            c.drawPath(path, paint)
            if (line.head != 0 || line.tail != 0) drawArrowEnds(c, path, line)
        }
    }

    /** 连接线/箭头：按路径两端切线方向补三角形，这样弯折线也能画对方向 */
    private fun drawArrowEnds(c: Canvas, path: Path, line: PptxShape.Line) {
        try {
            val pm = PathMeasure(path, false)
            val len = pm.length
            if (len < 2f || len.isNaN()) return
            val size = (line.width * 3.2f).coerceIn(5f, 40f)
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            if (line.tail != 0) {
                if (pm.getPosTan(len, pos, tan)) arrowHead(c, pos[0], pos[1], tan[0], tan[1], size, line)
            }
            if (line.head != 0) {
                if (pm.getPosTan(0f, pos, tan)) arrowHead(c, pos[0], pos[1], -tan[0], -tan[1], size, line)
            }
        } catch (_: Throwable) {
        }
    }

    private fun arrowHead(
        c: Canvas,
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        size: Float,
        line: PptxShape.Line
    ) {
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1e-4f) return
        val ux = dx / len
        val uy = dy / len
        val px = -uy
        val py = ux
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = line.color
        }
        if (line.tail == 3 || line.head == 3) {
            // 圆点箭头
            c.drawCircle(x - ux * size * 0.3f, y - uy * size * 0.3f, size * 0.28f, paint)
            return
        }
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x - ux * size + px * size * 0.42f, y - uy * size + py * size * 0.42f)
        path.lineTo(x - ux * size - px * size * 0.42f, y - uy * size - py * size * 0.42f)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawPicture(c: Canvas, op: SlideOp) {
        val ref = op.ref ?: return
        val bmp = ref.bmp ?: return
        val dw = if (ref.wPx > 0f) ref.wPx else op.w
        val dh = if (ref.hPx > 0f) ref.hPx else op.h
        if (dw <= 0f || dh <= 0f) return
        val dst = RectF(op.x, op.y, op.x + dw, op.y + dh)
        val crop = op.crop
        if (crop != null && (crop.left > 0f || crop.top > 0f || crop.right > 0f || crop.bottom > 0f)) {
            val src = Rect(
                (bmp.width * crop.left).toInt().coerceIn(0, bmp.width - 1),
                (bmp.height * crop.top).toInt().coerceIn(0, bmp.height - 1),
                (bmp.width * (1f - crop.right)).toInt().coerceIn(1, bmp.width),
                (bmp.height * (1f - crop.bottom)).toInt().coerceIn(1, bmp.height)
            )
            if (src.width() > 1 && src.height() > 1) {
                c.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                return
            }
        }
        c.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * PowerPoint / WPS 的空占位符会带一句「提示语」，正常情况下只在编辑态显示。
     * 但有些版本另存后会把提示语**当真文字写进 slideN.xml**，导入后就变成页面上
     * 一行莫名其妙的「双击此处添加文本」。这里按整句精确匹配，不会误伤正文。
     */
    private fun isPromptText(text: CharSequence): Boolean {
        val t = text.toString().replace(" ", "").replace("\u00A0", "").trim()
        if (t.isEmpty() || t.length > 24) return false
        for (p in PROMPT_TEXTS) if (t == p) return true
        return false
    }

    private val PROMPT_TEXTS = arrayOf(
        "单击此处添加文本", "双击此处添加文本", "单击此处添加标题", "双击此处添加标题",
        "单击此处添加副标题", "双击此处添加副标题", "单击此处添加正文",
        "单击此处编辑母版标题样式", "单击此处编辑母版文本样式",
        "Clicktoaddtext", "Clicktoaddtitle", "Clicktoaddsubtitle"
    )

    private fun drawText(c: Canvas, op: SlideOp, tp: TextPaint) {
        val txt = op.text
        if (txt == null || txt.isEmpty()) return

        val pad = 8f
        tp.textSize = 16f
        tp.color = 0xFF1B1B1B.toInt()
        val avail0 = if (op.noWrap) {
            // wrap="none" 的形状本来就按文字宽度设了 ext，留 30% 余量避免折行
            max(24, (op.w * 1.3f).toInt())
        } else {
            max(24, (op.w - pad * 2).toInt())
        }
        var text: CharSequence = expandTabs(txt, tp, op.tabPx, op.tabOff)
        var avail = avail0
        var layout = buildSlideText(text, tp, avail, op.align, op.lineMul)
        // 色块上的短标签（"8B" "4B" "2B"）在窄框里被折成「2」+「B」两行，
        // 纯粹是字体实际宽度比 PowerPoint 估算的多几个像素。宁可让它略微溢出
        // 形状框，也不要拆成两行 —— 原稿里这些字本来就在框外一点点。
        if (!op.noWrap && layout.lineCount > 1 && txt.length <= 8 &&
            txt.toString().indexOf('\n') < 0
        ) {
            avail = max(avail, (op.w * 2.4f).toInt())
            layout = buildSlideText(text, tp, avail, op.align, op.lineMul)
        }
        // 兜底：PowerPoint 的自动缩排没写进文件时（老版本导出的 pptx），
        // 按框高自己往下缩，宁可小一点也不要溢出页面被裁掉半行。
        // 缩得超过 28% 说明框本身的尺寸就不对（比如占位符只有一条），
        // 这时宁可让它溢出也不要缩成一堆看不清的小字。
        var guard = 0
        while (op.fit && !op.noWrap && op.h > 6f &&
            layout.height > op.h + 1f && guard < 2
        ) {
            val f = op.h / layout.height.toFloat()
            if (f > 0.995f || f < 0.72f) break
            text = shrinkText(text, f)
            layout = buildSlideText(text, tp, avail, op.align, op.lineMul)
            guard++
        }
        val dy = when (op.anchor) {
            1 -> op.y + (op.h - layout.height) * 0.5f
            2 -> op.y + op.h - layout.height
            else -> op.y
        }
        // 为了不折行把可用宽度撑宽了，居中文字要跟着回正，否则会往右偏半个框
        val tx = if (avail > op.w + 1)
            op.x + (op.w - avail) * 0.5f + pad
        else
            op.x + pad
        c.save()
        c.translate(tx, dy)
        // 艺术字：先用描边色把整段文字描一遍（STROKE），再把正常填充叠上去。
        // 只画填充的话，「白字 + 深色描边」的标题会变成看不见的白字。
        if (op.textLineW > 0f && op.textLineColor != 0) {
            val sp = TextPaint(tp)
            sp.style = Paint.Style.STROKE
            // PowerPoint 的 w 是描边总宽，Android 的 strokeWidth 是按中线两边各一半
            sp.strokeWidth = op.textLineW * 2f
            sp.strokeJoin = Paint.Join.ROUND
            sp.strokeCap = Paint.Cap.ROUND
            val only = SpannableStringBuilder(text)
            for (fcs in only.getSpans(0, only.length, ForegroundColorSpan::class.java)) {
                only.removeSpan(fcs)
            }
            if (only.isNotEmpty()) {
                only.setSpan(
                    ForegroundColorSpan(op.textLineColor), 0, only.length, SPAN_FLAG
                )
            }
            buildSlideText(only, sp, avail, op.align, op.lineMul).draw(c)
        }
        layout.draw(c)
        c.restore()
    }

    /**
     * 把 `\t` 展开成**定宽垫片**。
     *
     * PPTX 没有「列」的概念，树状图/目录全靠作者敲一串 `\t` 让后面的文字跳到
     * 下一个制表位。Android 的 StaticLayout 对 `\t` 只留一个极窄空白，等于把整段
     * 缩进吞掉 —— 第 2 章第 13 页那张「C++ 数据类型」树就是这样，17 行节点全挤到
     * 框左边，只剩几个花括号孤零零挂在右边。
     *
     * 做法：每个 `\t` 换成一个不换行空格，再套一个 [TabPad] 让它**恰好占据**
     * 「当前笔位到下一个制表位」的像素宽度。宽度由 span 直接指定，完全不依赖
     * 字体度量 —— 这一点很关键：
     *
     * - 早先按空格个数凑，`Paint.measureText(" ")` 在个别环境返回 0，一个制表位
     *   被算成上百个空格，整段撑爆折行；
     * - 后来改成「拉伸一个 NBSP」，宽度仍要用基准宽度反算，字体一变就漂。
     *
     * 现在第 k 个制表位的横坐标恒为 `k*tabPx + tabOff`（`tabOff = -marL`，
     * 因为制表位按 lIns 起算、而正文被 marL 缩进过一段），误差不累积：
     * 不管前面的文字量出来多宽，垫片都把笔位顶回准确位置。
     */
    internal fun expandTabs(src: CharSequence, tp: TextPaint, tabPx: Float, tabOff: Float): CharSequence {
        if (tabPx <= 0f) return src
        var hasTab = false
        for (i in src.indices) {
            if (src[i] == '\t') {
                hasTab = true
                break
            }
        }
        if (!hasTab) return src

        val n = src.length
        val sb = if (src is SpannableStringBuilder) src else SpannableStringBuilder(src)
        val defPx = if (tp.textSize > 0f) tp.textSize else 16f
        val keepSize = tp.textSize
        // (tab 下标, 垫片宽度 px)
        val fills = ArrayList<FloatArray>()
        var curX = 0f
        var i = 0
        while (i < n) {
            val ch = sb[i]
            if (ch == '\t') {
                // 跳到严格大于笔位的下一个制表位；笔位已经越过就顺延到再下一个
                val k = floor((curX - tabOff) / tabPx) + 1f
                val target = k * tabPx + tabOff
                val need = max(1f, target - curX)
                fills.add(floatArrayOf(i.toFloat(), need))
                curX = target
                i++
            } else if (ch == '\n') {
                curX = 0f
                i++
            } else {
                // 一段普通文字一次量完，别一个字符一次 measureText
                var j = i + 1
                while (j < n && sb[j] != '\t' && sb[j] != '\n') j++
                tp.textSize = sizeAt(sb, i, defPx)
                curX += tp.measureText(sb, i, j)
                i = j
            }
        }
        // 一换一，长度不变，下标不会互相干扰
        for (k in fills.indices.reversed()) {
            val idx = fills[k][0].toInt()
            sb.replace(idx, idx + 1, NBSP)
            sb.setSpan(TabPad(fills[k][1]), idx, idx + 1, SPAN_FLAG)
        }
        tp.textSize = keepSize
        return sb
    }

    /**
     * 宽度精确等于构造值的空垫片。
     *
     * `ReplacementSpan.getSize()` 的返回值就是它在排版里占的宽度，画的时候什么都不画。
     * 这样「一个制表位 = 多少像素」是算出来的，而不是拿字体去凑出来的。
     */
    private class TabPad(private val w: Float) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            // 顺带把行高沿用当前字体，别让垫片把行撑高
            if (fm != null) {
                val m = paint.fontMetricsInt
                fm.ascent = m.ascent
                fm.descent = m.descent
                fm.top = m.top
                fm.bottom = m.bottom
                fm.leading = m.leading
            }
            return w.toInt().coerceAtLeast(1)
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            // 空白，什么都不画
        }
    }

    /** 取下标 [i] 处实际生效的字号（px） */
    private fun sizeAt(sb: Spanned, i: Int, def: Float): Float {
        val end = min(i + 1, sb.length)
        var out = def
        for (sp in sb.getSpans(i, end, AbsoluteSizeSpan::class.java)) out = sp.size.toFloat()
        return out
    }

    private fun buildSlideText(
        txt: CharSequence,
        tp: TextPaint,
        avail: Int,
        align: Layout.Alignment,
        mul: Float
    ): StaticLayout = StaticLayout.Builder
        .obtain(txt, 0, txt.length, tp, avail)
        .setAlignment(align)
        .setLineSpacing(1f, mul)
        .setIncludePad(false)
        .build()

    /** 复制一份文字，把所有字号按 [f] 缩小 */
    private fun shrinkText(txt: CharSequence, f: Float): CharSequence {
        val out = SpannableStringBuilder(txt)
        for (span in out.getSpans(0, out.length, AbsoluteSizeSpan::class.java)) {
            val s = out.getSpanStart(span)
            val e = out.getSpanEnd(span)
            val px = (span.size * f).toInt().coerceAtLeast(5)
            out.removeSpan(span)
            if (s >= 0 && e > s) out.setSpan(AbsoluteSizeSpan(px, span.dip), s, e, SPAN_FLAG)
        }
        return out
    }

    // ======================= 背景解析 =======================

    /**
     * 解析 `<p:bg>`：幻灯片的背景可以是纯色、渐变或一张图，也可以是
     * `<p:bgRef idx="1001">` 这种「引用主题背景样式」。幻灯片自己没有就继承版式、母版。
     *
     * 只取第一层填充就够 —— 背景不存在多层叠加的写法。
     */
    private fun parseBgFill(
        xml: String,
        theme: PptxShape.Theme,
        rels: Map<String, String>
    ): PptxShape.Fill? {
        if (xml.isBlank() || !xml.contains("<p:bg")) return null
        var kind = 0
        var color = 0
        var ref: ImgRef? = null
        var inGrad = false
        val stops = ArrayList<Int>()
        val pos = ArrayList<Float>()
        var ang = 0f
        var gsPos = 0f
        var base = -1
        var lumMod = 1f
        var lumOff = 0f
        var alpha = 255
        var inBg = false
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8")
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "bg" -> inBg = true
                        "solidFill" -> if (inBg) kind = 1
                        "gradFill" -> if (inBg) {
                            kind = 2
                            inGrad = true
                        }
                        "blipFill" -> if (inBg) kind = 3
                        "gs" -> if (inGrad) {
                            gsPos = (attr(p, "pos")?.toFloatOrNull() ?: 0f) / 100000f
                        }
                        "lin" -> if (inGrad) {
                            ang = (attr(p, "ang")?.toFloatOrNull() ?: 0f) / 60000f
                        }
                        "blip" -> {
                            attr(p, "embed")?.let { rels[it] }?.let { ref = ImgRef(it, 0f, 0f) }
                        }
                        "srgbClr", "schemeClr", "sysClr", "prstClr" -> {
                            base = when (p.name) {
                                "srgbClr" -> attr(p, "val")?.let { PptxShape.parseIntColor(it) }
                                "schemeClr" -> theme.resolve(attr(p, "val"))
                                "sysClr" -> attr(p, "lastClr")?.let { PptxShape.parseIntColor(it) }
                                else -> presetColor(attr(p, "val"))
                            } ?: 0
                            lumMod = 1f
                            lumOff = 0f
                            alpha = 255
                        }
                        "lumMod" -> if (base >= 0) {
                            lumMod = (attr(p, "val")?.toFloatOrNull() ?: 100000f) / 100000f
                        }
                        "lumOff" -> if (base >= 0) {
                            lumOff = (attr(p, "val")?.toFloatOrNull() ?: 0f) / 100000f
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "bg" -> inBg = false
                        "gradFill" -> inGrad = false
                        "srgbClr", "schemeClr", "sysClr", "prstClr" -> {
                            if (base >= 0) {
                                val c = PptxShape.modColor(base, lumMod, lumOff, alpha)
                                if (inGrad) {
                                    stops.add(c)
                                    pos.add(gsPos)
                                } else {
                                    color = c
                                }
                            }
                            base = -1
                        }
                    }
                }
                if (!inBg && kind != 0) break
                event = p.next()
            }
        } catch (_: Throwable) {
        }
        return when (kind) {
            1 -> PptxShape.Fill.solid(color)
            2 -> if (stops.isEmpty()) null else PptxShape.Fill(
                2, stops = stops.toIntArray(), stopPos = pos.toFloatArray(), angle = ang
            )
            3 -> if (ref == null) null else PptxShape.Fill(3, ref = ref)
            else -> null
        }
    }

    private fun arrowOf(type: String?): Int = when (type) {
        null, "none" -> 0
        "oval", "diamond" -> 3
        "arrow", "open" -> 2
        else -> 1
    }

    /** Office 预置色板里常用的一小部分；查不到就让调用方落到主题色 */
    private fun presetColor(name: String?): Int? = when (name) {
        null -> null
        "black" -> Color.BLACK
        "white" -> Color.WHITE
        "red" -> 0xFFFF0000.toInt()
        "green" -> 0xFF008000.toInt()
        "blue" -> 0xFF0000FF.toInt()
        "yellow" -> 0xFFFFFF00.toInt()
        "gray", "grey" -> 0xFF808080.toInt()
        "ltGray" -> 0xFFD3D3D3.toInt()
        "dkGray" -> 0xFFA9A9A9.toInt()
        "orange" -> 0xFFFFA500.toInt()
        else -> null
    }

    // ======================= 排版与分页 =======================

    private class _Renderer(
        private val sink: PageSink,
        private val w: Int,
        private val h: Int
    ) {
        private val margin = 92f
        private val contentW = (w - margin * 2).toInt()
        private val bottom = h - margin

        private var ops = ArrayList<(Canvas) -> Unit>()
        private var y = margin
        private val tp = TextPaint().apply { isAntiAlias = true }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
            color = 0xFFB8B8B8.toInt()
        }

        fun render(blocks: List<Block>) {
            for (b in blocks) {
                when (b) {
                    is Block.Para -> putPara(b)
                    is Block.Pic -> putPic(b)
                    is Block.Tbl -> putTable(b)
                }
            }
            flush()
        }

        private fun flush() {
            if (ops.isEmpty()) return
            val captured = ops
            sink.render {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                for (draw in captured) draw(c)
                bmp
            }
            ops = ArrayList()
            y = margin
        }

        private fun needsPage(extra: Float): Boolean = y + extra > bottom

        private fun putPara(b: Block.Para) {
            val sb = SpannableStringBuilder()
            for (r in b.runs) {
                val start = sb.length
                sb.append(r.text)
                val end = sb.length
                if (end <= start) continue
                val px = (r.sizePt * DPI / 72f).coerceIn(9f, 96f)
                sb.setSpan(AbsoluteSizeSpan(px.toInt(), false), start, end, SPAN_FLAG)
                if (r.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, SPAN_FLAG)
                if (r.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, SPAN_FLAG)
                sb.setSpan(ForegroundColorSpan(r.color), start, end, SPAN_FLAG)
            }
            if (sb.isEmpty()) {
                y += DEFAULT_PT * DPI / 72f
                return
            }
            if (b.indent > 0f) {
                sb.setSpan(
                    LeadingMarginSpan.Standard(b.indent.toInt(), 0),
                    0, sb.length, SPAN_FLAG
                )
            }
            tp.textSize = DEFAULT_PT * DPI / 72f
            tp.color = 0xFF232323.toInt()
            tp.setTypeface(Typeface.DEFAULT)
            val layout = StaticLayout.Builder
                .obtain(sb, 0, sb.length, tp, contentW)
                .setAlignment(b.align)
                .setLineSpacing(3f, b.lineSpacing)
                .setIncludePad(false)
                .build()

            y += b.spaceBefore
            var consumed = 0
            val total = layout.height
            while (consumed < total) {
                if (bottom - y < 20f) flush()
                val avail = (bottom - y).toInt().coerceAtLeast(1)
                val slice = min(total - consumed, avail)
                val top = consumed
                val atY = y
                ops.add { c ->
                    c.save()
                    c.clipRect(0f, atY, (margin + contentW), atY + slice)
                    c.translate(margin, atY - top)
                    layout.draw(c)
                    c.restore()
                }
                consumed += slice
                y += slice
            }
            y += b.spaceAfter
        }

        private fun putPic(b: Block.Pic) {
            val bmp = b.ref.bmp ?: return
            if (bmp.isRecycled || bmp.width <= 0) return
            var dw = if (b.ref.wPx > 0f) min(b.ref.wPx, contentW.toFloat()) else contentW * 0.7f
            var dh = dw * bmp.height / bmp.width
            val maxH = bottom - margin
            if (dh > maxH) {
                dh = maxH
                dw = dh * bmp.width / bmp.height
            }
            if (y + dh > bottom) flush()
            val atY = y
            val dx = margin + (contentW - dw) * 0.5f
            ops.add { c ->
                c.drawBitmap(bmp, null, RectF(dx, atY, dx + dw, atY + dh), null)
            }
            y += dh + 12f
        }

        private fun putTable(t: Block.Tbl) {
            if (t.rows.isEmpty()) return
            val cols = t.rows.maxOf { it.size }.coerceAtLeast(1)
            val colW = contentW.toFloat() / cols
            val pad = 7f
            for (row in t.rows) {
                val layouts = ArrayList<StaticLayout?>()
                var rowH = 24f
                for (ci in 0 until cols) {
                    val cellText = row.getOrElse(ci) { "" }
                    if (cellText.isBlank()) {
                        layouts.add(null)
                        continue
                    }
                    tp.textSize = DEFAULT_PT * DPI / 72f * 0.94f
                    tp.color = 0xFF232323.toInt()
                    tp.setTypeface(Typeface.DEFAULT)
                    val l = StaticLayout.Builder
                        .obtain(cellText, 0, cellText.length, tp, (colW - pad * 2).toInt().coerceAtLeast(20))
                        .setLineSpacing(2f, 1.2f)
                        .setIncludePad(false)
                        .build()
                    layouts.add(l)
                    rowH = max(rowH, l.height + pad * 2)
                }
                if (needsPage(rowH)) flush()
                val atY = y
                val cells = ArrayList(layouts)
                val rh = rowH
                ops.add { c ->
                    for (ci in 0 until cols) {
                        val cx = margin + ci * colW
                        c.drawRect(cx, atY, cx + colW, atY + rh, border)
                        val l = cells.getOrNull(ci) ?: continue
                        c.save()
                        c.clipRect(cx, atY, cx + colW, atY + rh)
                        c.translate(cx + pad, atY + pad)
                        l.draw(c)
                        c.restore()
                    }
                }
                y += rowH
            }
            y += 10f
        }
    }

    /**
     * 画一张 PPT 表格。列宽来自 a:gridCol，行高取 a:tr/@h 与文字实际高度的较大值，
     * gridSpan 的单元格横跨多列（表头 "birthday" 盖住 month/day/year 就是这种）。
     * 表比框宽时整体等比缩小，避免像旧版那样从页面左边溢出去。
     */
    private fun drawTable(
        c: Canvas,
        t: TableData,
        x0: Float,
        y0: Float,
        maxW: Float,
        tp: TextPaint
    ) {
        if (t.rows.isEmpty()) return
        val cols = t.cols()
        var total = 0f
        for (v in cols) total += v
        if (total <= 0f) return

        val k = if (maxW > 1f && total > maxW) (maxW / total).coerceIn(0.2f, 1f) else 1f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f
            color = 0xFF4A4A4A.toInt()
        }
        c.save()
        c.translate(x0, y0)
        if (k < 1f) c.scale(k, k)

        var cy = 0f
        for (row in t.rows) {
            var ci = 0
            val n = row.cells.size
            val xs = FloatArray(n)
            val ws = FloatArray(n)
            val layouts = arrayOfNulls<StaticLayout>(n)
            var rh = row.height
            for (i in 0 until n) {
                val cell = row.cells[i]
                var xAt = 0f
                var cw = 0f
                for (j in 0 until cols.size) {
                    if (j < ci) xAt += cols[j] else if (j < ci + cell.span) cw += cols[j]
                }
                xs[i] = xAt
                ws[i] = cw
                if (!cell.merged && cell.text.isNotBlank() && cw > 2f) {
                    tp.textSize = 18f
                    val l = buildSlideText(
                        cell.text, tp,
                        (cw - cell.padL - cell.padR).toInt().coerceAtLeast(8),
                        cell.align, 1.12f
                    )
                    layouts[i] = l
                    rh = max(rh, l.height + cell.padT + cell.padB)
                }
                ci += cell.span
            }
            if (rh <= 0f) rh = 22f

            for (i in 0 until n) {
                val cell = row.cells[i]
                if (cell.merged) continue
                val cx = xs[i]
                val cwd = ws[i]
                if (cwd < 1f) continue
                c.drawRect(cx, cy, cx + cwd, cy + rh, border)
                val l = layouts[i] ?: continue
                val ty = when (cell.anchor) {
                    1 -> cy + (rh - l.height) * 0.5f
                    2 -> cy + rh - l.height - cell.padB
                    else -> cy + cell.padT
                }
                c.save()
                c.clipRect(cx, cy, cx + cwd, cy + rh)
                c.translate(cx + cell.padL, ty)
                l.draw(c)
                c.restore()
            }
            cy += rh
        }
        c.restore()
    }

    private fun anchorOf(v: String?): Int = when (v) {
        "ctr" -> 1
        "b" -> 2
        else -> 0
    }

    // ======================= 工具 =======================

    private fun flag(p: XmlPullParser): Boolean {
        val v = attr(p, "val")
        return v == null || (v != "0" && v != "false")
    }

    private fun twipsToPx(twips: Float): Float = twips / TWIPS_PER_INCH * DPI

    /**
     * Word 的标题通常只靠样式（Title / Heading 1..3）定义字号，正文里没有显式的 w:sz。
     * 这里做一层最小映射，否则标题会被渲染成正文大小，整篇的层次就没了。
     */
    private fun applyStyle(id: String?, acc: ParaAcc) {
        val key = id?.lowercase()?.replace(" ", "") ?: return
        when {
            key.contains("subtitle") -> {
                acc.styleSize = 14f
                acc.styleBold = false
            }
            key.contains("title") -> {
                acc.styleSize = 25f
                acc.styleBold = true
            }
            key.startsWith("heading1") || key == "1" -> {
                acc.styleSize = 18f
                acc.styleBold = true
            }
            key.startsWith("heading2") || key == "2" -> {
                acc.styleSize = 15f
                acc.styleBold = true
            }
            key.startsWith("heading3") || key == "3" -> {
                acc.styleSize = 13f
                acc.styleBold = true
            }
        }
    }

    private fun attr(p: XmlPullParser, local: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            if (n == local || n.endsWith(":$local")) return p.getAttributeValue(i)
        }
        return null
    }

    private fun parseColor(v: String): Int? {
        val s = v.trim().removePrefix("#")
        return try {
            when (s.length) {
                6 -> 0xFF000000.toInt() or s.toLong(16).toInt()
                8 -> s.toLong(16).toInt()
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readEntry(file: File, name: String): String? {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == name) {
                    val bytes = readBytesCapped(zip, MAX_XML_BYTES) ?: return null
                    return String(bytes, Charsets.UTF_8)
                }
                e = zip.nextEntry
            }
        }
        return null
    }

    private fun readBytesCapped(zip: ZipInputStream, cap: Int): ByteArray? {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = zip.read(tmp)
            if (n <= 0) break
            total += n
            if (total > cap) return null
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    /** 读取 rels，返回 rId -> 解压包内完整路径 */
    private fun readRels(file: File, relsPath: String): Map<String, String> {
        val xml = readEntry(file, relsPath) ?: return emptyMap()
        val baseDir = relsPath.substringBeforeLast("_rels/")
        val out = HashMap<String, String>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = attr(p, "Id")
                val target = attr(p, "Target")
                if (id != null && target != null && !target.startsWith("http")) {
                    out[id] = resolvePath(baseDir, target)
                }
            }
            event = p.next()
        }
        return out
    }

    private fun resolvePath(baseDir: String, target: String): String {
        if (!target.contains("..")) return baseDir + target.trimStart('/')
        val parts = ArrayList<String>()
        for (seg in (baseDir + target).split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun decodeCapped(bytes: ByteArray, maxW: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxW) sample *= 2
        val dec = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dec)
        } catch (t: Throwable) {
            null
        }
    }

    private fun loadDocxImages(file: File, blocks: List<Block>) {
        val refs = blocks.filterIsInstance<Block.Pic>().map { it.ref }.take(MAX_IMAGES)
        if (refs.isEmpty()) return
        val byPath = HashMap<String, MutableList<ImgRef>>()
        for (r in refs) byPath.getOrPut(r.zipPath) { ArrayList() }.add(r)

        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val targets = byPath[e.name]
                if (targets != null) {
                    readBytesCapped(zip, MAX_IMAGE_BYTES)?.let { bytes ->
                        val bmp = decodeCapped(bytes, 1400)
                        if (bmp != null) for (t in targets) t.bmp = bmp
                    }
                }
                e = zip.nextEntry
            }
        }
    }

    private fun recycleImages(blocks: List<Block>) {
        val seen = IdentityHashMap<Bitmap, Boolean>()
        for (b in blocks) {
            if (b !is Block.Pic) continue
            val bmp = b.ref.bmp ?: continue
            if (seen.put(bmp, true) == null && !bmp.isRecycled) bmp.recycle()
        }
    }
}
