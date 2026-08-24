package com.moci.words.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.RewardQuota
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private enum class RewardGame {
    Hub, Memory, Stars, Reflex, Snake, Tank,
}

/**
 * 今日任务奖励：内置小游戏大厅。
 * 记忆翻牌 / 点星星 / 快反应 / 贪吃蛇 / 坦克大战。
 * 每天最多玩家长设置的分钟数（默认 30），只在对局进行中计时。
 */
@Composable
fun RewardGamesScreen(
    onBack: () -> Unit,
    onImmersiveChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as MociApp
    val scope = rememberCoroutineScope()
    val userId = app.api.cachedUser?.id ?: 0
    val limitMinutes = app.api.cachedUser?.rewardMinutes ?: RewardQuota.DEFAULT_LIMIT_MINUTES
    var game by remember { mutableStateOf(RewardGame.Hub) }
    var remainingMs by remember {
        mutableLongStateOf(RewardQuota.remainingMs(context, userId, limitMinutes))
    }
    var sessionPlaying by remember { mutableStateOf(false) }
    var appResumed by remember { mutableStateOf(true) }
    val remaining = (remainingMs / 1000L).toInt()

    fun submitScore(gameId: String, score: Int) {
        scope.launch {
            runCatching { app.api.submitGameScore(gameId, score) }
        }
    }

    fun refreshRemaining() {
        remainingMs = RewardQuota.remainingMs(context, userId, limitMinutes)
    }

    fun tryStartRound(): Boolean {
        refreshRemaining()
        return remainingMs > 0L
    }

    fun onPlayingChanged(active: Boolean) {
        sessionPlaying = active && remainingMs > 0L
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> appResumed = true
                Lifecycle.Event.ON_PAUSE -> appResumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(sessionPlaying, appResumed) {
        if (!sessionPlaying || !appResumed) return@LaunchedEffect
        while (sessionPlaying && appResumed) {
            delay(1000)
            remainingMs = RewardQuota.addUsed(context, userId, limitMinutes, 1000L)
            if (remainingMs <= 0L) {
                sessionPlaying = false
                break
            }
        }
    }

    BackHandler {
        if (game == RewardGame.Hub) {
            onBack()
        } else {
            sessionPlaying = false
            game = RewardGame.Hub
        }
    }

    val inGame = game != RewardGame.Hub
    DisposableEffect(inGame) {
        onImmersiveChange(inGame)
        onDispose { onImmersiveChange(false) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (inGame) Color(0xFF101510) else Paper),
    ) {
        when (game) {
            RewardGame.Hub -> RewardHub(
                onBack = onBack,
                remaining = remaining,
                limitMinutes = limitMinutes,
                onPick = { game = it },
            )
            RewardGame.Memory -> MemoryGame(
                remaining = remaining,
                onBack = { sessionPlaying = false; game = RewardGame.Hub },
                tryStart = ::tryStartRound,
                onPlayingChanged = ::onPlayingChanged,
                onScore = { submitScore("memory", it) },
            )
            RewardGame.Stars -> StarsGame(
                remaining = remaining,
                onBack = { sessionPlaying = false; game = RewardGame.Hub },
                tryStart = ::tryStartRound,
                onPlayingChanged = ::onPlayingChanged,
                onScore = { submitScore("stars", it) },
            )
            RewardGame.Reflex -> ReflexGame(
                remaining = remaining,
                onBack = { sessionPlaying = false; game = RewardGame.Hub },
                tryStart = ::tryStartRound,
                onPlayingChanged = ::onPlayingChanged,
                onScore = { submitScore("reflex", it) },
            )
            RewardGame.Snake -> SnakeGame(
                remaining = remaining,
                onBack = { sessionPlaying = false; game = RewardGame.Hub },
                tryStart = ::tryStartRound,
                onPlayingChanged = ::onPlayingChanged,
                onScore = { submitScore("snake", it) },
            )
            RewardGame.Tank -> TankGame(
                remaining = remaining,
                onBack = { sessionPlaying = false; game = RewardGame.Hub },
                tryStart = ::tryStartRound,
                onPlayingChanged = ::onPlayingChanged,
                onScore = { submitScore("tank", it) },
            )
        }
    }
}

@Composable
private fun RemainingHint(remaining: Int) {
    val ok = remaining > 0
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (ok) Pine.copy(alpha = 0.12f) else Cinnabar.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (ok) "今日还可玩 ${RewardQuota.formatSeconds(remaining)}"
            else "今日游戏时间已用完，明天再来",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (ok) Pine else Cinnabar,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GameHudChip(text: String, accent: Color = Color.White) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = accent,
    )
}

/** 全屏游戏：悬浮返回 + 角标 HUD，最大化游戏区域。 */
@Composable
private fun ImmersiveGameShell(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    hud: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            content()
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
            ) {
                Icon(MociIcons.Back, contentDescription = "返回", tint = Color.White)
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = hud,
            )
        }
        bottomBar()
    }
}

@Composable
private fun RewardTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(MociIcons.Back, contentDescription = "返回", tint = Pine)
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

