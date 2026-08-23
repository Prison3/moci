package com.moci.words.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiClient
import com.moci.words.api.ApiException
import com.moci.words.api.COMMON_POS_TAGS
import com.moci.words.api.WORD_LEVELS
import com.moci.words.api.Word
import com.moci.words.api.joinPosTags
import com.moci.words.api.levelLabelOf
import com.moci.words.api.parsePosTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface WordsView {
    data object List : WordsView
    data class Detail(val word: Word) : WordsView
    data class Form(val word: Word?) : WordsView
}

/** 管理员词库状态，由 MainScaffold 持有以跨 Tab 切换保留。 */
@Stable
class WordsScreenState(private val api: ApiClient) {
    var view by mutableStateOf<WordsView>(WordsView.List)
    var query by mutableStateOf("")
    var level by mutableStateOf("")
    var words by mutableStateOf<List<Word>?>(null)
    var total by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)

    fun load(scope: CoroutineScope, q: String = query, lv: String = level) {
        error = null
        scope.launch {
            if (words == null) {
                runCatching { api.wordsCached(q, lv) }.getOrNull()?.let { (list, n) ->
                    words = list
                    total = n
                }
            }
            try {
                val (list, n) = api.words(q, lv)
                words = list
                total = n
            } catch (e: ApiException) {
                if (words == null) error = e.message
            } catch (e: Exception) {
                if (words == null) error = "加载失败，请稍后重试。"
            }
        }
    }

    fun deleteWord(
        scope: CoroutineScope,
        word: Word,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            try {
                val msg = api.wordDelete(word.id)
                view = WordsView.List
                onSuccess(msg)
                load(scope)
            } catch (e: ApiException) {
                onError(e.message ?: "删除失败。")
            } catch (e: Exception) {
                onError("删除失败，请重试。")
            }
        }
    }
}

@Composable
fun rememberWordsScreenState(api: ApiClient, userId: Int): WordsScreenState =
    remember(userId) { WordsScreenState(api) }

