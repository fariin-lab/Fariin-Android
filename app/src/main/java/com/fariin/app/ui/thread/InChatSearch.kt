package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ChatSearch
import com.fariin.app.data.Message
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The way in from the info pages (iOS searchFromInfoPage): the chat's own header opens its info
 * page and notes which chat it came from; the page's Search circle shows only then (iOS source
 * .chat), pops back and asks that chat to open its search.
 */
object InChatSearchLink {
    @Volatile var openedFrom: String? = null
    val requests = MutableStateFlow<String?>(null)

    /** Read once by the info page as it opens: was it opened from [cid]'s chat? */
    fun takeOpenedFrom(cid: String): Boolean = (openedFrom == cid).also { openedFrom = null }
    fun request(cid: String) { requests.value = cid }
}

/** The search words while search is open, for the bubbles' yellow marks (iOS searchTerm). */
internal val LocalSearchTerm = staticCompositionLocalOf { "" }

/** [text] with every match of the open search marked yellow on black, as the iOS bubble does. */
internal fun searchMarked(text: String, term: String): AnnotatedString {
    if (term.length < ChatSearch.MIN_QUERY) return AnnotatedString(text)
    val ranges = ChatSearch.highlightRanges(text, term)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (r in ranges) addStyle(SpanStyle(background = Color(0xFFFFCC00), color = Color.Black), r.first, r.last + 1)
    }
}

/** iOS ThreadView's search state: the query, the matches oldest to newest, the focused one, list mode. */
@Stable
internal class InChatSearchState {
    var active by mutableStateOf(false)
    var query by mutableStateOf("")
    var matches by mutableStateOf<List<ChatSearch.Item>>(emptyList())
    var index by mutableIntStateOf(0)
    var asList by mutableStateOf(false)
    var corpus: List<ChatSearch.Item> = emptyList()
    private var lastQuery: String? = null
    var jumpSeq = 0

    /** iOS searchTermIsSearchable: two characters typed, so a search really ran. */
    val searchable: Boolean get() = query.trim().length >= ChatSearch.MIN_QUERY
    val term: String get() = if (active) query.trim() else ""
    val current: ChatSearch.Item? get() = matches.getOrNull(index)

    fun open() { active = true; query = ""; matches = emptyList(); index = 0; lastQuery = null; asList = false; corpus = emptyList() }
    fun close() { active = false; query = ""; matches = emptyList(); index = 0; lastQuery = null; asList = false; corpus = emptyList() }

    /**
     * iOS updateSearchMatches. [force] skips the same-query check (the corpus or the chat changed);
     * [navigate] false keeps the focus on the same message. True = jump to [current] now.
     */
    fun update(window: List<Message>, force: Boolean, navigate: Boolean): Boolean {
        val q = query.trim()
        if (q.length < ChatSearch.MIN_QUERY) { matches = emptyList(); lastQuery = null; return false }
        if (!force && q == lastQuery) return false
        lastQuery = q
        val live = window.mapNotNull { m -> ChatSearch.searchText(m)?.let { ChatSearch.Item(m.id, it, m.authorId, m.createdAtMillis) } }
        val found = ChatSearch.run(q, corpus, live)
        val old = matches
        index = ChatSearch.keepIndex(old, index, found, sameMessage = !navigate)
        matches = found
        return navigate && found.isNotEmpty()
    }
}

/** The search's top bar: back closes it (the Android way for a search mode), the grey capsule field. */
@Composable
internal fun SearchTopBar(s: InChatSearchState, focus: FocusRequester, onClose: () -> Unit, onSubmit: () -> Unit) {
    val c = Fariin.colors
    LaunchedEffect(Unit) { delay(150); try { focus.requestFocus() } catch (_: Exception) {} }   // iOS: focus 0.15 s after opening
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp, end = 8.dp).size(44.dp).floating()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search", tint = c.label, modifier = Modifier.size(22.dp))
        }
        com.fariin.app.ui.components.FloatingSearchField(s.query, { s.query = it }, "Search",
            Modifier.weight(1f).padding(end = 12.dp).focusRequester(focus), onSearch = onSubmit)
    }
}

