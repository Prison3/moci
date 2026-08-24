package com.moci.words.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.moci.words.MociApp
import com.moci.words.MociSpeech
import com.moci.words.api.ApiException
import com.moci.words.api.COMMON_POS_TAGS
import com.moci.words.api.Card
import com.moci.words.api.CardsData
import com.moci.words.api.posChoiceLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CheckStage { None, Pos, Read, Phonetic, Spell }

/** 「学会」朗读检查的分段：单词 → 短语 → 例句，有内容的都要读对。 */
private enum class SpeakPart { Term, Phrase, Example }

private fun Card.speakParts(): List<SpeakPart> = buildList {
    add(SpeakPart.Term)
    if (phrase.isNotBlank()) add(SpeakPart.Phrase)
    if (example.isNotBlank()) add(SpeakPart.Example)
}

private fun Card.speakText(part: SpeakPart): String = when (part) {
    SpeakPart.Term -> term
    SpeakPart.Phrase -> phrase
    SpeakPart.Example -> example
}

private fun SpeakPart.label(): String = when (this) {
    SpeakPart.Term -> "单词"
    SpeakPart.Phrase -> "短语"
    SpeakPart.Example -> "例句"
}

private const val HIDE_TAG = "MociHide"

private fun hideLog(msg: String) {
    Log.i(HIDE_TAG, "[HIDE] $msg")
}

