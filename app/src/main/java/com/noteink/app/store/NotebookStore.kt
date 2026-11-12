package com.noteink.app.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.noteink.app.ink.Ink
import com.noteink.app.model.NotebookMeta
import com.noteink.app.model.PageMeta
import com.noteink.app.model.PageStyle
import com.noteink.app.model.Stroke
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 笔记本本地仓库。目录结构：
 * files/notebooks/<id>/meta.json
 *                    /cover.png
 *                    /bg_p0.png     导入内容或空白底纹的栅格图
 *                    /ink_p0.bin    笔迹
 */
class NotebookStore(private val ctx: Context) {

    val root: File
        get() = File(ctx.filesDir, "notebooks").also { if (!it.exists()) it.mkdirs() }

    fun dir(id: String): File = File(root, id).also { if (!it.exists()) it.mkdirs() }

    // ---------- 列表 / 元信息 ----------

    fun list(): List<NotebookMeta> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { readMeta(it) }.sortedByDescending { it.updatedAt }
    }

    private fun readMeta(d: File): NotebookMeta? {
        val f = File(d, "meta.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("pages") ?: JSONArray()
            val pages = ArrayList<PageMeta>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                pages.add(
                    PageMeta(
                        id = p.getString("id"),
                        bgFile = if (p.isNull("bg")) null else p.getString("bg"),
                        width = p.optInt("w", PageFactory.DEFAULT_W),
                        height = p.optInt("h", PageFactory.DEFAULT_H),
                        style = runCatching { PageStyle.valueOf(p.optString("style", "BLANK")) }
                            .getOrDefault(PageStyle.BLANK),
                        src = if (p.isNull("src")) null else p.optString("src").ifBlank { null },
                        srcPage = p.optInt("srcPage", -1)
                    )
                )
            }
            NotebookMeta(
                id = o.getString("id"),
                title = o.optString("title", "未命名笔记"),
                pages = pages,
                updatedAt = o.optLong("updatedAt", 0L),
                renderV = o.optInt("renderV", 0)
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun saveMeta(meta: NotebookMeta) {
        meta.updatedAt = System.currentTimeMillis()
        val o = JSONObject()
        o.put("id", meta.id)
        o.put("title", meta.title)
        o.put("updatedAt", meta.updatedAt)
        o.put("renderV", meta.renderV)
        val arr = JSONArray()
        for (p in meta.pages) {
            val po = JSONObject()
            po.put("id", p.id)
            if (p.bgFile != null) po.put("bg", p.bgFile) else po.put("bg", JSONObject.NULL)
            po.put("w", p.width)
            po.put("h", p.height)
            po.put("style", p.style.name)
            if (p.src != null) {
                po.put("src", p.src)
                po.put("srcPage", p.srcPage)
            }
            arr.put(po)
        }
        o.put("pages", arr)
        File(dir(meta.id), "meta.json").writeText(o.toString())
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    // ---------- 新建 ----------

    fun create(title: String, style: PageStyle = PageStyle.GRID, pageCount: Int = 1): NotebookMeta {
        val id = "nb_" + System.currentTimeMillis()
        val pages = ArrayList<PageMeta>()
        repeat(pageCount) { i ->
            pages.add(
                PageMeta(
                    id = "p$i",
                    bgFile = null,
                    width = PageFactory.DEFAULT_W,
                    height = PageFactory.DEFAULT_H,
                    style = style
                )
            )
        }
        val meta = NotebookMeta(id, title, pages, System.currentTimeMillis())
        // 新建/导入的笔记本一律记成当前渲染引擎版本
        meta.renderV = OfficeRender.RENDER_VERSION
        saveMeta(meta)
        return meta
    }

    fun addBlankPage(meta: NotebookMeta, style: PageStyle = PageStyle.GRID): PageMeta {
        var idx = meta.pages.size
        var id = "p$idx"
        while (meta.pages.any { it.id == id }) {
            idx++
            id = "p$idx"
        }
        val p = PageMeta(id, null, PageFactory.DEFAULT_W, PageFactory.DEFAULT_H, style)
        meta.pages.add(p)
        saveMeta(meta)
        return p
    }

    fun removePage(meta: NotebookMeta, pageId: String) {
        meta.pages.removeAll { it.id == pageId }
        deletePageFiles(meta.id, pageId)
        saveMeta(meta)
    }

    /**
     * 只从页面列表里摘掉，磁盘文件保留 —— 供「删除后撤销」使用。
     * 孤儿文件由 [pruneOrphans] 在下次打开笔记本时清理。
     */
    fun removePageSoft(meta: NotebookMeta, pageId: String) {
        meta.pages.removeAll { it.id == pageId }
        saveMeta(meta)
    }

    fun insertPage(meta: NotebookMeta, index: Int, page: PageMeta) {
        val at = index.coerceIn(0, meta.pages.size)
        meta.pages.add(at, page)
        saveMeta(meta)
    }

    private fun deletePageFiles(nbId: String, pageId: String) {
        val d = dir(nbId)
        File(d, "ink_$pageId.bin").delete()
        File(d, "bg_$pageId.png").delete()
        File(d, "bg_$pageId.jpg").delete()
    }

    /**
     * 清理没有被 meta 引用的页面文件。只删 10 分钟以前的，
     * 避免误删正在导入 / 尚未落 meta 的新页面。
     */
    fun pruneOrphans(meta: NotebookMeta) {
        try {
            val d = dir(meta.id)
            val keep = HashSet<String>()
            for (p in meta.pages) {
                keep.add("ink_${p.id}.bin")
                p.bgFile?.let { keep.add(it) }
                // 源文件（src_xxx.pdf / src_xxx.pptx）被整批页面共享，删了高清就没了
                p.src?.let { keep.add(it) }
            }
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            val files = d.listFiles() ?: return
            for (f in files) {
                val n = f.name
                if (!n.startsWith("bg_") && !n.startsWith("ink_") && !n.startsWith("src_")) continue
                if (n in keep) continue
                if (n.endsWith(".tmp")) continue
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    // ---------- 页内容 ----------

    fun inkFile(nbId: String, pageId: String) = File(dir(nbId), "ink_$pageId.bin")

    fun loadStrokes(nbId: String, pageId: String): MutableList<Stroke> =
        InkCodec.read(inkFile(nbId, pageId))

    fun saveStrokes(nbId: String, pageId: String, strokes: List<Stroke>) {
        InkCodec.write(inkFile(nbId, pageId), strokes)
    }

    fun loadPageBitmap(nb: NotebookMeta, page: PageMeta): Bitmap {
        if (page.bgFile != null) {
            val f = File(dir(nb.id), page.bgFile)
            if (f.exists()) {
                BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
            }
        }
        return PageFactory.blank(page.width, page.height, page.style)
    }

    /**
     * 只取「真·底图」。没有底图的空白页返回 null —— 调用方（画布）会自己画底纹，
     * 比加载一张 1240px 的灰格位图再放大要清楚得多，也省一次内存分配。
     */
    fun loadPageBitmapOrNull(nb: NotebookMeta, page: PageMeta): Bitmap? {
        val name = page.bgFile ?: return null
        val f = File(dir(nb.id), name)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    fun saveBitmap(f: File, bmp: Bitmap) {
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 生成封面缩略图（首页底图 + 笔迹），用于笔记本列表 */
    fun writeCover(meta: NotebookMeta) {
        val p = meta.pages.firstOrNull() ?: return
        val outW = coverTargetWidth()
        val outH = (outW.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
        val bg = loadPageBitmap(meta, p)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(bg, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), filter)
        val s = outW.toFloat() / p.width
        c.save()
        c.scale(s, s)
        val strokes = loadStrokes(meta.id, p.id)
        if (strokes.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val path = Path()
            Ink.renderAll(
                c, strokes, paint, path,
                RectF(0f, 0f, p.width.toFloat(), p.height.toFloat())
            )
        }
        c.restore()
        saveBitmap(File(dir(meta.id), "cover.png"), out)
        out.recycle()
        if (!bg.isRecycled) bg.recycle()
    }

    /**
     * 封面宽度按屏幕来定：书架是两列，每张卡约占屏宽的一半，再留 10% 余量。
     * 以前固定写 360 —— 在 1600px 宽的机器上等于把 360px 的图拉到 750px 显示，
     * 字全糊成一团，这就是「主界面看着很模糊」的来源。
     */
    fun coverTargetWidth(): Int {
        val w = ctx.resources.displayMetrics.widthPixels
        return (w * 0.5f).toInt().coerceIn(560, 960)
    }

    /** 老版本存的是 360px 封面，明显小于目标宽度就重画一张。返回是否重画过 */
    fun refreshCoverIfStale(meta: NotebookMeta): Boolean {
        val f = coverFile(meta.id)
        if (!f.exists()) {
            writeCover(meta)
            return true
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth >= coverTargetWidth() * 0.85f) return false
        writeCover(meta)
        return true
    }

    fun coverFile(id: String) = File(dir(id), "cover.png")

    // ---------- 旧底图升级 ----------

    /**
     * 用保留的源 pptx 把「旧版渲染引擎画的」底图重画一遍。
     *
     * 只替换 bg_*.png，笔迹文件一个字节都不动，所以用户的批注不会丢。
     * [onPage] 返回 false 时提前收工（用户已经退出这一页时不要继续占着 CPU）。
     * 返回重画成功的页数；有失败就返回负数，下次打开还会再试。
     */
    fun refreshStaleBackgrounds(
        meta: NotebookMeta,
        onPage: (Int) -> Boolean = { true }
    ): Int {
        val d = dir(meta.id)
        var done = 0
        var bad = 0
        for ((i, p) in meta.pages.withIndex()) {
            val src = p.src ?: continue
            if (!src.endsWith(".pptx", true) || p.srcPage < 0) continue
            val bg = p.bgFile ?: continue
            val f = File(d, src)
            if (!f.exists()) continue
            if (!onPage(i)) {
                bad++
                break
            }
            val bmp = runCatching {
                OfficeRender.renderPage(f, p.srcPage, p.width)
            }.getOrNull()
            if (bmp == null) {
                bad++
                continue
            }
            // 幻灯片尺寸被改过就跟页面坐标系对不上了，宁可留着旧图也别拉变形
            if (bmp.width != p.width || bmp.height != p.height) {
                bmp.recycle()
                bad++
                continue
            }
            saveBitmap(File(d, bg), bmp)
            bmp.recycle()
            done++
        }
        if (done > 0 && bad == 0) {
            meta.renderV = OfficeRender.RENDER_VERSION
            saveMeta(meta)
        }
        return if (bad > 0) -done else done
    }
}
