package com.moci.words.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiException
import com.moci.words.api.PendingUser
import com.moci.words.api.ProfileData
import com.moci.words.api.User
import kotlinx.coroutines.launch

private enum class UserMenuPage { Main, Switch, Password }

private sealed class SwitchTarget {
    abstract val id: Int
    abstract val username: String
    abstract val avatar: String

    data class Parent(val user: PendingUser) : SwitchTarget() {
        override val id = user.id
        override val username = user.username
        override val avatar = user.avatar
    }

    data class Child(val user: User) : SwitchTarget() {
        override val id = user.id
        override val username = user.username
        override val avatar = user.avatar
    }
}

private val AccountMenuWidth = 220.dp

/** 顶栏头像下拉：修改头像、切换账号、退出登录。 */
@Composable
fun UserAccountDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    user: User,
    anchorWidth: Dp = 0.dp,
    onEditAvatar: () -> Unit,
    onLogout: () -> Unit,
    onUserChanged: (User) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var page by remember(expanded) { mutableStateOf(UserMenuPage.Main) }
    var profile by remember { mutableStateOf<ProfileData?>(null) }
    var loadingProfile by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var passwordTarget by remember { mutableStateOf<SwitchTarget.Parent?>(null) }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(expanded, user.id) {
        if (!expanded) return@LaunchedEffect
        page = UserMenuPage.Main
        password = ""
        passwordTarget = null
        switching = false
        loadingProfile = true
        profile = try {
            app.api.profile(force = true)
        } catch (_: Exception) {
            null
        }
        loadingProfile = false
    }

    val switchTargets = remember(user.id, profile) {
        when {
            user.isLearner -> profile?.parents
                ?.filter { it.status == "approved" }
                ?.map { SwitchTarget.Parent(it) }
                .orEmpty()
            user.isParent -> profile?.children
                ?.filter { it.user.status == "approved" }
                ?.map { SwitchTarget.Child(it.user) }
                .orEmpty()
            else -> emptyList()
        }
    }

    fun doSwitch(target: SwitchTarget, pwd: String? = null) {
        if (switching) return
        switching = true
        scope.launch {
            try {
                val fresh = app.api.switchAccount(target.id, pwd)
                context.toast("已切换到 ${fresh.username}。")
                onUserChanged(fresh)
            } catch (e: ApiException) {
                context.toast(e.message ?: "切换失败。")
                switching = false
            } catch (_: Exception) {
                context.toast("切换失败，请重试。")
                switching = false
            }
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(x = anchorWidth - AccountMenuWidth, y = 4.dp),
        modifier = Modifier
            .width(AccountMenuWidth)
            .clip(RoundedCornerShape(14.dp))
            .background(Paper2)
            .border(1.dp, Line, RoundedCornerShape(14.dp)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                UserMenuPage.Main -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        UserAvatar(user.avatar, user.username, size = 40.dp)
                        Column {
                            Text(
                                user.username,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink,
                            )
                            Spacer(Modifier.height(2.dp))
                            MociBadge(user.roleLabel, Pine)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Line)
                    AccountMenuItem("修改头像", onClick = onEditAvatar)
                    if (switchTargets.isNotEmpty()) {
                        AccountMenuItem("切换账号", onClick = { page = UserMenuPage.Switch })
                    }
                    AccountMenuItem("退出登录", danger = true, onClick = onLogout)
                }

                UserMenuPage.Switch -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "←",
                            fontSize = 16.sp,
                            color = Pine,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !switching) {
                                    page = UserMenuPage.Main
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("切换账号", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        loadingProfile -> Text("加载中…", fontSize = 13.sp, color = InkSoft)
                        switchTargets.isEmpty() -> Text("暂无可切换的账号。", fontSize = 13.sp, color = InkSoft)
                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                switchTargets.forEach { target ->
                                    SwitchAccountRow(
                                        username = target.username,
                                        avatar = target.avatar,
                                        subtitle = when (target) {
                                            is SwitchTarget.Parent -> "家长"
                                            is SwitchTarget.Child -> "孩子"
                                        },
                                        enabled = !switching,
                                        onClick = {
                                            when (target) {
                                                is SwitchTarget.Parent -> {
                                                    passwordTarget = target
                                                    password = ""
                                                    page = UserMenuPage.Password
                                                }
                                                is SwitchTarget.Child -> doSwitch(target)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                UserMenuPage.Password -> {
                    val target = passwordTarget
                    if (target == null) {
                        page = UserMenuPage.Switch
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "←",
                                fontSize = 16.sp,
                                color = Pine,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !switching) {
                                        page = UserMenuPage.Switch
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("输入密码", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            UserAvatar(target.avatar, target.username, size = 36.dp)
                            Column {
                                Text(target.username, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink)
                                Text("需验证家长密码", fontSize = 12.sp, color = InkSoft)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        MociTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "家长登录密码",
                            isPassword = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            "确认切换",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !switching && password.length >= 6,
                        ) {
                            doSwitch(target, password)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountMenuItem(
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = if (danger) Cinnabar else Ink,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

@Composable
private fun SwitchAccountRow(
    username: String,
    avatar: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Paper)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UserAvatar(avatar, username, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(username, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink)
            Text(subtitle, fontSize = 11.sp, color = InkSoft)
        }
        Text("切换", fontSize = 12.sp, color = Pine, fontWeight = FontWeight.Medium)
    }
}
