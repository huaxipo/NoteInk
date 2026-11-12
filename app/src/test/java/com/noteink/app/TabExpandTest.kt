package com.noteink.app

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ReplacementSpan
import com.noteink.app.store.OfficeRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 制表位展开：`\t` 必须变成**宽度精确**的空垫片。
 *
 * 这一条是整个树状图能不能画对的关键。以前按「数空格」凑宽度，`measureText(" ")`
 * 一旦返回 0 就会算出上百个空格；后来按「拉伸一个 NBSP」凑，又要靠基准宽度反算，
 * 字体一换就漂。现在垫片宽度直接由 span 指定，第 k 个制表位恒在 k*tabPx + tabOff。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TabExpandTest {

    private val tp = TextPaint().apply {
        textSize = 31f
        isAntiAlias = true
    }

    // 第 2 章第 13 页的真实比例：1654px 画布 / 12195175 EMU
    private val tabPx = 750_000f * (1654f / 12_195_175f)   // ≈101.7
    private val marL = 365_125f * (1654f / 12_195_175f)    // ≈49.5

    private fun expand(s: String): Spanned =
        OfficeRender.expandTabs(
            SpannableStringBuilder(s).apply {
                setSpan(AbsoluteSizeSpan(31), 0, length, 0)
            },
            tp, tabPx, -marL
        ) as Spanned

    private fun padWidths(out: Spanned): List<Int> =
        out.getSpans(0, out.length, ReplacementSpan::class.java)
            .sortedBy { out.getSpanStart(it) }
            .map { it.getSize(tp, out, out.getSpanStart(it), out.getSpanEnd(it), null) }

    @Test
    fun leadingTabsLandOnGrid() {
        // 第一行 6 个 \t：第一个垫片把笔位从 0 顶到 T-marL，之后每个都是整格
        val out = expand("\t\t\t\t\t\t短整型(short int)2bit")
        assertTrue("tab 必须全部被替换", out.indexOf('\t') < 0)
        val ws = padWidths(out)
        println("pads=$ws  tabPx=%.1f marL=%.1f".format(tabPx, marL))
        assertEquals("6 个 tab 应该有 6 个垫片", 6, ws.size)
        assertTrue("第一个垫片 = 一格减去 marL", abs(ws[0] - (tabPx - marL)) <= 1.5f)
        for (k in 1 until 6) {
            assertTrue("第 ${k + 1} 个垫片应为一整格", abs(ws[k] - tabPx) <= 1.5f)
        }
    }

    @Test
    fun inlineTabStaysOneCell() {
        // 「整型\t整型(int) 4」：5 个前导 tab 之后跟一段文字，再一个 tab。
        // 关键是这一格**没有**被算成几十上百个字符。
        val out = expand("\t\t\t\t\t整型\t整型(int) 4")
        val ws = padWidths(out)
        println("pads=$ws")
        assertEquals(6, ws.size)
        for (w in ws) assertTrue("垫片宽度异常：$w", w in 1..(tabPx.toInt() + 1))
    }

    @Test
    fun noTabReturnsSame() {
        val src = SpannableStringBuilder("布尔型就是逻辑型，空类型就是无值型")
        val out = OfficeRender.expandTabs(src, tp, tabPx, -marL)
        assertEquals(src.length, out.length)
        assertEquals(0, padWidths(out as Spanned).size)
    }
}