/** 学习页：闪卡 + 「学会」前的朗读 / 词性 / 音标 / 默写检查。 */
@Composable
fun StudyScreen(
    settingsKey: String,
    wordsKey: Long,
    onExit: () -> Unit,
    onGameImmersiveChange: (Boolean) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf<CardsData?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    var index by remember { mutableIntStateOf(0) }
    var finished by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf(CheckStage.None) }
    var spokenText by remember { mutableStateOf("") }
    var spokenPhrase by remember { mutableStateOf("") }
    var spokenExample by remember { mutableStateOf("") }
    var speakPartIndex by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var spellError by remember { mutableStateOf<String?>(null) }
    var spellInput by remember { mutableStateOf("") }
    var selectedPos by remember { mutableStateOf(setOf<String>()) }
    var posError by remember { mutableStateOf<String?>(null) }
    var phoneticInput by remember { mutableStateOf("") }
    var phoneticError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var showRewards by remember { mutableStateOf(false) }

    if (showRewards) {
        RewardGamesScreen(
            onBack = { showRewards = false },
            onImmersiveChange = onGameImmersiveChange,
        )
        return
    }

    fun load(force: Boolean = false) {
        loading = true
        loadError = null
        scope.launch {
            try {
                data = app.api.reviewCards(force)
                index = 0
                finished = 0
                stage = CheckStage.None
                hideLog(
                    "cards loaded n=${data?.cards?.size} speak=${data?.speak} spell=${data?.spell} " +
                        "pos=${data?.pos} phonetic=${data?.phonetic} first=${data?.cards?.firstOrNull()?.term}",
                )
            } catch (e: ApiException) {
                loadError = e.message
            } catch (e: Exception) {
                loadError = "加载失败，请检查网络后重试。"
            }
            loading = false
        }
    }
    LaunchedEffect(settingsKey, wordsKey) { load(force = true) }

    BackHandler { onExit() }

    val cards = data?.cards ?: emptyList()
    val card = cards.getOrNull(index)
    val needSpeak = data?.speak == true
    val needSpell = data?.spell == true
    val needPos = data?.pos == true && !card?.posTags.isNullOrEmpty()
    val needPhonetic = data?.phonetic == true && !card?.phonetic.isNullOrBlank()

    fun resetCheck() {
        hideLog("resetCheck term=${card?.term} prevStage=$stage")
        app.speech.cancel()
        listening = false
        spokenText = ""
        spokenPhrase = ""
        spokenExample = ""
        speakPartIndex = 0
        readError = null
        spellError = null
        spellInput = ""
        selectedPos = emptySet()
        posError = null
        phoneticInput = ""
        phoneticError = null
        stage = CheckStage.None
    }

    fun submit(
        cardId: Int,
        rating: String,
        spelling: String? = null,
        spoken: String? = null,
        spokenPhraseText: String? = null,
        spokenExampleText: String? = null,
        posTags: List<String>? = null,
        phonetic: String? = null,
    ) {
        if (submitting) return
        submitting = true
        scope.launch {
            try {
                app.api.submitReview(
                    cardId,
                    rating,
                    spelling,
                    spoken,
                    spokenPhraseText,
                    spokenExampleText,
                    posTags,
                    phonetic,
                )
                finished += 1
                index += 1
                resetCheck()
            } catch (e: ApiException) {
                when (e.code) {
                    "spelling" -> {
                        stage = CheckStage.Spell
                        spellError = "拼写不正确，请再试一次，或改选「不认识」。"
                    }
                    "spoken" -> {
                        spokenText = ""
                        spokenPhrase = ""
                        spokenExample = ""
                        speakPartIndex = 0
                        stage = CheckStage.Read
                        readError = e.message ?: "请先正确朗读单词、短语和例句。"
                    }
                    "pos" -> {
                        stage = CheckStage.Pos
                        posError = "词性不正确，请再试一次，或改选「不认识」。"
                    }
                    "phonetic" -> {
                        stage = CheckStage.Phonetic
                        phoneticError = "音标不正确，请再试一次，或改选「不认识」。"
                    }
                    else -> context.toast(e.message ?: "提交失败，请检查网络后重试。")
                }
            } catch (e: Exception) {
                context.toast("提交失败，请检查网络后重试。")
            }
            submitting = false
        }
    }

    fun enabledChecks(): List<CheckStage> = listOfNotNull(
        CheckStage.Read.takeIf { needSpeak },
        CheckStage.Pos.takeIf { needPos },
        CheckStage.Phonetic.takeIf { needPhonetic },
        CheckStage.Spell.takeIf { needSpell },
    )

    fun nextAfter(current: CheckStage): CheckStage? {
        val order = enabledChecks()
        val i = order.indexOf(current)
        return if (i >= 0 && i < order.lastIndex) order[i + 1] else null
    }

    fun submitEasy(c: Card) {
        submit(
            c.id,
            "easy",
            spelling = if (needSpell) spellInput else null,
            spoken = if (needSpeak) spokenText else null,
            spokenPhraseText = if (needSpeak && c.phrase.isNotBlank()) spokenPhrase else null,
            spokenExampleText = if (needSpeak && c.example.isNotBlank()) spokenExample else null,
            posTags = if (needPos) selectedPos.toList() else null,
            phonetic = if (needPhonetic) phoneticInput else null,
        )
    }

    fun advanceFrom(current: CheckStage, c: Card, okToast: String) {
        val next = nextAfter(current)
        if (next != null) {
            hideLog("${current.name} OK, stage -> $next term=${c.term}")
            context.toast(okToast)
            stage = next
        } else {
            hideLog("${current.name} OK, no later checks, submit term=${c.term}")
            submitEasy(c)
        }
    }

    fun onSpoken(expected: String, part: SpeakPart, cardId: Int, alternatives: List<String>) {
        listening = false
        val candidates = MociSpeech.heardCandidates(alternatives)
        val hit = candidates.firstOrNull { spokenMatches(it, expected) }
        if (hit == null) {
            val heard = candidates.firstOrNull().orEmpty()
            readError = if (heard.isBlank()) {
                "没听清或读得不对，请再试一次。"
            } else {
                val hint = when (part) {
                    SpeakPart.Example -> "对着例句慢慢读，读完稍等识别"
                    SpeakPart.Phrase -> "对着短语读，读完稍等识别"
                    SpeakPart.Term -> "对着单词读，读完稍等识别"
                }
                "听成了「$heard」，$hint。"
            }
            return
        }
        when (part) {
            SpeakPart.Term -> spokenText = hit
            SpeakPart.Phrase -> spokenPhrase = hit
            SpeakPart.Example -> spokenExample = hit
        }
        readError = null
        val current = card?.takeIf { it.id == cardId } ?: return
        val parts = current.speakParts()
        val nextIndex = speakPartIndex + 1
        if (nextIndex < parts.size) {
            speakPartIndex = nextIndex
            context.toast("${part.label()}正确，请继续读${parts[nextIndex].label()}")
        } else {
            speakPartIndex = 0
            advanceFrom(CheckStage.Read, current, "朗读全部正确")
        }
    }

    fun speechErrorMessage(code: String): String = when (code) {
        "not-allowed" -> "没有麦克风权限，请在系统设置中允许。"
        "model-loading" -> "语音模型正在加载，请稍后再试。"
        "service-not-available" ->
            app.speech.modelErrorMessage()?.let { "本地语音模型不可用：$it" }
                ?: "本地语音模型未就绪，请确认已放入 model-en-us。"
        "no-speech" -> "没有听到声音，请再试一次。"
        "no-match" -> "没听清或读得不对，请再试一次。"
        "busy" -> "识别器正忙，请稍后再试。"
        "aborted" -> "已取消。"
        else -> "没听清或读得不对，请再试一次。"
    }

    fun beginInAppSpeech(current: Card) {
        val parts = current.speakParts()
        val part = parts.getOrElse(speakPartIndex) { SpeakPart.Term }
        val expected = current.speakText(part)
        val timeoutMs = when (part) {
            SpeakPart.Term -> 6_000
            SpeakPart.Phrase -> 10_000
            SpeakPart.Example -> 15_000
        }
        app.speech.start(
            context = context,
            expectedTerm = expected,
            timeoutMs = timeoutMs,
            callback = object : MociSpeech.Callback {
                override fun onStart() {
                    listening = true
                    readError = null
                }

                override fun onResults(alternatives: List<String>) {
                    onSpoken(expected, part, current.id, alternatives)
                }

                override fun onError(code: String) {
                    if (code == "aborted") {
                        listening = false
                        return
                    }
                    listening = false
                    readError = speechErrorMessage(code)
                }

                override fun onEnd() {
                    listening = false
                }
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val current = card
        if (!granted || current == null) {
            listening = false
            readError = "没有麦克风权限，无法朗读检查。"
            return@rememberLauncherForActivityResult
        }
        beginInAppSpeech(current)
    }

    // 模型首次解压较慢，进入朗读页时若未就绪则继续尝试加载
    LaunchedEffect(stage) {
        if (stage != CheckStage.Read) return@LaunchedEffect
        if (app.speech.isAvailable()) return@LaunchedEffect
        app.speech.ensureModel()
        repeat(40) {
            if (app.speech.isAvailable() || app.speech.modelErrorMessage() != null) return@LaunchedEffect
            delay(250)
        }
    }

    fun startListening() {
        val current = card ?: return
        app.tts.stop()
        readError = null
        if (!app.speech.isAvailable()) {
            app.speech.ensureModel()
            listening = false
            readError = speechErrorMessage(
                if (app.speech.isLoading()) "model-loading" else "service-not-available",
            )
            return
        }
        listening = true
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginInAppSpeech(current)
    }

    fun beginKnowCheck() {
        val c = card ?: return
        hideLog(
            "tap 学会 id=${c.id} term=${c.term} needSpeak=$needSpeak needSpell=$needSpell " +
                "needPos=$needPos needPhonetic=$needPhonetic stage=$stage",
        )
        val next = enabledChecks().firstOrNull()
        if (next == null) {
            hideLog("skip hide: all checks off, submit immediately")
            submitEasy(c)
            return
        }
        app.tts.stop()
        hideLog("enter $next, FlashCard should unmount")
        if (next == CheckStage.Read) {
            speakPartIndex = 0
            spokenText = ""
            spokenPhrase = ""
            spokenExample = ""
        }
        stage = next
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                app.speech.cancel()
                app.tts.stop()
                onExit()
            }) {
                Icon(MociIcons.Back, contentDescription = "返回", tint = Pine)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "$finished / ${cards.size}",
                fontSize = 14.sp,
                color = InkSoft,
            )
            Spacer(Modifier.width(12.dp))
            LinearProgressIndicator(
                progress = { if (cards.isEmpty()) 0f else finished.toFloat() / cards.size },
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Pine,
                trackColor = Line,
            )
        }

        when {
            loading -> LoadingBox()
            loadError != null -> ErrorBox(loadError!!) { load() }
            data == null -> LoadingBox()
            cards.isEmpty() -> {
                val d = data!!
                if (d.stats.total == 0) {
                    EmptyBox("词库还是空的", "请等待管理员录入单词。")
                } else {
                    // 没有下一张可学的卡（含第一天没有复习）即算今日完成。
                    EmptyBox(
                        "今日任务已完成",
                        "今天没有更多待学单词了。可领取任务奖励。",
                    ) {
                        TaskRewardButton { showRewards = true }
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            "回到首页",
                            kind = BtnKind.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onExit,
                        )
                    }
                }
            }
            card == null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("本轮学习完成", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("还不熟的词会再出现。可领取今日任务奖励。", fontSize = 14.sp, color = InkSoft)
                Spacer(Modifier.height(20.dp))
                TaskRewardButton { showRewards = true }
                Spacer(Modifier.height(10.dp))
                MociButton(
                    "回到首页",
                    kind = BtnKind.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onExit,
                )
            }
            else -> {
                val hideView = when (stage) {
                    CheckStage.None, CheckStage.Read -> "FLASH"
                    CheckStage.Pos -> "POS"
                    CheckStage.Phonetic -> "PHONETIC"
                    else -> "HIDDEN"
                }
                val hideLine =
                    "[HIDE] stage=${stage.name} speak=${if (needSpeak) 1 else 0} " +
                        "spell=${if (needSpell) 1 else 0} pos=${if (needPos) 1 else 0} " +
                        "phonetic=${if (needPhonetic) 1 else 0} view=$hideView term=${card.term}"
                LaunchedEffect(stage, needSpeak, needSpell, needPos, needPhonetic, card.id, hideView) {
                    hideLog("compose $hideLine")
                }
                when (stage) {
                    CheckStage.None -> {
                        FlashCard(card, Modifier.weight(1f))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MociButton(
                                "不认识",
                                kind = BtnKind.Danger,
                                modifier = Modifier.weight(1f),
                                enabled = !submitting,
                            ) {
                                submit(card.id, "again")
                            }
                            MociButton(
                                "学会",
                                modifier = Modifier.weight(1f),
                                enabled = !submitting,
                            ) {
                                beginKnowCheck()
                            }
                        }
                    }

                    CheckStage.Read -> {
                        val parts = card.speakParts()
                        val part = parts.getOrElse(speakPartIndex) { SpeakPart.Term }
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            FlashCard(card, Modifier.weight(1f))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "朗读检查 ${speakPartIndex + 1} / ${parts.size}",
                                fontSize = 13.sp,
                                color = InkSoft,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "请大声读出${part.label()}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink,
                            )
                            Spacer(Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(if (listening) Cinnabar else Pine)
                                    .clickable(enabled = !submitting) {
                                        if (listening) {
                                            app.speech.cancel()
                                            listening = false
                                        } else {
                                            startListening()
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    MociIcons.Mic,
                                    contentDescription = if (listening) "停止录音" else "开始录音",
                                    tint = Paper2,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                when {
                                    readError != null -> readError!!
                                    listening -> "正在听（本地识别），请读英文…"
                                    app.speech.isLoading() -> "本地语音模型加载中…"
                                    else -> when (part) {
                                        SpeakPart.Example -> "请对照卡片读出例句，读完稍等识别（约 15 秒）。"
                                        SpeakPart.Phrase -> "请对照卡片读出短语，读完稍等识别。"
                                        else -> "请对照卡片，大声读出单词。"
                                    }
                                },
                                fontSize = 13.sp,
                                color = if (readError != null) Cinnabar else InkSoft,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            MociButton(
                                "返回",
                                kind = BtnKind.Ghost,
                                modifier = Modifier.fillMaxWidth(),
                            ) { resetCheck() }
                        }
                    }

                    CheckStage.Spell -> Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        HiddenWordCard(Modifier.weight(1f))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (needSpeak) "朗读全部正确，请默写完整单词" else "请默写完整单词",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Spacer(Modifier.height(10.dp))
                        SpellBoxesInput(
                            term = card.term,
                            value = spellInput,
                            isWrong = spellError != null,
                            onValueChange = {
                                spellInput = it
                                spellError = null
                            },
                        )
                        spellError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = 13.sp, color = Cinnabar)
                        }
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            "确认拼写",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !submitting,
                        ) {
                            if (needSpeak && spokenText.isEmpty()) {
                                speakPartIndex = 0
                                stage = CheckStage.Read
                                readError = "请先完成朗读检查（单词、短语、例句）。"
                                return@MociButton
                            }
                            if (needSpeak && card.phrase.isNotBlank() && spokenPhrase.isEmpty()) {
                                speakPartIndex =
                                    card.speakParts().indexOf(SpeakPart.Phrase).coerceAtLeast(0)
                                stage = CheckStage.Read
                                readError = "请先正确朗读短语。"
                                return@MociButton
                            }
                            if (needSpeak && card.example.isNotBlank() && spokenExample.isEmpty()) {
                                speakPartIndex =
                                    card.speakParts().indexOf(SpeakPart.Example).coerceAtLeast(0)
                                stage = CheckStage.Read
                                readError = "请先正确朗读例句。"
                                return@MociButton
                            }
                            if (needPhonetic && normalizePhonetic(phoneticInput) !=
                                normalizePhonetic(card.phonetic)
                            ) {
                                stage = CheckStage.Phonetic
                                phoneticError = "请先正确写出这个单词的音标。"
                                return@MociButton
                            }
                            submitEasy(card)
                        }
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            "返回",
                            kind = BtnKind.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                        ) { resetCheck() }
                    }

                    CheckStage.Pos -> Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        PosPromptCard(card)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "请选出这个单词的词性",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (card.posTags.size > 1) {
                                "可能有多个词性，请全部选出；选对后自动进入下一步。"
                            } else {
                                "点选正确的词性，选对后自动进入下一步。"
                            },
                            fontSize = 13.sp,
                            color = InkSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                        PosChoiceRow(
                            choices = remember(card.id, card.pos) {
                                (COMMON_POS_TAGS + card.posTags).distinct()
                            },
                            selected = selectedPos,
                            isWrong = posError != null,
                            onToggle = { tag ->
                                val expected = card.posTags.toSet()
                                val newSelected = if (tag in selectedPos) {
                                    selectedPos - tag
                                } else {
                                    selectedPos + tag
                                }
                                if (newSelected.any { it !in expected }) {
                                    selectedPos = newSelected
                                    posError = "词性不正确，请再试一次，或改选「不认识」。"
                                    return@PosChoiceRow
                                }
                                selectedPos = newSelected
                                posError = null
                                if (newSelected == expected) {
                                    advanceFrom(CheckStage.Pos, card, "词性正确")
                                }
                            },
                        )
                        posError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = 13.sp, color = Cinnabar)
                        }
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            "返回",
                            kind = BtnKind.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                        ) { resetCheck() }
                    }

                    CheckStage.Phonetic -> Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        PhoneticPromptCard(card)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "请用音标键盘写出这个单词的音标",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Spacer(Modifier.height(10.dp))
                        PhoneticBoxesInput(
                            expected = card.phonetic,
                            value = phoneticInput,
                            isWrong = phoneticError != null,
                            onValueChange = {
                                phoneticInput = it
                                phoneticError = null
                            },
                        )
                        phoneticError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = 13.sp, color = Cinnabar)
                        }
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            "确认音标",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !submitting,
                        ) {
                            if (tokenizePhonetic(phoneticInput).isEmpty()) {
                                phoneticError = "请写出这个单词的音标。"
                                return@MociButton
                            }
                            if (normalizePhonetic(phoneticInput) !=
                                normalizePhonetic(card.phonetic)
                            ) {
                                phoneticError = "音标不正确，请再试一次，或改选「不认识」。"
                                return@MociButton
                            }
                            phoneticError = null
                            advanceFrom(CheckStage.Phonetic, card, "音标正确")
                        }
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            "返回",
                            kind = BtnKind.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                        ) { resetCheck() }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashCard(card: Card, modifier: Modifier = Modifier) {
    SideEffect { hideLog("FlashCard showing term=${card.term} meaning=${card.meaning}") }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Paper2)
                .border(1.dp, Line, RoundedCornerShape(18.dp))
                .padding(20.dp),
        ) {
            MociBadge(
                if (card.kind == "review") "复习" else "新词",
                if (card.kind == "review") Pine2 else Pine,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakText(card.term, style = MociType.cardTerm)
                Spacer(Modifier.width(6.dp))
                SpeakIconButton(card.term, size = 26)
                if (card.pos.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    PosBadges(card.pos)
                }
            }
            if (card.phonetic.isNotEmpty()) {
                Text(card.phonetic, fontSize = 15.sp, color = InkSoft)
            }
            Spacer(Modifier.height(16.dp))
            Text(card.meaning, fontSize = 17.sp, color = Ink)
            if (card.phrase.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ExtraLine("短语", card.phrase, card.phraseZh)
            }
            if (card.example.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ExtraLine("例句", card.example, card.exampleZh)
            }
            if (card.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(card.notes, fontSize = 13.sp, color = InkSoft)
            }
        }
    }
}

