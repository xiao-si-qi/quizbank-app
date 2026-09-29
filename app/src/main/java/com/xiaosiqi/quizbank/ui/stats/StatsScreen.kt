package com.xiaosiqi.quizbank.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.xiaosiqi.quizbank.model.BankStats
import com.xiaosiqi.quizbank.model.DayCount
import com.xiaosiqi.quizbank.model.OverallStats
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.EmptyState
import com.xiaosiqi.quizbank.ui.common.LoadingBox
import com.xiaosiqi.quizbank.ui.common.SectionCard
import com.xiaosiqi.quizbank.ui.common.StatCell
import com.xiaosiqi.quizbank.ui.common.ThinProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsViewModel(private val container: AppContainer) : ViewModel() {

    data class BankRow(val name: String, val category: String, val stats: BankStats)

    data class UiState(
        val loading: Boolean = true,
        val overall: OverallStats = OverallStats(),
        val days: List<DayCount> = emptyList(),
        val banks: List<BankRow> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val overall = container.store.overallStats(14)
                val days = container.store.dailyCounts(14)
                val banks = container.store.banks().map { bank ->
                    BankRow(bank.name, bank.category, container.store.stats(bank.id))
                }.filter { it.stats.questionCount > 0 }
                Triple(overall, days, banks)
            }
            _state.value = UiState(false, data.first, data.second, data.third)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(container: AppContainer, nav: NavController) {
    val vm: StatsViewModel = viewModel(factory = VmFactory { StatsViewModel(container) })
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("学习统计") },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) {
                LoadingBox("正在统计…")
            } else if (state.overall.questionCount == 0) {
                EmptyState(title = "还没有数据", description = "先去练几题，这里就会有统计了。")
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionCard {
                        Text("总览", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatCell("答题次数", "${state.overall.answered}")
                            StatCell(
                                "正确率",
                                if (state.overall.answered == 0) "—" else "${(state.overall.accuracy * 100).toInt()}%",
                                valueColor = MaterialTheme.colorScheme.primary,
                            )
                            StatCell("错题", "${state.overall.wrongCount}", valueColor = MaterialTheme.colorScheme.error)
                            StatCell("收藏", "${state.overall.favoriteCount}")
                        }
                        Spacer(Modifier.height(12.dp))
                        ThinProgress(state.overall.accuracy)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "题库 ${state.overall.bankCount} 个 · 题目 ${state.overall.questionCount} 道",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SectionCard {
                        Text("最近 14 天", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        DailyBars(state.days)
                    }

                    state.banks.forEach { row ->
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                                    if (row.category.isNotBlank()) {
                                        Text(
                                            row.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    if (row.stats.answered == 0) "未练习"
                                    else "${(row.stats.accuracy * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            ThinProgress(row.stats.progress)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "完成 ${row.stats.distinctAnswered}/${row.stats.questionCount} · " +
                                    "答对 ${row.stats.correct} · 答错 ${row.stats.wrong} · 错题 ${row.stats.wrongCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun DailyBars(days: List<DayCount>) {
    if (days.isEmpty()) {
        Text("暂无数据", style = MaterialTheme.typography.bodySmall)
        return
    }
    val max = (days.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (day.total > 0) {
                    Text(
                        "${day.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                val ratio = day.total.toFloat() / max
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((90 * ratio).dp.coerceAtLeast(if (day.total > 0) 3.dp else 1.dp))
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (day.total == 0) MaterialTheme.colorScheme.surfaceVariant
                            else Color(0xFF2563EB)
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    day.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
