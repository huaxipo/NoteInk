package com.noteink.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noteink.app.ink.Ink
import com.noteink.app.ink.InkCanvasView
import com.noteink.app.model.InkTool
import com.noteink.app.model.NotebookMeta
import com.noteink.app.model.PageMeta
import com.noteink.app.model.PageStyle
import com.noteink.app.store.Exporter
import com.noteink.app.store.NotebookStore
import com.noteink.app.store.PdfHiRes
import com.noteink.app.store.PptxHiRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 顶栏固定高度。用固定值而不是靠子项撑开，避免任何测量问题导致整条栏塌成 0 */
private val TopBarHeight = 52.dp

/** 被删掉的页面快照，用于「撤销删除」 */
private class DeletedPage(val index: Int, val page: PageMeta)

@Composable
fun EditorScreen(
    store: NotebookStore,
    notebookId: String,
    appScope: CoroutineScope,
    onExit: () -> Unit
) {
    var meta by remember { mutableStateOf<NotebookMeta?>(null) }
    LaunchedEffect(notebookId) {
        meta = withContext(Dispatchers.IO) {
            val m = store.list().firstOrNull { it.id == notebookId }
            // 顺手清掉上次删页留下的孤儿文件（只删 10 分钟前的，不会误伤正在导入的页）
            m?.let { store.pruneOrphans(it) }
            m
        }
    }
    val nb = meta
    if (nb == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
        return
    }
    EditorBody(store, nb, appScope, onExit)
}