@Composable
private fun StatusChip(text: String, accent: Color = Pine) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = accent,
    )
}

@Composable
private fun RewardHub(
    onBack: () -> Unit,
    remaining: Int,
    limitMinutes: Int,
    onPick: (RewardGame) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        RewardTopBar("任务奖励", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "今天的学习任务完成了，选一款小游戏放松一下。每天最多玩 ${limitMinutes} 分钟。",
                fontSize = 14.sp,
                color = InkSoft,
            )
            Spacer(Modifier.height(10.dp))
            RemainingHint(remaining)
            Spacer(Modifier.height(16.dp))
            GameCard(
                emoji = "🃏",
                title = "记忆翻牌",
                blurb = "翻开两张一样的牌，全部配对就过关。",
                tint = Color(0xFF5B8DEF),
                enabled = remaining > 0,
            ) { onPick(RewardGame.Memory) }
            Spacer(Modifier.height(10.dp))
            GameCard(
                emoji = "⭐",
                title = "点星星",
                blurb = "金色星星会跳出来，15 秒内点得越多越好。",
                tint = Color(0xFFE6A100),
                enabled = remaining > 0,
            ) { onPick(RewardGame.Stars) }
            Spacer(Modifier.height(10.dp))
            GameCard(
                emoji = "⚡",
                title = "快反应",
                blurb = "变绿再点，测测你的反应有多快。",
                tint = Color(0xFF2E9B6A),
                enabled = remaining > 0,
            ) { onPick(RewardGame.Reflex) }
            Spacer(Modifier.height(10.dp))
            GameCard(
                emoji = "🐍",
                title = "贪吃蛇",
                blurb = "吃豆子变长。方向键或滑动控制，别撞墙和自己。",
                tint = Color(0xFF3D8B5A),
                enabled = remaining > 0,
            ) { onPick(RewardGame.Snake) }
            Spacer(Modifier.height(10.dp))
            GameCard(
                emoji = "🛡️",
                title = "坦克大战",
                blurb = "自动开火，只需按左右移动，守住 3 条命。",
                tint = Color(0xFF4A7C3F),
                enabled = remaining > 0,
            ) { onPick(RewardGame.Tank) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GameCard(
    emoji: String,
    title: String,
    blurb: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(if (enabled) 4.dp else 0.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) Paper2 else Paper2.copy(alpha = 0.6f))
            .border(1.dp, if (enabled) tint.copy(alpha = 0.28f) else Line, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 24.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (enabled) Ink else InkSoft)
            Spacer(Modifier.height(4.dp))
            Text(blurb, fontSize = 12.sp, color = InkSoft, lineHeight = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (enabled) "开始玩 →" else "今日时间已用完",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) tint else InkSoft,
            )
        }
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .shadow(2.dp, RoundedCornerShape(14.dp), clip = false)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(listOf(Pine.copy(alpha = 0.95f), Pine)),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Paper2, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

// ---------------------------------------------------------------------------
// 1. 记忆翻牌

private data class MemoryTile(val id: Int, val face: String, val matched: Boolean = false)

private val MEMORY_FACES = listOf("🍎", "🍋", "🍇", "🍓", "🍑", "🥝")

