package com.xiaosiqi.quizbank.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.alist.AlistAccount
import com.xiaosiqi.quizbank.data.prefs.AppSettings
import com.xiaosiqi.quizbank.ui.Routes
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.DividerLine
import com.xiaosiqi.quizbank.ui.common.ErrorDialog
import com.xiaosiqi.quizbank.ui.common.MessageDialog
import com.xiaosiqi.quizbank.ui.common.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val busy: String? = null,
        val message: String? = null,
        val error: String? = null,
        val cacheText: String = "计算中…",
        val pendingUploads: Int = 0,
        val account: AlistAccount? = null,
    )

    private val _state = MutableStateFlow(UiState(settings = container.settings.current()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshCacheInfo()
        loadAccount()
    }

    /** 读取当前 alist 账号，用来展示身份（管理员 / 普通用户）。 */
    fun loadAccount() {
        viewModelScope.launch {
            val account = runCatching { container.session.me(force = true) }.getOrNull()
            _state.update { it.copy(account = account) }
        }
    }

    fun update(block: (AppSettings) -> AppSettings) {
        container.settings.edit(block)
        _state.update { it.copy(settings = container.settings.current()) }
    }

    fun refreshCacheInfo() {
        viewModelScope.launch {
            val (cache, pending) = withContext(Dispatchers.IO) {
                val size = container.images.sizeBytes()
                val count = container.exams.pending().size
                humanSize(size) to count
            }
            _state.update { it.copy(cacheText = cache, pendingUploads = pending) }
        }
    }

    fun testConnection() {
        val s = container.settings.current()
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在连接 alist…") }
            runCatching { container.session.testConnection(s.baseUrl, s.token) }
                .onSuccess { info -> _state.update { it.copy(busy = null, message = "连接成功：$info") } }
                .onFailure { e -> _state.update { it.copy(busy = null, error = e.message ?: "连接失败") } }
        }
    }

    fun login() {
        val s = container.settings.current()
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在登录 alist…") }
            runCatching { container.session.login(s.baseUrl, s.username, s.password) }
                .onSuccess {
                    val account = runCatching { container.session.me(force = true) }.getOrNull()
                    _state.update {
                        it.copy(
                            busy = null,
                            message = "登录成功，账号已保存" + (account?.let { a -> "（${a.username} · ${a.roleLabel}）" } ?: ""),
                            settings = container.settings.current(),
                            account = account,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = null, error = e.message ?: "登录失败") } }
        }
    }

    fun logout() {
        container.session.logout()
        _state.update { it.copy(settings = container.settings.current(), message = "已退出登录") }
    }

    fun verifyBankList() {
        val s = container.settings.current()
        if (s.listPath.isBlank()) {
            _state.update { it.copy(error = "请先填写题库列表地址") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在读取题库列表…") }
            runCatching { container.repository.checkBankListSource(s.listPath, s.listPassword) }
                .onSuccess { info -> _state.update { it.copy(busy = null, message = info) } }
                .onFailure { e -> _state.update { it.copy(busy = null, error = e.message ?: "读取失败") } }
        }
    }

    fun verifyScorePath() {
        val s = container.settings.current()
        if (!s.hasScorePath) {
            _state.update { it.copy(error = "请先填写成绩上传目录") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在检查成绩目录…") }
            runCatching {
                container.session.withAuth { client ->
                    client.ensureDir(s.scoreUploadPath)
                    client.list(s.scoreUploadPath)
                }
            }.onSuccess { items ->
                _state.update { it.copy(busy = null, message = "目录可用，里面已有 ${items.size} 个文件/文件夹") }
            }.onFailure { e ->
                _state.update { it.copy(busy = null, error = e.message ?: "目录不可用（可能没有写入权限）") }
            }
        }
    }

    fun uploadPendingExams() {
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在上传未上传的考试记录…") }
            runCatching { container.exams.uploadAllPending() }
                .onSuccess { (ok, errors) ->
                    val text = buildString {
                        append("已上传 $ok 条考试记录")
                        if (errors.isNotEmpty()) append("\n失败：\n" + errors.joinToString("\n"))
                    }
                    _state.update { it.copy(busy = null, message = text) }
                    refreshCacheInfo()
                }
                .onFailure { e -> _state.update { it.copy(busy = null, error = e.message ?: "上传失败") } }
        }
    }

    fun clearImageCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.images.clear() }
            refreshCacheInfo()
            _state.update { it.copy(message = "图片缓存已清空") }
        }
    }

    fun clearAllProgress() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.store.resetAllProgress() }
            _state.update { it.copy(message = "已清空所有答题记录、错题和进度") }
        }
    }

    fun importSample() {
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在导入示例题库…") }
            runCatching { withContext(Dispatchers.IO) { container.repository.importSample() } }
                .onSuccess { summary ->
                    _state.update { it.copy(busy = null, message = "示例题库已导入，共 ${summary.questionCount} 题") }
                }
                .onFailure { e -> _state.update { it.copy(busy = null, error = e.message ?: "导入失败") } }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** 把题库列表/成绩目录恢复成「跟随登录状态自动设置」。 */
    fun restoreDefaults() {
        container.settings.edit {
            it.copy(autoConfigurePaths = true).withAutoDefaults(container.session.cachedAccount())
        }
        _state.update { it.copy(settings = container.settings.current(), message = "已恢复默认路径") }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, nav: NavController) {
    val vm: SettingsViewModel = viewModel(factory = VmFactory { SettingsViewModel(container) })
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val settings = state.settings
    var confirmClear by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri: Uri? ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.second) }
            }.onSuccess { vm.clearMessage() }.onFailure { /* 用户取消或写入失败，静默处理 */ }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ------------------------------------------------ 账号
                SectionCard {
                    Text("账号", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "不登录也能用，看到的是公开题库；\n登录后能看到你有权限的全部题库，成绩也会保存到账号里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.baseUrl,
                        onValueChange = { v -> vm.update { it.copy(baseUrl = v) } },
                        label = { Text("服务器地址") },
                        supportingText = { Text("默认地址一般不用改") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.account?.let { account ->
                            if (account.isGuest || !settings.loggedIn) "当前状态：未登录（只能看公开题库）"
                            else "当前状态：${account.username} · ${account.roleLabel} · 根目录 ${account.basePath}"
                        } ?: "当前状态：未登录（只能看公开题库）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (settings.loggedIn) {
                            Button(onClick = { nav.navigate(Routes.LOGIN) }) { Text("切换账号") }
                            OutlinedButton(onClick = { vm.logout(); vm.loadAccount() }) { Text("退出登录") }
                        } else {
                            Button(onClick = { nav.navigate(Routes.LOGIN) }) { Text("登录获取更多题库") }
                        }
                        OutlinedButton(onClick = vm::testConnection) { Text("测试连接") }
                    }
                }

                // ------------------------------------------------ 题库列表
                SectionCard {
                    Text("题库列表", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已经填好默认值了，一般不用改。\n登录后会自动切到「完整题库清单」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.listPath,
                        onValueChange = { v ->
                            vm.update { it.copy(listPath = v, autoConfigurePaths = false) }
                        },
                        label = { Text("题库列表地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.navigate(Routes.browser(BrowserTarget.LIST)) }) {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("浏览选择")
                        }
                        OutlinedButton(onClick = vm::verifyBankList) { Text("检查能否读取") }
                    }
                    if (!settings.autoConfigurePaths) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            vm.update { it.copy(autoConfigurePaths = true) }
                            vm.restoreDefaults()
                        }) { Text("恢复自动设置") }
                    }
                }

                // ------------------------------------------------ 内置题库兜底
                SectionCard {
                    Text("内置题库", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "首次打开 App 会自动获取在线题库；如果打不开（比如没网），" +
                            "就自动载入一份内置示例题库，保证能直接开始练习。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow(
                        title = "在线题库打不开时使用内置题库",
                        checked = settings.useBuiltinFallback,
                        onCheckedChange = { v -> vm.update { it.copy(useBuiltinFallback = v) } },
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = vm::importSample) { Text("立即载入内置示例题库") }
                }

                // ------------------------------------------------ 考试记录
                SectionCard {
                    Text("考试记录上传", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (settings.loggedIn)
                            "这是所有人共用的成绩根目录，登录后自动填好，一般不用改。" +
                                "\nApp 会在这个目录下按用户名建子目录，所以你的成绩会存到「成绩目录/你的用户名/」。" +
                                "\n每次考试一个 JSON 文件，多设备互不覆盖；管理员用这个根目录就能看到所有人的成绩。" +
                                "\n普通练习不上传，只有考试模式的成绩才会存到云端。"
                        else
                            "登录之后才会自动填好目录；未登录时考试记录只保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.scoreUploadPath,
                        onValueChange = { v ->
                            vm.update { it.copy(scoreUploadPath = v, autoConfigurePaths = false) }
                        },
                        label = { Text("成绩目录") },
                        placeholder = { Text("登录后自动填写") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.displayName,
                        onValueChange = { v -> vm.update { it.copy(displayName = v) } },
                        label = { Text("署名（默认用 alist 用户名）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        title = "自动同步考试记录到云端",
                        checked = settings.autoUploadScore,
                        onCheckedChange = { v -> vm.update { it.copy(autoUploadScore = v) } },
                    )
                    Text(
                        "登录后默认开启：考试过程中会节流同步（换手机也能接着考），交卷后再同步一次。" +
                            "普通练习不上传、也不生成成绩。每条记录都带逐题作答，管理员可据此回顾每道题。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        title = "允许从 alist 查看所有人的考试记录",
                        checked = settings.allowAdminOverview,
                        onCheckedChange = { v -> vm.update { it.copy(allowAdminOverview = v) } },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.account?.let { account ->
                            if (account.isGuest) "当前账号：匿名访客（只能看公开题库）"
                            else "当前账号：${account.username} · ${account.roleLabel} · 根目录 ${account.basePath}"
                        } ?: "当前账号：未登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "成绩与答题记录的可见范围完全由 alist 的账号权限决定：" +
                            "管理员登录后能列出成绩目录下所有用户的文件，普通用户只能看到自己有权限的路径。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.navigate(Routes.browser(BrowserTarget.SCORE)) }) { Text("浏览目录") }
                        OutlinedButton(onClick = vm::verifyScorePath) { Text("检查目录") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = vm::uploadPendingExams, enabled = state.pendingUploads > 0) {
                        Text(if (state.pendingUploads > 0) "上传未上传的成绩（${state.pendingUploads}）" else "没有待上传的成绩")
                    }
                }

                // ------------------------------------------------ 图片
                SectionCard {
                    Text("图片", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "题目里的图片会缓存到手机本地，之后离线也能看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "下载题库时顺便缓存图片",
                        checked = settings.downloadImagesWithBank,
                        onCheckedChange = { v -> vm.update { it.copy(downloadImagesWithBank = v) } },
                    )
                    SwitchRow(
                        title = "仅在 Wi-Fi 下缓存图片",
                        checked = settings.downloadImagesWifiOnly,
                        onCheckedChange = { v -> vm.update { it.copy(downloadImagesWifiOnly = v) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("当前缓存：${state.cacheText}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::clearImageCache) { Text("清空图片缓存") }
                }

                // ------------------------------------------------ 练习
                SectionCard {
                    Text("练习", style = MaterialTheme.typography.titleMedium)
                    SwitchRow(
                        title = "随机打乱题目顺序",
                        checked = settings.shuffleQuestions,
                        onCheckedChange = { v -> vm.update { it.copy(shuffleQuestions = v) } },
                    )
                    SwitchRow(
                        title = "随机打乱选项顺序",
                        checked = settings.shuffleOptions,
                        onCheckedChange = { v -> vm.update { it.copy(shuffleOptions = v) } },
                    )
                    SwitchRow(
                        title = "答对后自动移出错题本",
                        checked = settings.autoRemoveWrong,
                        onCheckedChange = { v -> vm.update { it.copy(autoRemoveWrong = v) } },
                    )
                    SwitchRow(
                        title = "答完立即显示解析",
                        checked = settings.showAnalysisImmediately,
                        onCheckedChange = { v -> vm.update { it.copy(showAnalysisImmediately = v) } },
                    )
                }

                // ------------------------------------------------ 显示
                SectionCard {
                    Text("显示", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("主题", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (value, label) ->
                            if (settings.darkTheme == value) {
                                Button(onClick = { vm.update { it.copy(darkTheme = value) } }) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { vm.update { it.copy(darkTheme = value) } }) { Text(label) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("字体大小", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.9f to "小", 1f to "标准", 1.15f to "大", 1.3f to "特大").forEach { (value, label) ->
                            if (kotlin.math.abs(settings.fontScale - value) < 0.01f) {
                                Button(onClick = { vm.update { it.copy(fontScale = value) } }) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { vm.update { it.copy(fontScale = value) } }) { Text(label) }
                            }
                        }
                    }
                }

                // ------------------------------------------------ 模板与数据
                SectionCard {
                    Text("模板与数据", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "导出的模板就是 App 能直接读取的格式，照着填最省事。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            pendingExport = "题库模板.xlsx" to container.repository.questionTemplate()
                            exportLauncher.launch("题库模板.xlsx")
                        }) { Text("导出题库模板") }
                        OutlinedButton(onClick = {
                            pendingExport = "题库列表模板.xlsx" to container.repository.bankListTemplate()
                            exportLauncher.launch("题库列表模板.xlsx")
                        }) { Text("导出列表模板") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            pendingExport = "示例题库.xlsx" to container.repository.sampleBank()
                            exportLauncher.launch("示例题库.xlsx")
                        }) { Text("导出示例题库") }
                        OutlinedButton(onClick = vm::importSample) { Text("导入示例题库") }
                    }
                    Spacer(Modifier.height(12.dp))
                    DividerLine()
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { confirmClear = true }) { Text("清空所有答题记录与进度") }
                }

                // ------------------------------------------------ 关于
                SectionCard {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("题库 App v1.0.0", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "题库列表与题库文件都存放在 alist 上，App 只做读取与练习；" +
                            "成绩也会按你的设置写回 alist。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(40.dp))
            }

            state.busy?.let { text ->
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("确认清空") },
            text = { Text("会清空所有题库的答题记录、错题本和练习进度（题库本身保留）。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllProgress()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }

    ErrorDialog(message = state.error, onDismiss = vm::clearError)
    MessageDialog(message = state.message, onDismiss = vm::clearMessage, title = "提示")
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 浏览器要填入的目标字段。 */
object BrowserTarget {
    const val LIST = "list"
    const val SCORE = "score"
}

@Suppress("unused")
private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享"))
}