@Composable
private fun EditorBody(
    store: NotebookStore,
    nb: NotebookMeta,
    appScope: CoroutineScope,
    onExit: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val canvasRef = remember { mutableStateOf<InkCanvasView?>(null) }

    var pageIndex by remember { mutableStateOf(0) }
    var tool by remember { mutableStateOf(InkTool.PEN) }
    var color by remember { mutableStateOf(0xFF1B1B1B.toInt()) }
    var width by remember { mutableStateOf(Ink.defaultWidth(InkTool.PEN)) }
    var eraserSize by remember { mutableStateOf(28f) }
    var fingerDraw by remember { mutableStateOf(true) }
    var partialErase by remember { mutableStateOf(false) }
    var simPressure by remember { mutableStateOf(true) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var selCount by remember { mutableStateOf(0) }
    var dirty by remember { mutableStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf<DeletedPage?>(null) }

    // 所有落盘串行执行，避免同一文件被两个协程同时写
    val saveLock = remember { Mutex() }
    var exiting by remember { mutableStateOf(false) }

    fun persist(idx: Int = pageIndex) {
        if (exiting) return
        val v = canvasRef.value ?: return
        val p = nb.pages.getOrNull(idx) ?: return
        if (p.id.isEmpty()) return
        val data = v.strokesSnapshot().toList()
        appScope.launch {
            saveLock.withLock {
                // 退出流程已经写过最新快照，这里跳过，避免旧快照覆盖新内容
                if (exiting) return@withLock
                store.saveStrokes(nb.id, p.id, data)
            }
        }
    }

    fun loadInto(v: InkCanvasView, idx: Int) {
        val p = nb.pages.getOrNull(idx) ?: return
        // 空白页不给位图，让画布每帧现画底纹（放大也锐利）；有底图才解码
        val bmp = store.loadPageBitmapOrNull(nb, p)
        v.setPage(bmp, p.width, p.height, p.style)
        // 有源文件就带上矢量源：放大时按视口重渲染，文字不会糊
        val sharp = p.src?.let { name ->
            if (p.srcPage < 0) null
            else {
                val f = java.io.File(store.dir(nb.id), name)
                if (!f.exists()) null
                else if (name.endsWith(".pptx", true)) {
                    PptxHiRes(f, p.srcPage, p.width, p.height) { v.post { v.invalidate() } }
                } else {
                    PdfHiRes(f, p.srcPage, p.width, p.height) { v.post { v.invalidate() } }
                }
            }
        }
        v.setHiRes(sharp)
        v.loadStrokes(store.loadStrokes(nb.id, p.id))
        pageIndex = idx
        canUndo = false
        canRedo = false
        selCount = 0
    }

    fun goto(idx: Int) {
        if (idx == pageIndex || idx < 0 || idx >= nb.pages.size) return
        persist(pageIndex)
        canvasRef.value?.let { loadInto(it, idx) }
    }

    // 旧版渲染引擎画的底图：用保留的源 pptx 重画一遍，用户不必删掉重导入。
    // 只换底图不动笔迹，所以这一页刚写的字不会丢。
    LaunchedEffect(nb.id) {
        if (nb.renderV >= com.noteink.app.store.OfficeRender.RENDER_VERSION) return@LaunchedEffect
        if (nb.pages.none { it.src?.endsWith(".pptx", true) == true }) {
            nb.renderV = com.noteink.app.store.OfficeRender.RENDER_VERSION
            withContext(Dispatchers.IO) { runCatching { store.saveMeta(nb) } }
            return@LaunchedEffect
        }
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val n = withContext(Dispatchers.IO) {
            store.refreshStaleBackgrounds(nb) { i ->
                // 正好重画到当前这一页，就立刻换上，不用等 127 页全部跑完
                if (i == pageIndex) {
                    val v = canvasRef.value
                    val p = nb.pages.getOrNull(i)
                    if (v != null && p != null) {
                        v.post {
                            store.loadPageBitmapOrNull(nb, p)?.let { v.replaceBackground(it) }
                        }
                    }
                }
                // 用户已经退出这一页就别再继续占 CPU 了
                job?.isActive ?: true
            }
        }
        if (n != 0) {
            dirty++
            // 再兜一次：开头画布可能还没挂上，这时当前页的底图要补换一次
            val v = canvasRef.value
            val p = nb.pages.getOrNull(pageIndex)
            if (v != null && p != null) {
                store.loadPageBitmapOrNull(nb, p)?.let { v.replaceBackground(it) }
            }
        }
    }

    fun close() {
        if (exiting) return
        exiting = true
        val v = canvasRef.value
        val p = nb.pages.getOrNull(pageIndex)
        val data = v?.strokesSnapshot()?.toList()
        // 元信息同步写：保证回到列表时页数 / 时间戳已经是最新的
        runCatching { store.saveMeta(nb) }
        // 笔迹与封面交给应用级协程，界面销毁也不会被取消
        appScope.launch {
            saveLock.withLock {
                if (data != null && p != null) store.saveStrokes(nb.id, p.id, data)
                store.writeCover(nb)
                store.pruneOrphans(nb)
            }
        }
        onExit()
    }

    fun selectTool(t: InkTool) {
        tool = t
        if (t.isInk) width = Ink.defaultWidth(t)
        if (t == InkTool.HIGHLIGHTER) color = Ink.highlighterPalette[0]
        val v = canvasRef.value
        v?.tool = t
        if (t.isInk) v?.strokeWidth = width
        v?.color = color
    }

    fun deleteCurrentPage() {
        if (nb.pages.size <= 1) return
        val v = canvasRef.value ?: return
        val idx = pageIndex
        val page = nb.pages.getOrNull(idx) ?: return
        store.saveStrokes(nb.id, page.id, v.strokesSnapshot().toList())
        store.removePageSoft(nb, page.id)
        deleted = DeletedPage(idx, page)
        loadInto(v, idx.coerceAtMost(nb.pages.size - 1))
    }

    fun undoDelete() {
        val d = deleted ?: return
        deleted = null
        store.insertPage(nb, d.index, d.page)
        canvasRef.value?.let { loadInto(it, d.index) }
    }

    // ---------- 导出 ----------
    val ctx = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }

    /** 导出前把当前页的笔迹同步落盘，保证导出内容是最新的 */
    fun flushNow() {
        val v = canvasRef.value ?: return
        val p = nb.pages.getOrNull(pageIndex) ?: return
        runCatching { store.saveStrokes(nb.id, p.id, v.strokesSnapshot().toList()) }
        runCatching { store.saveMeta(nb) }
    }

    fun afterExport(ok: Boolean, what: String) {
        appScope.launch {
            withContext(Dispatchers.Main) {
                exporting = false
                Toast.makeText(ctx, if (ok) "已导出$what" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        exportText = "正在导出 PDF…"
        appScope.launch {
            val ok = runCatching {
                flushNow()
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    Exporter.exportPdf(store, nb, os)
                } ?: throw IllegalStateException("无法写入文件")
            }.isSuccess
            afterExport(ok, " PDF")
        }
    }

    val pngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        exportText = "正在导出图片…"
        appScope.launch {
            val ok = runCatching {
                flushNow()
                val p = nb.pages.getOrNull(pageIndex) ?: throw IllegalStateException("页面不存在")
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    Exporter.exportPagePng(store, nb, p, os)
                } ?: throw IllegalStateException("无法写入文件")
            }.isSuccess
            afterExport(ok, "图片")
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        exportText = "正在打包备份…"
        appScope.launch {
            val ok = runCatching {
                flushNow()
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    Exporter.backup(store, nb, os)
                } ?: throw IllegalStateException("无法写入文件")
            }.isSuccess
            afterExport(ok, "备份")
        }
    }


    // 自动保存（停止书写 0.8s 后落盘）
    LaunchedEffect(dirty) {
        if (dirty > 0) {
            delay(800)
            persist(pageIndex)
        }
    }

    // 「已删除 · 撤销」浮条 5 秒后自动消失
    LaunchedEffect(deleted) {
        if (deleted != null) {
            delay(5000)
            deleted = null
        }
    }

    // 切到后台时保存
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) persist(pageIndex)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            // 退出流程已经存过了，避免重复写同一文件
            if (!exiting) persist(pageIndex)
        }
    }

    BackHandler { close() }

    Box(Modifier.fillMaxSize().background(Color(0xFFEDEAE3))) {
        Column(Modifier.fillMaxSize()) {
            // 给顶栏留出高度
            Spacer(Modifier.height(TopBarHeight))

            // ---------- 画布 ----------
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { c ->
                        InkCanvasView(c).also { v ->
                            v.onStrokesChanged = { dirty++ }
                            v.onHistoryChanged = { u, r -> canUndo = u; canRedo = r }
                            v.onSelectionChanged = { selCount = it }
                            canvasRef.value = v
                            v.post { loadInto(v, pageIndex) }
                        }
                    },
                    update = { v ->
                        v.tool = tool
                        v.color = color
                        v.strokeWidth = width
                        v.eraserSize = eraserSize
                        v.fingerDraw = fingerDraw
                        v.partialErase = partialErase
                        v.simulatePressure = simPressure
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 底部胶囊：撤销 + 翻页 + 重做（拇指可达，不依赖顶栏）
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shadowElevation = 2.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CapsuleButton(
                            enabled = canUndo,
                            onClick = { canvasRef.value?.undo() }
                        ) { t -> historySymbol(t, redo = false) }
                        Box(
                            Modifier
                                .clickable { goto(pageIndex - 1) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text("‹", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface) }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showJump = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "${pageIndex + 1} / ${nb.pages.size}",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            Modifier
                                .clickable { goto(pageIndex + 1) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface) }
                        CapsuleButton(
                            enabled = canRedo,
                            onClick = { canvasRef.value?.redo() }
                        ) { t -> historySymbol(t, redo = true) }
                    }
                }

                // 删除页面的撤销浮条
                deleted?.let { d ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 62.dp),
                        shape = CircleShape,
                        color = Color(0xF02A2A2A),
                        shadowElevation = 3.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已删除第 ${d.index + 1} 页",
                                fontSize = 11.5.sp,
                                color = Color.White,
                                modifier = Modifier.padding(start = 14.dp, end = 4.dp)
                            )
                            TextButton(onClick = { undoDelete() }) {
                                Text("撤销", fontSize = 11.5.sp, color = Color(0xFF8FC3FF))
                            }
                        }
                    }
                }

                // 选区操作条
                if (selCount > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 3.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已选 $selCount 笔",
                                fontSize = 11.sp,
                                color = Color.White,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                            TextButton(onClick = {
                                canvasRef.value?.deleteSelection()
                                selCount = 0
                            }) { Text("删除", fontSize = 11.sp, color = Color.White) }
                            TextButton(onClick = {
                                canvasRef.value?.clearSelection()
                                selCount = 0
                            }) { Text("取消", fontSize = 11.sp, color = Color(0xCCFFFFFF)) }
                        }
                    }
                }
            }

            // ---------- 底部工具栏 ----------
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InkTool.values().forEach { t ->
                            ToolButton(t, tool == t) { selectTool(t) }
                        }
                    }

                    if (tool.isInk) {
                        val palette =
                            if (tool == InkTool.HIGHLIGHTER) Ink.highlighterPalette else Ink.palette
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(palette) { c ->
                                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                    ColorDot(c, color == c, 30.dp) { color = c }
                                }
                            }
                            item {
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clickable { showPicker = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier
                                            .size(26.dp)
                                            .background(
                                                androidx.compose.ui.graphics.Brush.sweepGradient(
                                                    listOf(
                                                        Color(0xFFE85D5D), Color(0xFFE8C55D),
                                                        Color(0xFF5DC97F), Color(0xFF5DB4E8),
                                                        Color(0xFF8B5DE8), Color(0xFFE85D9E),
                                                        Color(0xFFE85D5D)
                                                    )
                                                ),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("+", fontSize = 13.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when {
                                tool == InkTool.ERASER -> "大小"
                                tool == InkTool.LASSO -> "圈选后拖动"
                                tool == InkTool.LASSO_ERASE -> "圈住的笔整笔删除"
                                else -> "粗细"
                            },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        if (tool == InkTool.ERASER) {
                            Slider(
                                value = eraserSize,
                                onValueChange = {
                                    eraserSize = it
                                    canvasRef.value?.eraserSize = it
                                },
                                valueRange = 6f..70f,
                                modifier = Modifier.weight(1f)
                            )
                        } else if (tool.isInk) {
                            Slider(
                                value = width,
                                onValueChange = {
                                    width = it
                                    canvasRef.value?.strokeWidth = it
                                },
                                valueRange = when (tool) {
                                    InkTool.HIGHLIGHTER -> 8f..80f
                                    InkTool.MARKER -> 3f..40f
                                    else -> 1f..12f
                                },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = {
                            fingerDraw = !fingerDraw
                            canvasRef.value?.fingerDraw = fingerDraw
                        }) {
                            Text(
                                if (fingerDraw) "手指可写" else "手指平移",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (tool == InkTool.ERASER) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 10.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "擦除方式",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            TinyChip("整笔", !partialErase) {
                                partialErase = false
                                canvasRef.value?.partialErase = false
                            }
                            Spacer(Modifier.width(6.dp))
                            TinyChip("局部", partialErase) {
                                partialErase = true
                                canvasRef.value?.partialErase = true
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (partialErase) "像真橡皮，只擦掉笔迹的一部分"
                                else "碰到就整笔删除，最省资源",
                                fontSize = 9.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---------- 顶栏 ----------
        // 放在 Column 之后，作为覆盖层绘制：无论画布（原生 View）如何布局，
        // 它都压在最上层，不会出现「被画布盖住」或「高度塌成 0」的情况。
        EditorTopBar(
            title = nb.title,
            pageText = "第 ${pageIndex + 1} / ${nb.pages.size} 页",
            canUndo = canUndo,
            canRedo = canRedo,
            onBack = { close() },
            onUndo = { canvasRef.value?.undo() },
            onRedo = { canvasRef.value?.redo() },
            onMore = { showMore = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(TopBarHeight)
        )
    }

    if (showPicker) {
        HsvColorDialog(
            initial = color,
            onDismiss = { showPicker = false },
            onPick = { color = it }
        )
    }

    if (showJump) {
        JumpPageDialog(
            pageCount = nb.pages.size,
            current = pageIndex,
            onDismiss = { showJump = false },
            onGo = { idx ->
                showJump = false
                goto(idx)
            }
        )
    }

    if (showMore) {
        AlertDialog(
            onDismissRequest = { showMore = false },
            title = { Text("更多") },
            text = {
                Column {
                    Text(
                        "页面",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        showMore = false
                        persist(pageIndex)
                        store.addBlankPage(nb, PageStyle.GRID)
                        canvasRef.value?.let { loadInto(it, nb.pages.size - 1) }
                    }) { Text("在后面插入空白页") }
                    TextButton(onClick = {
                        showMore = false
                        showJump = true
                    }) { Text("跳转到指定页（共 ${nb.pages.size} 页）") }
                    TextButton(onClick = {
                        showMore = false
                        canvasRef.value?.fitToPage()
                    }) { Text("页面适配屏幕") }
                    TextButton(onClick = {
                        showMore = false
                        deleteCurrentPage()
                    }) {
                        Text("删除当前页（可撤销）", color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "导出",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        showMore = false
                        pngLauncher.launch(safeName(nb.title) + ".png")
                    }) { Text("导出本页为图片（PNG）") }
                    TextButton(onClick = {
                        showMore = false
                        pdfLauncher.launch(safeName(nb.title) + ".pdf")
                    }) { Text("导出整本为 PDF（${nb.pages.size} 页）") }
                    TextButton(onClick = {
                        showMore = false
                        zipLauncher.launch(safeName(nb.title) + ".noteink.zip")
                    }) { Text("打包备份（含笔迹，可恢复）") }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "书写",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        simPressure = !simPressure
                        canvasRef.value?.simulatePressure = simPressure
                    }) {
                        Text(
                            if (simPressure) "压感模拟：开（快细慢粗）"
                            else "压感模拟：关（等宽）"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMore = false }) { Text("关闭") }
            }
        )
    }

    if (exporting) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x88000000)),
            contentAlignment = Alignment.Center
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(exportText, fontSize = 12.5.sp)
                }
            }
        }
    }
}

