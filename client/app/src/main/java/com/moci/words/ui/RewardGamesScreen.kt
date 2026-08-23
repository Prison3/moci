package com.moci.words.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlin.math.max
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
fun RewardGamesScreen(onBack: () -> Unit) {
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper),
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
    Text(
        if (remaining > 0) "今日还可玩 ${RewardQuota.formatSeconds(remaining)}"
        else "今日游戏时间已用完，明天再来",
        fontSize = 13.sp,
        color = if (remaining > 0) InkSoft else Cinnabar,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
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
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
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
            Spacer(Modifier.height(8.dp))
            RemainingHint(remaining)
            Spacer(Modifier.height(14.dp))
            GameCard("记忆翻牌", "翻开两张一样的牌，全部配对就过关。", enabled = remaining > 0) {
                onPick(RewardGame.Memory)
            }
            Spacer(Modifier.height(10.dp))
            GameCard("点星星", "星星会跳出来，15 秒内点得越多越好。", enabled = remaining > 0) {
                onPick(RewardGame.Stars)
            }
            Spacer(Modifier.height(10.dp))
            GameCard("快反应", "变绿再点，测测你的反应有多快。", enabled = remaining > 0) {
                onPick(RewardGame.Reflex)
            }
            Spacer(Modifier.height(10.dp))
            GameCard("贪吃蛇", "吃豆子变长。方向键或滑动控制，别撞墙和自己。", enabled = remaining > 0) {
                onPick(RewardGame.Snake)
            }
            Spacer(Modifier.height(10.dp))
            GameCard("坦克大战", "左右移动、开火，打掉对面坦克，守住 3 条命。", enabled = remaining > 0) {
                onPick(RewardGame.Tank)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    blurb: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Paper2 else Paper2.copy(alpha = 0.55f))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (enabled) Ink else InkSoft)
        Spacer(Modifier.height(6.dp))
        Text(blurb, fontSize = 13.sp, color = InkSoft)
        Spacer(Modifier.height(10.dp))
        Text(
            if (enabled) "开始玩 →" else "今日时间已用完",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Pine else InkSoft,
        )
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Pine)
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

    Column(Modifier.fillMaxSize()) {
        RewardTopBar("记忆翻牌", onBack)
        RemainingHint(remaining)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !playing -> "点开始"
                won -> "全部配对！用了 $moves 步"
                else -> "步数 $moves · 点开两张相同的牌"
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            fontSize = 14.sp,
            color = InkSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tiles.chunked(4).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { tile ->
                            val revealed = playing && (tile.matched || tile.id in open)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (revealed) Paper2 else Pine)
                                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                                    .clickable(enabled = playing && !locked && !revealed && !won) {
                                        if (open.size < 2 && tile.id !in open) {
                                            open = open + tile.id
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (revealed) tile.face else "?",
                                    fontSize = if (revealed) 28.sp else 22.sp,
                                    color = if (revealed) Ink else Paper2,
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
                        .background(Paper.copy(alpha = if (playing) 0.55f else 0.78f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (won) {
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
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// 2. 点星星

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

    Column(Modifier.fillMaxSize()) {
        RewardTopBar("点星星", onBack)
        RemainingHint(remaining)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("得分 $score", fontSize = 14.sp, color = InkSoft)
            Text(
                when {
                    finished -> "时间到"
                    playing -> "剩余 ${left}s"
                    else -> "点开始"
                },
                fontSize = 14.sp,
                color = InkSoft,
            )
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Paper2)
                .border(1.dp, Line, RoundedCornerShape(16.dp)),
        ) {
            if (playing) {
                Box(
                    Modifier
                        .offset(x = maxWidth * starX, y = maxHeight * starY)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Pine)
                        .clickable {
                            score += 1
                            spawn()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("★", fontSize = 26.sp, color = Paper2)
                }
            } else {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (finished) "本局得分 $score" else "点中星星得分，越快越好",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
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
        Spacer(Modifier.height(16.dp))
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
        ReflexPhase.Go -> Pine
        ReflexPhase.Early -> Cinnabar
        else -> Paper2
    }
    val fg = when (phase) {
        ReflexPhase.Go, ReflexPhase.Early -> Paper2
        else -> Ink
    }
    val hint = when (phase) {
        ReflexPhase.Idle -> if (remaining > 0) "点屏幕开始。\n变绿之前不要点。"
        else "今日时间已用完"
        ReflexPhase.Wait -> "等一等…\n变绿再点"
        ReflexPhase.Go -> "点！"
        ReflexPhase.Early -> "太早了"
        ReflexPhase.Result -> "${ms} 毫秒"
    }

    Column(Modifier.fillMaxSize()) {
        RewardTopBar("快反应", onBack)
        RemainingHint(remaining)
        Spacer(Modifier.height(4.dp))
        Text(
            if (best == Int.MAX_VALUE) "还没有最好成绩" else "最好成绩 ${best}ms",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            fontSize = 14.sp,
            color = InkSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .border(1.dp, Line, RoundedCornerShape(16.dp))
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    hint,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                    textAlign = TextAlign.Center,
                )
                if (phase == ReflexPhase.Result || phase == ReflexPhase.Early) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (remaining > 0) "再点一次重来" else "今日时间已用完",
                        fontSize = 13.sp,
                        color = fg.copy(alpha = 0.85f),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// 4. 贪吃蛇

private data class Cell(val x: Int, val y: Int)
private enum class Dir { Up, Down, Left, Right }

private const val SNAKE_W = 12
private const val SNAKE_H = 16

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

    Column(Modifier.fillMaxSize()) {
        RewardTopBar("贪吃蛇", onBack)
        RemainingHint(remaining)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                over -> "撞到了！得分 $score"
                playing -> "得分 $score · 滑动或按方向键"
                else -> "点开始"
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            fontSize = 14.sp,
            color = InkSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Paper2)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(4.dp)
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
            for (y in 0 until SNAKE_H) {
                for (x in 0 until SNAKE_W) {
                    val c = Cell(x, y)
                    val isHead = snake.firstOrNull() == c
                    val isBody = c in snake
                    val isFood = c == food
                    val color = when {
                        isHead -> Pine
                        isBody -> Pine2
                        isFood -> Cinnabar
                        else -> Paper
                    }
                    Box(
                        Modifier
                            .offset(x = cellW * x, y = cellH * y)
                            .size(cellW, cellH)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                }
            }
            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Paper.copy(alpha = 0.78f)),
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
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PadButton("↑", Modifier.width(76.dp)) { turn(Dir.Up) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("←", Modifier.width(76.dp)) { turn(Dir.Left) }
                PadButton("↓", Modifier.width(76.dp)) { turn(Dir.Down) }
                PadButton("→", Modifier.width(76.dp)) { turn(Dir.Right) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// 5. 坦克大战

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
    var lastShotAt by remember { mutableLongStateOf(0L) }

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
        lastShotAt = 0L
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

    fun fire() {
        if (!playing || over) return
        val now = System.currentTimeMillis()
        if (now - lastShotAt < 280) return
        lastShotAt = now
        bullets = bullets + Bullet(playerX, 0.84f, fromPlayer = true)
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (playing) {
            delay(40)
            if (!playing) break
            tick += 1

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

    Column(Modifier.fillMaxSize()) {
        RewardTopBar("坦克大战", onBack)
        RemainingHint(remaining)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                over -> "战斗结束，得分 $score"
                playing -> "得分 $score · 生命 ${"♥".repeat(lives.coerceAtLeast(0))}"
                else -> "点开始 · 左右移动，中间开火"
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            fontSize = 14.sp,
            color = InkSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Paper2)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .pointerInput(playing) {
                    if (!playing) return@pointerInput
                    detectDragGestures { _, drag ->
                        playerX = (playerX + drag.x / size.width).coerceIn(0.08f, 0.92f)
                    }
                },
        ) {
            val w = maxWidth
            val h = maxHeight

            // 简易星空背景点
            repeat(12) { i ->
                Box(
                    Modifier
                        .offset(
                            x = w * ((i * 37) % 100) / 100f,
                            y = h * ((i * 53) % 80) / 100f,
                        )
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(Line),
                )
            }

            enemies.forEach { e ->
                Box(
                    Modifier
                        .offset(x = w * e.x - 16.dp, y = h * e.y)
                        .size(32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Cinnabar),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▼", fontSize = 14.sp, color = Paper2)
                }
            }
            bullets.forEach { b ->
                Box(
                    Modifier
                        .offset(x = w * b.x - 3.dp, y = h * b.y)
                        .size(6.dp, 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (b.fromPlayer) Pine else Warn),
                )
            }
            if (playing || over) {
                Box(
                    Modifier
                        .offset(x = w * playerX - 18.dp, y = h * 0.88f)
                        .size(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Pine),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▲", fontSize = 16.sp, color = Paper2)
                }
            }
            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Paper.copy(alpha = 0.78f)),
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
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PadButton("←", Modifier.weight(1f)) {
                if (playing) playerX = (playerX - 0.07f).coerceIn(0.08f, 0.92f)
            }
            PadButton("开火", Modifier.weight(1.3f)) { fire() }
            PadButton("→", Modifier.weight(1f)) {
                if (playing) playerX = (playerX + 0.07f).coerceIn(0.08f, 0.92f)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
