package com.moci.words.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiException
import com.moci.words.api.ChildInfo
import com.moci.words.api.User
import kotlinx.coroutines.launch

/** 我的：账号信息、家长任务设置、账号切换、退出登录。 */
@Composable
fun MeScreen(
    user: User,
    onUserChanged: (User) -> Unit,
    onLogout: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberData { profile() }
    val data = state.data

    var selectedChildId by remember(user.id) { mutableIntStateOf(-1) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 账号卡
        PanelCard {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(user.username, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    MociBadge(user.roleLabel, Pine)
                }
                MociButton("退出登录", kind = BtnKind.Ghost) { onLogout() }
            }
        }

        when {
            state.loading && data == null -> LoadingBox()
            state.error != null && data == null -> ErrorBox(state.error!!, state.reload)
            data != null -> {
                // 学生：统计 + 切换家长
                if (user.isLearner) {
                    data.stats?.let { stats ->
                        StatGrid(
                            listOf(
                                "${stats.total}" to "单词",
                                "${stats.learning}" to "了解",
                                "${stats.mastered}" to "掌握",
                            )
                        )
                    }
                    PanelCard {
                        PanelTitle("切换账号")
                        Text(
                            "切换到家长时，需要输入该家长的登录密码。",
                            fontSize = 13.sp,
                            color = InkSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                        val parents = data.parents.filter { it.status == "approved" }
                        if (parents.isEmpty()) {
                            Text("还没有绑定家长。", fontSize = 13.sp, color = InkSoft)
                        } else {
                            parents.forEach { p ->
                                ParentSwitchRow(
                                    username = p.username,
                                    onSwitch = { password ->
                                        scope.launch {
                                            try {
                                                val newUser = app.api.switchAccount(p.id, password)
                                                context.toast("已切换到 ${newUser.username}。")
                                                onUserChanged(newUser)
                                            } catch (e: ApiException) {
                                                context.toast(e.message ?: "切换失败。")
                                            } catch (e: Exception) {
                                                context.toast("切换失败，请重试。")
                                            }
                                        }
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }

                // 家长：孩子列表 + 任务设置 + 切换到孩子
                if (user.isParent) {
                    if (data.children.isEmpty()) {
                        PanelCard {
                            Text(
                                "还没有绑定孩子。请联系管理员，把学生账号绑到你的家长账号下。",
                                fontSize = 14.sp,
                                color = InkSoft,
                            )
                        }
                    } else {
                        val children = data.children
                        if (selectedChildId == -1 || children.none { it.user.id == selectedChildId }) {
                            selectedChildId = children.first().user.id
                        }
                        val selected = children.first { it.user.id == selectedChildId }
                        PanelCard {
                            PanelTitle("孩子列表")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                children.forEach { child ->
                                    val sel = child.user.id == selectedChildId
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedChildId = child.user.id },
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                child.user.username,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = if (sel) Pine else Ink,
                                            )
                                            Text(
                                                "今日 ${child.task.done}/${child.task.quota}",
                                                fontSize = 12.sp,
                                                color = InkSoft,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        ChildSettingsCard(
                            child = selected,
                            onSaved = { state.reload() },
                        )

                        if (selected.user.status == "approved") {
                            PanelCard {
                                PanelTitle("切换到孩子")
                                Text(
                                    "一键切换到 ${selected.user.username}，无需密码。",
                                    fontSize = 13.sp,
                                    color = InkSoft,
                                )
                                Spacer(Modifier.height(10.dp))
                                MociButton(
                                    "切换到 ${selected.user.username}",
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    scope.launch {
                                        try {
                                            val newUser = app.api.switchAccount(selected.user.id)
                                            context.toast("已切换到 ${newUser.username}。")
                                            onUserChanged(newUser)
                                        } catch (e: ApiException) {
                                            context.toast(e.message ?: "切换失败。")
                                        } catch (e: Exception) {
                                            context.toast("切换失败，请重试。")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 管理员：入口提示（具体在各自 tab）
                if (user.isAdmin) {
                    PanelCard {
                        PanelTitle("管理")
                        Text(
                            "管理员负责审核注册、维护词库，并把学生绑定给家长。请使用底部「词库 / 用户 / 学情」标签。",
                            fontSize = 13.sp,
                            color = InkSoft,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ParentSwitchRow(username: String, onSwitch: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(username, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
        Spacer(Modifier.height(6.dp))
        MociTextField(
            value = password,
            onValueChange = { password = it },
            label = "家长登录密码",
            isPassword = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(8.dp))
        MociButton(
            "切换到该家长",
            modifier = Modifier.fillMaxWidth(),
            enabled = password.length >= 6,
        ) {
            onSwitch(password)
        }
    }
}

@Composable
private fun ChildSettingsCard(child: ChildInfo, onSaved: () -> Unit) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dailyWords by remember(child.user.id) {
        mutableStateOf(child.user.dailyWords.toString())
    }
    var dailyReview by remember(child.user.id) {
        mutableStateOf(child.user.dailyReview.toString())
    }
    var knowSpeak by remember(child.user.id) { mutableStateOf(child.user.knowSpeak) }
    var knowSpell by remember(child.user.id) { mutableStateOf(child.user.knowSpell) }
    var knowPos by remember(child.user.id) { mutableStateOf(child.user.knowPos) }
    var knowPhonetic by remember(child.user.id) { mutableStateOf(child.user.knowPhonetic) }
    var saving by remember { mutableStateOf(false) }

    PanelCard {
        PanelTitle("学习任务 · ${child.user.username}")
        MociTextField(
            value = dailyWords,
            onValueChange = { dailyWords = it.filter(Char::isDigit).take(2) },
            label = "每日新词（0–50）",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(10.dp))
        MociTextField(
            value = dailyReview,
            onValueChange = { dailyReview = it.filter(Char::isDigit).take(2) },
            label = "每日复习（0–50）",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(12.dp))
        Text("判断「学会」需要", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = knowSpeak,
                onCheckedChange = { knowSpeak = it },
                colors = CheckboxDefaults.colors(checkedColor = Pine),
            )
            Text("正确朗读", fontSize = 14.sp, color = Ink)
            Spacer(Modifier.weight(1f))
            Checkbox(
                checked = knowSpell,
                onCheckedChange = { knowSpell = it },
                colors = CheckboxDefaults.colors(checkedColor = Pine),
            )
            Text("正确默写", fontSize = 14.sp, color = Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = knowPos,
                onCheckedChange = { knowPos = it },
                colors = CheckboxDefaults.colors(checkedColor = Pine),
            )
            Text("正确词性", fontSize = 14.sp, color = Ink)
            Spacer(Modifier.weight(1f))
            Checkbox(
                checked = knowPhonetic,
                onCheckedChange = { knowPhonetic = it },
                colors = CheckboxDefaults.colors(checkedColor = Pine),
            )
            Text("正确音标", fontSize = 14.sp, color = Ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "今日新词 ${child.task.new.done} / ${child.task.new.quota} · 复习 ${child.task.review.done} / ${child.task.review.quota}",
            fontSize = 12.sp,
            color = InkSoft,
        )
        Spacer(Modifier.height(12.dp))
        MociButton(
            if (saving) "保存中…" else "保存设置",
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
        ) {
            saving = true
            scope.launch {
                try {
                    val msg = app.api.saveChildSettings(
                        child.user.id,
                        dailyWords.toIntOrNull() ?: 8,
                        dailyReview.toIntOrNull() ?: 8,
                        knowSpeak,
                        knowSpell,
                        knowPos,
                        knowPhonetic,
                    )
                    context.toast(msg)
                    onSaved()
                } catch (e: ApiException) {
                    context.toast(e.message ?: "保存失败。")
                } catch (e: Exception) {
                    context.toast("保存失败，请重试。")
                }
                saving = false
            }
        }
    }
}
