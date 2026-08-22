package com.moci.words.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiException
import com.moci.words.api.Word
import kotlinx.coroutines.launch

private fun statusLabel(status: String) = when (status) {
    "learning" -> "了解"
    "mastered" -> "掌握"
    "new" -> "新词"
    else -> "全部"
}

/** 学生从首页统计点进去，按进度查看自己的单词。 */
@Composable
fun ProgressWordsScreen(
    status: String,
    title: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MociApp
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<Word>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Word?>(null) }

    fun load(q: String = query) {
        loading = true
        error = null
        scope.launch {
            try {
                val (list, n) = app.api.myWords(status, q)
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
    androidx.compose.runtime.LaunchedEffect(status) { load() }
    BackHandler(onBack = {
        if (detail != null) detail = null else onBack()
    })

    val shown = detail
    if (shown != null) {
        LearnerWordDetail(shown, onBack = { detail = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(MociIcons.Back, contentDescription = "返回", tint = Pine)
            }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                MociTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索单词或释义",
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { load(query) }) {
                Icon(MociIcons.Search, contentDescription = "搜索", tint = Pine)
            }
        }
        when {
            loading && words == null -> LoadingBox()
            error != null && words == null -> ErrorBox(error!!) { load() }
            words.isNullOrEmpty() -> EmptyBox("没有单词", "这一类里还没有词。")
            else -> {
                Text(
                    "共 $total 词",
                    fontSize = 12.sp,
                    color = InkSoft,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(words!!, key = { it.id }) { w ->
                        ProgressWordRow(w) { detail = w }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressWordRow(w: Word, onClick: () -> Unit) {
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
        if (w.status.isNotEmpty()) {
            MociBadge(statusLabel(w.status), if (w.status == "mastered") Pine else Pine2)
            Spacer(Modifier.width(6.dp))
        }
        SpeakIconButton(w.term, size = 20)
    }
}

@Composable
private fun LearnerWordDetail(word: Word, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(MociIcons.Back, contentDescription = "返回", tint = Pine)
            }
            if (word.status.isNotEmpty()) {
                MociBadge(statusLabel(word.status), Pine)
            }
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
}
