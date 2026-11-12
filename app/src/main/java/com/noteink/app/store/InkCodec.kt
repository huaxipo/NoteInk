package com.noteink.app.store

import com.noteink.app.model.InkPoint
import com.noteink.app.model.InkTool
import com.noteink.app.model.Stroke
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 笔迹二进制存储。
 * 相比 JSON，一笔 200 个点约 2.4KB -> 1KB 左右，加载也快得多。
 */
object InkCodec {

    private const val MAGIC = 0x4E494E31 // "NIN1"

    fun write(file: File, strokes: List<Stroke>) {
        file.parentFile?.mkdirs()
        DataOutputStream(
            BufferedOutputStream(GZIPOutputStream(FileOutputStream(file)), 32 * 1024)
        ).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(1) // version
            out.writeInt(strokes.size)
            val tools = InkTool.values()
            for (s in strokes) {
                out.writeByte(s.tool.ordinal)
                out.writeInt(s.color)
                out.writeFloat(s.width)
                out.writeInt(s.points.size)
                for (p in s.points) {
                    out.writeFloat(p.x)
                    out.writeFloat(p.y)
                    out.writeFloat(p.p)
                }
            }
        }
    }

    fun read(file: File): MutableList<Stroke> {
        val list = ArrayList<Stroke>()
        if (!file.exists()) return list
        try {
            DataInputStream(
                BufferedInputStream(GZIPInputStream(FileInputStream(file)), 32 * 1024)
            ).use { inp ->
                if (inp.readInt() != MAGIC) return list
                inp.readInt()
                val count = inp.readInt()
                val tools = InkTool.values()
                repeat(count) {
                    val ti = inp.readByte().toInt()
                    val color = inp.readInt()
                    val width = inp.readFloat()
                    val n = inp.readInt()
                    val pts = ArrayList<InkPoint>(n)
                    for (i in 0 until n) {
                        val x = inp.readFloat()
                        val y = inp.readFloat()
                        val p = inp.readFloat()
                        pts.add(InkPoint(x, y, p))
                    }
                    val tool = if (ti in tools.indices) tools[ti] else InkTool.PEN
                    list.add(Stroke(tool, color, width, pts))
                }
            }
        } catch (t: Throwable) {
            // 文件损坏时返回已读到的部分，避免整页打不开
        }
        return list
    }
}
