package com.xiaosiqi.quizbank.ui.practice

import android.os.Build
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.domain.AnswerJudge
import com.xiaosiqi.quizbank.domain.SessionBuilder
import com.xiaosiqi.quizbank.model.Bank
import com.xiaosiqi.quizbank.model.BankProgress
import com.xiaosiqi.quizbank.model.PracticeMode
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

class PracticeViewModel(
    private val container: AppContainer,
    private val bankId: Long,
    private val mode: PracticeMode,
    private val startIndex: Int,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val bank: Bank? = null,
        val questions: List<Question> = emptyList(),
        val index: Int = 0,
        val selected: List<String> = emptyList(),
        val input: String = "",
        val revealed: Boolean = false,
        val lastCorrect: Boolean? = null,
        val favorite: Boolean = false,
        val answeredCount: Int = 0,
        val correctCount: Int = 0,
        val wrongCount: Int = 0,
        val selfGradedCount: Int = 0,
        val results: Map<Int, Boolean?> = emptyMap(),
        /** 每道题各自的作答（滑到别的题再滑回来要能还原） */
        val answers: Map<Int, List<String>> = emptyMap(),
        val finished: Boolean = false,
        val empty: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val showAnalysis: Boolean = true,
        val autoRemoveWrong: Boolean = true,
    ) {
        val current: Question? get() = questions.getOrNull(index)
        val total: Int get() = questions.size
        val progress: Float get() = if (total == 0) 0f else (index + 1).toFloat() / total
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val startedAt = System.currentTimeMillis()
    private val userAnswers = HashMap<Int, List<String>>()

    /**
     * 收藏的题目 id。
     * 启动时一次性读出来缓存在内存里——之前 goto() 每切一题都同步查一次 SQLite，
     * 那是在主线程上做的磁盘 IO，滑动时会掉帧。
     */
    private var favoriteIds: Set<Long> = emptySet()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val settings = container.settings.current()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bank = container.store.bank(bankId)
                    val all = container.store.questions(bankId)
                    val wrongIds = container.store.wrongIds(bankId)
                    val favoriteIds = container.store.favoriteIds()
                    this@PracticeViewModel.favoriteIds = favoriteIds
                    val list = SessionBuilder.build(
                        questions = all,
                        mode = mode,
                        wrongIds = wrongIds,
                        favoriteIds = favoriteIds,
                        shuffleQuestions = settings.shuffleQuestions,
                        shuffleOptions = settings.shuffleOptions,
                    )
                    val progress = container.store.progress(bankId)
                    Triple(bank, list, progress)
                }
            }
            result.onSuccess { (bank, list, progress) ->
                val resumeAt = if (startIndex > 0) startIndex
                else if (mode == PracticeMode.SEQUENTIAL && progress != null && progress.mode == PracticeMode.SEQUENTIAL) progress.lastIndex
                else 0
                val index = resumeAt.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                _state.value = UiState(
                    loading = false,
                    bank = bank,
                    questions = list,
                    index = index,
                    empty = list.isEmpty(),
                    showAnalysis = settings.showAnalysisImmediately,
                    autoRemoveWrong = settings.autoRemoveWrong,
                    message = if (resumeAt > 0 && list.isNotEmpty()) "已跳到第 ${index + 1} 题" else null,
                )
            }.onFailure {
                _state.update { s -> s.copy(loading = false, error = it.message ?: "题库加载失败") }
            }
        }
    }

    // ------------------------------------------------------------ 作答交互

    fun selectOption(key: String) {
        val s = _state.value
        val question = s.current ?: return
        if (s.revealed) return
        when (question.type) {
            QuestionType.SINGLE, QuestionType.JUDGE -> {
                _state.update { it.copy(selected = listOf(key)) }
                grade(listOf(key))
            }
            QuestionType.MULTIPLE -> {
                val next = if (key in s.selected) s.selected - key else s.selected + key
                _state.update { it.copy(selected = next.sorted()) }
            }
            else -> Unit
        }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    /** 多选提交 / 填空提交。 */
    fun submit() {
        val s = _state.value
        val question = s.current ?: return
        if (s.revealed) return
        when (question.type) {
            QuestionType.MULTIPLE -> if (s.selected.isNotEmpty()) grade(s.selected)
            QuestionType.BLANK -> {
                val answers = s.input.split('|', '｜', ';', '；', ',').map { it.trim() }.filter { it.isNotEmpty() }
                if (answers.isNotEmpty()) grade(answers)
            }
            else -> Unit
        }
    }

    /** 直接看答案（不会自动判分，用于简答题或不会做的题）。 */
    fun reveal() {
        val s = _state.value
        if (s.revealed) return
        _state.update { it.copy(revealed = true, lastCorrect = null) }
    }

    /** 主观题自评。 */
    fun selfGrade(correct: Boolean) {
        val s = _state.value
        val question = s.current ?: return
        val answers = if (s.input.isNotBlank()) listOf(s.input.trim()) else listOf("（自评）")
        record(question, correct, answers)
    }

    private fun grade(answers: List<String>) {
        val question = _state.value.current ?: return
        val correct = AnswerJudge.isCorrect(question, answers)
        if (correct == null) {
            // 没有标准答案，只能展示参考答案让用户自评
            _state.update { it.copy(revealed = true, lastCorrect = null) }
            return
        }
        record(question, correct, answers)
    }

    private fun record(question: Question, correct: Boolean?, answers: List<String>) {
        val s = _state.value
        val index = s.index
        userAnswers[index] = answers
        val selfGraded = correct == null || question.type == QuestionType.ESSAY
        val results = s.results + (index to correct)
        val answered = s.answeredCount + 1
        val correctCount = s.correctCount + if (correct == true) 1 else 0
        val wrongCount = s.wrongCount + if (correct == false) 1 else 0
        val selfCount = s.selfGradedCount + if (selfGraded) 1 else 0

        _state.update {
            it.copy(
                revealed = true,
                lastCorrect = correct,
                selected = answers,
                results = results,
                answers = userAnswers.toMap(),
                answeredCount = answered,
                correctCount = correctCount,
                wrongCount = wrongCount,
                selfGradedCount = selfCount,
            )
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.store.recordAnswer(
                    bankId = bankId,
                    questionId = question.id,
                    correct = correct,
                    userAnswer = answers.joinToString("|"),
                    mode = mode,
                    autoRemoveWrong = container.settings.current().autoRemoveWrong,
                )
                container.store.saveProgress(
                    BankProgress(
                        bankId = bankId,
                        mode = mode,
                        lastIndex = index,
                        answered = answered,
                        correct = correctCount,
                        wrong = wrongCount,
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------ 导航

    fun next() {
        val s = _state.value
        if (s.index >= s.total - 1) {
            finish()
            return
        }
        goto(s.index + 1)
    }

    fun prev() {
        val s = _state.value
        if (s.index <= 0) return
        goto(s.index - 1)
    }

    fun goto(index: Int) {
        val s = _state.value
        if (index !in s.questions.indices) return
        val answered = s.results.containsKey(index)
        _state.update {
            it.copy(
                index = index,
                selected = userAnswers[index] ?: emptyList(),
                input = "",
                revealed = answered,
                lastCorrect = s.results[index],
                favorite = s.questions[index].id in favoriteIds,
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.store.saveProgress(
                    BankProgress(bankId, mode, index, s.answeredCount, s.correctCount, s.wrongCount)
                )
            }
        }
    }

    fun toggleFavorite() {
        val s = _state.value
        val question = s.current ?: return
        viewModelScope.launch {
            val now = withContext(Dispatchers.IO) { container.store.toggleFavorite(question.id, bankId) }
            favoriteIds = if (now) favoriteIds + question.id else favoriteIds - question.id
            _state.update { it.copy(favorite = now) }
        }
    }

    fun removeFromWrong() {
        val question = _state.value.current ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.store.removeWrong(question.id) }
            _state.update { it.copy(message = "已从错题本移除") }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------ 交卷

    /**
     * 练习结束：只展示本次结果，**不生成成绩、也不上传**。
     * 普通练习的产出是「错题本」和「阅读进度」；想留成绩请用考试模式。
     */
    fun finish() {
        val s = _state.value
        if (s.finished || s.questions.isEmpty()) return
        _state.update { it.copy(finished = true) }
    }

    fun dismissResult() = _state.update { it.copy(finished = false) }

    /** 错题本里重新练习时，答对后可以顺手移出错题本 */
    fun isWrongMode(): Boolean = mode == PracticeMode.WRONG
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    container: AppContainer,
    nav: NavController,
    bankId: Long,
    mode: PracticeMode,
    startIndex: Int,
) {
    val vm: PracticeViewModel = viewModel(
        key = "practice-$bankId-${mode.name}-$startIndex",
        factory = VmFactory { PracticeViewModel(container, bankId, mode, startIndex) },
    )
    val state by vm.state.collectAsState()
    var showCard by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            if (!state.loading && !state.empty && state.total > 0) {
                ExtendedFloatingActionButton(
                    onClick = { showCard = true },
                    icon = { Icon(Icons.Filled.List, contentDescription = "题目清单") },
                    text = { Text("题目清单 ${state.index + 1}/${state.total}") },
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
                        Text(state.bank?.name ?: "练习", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        if (state.total > 0) {
                            Text(
                                "${mode.label} · 第 ${state.index + 1}/${state.total} 题 · 正确 ${state.correctCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (state.total > 0) {
                        IconButton(onClick = vm::toggleFavorite) {
                            Icon(
                                if (state.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (state.favorite) Color(0xFFE11D48) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!state.loading && !state.empty) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = vm::prev, enabled = state.index > 0, modifier = Modifier.weight(1f)) {
                            Text("上一题")
                        }
                        val current = state.current
                        when {
                            current == null -> Unit
                            state.revealed -> Button(onClick = vm::next, modifier = Modifier.weight(1.4f)) {
                                Text(if (state.index >= state.total - 1) "交卷" else "下一题")
                            }
                            current.type == QuestionType.MULTIPLE -> Button(
                                onClick = vm::submit,
                                enabled = state.selected.isNotEmpty(),
                                modifier = Modifier.weight(1.4f),
                            ) { Text("提交答案") }
                            current.type == QuestionType.BLANK -> Button(
                                onClick = vm::submit,
                                enabled = state.input.isNotBlank(),
                                modifier = Modifier.weight(1.4f),
                            ) { Text("提交答案") }
                            else -> Button(onClick = vm::reveal, modifier = Modifier.weight(1.4f)) { Text("看答案") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox("正在准备题目…")
                state.empty -> EmptyState(
                    title = when (mode) {
                        PracticeMode.WRONG -> "错题本是空的"
                        PracticeMode.FAVORITE -> "还没有收藏题目"
                        else -> "这个题库还没有题目"
                    },
                    description = "换个模式试试，或者先导入/同步题库。",
                    action = { OutlinedButton(onClick = { nav.popBackStack() }) { Text("返回") } },
                )
                else -> QuestionPager(state = state, vm = vm)
            }
        }
    }

    if (showCard) {
        AnswerCardDialog(
            state = state,
            onJump = { index ->
                vm.goto(index)
                showCard = false
            },
            onDismiss = { showCard = false },
        )
    }

    if (state.finished) {
        ResultDialog(
            state = state,
            onDismiss = {
                vm.dismissResult()
                nav.popBackStack()
            },
            onRetry = { vm.dismissResult() },
        )
    }

    ErrorDialog(message = state.error, onDismiss = vm::clearError)
    state.message?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            title = { Text("提示") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text("知道了") } },
        )
    }
}

@Composable
private fun QuestionPager(state: PracticeViewModel.UiState, vm: PracticeViewModel) {
    if (state.total == 0) return
    val pagerState = rememberPagerState(
        initialPage = state.index.coerceIn(0, state.total - 1),
        pageCount = { state.total },
    )

    // 「上一题/下一题」和答题卡跳题：让翻页器跟着滚过去
    LaunchedEffect(state.index, state.total) {
        if (pagerState.currentPage != state.index) pagerState.animateScrollToPage(state.index)
    }
    // 手指左右滑动：同步回 ViewModel，之后所有渲染都以 state.index 为准
    LaunchedEffect(pagerState, state.total) {
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
        QuestionPage(state = state, vm = vm, index = page)
    }
}

/**
 * 单页题目。
 *
 * 只有「当前页」可以作答（输入框、选项点击），左右相邻的页也能显示，
 * 但它们从 [PracticeViewModel.UiState.answers] / `results` 里读自己的作答记录，
 * 所以滑动过程中不会串到别的题的选中状态。
 */
@Composable
private fun QuestionPage(state: PracticeViewModel.UiState, vm: PracticeViewModel, index: Int) {
    val question = state.questions.getOrNull(index) ?: return
    val isCurrent = index == state.index
    val picked = if (isCurrent) state.selected else state.answers[index].orEmpty()
    val result = state.results[index]
    val revealed = state.results.containsKey(index) || (isCurrent && state.revealed)
    val imageBase = state.bank?.imageBase.orEmpty()
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeChip(question.type)
            if (question.chapter.isNotBlank()) {
                Text(
                    question.chapter,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (question.difficulty > 0) {
                Text(
                    "★".repeat(question.difficulty.coerceIn(1, 5)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF59E0B),
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.results.containsKey(index)) {
                Text(
                    when (result) {
                        true -> "已答对"
                        false -> "已答错"
                        null -> "已看答案"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (result) {
                        true -> Color(0xFF16A34A)
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        RichTextView(raw = question.stem, imageBase = imageBase)

        when {
            question.type == QuestionType.BLANK && question.options.isEmpty() && !revealed -> {
                OutlinedTextField(
                    value = if (isCurrent) state.input else state.answers[index]?.joinToString("|").orEmpty(),
                    onValueChange = { if (isCurrent) vm.onInputChange(it) },
                    enabled = isCurrent,
                    label = { Text(if (question.answers.size > 1) "多个空用 | 分隔" else "填写答案") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
            question.options.isNotEmpty() -> {
                question.options.forEach { option ->
                    OptionRow(
                        key = option.key,
                        text = option.text,
                        imageBase = imageBase,
                        selected = option.key in picked,
                        revealed = revealed,
                        isCorrectOption = revealed && option.key in question.answers,
                        multi = question.type == QuestionType.MULTIPLE,
                        onClick = { if (isCurrent) vm.selectOption(option.key) },
                    )
                }
            }
        }

        if (revealed) {
            AnswerPanel(
                question = question,
                correct = result,
                imageBase = imageBase,
                interactive = isCurrent,
                wrongMode = vm.isWrongMode(),
                onRemoveWrong = vm::removeFromWrong,
                onSelfGrade = vm::selfGrade,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OptionRow(
    key: String,
    text: String,
    imageBase: String,
    selected: Boolean,
    revealed: Boolean,
    isCorrectOption: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        revealed && isCorrectOption -> Color(0xFF16A34A)
        revealed && selected && !isCorrectOption -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    val background = when {
        revealed && isCorrectOption -> Color(0xFF16A34A).copy(alpha = 0.10f)
        revealed && selected && !isCorrectOption -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(enabled = !revealed, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(if (multi) RoundedCornerShape(6.dp) else CircleShape)
                    .background(if (selected || (revealed && isCorrectOption)) borderColor else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    key,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected || (revealed && isCorrectOption)) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun AnswerPanel(
    question: Question,
    correct: Boolean?,
    imageBase: String,
    interactive: Boolean,
    wrongMode: Boolean,
    onRemoveWrong: () -> Unit,
    onSelfGrade: (Boolean) -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (correct) {
                    true -> "回答正确"
                    false -> "回答错误"
                    null -> "参考答案"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when (correct) {
                    true -> Color(0xFF16A34A)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.weight(1f))
            if (wrongMode && interactive) {
                TextButton(onClick = onRemoveWrong) { Text("移出错题本") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "正确答案：${question.answerText()}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (correct == null && question.type == QuestionType.ESSAY && interactive) {
            Spacer(Modifier.height(10.dp))
            Text("主观题请自行对照参考答案评分：", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSelfGrade(true) }, modifier = Modifier.weight(1f)) { Text("我答对了") }
                OutlinedButton(onClick = { onSelfGrade(false) }, modifier = Modifier.weight(1f)) { Text("我答错了") }
            }
        }

        if (question.analysis.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("解析", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            RichTextView(raw = question.analysis, imageBase = imageBase)
        }
    }
}

@Composable
private fun AnswerCardDialog(
    state: PracticeViewModel.UiState,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("答题卡") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "已答 ${state.results.size} / ${state.total}　正确 ${state.correctCount}　错误 ${state.wrongCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.questions.indices.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { index ->
                            val answered = state.results.containsKey(index)
                            val ok = state.results[index]
                            val bg = when {
                                !answered -> MaterialTheme.colorScheme.surfaceVariant
                                ok == true -> Color(0xFF16A34A)
                                ok == false -> MaterialTheme.colorScheme.error
                                else -> Color(0xFF64748B)
                            }
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
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
        confirmButton = {
            TextButton(onClick = { onJump(state.index) }) { Text("继续答题") }
        },
    )
}

@Composable
private fun ResultDialog(
    state: PracticeViewModel.UiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val answered = state.answeredCount
    val accuracy = if (answered == 0) 0 else state.correctCount * 100 / answered
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本次练习完成") },
        text = {
            Column {
                Text("正确率 $accuracy%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("共 ${state.total} 题，作答 $answered 题")
                Text("答对 ${state.correctCount} 题，答错 ${state.wrongCount} 题")
                if (state.selfGradedCount > 0) Text("其中自评题 ${state.selfGradedCount} 题")
                Spacer(Modifier.height(10.dp))
                Text(
                    "普通练习只帮你记错题，不生成成绩。想要成绩和考试记录，回题库点卡片上的「考试」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = onRetry) { Text("继续看题") } },
    )
}
