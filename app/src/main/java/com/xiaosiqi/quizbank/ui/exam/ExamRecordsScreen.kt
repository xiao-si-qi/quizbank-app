package com.xiaosiqi.quizbank.ui.exam

import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.exam.ExamRecord
import com.xiaosiqi.quizbank.exam.ExamRepository
import com.xiaosiqi.quizbank.exam.ExamSummary
import com.xiaosiqi.quizbank.importer.TextNorm
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.EmptyState
import com.xiaosiqi.quizbank.ui.common.ErrorDialog
import com.xiaosiqi.quizbank.ui.common.LoadingBox
import com.xiaosiqi.quizbank.ui.common.RichTextView
import com.xiaosiqi.quizbank.ui.common.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 考试记录：本机 / 云端（自己的）/ 全部用户（管理员）。
 * 每条记录都能点开回顾这次考试抽到的每一道题。
 */
class ExamRecordsViewModel(private val container: AppContainer) : ViewModel() {

    /** 三个视角。哪些可用取决于登录状态和是不是管理员。 */
    enum class RecordTab(val title: String) {
        LOCAL("本机记录"),
        CLOUD("云端记录"),
        ALL_USERS("全部用户"),
    }

    data class UiState(
        val tab: RecordTab = RecordTab.LOCAL,
        val loading: Boolean = true,
        /** 下拉刷新中：不清空已有列表，避免闪一下 */
        val refreshing: Boolean = false,
        val busy: String? = null,
        val local: List<ExamRecord> = emptyList(),
        val summary: ExamSummary = ExamSummary(),
        val remote: List<ExamRecord> = emptyList(),
        val remoteSummary: ExamSummary = ExamSummary(),
        val users: List<ExamRepository.UserExams> = emptyList(),
        /** 已经加载过的标签页（避免切来切去重复请求）。 */
        val loadedTabs: Set<RecordTab> = emptySet(),
        val selectedUser: String? = null,
        val detail: ExamRecord? = null,
        val message: String? = null,
        val error: String? = null,
        val canUpload: Boolean = false,
        val loggedIn: Boolean = false,
        val isAdmin: Boolean = false,
        val userName: String = "",
    ) {
        /**
         * 实际显示的标签页：
         * - 本机记录：谁都能看
         * - 云端记录：**登录后**才有意义（要靠账号权限去 alist 读写）
         * - 全部用户：**只有 alist 管理员**能看到别人的成绩
         */
        val availableTabs: List<RecordTab>
            get() = buildList {
                add(RecordTab.LOCAL)
                if (loggedIn) add(RecordTab.CLOUD)
                if (isAdmin) add(RecordTab.ALL_USERS)
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshLocal()
    }

    /** 下拉刷新当前标签页（三个 tab 共用）。 */
    fun pullRefresh(tab: RecordTab) {
        _state.update { it.copy(refreshing = true) }
        when (tab) {
            RecordTab.LOCAL -> refreshLocal()
            RecordTab.CLOUD -> loadRemote()
            RecordTab.ALL_USERS -> loadAllUsers()
        }
    }

    fun refreshLocal() {
        viewModelScope.launch {
            val settings = container.settings.current()
            val (records, summary, account) = withContext(Dispatchers.IO) {
                val list = container.exams.local(500)
                // 登录态里已经缓存了账号信息，这里不会额外发请求
                Triple(list, ExamSummary.of(list), runCatching { container.session.me() }.getOrNull())
            }
            _state.update {
                val next = it.copy(
                    loading = false,
                    refreshing = false,
                    local = records,
                    summary = summary,
                    canUpload = container.exams.hasUploadTarget(),
                    loggedIn = settings.loggedIn,
                    isAdmin = account?.isAdmin == true,
                    userName = settings.effectiveName,
                )
                // 之前选中的标签页可能已经不适用（比如刚退出登录），回落到「本机记录」
                if (next.tab in next.availableTabs) next else next.copy(tab = RecordTab.LOCAL)
            }
        }
    }

    fun setTab(tab: RecordTab) {
        val s = _state.value
        if (tab !in s.availableTabs) return      // 没资格看的标签页直接忽略
        _state.update { it.copy(tab = tab) }
        when (tab) {
            RecordTab.CLOUD -> if (s.remote.isEmpty() && s.loadedTabs.none { it == RecordTab.CLOUD }) loadRemote()
            RecordTab.ALL_USERS -> if (s.loadedTabs.none { it == RecordTab.ALL_USERS }) loadAllUsers()
            RecordTab.LOCAL -> Unit
        }
    }

    fun uploadOne(record: ExamRecord) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在上传考试记录…") }
            runCatching { container.exams.upload(record) }
                .onSuccess { path ->
                    _state.update { it.copy(busy = null, message = "已上传到：\n$path") }
                    refreshLocal()
                }
                .onFailure { e -> _state.update { it.copy(busy = null, error = e.message ?: "上传失败") } }
        }
    }

    fun uploadAll() {
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在上传未上传的考试记录…") }
            runCatching { container.exams.uploadAllPending() }
                .onSuccess { (ok, errors) ->
                    val text = buildString {
                        append("已上传 $ok 条考试记录")
                        if (errors.isNotEmpty()) append("\n失败：\n" + errors.joinToString("\n"))
                    }
                    _state.update { it.copy(busy = null, message = text) }
                    refreshLocal()
                }
                .onFailure { e -> _state.update { it.copy(busy = null, error = e.message ?: "上传失败") } }
        }
    }

    /** 放弃当前进度重新考：先删掉进行中的记录，再让调用方跳进考试页（会重新抽卷）。 */
    fun restartExam(record: ExamRecord, onReady: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.exams.deleteLocal(record.localId) }
            onReady()
        }
    }

    fun deleteRecord(record: ExamRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.exams.deleteLocal(record.localId) }
            refreshLocal()
        }
    }

    fun loadRemote() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { container.exams.fetchRemote(scopeMine = true) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            loadedTabs = it.loadedTabs + RecordTab.CLOUD,
                            loading = false,
                            refreshing = false,
                            remote = result.records,
                            remoteSummary = ExamSummary.of(result.records),
                            // 加载成功不弹窗，列表出来就行；
                            // 只有真有文件读不出来（数据可能不完整）才提示一次
                            message = if (result.failed > 0) {
                                buildString {
                                    append("有 ${result.failed} 个成绩文件没能读取")
                                    if (result.errors.isNotEmpty()) append("：\n" + result.errors.joinToString("\n"))
                                }
                            } else null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, refreshing = false, error = e.message ?: "读取失败") }
                }
        }
    }

    fun loadAllUsers() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { container.exams.fetchAllUsers() }
                .onSuccess { users ->
                    _state.update {
                        it.copy(
                            loadedTabs = it.loadedTabs + RecordTab.ALL_USERS,
                            loading = false,
                            refreshing = false,
                            users = users,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = e.message ?: "读取失败",
                            users = emptyList(),
                        )
                    }
                }
        }
    }

    fun selectUser(name: String?) = _state.update { it.copy(selectedUser = name) }

    fun openDetail(record: ExamRecord?) = _state.update { it.copy(detail = record) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearError() = _state.update { it.copy(error = null) }
}

