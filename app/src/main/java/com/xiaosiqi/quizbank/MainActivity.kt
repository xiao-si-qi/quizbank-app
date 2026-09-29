package com.xiaosiqi.quizbank

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.xiaosiqi.quizbank.ui.AppNav
import com.xiaosiqi.quizbank.ui.theme.QuizBankTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = appContainer
        setContent {
            val settings by container.settings.state.collectAsState()
            val dark = when (settings.darkTheme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            QuizBankTheme(darkTheme = dark, fontScale = settings.fontScale) {
                AppNav(container)
            }
        }
    }
}