/** 文件名里去掉会破坏 SAF 的字符 */
private fun safeName(s: String): String {
    val t = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim()
    return t.ifBlank { "noteink" }.take(80)
}

// ================= 跳转到指定页 =================

/**
 * 页码输入 + 拖动条。127 页的课件靠翻页键翻到第 60 页要按 59 次，
 * 所以底部页码可点、菜单里也放一个入口。
 */
@Composable
private fun JumpPageDialog(
    pageCount: Int,
    current: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit
) {
    var text by remember { mutableStateOf((current + 1).toString()) }
    val target = text.toIntOrNull()
    val valid = target != null && target in 1..pageCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到页面") },
        text = {
            Column {
                Text(
                    "共 $pageCount 页，输入页码直接跳转",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { s ->
                        if (s.length <= 4 && s.all { it.isDigit() }) text = s
                    },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (pageCount > 1) {
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = (target ?: (current + 1)).coerceIn(1, pageCount).toFloat(),
                        onValueChange = { v -> text = v.toInt().coerceIn(1, pageCount).toString() },
                        valueRange = 1f..pageCount.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { target?.let { onGo((it - 1).coerceIn(0, pageCount - 1)) } }
            ) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ================= 顶栏 =================

@Composable
private fun EditorTopBar(
    title: String,
    pageText: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton44(onClick = onBack) { t -> backSymbol(t) }
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    pageText,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton44(enabled = canUndo, onClick = onUndo) { t -> historySymbol(t, redo = false) }
            IconButton44(enabled = canRedo, onClick = onRedo) { t -> historySymbol(t, redo = true) }
            IconButton44(onClick = onMore) { t -> moreSymbol(t) }
        }
    }
}

// ================= 自绘图标 =================

@Composable
private fun IconButton44(
    enabled: Boolean = true,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit
) {
    GlyphBox(size = 44.dp, glyphSize = 21.dp, enabled = enabled, onClick = onClick, glyph = glyph)
}

/** 底部胶囊里的图标按钮，尺寸略小 */
@Composable
private fun CapsuleButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit
) {
    GlyphBox(size = 36.dp, glyphSize = 17.dp, enabled = enabled, onClick = onClick, glyph = glyph)
}

@Composable
private fun GlyphBox(
    size: Dp,
    glyphSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(glyphSize)) { glyph(tint) }
    }
}