private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

private fun Long.asTime(): String =
    if (this <= 0) "-" else fmt.format(Date(this))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamRecordsScreen(container: AppContainer, nav: NavController) {
    val vm: ExamRecordsViewModel = viewModel(factory = VmFactory { ExamRecordsViewModel(container) })
    val state by vm.state.collectAsState()

    // 系统返回键（手势/返回键）要逐级退，不能一下子弹回主界面：
    // 先关题目回顾 → 再退「某个用户」的列表 → 最后才离开本页
    BackHandler(enabled = state.detail != null) { vm.openDetail(null) }
    BackHandler(enabled = state.detail == null && state.selectedUser != null) { vm.selectUser(null) }

    val detail = state.detail
    if (detail != null) {
        ExamRecordDetail(record = detail, onBack = { vm.openDetail(null) })
        return
    }

    // 进行中的记录不回顾题目，直接进考试页接着考（考试页会自动恢复那条记录）
    val continueExam: (ExamRecord) -> Unit = { record ->
        nav.navigate(com.xiaosiqi.quizbank.ui.Routes.exam(record.bankId))
    }
    val restartExam: (ExamRecord) -> Unit = { record ->
        vm.restartExam(record) { nav.navigate(com.xiaosiqi.quizbank.ui.Routes.exam(record.bankId)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("考试记录") },
                actions = {
                    if (state.canUpload && state.local.any { !it.uploaded }) {
                        TextButton(onClick = vm::uploadAll) { Text("全部上传") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val tabs = state.availableTabs
            TabRow(selectedTabIndex = tabs.indexOf(state.tab).coerceAtLeast(0)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { vm.setTab(tab) },
                        text = { Text(tab.title) },
                    )
                }
            }

            if (state.busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    state.busy.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            // 三个标签页共用一层下拉刷新
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { vm.pullRefresh(state.tab) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (state.tab) {
                    ExamRecordsViewModel.RecordTab.LOCAL -> LocalTab(state, vm, continueExam, restartExam)
                    ExamRecordsViewModel.RecordTab.CLOUD -> RemoteTab(state, vm, continueExam, restartExam)
                    ExamRecordsViewModel.RecordTab.ALL_USERS -> AllUsersTab(state, vm, continueExam, restartExam)
                }
            }
        }
    }

    state.message?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            title = { Text("考试记录") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text("好的") } },
        )
    }
    ErrorDialog(message = state.error, onDismiss = vm::clearError)
}

