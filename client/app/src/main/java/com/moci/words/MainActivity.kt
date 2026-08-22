package com.moci.words

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.moci.words.api.ApiException
import com.moci.words.api.SessionListener
import com.moci.words.api.User
import com.moci.words.ui.AuthScreen
import com.moci.words.ui.HomeScreen
import com.moci.words.ui.InkSoft
import com.moci.words.ui.LearningScreen
import com.moci.words.ui.LoadingBox
import com.moci.words.ui.MeScreen
import com.moci.words.ui.MociIcons
import com.moci.words.ui.MociTheme
import com.moci.words.ui.Paper
import com.moci.words.ui.Paper2
import com.moci.words.ui.Pine
import com.moci.words.ui.StudyScreen
import com.moci.words.ui.UsersScreen
import com.moci.words.ui.WordsScreen
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
)

private fun tabsFor(user: User): List<TabSpec> = when {
    user.isAdmin -> listOf(
        TabSpec("home", "首页", MociIcons.Home),
        TabSpec("words", "词库", MociIcons.Book),
        TabSpec("users", "用户", MociIcons.Users),
        TabSpec("learning", "学情", MociIcons.Chart),
    )
    user.isParent -> listOf(
        TabSpec("home", "首页", MociIcons.Home),
        TabSpec("me", "我的", MociIcons.Person),
    )
    else -> listOf(
        TabSpec("home", "首页", MociIcons.Home),
        TabSpec("study", "学习", MociIcons.Study),
        TabSpec("me", "我的", MociIcons.Person),
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

    LaunchedEffect(user.id, user.isLearner) {
        if (user.isLearner) {
            app.api.onSettingsUpdated = { fresh -> onUserChanged(fresh) }
            app.api.startSync(scope)
        } else {
            app.api.stopSync()
        }
    }

    // 学习页之外按返回键先回首页
    BackHandler(enabled = currentTab != "home") { currentTab = "home" }

    Column(Modifier.fillMaxSize()) {
        // 学习页沉浸式，不显示顶栏
        if (currentTab != "study") {
            com.moci.words.ui.MociTopBar(
                subtitle = when {
                    user.isAdmin -> "管理词库与用户"
                    user.isParent -> "关注孩子每天的学习"
                    else -> "今天也把几个词留下来"
                },
                username = user.username,
                onUserClick = {
                    currentTab = if (user.isAdmin) "home" else "me"
                },
            )
        }
        Box(Modifier.weight(1f)) {
            when (currentTab) {
                "home" -> HomeScreen(
                    user = user,
                    settingsKey = user.settingsKey,
                    onStartStudy = { currentTab = "study" },
                    onUserChanged = onUserChanged,
                    onNavigate = { currentTab = it },
                )
                "study" -> StudyScreen(
                    settingsKey = user.settingsKey,
                    onExit = { currentTab = "home" },
                )
                "me" -> MeScreen(
                    user = user,
                    onUserChanged = onUserChanged,
                    onLogout = onLogout,
                )
                "words" -> WordsScreen()
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
        MociTabBar(
            tabs = tabs,
            current = currentTab,
            onSelect = { currentTab = it },
        )
    }
}

@Composable
private fun MociTabBar(
    tabs: List<TabSpec>,
    current: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Paper2)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        tabs.forEach { tab ->
            val active = tab.key == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(tab.key) }
                    .padding(vertical = 4.dp),
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) Pine else InkSoft,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    tab.label,
                    fontSize = 11.sp,
                    color = if (active) Pine else InkSoft,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