/** 返回箭头（‹） */
private fun DrawScope.backSymbol(tint: Color) {
    val w = size.width
    val h = size.height
    drawPath(
        Path().apply {
            moveTo(w * 0.62f, h * 0.18f)
            lineTo(w * 0.34f, h * 0.50f)
            lineTo(w * 0.62f, h * 0.82f)
        },
        tint,
        style = Stroke(
            width = (w * 0.12f).coerceIn(1.6f, 2.6f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

/** 撤销 / 重做：上方一段圆弧 + 左端箭头，redo 时整体水平镜像 */
private fun DrawScope.historySymbol(tint: Color, redo: Boolean) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h * 0.58f
    val r = min(w, h) * 0.31f
    val sw = (w * 0.10f).coerceIn(1.4f, 2.4f)
    val stroke = Stroke(width = sw, cap = StrokeCap.Round)

    val body: DrawScope.() -> Unit = {
        drawArc(
            color = tint,
            startAngle = 200f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = stroke
        )
        val a = Math.toRadians(200.0)
        val px = cx + r * cos(a).toFloat()
        val py = cy + r * sin(a).toFloat()
        // 圆弧在该点的切线方向（角度增大方向）
        val tx = -sin(a).toFloat()
        val ty = cos(a).toFloat()
        val nx = -ty
        val ny = tx
        val len = r * 0.58f
        val half = r * 0.34f
        drawPath(
            Path().apply {
                moveTo(px - tx * len, py - ty * len)
                lineTo(px + nx * half, py + ny * half)
                lineTo(px - nx * half, py - ny * half)
                close()
            },
            tint
        )
    }

    if (redo) {
        scale(-1f, 1f, pivot = Offset(cx, cy)) { body() }
    } else {
        body()
    }
}

/** 更多（三点） */
private fun DrawScope.moreSymbol(tint: Color) {
    val w = size.width
    val h = size.height
    val r = w * 0.085f
    val cy = h * 0.5f
    for (f in floatArrayOf(0.24f, 0.5f, 0.76f)) {
        drawCircle(tint, r, Offset(w * f, cy))
    }
}

@Composable
private fun ToolButton(t: InkTool, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ToolGlyph(t, fg, 21.dp)
        Spacer(Modifier.height(2.dp))
        Text(t.label, fontSize = 9.sp, color = fg)
    }
}

@Composable
private fun TinyChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text,
            fontSize = 10.5.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
