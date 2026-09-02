package com.moci.words

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.moci.words.BuildConfig
import com.moci.words.api.ApiException
import com.moci.words.api.AppReleaseInfo
import com.moci.words.api.SessionListener
import com.moci.words.api.User
import com.moci.words.update.AppUpdater
import com.moci.words.update.DownloadProgress
import com.moci.words.ui.AuthScreen
import com.moci.words.ui.BabyNowScreen
import com.moci.words.ui.ParentConfigScreen
import com.moci.words.ui.HomeScreen
import com.moci.words.ui.LearningScreen
import com.moci.words.ui.Line
import com.moci.words.ui.LoadingBox
import com.moci.words.ui.MeScreen
import com.moci.words.ui.MociIcons
import com.moci.words.ui.MociTheme
import com.moci.words.ui.NavHome
import com.moci.words.ui.NavLearning
import com.moci.words.ui.NavMe
import com.moci.words.ui.NavNow
import com.moci.words.ui.NavRank
import com.moci.words.ui.NavStudy
import com.moci.words.ui.NavUsers
import com.moci.words.ui.NavWords
import com.moci.words.ui.Paper
import com.moci.words.ui.Paper2
import com.moci.words.ui.RankScreen
import com.moci.words.ui.StudyScreen
import com.moci.words.ui.UpdateDialog
import com.moci.words.ui.UserAccountDropdownMenu
import com.moci.words.ui.UsersScreen
import com.moci.words.ui.WordsScreen
import com.moci.words.ui.rememberWordsScreenState
import com.moci.words.sync.SyncForegroundService
import com.moci.words.ui.toast
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MociApp

        setContent {
            MociTheme {
                var user by remember { mutableStateOf<User?>(app.api.cachedUser) }
                var booting by remember { mutableStateOf(true) }
                var updateInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
                var updating by remember { mutableStateOf(false) }
                var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val notifyPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* 未授权时仍可同步，只是事件通知会被系统拦下 */ }

                // 会话过期（任意请求 401）→ 回登录页
                remember {
                    app.api.listener = object : SessionListener {
                        override fun onUnauthorized() {
                            user = null
                            toast("登录已过期，请重新登录。")
                        }
                    }
                    true
                }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    runCatching {
                        val info = app.api.appInfo()
                        if (info.versionCode > BuildConfig.VERSION_CODE) {
                            updateInfo = info
                        }
                    }
                    if (app.api.hasSession) {
                        user = try {
                            app.api.me()
                        } catch (e: ApiException) {
                            // 网络异常时若有缓存用户则容错进入，401 已在客户端内清会话
                            if (e.httpStatus == 401) null else app.api.cachedUser
                        } catch (e: Exception) {
                            app.api.cachedUser
                        }
                    }
                    booting = false
                }

                androidx.compose.runtime.LaunchedEffect(user?.id, booting) {
                    if (booting) return@LaunchedEffect
                    if (user != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        SyncForegroundService.start(context)
                    } else {
                        SyncForegroundService.stop(context)
                    }
                }

                updateInfo?.let { info ->
                    UpdateDialog(
                        info = info,
                        busy = updating,
                        progress = downloadProgress,
                        onDismiss = { if (!updating) updateInfo = null },
                        onUpdate = {
                            if (!AppUpdater.canInstallPackages(context)) {
                                toast("请先允许安装未知来源应用。")
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
                                    updateInfo = null
                                } catch (e: Exception) {
                                    toast(e.message ?: "更新失败，请稍后重试。")
                                } finally {
                                    updating = false
                                    downloadProgress = null
                                }
                            }
                        },
                    )
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Paper)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    when {
                        booting && user == null -> LoadingBox()
                        user == null -> AuthScreen(onLogin = { user = it })
                        else -> MainScaffold(
                            user = user!!,
                            onUserChanged = { user = it },
                            onLogout = {
                                lifecycleScope.launch {
                                    app.api.logout()
                                    user = null
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class TabSpec(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
)

private fun tabsFor(user: User): List<TabSpec> = when {
    user.isAdmin -> listOf(
        TabSpec("home", "首页", MociIcons.Home, NavHome),
        TabSpec("words", "词库", MociIcons.Book, NavWords),
        TabSpec("users", "用户", MociIcons.Users, NavUsers),
        TabSpec("learning", "学情", MociIcons.Chart, NavLearning),
    )
    user.isParent -> listOf(
        TabSpec("home", "首页", MociIcons.Home, NavHome),
        TabSpec("now", "此刻", MociIcons.Clock, NavNow),
        TabSpec("study", "学习", MociIcons.Study, NavStudy),
        TabSpec("rank", "排行", MociIcons.Trophy, NavRank),
        TabSpec("config", "配置", MociIcons.Settings, NavUsers),
        TabSpec("me", "我的", MociIcons.Person, NavMe),
    )
    else -> listOf(
        TabSpec("home", "首页", MociIcons.Home, NavHome),
        TabSpec("now", "此刻", MociIcons.Clock, NavNow),
        TabSpec("study", "学习", MociIcons.Study, NavStudy),
        TabSpec("rank", "排行", MociIcons.Trophy, NavRank),
        TabSpec("me", "我的", MociIcons.Person, NavMe),
    )
}

@Composable
private fun MainScaffold(
    user: User,
    onUserChanged: (User) -> Unit,
    onLogout: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MociApp
    val scope = rememberCoroutineScope()
    val tabs = tabsFor(user)
    var currentTab by remember(user.id, user.role) { mutableStateOf(tabs.first().key) }
    var learningUserId by remember { mutableStateOf<Int?>(null) }

    var wordsKey by remember { mutableLongStateOf(app.api.wordsSyncKey) }
    val wordsState = rememberWordsScreenState(app.api, user.id)

    LaunchedEffect(user.id) {
        app.api.onSettingsUpdated = if (user.isLearner) {
            { fresh -> onUserChanged(fresh) }
        } else {
            null
        }
        app.api.onWordsUpdated = { wordsKey = app.api.wordsSyncKey }
        SyncForegroundService.start(app)
        if (user.isAdmin) wordsState.load(scope)
    }

    var gameImmersive by remember { mutableStateOf(false) }
    var showUserMenu by remember { mutableStateOf(false) }

    // 学习页之外按返回键先回首页
    BackHandler(enabled = currentTab != "home" && !gameImmersive && !showUserMenu) { currentTab = "home" }
    BackHandler(enabled = showUserMenu) { showUserMenu = false }

    Column(Modifier.fillMaxSize()) {
        // 学习页 / 游戏页沉浸式，不显示顶栏
        if (currentTab != "study" && !gameImmersive) {
            com.moci.words.ui.MociTopBar(
                subtitle = when {
                    currentTab == "config" -> "设置自己和孩子的学习任务"
                    currentTab == "now" -> "看看宝贝此刻在干什么"
                    user.isAdmin -> "管理词库与用户"
                    user.isParent -> "自己学，也看看孩子"
                    else -> "今天也把几个词留下来"
                },
                username = user.username,
                avatar = user.avatar,
                onUserClick = { showUserMenu = !showUserMenu },
                menuContent = { anchorWidth ->
                    UserAccountDropdownMenu(
                        expanded = showUserMenu,
                        onDismiss = { showUserMenu = false },
                        user = user,
                        anchorWidth = anchorWidth,
                        onEditAvatar = {
                            showUserMenu = false
                            currentTab = "me"
                        },
                        onLogout = {
                            showUserMenu = false
                            onLogout()
                        },
                        onUserChanged = { fresh ->
                            showUserMenu = false
                            onUserChanged(fresh)
                        },
                    )
                },
            )
        }
        Box(Modifier.weight(1f)) {
            when (currentTab) {
                "home" -> HomeScreen(
                    user = user,
                    settingsKey = user.settingsKey,
                    wordsKey = wordsKey,
                    onStartStudy = { currentTab = "study" },
                    onUserChanged = onUserChanged,
                    onNavigate = { currentTab = it },
                    onGameImmersiveChange = { gameImmersive = it },
                )
                "study" -> StudyScreen(
                    settingsKey = user.settingsKey,
                    wordsKey = wordsKey,
                    onExit = { currentTab = "home" },
                    onGameImmersiveChange = { gameImmersive = it },
                )
                "rank" -> RankScreen(user = user)
                "now" -> BabyNowScreen()
                "config" -> ParentConfigScreen(
                    user = user,
                    onUserChanged = onUserChanged,
                )
                "me" -> MeScreen(
                    user = user,
                    onUserChanged = onUserChanged,
                )
                "words" -> WordsScreen(state = wordsState, wordsKey = wordsKey)
                "users" -> UsersScreen(onGotoLearning = { uid ->
                    learningUserId = uid
                    currentTab = "learning"
                })
                "learning" -> LearningScreen(
                    initialUserId = learningUserId,
                    onConsumedInitial = { learningUserId = null },
                )
            }
        }
        if (!gameImmersive) {
            MociTabBar(
                tabs = tabs,
                current = currentTab,
                onSelect = { currentTab = it },
            )
        }
    }
}

@Composable
private fun MociTabBar(
    tabs: List<TabSpec>,
    current: String,
    onSelect: (String) -> Unit,
) {
    val barShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(Paper2)
            .border(1.dp, Line, barShape)
            .padding(top = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.Bottom,
        ) {
            tabs.forEach { tab ->
                val active = tab.key == current
                val accent = tab.accent
                val iconTint by animateColorAsState(
                    if (active) Paper2 else accent.copy(alpha = 0.62f),
                    label = "navIcon",
                )
                val labelTint by animateColorAsState(
                    if (active) accent else accent.copy(alpha = 0.62f),
                    label = "navLabel",
                )
                val pill by animateColorAsState(
                    if (active) accent else Color.Transparent,
                    label = "navPill",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember(tab.key) { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 28.dp, color = accent),
                            onClick = { onSelect(tab.key) },
                        )
                        .padding(top = 6.dp, bottom = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        color = labelTint,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