@Composable
private fun PosPromptCard(card: Card, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Paper2)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        MociBadge("选词性", Pine)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeakText(card.term, style = MociType.cardTerm)
            Spacer(Modifier.width(6.dp))
            SpeakIconButton(card.term, size = 26)
        }
        Spacer(Modifier.height(12.dp))
        Text(card.meaning, fontSize = 17.sp, color = Ink)
    }
}

@Composable
private fun PhoneticPromptCard(card: Card, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Paper2)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        MociBadge("写音标", Pine)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeakText(card.term, style = MociType.cardTerm)
            Spacer(Modifier.width(6.dp))
            SpeakIconButton(card.term, size = 26)
            if (card.pos.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                PosBadges(card.pos)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(card.meaning, fontSize = 17.sp, color = Ink)
        Spacer(Modifier.height(10.dp))
        Text("音标已隐藏，请凭记忆用下方键盘填写", fontSize = 13.sp, color = InkSoft)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosChoiceRow(
    choices: List<String>,
    selected: Set<String>,
    isWrong: Boolean,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { tag ->
            val on = tag in selected
            val wrongPick = isWrong && on
            Text(
                posChoiceLabel(tag),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    wrongPick -> Cinnabar
                    on -> Pine
                    isWrong -> InkSoft.copy(alpha = 0.55f)
                    else -> InkSoft
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            wrongPick -> Cinnabar.copy(alpha = 0.10f)
                            on -> Pine.copy(alpha = 0.14f)
                            else -> Paper2
                        },
                    )
                    .border(
                        1.dp,
                        when {
                            wrongPick -> Cinnabar
                            on -> Pine
                            else -> Line
                        },
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onToggle(tag) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun HiddenWordCard(modifier: Modifier = Modifier) {
    SideEffect { hideLog("HiddenWordCard mounted, no term on screen") }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Paper2)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MociBadge("默写中", Pine)
        Spacer(Modifier.height(20.dp))
        FrostBar(Modifier.fillMaxWidth(0.55f).height(36.dp))
        Spacer(Modifier.height(12.dp))
        FrostBar(Modifier.fillMaxWidth(0.3f).height(16.dp))
        Spacer(Modifier.height(20.dp))
        FrostBar(Modifier.fillMaxWidth(0.85f).height(18.dp))
        Spacer(Modifier.height(10.dp))
        FrostBar(Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(8.dp))
        FrostBar(Modifier.fillMaxWidth(0.9f).height(14.dp))
        Spacer(Modifier.height(18.dp))
        Text("单词信息已隐藏，请凭记忆默写", fontSize = 14.sp, color = InkSoft)
    }
}

/** 与服务端 normalize_spelling 对齐：trim、小写、空白压成单空格。 */
private fun normalizeSpelling(text: String): String =
    text.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * 默写方格：进入时先按当前单词字母数放出空格，输入英文时逐格填入。
 * 多词短语中间的空格自动插入，不占用字母方块。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpellBoxesInput(
    term: String,
    value: String,
    isWrong: Boolean,
    onValueChange: (String) -> Unit,
) {
    val expected = remember(term) { normalizeSpelling(term) }
    val slots = remember(expected) {
        buildList {
            var letterIndex = 0
            for (ch in expected) {
                if (ch.isWhitespace()) {
                    add(-1)
                } else {
                    add(letterIndex)
                    letterIndex += 1
                }
            }
        }
    }
    val letterCount = slots.count { it >= 0 }
    val typedLetters = value.filter { !it.isWhitespace() }
    val systemKeyboard = LocalSoftwareKeyboardController.current

    // 学习默写只用应用内键盘，主动收起系统输入法（避免联想）
    LaunchedEffect(expected) {
        systemKeyboard?.hide()
    }

    fun appendLetter(ch: Char) {
        if (typedLetters.length >= letterCount) return
        onValueChange(buildSpellFromLetters(typedLetters + ch.lowercaseChar(), expected))
    }

    fun deleteLetter() {
        if (typedLetters.isEmpty()) return
        onValueChange(buildSpellFromLetters(typedLetters.dropLast(1), expected))
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "共 $letterCount 个字母方块",
            fontSize = 13.sp,
            color = InkSoft,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Paper2)
                .border(
                    1.dp,
                    if (isWrong) Cinnabar else Line,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                slots.forEach { idx ->
                    if (idx < 0) {
                        Spacer(Modifier.width(10.dp))
                    } else {
                        SpellLetterCell(
                            char = typedLetters.getOrNull(idx),
                            active = typedLetters.length == idx,
                            isWrong = isWrong,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SpellLetterKeyboard(
            onLetter = ::appendLetter,
            onDelete = ::deleteLetter,
            canType = typedLetters.length < letterCount,
            canDelete = typedLetters.isNotEmpty(),
        )
    }
}

private val SPELL_ROWS = listOf(
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm",
)

@Composable
private fun SpellLetterKeyboard(
    onLetter: (Char) -> Unit,
    onDelete: () -> Unit,
    canType: Boolean,
    canDelete: Boolean,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SPELL_ROWS.forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (rowIndex == 1) Spacer(Modifier.weight(0.5f))
                row.forEach { ch ->
                    SpellKey(
                        label = ch.toString(),
                        enabled = canType,
                        modifier = Modifier.weight(1f),
                        onClick = { onLetter(ch) },
                    )
                }
                if (rowIndex == 2) {
                    SpellKey(
                        label = null,
                        enabled = canDelete,
                        modifier = Modifier.weight(1.4f),
                        onClick = onDelete,
                    ) {
                        Icon(
                            Icons.Filled.Backspace,
                            contentDescription = "删除",
                            tint = if (canDelete) Ink else InkSoft.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (rowIndex == 1) Spacer(Modifier.weight(0.5f))
            }
        }
    }
}

@Composable
private fun RowScope.SpellKey(
    label: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Paper2 else Paper)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else {
            Text(
                text = label.orEmpty(),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (enabled) Ink else InkSoft.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun SpellLetterCell(
    char: Char?,
    active: Boolean,
    isWrong: Boolean,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isWrong -> Cinnabar.copy(alpha = 0.08f)
                    char != null -> Pine.copy(alpha = 0.10f)
                    else -> Paper
                },
            )
            .border(
                width = if (active) 2.dp else 1.dp,
                color = when {
                    isWrong -> Cinnabar
                    active -> Pine
                    char != null -> Pine2
                    else -> Line
                },
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = char?.lowercaseChar()?.toString().orEmpty(),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** 把已输入字母按目标词模板填回（自动补空格）。 */
private fun buildSpellFromLetters(letters: String, expected: String): String {
    val clean = letters.filter { !it.isWhitespace() }
    if (clean.isEmpty()) return ""
    val out = StringBuilder()
    var i = 0
    for (ch in expected) {
        if (ch.isWhitespace()) {
            if (i >= clean.length) break
            out.append(' ')
        } else {
            if (i >= clean.length) break
            out.append(clean[i++])
        }
    }
    return out.toString()
}

@Composable
private fun FrostBar(modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Line),
    )
}

private fun normalizeSpoken(text: String): String =
    MociSpeech.cleanHeard(text)

private fun spokenMatches(spoken: String, term: String): Boolean =
    MociSpeech.hypothesisMatches(spoken, MociSpeech.normalizeHeard(term))

