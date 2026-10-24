@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.twosub.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.ceil

private val Ink = Color(0xFF0B0F18)
private val Panel = Color(0xFF151D2B)
private val Muted = Color(0xFF9CAABD)
private val Accent = Color(0xFFFF2D78)
private val SoftWhite = Color(0xFFF6F7FA)
private val Control = Color(0xFF253246)
private val ControlOpticalOffset = 2.dp
private val ControlTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None),
)

/** The supplied session WebView stays mounted beneath the native application. */
@Composable
fun TvApp(state: TvAppState, onAction: (TvAction) -> Unit, webContent: @Composable () -> Unit) {
    val gridState = rememberLazyGridState()
    var focusedUrl by rememberSaveable { mutableStateOf("") }
    val videoFocus = remember { FocusRequester() }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel, onSurface = SoftWhite)) {
        Box(Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent { event ->
            if (state.screen != TvScreen.PLAYER || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (state.player.error != null || state.error != null) return@onPreviewKeyEvent false
            if (state.player.controlsVisible) onAction(TvAction.Interaction)
            when (event.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { onAction(TvAction.Toggle); true }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { if (state.player.paused) onAction(TvAction.Toggle); true }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (!state.player.paused) onAction(TvAction.Toggle); true }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { onAction(TvAction.Seek(-10.0)); true }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { onAction(TvAction.Seek(10.0)); true }
                else -> if (!state.player.controlsVisible) {
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                            onAction(TvAction.Toggle); onAction(TvAction.ShowControls); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { onAction(TvAction.Seek(-10.0)); true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { onAction(TvAction.Seek(10.0)); true }
                        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_MENU -> { onAction(TvAction.ShowControls); true }
                        else -> false
                    }
                } else false
            }
        }) {
            webContent()
            when (state.screen) {
                TvScreen.CATALOG -> CatalogScreen(state, gridState, focusedUrl, { focusedUrl = it }, onAction)
                TvScreen.DETAIL -> DetailScreen(state, onAction)
                TvScreen.LOGIN -> LoginScreen(state, onAction)
                TvScreen.PLAYER -> {
                    if (!state.player.controlsVisible && state.player.error == null && state.error == null) {
                        Box(Modifier.fillMaxSize().focusRequester(videoFocus).focusable())
                        LaunchedEffect(Unit) { videoFocus.requestFocus() }
                    }
                    PlayerScreen(state, onAction)
                }
            }
        }
    }
}

