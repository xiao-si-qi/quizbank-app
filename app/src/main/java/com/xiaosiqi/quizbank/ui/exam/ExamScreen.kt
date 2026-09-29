package com.xiaosiqi.quizbank.ui.exam

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.xiaosiqi.quizbank.domain.AnswerJudge
import com.xiaosiqi.quizbank.exam.ExamDetail
import com.xiaosiqi.quizbank.data.prefs.AppSettings
import com.xiaosiqi.quizbank.model.Option
import com.xiaosiqi.quizbank.exam.ExamBuilder
import com.xiaosiqi.quizbank.exam.ExamJson
import com.xiaosiqi.quizbank.exam.ExamMeta
import com.xiaosiqi.quizbank.exam.ExamPaper
import com.xiaosiqi.quizbank.exam.ExamRecord
import com.xiaosiqi.quizbank.exam.ExamResult
import com.xiaosiqi.quizbank.exam.examBlueprint
import com.xiaosiqi.quizbank.importer.TextNorm
import com.xiaosiqi.quizbank.model.Bank
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.model.QuestionType
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.EmptyState
import com.xiaosiqi.quizbank.ui.common.ErrorDialog
import com.xiaosiqi.quizbank.ui.common.LoadingBox
import com.xiaosiqi.quizbank.ui.common.RichTextView
import com.xiaosiqi.quizbank.ui.common.SectionCard
import com.xiaosiqi.quizbank.ui.common.TypeChip
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 考试模式：
 * - 进来先按题库的组卷规则抽一套卷（每次都是新卷）
 * - 答题过程**不显示答案、不判分**，可以自由来回改
 * - 交卷后统一判分，生成成绩（总分/得分/百分制）并保存成考试记录
 * - 只有考试才会生成成绩并上传；普通练习只记错题
 */
/**
 * 考试模式。
 *
 * 和练习的区别是：进来先按组卷规则抽一套卷，**点「考试」的那一刻就落一条记录**，
 * 之后边答边判、边答边存——中途退出再进来能接着考；交卷后这条记录定型为成绩。
 *
 * 整个页面以 [ExamRecord] 为唯一真相来源（含题目快照与每题作答），
 * 所以「续考」不需要额外恢复逻辑：读出记录就能继续。
 */