@Composable
private fun MemoryGame(
    remaining: Int,
    onBack: () -> Unit,
    tryStart: () -> Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    onScore: (Int) -> Unit,
) {
    fun freshDeck(): List<MemoryTile> {
        val faces = (MEMORY_FACES + MEMORY_FACES).shuffled()
        return faces.mapIndexed { i, face -> MemoryTile(i, face) }
    }

    var tiles by remember { mutableStateOf(freshDeck()) }
    var open by remember { mutableStateOf<List<Int>>(emptyList()) }
    var locked by remember { mutableStateOf(false) }
    var moves by remember { mutableIntStateOf(0) }
    var won by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }

    fun beginRound() {
        if (!tryStart()) return
        tiles = freshDeck()
        open = emptyList()
        moves = 0
        won = false
        locked = false
        playing = true
        onPlayingChanged(true)
    }

    LaunchedEffect(remaining) {
        if (remaining <= 0 && playing) {
            playing = false
            onPlayingChanged(false)
        }
    }

    LaunchedEffect(open) {
        if (!playing || open.size != 2) return@LaunchedEffect
        locked = true
        moves += 1
        val a = tiles[open[0]]
        val b = tiles[open[1]]
        delay(500)
        if (a.face == b.face) {
            tiles = tiles.map {
                if (it.id == a.id || it.id == b.id) it.copy(matched = true) else it
            }
            if (tiles.all { it.matched }) {
                won = true
                playing = false
                onPlayingChanged(false)
                onScore(moves)
            }
        }
        open = emptyList()
        locked = false
    }

    ImmersiveGameShell(
        onBack = onBack,
        hud = {
            GameHudChip(
                when {
                    !playing -> "点开始"
                    won -> "全部配对 · $moves 步"
                    else -> "步数 $moves"
                },
            )
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFFE8F0FF), Color(0xFFF5F7FB))),
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tiles.chunked(4).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { tile ->
                            val revealed = playing && (tile.matched || tile.id in open)
                            val backBrush = Brush.verticalGradient(
                                listOf(Color(0xFF6B9AF0), Color(0xFF3F6EC8)),
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .shadow(if (revealed) 2.dp else 0.dp, RoundedCornerShape(14.dp), clip = false)
                                    .clip(RoundedCornerShape(14.dp))
                                    .then(
                                        if (revealed) {
                                            Modifier.background(
                                                if (tile.matched) Color(0xFFD9F2E4) else Color.White,
                                            )
                                        } else {
                                            Modifier.background(backBrush)
                                        },
                                    )
                                    .border(
                                        1.5.dp,
                                        when {
                                            tile.matched -> Color(0xFF3D9B6A)
                                            revealed -> Color(0xFFE0E6F0)
                                            else -> Color(0xFF3F6EC8)
                                        },
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable(enabled = playing && !locked && !revealed && !won) {
                                        if (open.size < 2 && tile.id !in open) {
                                            open = open + tile.id
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (revealed) tile.face else "?",
                                    fontSize = if (revealed) 30.sp else 22.sp,
                                    color = if (revealed) Ink else Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
            if (!playing || won) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = if (playing) 0.55f else 0.78f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (won) {
                            Text("🎉", fontSize = 36.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "全部配对！用了 $moves 步",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        if (remaining > 0) {
                            MociButton(
                                if (won) "再玩一局" else "开始",
                                onClick = { beginRound() },
                            )
                        } else {
                            Text("今日时间已用完", fontSize = 14.sp, color = Cinnabar)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. 点星星

private val StarGold = Color(0xFFFFC107)
private val StarGoldDeep = Color(0xFFFF8F00)
private val StarGoldPale = Color(0xFFFFF8E1)
private val StarGlow = Color(0xFFFFE082)

/** 金色五角星：外发光 + 轻微呼吸缩放。 */
@Composable
private fun PrettyStar(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "starPulse")
    val scale by pulse.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(720),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "starScale",
    )
    val glow by pulse.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "starGlow",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outer = size.minDimension * 0.4f
            val inner = outer * 0.42f
            drawCircle(
                color = StarGlow.copy(alpha = glow * 0.55f),
                radius = outer * 1.45f,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = StarGold.copy(alpha = glow * 0.35f),
                radius = outer * 1.15f,
                center = Offset(cx, cy),
            )
            val path = Path()
            for (i in 0 until 10) {
                val angle = -Math.PI / 2.0 + i * Math.PI / 5.0
                val r = if (i % 2 == 0) outer else inner
                val x = cx + (cos(angle) * r).toFloat()
                val y = cy + (sin(angle) * r).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(
                path = path,
                brush = Brush.radialGradient(
                    colors = listOf(StarGoldPale, StarGold, StarGoldDeep),
                    center = Offset(cx, cy - outer * 0.12f),
                    radius = outer * 1.1f,
                ),
            )
            // 高光点
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = outer * 0.12f,
                center = Offset(cx - outer * 0.12f, cy - outer * 0.18f),
            )
        }
    }
}

@Composable
private fun StarsGame(
    remaining: Int,
    onBack: () -> Unit,
    tryStart: () -> Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    onScore: (Int) -> Unit,
) {
    var score by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(15) }
    var playing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var starX by remember { mutableFloatStateOf(0.4f) }
    var starY by remember { mutableFloatStateOf(0.4f) }
    var starId by remember { mutableIntStateOf(0) }

    fun spawn() {
        starX = Random.nextFloat().coerceIn(0.08f, 0.82f)
        starY = Random.nextFloat().coerceIn(0.08f, 0.82f)
        starId += 1
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (left > 0 && playing) {
            delay(1000)
            if (!playing) break
            left -= 1
        }
        if (playing && left <= 0) {
            playing = false
            finished = true
            onPlayingChanged(false)
            onScore(score)
        }
    }

    LaunchedEffect(remaining) {
        if (remaining <= 0 && playing) {
            playing = false
            finished = true
            onPlayingChanged(false)
        }
    }

    LaunchedEffect(playing, starId) {
        if (!playing) return@LaunchedEffect
        delay(1200)
        if (playing) spawn()
    }

    ImmersiveGameShell(
        onBack = onBack,
        hud = {
            GameHudChip("得分 $score", accent = StarGold)
            if (playing) {
                GameHudChip("剩余 ${left}s", accent = Color(0xFFBBDEFB))
            } else if (finished) {
                GameHudChip("时间到", accent = Color(0xFFBBDEFB))
            }
        },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF1A2450))),
                ),
        ) {
            // 夜空小星点
            repeat(22) { i ->
                Box(
                    Modifier
                        .offset(
                            x = maxWidth * ((i * 41) % 100) / 100f,
                            y = maxHeight * ((i * 67) % 100) / 100f,
                        )
                        .size(if (i % 3 == 0) 3.dp else 2.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i % 2 == 0) 0.55f else 0.28f)),
                )
            }
            if (playing) {
                key(starId) {
                    PrettyStar(
                        modifier = Modifier
                            .offset(x = maxWidth * starX, y = maxHeight * starY)
                            .size(68.dp),
                        onClick = {
                            score += 1
                            spawn()
                        },
                    )
                }
            } else {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PrettyStar(modifier = Modifier.size(80.dp), onClick = {})
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (finished) "本局得分 $score" else "点中星星得分，越快越好",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (remaining > 0) {
                        MociButton(
                            if (finished) "再玩一局" else "开始",
                            onClick = {
                                if (!tryStart()) return@MociButton
                                score = 0
                                left = 15
                                finished = false
                                spawn()
                                playing = true
                                onPlayingChanged(true)
                            },
                        )
                    } else {
                        Text("今日时间已用完", fontSize = 14.sp, color = Cinnabar)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. 快反应

private enum class ReflexPhase { Idle, Wait, Go, Early, Result }

@Composable
private fun ReflexGame(
    remaining: Int,
    onBack: () -> Unit,
    tryStart: () -> Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    onScore: (Int) -> Unit,
) {
    var phase by remember { mutableStateOf(ReflexPhase.Idle) }
    var goAt by remember { mutableLongStateOf(0L) }
    var ms by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var waitToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(phase, waitToken) {
        if (phase != ReflexPhase.Wait) return@LaunchedEffect
        val token = waitToken
        delay(Random.nextLong(1200L, 3500L))
        if (phase == ReflexPhase.Wait && waitToken == token) {
            goAt = System.currentTimeMillis()
            phase = ReflexPhase.Go
        }
    }

    LaunchedEffect(remaining) {
        if (remaining <= 0 && (phase == ReflexPhase.Wait || phase == ReflexPhase.Go)) {
            phase = ReflexPhase.Idle
            onPlayingChanged(false)
        }
    }

    val bg = when (phase) {
        ReflexPhase.Go -> Color(0xFF2E9B6A)
        ReflexPhase.Early -> Color(0xFFC62828)
        ReflexPhase.Wait -> Color(0xFFFFF3E0)
        ReflexPhase.Result -> Color(0xFFE8F5E9)
        else -> Color(0xFFF3F0EA)
    }
    val fg = when (phase) {
        ReflexPhase.Go, ReflexPhase.Early -> Color.White
        else -> Ink
    }
    val hint = when (phase) {
        ReflexPhase.Idle -> if (remaining > 0) "点屏幕开始\n变绿之前不要点"
        else "今日时间已用完"
        ReflexPhase.Wait -> "等一等…\n变绿再点"
        ReflexPhase.Go -> "点！"
        ReflexPhase.Early -> "太早了"
        ReflexPhase.Result -> "${ms} 毫秒"
    }

    ImmersiveGameShell(
        onBack = onBack,
        hud = {
            GameHudChip(
                if (best == Int.MAX_VALUE) "还没有最好成绩" else "最好 ${best}ms",
                accent = Color(0xFFA5D6A7),
            )
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(bg)
                .clickable {
                    when (phase) {
                        ReflexPhase.Idle, ReflexPhase.Result, ReflexPhase.Early -> {
                            if (remaining <= 0 || !tryStart()) return@clickable
                            waitToken += 1
                            phase = ReflexPhase.Wait
                            onPlayingChanged(true)
                        }
                        ReflexPhase.Wait -> {
                            phase = ReflexPhase.Early
                            onPlayingChanged(false)
                        }
                        ReflexPhase.Go -> {
                            val elapsed = max(1, (System.currentTimeMillis() - goAt).toInt())
                            ms = elapsed
                            if (elapsed < best) best = elapsed
                            phase = ReflexPhase.Result
                            onPlayingChanged(false)
                            onScore(elapsed)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // 外圈装饰环
            Box(
                Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .border(
                        6.dp,
                        when (phase) {
                            ReflexPhase.Go -> Color.White.copy(alpha = 0.35f)
                            ReflexPhase.Early -> Color.White.copy(alpha = 0.25f)
                            else -> Pine.copy(alpha = 0.18f)
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (phase) {
                            ReflexPhase.Go -> "⚡"
                            ReflexPhase.Early -> "!"
                            ReflexPhase.Result -> "✓"
                            ReflexPhase.Wait -> "…"
                            else -> "👆"
                        },
                        fontSize = 36.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        hint,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                        textAlign = TextAlign.Center,
                        lineHeight = 30.sp,
                    )
                    if (phase == ReflexPhase.Result || phase == ReflexPhase.Early) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (remaining > 0) "再点一次重来" else "今日时间已用完",
                            fontSize = 13.sp,
                            color = fg.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. 贪吃蛇

private data class Cell(val x: Int, val y: Int)
private enum class Dir { Up, Down, Left, Right }

private const val SNAKE_W = 12
private const val SNAKE_H = 16

private val SnakeHeadColor = Color(0xFF5AD68A)
private val SnakeHeadDark = Color(0xFF2E8B57)
private val SnakeBodyColor = Color(0xFF3CB371)
private val SnakeTailColor = Color(0xFF1F5C38)
private val SnakeBellyColor = Color(0xFF9AE8BC)
private val SnakeScaleColor = Color(0xFF267A4A)
private val AppleRed = Color(0xFFE74C3C)
private val AppleDark = Color(0xFFC0392B)
private val AppleLeaf = Color(0xFF27AE60)

private fun dirVector(d: Dir): Offset = when (d) {
    Dir.Up -> Offset(0f, -1f)
    Dir.Down -> Offset(0f, 1f)
    Dir.Left -> Offset(-1f, 0f)
    Dir.Right -> Offset(1f, 0f)
}

/** 圆角粗线段 + 鳞片 + 蛇头眼睛舌头，食物画成苹果。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnakeScene(
    snake: List<Cell>,
    dir: Dir,
    food: Cell,
    cellW: Dp,
    cellH: Dp,
) {
    val cw = cellW.toPx()
    val ch = cellH.toPx()
    val unit = minOf(cw, ch)
    fun center(c: Cell) = Offset((c.x + 0.5f) * cw, (c.y + 0.5f) * ch)

    // 苹果
    val fc = center(food)
    val appleR = unit * 0.34f
    drawCircle(AppleDark, appleR * 1.05f, fc + Offset(0f, appleR * 0.12f))
    drawCircle(AppleRed, appleR, fc)
    drawCircle(Color.White.copy(alpha = 0.25f), appleR * 0.35f, fc + Offset(-appleR * 0.25f, -appleR * 0.2f))
    drawLine(
        Color(0xFF6D4C2E),
        fc + Offset(0f, -appleR * 0.85f),
        fc + Offset(0f, -appleR * 1.15f),
        strokeWidth = unit * 0.06f,
        cap = StrokeCap.Round,
    )
    val leafPath = Path().apply {
        moveTo(fc.x + unit * 0.04f, fc.y - appleR * 0.9f)
        quadraticBezierTo(
            fc.x + unit * 0.28f, fc.y - appleR * 1.35f,
            fc.x + unit * 0.22f, fc.y - appleR * 0.55f,
        )
        close()
    }
    drawPath(leafPath, AppleLeaf)

    if (snake.isEmpty()) return

    val baseR = unit * 0.38f
    val n = snake.size

    // 蛇身：粗圆角线段 + 渐变粗细
    for (i in n - 1 downTo 1) {
        val t = i.toFloat() / n.coerceAtLeast(1)
        val segR = baseR * (0.45f + 0.55f * (1f - t))
        val color = lerp(SnakeBodyColor, SnakeTailColor, t.coerceIn(0f, 1f))
        drawLine(
            color = color,
            start = center(snake[i]),
            end = center(snake[i - 1]),
            strokeWidth = segR * 2f,
            cap = StrokeCap.Round,
        )
    }

    // 每节圆点 + 肚皮 + 鳞片
    snake.forEachIndexed { i, cell ->
        val t = i.toFloat() / n.coerceAtLeast(1)
        val r = baseR * when {
            i == 0 -> 1.05f
            i == n - 1 -> 0.38f
            else -> (0.92f - t * 0.42f).coerceAtLeast(0.42f)
        }
        val c = center(cell)
        val bodyColor = if (i == 0) SnakeHeadColor else lerp(SnakeBodyColor, SnakeTailColor, t)
        drawCircle(bodyColor, r, c)
        // 肚皮浅色条
        val bellyR = r * 0.55f
        drawCircle(SnakeBellyColor.copy(alpha = 0.55f), bellyR, c + Offset(0f, r * 0.18f))
        if (i > 0 && i < n - 1) {
            drawCircle(SnakeScaleColor.copy(alpha = 0.35f), r * 0.22f, c + Offset(-r * 0.2f, -r * 0.15f))
            drawCircle(SnakeScaleColor.copy(alpha = 0.25f), r * 0.18f, c + Offset(r * 0.15f, r * 0.1f))
        }
    }

    // 蛇头：深色描边 + 眼睛 + 分叉舌头
    val head = center(snake.first())
    val hr = baseR * 1.08f
    drawCircle(SnakeHeadDark, hr * 1.06f, head)
    drawCircle(SnakeHeadColor, hr, head)

    val forward = dirVector(dir)
    val perp = Offset(-forward.y, forward.x)
    val eyeSpread = hr * 0.38f
    val eyeForward = hr * 0.12f
    listOf(-1f, 1f).forEach { side ->
        val eyePos = head + forward * eyeForward + perp * (eyeSpread * side)
        drawCircle(Color.White, hr * 0.22f, eyePos)
        drawCircle(Color(0xFF1A1A1A), hr * 0.11f, eyePos + forward * (hr * 0.06f))
    }

    val tongueLen = hr * 0.55f
    val tongueBase = head + forward * hr * 0.75f
    val tongueTip = tongueBase + forward * tongueLen
    val fork = perp * (hr * 0.18f)
    drawLine(Color(0xFFE74C3C), tongueBase, tongueTip, strokeWidth = hr * 0.1f, cap = StrokeCap.Round)
    drawLine(Color(0xFFE74C3C), tongueTip, tongueTip + forward * (hr * 0.2f) + fork, strokeWidth = hr * 0.07f, cap = StrokeCap.Round)
    drawLine(Color(0xFFE74C3C), tongueTip, tongueTip + forward * (hr * 0.2f) - fork, strokeWidth = hr * 0.07f, cap = StrokeCap.Round)
}

@Composable
private fun SnakeGame(
    remaining: Int,
    onBack: () -> Unit,
    tryStart: () -> Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    onScore: (Int) -> Unit,
) {
    var snake by remember {
        mutableStateOf(listOf(Cell(5, 8), Cell(4, 8), Cell(3, 8)))
    }
    var dir by remember { mutableStateOf(Dir.Right) }
    var pending by remember { mutableStateOf<Dir?>(null) }
    var food by remember { mutableStateOf(Cell(8, 4)) }
    var score by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }

    fun placeFood(body: List<Cell>): Cell {
        repeat(100) {
            val c = Cell(Random.nextInt(SNAKE_W), Random.nextInt(SNAKE_H))
            if (c !in body) return c
        }
        return Cell(0, 0)
    }

    fun reset() {
        if (!tryStart()) return
        snake = listOf(Cell(5, 8), Cell(4, 8), Cell(3, 8))
        dir = Dir.Right
        pending = null
        food = placeFood(snake)
        score = 0
        over = false
        playing = true
        onPlayingChanged(true)
    }

    LaunchedEffect(remaining) {
        if (remaining <= 0 && playing) {
            playing = false
            over = true
            onPlayingChanged(false)
        }
    }

    fun turn(next: Dir) {
        if (!playing || over) return
        val cur = pending ?: dir
        val invalid = (cur == Dir.Up && next == Dir.Down) ||
            (cur == Dir.Down && next == Dir.Up) ||
            (cur == Dir.Left && next == Dir.Right) ||
            (cur == Dir.Right && next == Dir.Left)
        if (!invalid) pending = next
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (playing) {
            delay(200)
            if (!playing) break
            val nextDir = pending ?: dir
            dir = nextDir
            pending = null
            val head = snake.first()
            val nx = when (nextDir) {
                Dir.Left -> head.x - 1
                Dir.Right -> head.x + 1
                else -> head.x
            }
            val ny = when (nextDir) {
                Dir.Up -> head.y - 1
                Dir.Down -> head.y + 1
                else -> head.y
            }
            val newHead = Cell(nx, ny)
            val grow = newHead == food
            // 尾巴这一格马上会空出来，吃豆子时除外
            val bodyToCheck = if (grow) snake else snake.dropLast(1)
            if (nx !in 0 until SNAKE_W || ny !in 0 until SNAKE_H || newHead in bodyToCheck) {
                over = true
                playing = false
                onPlayingChanged(false)
                onScore(score)
                break
            }
            snake = if (grow) {
                score += 1
                val grown = listOf(newHead) + snake
                food = placeFood(grown)
                grown
            } else {
                listOf(newHead) + snake.dropLast(1)
            }
        }
    }

    ImmersiveGameShell(
        onBack = onBack,
        hud = {
            GameHudChip(
                when {
                    over -> "得分 $score"
                    playing -> "得分 $score"
                    else -> "滑动或方向键"
                },
                accent = Color(0xFF81C784),
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF152018))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PadButton("↑", Modifier.width(72.dp)) { turn(Dir.Up) }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PadButton("←", Modifier.width(72.dp)) { turn(Dir.Left) }
                    PadButton("↓", Modifier.width(72.dp)) { turn(Dir.Down) }
                    PadButton("→", Modifier.width(72.dp)) { turn(Dir.Right) }
                }
            }
        },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF152018))
                .pointerInput(playing) {
                    if (!playing) return@pointerInput
                    detectDragGestures { _, drag ->
                        if (abs(drag.x) > abs(drag.y)) {
                            if (drag.x > 8) turn(Dir.Right)
                            else if (drag.x < -8) turn(Dir.Left)
                        } else {
                            if (drag.y > 8) turn(Dir.Down)
                            else if (drag.y < -8) turn(Dir.Up)
                        }
                    }
                },
        ) {
            val cellW = maxWidth / SNAKE_W
            val cellH = maxHeight / SNAKE_H
            // 棋盘格底纹
            for (y in 0 until SNAKE_H) {
                for (x in 0 until SNAKE_W) {
                    if ((x + y) % 2 == 0) {
                        Box(
                            Modifier
                                .offset(x = cellW * x, y = cellH * y)
                                .size(cellW, cellH)
                                .background(Color(0xFF1A2A1E)),
                        )
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawSnakeScene(snake, dir, food, cellW, cellH)
            }
            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF152018).copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (remaining > 0) {
                        MociButton(
                            if (over) "再玩一局" else "开始",
                            onClick = { reset() },
                        )
                    } else {
                        Text("今日时间已用完", fontSize = 14.sp, color = Cinnabar)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 5. 坦克大战

private val TankGreen = Color(0xFF3D6B3A)
private val TankGreenDark = Color(0xFF2A4A28)
private val TankGreenLight = Color(0xFF5A9155)
private val TankRed = Color(0xFFB54A2E)
private val TankRedDark = Color(0xFF7A2E1C)
private val TankRedLight = Color(0xFFD46A48)
private val TankTrack = Color(0xFF2B2B2B)
private val TankTrackLight = Color(0xFF555555)

/** 经典坦克大战风格：履带 + 车身 + 炮塔 + 炮管。 */
@Composable
private fun ClassicTank(
    facingUp: Boolean,
    modifier: Modifier = Modifier,
    body: Color = if (facingUp) TankGreen else TankRed,
    bodyDark: Color = if (facingUp) TankGreenDark else TankRedDark,
    bodyLight: Color = if (facingUp) TankGreenLight else TankRedLight,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val trackW = w * 0.2f
        // 履带
        drawRect(TankTrack, Offset(0f, h * 0.12f), Size(trackW, h * 0.76f))
        drawRect(TankTrack, Offset(w - trackW, h * 0.12f), Size(trackW, h * 0.76f))
        // 履带齿纹
        val teeth = 5
        val toothH = h * 0.76f / teeth
        repeat(teeth) { i ->
            val y = h * 0.12f + i * toothH + toothH * 0.15f
            drawRect(TankTrackLight, Offset(2f, y), Size(trackW - 4f, toothH * 0.35f))
            drawRect(TankTrackLight, Offset(w - trackW + 2f, y), Size(trackW - 4f, toothH * 0.35f))
        }
        // 车身
        val bodyL = trackW * 0.55f
        val bodyW = w - bodyL * 2
        drawRect(bodyDark, Offset(bodyL, h * 0.22f), Size(bodyW, h * 0.56f))
        drawRect(body, Offset(bodyL + 2f, h * 0.24f), Size(bodyW - 4f, h * 0.5f))
        // 炮塔
        val tw = w * 0.4f
        val th = h * 0.26f
        val tx = (w - tw) / 2f
        val ty = h * 0.34f
        drawRect(bodyDark, Offset(tx, ty), Size(tw, th))
        drawRect(bodyLight, Offset(tx + 2f, ty + 2f), Size(tw - 4f, th - 4f))
        // 炮管
        val bw = w * 0.14f
        val bh = h * 0.36f
        val bx = (w - bw) / 2f
        if (facingUp) {
            drawRect(bodyDark, Offset(bx, h * 0.02f), Size(bw, bh))
            drawRect(body, Offset(bx + 1.5f, h * 0.02f), Size(bw - 3f, bh * 0.85f))
        } else {
            drawRect(bodyDark, Offset(bx, h * 0.58f), Size(bw, bh))
            drawRect(body, Offset(bx + 1.5f, h * 0.62f), Size(bw - 3f, bh * 0.85f))
        }
    }
}

/** 按住持续触发（左右移动）。 */
@Composable
private fun HoldPadButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onHold: () -> Unit,
) {
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding, enabled) {
        if (!holding || !enabled) return@LaunchedEffect
        while (holding && enabled) {
            onHold()
            delay(45)
        }
    }
    Box(
        modifier
            .shadow(if (enabled) 3.dp else 0.dp, RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) Brush.verticalGradient(listOf(Color(0xFF4A8A45), TankGreenDark))
                else Brush.verticalGradient(listOf(Color(0xFF666666), Color(0xFF444444))),
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    holding = true
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        holding = false
                    }
                }
            }
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    }
}

private data class Bullet(
    val x: Float,
    val y: Float,
    val fromPlayer: Boolean,
    val vx: Float = 0f,
)

private data class Enemy(
    val x: Float,
    val y: Float,
    val vx: Float,
)

@Composable
private fun TankGame(
    remaining: Int,
    onBack: () -> Unit,
    tryStart: () -> Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    onScore: (Int) -> Unit,
) {
    var playerX by remember { mutableFloatStateOf(0.5f) }
    var bullets by remember { mutableStateOf<List<Bullet>>(emptyList()) }
    var enemies by remember { mutableStateOf<List<Enemy>>(emptyList()) }
    var score by remember { mutableIntStateOf(0) }
    var lives by remember { mutableIntStateOf(3) }
    var playing by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    fun reset() {
        if (!tryStart()) return
        playerX = 0.5f
        bullets = emptyList()
        enemies = listOf(
            Enemy(0.2f, 0.08f, 0.012f),
            Enemy(0.5f, 0.08f, -0.01f),
            Enemy(0.8f, 0.08f, 0.008f),
        )
        score = 0
        lives = 3
        over = false
        tick = 0
        playing = true
        onPlayingChanged(true)
    }

    LaunchedEffect(remaining) {
        if (remaining <= 0 && playing) {
            playing = false
            over = true
            onPlayingChanged(false)
        }
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (playing) {
            delay(40)
            if (!playing) break
            tick += 1

            // 玩家自动开火
            if (tick % 7 == 0) {
                bullets = bullets + Bullet(playerX, 0.84f, fromPlayer = true)
            }

            // 子弹
            bullets = bullets
                .map {
                    it.copy(
                        y = it.y + if (it.fromPlayer) -0.04f else 0.032f,
                        x = it.x + it.vx,
                    )
                }
                .filter { it.y in -0.08f..1.08f && it.x in -0.05f..1.05f }

            // 敌人左右移动并缓慢下压
            enemies = enemies.map { e ->
                var vx = e.vx
                var x = e.x + vx
                if (x < 0.08f || x > 0.92f) {
                    vx = -vx
                    x = e.x + vx
                }
                e.copy(x = x, y = e.y + 0.0035f, vx = vx)
            }

            // 定时刷新敌人
            if (tick % 45 == 0) {
                enemies = enemies + Enemy(
                    x = Random.nextFloat().coerceIn(0.12f, 0.88f),
                    y = -0.04f,
                    vx = if (Random.nextBoolean()) 0.01f else -0.01f,
                )
            }
            // 敌人开火
            if (tick % 22 == 0 && enemies.isNotEmpty()) {
                val shooter = enemies.random()
                bullets = bullets + Bullet(
                    x = shooter.x,
                    y = shooter.y + 0.05f,
                    fromPlayer = false,
                    vx = (playerX - shooter.x) * 0.015f,
                )
            }

            // 玩家子弹打敌人
            val hitEnemies = mutableSetOf<Int>()
            val hitBullets = mutableSetOf<Int>()
            bullets.forEachIndexed { bi, b ->
                if (!b.fromPlayer) return@forEachIndexed
                enemies.forEachIndexed { ei, e ->
                    if (ei in hitEnemies) return@forEachIndexed
                    if (abs(b.x - e.x) < 0.07f && abs(b.y - e.y) < 0.055f) {
                        hitEnemies += ei
                        hitBullets += bi
                        score += 1
                    }
                }
            }
            if (hitEnemies.isNotEmpty()) {
                enemies = enemies.filterIndexed { i, _ -> i !in hitEnemies }
                bullets = bullets.filterIndexed { i, _ -> i !in hitBullets }
            }

            // 漏到底的敌人清掉（不扣命）
            enemies = enemies.filter { it.y <= 1.02f }

            // 受伤：敌弹或敌人撞上玩家
            var hit = false
            enemies.forEach { e ->
                if (e.y > 0.86f && abs(e.x - playerX) < 0.09f) hit = true
            }
            bullets.forEach { b ->
                if (!b.fromPlayer && abs(b.x - playerX) < 0.08f && b.y in 0.84f..0.98f) {
                    hit = true
                }
            }
            if (hit) {
                lives -= 1
                enemies = enemies.filter { it.y < 0.7f }
                bullets = bullets.filter { it.fromPlayer }
                if (lives <= 0) {
                    over = true
                    playing = false
                    onPlayingChanged(false)
                    onScore(score)
                }
            }
        }
    }

    ImmersiveGameShell(
        onBack = onBack,
        hud = {
            GameHudChip(
                when {
                    over -> "得分 $score"
                    playing -> "得分 $score · ${"♥".repeat(lives.coerceAtLeast(0))}"
                    else -> "自动开火"
                },
                accent = Color(0xFFA5D6A7),
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121610))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HoldPadButton("←", Modifier.weight(1f), enabled = playing) {
                    playerX = (playerX - 0.045f).coerceIn(0.08f, 0.92f)
                }
                HoldPadButton("→", Modifier.weight(1f), enabled = playing) {
                    playerX = (playerX + 0.045f).coerceIn(0.08f, 0.92f)
                }
            }
        },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF121610), Color(0xFF1E2A18))),
                )
                .pointerInput(playing) {
                    if (!playing) return@pointerInput
                    detectDragGestures { _, drag ->
                        playerX = (playerX + drag.x / size.width).coerceIn(0.08f, 0.92f)
                    }
                },
        ) {
            val w = maxWidth
            val h = maxHeight

            repeat(14) { i ->
                Box(
                    Modifier
                        .offset(
                            x = w * ((i * 37) % 100) / 100f,
                            y = h * ((i * 53) % 80) / 100f,
                        )
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6B7A62).copy(alpha = 0.7f)),
                )
            }

            enemies.forEach { e ->
                ClassicTank(
                    facingUp = false,
                    modifier = Modifier
                        .offset(x = w * e.x - 18.dp, y = h * e.y)
                        .size(36.dp),
                )
            }
            bullets.forEach { b ->
                Box(
                    Modifier
                        .offset(x = w * b.x - 2.dp, y = h * b.y)
                        .size(4.dp, 10.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (b.fromPlayer) Color(0xFFE8E070) else Color(0xFFFF8A65)),
                )
            }
            if (playing || over) {
                ClassicTank(
                    facingUp = true,
                    modifier = Modifier
                        .offset(x = w * playerX - 20.dp, y = h * 0.86f)
                        .size(40.dp),
                )
            }
            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121610).copy(alpha = 0.78f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (remaining > 0) {
                        MociButton(
                            if (over) "再玩一局" else "开始",
                            onClick = { reset() },
                        )
                    } else {
                        Text("今日时间已用完", fontSize = 14.sp, color = Cinnabar)
                    }
                }
            }
        }
    }
}