@Composable
private fun CatalogScreen(
    state: TvAppState,
    gridState: LazyGridState,
    focusedUrl: String,
    onFocused: (String) -> Unit,
    onAction: (TvAction) -> Unit,
) {
    var query by rememberSaveable(state.query) { mutableStateOf(state.query) }
    val cardFocus = remember { FocusRequester() }
    val firstCardFocus = remember { FocusRequester() }
    val searchButtonFocus = remember { FocusRequester() }
    val initialFocus = remember { FocusRequester() }
    val accountFocus = remember { FocusRequester() }
    val genreFocus = remember { FocusRequester() }
    val orderFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val uiScope = rememberCoroutineScope()
    var restored by remember { mutableStateOf(false) }
    var searchSubmitted by remember { mutableStateOf(false) }
    var pendingCategoryReset by remember { mutableStateOf(false) }
    var logoutConfirmation by remember { mutableStateOf(false) }
    var filterPanel by remember { mutableStateOf("") }
    val showFilters = state.query.isBlank() && state.category in setOf(CatalogCategory.ALL, CatalogCategory.FILMS, CatalogCategory.SERIES, CatalogCategory.CARTOONS)
    val restoreUrl = if (state.catalog.any { it.url == focusedUrl }) focusedUrl else state.catalog.firstOrNull()?.url
    val submitSearch: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        searchSubmitted = true
        pendingCategoryReset = false
        restored = false
        onAction(TvAction.Search(query.trim()))
    }
    val focusResults: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        uiScope.launch {
            if (state.catalog.isNotEmpty()) {
                gridState.scrollToItem(0)
                delay(70)
                runCatching { firstCardFocus.requestFocus() }
            } else runCatching { searchButtonFocus.requestFocus() }
        }
    }
    val focusSidebar: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        runCatching { initialFocus.requestFocus() }
    }
    LaunchedEffect(Unit) {
        if (state.catalog.isEmpty()) runCatching { initialFocus.requestFocus() }
    }
    LaunchedEffect(state.loading, state.catalog.isNotEmpty(), searchSubmitted) {
        if (!state.loading && !restored && state.catalog.isNotEmpty()) {
            if (searchSubmitted) gridState.scrollToItem(0)
            delay(60)
            runCatching { if (searchSubmitted) firstCardFocus.requestFocus() else cardFocus.requestFocus() }
            restored = true
            searchSubmitted = false
        } else if (!state.loading && searchSubmitted && state.catalog.isEmpty()) {
            runCatching { searchButtonFocus.requestFocus() }
            restored = true
            searchSubmitted = false
        }
    }
    LaunchedEffect(state.loading, state.category, state.catalog, pendingCategoryReset) {
        if (pendingCategoryReset && !state.loading && state.error == null) {
            delay(80)
            gridState.scrollToItem(0)
            pendingCategoryReset = false
        }
    }
    Row(Modifier.fillMaxSize().background(Ink).padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 22.dp)) {
        Column(Modifier.width(174.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 21.dp)) {
                Text("2", color = Accent, fontSize = 35.sp, fontWeight = FontWeight.Black)
                Text("SUB", color = SoftWhite, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text("TV", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 7.dp, top = 10.dp))
            }
            CatalogCategory.entries.forEachIndexed { index, category ->
                ActionButton(
                    label = category.label,
                    onClick = {
                        keyboard?.hide()
                        searchSubmitted = false
                        restored = true
                        if (state.category != category || state.query.isNotBlank()) pendingCategoryReset = true
                        onAction(TvAction.Category(category))
                    },
                    selected = state.category == category && state.query.isEmpty(),
                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                    icon = when (category.name) {
                        "SERIES" -> TvIconName.Television
                        "CONTINUE" -> TvIconName.History
                        "NOVELTIES", "NEW" -> TvIconName.Sparkle
                        else -> TvIconName.FilmSlate
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(if (!state.authKnown) "АККАУНТ 2SUB" else if (state.loggedIn) state.accountName.ifBlank { "ВХОД ВЫПОЛНЕН" } else "АККАУНТ 2SUB", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 9.dp, bottom = 2.dp))
            ActionButton(
                label = if (!state.authKnown) "Проверяем вход…" else if (state.loggedIn) "Выйти из аккаунта" else "Войти в аккаунт",
                onClick = {
                    if (state.loggedIn) { keyboard?.hide(); logoutConfirmation = true }
                    else onAction(TvAction.Login)
                },
                modifier = Modifier.fillMaxWidth().focusRequester(accountFocus),
                enabled = state.authKnown,
                icon = if (state.loggedIn) TvIconName.SignOut else TvIconName.SignIn,
            )
        }
        Spacer(Modifier.width(25.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(query, { query = it }, submitSearch, focusResults, focusSidebar, Modifier.weight(1f))
                ActionIconButton(TvIconName.Search, "Найти", submitSearch, Modifier.focusRequester(searchButtonFocus))
                ActionIconButton(TvIconName.Refresh, "Обновить каталог", { onAction(TvAction.Refresh) })
            }
            Row(Modifier.padding(top = 22.dp, bottom = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text(if (state.query.isNotBlank()) "Результаты поиска" else state.category.label.let { if (it == "Всё") "Каталог" else it }, fontSize = 26.sp, color = SoftWhite, fontWeight = FontWeight.Bold)
                    if (state.query.isNotBlank()) Text("«${state.query}»", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (state.loading) Text("Загружаем…", color = Accent, fontSize = 12.sp)
            }
            if (showFilters) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        state.genreOptions.firstOrNull { it.id == state.genre }?.label ?: "Все жанры",
                        { keyboard?.hide(); filterPanel = "genre" },
                        Modifier.widthIn(max = 230.dp).focusRequester(genreFocus),
                        selected = state.genre.isNotBlank(), icon = TvIconName.Filter,
                        enabled = state.genreOptions.isNotEmpty(),
                    )
                    ActionButton(
                        state.orderOptions.firstOrNull { it.id == state.order }?.label ?: "Сортировка",
                        { keyboard?.hide(); filterPanel = "order" },
                        Modifier.widthIn(max = 250.dp).focusRequester(orderFocus),
                        icon = TvIconName.Sort, enabled = state.orderOptions.isNotEmpty(),
                    )
                }
            }
            if (state.error != null) {
                Notice(state.error, { onAction(TvAction.Refresh) })
            }
            if (state.catalog.isEmpty() && !state.loading && state.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (state.category == CatalogCategory.CONTINUE) "Здесь появятся твои фильмы" else "Ничего не найдено", color = SoftWhite, fontSize = 21.sp)
                        Text(if (state.category == CatalogCategory.CONTINUE) "Начни просмотр — мы запомним место остановки." else "Попробуй другое название или открой каталог.", color = Muted, fontSize = 13.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = if (state.category == CatalogCategory.CONTINUE) GridCells.Fixed(3) else GridCells.Adaptive(132.dp),
                    state = gridState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(6.dp, 8.dp, 6.dp, 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(17.dp),
                    verticalArrangement = Arrangement.spacedBy(21.dp),
                ) {
                    items(state.catalog.distinctBy { it.id + it.url }, key = { it.id + it.url }) { item ->
                        val itemModifier = Modifier
                            .then(if (item.url == restoreUrl) Modifier.focusRequester(cardFocus) else Modifier)
                            .then(if (item.url == state.catalog.firstOrNull()?.url) Modifier.focusRequester(firstCardFocus) else Modifier)
                            .onFocusChanged { if (it.hasFocus) onFocused(item.url) }
                        if (state.category == CatalogCategory.CONTINUE) ContinueCard(item, { onAction(TvAction.Open(item)) }, itemModifier)
                        else PosterCard(item, { onAction(TvAction.Open(item)) }, itemModifier, showUpdates = state.category == CatalogCategory.NEW)
                    }
                    if (state.hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                                ActionButton(if (state.loading) "Загружаем…" else "Показать ещё", { onAction(TvAction.LoadMore) }, enabled = !state.loading)
                            }
                        }
                    }
                }
            }
        }
    }
    if (logoutConfirmation && state.loggedIn) {
        LogoutConfirmation(
            onCancel = {
                logoutConfirmation = false
                uiScope.launch { delay(60); runCatching { accountFocus.requestFocus() } }
            },
            onConfirm = { logoutConfirmation = false; onAction(TvAction.Logout) },
        )
    }
    if (filterPanel.isNotBlank() && showFilters) {
        val isGenre = filterPanel == "genre"
        val closeFilter: () -> Unit = {
            filterPanel = ""
            uiScope.launch { delay(60); runCatching { if (isGenre) genreFocus.requestFocus() else orderFocus.requestFocus() } }
        }
        FilterChoices(
            title = if (isGenre) "Жанр" else "Сортировка",
            options = if (isGenre) state.genreOptions else state.orderOptions,
            selectedId = if (isGenre) state.genre else state.order,
            onDismiss = closeFilter,
            onSelect = { id ->
                pendingCategoryReset = true
                restored = true
                searchSubmitted = false
                closeFilter()
                onAction(TvAction.Filters(if (isGenre) id else state.genre, if (isGenre) state.order else id))
            },
        )
    }
}

