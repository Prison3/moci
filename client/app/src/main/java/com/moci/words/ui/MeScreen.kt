package com.moci.words.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.moci.words.BuildConfig
import com.moci.words.MociApp
import com.moci.words.api.ApiException
import com.moci.words.api.AppReleaseInfo
import com.moci.words.api.ChildInfo
import com.moci.words.api.User
import com.moci.words.api.WORD_LEVELS
import com.moci.words.api.levelLabelOf
import com.moci.words.update.AppUpdater
import com.moci.words.update.DownloadProgress
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 我的：头像、自己的学习任务、版本。家长的孩子管理在 [ChildrenManageScreen]。 */
@Composable
fun MeScreen(
    user: User,
    onUserChanged: (User) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberData { profile() }
    val data = state.data

    var avatarCropUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(user.settingsKey) { state.reload() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
        AvatarSettingsCard(
            user = user,
            onUserChanged = onUserChanged,
            onPhotoSelected = { avatarCropUri = it },
        )

        when {
            state.loading && data == null -> LoadingBox()
            state.error != null && data == null -> ErrorBox(state.error!!, state.reload)
            data != null -> {
                // 学生：家长配置（只读）+ 切换家长
                if (user.isLearner && !user.isParent) {
                    LearnerSettingsReadonly(data.user)
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
                                    avatar = p.avatar,
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

                // 家长：只管理自己的学习任务
                if (user.isParent) {
                    val ownTask = data.task
                    if (ownTask != null) {
                        ChildSettingsCard(
                            child = ChildInfo(data.user, data.stats, ownTask),
                            onSaved = {
                                scope.launch {
                                    runCatching { onUserChanged(app.api.me()) }
                                    state.reload()
                                }
                            },
                            title = "我的学习任务",
                        )
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

        AppVersionSection()
        Spacer(Modifier.height(16.dp))
        }

        avatarCropUri?.let { uri ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Paper),
            ) {
                AvatarCropScreen(
                    uri = uri,
                    onDismiss = { avatarCropUri = null },
                    onConfirm = { bytes ->
                        avatarCropUri = null
                        scope.launch {
                            try {
                                val fresh = app.api.saveAvatarImage(bytes)
                                onUserChanged(fresh)
                                context.toast("头像已更新")
                            } catch (e: ApiException) {
                                context.toast(e.message ?: "保存失败")
                            } catch (_: Exception) {
                                context.toast("保存失败，请重试")
                            }
                        }
                    },
                )
            }
        }
    }
}

/** 家长：孩子列表、学习任务设置、一键切换到孩子。 */
@Composable
fun ChildrenManageScreen(
    user: User,
    onUserChanged: (User) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberData { profile() }
    val data = state.data
    var selectedChildId by remember(user.id) { mutableIntStateOf(-1) }
    LaunchedEffect(user.settingsKey) { state.reload() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        when {
            state.loading && data == null -> LoadingBox()
            state.error != null && data == null -> ErrorBox(state.error!!, state.reload)
            data != null -> {
                val children = data.children
                if (children.isEmpty()) {
                    PanelCard {
                        Text(
                            "还没有绑定孩子。请联系管理员，把学生账号绑到你的家长账号下。",
                            fontSize = 14.sp,
                            color = InkSoft,
                        )
                    }
                } else {
                    if (selectedChildId == -1 || children.none { it.user.id == selectedChildId }) {
                        selectedChildId = children.first().user.id
                    }
                    val selected = children.first { it.user.id == selectedChildId }
                    PanelCard {
                        PanelTitle("孩子列表")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            children.forEachIndexed { index, child ->
                                ChildSelectRow(
                                    child = child,
                                    selected = child.user.id == selectedChildId,
                                    accent = childAccentAt(index),
                                    onClick = { selectedChildId = child.user.id },
                                )
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
                                    } catch (_: Exception) {
                                        context.toast("切换失败，请重试。")
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
}

private const val APP_UPDATE_TAG = "AppUpdate"

@Composable
private fun AppVersionSection() {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var releaseInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val hasUpdate = releaseInfo?.let { it.versionCode > BuildConfig.VERSION_CODE } == true

    fun checkUpdate() {
        scope.launch {
            checking = true
            checkError = null
            runCatching { app.api.appInfo() }
                .onSuccess { info ->
                    releaseInfo = info
                    val localApkPath = AppUpdater.apkCacheFile(context).absolutePath
                    Log.i(
                        APP_UPDATE_TAG,
                        "检查更新：downloadUrl=${info.downloadUrl} localApkPath=$localApkPath",
                    )
                }
                .onFailure { e ->
                    Log.w(APP_UPDATE_TAG, "检查更新失败：${e.message}")
                    checkError = when (e) {
                        is ApiException -> e.message ?: "检查失败"
                        else -> e.message ?: "无法连接服务器"
                    }
                }
            checking = false
        }
    }

    LaunchedEffect(Unit) { checkUpdate() }

    PanelCard {
        PanelTitle("版本信息")
        SettingsReadonlyRow("当前版本", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        when {
            checking && releaseInfo == null -> SettingsReadonlyRow("最新版本", "检查中…")
            checkError != null && releaseInfo == null -> SettingsReadonlyRow("最新版本", checkError!!)
            releaseInfo != null -> {
                val info = releaseInfo!!
                SettingsReadonlyRow("最新版本", "v${info.versionName} (${info.versionCode})")
                SettingsReadonlyRow("安装包大小", "${(info.sizeBytes / 1048576.0).roundToInt()} MB")
                Text(
                    if (hasUpdate) "发现新版本，建议更新。" else "当前已是最新版本。",
                    fontSize = 13.sp,
                    color = if (hasUpdate) Cinnabar else Pine,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MociButton(
                if (checking) "检查中…" else "检查更新",
                modifier = Modifier.weight(1f),
                enabled = !checking && !updating,
            ) { checkUpdate() }
            if (hasUpdate) {
                MociButton(
                    when {
                        updating && downloadProgress?.percent != null -> "下载中 ${downloadProgress!!.percent}%"
                        updating -> "下载中…"
                        else -> "立即更新"
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !checking && !updating,
                ) { showUpdateDialog = true }
            }
        }
    }

    releaseInfo?.takeIf { hasUpdate && showUpdateDialog }?.let { info ->
        UpdateDialog(
            info = info,
            busy = updating,
            progress = downloadProgress,
            onDismiss = { if (!updating) showUpdateDialog = false },
            onUpdate = {
                if (!AppUpdater.canInstallPackages(context)) {
                    context.toast("请先允许安装未知来源应用。")
                    AppUpdater.openInstallPermissionSettings(context)
                    return@UpdateDialog
                }
                updating = true
                downloadProgress = null
                scope.launch {
                    try {
                        val apk = AppUpdater.downloadApk(
                            context = context,
                            url = info.downloadUrl,
                            expectedBytes = info.sizeBytes,
                            onProgress = { downloadProgress = it },
                        )
                        AppUpdater.installApk(context, apk)
                        showUpdateDialog = false
                    } catch (e: Exception) {
                        context.toast(e.message ?: "更新失败，请稍后重试。")
                    } finally {
                        updating = false
                        downloadProgress = null
                    }
                }
            },
        )
    }
}

@Composable
private fun AvatarSettingsCard(
    user: User,
    onUserChanged: (User) -> Unit,
    onPhotoSelected: (Uri) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onPhotoSelected(uri)
    }

    PanelCard {
        PanelTitle("设置头像")
        AvatarPicker(
            current = user.avatar,
            username = user.username,
            saving = saving,
            onConfirm = { emoji ->
                if (emoji == user.avatar || saving) return@AvatarPicker
                saving = true
                scope.launch {
                    try {
                        val fresh = app.api.saveAvatar(emoji)
                        onUserChanged(fresh)
                        context.toast("头像已更新")
                    } catch (e: ApiException) {
                        context.toast(e.message ?: "保存失败")
                    } catch (_: Exception) {
                        context.toast("保存失败，请重试")
                    }
                    saving = false
                }
            },
            onPickPhoto = {
                pickPhoto.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }
}

@Composable
private fun LearnerSettingsReadonly(settings: User) {
    val knowItems = buildList {
        if (settings.knowSpeak) add("正确朗读（词/短语/句）")
        if (settings.knowSpell) add("正确默写")
        if (settings.knowPos) add("正确词性")
        if (settings.knowPhonetic) add("正确音标")
    }
    PanelCard {
        PanelTitle("学习配置")
        Text(
            "由家长设置，学生端不可修改。",
            fontSize = 12.sp,
            color = InkSoft,
        )
        Spacer(Modifier.height(10.dp))
        SettingsReadonlyRow("每日新词", "${settings.dailyWords}")
        SettingsReadonlyRow("每日复习", "${settings.dailyReview}")
        SettingsReadonlyRow("游戏奖励", "${settings.rewardMinutes} 分钟")
        SettingsReadonlyRow(
            "学习学段",
            settings.wordLevels.joinToString("、") { levelLabelOf(it) },
        )
        SettingsReadonlyRow(
            "学会判定",
            if (knowItems.isEmpty()) "无额外要求" else knowItems.joinToString(" · "),
        )
    }
}

@Composable
private fun SettingsReadonlyRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontSize = 14.sp, color = InkSoft)
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Ink,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun ParentSwitchRow(username: String, avatar: String, onSwitch: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserAvatar(avatar, username, size = 36.dp)
            Text(username, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
        }
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
private fun ChildSettingsCard(
    child: ChildInfo,
    onSaved: () -> Unit,
    title: String = "学习任务 · ${child.user.username}",
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dailyWords by remember(child.user.id, child.user.dailyWords) {
        mutableStateOf(child.user.dailyWords.toString())
    }
    var dailyReview by remember(child.user.id, child.user.dailyReview) {
        mutableStateOf(child.user.dailyReview.toString())
    }
    var knowSpeak by remember(child.user.id, child.user.knowSpeak) { mutableStateOf(child.user.knowSpeak) }
    var knowSpell by remember(child.user.id, child.user.knowSpell) { mutableStateOf(child.user.knowSpell) }
    var knowPos by remember(child.user.id, child.user.knowPos) { mutableStateOf(child.user.knowPos) }
    var knowPhonetic by remember(child.user.id, child.user.knowPhonetic) { mutableStateOf(child.user.knowPhonetic) }
    var rewardMinutes by remember(child.user.id, child.user.rewardMinutes) {
        mutableStateOf(child.user.rewardMinutes.toString())
    }
    var wordLevels by remember(child.user.id, child.user.wordLevels) {
        mutableStateOf(child.user.wordLevels.toSet())
    }
    var saving by remember { mutableStateOf(false) }

    PanelCard {
        PanelTitle(title)
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
        Spacer(Modifier.height(10.dp))
        MociTextField(
            value = rewardMinutes,
            onValueChange = { rewardMinutes = it.filter(Char::isDigit).take(3) },
            label = "游戏奖励时长（分钟，默认 30）",
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
            Text("正确朗读（词/短语/句）", fontSize = 13.sp, color = Ink)
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
        Spacer(Modifier.height(12.dp))
        Text("学习学段（新词范围）", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
        WORD_LEVELS.forEach { lv ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = lv in wordLevels,
                    onCheckedChange = { checked ->
                        wordLevels = if (checked) {
                            wordLevels + lv
                        } else {
                            val next = wordLevels - lv
                            if (next.isEmpty()) wordLevels else next
                        }
                    },
                    colors = CheckboxDefaults.colors(checkedColor = Pine),
                )
                Text(levelLabelOf(lv), fontSize = 14.sp, color = Ink)
            }
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
                        (rewardMinutes.toIntOrNull() ?: 30).coerceIn(0, 180),
                        WORD_LEVELS.filter { it in wordLevels },
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
