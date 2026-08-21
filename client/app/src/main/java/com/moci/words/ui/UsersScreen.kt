package com.moci.words.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.AdminUser
import com.moci.words.api.ApiException
import kotlinx.coroutines.launch

/** 管理员用户管理：审核、角色、绑定孩子。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsersScreen(
    onGotoLearning: (Int) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberData { adminUsers() }
    val data = state.data

    fun act(action: suspend () -> String) {
        scope.launch {
            try {
                context.toast(action())
            } catch (e: ApiException) {
                context.toast(e.message ?: "操作失败。")
            } catch (e: Exception) {
                context.toast("操作失败，请重试。")
            }
            state.reload()
        }
    }

    when {
        state.loading && data == null -> LoadingBox()
        state.error != null && data == null -> ErrorBox(state.error!!, state.reload)
        data != null -> Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            PanelCard {
                PanelTitle("用户管理")
                Text(
                    "学生和家长注册后需审核。家长绑定孩子后，只能查看这些孩子的学习情况。" +
                        if (data.pendingCount > 0) "\n当前有 ${data.pendingCount} 人待审核。" else "",
                    fontSize = 13.sp,
                    color = InkSoft,
                )
            }

            data.users.forEach { u ->
                PanelCard {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(u.username, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        MociBadge(
                            "${u.roleLabel} · ${u.statusLabel}",
                            when {
                                u.role == "parent" -> Pine2
                                u.status == "approved" -> Pine
                                u.status == "rejected" -> Cinnabar
                                else -> Warn
                            },
                        )
                    }
                    Text(u.createdAt, fontSize = 12.sp, color = InkSoft)
                    if (u.role == "parent") {
                        val kids = data.childrenMap[u.id.toString()].orEmpty()
                        Text(
                            if (kids.isEmpty()) "尚未绑定孩子"
                            else "孩子：" + kids.joinToString("、") { it.second },
                            fontSize = 12.sp,
                            color = InkSoft,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (u.role == "admin") {
                            MociButton(
                                "设为学生",
                                kind = BtnKind.Ghost,
                                enabled = data.adminCount > 1,
                            ) {
                                act { app.api.adminSetRole(u.id, "user") }
                            }
                        } else {
                            if (u.status != "approved") {
                                MociButton("同意") {
                                    act { app.api.adminSetStatus(u.id, "approved") }
                                }
                            }
                            if (u.status != "rejected") {
                                MociButton("拒绝", kind = BtnKind.Danger) {
                                    act { app.api.adminSetStatus(u.id, "rejected") }
                                }
                            }
                            if (u.status == "approved") {
                                if (u.role == "user") {
                                    MociButton("学习情况", kind = BtnKind.Ghost) {
                                        onGotoLearning(u.id)
                                    }
                                }
                                if (u.role != "parent") {
                                    MociButton("设为家长", kind = BtnKind.Ghost) {
                                        act { app.api.adminSetRole(u.id, "parent") }
                                    }
                                }
                                if (u.role != "user") {
                                    MociButton("设为学生", kind = BtnKind.Ghost) {
                                        act { app.api.adminSetRole(u.id, "user") }
                                    }
                                }
                                MociButton("设为管理员", kind = BtnKind.Ghost) {
                                    act { app.api.adminSetRole(u.id, "admin") }
                                }
                            }
                        }
                    }
                    if (u.role == "parent" && u.status == "approved") {
                        Spacer(Modifier.height(8.dp))
                        BindChildRow(
                            students = data.students,
                            onBind = { childId -> act { app.api.adminBindChild(u.id, childId) } },
                        )
                        val kids = data.childrenMap[u.id.toString()].orEmpty()
                        if (kids.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                kids.forEach { (childId, name) ->
                                    MociButton("取消 $name", kind = BtnKind.Ghost) {
                                        act { app.api.adminUnbindChild(u.id, childId) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BindChildRow(students: List<AdminUser>, onBind: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AdminUser?>(null) }

    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            MociButton(
                selected?.username ?: "选择孩子",
                kind = BtnKind.Ghost,
            ) { expanded = true }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                students.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.username) },
                        onClick = {
                            selected = s
                            expanded = false
                        },
                    )
                }
            }
        }
        MociButton("绑定", enabled = selected != null) {
            selected?.let { onBind(it.id) }
            selected = null
        }
    }
}
