package com.noteink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.noteink.app.store.NotebookStore
import com.noteink.app.ui.EditorScreen
import com.noteink.app.ui.LibraryScreen
import com.noteink.app.ui.NoteInkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    /**
     * 与界面生命周期解绑的 IO 作用域。
     * 退出编辑器时界面会立刻销毁，若把落盘交给 rememberCoroutineScope，
     * 协程会被取消导致最后几笔丢失 —— 所以保存必须挂在这个作用域上。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = NotebookStore(applicationContext)
        setContent {
            NoteInkTheme {
                var openId by remember { mutableStateOf<String?>(null) }
                var refresh by remember { mutableStateOf(0) }
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val id = openId
                    if (id == null) {
                        LibraryScreen(store, refresh) { openId = it }
                    } else {
                        EditorScreen(store, id, appScope) {
                            openId = null
                            refresh++
                        }
                    }
                }
            }
        }
    }
}
