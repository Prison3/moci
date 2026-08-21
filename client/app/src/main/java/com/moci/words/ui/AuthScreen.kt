package com.moci.words.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiException
import com.moci.words.api.User
import kotlinx.coroutines.launch

/** 登录 / 注册一体界面。 */
@Composable
fun AuthScreen(onLogin: (User) -> Unit) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRegister by remember { mutableStateOf(false) }
    var recentAccounts by remember { mutableStateOf(app.api.recentUsernames()) }
    var username by remember { mutableStateOf(app.api.lastUsername()) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("user") } // user | parent
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text(
            "Moci",
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
            color = Pine,
            fontFamily = SerifFamily,
        )
        Text("公共词库，各自背诵", fontSize = 14.sp, color = InkSoft)
        Spacer(Modifier.height(36.dp))

        // 登录 / 注册 切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Paper2)
                .padding(4.dp),
        ) {
            listOf(false to "登录", true to "注册").forEach { (reg, label) ->
                val active = isRegister == reg
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) Pine else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable {
                            isRegister = reg
                            error = null
                            accountMenuOpen = false
                            if (reg) {
                                username = ""
                            } else if (username.isBlank()) {
                                username = app.api.lastUsername()
                                recentAccounts = app.api.recentUsernames()
                            }
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (active) Paper2 else InkSoft,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 15.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (isRegister) {
            MociTextField(username, { username = it }, "用户名")
        } else {
            MociTextField(
                value = username,
                onValueChange = { username = it },
                label = "用户名",
                modifier = Modifier.onFocusChanged { focus ->
                    if (focus.isFocused && recentAccounts.isNotEmpty()) {
                        accountMenuOpen = true
                    }
                },
                trailingContent = if (recentAccounts.isEmpty()) {
                    null
                } else {
                    {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "选择最近账号",
                            tint = Pine,
                            modifier = Modifier.clickable {
                                accountMenuOpen = !accountMenuOpen
                            },
                        )
                    }
                },
            )
            if (accountMenuOpen && recentAccounts.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Paper2)
                        .border(1.dp, Line, RoundedCornerShape(12.dp)),
                ) {
                    recentAccounts.forEach { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    username = name
                                    accountMenuOpen = false
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                modifier = Modifier.weight(1f),
                                fontSize = 15.sp,
                                color = if (name == username) Pine else Ink,
                                fontWeight = if (name == username) FontWeight.Bold else FontWeight.Normal,
                            )
                            IconButton(
                                onClick = {
                                    app.api.forgetUsername(name)
                                    recentAccounts = app.api.recentUsernames()
                                    if (username == name) username = app.api.lastUsername()
                                    if (recentAccounts.isEmpty()) accountMenuOpen = false
                                },
                            ) {
                                Icon(MociIcons.Close, contentDescription = "从列表移除", tint = InkSoft)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        MociTextField(
            password, { password = it }, "密码",
            isPassword = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (isRegister) {
            Spacer(Modifier.height(12.dp))
            MociTextField(
                confirm, { confirm = it }, "再输一次密码",
                isPassword = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf("user" to "我是学生", "parent" to "我是家长").forEach { (value, label) ->
                    val active = role == value
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) Pine.copy(alpha = 0.12f) else Paper2)
                            .clickable { role = value }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (active) Pine else InkSoft,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Cinnabar, fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))
        MociButton(
            text = if (busy) "请稍候…" else if (isRegister) "注册" else "登录",
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) return@MociButton
            error = null
            busy = true
            scope.launch {
                try {
                    if (isRegister) {
                        val res = app.api.register(username.trim(), password, confirm, role)
                        if (res.optBoolean("auto_login")) {
                            context.toast(res.optString("message", "注册成功。"))
                            onLogin(app.api.cachedUser!!)
                        } else {
                            context.toast(res.optString("message", "注册已提交，请等待审核。"))
                            isRegister = false
                            password = ""
                            confirm = ""
                        }
                    } else {
                        val user = app.api.login(username.trim(), password)
                        onLogin(user)
                    }
                } catch (e: ApiException) {
                    error = e.message
                } catch (e: Exception) {
                    error = "操作失败，请稍后重试。"
                } finally {
                    busy = false
                }
            }
        }
        if (isRegister) {
            Spacer(Modifier.height(14.dp))
            Text(
                "注册后需管理员同意才能登录。",
                fontSize = 12.sp,
                color = InkSoft,
            )
        }
        Spacer(Modifier.height(48.dp))
    }
}
