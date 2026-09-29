package com.xiaosiqi.quizbank.ui.login

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xiaosiqi.quizbank.AppContainer
import com.xiaosiqi.quizbank.alist.AlistAccount
import com.xiaosiqi.quizbank.data.prefs.AppSettings
import com.xiaosiqi.quizbank.ui.VmFactory
import com.xiaosiqi.quizbank.ui.common.ErrorDialog
import com.xiaosiqi.quizbank.ui.common.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val baseUrl: String = AppSettings.DEFAULT_BASE_URL,
        val username: String = "",
        val password: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val account: AlistAccount? = null,
        val finished: Boolean = false,
    ) {
        val canSubmit: Boolean get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !busy
    }

    private val _state = MutableStateFlow(
        UiState(
            baseUrl = container.settings.current().baseUrl.ifBlank { AppSettings.DEFAULT_BASE_URL },
            username = container.settings.current().username,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onBaseUrl(value: String) = _state.update { it.copy(baseUrl = value, error = null) }

    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun login() {
        val s = _state.value
        if (!s.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                container.session.login(s.baseUrl.trim(), s.username.trim(), s.password)
                container.session.cachedAccount() ?: container.session.me(force = true)
            }.onSuccess { account ->
                _state.update { it.copy(busy = false, account = account, finished = true) }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "登录失败") }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(container: AppContainer, nav: NavController) {
    val vm: LoginViewModel = viewModel(factory = VmFactory { LoginViewModel(container) })
    val state by vm.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    androidx.compose.runtime.LaunchedEffect(state.finished) {
        if (state.finished) nav.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("登录") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionCard {
                    Text("登录后能做什么", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "· 看到你账号下有权限的全部题库（不登录只能看公开题库）\n" +
                            "· 练习成绩自动保存到你自己的目录里，换手机也不丢\n" +
                            "· 老师/管理员账号还能查看所有人的成绩",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = vm::onBaseUrl,
                    label = { Text("服务器地址") },
                    supportingText = { Text("一般不用改，留默认的就行") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.username,
                    onValueChange = vm::onUsername,
                    label = { Text("用户名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::onPassword,
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        vm.login()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        keyboard?.hide()
                        vm.login()
                    },
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.busy) "正在登录…" else "登录")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "账号由题库管理员分配。没有账号也可以直接用公开题库。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    ErrorDialog(message = state.error, title = "登录失败", onDismiss = vm::clearError)
}