class ExamViewModel(
    private val container: AppContainer,
    private val bankId: Long,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val bank: Bank? = null,
        /** 当前这场考试（进行中或已交卷） */
        val record: ExamRecord? = null,
        val index: Int = 0,
        /** 当前题的输入框内容（填空/简答） */
        val input: String = "",
        val submitted: Boolean = false,
        val reviewIndex: Int? = null,
        val empty: Boolean = false,
        /** 这次进来是接着上次考的 */
        val resumed: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val uploadState: String? = null,
        val canUpload: Boolean = false,
        val autoUpload: Boolean = false,
        val busy: Boolean = false,
        /** 云端同步状态，显示在标题下面那一行 */
        val cloudState: String? = null,
    ) {
        val details: List<ExamDetail> get() = record?.details.orEmpty()
        val totalQuestions: Int get() = details.size
        val totalPoints: Int get() = record?.totalPoints ?: 0
        val current: ExamDetail? get() = details.getOrNull(index)
        val answeredCount: Int get() = details.count { it.my.isNotBlank() }
        val currentType: QuestionType?
            get() = current?.let { QuestionType.fromText(it.type) }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private companion object {
        /** 进行中考试的云端同步节流间隔 */
        const val SYNC_THROTTLE_MS = 5_000L
    }

    init {
        start()
    }

    /** 有没交卷的就接着考，否则当场抽一套新卷并立刻落库。 */
    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val settings = container.settings.current()
            val data = withContext(Dispatchers.IO) {
                val bank = container.store.bank(bankId)
                val local = container.exams.inProgress(bankId)

                // 登录用户：再看看云端有没有更新的一份（换手机续考）
                val online = settings.loggedIn && settings.autoUploadScore
                val cloudLocal = if (online && container.exams.hasUploadTarget()) {
                    runCatching { container.exams.findInProgress(bankId) }.getOrNull()
                } else null

                var chosen = when {
                    cloudLocal == null -> local
                    local == null -> cloudLocal
                    cloudLocal.updatedAt > local.updatedAt -> cloudLocal
                    else -> local
                }
                var fromCloud = false
                if (chosen != null && chosen!!.localId == 0L) {
                    // 来自云端：落一份到本机，并把本机那份旧的清掉，避免同题库两条进行中
                    local?.let { container.exams.deleteLocal(it.localId) }
                    chosen = chosen!!.copy(localId = container.exams.saveLocal(chosen!!))
                    fromCloud = true
                }

                if (chosen != null) {
                    return@withContext Triple(bank, chosen, true to fromCloud)
                }

                val paper = if (bank == null) null
                else ExamBuilder.build(container.store.questions(bankId), bank.examBlueprint)
                if (paper == null || paper.questionCount == 0) {
                    return@withContext Triple(bank, null, false to false)
                }
                val fresh = ExamRecord.start(
                    paper = paper,
                    meta = metaOf(bank, settings, startedAt = System.currentTimeMillis(), finishedAt = 0L),
                )
                // 点「考试」就落库：这时候记录还是「进行中」
                Triple(bank, fresh.copy(localId = container.exams.saveLocal(fresh)), false to false)
            }
            val (bank, record, flags) = data
            val (resumed, fromCloud) = flags
            _state.update {
                UiState(
                    loading = false,
                    bank = bank,
                    record = record,
                    index = record?.index ?: 0,
                    empty = record == null,
                    resumed = resumed,
                    canUpload = container.exams.hasUploadTarget(),
                    autoUpload = settings.autoUploadScore,
                    message = when {
                        record == null -> null
                        resumed -> (if (fromCloud) "已从云端恢复" else "已恢复上次") +
                            "没考完的考试：第 ${record.index + 1}/${record.questionCount} 题，" +
                            "已答 ${record.details.count { d -> d.my.isNotBlank() }} 题"
                        else -> "本次试卷共 ${record.questionCount} 题、满分 ${record.totalPoints} 分"
                    },
                )
            }
        }
    }

    private fun metaOf(bank: Bank?, settings: AppSettings, startedAt: Long, finishedAt: Long) = ExamMeta(
        user = settings.effectiveName,
        bankId = bankId,
        bankName = bank?.name.orEmpty(),
        bankKey = bank?.remoteId?.ifBlank { bank?.fileUrl.orEmpty() }.orEmpty(),
        category = bank?.category.orEmpty(),
        startedAt = startedAt,
        finishedAt = finishedAt,
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    )

    /** 把记录里的一道题还原成可判分的题目。 */
    private fun questionOf(d: ExamDetail): Question = Question(
        id = 0L,
        orderIndex = d.index - 1,
        type = QuestionType.fromText(d.type) ?: QuestionType.SINGLE,
        stem = d.stem,
        options = d.optionKeys.mapIndexed { i, key -> Option(key, d.options.getOrNull(i).orEmpty()) },
        answers = d.answerKeys,
        analysis = d.analysis,
    )

    private fun split(text: String): List<String> =
        text.split('|', '｜', ';', '；', ',').map { it.trim() }.filter { it.isNotEmpty() }

    // ------------------------------------------------------------ 作答

    /** 选择题点选项：单选/判断立刻判分，多选先记下来等「提交答案」。 */
    fun choose(key: String) {
        val s = _state.value
        val d = s.current ?: return
        if (d.revealed || s.submitted) return
        if (s.currentType == QuestionType.MULTIPLE) {
            val now = d.my.split("|").filter { it.isNotBlank() }
            val next = (if (key in now) now - key else now + key).sorted()
            persist(s.index, next, correct = null, revealed = false)
        } else {
            gradeAndPersist(s.index, listOf(key))
        }
    }

    fun input(text: String) = _state.update { it.copy(input = text) }

    /** 多选/填空题的「提交答案」。 */
    fun submitAnswer() {
        val s = _state.value
        val d = s.current ?: return
        if (d.revealed || s.submitted) return
        val my = when (s.currentType) {
            QuestionType.MULTIPLE -> if (s.input.isNotBlank()) split(s.input)
            else d.my.split("|").filter { it.isNotBlank() }
            QuestionType.BLANK -> split(s.input)
            else -> emptyList()
        }
        if (my.isEmpty()) return
        gradeAndPersist(s.index, my)
    }

    /** 不会做，直接看答案（主观题也走这里）。 */
    fun reveal() {
        val s = _state.value
        val d = s.current ?: return
        if (d.revealed || s.submitted) return
        persist(s.index, emptyList(), correct = null, revealed = true)
    }

    /** 主观题自评。 */
    fun selfGrade(correct: Boolean) {
        val s = _state.value
        val updated = s.record?.withSelfGrade(s.index, correct) ?: return
        _state.update { it.copy(record = updated, input = "") }
        saveAsync(updated)
    }

    /** 成绩单里对某一题自评。 */
    fun selfGradeAt(index: Int, correct: Boolean) {
        val s = _state.value
        val updated = s.record?.withSelfGrade(index, correct) ?: return
        _state.update { it.copy(record = updated) }
        saveAsync(updated)
    }

    private fun gradeAndPersist(index: Int, my: List<String>) {
        val d = _state.value.details.getOrNull(index) ?: return
        val correct = AnswerJudge.isCorrect(questionOf(d), my)
        persist(index, my, correct, revealed = true)
    }

    /** 改内存状态 + 立即落本地库（实时记录考试进度），再按节流同步云端。 */
    private fun persist(index: Int, my: List<String>, correct: Boolean?, revealed: Boolean) {
        val s = _state.value
        val record = s.record ?: return
        val updated = record.withAnswer(index, my, correct, revealed, s.index)
        _state.update { it.copy(record = updated, input = "") }
        saveAsync(updated)
        scheduleCloudSync()
    }

    private fun saveAsync(record: ExamRecord) {
        viewModelScope.launch { withContext(Dispatchers.IO) { container.exams.saveLocal(record) } }
    }

    // ------------------------------------------------------- 云端同步（节流）

    private var lastCloudSyncAt = 0L
    private var syncJob: Job? = null

    private fun canSyncToCloud(): Boolean {
        val settings = container.settings.current()
        return settings.loggedIn && settings.autoUploadScore && container.exams.hasUploadTarget()
    }

    /**
     * 把「进行中」的考试同步到云端。
     *
     * 本地一定是先写好的，所以这里**失败也不影响答题**，只更新一个状态文字，
     * 下次作答会再试；并且节流（最快 [SYNC_THROTTLE_MS] 一次），避免每答一题都打网络。
     */
    private fun scheduleCloudSync(force: Boolean = false) {
        if (_state.value.submitted || !canSyncToCloud()) return
        if (syncJob?.isActive == true && !force) return
        val wait = if (force) 0L
        else (SYNC_THROTTLE_MS - (System.currentTimeMillis() - lastCloudSyncAt)).coerceAtLeast(0L)
        syncJob = viewModelScope.launch {
            if (wait > 0) delay(wait)
            doCloudSync()
        }
    }

    private suspend fun doCloudSync() {
        val record = _state.value.record ?: return
        if (record.localId <= 0L) return
        lastCloudSyncAt = System.currentTimeMillis()
        runCatching { container.exams.upload(record) }
            .onSuccess { path ->
                _state.update {
                    it.copy(
                        cloudState = "已同步到云端",
                        record = it.record?.copy(uploaded = true, remotePath = path),
                    )
                }
            }
            .onFailure {
                _state.update { it.copy(cloudState = "云端同步失败，答题不受影响，稍后自动重试") }
            }
    }

    /** 离开页面时补一次，确保最后的状态落地（用 appScope，页面销毁也能跑完）。 */
    fun flushCloudSync() {
        if (_state.value.submitted || !canSyncToCloud()) return
        val record = _state.value.record ?: return
        if (record.localId <= 0L) return
        container.appScope.launch { runCatching { container.exams.upload(record) } }
    }

    // ------------------------------------------------------------ 导航

    fun next() {
        val s = _state.value
        if (s.index >= s.totalQuestions - 1) return
        goto(s.index + 1)
    }

    fun prev() {
        val s = _state.value
        if (s.index <= 0) return
        goto(s.index - 1)
    }

    fun goto(index: Int) {
        val s = _state.value
        if (index !in s.details.indices) return
        _state.update { it.copy(index = index, input = "", reviewIndex = null) }
        s.record?.let { saveAsync(it.copy(index = index)) }
        scheduleCloudSync()
    }

    // ------------------------------------------------------------ 交卷

    fun submit() {
        val s = _state.value
        val record = s.record ?: return
        if (s.submitted) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val finished = record.copy(index = s.index).finish(System.currentTimeMillis())
            withContext(Dispatchers.IO) { container.exams.saveLocal(finished) }
            _state.update { it.copy(busy = false, submitted = true, record = finished) }
            if (canSyncToCloud()) {
                upload()
                // 交卷后文件名变了，把旧的「进行中」文件从云端清掉
                if (record.remotePath.isNotBlank()) {
                    withContext(Dispatchers.IO) { container.exams.removeRemote(record.remotePath) }
                }
            }
        }
    }

    fun upload() {
        val record = _state.value.record ?: return
        if (record.localId <= 0L) return
        viewModelScope.launch {
            _state.update { it.copy(uploadState = "正在上传考试记录…") }
            val local = withContext(Dispatchers.IO) { container.exams.record(record.localId) } ?: record
            runCatching { container.exams.upload(local) }
                .onSuccess { path ->
                    _state.update {
                        it.copy(
                            uploadState = "已上传到：\n$path",
                            record = it.record?.copy(uploaded = true, remotePath = path),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(uploadState = "上传失败：${e.message}") } }
        }
    }

    fun clearUploadState() = _state.update { it.copy(uploadState = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun openReview(index: Int?) = _state.update { it.copy(reviewIndex = index) }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(container: AppContainer, nav: NavController, bankId: Long) {
    val vm: ExamViewModel = viewModel(
        key = "exam-$bankId",
        factory = VmFactory { ExamViewModel(container, bankId) },
    )
    val state by vm.state.collectAsState()
    var showCard by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }

    // 交卷后展开的某道题，用返回键先收起来，再退页面
    BackHandler(enabled = state.reviewIndex != null) { vm.openReview(null) }

    Scaffold(
        floatingActionButton = {
            if (!state.loading && !state.empty && state.totalQuestions > 0 && !state.submitted) {
                ExtendedFloatingActionButton(
                    onClick = { showCard = true },
                    icon = { Icon(Icons.Filled.List, contentDescription = "题目清单") },
                    text = { Text("题目清单 ${state.index + 1}/${state.totalQuestions}") },
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            state.bank?.name ?: "考试",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        if (state.totalQuestions > 0) {
                            Text(
                                if (state.submitted) "考试已完成 · 满分 ${state.totalPoints} 分"
                                else "考试中 · 第 ${state.index + 1}/${state.totalQuestions} 题 · 满分 ${state.totalPoints} 分",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },

            )
        },
        bottomBar = {
            if (!state.loading && !state.empty && !state.submitted) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = vm::prev, enabled = state.index > 0, modifier = Modifier.weight(1f)) {
                            Text("上一题")
                        }
                        Button(
                            onClick = { if (state.index >= state.totalQuestions - 1) confirmSubmit = true else vm.next() },
                            modifier = Modifier.weight(1.4f),
                        ) {
                            Text(if (state.index >= state.totalQuestions - 1) "交卷" else "下一题")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox("正在组卷…")
                state.empty -> EmptyState(
                    title = "这个题库还没有题目",
                    description = "先去同步或导入题库，再来考试。",
                    action = { OutlinedButton(onClick = { nav.popBackStack() }) { Text("返回") } },
                )
                state.submitted -> ExamReport(
                    state = state,
                    onSelfGrade = vm::selfGradeAt,
                    onUpload = vm::upload,
                    onFinish = { nav.popBackStack() },
                    onReview = { vm.openReview(it) },
                )
                else -> ExamPager(state = state, vm = vm)
            }

            if (state.busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SectionCard(modifier = Modifier.padding(32.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("正在交卷…")
                        }
                    }
                }
            }
        }
    }

    if (showCard) {
        ExamAnswerCard(state = state, onJump = { vm.goto(it); showCard = false }, onDismiss = { showCard = false })
    }

    if (confirmSubmit) {
        val unanswered = state.totalQuestions - state.answeredCount
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text("确认交卷") },
            text = {
                Text(
                    if (unanswered > 0) "还有 $unanswered 题没有作答，交卷后不能再修改。"
                    else "所有题目都已作答，交卷后不能再修改。"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSubmit = false; vm.submit() }) { Text("确定交卷") }
            },
            dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("再检查一下") } },
        )
    }

    state.uploadState?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearUploadState,
            title = { Text("考试记录上传") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = vm::clearUploadState) { Text("好的") } },
        )
    }
    state.message?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            title = { Text("开始考试") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text("开始答题") } },
        )
    }
    ErrorDialog(message = state.error, onDismiss = vm::clearError)
}