/** 管理员词库：搜索列表 / 详情 / 新建与编辑。 */
@Composable
fun WordsScreen(state: WordsScreenState, wordsKey: Long = 0L) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(wordsKey) { state.load(scope) }

    when (val v = state.view) {
        is WordsView.Form -> WordForm(
            initial = v.word,
            onDone = { message ->
                context.toast(message)
                state.view = WordsView.List
                state.load(scope)
            },
            onCancel = {
                state.view = v.word?.let { WordsView.Detail(it) } ?: WordsView.List
            },
        )

        is WordsView.Detail -> WordDetail(
            word = v.word,
            onBack = { state.view = WordsView.List },
            onEdit = { state.view = WordsView.Form(v.word) },
            onDelete = {
                state.deleteWord(
                    scope = scope,
                    word = v.word,
                    onSuccess = { context.toast(it) },
                    onError = { context.toast(it) },
                )
            },
        )

        WordsView.List -> Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    MociTextField(
                        value = state.query,
                        onValueChange = { state.query = it },
                        label = "搜索单词 / 释义 / 音标",
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { state.load(scope, state.query, state.level) }) {
                    Icon(MociIcons.Search, contentDescription = "搜索", tint = Pine)
                }
                IconButton(onClick = { state.view = WordsView.Form(null) }) {
                    Icon(MociIcons.Add, contentDescription = "录入单词", tint = Pine)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LevelChip("全部", state.level.isEmpty()) {
                    state.level = ""
                    state.load(scope, state.query, "")
                }
                WORD_LEVELS.forEach { lv ->
                    LevelChip(levelLabelOf(lv), state.level == lv) {
                        state.level = lv
                        state.load(scope, state.query, lv)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            val words = state.words
            when {
                state.error != null && words == null -> ErrorBox(state.error!!) { state.load(scope) }
                words == null -> Unit
                words.isEmpty() -> EmptyBox(
                    "词库还是空的",
                    "点右上角 + 录入第一个单词。",
                )
                else -> {
                    Text(
                        "共 ${state.total} 词",
                        fontSize = 12.sp,
                        color = InkSoft,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(words, key = { it.id }) { w ->
                            WordRow(w) { state.view = WordsView.Detail(w) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = if (active) Pine else InkSoft,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Pine.copy(alpha = 0.14f) else Paper2)
            .border(1.dp, if (active) Pine else Line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun WordRow(w: Word, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Paper2)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.term,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    fontFamily = SerifFamily,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    w.levelLabel,
                    fontSize = 11.sp,
                    color = Pine,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Pine.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                if (w.pos.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    PosBadges(w.pos)
                }
                if (w.phonetic.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(w.phonetic, fontSize = 12.sp, color = InkSoft)
                }
            }
            if (w.meaning.isNotEmpty()) {
                Text(w.meaning, fontSize = 13.sp, color = InkSoft, maxLines = 2)
            }
        }
        SpeakIconButton(w.term, size = 20)
    }
}

@Composable
private fun WordDetail(
    word: Word,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(MociIcons.Back, contentDescription = "返回", tint = Pine)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text("编辑", color = Pine) }
            TextButton(onClick = { confirmDelete = true }) { Text("删除", color = Cinnabar) }
        }

        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakText(word.term, style = MociType.cardTerm)
                Spacer(Modifier.width(6.dp))
                SpeakIconButton(word.term, size = 26)
                if (word.pos.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    PosBadges(word.pos)
                }
            }
            if (word.phonetic.isNotEmpty()) {
                Text(word.phonetic, fontSize = 15.sp, color = InkSoft)
            }
            Spacer(Modifier.height(6.dp))
            Text("学段：${word.levelLabel}", fontSize = 13.sp, color = InkSoft)
            Spacer(Modifier.height(12.dp))
            Text(word.meaning, fontSize = 16.sp, color = Ink)
            if (word.phrase.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ExtraLine("短语", word.phrase, word.phraseZh)
            }
            if (word.example.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ExtraLine("例句", word.example, word.exampleZh)
            }
            if (word.notes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(word.notes, fontSize = 13.sp, color = InkSoft)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除单词") },
            text = { Text("确定删除「${word.term}」吗？所有用户的学习进度会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = Cinnabar) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordForm(
    initial: Word?,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MociApp
    val scope = rememberCoroutineScope()

    var term by remember { mutableStateOf(initial?.term ?: "") }
    var phonetic by remember { mutableStateOf(initial?.phonetic ?: "") }
    var selectedPos by remember {
        mutableStateOf(parsePosTags(initial?.pos.orEmpty()).toSet())
    }
    var meaning by remember { mutableStateOf(initial?.meaning ?: "") }
    var phrase by remember { mutableStateOf(initial?.phrase ?: "") }
    var phraseZh by remember { mutableStateOf(initial?.phraseZh ?: "") }
    var example by remember { mutableStateOf(initial?.example ?: "") }
    var exampleZh by remember { mutableStateOf(initial?.exampleZh ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var level by remember { mutableStateOf(initial?.level ?: "primary") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val posChoices = remember(selectedPos) {
        (COMMON_POS_TAGS + selectedPos.filter { it !in COMMON_POS_TAGS }).distinct()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(MociIcons.Back, contentDescription = "返回", tint = Pine)
            }
            Text(
                if (initial == null) "录入单词" else "编辑单词",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
        }
        Spacer(Modifier.height(6.dp))
        MociTextField(term, { term = it }, "单词（必填）")
        Spacer(Modifier.height(10.dp))
        MociTextField(phonetic, { phonetic = it }, "音标（可选）")
        Spacer(Modifier.height(10.dp))
        Text("词性（可多选）", fontSize = 13.sp, color = InkSoft)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            posChoices.forEach { tag ->
                val on = tag in selectedPos
                Text(
                    tag,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (on) Pine else InkSoft,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) Pine.copy(alpha = 0.14f) else Paper2)
                        .border(1.dp, if (on) Pine else Line, RoundedCornerShape(999.dp))
                        .clickable {
                            selectedPos = if (on) selectedPos - tag else selectedPos + tag
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MociTextField(meaning, { meaning = it }, "释义（必填）", singleLine = false)
        Spacer(Modifier.height(10.dp))
        Text("学段", fontSize = 13.sp, color = InkSoft)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WORD_LEVELS.forEach { lv ->
                val on = level == lv
                Text(
                    levelLabelOf(lv),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (on) Pine else InkSoft,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) Pine.copy(alpha = 0.14f) else Paper2)
                        .border(1.dp, if (on) Pine else Line, RoundedCornerShape(999.dp))
                        .clickable { level = lv }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MociTextField(phrase, { phrase = it }, "短语（可选）")
        Spacer(Modifier.height(10.dp))
        MociTextField(phraseZh, { phraseZh = it }, "短语翻译（可选）")
        Spacer(Modifier.height(10.dp))
        MociTextField(example, { example = it }, "例句（可选）", singleLine = false)
        Spacer(Modifier.height(10.dp))
        MociTextField(exampleZh, { exampleZh = it }, "例句翻译（可选）", singleLine = false)
        Spacer(Modifier.height(10.dp))
        MociTextField(notes, { notes = it }, "笔记（可选）", singleLine = false)
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Cinnabar, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        MociButton(
            if (saving) "保存中…" else "保存",
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
        ) {
            saving = true
            error = null
            val word = Word(
                id = initial?.id ?: 0,
                term = term,
                phonetic = phonetic,
                pos = joinPosTags(COMMON_POS_TAGS.filter { it in selectedPos } + selectedPos.filter { it !in COMMON_POS_TAGS }),
                meaning = meaning,
                phrase = phrase,
                phraseZh = phraseZh,
                example = example,
                exampleZh = exampleZh,
                notes = notes,
                level = level,
            )
            scope.launch {
                try {
                    val msg = if (initial == null) {
                        app.api.wordCreate(word)
                    } else {
                        app.api.wordUpdate(initial.id, word)
                    }
                    onDone(msg)
                } catch (e: ApiException) {
                    error = e.message
                } catch (e: Exception) {
                    error = "保存失败，请重试。"
                }
                saving = false
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