@Composable
private fun FilterChoices(title: String, options: List<PlaybackOption>, selectedId: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val optionFocus = remember { FocusRequester() }
    val listState = rememberLazyGridState()
    val initialIndex = options.indexOfFirst { it.id == selectedId && it.enabled }.takeIf { it >= 0 }
        ?: options.indexOfFirst { it.enabled }.coerceAtLeast(0)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) {
            if (options.isNotEmpty()) listState.scrollToItem(initialIndex)
            delay(80)
            runCatching { optionFocus.requestFocus() }
        }
        Column(Modifier.width(620.dp).background(Panel, RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF344158), RoundedCornerShape(16.dp)).padding(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = SoftWhite, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                ActionIconButton(TvIconName.Close, "Закрыть", onDismiss)
            }
            LazyVerticalGrid(columns = GridCells.Fixed(2), state = listState, modifier = Modifier.heightIn(max = 320.dp), contentPadding = PaddingValues(5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(options, key = { it.id }) { option ->
                    ActionButton(option.label, { onSelect(option.id) }, Modifier.fillMaxWidth().then(if (option == options.getOrNull(initialIndex)) Modifier.focusRequester(optionFocus) else Modifier), selected = option.id == selectedId, enabled = option.enabled, icon = if (option.id == selectedId) TvIconName.Check else null)
                }
            }
        }
    }
}

@Composable
private fun LogoutConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(Unit) { delay(60); runCatching { cancelFocus.requestFocus() } }
        Column(
            Modifier.width(440.dp).background(Panel, RoundedCornerShape(18.dp))
                .border(1.dp, Color(0xFF344158), RoundedCornerShape(18.dp)).padding(27.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Выйти из аккаунта?", color = SoftWhite, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("Для следующего входа понадобится снова отсканировать QR-код телефоном.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                ActionButton("Подтвердить выход", onConfirm)
                ActionButton("Отмена", onCancel, Modifier.focusRequester(cancelFocus), selected = true)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, onMoveDown: () -> Unit, onMoveToSidebar: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ControlTextStyle.copy(color = SoftWhite, fontSize = 15.sp, lineHeight = 20.sp),
        cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }, onDone = { onSearch() }, onGo = { onSearch() }),
        modifier = modifier.height(44.dp).onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { onMoveDown(); true }
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { onMoveToSidebar(); true }
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> { onSearch(); true }
                    else -> false
                }
            }
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color(0xFF2A3547), RoundedCornerShape(12.dp))
            .background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().offset(y = ControlOpticalOffset), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text("Поиск фильма или сериала", color = Muted, fontSize = 15.sp, lineHeight = 20.sp, style = ControlTextStyle)
                inner()
            }
        },
    )
}

