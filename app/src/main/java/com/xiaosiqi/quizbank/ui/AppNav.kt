package com.xiaosiqi.quizbank.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.model.PracticeMode
import com.xiaosiqi.quizbank.ui.browse.BrowserScreen
import com.xiaosiqi.quizbank.ui.collection.CollectionScreen
import com.xiaosiqi.quizbank.ui.home.HomeScreen
import com.xiaosiqi.quizbank.ui.login.LoginScreen
import com.xiaosiqi.quizbank.ui.practice.PracticeScreen
import com.xiaosiqi.quizbank.ui.exam.ExamRecordsScreen
import com.xiaosiqi.quizbank.ui.exam.ExamScreen
import com.xiaosiqi.quizbank.ui.settings.SettingsScreen
import com.xiaosiqi.quizbank.ui.stats.StatsScreen
import com.xiaosiqi.quizbank.ui.sync.SyncScreen

object Routes {
    const val HOME = "home"
    const val SYNC = "sync"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val BROWSER = "browser/{target}"
    const val STATS = "stats"
    const val EXAM_RECORDS = "exam-records"
    const val EXAM = "exam/{bankId}"
    const val COLLECTION = "collection/{kind}"
    const val PRACTICE = "practice/{bankId}/{mode}/{start}"

    fun browser(target: String) = "browser/$target"

    fun collection(kind: String) = "collection/$kind"

    fun practice(bankId: Long, mode: PracticeMode = PracticeMode.SEQUENTIAL, start: Int = 0) =
        "practice/$bankId/${mode.name}/$start"

    fun exam(bankId: Long) = "exam/$bankId"
}

object CollectionKind {
    const val WRONG = "wrong"
    const val FAVORITE = "favorite"
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(container, nav) }
        composable(Routes.SYNC) { SyncScreen(container, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(container, nav) }
        composable(Routes.LOGIN) { LoginScreen(container, nav) }
        composable(Routes.STATS) { StatsScreen(container, nav) }
        composable(
            route = Routes.EXAM,
            arguments = listOf(navArgument("bankId") { type = NavType.LongType }),
        ) { entry ->
            ExamScreen(container, nav, entry.arguments?.getLong("bankId") ?: 0L)
        }
        composable(Routes.EXAM_RECORDS) { ExamRecordsScreen(container, nav) }
        composable(
            route = Routes.BROWSER,
            arguments = listOf(navArgument("target") { type = NavType.StringType }),
        ) { entry ->
            BrowserScreen(container, nav, entry.arguments?.getString("target").orEmpty())
        }
        composable(
            route = Routes.COLLECTION,
            arguments = listOf(navArgument("kind") { type = NavType.StringType }),
        ) { entry ->
            CollectionScreen(container, nav, entry.arguments?.getString("kind").orEmpty())
        }
        composable(
            route = Routes.PRACTICE,
            arguments = listOf(
                navArgument("bankId") { type = NavType.LongType },
                navArgument("mode") { type = NavType.StringType },
                navArgument("start") { type = NavType.IntType },
            ),
        ) { entry ->
            val args = entry.arguments
            val bankId = args?.getLong("bankId") ?: 0L
            val mode = runCatching { PracticeMode.valueOf(args?.getString("mode").orEmpty()) }
                .getOrDefault(PracticeMode.SEQUENTIAL)
            val start = args?.getInt("start") ?: 0
            PracticeScreen(container, nav, bankId, mode, start)
        }
    }
}