@Composable
private fun LocalTab(state: ExamRecordsViewModel.UiState, vm: ExamRecordsViewModel,
    onContinue: (ExamRecord) -> Unit,
    onRestart: (ExamRecord) -> Unit,
) {
    if (state.loading) return LoadingBox("正在读取本机记录…")
    if (state.local.isEmpty()) {
        return EmptyState(
            title = "还没有考试记录",
            description = "回首页，在题库卡片上点「考试」，交卷后这里就会有成绩。\n普通练习只记错题，不生成成绩。",
        )
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SummaryCard(title = "本机考试概览", summary = state.summary) }
        items(state.local, key = { it.localId }) { record ->
            ExamRecordCard(
                record = record,
                onOpen = { vm.openDetail(record) },
                onUpload = if (state.canUpload) ({ vm.uploadOne(record) }) else null,
                onDelete = { vm.deleteRecord(record) },
                onContinue = { onContinue(record) },
                onRestart = { onRestart(record) },
            )
        }
    }
}

@Composable
private fun RemoteTab(state: ExamRecordsViewModel.UiState, vm: ExamRecordsViewModel,
    onContinue: (ExamRecord) -> Unit,
    onRestart: (ExamRecord) -> Unit,
) {
    if (!state.canUpload) {
        return EmptyState(
            title = "还没有设置上传目录",
            description = "到「设置 → 考试记录上传」填好 alist 上的目录，就能把成绩同步到云端。",
        )
    }
    if (state.loading) return LoadingBox("正在从 alist 读取…")
    if (state.remote.isEmpty()) {
        return EmptyState(
            title = "云端还没有记录",
            description = "把本机记录上传后，换手机登录同一账号也能看到。",
            action = { Button(onClick = vm::loadRemote) { Text("重新读取") } },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SummaryCard(title = "云端考试概览", summary = state.remoteSummary) }
        item {
            OutlinedButton(onClick = vm::loadRemote, modifier = Modifier.fillMaxWidth()) { Text("重新读取") }
        }
        items(state.remote, key = { it.remotePath }) { record ->
            ExamRecordCard(
                record = record,
                onOpen = { vm.openDetail(record) },
                onContinue = { onContinue(record) },
                onRestart = { onRestart(record) },
            )
        }
    }
}

