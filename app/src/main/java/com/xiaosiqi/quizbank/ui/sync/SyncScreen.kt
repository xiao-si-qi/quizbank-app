package com.xiaosiqi.quizbank.ui.sync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.importer.ImportException
import com.xiaosiqi.quizbank.model.BankListItem
import com.xiaosiqi.quizbank.ui.Routes
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.EmptyState
import com.xiaosiqi.quizbank.ui.common.ErrorDialog
import com.xiaosiqi.quizbank.ui.common.LoadingBox
import com.xiaosiqi.quizbank.ui.common.SectionCard
import com.xiaosiqi.quizbank.ui.common.TagChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncViewModel(private val container: AppContainer) : ViewModel() {

    data class ItemState(
        val item: BankListItem,
        val installed: Boolean = false,
        val working: Boolean = false,
        val done: Boolean = false,
        val failed: String? = null,
        val message: String? = null,
    )

    data class UiState(
        val loading: Boolean = false,
        val items: List<ItemState> = emptyList(),
        val warnings: List<String> = emptyList(),
        val sourceLabel: String = "",
        val status: String? = null,
        val error: String? = null,
        val configured: Boolean = false,
        val hasSource: Boolean = false,
        val lastSyncSummary: String = "",
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val s = container.settings.current()
        _state.value = UiState(
            configured = s.configured,
            hasSource = s.hasBankListSource,
            lastSyncSummary = s.lastSyncSummary,
            sourceLabel = s.listPath,
        )
    }

    fun load() {
        val s = container.settings.current()
        if (!s.configured || !s.hasBankListSource) {
            _state.update { it.copy(configured = s.configured, hasSource = s.hasBankListSource) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, busy = true) }
            runCatching { container.repository.loadBankList() }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            loading = false,
                            busy = false,
                            items = snapshot.items.map { item ->
                                ItemState(item, installed = snapshot.isInstalled(item))
                            },
                            warnings = snapshot.warnings,
                            sourceLabel = snapshot.sourceLabel,
                            lastSyncSummary = container.settings.current().lastSyncSummary,
                            status = "共 ${snapshot.items.size} 个题库",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, busy = false, error = e.message ?: "同步失败") }
                }
        }
    }

    fun download(index: Int) {
        val item = _state.value.items.getOrNull(index) ?: return
        if (item.working) return
        viewModelScope.launch {
            setItem(index) { it.copy(working = true, failed = null) }
            _state.update { it.copy(busy = true, status = "正在下载「${item.item.name}」…") }
            runCatching { container.repository.importBankItem(item.item, _state.value.sourceLabel) }
                .onSuccess { summary ->
                    setItem(index) {
                        it.copy(
                            working = false,
                            done = true,
                            installed = true,
                            message = "已导入 ${summary.questionCount} 题",
                        )
                    }
                    _state.update { it.copy(status = "「${summary.bankName}」导入完成，共 ${summary.questionCount} 题") }
                    prefetchImages(summary)
                }
                .onFailure { e ->
                    setItem(index) { it.copy(working = false, failed = e.message ?: "下载失败") }
                    _state.update { it.copy(status = null) }
                }
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun prefetchImages(summary: com.xiaosiqi.quizbank.data.repo.ImportSummary) {
        val s = container.settings.current()
        if (!s.downloadImagesWithBank || summary.imageRefs.isEmpty()) return
        _state.update { it.copy(status = "正在下载图片 0/${summary.imageRefs.size}…") }
        val result = container.repository.prefetchImages(summary) { done, total ->
            _state.update { it.copy(status = "正在下载图片 $done/$total…") }
        }
        val text = buildString {
            append("图片已缓存 ${result.ok}/${summary.imageRefs.size} 张")
            if (result.failed > 0) append("，${result.failed} 张失败")
        }
        _state.update { it.copy(status = text) }
    }

    fun downloadAll() {
        val pending = _state.value.items.withIndex().filter { !it.value.installed && !it.value.done }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            for ((index, _) in pending) {
                val item = _state.value.items.getOrNull(index) ?: continue
                setItem(index) { it.copy(working = true, failed = null) }
                _state.update { it.copy(status = "正在下载「${item.item.name}」…") }
                runCatching { container.repository.importBankItem(item.item, _state.value.sourceLabel) }
                    .onSuccess { summary ->
                        setItem(index) {
                            it.copy(working = false, done = true, installed = true, message = "已导入 ${summary.questionCount} 题")
                        }
                        prefetchImages(summary)
                    }
                    .onFailure { e ->
                        setItem(index) { it.copy(working = false, failed = e.message ?: "下载失败") }
                    }
            }
            _state.update { it.copy(busy = false, status = "全部处理完成") }
        }
    }

    fun importLocal(context: Context, uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "正在导入本地文件…") }
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = displayName(context, uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw ImportException("无法读取所选文件")
                    container.repository.importLocalFile(bytes, name)
                }
            }.onSuccess { summary ->
                _state.update { it.copy(busy = false, status = "「${summary.bankName}」导入完成，共 ${summary.questionCount} 题") }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, status = null, error = e.message ?: "导入失败") }
            }
        }
    }

    private fun setItem(index: Int, block: (ItemState) -> ItemState) {
        _state.update { current ->
            if (index !in current.items.indices) current
            else current.copy(items = current.items.toMutableList().also { it[index] = block(it[index]) })
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && c.moveToFirst()) return c.getString(index) ?: ""
            }
        }
        return uri.lastPathSegment.orEmpty()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(container: AppContainer, nav: NavController) {
    val vm: SyncViewModel = viewModel(factory = VmFactory { SyncViewModel(container) })
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLocal(context, uri)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("从 alist 获取题库") },
                actions = {
                    IconButton(onClick = vm::load, enabled = !state.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.configured || !state.hasSource -> EmptyState(
                    title = if (!state.configured) "还没有填 alist 服务器地址" else "还没有设置题库列表地址",
                    description = if (!state.configured)
                        "公开的题库不需要登录：填上服务器地址就能用。\n登录后还能看到你有权限的全部题库，并上传成绩。"
                    else
                        "在设置里填写「题库列表 Excel」的路径或直链，\n之后就能在这里一键下载题库。",
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("去设置") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("先导入本地 Excel") }
                        }
                    },
                )
                state.loading && state.items.isEmpty() -> LoadingBox("正在读取题库列表…")
                state.items.isEmpty() -> EmptyState(
                    title = "没有读到题库",
                    description = state.lastSyncSummary.ifBlank { "题库列表里没有有效的条目。" },
                    action = { Button(onClick = vm::load) { Text("重试") } },
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        SectionCard {
                            Text(state.sourceLabel, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "共 ${state.items.size} 个题库，已下载 ${state.items.count { it.done || it.installed }} 个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.status?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (state.busy) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            state.warnings.take(3).forEach {
                                Spacer(Modifier.height(4.dp))
                                Text("· $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = vm::downloadAll, enabled = !state.busy) { Text("全部下载") }
                                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                                    Text("导入本地文件")
                                }
                            }
                        }
                    }
                    items(state.items.size) { index ->
                        val item = state.items[index]
                        SyncItemRow(item = item, onDownload = { vm.download(index) })
                    }
                }
            }
        }
    }

    ErrorDialog(message = state.error, onDismiss = vm::clearError)
}

@Composable
private fun SyncItemRow(item: SyncViewModel.ItemState, onDownload: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.item.category.isNotBlank()) TagChip(item.item.category)
                    if (item.item.declaredCount > 0) {
                        Text(
                            "${item.item.declaredCount} 题",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (item.item.version.isNotBlank()) {
                        Text(
                            item.item.version,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (item.item.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.item.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                item.message?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                item.failed?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it.lineSequence().first(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            when {
                item.working -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                item.done || item.installed -> Icon(
                    Icons.Filled.Check,
                    contentDescription = "已下载",
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> Button(onClick = onDownload) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载")
                }
            }
        }
    }
}
