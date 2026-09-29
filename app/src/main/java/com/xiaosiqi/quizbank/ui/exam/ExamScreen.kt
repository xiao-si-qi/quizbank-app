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
class ExamViewModel(
    private val container: AppContainer,
    private val bankId: Long,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val bank: Bank? = null,
        val paper: ExamPaper? = null,
        val index: Int = 0,
        val choices: Map<Int, List<String>> = emptyMap(),
        val texts: Map<Int, String> = emptyMap(),
        val submitted: Boolean = false,
        val reviewIndex: Int? = null,
        val record: ExamRecord? = null,
        val result: ExamResult? = null,
        val empty: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val uploadState: String? = null,
        val canUpload: Boolean = false,
        val autoUpload: Boolean = false,
        val busy: Boolean = false,
    ) {
        val totalQuestions: Int get() = paper?.questionCount ?: 0
        val totalPoints: Int get() = paper?.totalPoints ?: 0
        val current: Question? get() = paper?.items?.getOrNull(index)?.question
        val currentScore: Int get() = paper?.items?.getOrNull(index)?.score ?: 0
        val answeredCount: Int get() = choices.size + texts.count { it.value.isNotBlank() }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var startedAt = System.currentTimeMillis()

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val data = withContext(Dispatchers.IO) {
                val bank = container.store.bank(bankId)
                val questions = container.store.questions(bankId)
                val paper = if (bank == null) null else ExamBuilder.build(questions, bank.examBlueprint)
                Triple(bank, questions.size, paper)
            }
            val (bank, available, paper) = data
            val settings = container.settings.current()
            startedAt = System.currentTimeMillis()
            _state.update {
                UiState(
                    loading = false,
                    bank = bank,
                    paper = paper,
                    empty = paper == null || paper.questionCount == 0,
                    canUpload = container.exams.hasUploadTarget(),
                    autoUpload = settings.autoUploadScore,
                    message = if (bank != null && paper != null && paper.questionCount > 0) {
                        "本次试卷共 ${paper.questionCount} 题、满分 ${paper.totalPoints} 分"
                    } else null,
                )
            }
            if (bank != null && paper != null && paper.questionCount == 0 && available == 0) {
                _state.update { it.copy(empty = true) }
            }
        }
    }

    // ------------------------------------------------------------ 作答

    fun choose(key: String) {
        val s = _state.value
        val question = s.current ?: return
        if (s.submitted) return
        when (question.type) {
            QuestionType.MULTIPLE -> {
                val now = s.choices[s.index].orEmpty()
                val next = if (key in now) now - key else now + key
                _state.update { it.copy(choices = it.choices + (s.index to next.sorted())) }
            }
            else -> _state.update { it.copy(choices = it.choices + (s.index to listOf(key))) }
        }
    }

    fun input(text: String) {
        val s = _state.value
        if (s.submitted) return
        _state.update { it.copy(texts = it.texts + (s.index to text)) }
    }

    fun next() {
        val s = _state.value
        if (s.index < s.totalQuestions - 1) _state.update { it.copy(index = s.index + 1) }
    }

    fun prev() {
        val s = _state.value
        if (s.index > 0) _state.update { it.copy(index = s.index - 1) }
    }

    fun goto(index: Int) {
        val s = _state.value
        if (index in 0 until s.totalQuestions) {
            _state.update { it.copy(index = index, reviewIndex = null) }
        }
    }

    // ------------------------------------------------------------ 交卷

    fun submit() {
        val s = _state.value
        val paper = s.paper ?: return
        if (s.submitted) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val finishedAt = System.currentTimeMillis()
            val results = paper.items.mapIndexed { index, item ->
                index to grade(item.question, s.choices[index], s.texts[index])
            }.toMap()
            val userAnswers = paper.items.indices.associateWith { index ->
                s.choices[index] ?: s.texts[index]?.takeIf { it.isNotBlank() }?.let { splitText(it) } ?: emptyList()
            }
            val settings = container.settings.current()
            val record = ExamRecord.from(
                paper = paper,
                results = results,
                userAnswers = userAnswers,
                meta = ExamMeta(
                    user = settings.effectiveName,
                    bankId = bankId,
                    bankName = s.bank?.name.orEmpty(),
                    bankKey = s.bank?.remoteId?.ifBlank { s.bank?.fileUrl.orEmpty() }.orEmpty(),
                    category = s.bank?.category.orEmpty(),
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                ),
            )
            val id = withContext(Dispatchers.IO) { container.exams.saveLocal(record) }
            val saved = record.copy(localId = id)
            _state.update {
                it.copy(
                    busy = false,
                    submitted = true,
                    record = saved,
                    result = ExamBuilder.score(paper, results),
                )
            }
            if (settings.autoUploadScore && container.exams.hasUploadTarget()) upload()
        }
    }

    private fun grade(question: Question, choice: List<String>?, text: String?): Boolean? = when (question.type) {
        QuestionType.ESSAY -> null
        QuestionType.BLANK -> {
            val answers = text?.takeIf { it.isNotBlank() }?.let { splitText(it) }
            if (answers.isNullOrEmpty()) false else AnswerJudge.isCorrect(question, answers)
        }
        else -> AnswerJudge.isCorrect(question, choice.orEmpty())
    }

    private fun splitText(text: String): List<String> =
        text.split('|', '｜', ';', '；', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 主观题自评：改分并更新本地记录。 */
    fun selfGrade(index: Int, correct: Boolean) {
        val s = _state.value
        val record = s.record ?: return
        val paper = s.paper ?: return
        if (index !in record.details.indices) return
        viewModelScope.launch {
            val details = record.details.toMutableList()
            details[index] = details[index].copy(correct = correct)
            val scored = paper.items.indices.sumOf { i ->
                val ok = details.getOrNull(i)?.correct == true
                if (ok) paper.items[i].score else 0
            }
            val correctCount = details.count { it.correct == true }
            val wrongCount = details.count { it.correct == false }
            val selfGraded = details.count { it.correct == null }
            val total = record.totalPoints
            val updated = record.copy(
                details = details,
                scoredPoints = scored,
                percent = if (total <= 0) 0 else (scored * 100 + total / 2) / total,
                correctCount = correctCount,
                wrongCount = wrongCount,
                selfGraded = selfGraded,
                uploaded = false, // 分数变了，需要重新上传
            )
            withContext(Dispatchers.IO) { container.exams.saveLocal(updated) }
            _state.update { it.copy(record = updated) }
        }
    }

    fun upload() {
        val record = _state.value.record ?: return
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
                    onSelfGrade = vm::selfGrade,
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
    val item = state.paper?.items?.getOrNull(index) ?: return
    val question = item.question
    val isCurrent = index == state.index
    val selected = state.choices[index].orEmpty()
    val text = state.texts[index].orEmpty()
    val imageBase = state.bank?.imageBase.orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeChip(question.type)
            Text(
                "第 ${index + 1} 题 · ${item.score} 分",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (!isCurrent) {
                // 相邻页给个提示，避免以为点不动是坏了
                Text(
                    "滑动或点「下一题」回到这题作答",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        RichTextView(raw = question.stem, imageBase = imageBase)

        when {
            question.options.isNotEmpty() -> question.options.forEach { option ->
                ExamOptionRow(
                    key = option.key,
                    text = option.text,
                    imageBase = imageBase,
                    selected = option.key in selected,
                    multi = question.type == QuestionType.MULTIPLE,
                    onClick = { if (isCurrent) vm.choose(option.key) },
                )
            }
            question.type == QuestionType.BLANK -> OutlinedTextField(
                value = text,
                onValueChange = { if (isCurrent) vm.input(it) },
                enabled = isCurrent,
                label = { Text(if (question.answers.size > 1) "多个空用 | 分隔" else "填写答案") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default,
            )
            else -> OutlinedTextField(
                value = text,
                onValueChange = { if (isCurrent) vm.input(it) },
                enabled = isCurrent,
                label = { Text("作答（主观题交卷后自评）") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
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
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(if (multi) RoundedCornerShape(6.dp) else CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
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
                            val answered = state.choices.containsKey(index) ||
                                !state.texts[index].isNullOrBlank()
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
                        detail.options.forEach { option ->
                            Text(option, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
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
