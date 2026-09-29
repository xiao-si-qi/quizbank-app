package com.xiaosiqi.quizbank.ui.home

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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.alist.AlistAccount
import com.xiaosiqi.quizbank.data.prefs.AppSettings
import com.xiaosiqi.quizbank.data.repo.BankListSnapshot
import com.xiaosiqi.quizbank.data.repo.ImportSummary
import com.xiaosiqi.quizbank.importer.ImportException
import com.xiaosiqi.quizbank.model.Bank
import com.xiaosiqi.quizbank.model.BankProgress
import com.xiaosiqi.quizbank.model.BankStats
import com.xiaosiqi.quizbank.model.OverallStats
import com.xiaosiqi.quizbank.model.PracticeMode
import com.xiaosiqi.quizbank.ui.CollectionKind
import com.xiaosiqi.quizbank.ui.Routes
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.EmptyState
import com.xiaosiqi.quizbank.ui.common.ErrorDialog
import com.xiaosiqi.quizbank.ui.common.LoadingBox
import com.xiaosiqi.quizbank.ui.common.MessageDialog
import com.xiaosiqi.quizbank.ui.common.SectionCard
import com.xiaosiqi.quizbank.ui.common.TagChip
import com.xiaosiqi.quizbank.ui.common.ThinProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    data class BankCard(val bank: Bank, val stats: BankStats, val progress: BankProgress?)

    data class UiState(
        val loading: Boolean = true,
        val cards: List<BankCard> = emptyList(),
        val overall: OverallStats? = null,
        val account: AlistAccount? = null,
        val loggedIn: Boolean = false,
        /** 首次启动自动获取在线题库的过程 */
        val bootstrapping: Boolean = false,
        val bootstrapStatus: String? = null,
        val bootstrapFailed: Boolean = false,
        /** 用了内置题库兜底 */
        val fallbackUsed: Boolean = false,
        val bannerDismissed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    init {
        refresh()
        bootstrapIfNeeded()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.cards.isEmpty()) }
            val data = withContext(Dispatchers.IO) {
                val cards = container.store.banks().map { bank ->
                    BankCard(bank, container.store.stats(bank.id), container.store.progress(bank.id))
                }
                Triple(cards, container.store.overallStats(14), container.session.cachedAccount())
            }
            _state.update {
                it.copy(
                    loading = false,
                    cards = data.first,
                    overall = data.second,
                    account = data.third,
                    loggedIn = container.settings.current().loggedIn,
                )
            }
        }
    }

    /** 重新检查登录态并刷新（从登录页/设置页返回时调用）。 */
    fun onResume() {
        viewModelScope.launch {
            runCatching { container.session.applyAutoDefaults() }
            refresh()
        }
    }

    fun dismissBanner() = _state.update { it.copy(bannerDismissed = true) }

    /**
     * 第一次打开时：没有本地题库就自动去线上取，取不到才用内置题库兜底。
     * 普通用户因此完全不需要配置。
     */
    private fun bootstrapIfNeeded() {
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) { container.store.banks() }
            if (existing.isNotEmpty()) {
                refresh()
                return@launch
            }
            _state.update { it.copy(bootstrapping = true, bootstrapStatus = "正在获取在线题库…", bootstrapFailed = false) }

            val settings = container.settings.current()
            val snapshot = fetchListWithFallback(settings)
            if (snapshot == null || snapshot.items.isEmpty()) {
                useBuiltinFallback("在线题库暂时打不开。")
                return@launch
            }

            val targets = snapshot.items.take(AUTO_DOWNLOAD_LIMIT)
            var ok = 0
            targets.forEachIndexed { index, item ->
                _state.update {
                    it.copy(bootstrapStatus = "正在下载「${item.name}」 ${index + 1}/${targets.size}")
                }
                runCatching { container.repository.importBankItem(item, snapshot.sourceLabel) }
                    .onSuccess { summary ->
                        ok++
                        prefetchImages(summary)
                    }
                    .onFailure { /* 单个题库失败不影响其它 */ }
            }

            if (ok == 0) {
                useBuiltinFallback("在线题库下载失败。")
            } else {
                _state.update { it.copy(bootstrapping = false, bootstrapStatus = null) }
                refresh()
            }
        }
    }

    /** 先按设置里的地址拉；失败或为空时，登录状态下退回公开清单再试一次。 */
    private suspend fun fetchListWithFallback(settings: AppSettings): BankListSnapshot? {
        runCatching { container.repository.loadBankList() }
            .onSuccess { if (it.items.isNotEmpty()) return it }
        val fallbackPath = if (settings.loggedIn) AppSettings.DEFAULT_PUBLIC_LIST else AppSettings.DEFAULT_FULL_LIST
        return runCatching { container.repository.loadBankList(fallbackPath) }
            .onSuccess { return it }
            .getOrNull()
    }

    private suspend fun prefetchImages(summary: ImportSummary) {
        if (!container.settings.current().downloadImagesWithBank || summary.imageRefs.isEmpty()) return
        container.repository.prefetchImages(summary) { done, total ->
            _state.update { it.copy(bootstrapStatus = "正在缓存图片 $done/$total") }
        }
    }

    private suspend fun useBuiltinFallback(reason: String) {
        if (!container.settings.current().useBuiltinFallback) {
            _state.update { it.copy(bootstrapping = false, bootstrapStatus = null, bootstrapFailed = true) }
            return
        }
        val imported = runCatching { withContext(Dispatchers.IO) { container.repository.importSample() } }
        _state.update {
            it.copy(
                bootstrapping = false,
                bootstrapStatus = null,
                fallbackUsed = imported.isSuccess,
                bootstrapFailed = imported.isFailure,
            )
        }
        if (imported.isSuccess) {
            _message.value = "$reason\n已为你载入内置示例题库，可以先练手；网络恢复后点「同步题库」即可获取在线题库。"
        }
        refresh()
    }

    /** 手动重试在线题库 */
    fun retryBootstrap() {
        viewModelScope.launch {
            _state.update { it.copy(bootstrapping = true, bootstrapStatus = "正在获取在线题库…", bootstrapFailed = false) }
            val snapshot = fetchListWithFallback(container.settings.current())
            if (snapshot == null || snapshot.items.isEmpty()) {
                useBuiltinFallback("在线题库仍然打不开。")
                return@launch
            }
            val targets = snapshot.items.take(AUTO_DOWNLOAD_LIMIT)
            var ok = 0
            targets.forEachIndexed { index, item ->
                _state.update { it.copy(bootstrapStatus = "正在下载「${item.name}」 ${index + 1}/${targets.size}") }
                runCatching { container.repository.importBankItem(item, snapshot.sourceLabel) }
                    .onSuccess { ok++ }
            }
            _state.update { it.copy(bootstrapping = false, bootstrapStatus = null) }
            if (ok == 0) {
                _error.value = "题库下载失败，请检查网络后重试。"
            } else {
                refresh()
            }
        }
    }

    fun deleteBank(bank: Bank) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.store.deleteBank(bank.id) }
            _message.value = "已删除「${bank.name}」"
            refresh()
        }
    }

    fun reimport(bank: Bank) {
        viewModelScope.launch {
            _busy.value = "正在更新「${bank.name}」…"
            val result = runCatching { container.repository.reimportBank(bank) }
            _busy.value = null
            result.onSuccess {
                _message.value = summarize(it)
                refresh()
            }.onFailure { _error.value = it.message ?: "更新失败" }
        }
    }

    fun importSample() {
        viewModelScope.launch {
            _busy.value = "正在导入示例题库…"
            val result = runCatching { withContext(Dispatchers.IO) { container.repository.importSample() } }
            _busy.value = null
            result.onSuccess {
                _message.value = summarize(it)
                refresh()
            }.onFailure { _error.value = it.message ?: "导入失败" }
        }
    }

    fun importLocal(context: Context, uri: Uri) {
        viewModelScope.launch {
            _busy.value = "正在读取并解析文件…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val name = displayName(context, uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw ImportException("无法读取所选文件")
                    container.repository.importLocalFile(bytes, name)
                }
            }
            _busy.value = null
            result.onSuccess {
                _message.value = summarize(it)
                refresh()
            }.onFailure { _error.value = it.message ?: "导入失败" }
        }
    }

    private fun summarize(summary: ImportSummary): String {
        val types = summary.typeCounts.entries.joinToString("、") { "${it.key.label}${it.value}" }
        return buildString {
            append("导入成功：${summary.bankName}\n共 ${summary.questionCount} 题")
            if (types.isNotBlank()) append("\n$types")
            if (summary.skipped > 0) append("\n跳过空行 ${summary.skipped} 行")
            summary.warnings.take(4).forEach { append("\n· $it") }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && c.moveToFirst()) return c.getString(index) ?: ""
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    fun clearError() { _error.value = null }

    fun clearMessage() { _message.value = null }

    companion object {
        /** 首次启动自动下载的题库数量上限，避免一上来拉太多。 */
        const val AUTO_DOWNLOAD_LIMIT = 5
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer, nav: NavController) {
    val vm: HomeViewModel = viewModel(factory = VmFactory { HomeViewModel(container) })
    val state by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<Bank?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLocal(context, uri)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.onResume() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的题库") },
                actions = {
                    IconButton(onClick = {
                        if (state.loggedIn) menuOpen = true else nav.navigate(Routes.LOGIN)
                    }) {
                        Icon(
                            if (state.loggedIn) Icons.Filled.AccountCircle else Icons.Filled.Person,
                            contentDescription = if (state.loggedIn) "账号" else "登录获取更多题库",
                        )
                    }
                    IconButton(onClick = { nav.navigate(Routes.SYNC) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "同步题库")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.loggedIn) {
                                val who = state.account?.takeIf { !it.isGuest }?.let { "${it.username} · ${it.roleLabel}" }
                                    ?: "已登录"
                                DropdownMenuItem(text = { Text(who) }, enabled = false, onClick = {})
                                DropdownMenuItem(
                                    text = { Text("退出登录") },
                                    onClick = {
                                        menuOpen = false
                                        container.session.logout()
                                        vm.onResume()
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("登录获取更多题库") },
                                    onClick = { menuOpen = false; nav.navigate(Routes.LOGIN) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("学习统计") },
                                onClick = { menuOpen = false; nav.navigate(Routes.STATS) },
                            )
                            DropdownMenuItem(
                                text = { Text("考试记录与上传") },
                                onClick = { menuOpen = false; nav.navigate(Routes.EXAM_RECORDS) },
                            )
                            DropdownMenuItem(
                                text = { Text("错题本") },
                                onClick = { menuOpen = false; nav.navigate(Routes.collection(CollectionKind.WRONG)) },
                            )
                            DropdownMenuItem(
                                text = { Text("我的收藏") },
                                onClick = { menuOpen = false; nav.navigate(Routes.collection(CollectionKind.FAVORITE)) },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = { menuOpen = false; nav.navigate(Routes.SETTINGS) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.cards.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text("同步题库") },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = { nav.navigate(Routes.SYNC) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.bootstrapping -> BootstrapProgress(
                    status = state.bootstrapStatus ?: "正在准备…",
                )
                state.loading && state.cards.isEmpty() -> LoadingBox("正在读取本地题库…")
                state.cards.isEmpty() -> EmptyState(
                    title = if (state.bootstrapFailed) "在线题库打不开" else "还没有题库",
                    description = if (state.bootstrapFailed)
                        "检查一下网络，或者换成自己的 alist 服务器地址。\n也可以先导入本地 Excel、或用内置示例题库练手。"
                    else "点右下角「同步题库」从 alist 获取，或直接导入本地 Excel。",
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(onClick = vm::retryBootstrap) { Text("重试在线题库") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { nav.navigate(Routes.LOGIN) }) { Text("登录获取更多题库") }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("导入本地 Excel") }
                            TextButton(onClick = { vm.importSample() }) { Text("载入内置示例题库") }
                        }
                    },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!state.loggedIn && !state.bannerDismissed) {
                        item { LoginBanner(onLogin = { nav.navigate(Routes.LOGIN) }, onDismiss = vm::dismissBanner) }
                    }
                    if (state.fallbackUsed) {
                        item {
                            SectionCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "在线题库暂时打不开，现在用的是内置示例题库",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = vm::retryBootstrap) { Text("重试") }
                                }
                            }
                        }
                    }
                    state.overall?.let { overall ->
                        item { OverallCard(overall) }
                    }
                    items(state.cards, key = { it.bank.id }) { card ->
                        BankCardItem(
                            card = card,
                            onPractice = { mode, start ->
                                nav.navigate(Routes.practice(card.bank.id, mode, start))
                            },
                            onExam = { nav.navigate(Routes.exam(card.bank.id)) },
                            onStats = { nav.navigate(Routes.STATS) },
                            onReimport = { vm.reimport(card.bank) },
                            onDelete = { pendingDelete = card.bank },
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导入本地 Excel")
                            }
                        }
                    }
                }
            }

            busy?.let { text ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SectionCard(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { bank ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除题库") },
            text = { Text("确定删除「${bank.name}」吗？该题库的答题记录、错题和收藏都会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBank(bank)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    ErrorDialog(message = error, onDismiss = vm::clearError)
    MessageDialog(message = message, onDismiss = vm::clearMessage)
}

@Composable
private fun BootstrapProgress(status: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(18.dp))
        Text("正在准备题库", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth(0.7f))
        Spacer(Modifier.height(10.dp))
        Text(
            "首次打开会自动获取公开题库，之后就能离线练习",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginBanner(onLogin: () -> Unit, onDismiss: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("登录后可获取更多题库", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "现在看到的是公开题库；登录后能看到你有权限的全部题库，成绩也会保存到账号里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "不再提示", modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("去登录") }
    }
}

@Composable
private fun OverallCard(overall: OverallStats) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("累计答题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${overall.answered}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("正确率", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (overall.answered == 0) "—" else "${(overall.accuracy * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column {
                Text("错题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${overall.wrongCount}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("题量", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${overall.questionCount}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BankCardItem(
    card: HomeViewModel.BankCard,
    onPractice: (PracticeMode, Int) -> Unit,
    onExam: () -> Unit,
    onStats: () -> Unit,
    onReimport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bank = card.bank
    val stats = card.stats

    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    bank.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (bank.category.isNotBlank()) TagChip(bank.category)
                    bank.tags.filterNot { it.equals(bank.category, ignoreCase = true) }
                        .take(3)
                        .forEach { TagChip(it, MaterialTheme.colorScheme.secondary) }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    card.progress?.let { progress ->
                        DropdownMenuItem(
                            text = { Text("继续上次（第 ${progress.lastIndex + 1} 题）") },
                            onClick = {
                                menuOpen = false
                                onPractice(progress.mode, progress.lastIndex)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("错题练习（${stats.wrongCount}）") },
                        enabled = stats.wrongCount > 0,
                        onClick = { menuOpen = false; onPractice(PracticeMode.WRONG, 0) },
                    )
                    DropdownMenuItem(
                        text = { Text("收藏练习（${stats.favoriteCount}）") },
                        enabled = stats.favoriteCount > 0,
                        onClick = { menuOpen = false; onPractice(PracticeMode.FAVORITE, 0) },
                    )
                    DropdownMenuItem(
                        text = { Text("查看统计") },
                        onClick = { menuOpen = false; onStats() },
                    )
                    DropdownMenuItem(
                        text = { Text("重新下载并更新题库") },
                        onClick = { menuOpen = false; onReimport() },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("删除题库")
                            }
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatInline("题量", "${stats.questionCount}")
            StatInline("已做", "${stats.distinctAnswered}")
            StatInline("正确率", if (stats.answered == 0) "—" else "${(stats.accuracy * 100).toInt()}%")
            if (stats.wrongCount > 0) StatInline("错题", "${stats.wrongCount}", MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(10.dp))
        ThinProgress(stats.progress)
        Spacer(Modifier.height(4.dp))
        Text(
            "完成度 ${(stats.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        // 顺序练习 / 随机练习 / 考试 三个同级按钮
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { onPractice(PracticeMode.SEQUENTIAL, 0) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("顺序练习", maxLines = 1)
            }
            OutlinedButton(
                onClick = { onPractice(PracticeMode.RANDOM, 0) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) { Text("随机练习", maxLines = 1) }
            FilledTonalButton(
                onClick = onExam,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) { Text("考试", maxLines = 1) }
        }
    }
}

@Composable
private fun StatInline(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}
