package com.moci.words.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.moci.words.MociApp
import com.moci.words.MociSpeech
import com.moci.words.api.ApiException
import com.moci.words.api.Card
import com.moci.words.api.CardsData
import kotlinx.coroutines.launch

private enum class CheckStage { None, Read, Spell }

/** 学习页：闪卡 + 「学会」前的朗读 / 默写检查。逻辑对齐网页版 app.js。 */
@Composable
fun StudyScreen(onExit: () -> Unit) {
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
    var listening by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var spellError by remember { mutableStateOf<String?>(null) }
    var spellInput by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        loadError = null
        scope.launch {
            try {
                data = app.api.reviewCards()
                index = 0
                finished = 0
                stage = CheckStage.None
            } catch (e: ApiException) {
                loadError = e.message
            } catch (e: Exception) {
                loadError = "加载失败，请检查网络后重试。"
            }
            loading = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { load() }

    BackHandler { onExit() }

    val cards = data?.cards ?: emptyList()
    val card = cards.getOrNull(index)
    val needSpeak = data?.speak == true
    val needSpell = data?.spell == true

    fun resetCheck() {
        app.speech.cancel()
        listening = false
        spokenText = ""
        readError = null
        spellError = null
        spellInput = ""
        stage = CheckStage.None
    }

    fun submit(cardId: Int, rating: String, spelling: String? = null, spoken: String? = null) {
        if (submitting) return
        submitting = true
        scope.launch {
            try {
                app.api.submitReview(cardId, rating, spelling, spoken)
                finished += 1
                index += 1
                resetCheck()
            } catch (e: ApiException) {
                when (e.code) {
                    "spelling" -> spellError = "拼写不正确，请再试一次，或改选「不认识」。"
                    "spoken" -> {
                        spokenText = ""
                        stage = CheckStage.Read
                        readError = "请先正确朗读这个单词。"
                    }
                    else -> context.toast(e.message ?: "提交失败，请检查网络后重试。")
                }
            } catch (e: Exception) {
                context.toast("提交失败，请检查网络后重试。")
            }
            submitting = false
        }
    }

    val speechCallback = remember(card?.id) {
        object : MociSpeech.Callback {
            override fun onStart() {
                listening = true
                readError = null
            }

            override fun onResults(alternatives: List<String>) {
                val term = card?.term ?: return
                val hit = alternatives.firstOrNull { spokenMatches(it, term) }
                if (hit != null) {
                    spokenText = hit
                    if (needSpell) {
                        stage = CheckStage.Spell
                    } else {
                        submit(card?.id ?: return, "easy", spoken = hit)
                    }
                } else {
                    readError = "没听清或读得不对，请再试一次。"
                }
            }

            override fun onError(code: String) {
                readError = when (code) {
                    "not-allowed" -> "没有麦克风权限，请在系统设置中允许。"
                    "service-not-available" -> "设备没有可用的语音识别服务。"
                    "no-speech" -> "没有听到声音，请再试一次。"
                    else -> "没听清或读得不对，请再试一次。"
                }
            }

            override fun onEnd() {
                listening = false
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            app.speech.start(callback = speechCallback)
        } else {
            readError = "没有麦克风权限，无法朗读检查。"
        }
    }

    fun startListening() {
        app.tts.stop()
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!app.speech.available) {
            readError = "设备没有可用的语音识别服务。"
            return
        }
        app.speech.start(callback = speechCallback)
    }

    fun beginKnowCheck() {
        val c = card ?: return
        if (!needSpeak && !needSpell) {
            submit(c.id, "easy")
            return
        }
        app.tts.stop()
        stage = if (needSpeak) CheckStage.Read else CheckStage.Spell
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // 顶部：返回 + 进度
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
                when {
                    d.stats.total == 0 -> EmptyBox("词库还是空的", "请等待管理员录入单词。")
                    d.task.remaining == 0 -> EmptyBox(
                        "今日任务已完成",
                        "今天新词 ${d.task.new.quota} 个、复习 ${d.task.review.quota} 个都已完成，明天再来。",
                    )
                    else -> EmptyBox("今天没有待学习单词", "稍后再来，或明天继续学习。")
                }
            }
            card == null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("本轮学习完成", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("还不熟的词会再出现。", fontSize = 14.sp, color = InkSoft)
                Spacer(Modifier.height(20.dp))
                MociButton("回到首页", onClick = onExit)
            }
            else -> {
                FlashCard(card, Modifier.weight(1f))

                when (stage) {
                    CheckStage.None -> Row(
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

                    CheckStage.Read -> Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            "请大声读出这个单词",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            if (listening) "正在听…（点击停止）" else "开始朗读",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !submitting,
                        ) {
                            if (listening) {
                                app.speech.cancel()
                                listening = false
                            } else {
                                startListening()
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            readError ?: "点击后对着麦克风读英文。",
                            fontSize = 13.sp,
                            color = if (readError != null) Cinnabar else InkSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                        MociButton(
                            "返回",
                            kind = BtnKind.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                        ) { resetCheck() }
                    }

                    CheckStage.Spell -> Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            if (needSpeak) "朗读正确，请默写完整单词" else "请默写完整单词",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Spacer(Modifier.height(10.dp))
                        MociTextField(
                            value = spellInput,
                            onValueChange = {
                                spellInput = it
                                spellError = null
                            },
                            label = "输入英文拼写",
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Ascii,
                                autoCorrectEnabled = false,
                            ),
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
                                stage = CheckStage.Read
                                readError = "请先正确朗读这个单词。"
                                return@MociButton
                            }
                            submit(
                                card.id,
                                "easy",
                                spelling = if (needSpell) spellInput else null,
                                spoken = if (needSpeak) spokenText else null,
                            )
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
                Text(card.term, style = MociType.cardTerm)
                Spacer(Modifier.width(6.dp))
                SpeakIconButton(card.term, size = 26)
            }
            if (card.phonetic.isNotEmpty()) {
                Text(card.phonetic, fontSize = 15.sp, color = InkSoft)
            }
            Spacer(Modifier.height(16.dp))
            Text(card.meaning, fontSize = 17.sp, color = Ink)
            if (card.phrase.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ExtraLine("短语", card.phrase)
            }
            if (card.example.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ExtraLine("例句", card.example)
            }
            if (card.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(card.notes, fontSize = 13.sp, color = InkSoft)
            }
        }
    }
}

// 与网页版 app.js 一致的口语匹配：规范化后整体相等或连续子串匹配
private fun normalizeSpoken(text: String): String =
    text.trim().lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun spokenMatches(spoken: String, term: String): Boolean {
    val want = normalizeSpoken(term)
    val said = normalizeSpoken(spoken)
    if (want.isEmpty() || said.isEmpty()) return false
    if (said == want) return true
    val saidTokens = said.split(" ").filter { it.isNotEmpty() }
    val wantTokens = want.split(" ").filter { it.isNotEmpty() }
    for (i in 0..saidTokens.size - wantTokens.size) {
        if (wantTokens.indices.all { j -> saidTokens[i + j] == wantTokens[j] }) return true
    }
    return false
}
