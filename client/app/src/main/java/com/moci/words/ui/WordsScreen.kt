package com.moci.words.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.moci.words.api.ApiException
import com.moci.words.api.Word
import kotlinx.coroutines.launch

private sealed interface WordsView {
    data object List : WordsView
    data class Detail(val word: Word) : WordsView
    data class Form(val word: Word?) : WordsView
}

/** 管理员词库：搜索列表 / 详情 / 新建与编辑。 */
@Composable
fun WordsScreen() {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var view by remember { mutableStateOf<WordsView>(WordsView.List) }
    var query by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<Word>?>(null) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(q: String = query) {
        loading = true
        error = null
        scope.launch {
            try {
                val (list, n) = app.api.words(q)
                words = list
                total = n
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "加载失败，请稍后重试。"
            }
            loading = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { load() }

    when (val v = view) {
        is WordsView.Form -> WordForm(
            initial = v.word,
            onDone = { message ->
                context.toast(message)
                view = WordsView.List
                load()
            },
            onCancel = {
                view = v.word?.let { WordsView.Detail(it) } ?: WordsView.List
            },
        )

        is WordsView.Detail -> WordDetail(
            word = v.word,
            onBack = { view = WordsView.List },
            onEdit = { view = WordsView.Form(v.word) },
            onDelete = {
                scope.launch {
                    try {
                        context.toast(app.api.wordDelete(v.word.id))
                        view = WordsView.List
                        load()
                    } catch (e: ApiException) {
                        context.toast(e.message ?: "删除失败。")
                    } catch (e: Exception) {
                        context.toast("删除失败，请重试。")
                    }
                }
            },
        )

        WordsView.List -> Column(Modifier.fillMaxSize()) {
            // 搜索 + 新增
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    MociTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "搜索单词 / 释义 / 音标",
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { load(query) }) {
                    Icon(MociIcons.Search, contentDescription = "搜索", tint = Pine)
                }
                IconButton(onClick = { view = WordsView.Form(null) }) {
                    Icon(MociIcons.Add, contentDescription = "录入单词", tint = Pine)
                }
            }

            when {
                loading && words == null -> LoadingBox()
                error != null && words == null -> ErrorBox(error!!) { load() }
                words.isNullOrEmpty() -> EmptyBox(
                    "词库还是空的",
                    "点右上角 + 录入第一个单词。",
                )
                else -> {
                    Text(
                        "共 $total 词",
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
                        items(words!!, key = { it.id }) { w ->
                            WordRow(w) { view = WordsView.Detail(w) }
                        }
                    }
                }
            }
        }
    }
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
                Text(word.term, style = MociType.cardTerm)
                Spacer(Modifier.width(6.dp))
                SpeakIconButton(word.term, size = 26)
            }
            if (word.phonetic.isNotEmpty()) {
                Text(word.phonetic, fontSize = 15.sp, color = InkSoft)
            }
            Spacer(Modifier.height(12.dp))
            Text(word.meaning, fontSize = 16.sp, color = Ink)
            if (word.phrase.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ExtraLine("短语", word.phrase)
            }
            if (word.example.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ExtraLine("例句", word.example)
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
    var meaning by remember { mutableStateOf(initial?.meaning ?: "") }
    var phrase by remember { mutableStateOf(initial?.phrase ?: "") }
    var example by remember { mutableStateOf(initial?.example ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

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
        MociTextField(meaning, { meaning = it }, "释义（必填）", singleLine = false)
        Spacer(Modifier.height(10.dp))
        MociTextField(phrase, { phrase = it }, "短语（可选）")
        Spacer(Modifier.height(10.dp))
        MociTextField(example, { example = it }, "例句（可选）", singleLine = false)
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
                term = term, phonetic = phonetic, meaning = meaning,
                phrase = phrase, example = example, notes = notes,
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
