package com.xiaosiqi.quizbank.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.appContainer

class VmFactory<T : ViewModel>(private val create: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

@Composable
fun rememberContainer(): AppContainer = LocalContext.current.appContainer
