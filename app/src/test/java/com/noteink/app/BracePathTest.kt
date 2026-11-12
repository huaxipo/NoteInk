package com.noteink.app

import android.graphics.Path
import android.graphics.RectF
import com.noteink.app.store.PptxShape
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BracePathTest {

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    @Test
    fun shape() {
        // 第2章 第13页真实参数：AutoShape 3 / 4 / 5 / 6 / 7
        val cases = listOf(
            Quad("sp3", 29f, 468f, mapOf("adj1" to 79242f, "adj2" to 50000f)),
            Quad("sp4", 31f, 253f, mapOf("adj1" to 50249f, "adj2" to 50000f)),
            Quad("sp5", 10f, 97f, mapOf("adj1" to 108333f, "adj2" to 50000f)),
            Quad("sp6", 15f, 97f, mapOf("adj1" to 100000f, "adj2" to 50000f)),
            Quad("sp7", 31f, 175f, mapOf("adj1" to 47871f, "adj2" to 50000f))
        )
        for (q in cases) {
            val name = q.a; val w = q.b; val h = q.c; val adj = q.d
            val p = Path()
            PptxShape.buildPreset("leftBrace", adj, p, w, h)
            val b = RectF()
            p.computeBounds(b, true)
            println("$name w=$w h=$h bounds=[%.3f,%.3f,%.3f,%.3f]".format(b.left, b.top, b.right, b.bottom))
        }
    }

    /**
     * 把 sp4 的路径采样点换算成 PowerPoint 导出图（1654x932）上的像素坐标，
     * 用来和官方渲染的轮廓逐像素比对。
     *
     * sp4 在幻灯片上的位置：off=(5315693,1712104) ext=(228602,1872208)，单位 EMU
     */
    @Test
    fun trace() {
        val p = Path()
        PptxShape.buildPreset("leftBrace", mapOf("adj1" to 50249f, "adj2" to 50000f), p, 31f, 254f)
        val a = p.approximate(0.1f)
        val ox = 5315693.0 / 12195175.0 * 1654
        val oy = 1712104.0 / 6858000.0 * 932
        val sx = 228602.0 / 12195175.0 * 1654
        val sy = 1872208.0 / 6858000.0 * 932
        println("origin=(%.1f,%.1f) size=(%.1f,%.1f)".format(ox, oy, sx, sy))
        var lastY = -999.0
        var i = 0
        while (i < a.size) {
            val y = oy + a[i + 2] * sy
            if (y - lastY >= 4.0 || i + 3 >= a.size) {
                println("y=%6.1f  x=%6.1f".format(y, ox + a[i + 1] * sx))
                lastY = y
            }
            i += 3
        }
    }
}
