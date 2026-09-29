package com.xiaosiqi.quizbank.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.alist.AlistException
import com.xiaosiqi.quizbank.alist.AlistFsItem
import com.xiaosiqi.quizbank.ui.Routes
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.EmptyState
import com.xiaosiqi.quizbank.ui.common.ErrorDialog
import com.xiaosiqi.quizbank.ui.common.LoadingBox
import com.xiaosiqi.quizbank.ui.common.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 浏览 alist 目录，用来挑「题库列表文件」或「成绩上传目录」。 */
class BrowserViewModel(
    private val container: AppContainer,
    private val target: String,
) : ViewModel() {

    data class UiState(
        /** 进入本页时的起始目录；退到这里再按返回才离开本页。 */
        val startPath: String = "/",
        val path: String = "/",
        val loading: Boolean = true,
        val entries: List<AlistFsItem> = emptyList(),
        val error: String? = null,
        val needsLogin: Boolean = false,
        val picked: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val current = container.settings.current()
        val start = when {
            target == TARGET_LIST && current.listPath.startsWith("/") -> current.listPath.substringBeforeLast('/', "/")
            target == TARGET_SCORE && current.scoreUploadPath.startsWith("/") -> current.scoreUploadPath
            else -> "/"
        }
        _state.update { it.copy(startPath = start.ifBlank { "/" }) }
        open(start.ifBlank { "/" })
    }

    fun open(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, needsLogin = false, path = path) }
            runCatching { container.session.withAuth { it.list(path) } }
                .onSuccess { items ->
                    _state.update { it.copy(loading = false, entries = items) }
                }
                .onFailure { e ->
                    // 匿名访问遇到需要登录的目录时，给一个明确的下一步，而不是一条红字报错
                    val denied = e is AlistException && (e.code == 401 || e.code == 403)
                    _state.update {
                        it.copy(
                            loading = false,
                            needsLogin = denied,
                            error = if (denied) null else e.message ?: "读取目录失败",
                        )
                    }
                }
        }
    }

    fun up() {
        val current = _state.value.path.trimEnd('/')
        if (current.isEmpty() || current == "/") return
        open(current.substringBeforeLast('/').ifBlank { "/" })
    }

    fun chooseFile(item: AlistFsItem) {
        val full = join(_state.value.path, item.name)
        if (item.isDir) {
            open(full)
            return
        }
        if (target == TARGET_LIST) {
            container.settings.edit { it.copy(listPath = full) }
            _state.update { it.copy(picked = full) }
        }
    }

    fun chooseCurrentDir() {
        val path = _state.value.path
        when (target) {
            TARGET_SCORE -> container.settings.edit { it.copy(scoreUploadPath = path) }
            TARGET_LIST -> {
                val current = container.settings.current()
                if (current.listPath.isBlank()) return
            }
        }
        _state.update { it.copy(picked = path) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun join(dir: String, name: String): String {
        val base = dir.trimEnd('/')
        return if (base.isEmpty()) "/$name" else "$base/$name"
    }

    companion object {
        const val TARGET_LIST = "list"
        const val TARGET_SCORE = "score"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(container: AppContainer, nav: NavController, target: String) {
    val vm: BrowserViewModel = viewModel(factory = VmFactory { BrowserViewModel(container, target) })
    val state by vm.state.collectAsState()

    LaunchedEffect(state.picked) {
        if (state.picked != null) nav.popBackStack()
    }

    // 系统返回键：先进来的那层目录才算「本页」，在子目录里按返回先回上级目录
    BackHandler(
        enabled = state.path.trimEnd('/') != state.startPath.trimEnd('/') &&
            state.path.trimEnd('/').isNotEmpty() && state.path.trimEnd('/') != "",
    ) { vm.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(if (target == BrowserViewModel.TARGET_SCORE) "选择成绩目录" else "选择题库列表文件")
                        Text(
                            state.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::up) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一级")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox("正在读取目录…")
                state.needsLogin -> EmptyState(
                    title = "这个目录需要登录",
                    description = "现在是匿名访问，只能看到 alist 上公开的内容。\n登录后就能浏览你有权限的目录。",
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("去登录") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.open("/") }) { Text("回到根目录") }
                        }
                    },
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        SectionCard {
                            Text(
                                if (target == BrowserViewModel.TARGET_SCORE)
                                    "选好目录后点下面的按钮，成绩就会上传到这里。"
                                else "点一个 Excel 文件作为题库列表；也可以直接进入某个目录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (target == BrowserViewModel.TARGET_SCORE) {
                                    Button(onClick = vm::chooseCurrentDir) {
                                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("用当前目录")
                                    }
                                }
                                OutlinedButton(onClick = { vm.open("/") }) { Text("回到根目录") }
                            }
                        }
                    }
                    if (state.entries.isEmpty()) {
                        item {
                            EmptyState(title = "这个目录是空的", description = "换个目录看看。")
                        }
                    }
                    items(state.entries) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDir) vm.open(joinPath(state.path, entry.name))
                                    else vm.chooseFile(entry)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (entry.isDir) "📁" else fileEmoji(entry.name),
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!entry.isDir && entry.size > 0) {
                                Text(
                                    humanSize(entry.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ErrorDialog(message = state.error, onDismiss = vm::clearError)
}

private fun joinPath(dir: String, name: String): String {
    val base = dir.trimEnd('/')
    return if (base.isEmpty()) "/$name" else "$base/$name"
}

private fun fileEmoji(name: String): String = when {
    name.endsWith(".xlsx", true) || name.endsWith(".xls", true) -> "📊"
    name.endsWith(".csv", true) -> "📄"
    name.endsWith(".json", true) -> "🧾"
    else -> "📄"
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