/**
 * The bar in place of the composer (iOS searchNavBar): the count on the left ("3 of 12", "12 messages"
 * in list mode, where a tap goes back to the chat, "No results"), "Show as List" / "Show as Chat", and
 * the up / down steps (up is the older match). Nothing shows for a query that never ran.
 */
@Composable
internal fun SearchNavBar(s: InChatSearchState, onStep: (Int) -> Unit, onList: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 6.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (s.matches.isNotEmpty() || s.searchable) {
            Box(Modifier.height(40.dp).floating().clickable(enabled = s.asList) { onList(false) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text(ChatSearch.countText(s.matches.size, s.index, s.asList), fontSize = 15.sp, color = c.secondary)
            }
        }
        Spacer(Modifier.weight(1f))
        if (s.matches.isNotEmpty()) {
            Box(Modifier.height(40.dp).floating().clickable { onList(!s.asList) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text(if (s.asList) "Show as Chat" else "Show as List", fontSize = 15.sp, color = c.label)
            }
            if (!s.asList) {
                Spacer(Modifier.width(8.dp))
                StepButton(Icons.Filled.KeyboardArrowUp, "Previous result", s.index > 0) { onStep(-1) }
                Spacer(Modifier.width(8.dp))
                StepButton(Icons.Filled.KeyboardArrowDown, "Next result", s.index < s.matches.size - 1) { onStep(1) }
            }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp).floating()) {
        Icon(icon, contentDescription = label, tint = c.label.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(24.dp))
    }
}

/** One list row (iOS InChatSearchRow). */
internal data class SearchRow(val id: String, val name: String, val photoUrl: String?, val atMillis: Double,
                              val text: String, val outgoing: Boolean, val read: Boolean, val squircle: Boolean = false)

/** iOS searchListRows: newest first; a channel's posts are the channel's (its name, photo and shape, no ticks). */
internal fun searchRows(ui: ThreadUi, matches: List<ChatSearch.Item>): List<SearchRow> = matches.reversed().map { m ->
    val ch = ui.channel
    if (ch != null) SearchRow(m.id, ui.title, ui.photoUrl, m.atMillis, m.text, outgoing = false, read = false, squircle = true)
    else {
        val mine = m.authorId == ui.me
        SearchRow(m.id,
            name = if (mine) "You" else if (ui.isGroup) ui.names[m.authorId] ?: "User" else ui.title,
            photoUrl = ui.photos[m.authorId] ?: if (mine) ui.myPhoto else if (ui.isGroup) null else ui.photoUrl,
            atMillis = m.atMillis, text = m.text, outgoing = mine,
            read = mine && m.atMillis <= ui.receipts.otherLastReadMillis)
    }
}

/**
 * "Show as List" (iOS InChatSearchList): every match as a chat-list row of the message, newest
 * first: the author's 60 dp photo, the name, read ticks and date, and the line cut to the first
 * match with the matches in the text colour. 76 dp rows. A tap goes to that message in the chat.
 */
@Composable
internal fun SearchResultsList(rows: List<SearchRow>, terms: List<String>, top: Dp, bottom: Dp, is24h: Boolean,
                               onSelect: (String) -> Unit) {
    val c = Fariin.colors
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(top = top, bottom = bottom)) {
        items(rows, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().height(76.dp).clickable { onSelect(r.id) }.padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(r.photoUrl, 60.dp, squircle = r.squircle)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxWidth().padding(end = 16.dp).align(Alignment.CenterStart),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp))
                            if (r.outgoing) {
                                // One tick sent, two once read; blue when read, as on the chat list.
                                Icon(if (r.read) Icons.Filled.DoneAll else Icons.Filled.Done,
                                    contentDescription = if (r.read) "Read" else "Sent",
                                    tint = if (r.read) c.accent else c.secondary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(ChatSearch.dateText(r.atMillis, is24h), fontSize = 15.sp, color = c.secondary, maxLines = 1)
                        }
                        val sn = ChatSearch.snippet(r.text, terms)
                        Text(buildAnnotatedString {
                            append(sn.text)
                            addStyle(SpanStyle(color = c.secondary), 0, sn.text.length)
                            for (m in sn.marks) addStyle(SpanStyle(color = c.label), m.first, m.last + 1)
                        }, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(Modifier.align(Alignment.BottomStart), thickness = 0.5.dp, color = c.separator)
                }
            }
        }
    }
}
