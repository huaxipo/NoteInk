package com.noteink.app.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noteink.app.BuildConfig
import com.noteink.app.model.NotebookMeta
import com.noteink.app.model.PageStyle
import com.noteink.app.store.Exporter
import com.noteink.app.store.Importers
import com.noteink.app.store.NotebookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    store: NotebookStore,
    refreshKey: Int,
    onOpen: (String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var notebooks by remember { mutableStateOf<List<NotebookMeta>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<NotebookMeta?>(null) }
    var renameFor by remember { mutableStateOf<NotebookMeta?>(null) }
    var pdfFor by remember { mutableStateOf<NotebookMeta?>(null) }
    var pngDirFor by remember { mutableStateOf<NotebookMeta?>(null) }
    var zipFor by remember { mutableStateOf<NotebookMeta?>(null) }
    /** 封面重画过的代数：变了就让卡片重新解码（老版本存的是 360px 封面） */
    var coverRev by remember { mutableStateOf(0) }

    suspend fun upgradeCovers() {
        val changed = withContext(Dispatchers.IO) {
            var any = false
            for (n in notebooks) if (store.refreshCoverIfStale(n)) any = true
            any
        }
        if (changed) coverRev++
    }

    suspend fun reload() {
        busy = false
        notebooks = withContext(Dispatchers.IO) { store.list() }
        upgradeCovers()
    }

    LaunchedEffect(refreshKey) {
        notebooks = withContext(Dispatchers.IO) { store.list() }
        upgradeCovers()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            busyText = "正在准备导入…"
            val errors = ArrayList<String>()
            withContext(Dispatchers.IO) {
                uris.forEachIndexed { i, u ->
                    val name = Importers.queryName(ctx, u, "导入文件")
                    val title = name.substringBeforeLast('.').ifBlank { "导入笔记" }
                    val prefix = if (uris.size > 1) "(${i + 1}/${uris.size}) " else ""
                    mainHandler.post { busyText = "${prefix}正在导入：$name" }
                    val nb = store.create(title, PageStyle.BLANK, 0)
                    try {
                        val n = Importers.importInto(ctx, store, nb, u, name) { done, total ->
                            mainHandler.post {
                                busyText = "${prefix}正在导入：$name\n$done / $total 页"
                            }
                        }
                        if (n <= 0) {
                            store.delete(nb.id)
                            errors.add("$name：没有可用页面")
                        } else {
                            store.saveMeta(nb)
                            store.writeCover(nb)
                        }
                    } catch (t: Throwable) {
                        store.delete(nb.id)
                        errors.add("$name：${t.message ?: "导入失败"}")
                    }
                }
            }
            busyText = "完成"
            reload()
            if (errors.isNotEmpty()) {
                Toast.makeText(ctx, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 导出 / 备份 / 恢复 ----------

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val nb = pdfFor
        pdfFor = null
        if (uri != null && nb != null) {
            scope.launch {
                busy = true
                busyText = "正在导出 PDF…"
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use { os ->
                            Exporter.exportPdf(store, nb, os) { d, t ->
                                mainHandler.post { busyText = "正在导出 PDF…\n$d / $t 页" }
                            }
                        } ?: throw IllegalStateException("无法写入文件")
                    }.isSuccess
                }
                busy = false
                Toast.makeText(ctx, if (ok) "已导出 PDF" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pngDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val nb = pngDirFor
        pngDirFor = null
        if (uri != null && nb != null) {
            scope.launch {
                busy = true
                busyText = "正在导出图片…"
                val n = withContext(Dispatchers.IO) {
                    runCatching {
                        Exporter.exportAllPng(ctx, store, nb, uri) { d, t ->
                            mainHandler.post { busyText = "正在导出图片…\n$d / $t 页" }
                        }
                    }.getOrDefault(0)
                }
                busy = false
                Toast.makeText(
                    ctx,
                    if (n > 0) "已导出 $n 张图片" else "导出失败，换个目录再试",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val nb = zipFor
        zipFor = null
        if (uri != null && nb != null) {
            scope.launch {
                busy = true
                busyText = "正在打包备份…"
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use { os ->
                            Exporter.backup(store, nb, os)
                        } ?: throw IllegalStateException("无法写入文件")
                    }.isSuccess
                }
                busy = false
                Toast.makeText(ctx, if (ok) "备份已保存" else "备份失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                busyText = "正在恢复备份…"
                val nb = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use {
                            Exporter.restore(ctx, store, it)
                        }
                    }.getOrNull()
                }
                busy = false
                reload()
                Toast.makeText(
                    ctx,
                    if (nb != null) "已恢复：${nb.title}" else "这不是有效的备份文件",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // 顶部标题
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "墨记",
                        fontSize = 27.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        if (notebooks.isEmpty()) "手写笔记 · 支持 PDF / Office / 图片导入  ·  v${BuildConfig.VERSION_NAME}"
                        else "${notebooks.size} 个笔记本  ·  v${BuildConfig.VERSION_NAME}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }) {
                    Text("恢复备份", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (notebooks.isEmpty() && !busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "还没有笔记",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点右下角 + 新建，或导入一份 PDF 试试手写批注",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(notebooks, key = { it.id }) { nb ->
                        NotebookCard(
                            store = store,
                            nb = nb,
                            rev = coverRev,
                            onClick = { onOpen(nb.id) },
                            onMenu = { menuFor = nb }
                        )
                    }
                }
            }
        }

        if (busy) {
            Surface(
                color = Color(0xCC000000),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(14.dp))
                        Text(busyText, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showNew = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(22.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Text("+", fontSize = 26.sp, color = Color.White)
        }

        // 新建对话框
        if (showNew) {
            NewNotebookDialog(
                onDismiss = { showNew = false },
                onCreate = { title, style ->
                    showNew = false
                    scope.launch {
                        val nb = withContext(Dispatchers.IO) {
                            val n = store.create(title, style, 1)
                            store.writeCover(n)
                            n
                        }
                        reload()
                        onOpen(nb.id)
                    }
                },
                onImport = {
                    showNew = false
                    importLauncher.launch(
                        arrayOf(
                            "application/pdf",
                            "image/*",
                            "text/plain",
                            "text/markdown",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/octet-stream"
                        )
                    )
                }
            )
        }

        // 更多菜单
        menuFor?.let { nb ->
            AlertDialog(
                onDismissRequest = { menuFor = null },
                title = { Text(nb.title) },
                text = {
                    Column {
                        TextButton(onClick = { menuFor = null; renameFor = nb }) {
                            Text("重命名")
                        }
                        TextButton(onClick = {
                            menuFor = null
                            pdfFor = nb
                            pdfLauncher.launch(fileName(nb.title, ".pdf"))
                        }) {
                            Text("导出整本 PDF（${nb.pages.size} 页）")
                        }
                        TextButton(onClick = {
                            menuFor = null
                            pngDirFor = nb
                            pngDirLauncher.launch(null)
                        }) {
                            Text("导出为图片（选一个目录）")
                        }
                        TextButton(onClick = {
                            menuFor = null
                            zipFor = nb
                            zipLauncher.launch(fileName(nb.title, ".noteink.zip"))
                        }) {
                            Text("打包备份（含笔迹）")
                        }
                        TextButton(onClick = {
                            menuFor = null
                            scope.launch {
                                withContext(Dispatchers.IO) { store.delete(nb.id) }
                                reload()
                            }
                        }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { menuFor = null }) { Text("关闭") }
                }
            )
        }

        // 重命名
        renameFor?.let { nb ->
            var name by remember(nb.id) { mutableStateOf(nb.title) }
            AlertDialog(
                onDismissRequest = { renameFor = null },
                title = { Text("重命名") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = name.trim()
                        renameFor = null
                        if (t.isNotEmpty()) {
                            scope.launch {
                                nb.title = t
                                withContext(Dispatchers.IO) { store.saveMeta(nb) }
                                reload()
                            }
                        }
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { renameFor = null }) { Text("取消") }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    store: NotebookStore,
    nb: NotebookMeta,
    rev: Int,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    val coverPath = remember(nb.id, nb.updatedAt, rev) { store.coverFile(nb.id) }
    val bmp = remember(coverPath.absolutePath, nb.updatedAt, rev) {
        decodeSampled(coverPath, store.coverTargetWidth())
    }
    val df = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Column(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = nb.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空白", color = Color(0xFFB9B4A8), fontSize = 12.sp)
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                TextButton(onClick = onMenu, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                    Text("⋮", fontSize = 15.sp, color = Color(0xFF8A8578))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            nb.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
        Text(
            "${nb.pages.size} 页 · ${df.format(Date(nb.updatedAt))}",
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NewNotebookDialog(
    onDismiss: () -> Unit,
    onCreate: (String, PageStyle) -> Unit,
    onImport: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(PageStyle.GRID) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建笔记本") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("页面样式", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageStyle.values().forEach { s ->
                        Surface(
                            color = if (style == s) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.combinedClickableNoRipple { style = s }
                        ) {
                            Text(
                                s.label,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = if (style == s) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onImport) {
                    Text("或者：导入文件（PDF / Office / 图片 / 文本）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(title.trim().ifEmpty { "未命名笔记" }, style)
            }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun Modifier.combinedClickableNoRipple(onClick: () -> Unit): Modifier =
    this
        .clip(RoundedCornerShape(8.dp))
        .clickable { onClick() }

/** 系统文件选择器里显示的默认文件名 */
private fun fileName(title: String, ext: String): String {
    val t = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim()
    return t.ifBlank { "noteink" }.take(80) + ext
}
