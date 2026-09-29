package com.xiaosiqi.quizbank.ui.collection

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.model.PracticeMode
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.rich.RichText
import com.xiaosiqi.quizbank.ui.CollectionKind
import com.xiaosiqi.quizbank.ui.Routes
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.EmptyState
import com.xiaosiqi.quizbank.ui.common.LoadingBox
import com.xiaosiqi.quizbank.ui.common.SectionCard
import com.xiaosiqi.quizbank.ui.common.TypeChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectionViewModel(
    private val container: AppContainer,
    private val kind: String,
) : ViewModel() {

    data class Item(val question: Question, val bankName: String)

    data class UiState(
        val loading: Boolean = true,
        val items: List<Item> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val isWrong = kind == CollectionKind.WRONG

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val names = container.store.banks().associate { it.id to it.name }
                val questions = if (isWrong) container.store.wrongQuestions(null) else container.store.favoriteQuestions()
                questions.map { Item(it, names[it.bankId] ?: "未知题库") }
            }
            _state.value = UiState(loading = false, items = data)
        }
    }

    fun remove(item: Item) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (isWrong) container.store.removeWrong(item.question.id)
                else container.store.toggleFavorite(item.question.id, item.question.bankId)
            }
            _state.update { current ->
                current.copy(
                    items = current.items.filterNot { it.question.id == item.question.id },
                    message = if (isWrong) "已移出错题本" else "已取消收藏",
                )
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.store.clearWrong(null) }
            _state.update { it.copy(items = emptyList(), message = "错题本已清空") }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun practiceMode(): PracticeMode = if (isWrong) PracticeMode.WRONG else PracticeMode.FAVORITE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(container: AppContainer, nav: NavController, kind: String) {
    val vm: CollectionViewModel = viewModel(factory = VmFactory { CollectionViewModel(container, kind) })
    val state by vm.state.collectAsState()
    val isWrong = kind == CollectionKind.WRONG
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text(if (isWrong) "错题本" else "我的收藏") },
                actions = {
                    if (isWrong && state.items.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "清空")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                val firstBank = state.items.first().question.bankId
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    Button(
                        onClick = { nav.navigate(Routes.practice(firstBank, vm.practiceMode())) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isWrong) "开始错题练习" else "开始收藏练习")
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox("正在加载…")
                state.items.isEmpty() -> EmptyState(
                    title = if (isWrong) "错题本是空的" else "还没有收藏题目",
                    description = if (isWrong) "答错的题会自动进这里，答对后可以移出。"
                    else "练习时点右上角的爱心就能收藏题目。",
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.question.id }) { item ->
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypeChip(item.question.type)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.bankName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { vm.remove(item) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "移除", modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                previewText(item.question.stem),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "答案：${item.question.answerText()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空错题本") },
            text = { Text("会删除所有题库的错题记录，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }

    state.message?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            title = { Text("提示") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text("好的") } },
        )
    }
}

/** 列表里只显示纯文字，去掉图片标记。 */
internal fun previewText(raw: String): String {
    val texts = RichText.parse(raw).filterIsInstance<RichText.Block.Text>().map { it.text }
    val joined = texts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    val hasImage = RichText.parse(raw).any { it is RichText.Block.Image }
    return if (hasImage) "［图］$joined" else joined
}
