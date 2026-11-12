package com.noteink.app

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan
import com.noteink.app.store.OfficeRender
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 把一张含制表位的树状图页面（大文本框 + 若干花括号）的制表位展开结果打印出来。
 *
 * 这是唯一能验证「`\t` → 定宽垫片」是否正确的地方：每个节点的列坐标
 * 必须落在 k*tabPx 上，而花括号的位置是文件里写死的，两者一对就知道有没有对上。
 *
 * 样张不入库。运行时用 `-Dpptx.sample=<path>` 指定，没有则跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Dump13Test {

    @Test
    fun dump() {
        val src = File(System.getProperty("pptx.sample") ?: "")
        if (src.path.isBlank() || !src.exists()) {
            println("SRC MISSING（用 -Dpptx.sample=<path> 指定样张后重跑）")
            return
        }
        val art = OfficeRender.loadSlideArt(src, 12, 1654) ?: run {
            println("NULL")
            return
        }
        println("PAGE ${art.pageW}x${art.pageH} ops=${art.ops.size}")
        val tp = TextPaint()
        tp.isAntiAlias = true
        tp.textSize = 16f
        for ((i, op) in art.ops.withIndex()) {
            val t = op.text ?: continue
            if (op.name != "Rectangle 2") {
                println("  #$i '${op.name}' xy=${op.x.toInt()},${op.y.toInt()} wh=${op.w.toInt()}x${op.h.toInt()}")
                continue
            }
            println("  #$i '${op.name}' xy=${op.x.toInt()},${op.y.toInt()} wh=${op.w.toInt()}x${op.h.toInt()} tabPx=${op.tabPx} tabOff=${op.tabOff}")
            val ex = OfficeRender.expandTabs(SpannableStringBuilder(t), tp, op.tabPx, op.tabOff) as Spanned
            // 每个 pad 的宽度 = 它顶替的那个 `\t` 贡献的横向位移
            val lines = ex.toString().split("\n")
            var lineNo = 0
            var lineStart = 0
            println("      lines=${lines.size}")
            for (l in lines) {
                val end = lineStart + l.length
                val pads = ex.getSpans(lineStart, end, ReplacementSpan::class.java)
                    .sortedBy { ex.getSpanStart(it) }
                val ws = pads.map { it.getSize(tp, ex, ex.getSpanStart(it), ex.getSpanEnd(it), null) }
                // 累积到每个 pad 右边缘的横向位置（不含 pad 之间的文字宽度）
                println("      L%-2d pads=%d  w=%s  '${l.replace("\u00A0", "·").take(34)}'"
                    .format(lineNo, ws.size, ws.joinToString(",")))
                lineNo++
                lineStart = end + 1
            }
        }
        art.release()
    }
}