@Composable
private fun AllUsersTab(state: ExamRecordsViewModel.UiState, vm: ExamRecordsViewModel,
    onContinue: (ExamRecord) -> Unit,
    onRestart: (ExamRecord) -> Unit,
) {
    if (!state.canUpload) {
        return EmptyState(
            title = "还没有设置上传目录",
            description = "到「设置 → 考试记录上传」填好 alist 上的目录。",
        )
    }
    if (state.loading) return LoadingBox("正在读取所有人的考试记录…")

    val selected = state.selectedUser
    if (selected != null) {
        val user = state.users.firstOrNull { it.userName == selected }
        if (user == null) {
            vm.selectUser(null)
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.selectUser(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(selected, style = MaterialTheme.typography.titleMedium)
                }
            }
            item { SummaryCard(title = "$selected 的考试概览", summary = user.summary) }
            items(user.records, key = { it.remotePath }) { record ->
                ExamRecordCard(
                    record = record,
                    onOpen = { vm.openDetail(record) },
                    onContinue = { onContinue(record) },
                    onRestart = { onRestart(record) },
                    subtitle = record.user,
                )
            }
        }
        return
    }

    if (state.users.isEmpty()) {
        return EmptyState(
            title = "没有读到任何用户的记录",
            description = "成绩目录下还没有人上传考试记录。",
            action = { Button(onClick = vm::loadAllUsers) { Text("重新读取") } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "共 ${state.users.size} 位用户 · ${state.users.sumOf { it.records.size }} 次考试",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.users, key = { it.userName }) { user ->
            Card(
                Modifier.fillMaxWidth().clickable { vm.selectUser(user.userName) },
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.userName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${user.records.size} 次考试 · 平均 ${user.summary.percent} 分 · 用时 ${user.summary.durationText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "最近 " + user.records.firstOrNull()?.finishedAt.orZero().asTime(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { OutlinedButton(onClick = vm::loadAllUsers, modifier = Modifier.fillMaxWidth()) { Text("重新读取") } }
    }
}

private fun Long?.orZero(): Long = this ?: 0L

@Composable
private fun SummaryCard(title: String, summary: ExamSummary) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${summary.percent}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (summary.percent >= 60) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
            )
            Text(" 平均分", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("${summary.sessions} 次考试 · ${summary.questionCount} 题", style = MaterialTheme.typography.bodySmall)
                Text(
                    "正确率 ${(summary.accuracy * 100).toInt()}% · 累计 ${summary.durationText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (summary.byBank.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            summary.byBank.take(5).forEach { stat ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(stat.bankName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        "${stat.sessions} 次 · 平均 ${(stat.scoreRate * 100).toInt()} 分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExamRecordCard(
    record: ExamRecord,
    onOpen: () -> Unit,
    onUpload: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onRestart: (() -> Unit)? = null,
    subtitle: String = "",
) {
    val inProgress = record.inProgress
    // 没考完的：点卡片进考试接着考；已交卷的：点卡片回顾题目
    val primaryAction = if (inProgress) (onContinue ?: onOpen) else onOpen
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = primaryAction)) {
                    Text(
                        record.bankName.ifBlank { "考试" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        buildString {
                            append(record.finishedAt.asTime())
                            if (subtitle.isNotBlank()) append(" · $subtitle")
                            append(" · 用时 ${record.durationText}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (record.percent >= 60) Color(0xFF16A34A).copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable(onClick = primaryAction)
                ) {
                    Text(
                        if (inProgress) "进行中" else "${record.percent} 分",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (inProgress) MaterialTheme.colorScheme.primary
                        else if (record.percent >= 60) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (inProgress)
                    "已答 ${record.details.count { it.my.isNotBlank() }}/${record.questionCount} 题 · " +
                        "上次做到第 ${record.index + 1} 题 · 满分 ${record.totalPoints} 分"
                else
                    "${record.scoredPoints}/${record.totalPoints} 分 · ${record.correctCount} 对 ${record.wrongCount} 错" +
                        if (record.selfGraded > 0) " · ${record.selfGraded} 题待自评" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inProgress) {
                    Button(onClick = primaryAction, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("继续考试")
                    }
                    if (onRestart != null) {
                        TextButton(onClick = onRestart) { Text("重新开始") }
                    }
                } else {
                    TextButton(onClick = onOpen) { Text("回顾题目") }
                    if (onUpload != null) {
                        TextButton(onClick = onUpload) { Text(if (record.uploaded) "重新上传" else "上传") }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!inProgress && record.uploaded) {
                    Text(
                        "已上传",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** 一次考试的完整回顾：概览 + 逐题（题干/选项/我的答案/正确答案/解析）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamRecordDetail(record: ExamRecord, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text(record.bankName.ifBlank { "考试回顾" }, maxLines = 1) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${record.percent}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (record.percent >= 60) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                        )
                        Text(" 分", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${record.scoredPoints} / ${record.totalPoints} 分")
                            Text(
                                "${record.correctCount} 对 · ${record.wrongCount} 错 · ${record.durationText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append("时间 ${record.finishedAt.asTime()}")
                            if (record.user.isNotBlank()) append(" · ${record.user}")
                            if (record.device.isNotBlank()) append(" · ${record.device}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text(
                    "本次考试抽到的题目（共 ${record.details.size} 题）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(record.details, key = { it.index }) { detail ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when (detail.correct) {
                                            true -> Color(0xFF16A34A)
                                            false -> MaterialTheme.colorScheme.error
                                            null -> MaterialTheme.colorScheme.outline
                                        }
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${detail.index}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${detail.type} · ${detail.score} 分",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                when (detail.correct) {
                                    true -> "答对"
                                    false -> "答错"
                                    null -> "未计分"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = when (detail.correct) {
                                    true -> Color(0xFF16A34A)
                                    false -> MaterialTheme.colorScheme.error
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        RichTextView(raw = detail.stem, imageBase = "")
                        detail.options.forEach { option ->
                            Text(
                                option,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "我的作答：${detail.my.ifBlank { "（未作答）" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "正确答案：${detail.answer}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF16A34A),
                        )
                        if (detail.analysis.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("解析", style = MaterialTheme.typography.labelMedium)
                            RichTextView(raw = TextNorm.oneLine(detail.analysis), imageBase = "")
                        }
                    }
                }
            }
        }
    }
}
