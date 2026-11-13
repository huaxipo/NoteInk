package com.noteink.app

import android.graphics.RectF
import android.text.Layout
import com.noteink.app.ink.tileGrid
import com.noteink.app.store.OfficeRender
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PPTX 保真度的回归测试。
 *
 * 两处曾经丢过信息：
 *  1. wrap="none" 的左对齐文本被「撑宽布局」的居中补偿往左推，底部横幅首字被裁掉；
 *  2. svgBlip 的矢量图没有 r:embed，整块图形消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PptxFidelityTest {

    private fun op(x: Float, w: Float, align: Layout.Alignment) =
        OfficeRender.SlideOp(x, 0f, w, 40f, "x", align, null, null, 0, true)

    @Test
    fun leftAlignedTextStaysAtShapeLeft() {
        val o = op(100f, 800f, Layout.Alignment.ALIGN_NORMAL)
        // wrap="none" 时 avail 恒为 1.3*w
        assertEquals(108f, OfficeRender.textOriginX(o, 1040f, 8f), 0.01f)
    }

    @Test
    fun centeredTextIsPulledBackByHalfTheExtraWidth() {
        val o = op(100f, 800f, Layout.Alignment.ALIGN_CENTER)
        assertEquals(
            100f + (800f - 1040f) * 0.5f + 8f,
            OfficeRender.textOriginX(o, 1040f, 8f), 0.01f
        )
    }

    @Test
    fun rightAlignedTextIsPulledBackByTheWholeExtraWidth() {
        val o = op(100f, 800f, Layout.Alignment.ALIGN_OPPOSITE)
        assertEquals(
            100f + (800f - 1040f) + 8f,
            OfficeRender.textOriginX(o, 1040f, 8f), 0.01f
        )
    }

    @Test
    fun hoistsSvgBlipRefWithoutOverwritingRasterFallback() {
        val svgOnly = "<p:pic><a:blip><a:extLst><a:ext uri=\"x\">" +
            "<asvg:svgBlip r:embed=\"rId2\"/></a:ext></a:extLst></a:blip></p:pic>"
        val out = OfficeRender.hoistSvgBlipRefs(svgOnly)
        assertTrue(out.contains("<a:blip r:embed=\"rId2\">"))

        val withPng = "<a:blip r:embed=\"rId3\"><a:extLst><a:ext>" +
            "<asvg:svgBlip r:embed=\"rId2\"/></a:ext></a:extLst></a:blip>"
        assertEquals(withPng, OfficeRender.hoistSvgBlipRefs(withPng))

        // 自闭合位图 blip 在前面时，不能把后面的 svgBlip 块吞掉
        val mixed = "<a:blip r:embed=\"rId9\"/>" + svgOnly
        val mixedOut = OfficeRender.hoistSvgBlipRefs(mixed)
        assertTrue(mixedOut.startsWith("<a:blip r:embed=\"rId9\"/>"))
        assertTrue(mixedOut.contains("<a:blip r:embed=\"rId2\">"))
    }

    @Test
    fun highResTilesNeverExtendOutsideThePage() {
        // 页面 1240x700 居中显示：可见区域四周都比页面大，left/top 为负
        val g = tileGrid(RectF(-260f, -90f, 1500f, 820f), 384f, 1240, 700)!!
        val (x0, y0, x1, y1) = g
        assertEquals(0, x0)
        assertEquals(0, y0)
        assertEquals(3, x1)   // floor(1240/384)
        assertEquals(1, y1)   // floor(700/384)
        for (gy in y0..y1) for (gx in x0..x1) {
            val left = gx * 384f
            val top = gy * 384f
            val right = minOf(left + 384f, 1240f)
            val bottom = minOf(top + 384f, 700f)
            assertTrue(left >= 0f && top >= 0f)
            assertTrue(right <= 1240f && bottom <= 700f)
            assertTrue(right > left && bottom > top)
        }
    }

    @Test
    fun highResTilesClampOversizedViewport() {
        val g = tileGrid(RectF(0f, 0f, 9999f, 9999f), 384f, 1240, 700)!!
        assertArrayEquals(intArrayOf(0, 0, 3, 1), g)
        assertNull(tileGrid(RectF(2000f, 2000f, 2500f, 2500f), 384f, 1240, 700))
        assertNull(tileGrid(RectF(-900f, -900f, -800f, -800f), 384f, 1240, 700))
        assertNull(tileGrid(RectF(0f, 0f, 100f, 100f), 0f, 1240, 700))
    }

    @Test
    fun parsesSimpleSvgPrimitives() {
        val svg = "<svg width=\"420\" height=\"420\" viewBox=\"0 0 420 420\">" +
            "<circle cx=\"210\" cy=\"210\" r=\"145\" fill=\"none\" stroke=\"#D6DCE5\" " +
            "stroke-width=\"1\" stroke-dasharray=\"4 6\" />" +
            "<circle cx=\"210\" cy=\"65\" r=\"44\" fill=\"#E8EFF8\" stroke=\"#3D7BD9\" " +
            "stroke-width=\"1\" /></svg>"
        val s = OfficeRender.parseSimpleSvg(svg)
        assertNotNull(s)
        assertEquals(420f, s!!.w, 0.01f)
        assertEquals(420f, s.h, 0.01f)
        assertEquals(2, s.prims.size)
        assertNull(s.prims[0].fill)
        assertArrayEquals(floatArrayOf(4f, 6f), s.prims[0].dash, 0.01f)
        assertEquals(0xFFE8EFF8.toInt(), s.prims[1].fill)
    }

    /**
     * 诊断用：用 -Dpptx.sample=<path> -Dpptx.page=<1-based> 指定任意样张，
     * 打印每个文字/图片形状的位置，用来对照 PowerPoint 导出的同尺寸 PNG。
     * 没传路径就跳过，不依赖任何本机文件。
     */
    @Test
    fun dumpSampleSlide() {
        val src = File(System.getProperty("pptx.sample") ?: "")
        val page = (System.getProperty("pptx.page") ?: "5").toIntOrNull() ?: 5
        if (!src.exists()) {
            println("SAMPLE SKIPPED (use -Dpptx.sample=<path>)")
            return
        }
        val art = OfficeRender.loadSlideArt(src, page - 1, 1654) ?: return
        try {
            println("PAGE ${art.pageW}x${art.pageH} ops=${art.ops.size}")
            for ((i, o) in art.ops.withIndex()) {
                val t = o.text?.toString()?.take(24)?.replace("\n", "\\n")
                val pic = o.ref?.let { "pic=${it.zipPath} bmp=${it.bmp != null}" } ?: ""
                println("  #$i x=${o.x.toInt()} y=${o.y.toInt()} w=${o.w.toInt()} " +
                    "align=${o.align} noWrap=${o.noWrap} $pic text='$t'")
            }
            println("BACK ${art.back.size}")
            for ((i, o) in art.back.withIndex()) {
                println("  @$i x=${o.x.toInt()} y=${o.y.toInt()} w=${o.w.toInt()} " +
                    "h=${o.h.toInt()} fill=${o.fill != null} line=${o.line != null} " +
                    "text='${o.text?.toString()?.take(20)}'")
            }
            // 母版上 useBgFill="1" + <a:ln><a:noFill/> 的装饰条，不该被
            // p:style 的 fillRef/lnRef 填成两条实心白线
            assertTrue(
                "母版极扁装饰条不应同时带填充与描边",
                art.back.none {
                    it.h > 0f && it.h < 12f && it.w > 200f &&
                        it.fill != null && it.line != null
                }
            )
        } finally {
            art.release()
        }
    }
}