@Composable
private fun PosterCard(item: CardItem, onClick: () -> Unit, modifier: Modifier = Modifier, showUpdates: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val updateLabel = item.episodeLabel.ifBlank { episodeLabel(item.url).orEmpty() }
    Column(modifier) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(.68f).onFocusChanged { focused = it.hasFocus }, scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f), border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None), glow = CardDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None)) {
            Box(Modifier.fillMaxSize().background(Panel)) {
                AsyncImage(model = item.poster.ifBlank { null }, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (item.poster.isBlank()) Text(item.title, color = Muted, fontSize = 17.sp, modifier = Modifier.align(Alignment.Center).padding(14.dp))
                if (item.progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = .6f))) {
                    Box(Modifier.fillMaxWidth(item.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                }
                if (focused) Box(Modifier.fillMaxSize().border(3.dp, SoftWhite, RoundedCornerShape(12.dp)))
            }
        }
        Text(item.title, color = SoftWhite, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, modifier = Modifier.padding(top = 14.dp), style = ControlTextStyle)
        Text(listOf(item.year, kindLabel(item.kind)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { item.originalTitle }, color = Muted, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp), style = ControlTextStyle)
        if (showUpdates || updateLabel.isNotBlank()) Text(updateLabel, color = Color(0xFFFF8AB2), minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp), style = ControlTextStyle)
        if (showUpdates || item.addedLabel.isNotBlank()) Text(item.addedLabel, color = Muted, fontSize = 10.sp, lineHeight = 14.sp, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), style = ControlTextStyle)
    }
}

@Composable
private fun ContinueCard(item: CardItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val episode = if (item.season > 0 && item.episode > 0) "Сезон ${item.season} · Серия ${item.episode}"
        else item.episodeLabel.ifBlank { episodeLabel(item.url).orEmpty() }
    val context = episode.ifBlank { kindLabel(item.kind) }
    val progress = if (item.durationSeconds > 0) (item.positionSeconds / item.durationSeconds).toFloat().coerceIn(0f, 1f) else item.progress.coerceIn(0f, 1f)
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }, scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f), shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)), border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None), glow = CardDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None)) {
        Column(Modifier.fillMaxWidth().background(if (focused) SoftWhite else Panel).border(if (focused) 2.dp else 1.dp, if (focused) SoftWhite else Color(0xFF344158), RoundedCornerShape(12.dp))) {
            AsyncImage(model = item.landscape.ifBlank { item.poster }.ifBlank { null }, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink))
            Column(Modifier.fillMaxWidth().height(136.dp).padding(12.dp)) {
                Text(item.title, color = if (focused) Ink else SoftWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (context.isNotBlank()) Text(context, color = if (focused) Color(0xFF6D2544) else Color(0xFFFF8AB2), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.weight(1f))
                Text("Просмотрено ${clock(item.positionSeconds)}", color = if (focused) Ink else SoftWhite, fontSize = 11.sp)
                if (item.durationSeconds > item.positionSeconds) Text("Осталось ${ceil((item.durationSeconds - item.positionSeconds) / 60).toInt()} мин", color = if (focused) Color(0xFF4D5C70) else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (focused) Color(0xFFD4DAE3) else Control)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent))
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: EpisodeItem, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.width(190.dp).height(90.dp).onFocusChanged { focused = it.hasFocus },
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
        glow = CardDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
    ) {
        Box(
            Modifier.fillMaxSize().background(if (focused) SoftWhite else if (selected) Color(0xFF472238) else Control)
                .border(1.dp, if (selected) Accent else Color(0xFF42516A), RoundedCornerShape(10.dp)).padding(14.dp),
        ) {
            Column(Modifier.fillMaxWidth().align(Alignment.CenterStart).offset(y = ControlOpticalOffset), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Сезон ${episode.season} · Серия ${episode.number}", color = if (focused) Ink else if (selected) Color(0xFFFF8AB2) else Muted, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, style = ControlTextStyle)
                Text(episode.title, color = if (focused) Ink else SoftWhite, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = ControlTextStyle)
            }
        }
    }
}

