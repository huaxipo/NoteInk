package com.noteink.app

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.noteink.app.store.OfficeRender
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PptxRenderTest {

    private val src = File(System.getProperty("pptx.sample") ?: "")

    @Test
    fun inspectSlides() {
        if (!src.exists()) {
            println("SRC MISSING, skipped")
            return
        }
        for (page in intArrayOf(19, 38)) {
            val art = OfficeRender.loadSlideArt(src, page, 1654)
            if (art == null) {
                println("=== page ${page + 1}: NULL")
                continue
            }
            println("=== page ${page + 1} (${art.pageW}x${art.pageH}) ops=${art.ops.size} back=${art.back.size} bg=${art.bg?.kind}")
            for ((tag, list) in listOf("BACK" to art.back, "OP" to art.ops)) {
                for ((i, op) in list.withIndex()) {
                    val f = op.fill
                    val l = op.line
                    val colors = LinkedHashSet<String>()
                    val t = op.text
                    if (t is Spanned) {
                        for (sp in t.getSpans(0, t.length, ForegroundColorSpan::class.java)) {
                            colors.add("#" + Integer.toHexString(sp.foregroundColor))
                        }
                    }
                    val b = android.graphics.RectF()
                    op.shape?.computeBounds(b, true)
                    val txt = t?.toString()?.replace("\n", "\\n")?.take(28)
                    val sizes = LinkedHashSet<String>()
                    if (t is Spanned) {
                        for (sp in t.getSpans(0, t.length, android.text.style.AbsoluteSizeSpan::class.java)) {
                            sizes.add("${sp.size}px@%d".format(t.getSpanStart(sp)))
                        }
                    }
                    println(
                        "  $tag#$i '${op.name}' ${op.x.toInt()},${op.y.toInt()} ${op.w.toInt()}x${op.h.toInt()}" +
                            " shape=${op.shape != null}[${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}]" +
                            " fill=${f?.kind}/${f?.color?.let { Integer.toHexString(it) }}" +
                            " sizes=$sizes lnW=${op.textLineW} lnC=${op.textLineColor.let{Integer.toHexString(it)}}" +
                            " line=${l?.let { Integer.toHexString(it.color) }}" +
                            " anchor=${op.anchor} rot=${op.rot} flip=${op.flipH}/${op.flipV}" +
                            " txtCol=$colors text='$txt'"
                    )
                }
            }
            art.release()
        }
    }
}