@Composable
private fun ExamPager(state: ExamViewModel.UiState, vm: ExamViewModel) {
    if (state.totalQuestions == 0) return
    val pagerState = rememberPagerState(
        initialPage = state.index.coerceIn(0, state.totalQuestions - 1),
        pageCount = { state.totalQuestions },
    )
    LaunchedEffect(state.index, state.totalQuestions) {
        if (pagerState.currentPage != state.index) pagerState.animateScrollToPage(state.index)
    }
    LaunchedEffect(pagerState, state.totalQuestions) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page != vm.state.value.index) vm.goto(page)
        }
    }
    // 预组合左右相邻页：不预组合的话，下一页是在手指开始滑的那一帧才首次组合/测量，
    // 题干长一点就会掉帧。多留一页的代价很小，换来滑动跟手。
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
    ) { page ->
        ExamQuestion(state = state, vm = vm, index = page)
    }
}

/** 单页考题。只有当前页可作答，相邻页从各自的作答记录里还原选中状态。 */
@Composable
private fun ExamQuestion(state: ExamViewModel.UiState, vm: ExamViewModel, index: Int) {
    val d = state.details.getOrNull(index) ?: return
    val isCurrent = index == state.index
    val type = QuestionType.fromText(d.type) ?: QuestionType.SINGLE
    val picked = d.my.split("|").filter { it.isNotBlank() }
    val imageBase = state.bank?.imageBase.orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeChip(type)
            Text(
                "第 ${index + 1} 题 · ${d.score} 分",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (d.revealed) {
                Text(
                    when (d.correct) {
                        true -> "已答对"
                        false -> "已答错"
                        null -> "已看答案"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (d.correct) {
                        true -> Color(0xFF16A34A)
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        RichTextView(raw = d.stem, imageBase = imageBase)

        when {
            d.optionKeys.isNotEmpty() -> d.optionKeys.forEachIndexed { i, key ->
                ExamOptionRow(
                    key = key,
                    text = d.options.getOrNull(i).orEmpty(),
                    imageBase = imageBase,
                    selected = key in picked,
                    revealed = d.revealed,
                    isCorrectOption = key in d.answerKeys,
                    multi = type == QuestionType.MULTIPLE,
                    onClick = { if (isCurrent) vm.choose(key) },
                )
            }
            type == QuestionType.BLANK -> OutlinedTextField(
                value = if (isCurrent) state.input else picked.joinToString("|"),
                onValueChange = { if (isCurrent) vm.input(it) },
                enabled = isCurrent && !d.revealed,
                label = { Text(if (d.answerKeys.size > 1) "多个空用 | 分隔" else "填写答案") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default,
            )
            else -> OutlinedTextField(
                value = if (isCurrent) state.input else d.my,
                onValueChange = { if (isCurrent) vm.input(it) },
                enabled = isCurrent && !d.revealed,
                label = { Text("作答（点「看答案」后自评）") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 即时反馈：判完就地把答案和解析显示出来
        if (d.revealed) {
            SectionCard {
                Text(
                    when (d.correct) {
                        true -> "回答正确"
                        false -> "回答错误"
                        null -> "参考答案"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (d.correct) {
                        true -> Color(0xFF16A34A)
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text("正确答案：${d.answer}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (d.correct == null && type == QuestionType.ESSAY && isCurrent) {
                    Spacer(Modifier.height(10.dp))
                    Text("主观题请自行对照参考答案评分：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.selfGrade(true) }, modifier = Modifier.weight(1f)) { Text("我答对了") }
                        OutlinedButton(onClick = { vm.selfGrade(false) }, modifier = Modifier.weight(1f)) { Text("我答错了") }
                    }
                }
                if (d.analysis.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("解析", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    RichTextView(raw = d.analysis, imageBase = imageBase)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExamOptionRow(
    key: String,
    text: String,
    imageBase: String,
    selected: Boolean,
    multi: Boolean,
    revealed: Boolean = false,
    isCorrectOption: Boolean = false,
    onClick: () -> Unit,
) {
    val okColor = Color(0xFF16A34A)
    val borderColor = when {
        revealed && isCorrectOption -> okColor
        revealed && selected -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                revealed && isCorrectOption -> okColor.copy(alpha = 0.12f)
                revealed && selected -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(if (multi) RoundedCornerShape(6.dp) else CircleShape)
                    .background(
                        when {
                            revealed && isCorrectOption -> okColor
                            revealed && selected -> MaterialTheme.colorScheme.error
                            selected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    key,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                RichTextView(raw = text, imageBase = imageBase, selectable = false)
            }
        }
    }
}

@Composable
private fun ExamAnswerCard(
    state: ExamViewModel.UiState,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("答题卡") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "已答 ${state.answeredCount} / ${state.totalQuestions}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (0 until state.totalQuestions).chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { index ->
                            val answered = state.details.getOrNull(index)?.my?.isNotBlank() == true
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (answered) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { onJump(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (answered) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onJump(state.index) }) { Text("继续答题") } },
    )
}

/** 交卷后的成绩单 + 逐题回顾。 */
@Composable
private fun ExamReport(
    state: ExamViewModel.UiState,
    onSelfGrade: (Int, Boolean) -> Unit,
    onUpload: () -> Unit,
    onFinish: () -> Unit,
    onReview: (Int?) -> Unit,
) {
    val record = state.record
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Text("考试成绩", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${record?.percent ?: 0}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if ((record?.percent ?: 0) >= 60) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                )
                Text(" 分", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${record?.scoredPoints ?: 0} / ${record?.totalPoints ?: 0} 分", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${record?.correctCount ?: 0} 对 · ${record?.wrongCount ?: 0} 错 · 用时 ${record?.durationText ?: "-"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if ((record?.selfGraded ?: 0) > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "有 ${record?.selfGraded} 道主观题还没自评，点下面的题目标「我答对了/我答错了」就会计入成绩。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canUpload) {
                    Button(onClick = onUpload) {
                        Text(if (record?.uploaded == true) "重新上传" else "上传考试记录")
                    }
                }
                OutlinedButton(onClick = onFinish) { Text("完成") }
            }
            if (record?.uploaded == true) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "已上传 · ${record.remotePath.substringAfterLast('/')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SectionCard {
            Text("逐题回顾", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "点某一题可以展开看题干、选项和解析。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            record?.details?.forEachIndexed { index, detail ->
                val expanded = state.reviewIndex == index
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onReview(if (expanded) null else index) }
                        .padding(vertical = 8.dp)
                ) {
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
                            Text(
                                "${detail.index}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${detail.type} · ${detail.score}分",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            when (detail.correct) {
                                true -> "答对"
                                false -> "答错"
                                null -> "待自评"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (detail.correct) {
                                true -> Color(0xFF16A34A)
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        TextNorm.oneLine(detail.stem).take(60),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        RichTextView(raw = detail.stem, imageBase = state.bank?.imageBase.orEmpty())
                        detail.options.forEachIndexed { i, option ->
                            Text(
                                "${detail.optionKeys.getOrNull(i)?.let { "$it. " }.orEmpty()}$option",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("我的作答：${detail.my.ifBlank { "（未作答）" }}", style = MaterialTheme.typography.bodySmall)
                        Text("正确答案：${detail.answer}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        if (detail.analysis.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            RichTextView(raw = detail.analysis, imageBase = state.bank?.imageBase.orEmpty())
                        }
                        if (detail.correct == null) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onSelfGrade(index, true) }, modifier = Modifier.weight(1f)) {
                                    Text("我答对了")
                                }
                                OutlinedButton(onClick = { onSelfGrade(index, false) }, modifier = Modifier.weight(1f)) {
                                    Text("我答错了")
                                }
                            }
                        }
                    }
                }
                if (index != (record?.details?.size ?: 0) - 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