@Composable
private fun DetailScreen(state: TvAppState, onAction: (TvAction) -> Unit) {
    val detail = state.detail
    val detailArtwork = detail?.item?.let { it.poster.ifBlank { it.landscape } }.orEmpty()
    val playFocus = remember { FocusRequester() }
    val density = LocalDensity.current
    var detailColumnHeightPx by remember(detail?.item?.id) { mutableIntStateOf(0) }
    var posterAspectRatio by remember(detailArtwork) { mutableFloatStateOf(2f / 3f) }
    var posterRatioResolved by remember(detailArtwork) { mutableStateOf(false) }
    var season by remember(detail?.item?.id) { mutableStateOf(detail?.resumeSeason?.takeIf { it > 0 } ?: detail?.episodes?.firstOrNull()?.season ?: 1) }
    LaunchedEffect(detail?.item?.id, state.loading, state.error) {
        if (detail != null && !state.loading && state.error == null) {
            delay(80)
            runCatching { playFocus.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ActionButton("Каталог", { onAction(TvAction.Back) }, icon = TvIconName.ArrowLeft)
                if (state.loading) Text("Загрузка карточки…", color = Muted, fontSize = 12.sp)
            }
            if (state.error != null) Notice(state.error, { onAction(TvAction.Refresh) })
            if (detail != null) {
                BoxWithConstraints(Modifier.padding(top = 16.dp).fillMaxWidth()) {
                    val proportionalWidth = if (detailColumnHeightPx > 0) with(density) { (detailColumnHeightPx * posterAspectRatio).toDp() } else 160.dp
                    val posterWidth = proportionalWidth.coerceAtMost(maxWidth * .30f).coerceAtLeast(1.dp)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(27.dp)) {
                        Box(Modifier.width(posterWidth).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Panel)) {
                            AsyncImage(
                                model = detailArtwork,
                                contentDescription = detail.item.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.matchParentSize(),
                                onSuccess = { image ->
                                    val drawable = image.result.drawable
                                    if (!posterRatioResolved && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                                        val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                                        if (ratio.isFinite() && ratio > 0f) {
                                            posterAspectRatio = ratio
                                            posterRatioResolved = true
                                        }
                                    }
                                },
                            )
                        }
                        Column(Modifier.weight(1f).onSizeChanged { size ->
                            if (size.height > 0 && size.height != detailColumnHeightPx) detailColumnHeightPx = size.height
                        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(detail.item.title, color = SoftWhite, fontSize = 31.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (detail.item.originalTitle.isNotBlank()) Text(detail.item.originalTitle, color = Muted, fontSize = 15.sp)
                            Text(detail.meta.ifBlank { listOf(detail.item.year, kindLabel(detail.item.kind)).filter { it.isNotBlank() }.joinToString("  ·  ") }, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                            if (detail.ratings.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    detail.ratings.forEach { rating ->
                                        Row(Modifier.background(Panel, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 7.dp).offset(y = ControlOpticalOffset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (rating.source.lowercase() in setOf("kp", "kinopoisk", "imdb", "tmdb")) TvRatingBrandIcon(source = rating.source, height = 15.dp)
                                            else Text(rating.label, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, style = ControlTextStyle)
                                            Text(rating.value, color = SoftWhite, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, style = ControlTextStyle)
                                        }
                                    }
                                }
                            }
                            if (detail.description.isNotBlank()) Text(detail.description, color = Color(0xFFC3CCDA), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (detail.resumeSeason > 0 && detail.resumeEpisode > 0) {
                                Text("Сезон ${detail.resumeSeason} · Серия ${detail.resumeEpisode}", color = Color(0xFFFF8AB2), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 3.dp)) {
                                ActionButton(if (detail.resumeSeconds > 15) "Продолжить ${clock(detail.resumeSeconds)}" else "Смотреть", { onAction(TvAction.Play()) }, Modifier.focusRequester(playFocus), selected = true, enabled = !state.loading && state.error == null, icon = TvIconName.Play)
                                if (detail.resumeSeconds > 15) ActionButton("Сначала", { onAction(TvAction.Play(fromStart = true)) }, enabled = !state.loading && state.error == null)
                            }
                        }
                    }
                }
                if (detail.episodes.isNotEmpty()) {
                    val seasons = detail.episodes.map { it.season }.distinct().sorted()
                    Row(Modifier.padding(top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Эпизоды", color = SoftWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 7.dp))
                        seasons.forEach { number -> ActionButton("Сезон $number", { season = number }, selected = number == season) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                        items(detail.episodes.filter { it.season == season }, key = { it.id }) { episode ->
                            EpisodeCard(episode, { onAction(TvAction.Episode(episode)) }, selected = episode.url == state.selectedUrl)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(state: TvAppState, onAction: (TvAction) -> Unit) {
    val backFocus = remember { FocusRequester() }
    val instruction = "Отсканируй QR-код телефоном и подтверди вход в свой аккаунт"
    LaunchedEffect(Unit) { backFocus.requestFocus() }
    Box(Modifier.fillMaxSize().then(if (!state.loginReady) Modifier.background(Ink) else Modifier)) {
        Column(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 28.dp, vertical = 15.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                ActionButton("Назад", { onAction(TvAction.Back) }, Modifier.focusRequester(backFocus), icon = TvIconName.ArrowLeft)
                Column(Modifier.weight(1f)) {
                    Text("Вход в 2SUB", color = SoftWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (state.loginReady) Text(instruction, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                if (state.loginReady || state.error != null) ActionButton(if (state.loginReady) "Новый код" else "Повторить", { onAction(TvAction.Login) }, icon = TvIconName.Refresh)
            }
            if (state.loginReady && state.error != null) Text(state.error, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (!state.loginReady) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 580.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.error == null) LoadingSpinner()
                Text(if (state.error != null) "Не удалось подготовить вход" else "Подготавливаем вход…", color = SoftWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                val status = state.error ?: state.status.trim().takeIf { it.isNotBlank() && !it.contains("Отскан", ignoreCase = true) && !it.contains("QR", ignoreCase = true) }
                if (!status.isNullOrBlank()) Text(status, color = Muted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun LoadingSpinner() {
    val transition = rememberInfiniteTransition(label = "loading")
    val angle by transition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Restart), label = "loading angle")
    Canvas(Modifier.size(30.dp)) { drawArc(color = Accent, startAngle = angle, sweepAngle = 260f, useCenter = false, style = Stroke(3.dp.toPx())) }
}

@Composable
private fun PlayerScreen(state: TvAppState, onAction: (TvAction) -> Unit) {
    var panel by remember { mutableStateOf("") }
    val player = state.player
    val playbackError = player.error ?: state.error
    val playFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    val videoChoiceFocus = remember { FocusRequester() }
    val subtitleChoiceFocus = remember { FocusRequester() }
    val speedChoiceFocus = remember { FocusRequester() }
    val episodeChoiceFocus = remember { FocusRequester() }
    val serverListState = rememberLazyListState()
    val episodeListState = rememberLazyListState()
    val episodeOptions = state.detail?.episodes.orEmpty()
    val playbackTitle = state.detail?.item?.title?.takeIf { it.isNotBlank() }?.let { title ->
        episodeOptions.firstOrNull { it.url == state.selectedUrl }?.let { "$title · ${it.season}×${it.number}" } ?: title
    } ?: player.title
    val initialEpisodeIndex = episodeOptions.indexOfFirst { it.url == state.selectedUrl }.coerceAtLeast(0)
    val initialEpisodeId = episodeOptions.getOrNull(initialEpisodeIndex)?.id
    val speedOptions = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
    val initialSpeed = player.speed.takeIf { it in speedOptions } ?: 1f
    val qualityOptions = listOf("auto", "hd", "sd").mapNotNull { id -> player.qualityOptions.firstOrNull { it.id == id } }
    val initialQualityId = qualityOptions.firstOrNull { it.enabled && it.id == player.videoQualityMode }?.id
        ?: qualityOptions.firstOrNull { it.enabled }?.id
    val initialServerId = if (initialQualityId == null) player.serverOptions.firstOrNull { it.enabled && it.id == player.serverId }?.id
        ?: player.serverOptions.firstOrNull { it.enabled }?.id else null
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(player.buffering, state.selectedUrl) {
        stalled = false
        if (player.buffering) { delay(10_000); stalled = true }
    }
    val playbackNotice = when {
        stalled && player.buffering -> "Загрузка задерживается. В меню «Видео» можно сменить сервер или качество."
        player.controlsVisible && panel.isEmpty() -> player.playbackNotice
        else -> ""
    }
    LaunchedEffect(panel) {
        when (panel) {
            "video" -> {
                val serverIndex = player.serverOptions.indexOfFirst { it.id == player.serverId }
                if (serverIndex >= 0) serverListState.scrollToItem(serverIndex)
                delay(90)
                runCatching { videoChoiceFocus.requestFocus() }
            }
            "subtitles" -> {
                delay(90)
                runCatching { subtitleChoiceFocus.requestFocus() }
            }
            "speed" -> {
                delay(90)
                runCatching { speedChoiceFocus.requestFocus() }
            }
            "episodes" -> {
                if (episodeOptions.isNotEmpty()) episodeListState.scrollToItem(initialEpisodeIndex)
                delay(90)
                runCatching { episodeChoiceFocus.requestFocus() }
            }
        }
    }
    LaunchedEffect(panel) { onAction(TvAction.PanelVisibility(panel.isNotEmpty())) }
    DisposableEffect(Unit) { onDispose { onAction(TvAction.PanelVisibility(false)) } }
    BackHandler(enabled = panel.isNotEmpty()) {
        panel = ""
        runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(player.controlsVisible, playbackError) {
        if (player.controlsVisible && playbackError == null) { delay(70); runCatching { playFocus.requestFocus() } }
        else panel = ""
    }
    LaunchedEffect(playbackError) {
        if (playbackError != null) { delay(70); runCatching { errorFocus.requestFocus() } }
    }
    Box(Modifier.fillMaxSize().background(if (player.ready) Color.Transparent else Color.Black)) {
        AnimatedVisibility(visible = player.controlsVisible && playbackError == null, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .85f), Color.Transparent))).padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                ActionButton("Назад", { onAction(TvAction.ExitPlayer) }, icon = TvIconName.ArrowLeft)
                Column(Modifier.weight(1f)) {
                    Text(playbackTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (!player.ready) "Загружаем видео…" else listOf(player.subtitleMode.label, speedText(player.speed), player.actualQuality).filter { it.isNotBlank() }.joinToString("  ·  "), color = Color.White.copy(alpha = .7f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                ActionButton("Скрыть", { panel = ""; onAction(TvAction.CloseControls) })
            }
        }
        if (playbackError == null && (player.buffering || playbackNotice.isNotBlank())) {
            Column(
                Modifier.align(if (player.buffering) Alignment.Center else Alignment.TopCenter)
                    .padding(top = if (player.buffering) 0.dp else 106.dp)
                    .widthIn(max = 520.dp).background(Color.Black.copy(alpha = .8f), RoundedCornerShape(12.dp)).padding(horizontal = 19.dp, vertical = 13.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (player.buffering) Text("Загружаем видео…", color = SoftWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (playbackNotice.isNotBlank()) Text(playbackNotice, color = Color(0xFFCBD3DF), fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (playbackError != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 580.dp).background(Panel, RoundedCornerShape(16.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Не удалось начать просмотр", color = SoftWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(playbackError, color = Muted, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Повторить", { onAction(TvAction.Refresh) }, Modifier.focusRequester(errorFocus), selected = true)
                    ActionButton("Вернуться к фильму", { onAction(TvAction.ExitPlayer) })
                }
            }
        }
        AnimatedVisibility(visible = player.controlsVisible && playbackError == null, modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .92f)))).padding(start = 32.dp, end = 32.dp, top = 38.dp, bottom = 20.dp)) {
                if (panel.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 17.dp).background(Panel.copy(alpha = .97f), RoundedCornerShape(14.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(when (panel) { "subtitles" -> "Субтитры"; "episodes" -> "Эпизоды"; "video" -> "Видео"; else -> "Скорость" }, color = SoftWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                if (player.buffering) Text("Загружаем видео…", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                            ActionButton("Готово", { panel = ""; runCatching { playFocus.requestFocus() } }, if (panel == "video" && initialQualityId == null && initialServerId == null) Modifier.focusRequester(videoChoiceFocus) else Modifier)
                        }
                        when (panel) {
                            "subtitles" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SubtitleMode.entries.forEach { mode -> ActionButton(mode.label, { onAction(TvAction.Subtitles(mode)) }, if (player.subtitleMode == mode) Modifier.focusRequester(subtitleChoiceFocus) else Modifier, selected = player.subtitleMode == mode) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Размер", color = Muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                                    ActionButton("−", { onAction(TvAction.SubtitleScale((player.subtitleScale - .1f).coerceAtLeast(.6f))) })
                                    Text("${(player.subtitleScale * 100).roundToInt()}%", color = SoftWhite, fontSize = 14.sp, modifier = Modifier.width(48.dp))
                                    ActionButton("+", { onAction(TvAction.SubtitleScale((player.subtitleScale + .1f).coerceAtMost(1.8f))) })
                                    ActionButton(if (player.subtitleBackground) "Подложка: вкл" else "Подложка: выкл", { onAction(TvAction.SubtitleBackground(!player.subtitleBackground)) }, selected = player.subtitleBackground)
                                }
                                if (!player.englishAvailable || !player.russianAvailable) Text("${if (!player.englishAvailable) "EN" else "РУ"}: субтитры не найдены для этого видео", color = Accent, fontSize = 11.sp)
                            }
                            "speed" -> Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                speedOptions.forEach { value -> ActionButton(speedText(value), { onAction(TvAction.Speed(value)) }, if (value == initialSpeed) Modifier.focusRequester(speedChoiceFocus) else Modifier, selected = player.speed == value) }
                            }
                            "video" -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Качество", color = Muted, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                                    qualityOptions.forEach { option ->
                                        ActionButton(
                                            when (option.id) { "auto" -> "Авто"; "hd" -> "HD"; "sd" -> "SD"; else -> option.label },
                                            { onAction(TvAction.Quality(option.id)) },
                                            Modifier.then(if (option.id == initialQualityId) Modifier.focusRequester(videoChoiceFocus) else Modifier),
                                            selected = player.videoQualityMode == option.id,
                                            enabled = option.enabled,
                                        )
                                    }
                                    if (qualityOptions.isEmpty()) Text("Варианты появятся после загрузки видео", color = Muted, fontSize = 12.sp)
                                    else Text("Авто учитывает совместимость видео с этим ТВ", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 2)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Сервер", color = Muted, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                                    if (player.serverOptions.isEmpty()) Text("Список серверов загружается…", color = Muted, fontSize = 12.sp)
                                    else LazyRow(
                                        state = serverListState,
                                        modifier = Modifier.weight(1f).height(54.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                                    ) {
                                        items(player.serverOptions, key = { it.id }) { option ->
                                            ActionButton(option.label, { onAction(TvAction.Server(option.id)) }, Modifier.widthIn(max = 220.dp)
                                                .then(if (option.id == initialServerId) Modifier.focusRequester(videoChoiceFocus) else Modifier),
                                                selected = player.serverId == option.id, enabled = option.enabled)
                                        }
                                    }
                                }
                            }
                            "episodes" -> LazyRow(state = episodeListState, horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                                items(episodeOptions, key = { it.id }) { episode -> EpisodeCard(episode, { panel = ""; onAction(TvAction.Episode(episode)) }, if (episode.id == initialEpisodeId) Modifier.focusRequester(episodeChoiceFocus) else Modifier, selected = episode.url == state.selectedUrl) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(player.currentTime), color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(if (player.duration > 0) clock(player.duration) else "—:—", color = Muted, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .2f))) {
                    Box(Modifier.fillMaxWidth(if (player.duration > 0) (player.currentTime / player.duration).toFloat().coerceIn(0f, 1f) else 0f).fillMaxHeight().background(Accent))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ActionButton(if (player.paused) "Смотреть" else "Пауза", { onAction(TvAction.Toggle) }, Modifier.focusRequester(playFocus), selected = true, icon = if (player.paused) TvIconName.Play else TvIconName.Pause)
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(5.dp)) {
                        item { ActionButton("Субтитры", { panel = if (panel == "subtitles") "" else "subtitles" }, selected = panel == "subtitles", icon = TvIconName.Subtitles) }
                        item { ActionButton("Видео", { panel = if (panel == "video") "" else "video" }, selected = panel == "video", icon = TvIconName.Sliders) }
                        item { ActionButton(speedText(player.speed), { panel = if (panel == "speed") "" else "speed" }, selected = panel == "speed") }
                        if (state.detail?.episodes?.isNotEmpty() == true) item { ActionButton("Серии", { panel = if (panel == "episodes") "" else "episodes" }, selected = panel == "episodes", icon = TvIconName.Episodes) }
                    }
                }
                Text("← → перемотка при скрытом меню  ·  OK пауза  ·  ↑ ↓ управление", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 13.dp))
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, icon: TvIconName? = null) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f, disabledScale = 1f, focusedDisabledScale = 1f),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Accent.copy(alpha = .23f) else Control,
            contentColor = if (selected) Color(0xFFFF8AB2) else SoftWhite,
            focusedContainerColor = SoftWhite,
            focusedContentColor = Ink,
        ),
    ) {
        Row(Modifier.offset(y = ControlOpticalOffset), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                TvIcon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = LocalContentColor.current)
                Spacer(Modifier.width(7.dp))
            }
            Text(label, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ControlTextStyle)
        }
    }
}

@Composable
private fun ActionIconButton(icon: TvIconName, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f, disabledScale = 1f, focusedDisabledScale = 1f),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.colors(containerColor = Control, contentColor = SoftWhite, focusedContainerColor = SoftWhite, focusedContentColor = Ink),
    ) {
        TvIcon(icon, contentDescription = label, modifier = Modifier.size(21.dp).offset(y = ControlOpticalOffset), tint = LocalContentColor.current)
    }
}

@Composable
private fun Notice(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).background(Panel, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Text(message, color = Color(0xFFFFB3CC), fontSize = 13.sp, modifier = Modifier.weight(1f))
        ActionButton("Повторить", onRetry)
    }
}

private fun clock(seconds: Double): String {
    val whole = seconds.coerceAtLeast(0.0).toLong()
    val h = whole / 3600
    val m = (whole % 3600) / 60
    val s = whole % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun speedText(value: Float): String = "${if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()}×"

private fun kindLabel(kind: String): String = when (kind.lowercase()) {
    "film", "films", "movie", "movies", "фильм" -> "Фильм"
    "series", "serial", "сериал" -> "Сериал"
    "animation", "cartoon", "cartoons", "мультфильм" -> "Мультфильм"
    else -> ""
}

private val episodeFragment = Regex("watch-s([0-9]+)e([0-9]+)")

private fun episodeLabel(url: String): String? {
    val match = episodeFragment.matchEntire(url.substringAfter('#', "")) ?: return null
    return "Сезон ${match.groupValues[1]} · Серия ${match.groupValues[2]}"
}
