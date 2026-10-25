package app.twosub.tv

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/** Own TV UI around the user's ordinary 2SUB session. No separate client account backend. */
class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(TvAppState(loading = true, status = "Подключаемся к 2SUB…"))
    private lateinit var web: WebView
    private lateinit var preferences: SharedPreferences
    private lateinit var mediaSession: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var adapterReady = false
    private var rendererGone = false
    private var foreground = false
    private var catalogPage = 1
    private var pendingCommand: Pair<String, JSONObject>? = null
    private var appliedPlayerPreferences = false
    private var lastHistoryWrite = 0L
    private var lastPlayerLog = 0L
    private var currentItem: CardItem? = null
    private var requestedDetailUrl = ""
    private var accountHistory: List<CardItem> = emptyList()
    private var accountResume: List<ResumePoint> = emptyList()
    private var resolvedDetailUrl: String? = null
    private var playerPanelOpen = false
    private var controlsClearance = 0f
    private var pauseOnReady = false
    private var playbackErrorCanRecover = false
    private var restartHistoryRequested = false
    private val hideControls = Runnable {
        if (state.screen == TvScreen.PLAYER && !state.player.paused && !playerPanelOpen && !state.player.scrubbing) {
            state = state.copy(player = state.player.copy(controlsVisible = false))
        }
    }
    private val loadingTimeout = Runnable {
        if (state.loading) {
            if (state.screen == TvScreen.PLAYER && adapterReady) {
                // The site's source switch may still be loading; its bounded playback watchdog
                // reports progress and leaves quality/server controls usable.
                state = state.copy(loading = false)
            } else {
                playbackErrorCanRecover = state.screen == TvScreen.PLAYER
                state = state.copy(loading = false, error = "2SUB пока не ответил. Проверьте сеть и нажмите «Повторить».")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        preferences = getSharedPreferences("twosub-local", MODE_PRIVATE)
        val mode = runCatching { SubtitleMode.valueOf(preferences.getString("subtitleMode", "BOTH")!!) }.getOrDefault(SubtitleMode.BOTH)
        state = state.copy(player = state.player.copy(
            subtitleMode = mode,
            subtitleScale = preferences.getFloat("subtitleScale", 1f),
            subtitleBackground = preferences.getBoolean("subtitleBackground", true),
            speed = preferences.getFloat("speed", 1f)
        ))
        createWebView()
        createMediaSession()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { onAction(TvAction.Back) }
        })
        setContent {
            LaunchedEffect(state.screen, state.player.controlsVisible) {
                syncPlayerControls()
            }
            TvApp(state, ::onAction) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize(), update = {
                    it.visibility = if (state.screen == TvScreen.PLAYER || (state.screen == TvScreen.LOGIN && state.loginReady)) View.VISIBLE else View.INVISIBLE
                })
            }
        }
        web.loadUrl(SITE)
        handler.postDelayed(loadingTimeout, 40_000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = settings.userAgentString.replace(" Mobile", "") + " Android TV; Android Leanback"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // Original site remains top-level; don't enable third-party cookies without a demonstrated need.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            state = state.copy(loading = false, error = "Обновите Android System WebView: требуется защищённый канал сообщений.")
            return
        }
        WebViewCompat.addWebMessageListener(web, "TwoSubBridge", setOf("https://2sub.movie")) { _, message, origin, mainFrame, _ ->
            if (!mainFrame || origin.scheme != "https" || origin.host != "2sub.movie") return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            if (data.length > 1_500_000) return@addWebMessageListener
            runCatching { onSiteEvent(JSONObject(data)) }.onFailure {
                if (BuildConfig.DEBUG) android.util.Log.w(TAG, "Adapter event rejected: ${it.javaClass.simpleName}")
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val allowed = isSiteUrl(request.url.toString())
                if (!allowed) {
                    state = state.copy(loading = false, error = "Этот переход открывается на телефоне через официальный сайт 2SUB.")
                }
                return !allowed
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                adapterReady = false
                if (state.screen == TvScreen.LOGIN) {
                    view.visibility = View.INVISIBLE
                    state = state.copy(loginReady = false, status = "Подключаем аккаунт…")
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && isSiteUrl(url) && !rendererGone) {
                    view.evaluateJavascript(assets.open("2sub-adapter.js").bufferedReader().use { it.readText() }, null)
                    CookieManager.getInstance().flush()
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) state = state.copy(loading = false, error = "Не удалось открыть 2SUB. Проверьте подключение и повторите.")
            }
            override fun onReceivedSslError(view: WebView, error: SslErrorHandler, details: android.net.http.SslError) {
                error.cancel()
                state = state.copy(loading = false, error = "Не удалось проверить защищённое соединение с сайтом.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererGone = true
                adapterReady = false
                state = state.copy(loading = false, error = "Веб-плеер остановился. Нажмите «Повторить», чтобы восстановить приложение.")
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean = true
        }
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "2SUB TV").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { command("resume") }
                override fun onPause() { command("pause") }
                override fun onSeekTo(pos: Long) { command("seek", json("delta" to (pos / 1000.0 - state.player.currentTime))) }
                override fun onFastForward() { command("seek", json("delta" to 10)) }
                override fun onRewind() { command("seek", json("delta" to -10)) }
            })
        }
    }

    private fun command(name: String, payload: JSONObject = JSONObject()) {
        if (rendererGone) return
        if (state.player.scrubbing && name in setOf("pause", "closePlayer", "resume", "toggle", "seek")) {
            state = state.copy(player = state.player.copy(scrubbing = false))
        }
        if (!adapterReady) {
            if (name == "pause" || name == "closePlayer") {
                pauseOnReady = true
                if (pendingCommand?.first == "play") pendingCommand = null
            } else if (name != "state" && name != "controls") pendingCommand = name to payload
            return
        }
        web.evaluateJavascript("window.TwoSubTV && window.TwoSubTV.command(${JSONObject.quote(name)},${payload});", null)
    }

    private fun loadCatalog(reset: Boolean = true) {
        if (state.category == CatalogCategory.CONTINUE && state.authKnown && !state.loggedIn) {
            state = state.copy(catalog = historyItems(), loading = false, hasMore = false, error = null)
            return
        }
        if (reset) catalogPage = 1
        state = state.copy(loading = true, error = null, status = "Загружаем каталог…",
            catalog = if (state.category == CatalogCategory.CONTINUE && reset) historyItems() else state.catalog)
        armTimeout()
        command("catalog", json("category" to catalogCategoryId(), "query" to state.query,
            "genre" to state.genre, "order" to state.order, "page" to if (reset) 1 else catalogPage + 1))
    }

    private fun catalogCategoryId(): String = when (state.category) {
            CatalogCategory.SERIES -> "series"
            CatalogCategory.CARTOONS -> "animation"
            CatalogCategory.ALL -> "all"
            CatalogCategory.NEW -> "new"
            CatalogCategory.CONTINUE -> "continue"
            else -> "films"
    }

    private fun onAction(action: TvAction) {
        when (action) {
            is TvAction.Category -> {
                command("pause")
                state = state.copy(category = action.value, screen = TvScreen.CATALOG, catalog = emptyList(), query = "", genre = "", order = "site_popularity")
                loadCatalog()
            }
            is TvAction.Search -> {
                state = state.copy(query = action.query, category = CatalogCategory.ALL, genre = "", order = "site_popularity", catalog = emptyList())
                loadCatalog()
            }
            is TvAction.Filters -> {
                if (state.category in setOf(CatalogCategory.CONTINUE, CatalogCategory.NEW)) return
                state = state.copy(genre = action.genre, order = action.order, catalog = emptyList())
                loadCatalog()
            }
            is TvAction.Open -> {
                if (!isSiteUrl(action.item.url)) return
                currentItem = action.item
                requestedDetailUrl = action.item.url
                resolvedDetailUrl = null
                val resumeUrl = HistoryPolicy.resumeUrl(action.item.url, resumePoints().map { it.url })
                state = state.copy(screen = TvScreen.DETAIL, selectedUrl = resumeUrl,
                    detail = withResume(FilmDetail(action.item), resumeUrl), loading = true, error = null)
                armTimeout()
                command("detail", json("url" to action.item.url))
            }
            is TvAction.Play -> {
                if (state.screen != TvScreen.DETAIL || state.loading || state.error != null ||
                    resolvedDetailUrl != HistoryPolicy.titleKey(state.selectedUrl)) return
                startPlayer(state.selectedUrl, if (action.fromStart) 0.0 else resumePosition(state.selectedUrl), action.fromStart)
            }
            is TvAction.Episode -> {
                saveHistory()
                startPlayer(action.item.url, resumePosition(action.item.url))
            }
            TvAction.Back -> handleBack()
            TvAction.ExitPlayer -> exitPlayer()
            TvAction.Login -> {
                command("pause")
                state = state.copy(screen = TvScreen.LOGIN, loginReady = false, error = null, loading = false, status = "Подготавливаем вход через телефон…")
                command("login")
            }
            TvAction.Logout -> {
                command("logout")
                pendingCommand = null
                pauseOnReady = true
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    state = state.copy(loggedIn = false, authKnown = true, loginReady = false, accountName = "", screen = TvScreen.CATALOG, catalog = emptyList(), loading = true, status = "Выход выполнен на этом ТВ")
                    web.loadUrl(SITE)
                }
            }
            TvAction.Refresh -> {
                if (rendererGone) { recreate(); return }
                state = state.copy(error = null)
                if (!adapterReady) { web.reload(); return }
                when (state.screen) {
                    TvScreen.CATALOG -> loadCatalog()
                    TvScreen.DETAIL -> command("detail", json("url" to state.selectedUrl))
                    TvScreen.LOGIN -> { state = state.copy(loginReady = false); command("login") }
                    TvScreen.PLAYER -> startPlayer(state.selectedUrl, state.player.currentTime)
                }
            }
            TvAction.LoadMore -> if (state.hasMore && !state.loading) loadCatalog(false)
            TvAction.Toggle -> { command("toggle"); scheduleHide() }
            is TvAction.Seek -> { command("seek", json("delta" to action.seconds)); scheduleHide() }
            is TvAction.PreviewSeek -> if (state.screen == TvScreen.PLAYER && state.player.ready && action.playbackId == state.player.playbackId) {
                state = state.copy(player = state.player.copy(scrubbing = true))
                handler.removeCallbacks(hideControls)
                command("seekPreview", json("seconds" to action.seconds, "videoId" to action.playbackId, "x" to action.x, "top" to action.top))
            }
            is TvAction.SeekTo -> if (state.screen == TvScreen.PLAYER && action.playbackId == state.player.playbackId) {
                state = state.copy(player = state.player.copy(scrubbing = false))
                command("seekTo", json("seconds" to action.seconds, "videoId" to action.playbackId))
                scheduleHide()
            }
            is TvAction.CancelSeek -> if (state.player.scrubbing && action.playbackId == state.player.playbackId) {
                state = state.copy(player = state.player.copy(scrubbing = false))
                command("seekPreviewCancel", json("videoId" to action.playbackId))
                scheduleHide()
            }
            TvAction.SkipIntro -> if (state.screen == TvScreen.PLAYER && state.player.introAvailable) {
                command("skipIntro", json("videoId" to state.player.playbackId))
                state = state.copy(player = state.player.copy(introAvailable = false))
                scheduleHide()
            }
            is TvAction.ControlsLayout -> if (state.screen == TvScreen.PLAYER && action.clearance.isFinite() && controlsClearance != action.clearance) {
                controlsClearance = action.clearance.coerceIn(.1f, .9f)
                syncPlayerControls()
            }
            is TvAction.Subtitles -> {
                state = state.copy(player = state.player.copy(subtitleMode = action.mode))
                preferences.edit().putString("subtitleMode", action.mode.name).apply()
                command("subtitle", json("mode" to action.mode.name.lowercase()))
            }
            is TvAction.SubtitleScale -> {
                val scale = action.scale.coerceIn(0.6f, 1.8f)
                state = state.copy(player = state.player.copy(subtitleScale = scale))
                preferences.edit().putFloat("subtitleScale", scale).apply()
                command("subtitleSize", json("scale" to scale))
            }
            is TvAction.SubtitleBackground -> {
                state = state.copy(player = state.player.copy(subtitleBackground = action.enabled))
                preferences.edit().putBoolean("subtitleBackground", action.enabled).apply()
                command("subtitleBackground", json("enabled" to action.enabled))
            }
            is TvAction.Speed -> {
                state = state.copy(player = state.player.copy(speed = action.value))
                preferences.edit().putFloat("speed", action.value).apply()
                command("speed", json("value" to action.value))
            }
            is TvAction.Quality -> command("quality", json("value" to action.value))
            is TvAction.Server -> command("server", json("value" to action.value))
            TvAction.ShowControls -> { state = state.copy(player = state.player.copy(controlsVisible = true)); scheduleHide() }
            TvAction.CloseControls -> {
                if (state.player.scrubbing) onAction(TvAction.CancelSeek(state.player.playbackId))
                handler.removeCallbacks(hideControls)
                state = state.copy(player = state.player.copy(controlsVisible = false))
            }
            TvAction.Interaction -> scheduleHide()
            is TvAction.PanelVisibility -> {
                playerPanelOpen = action.visible
                syncPlayerControls()
                if (action.visible) handler.removeCallbacks(hideControls) else scheduleHide()
            }
        }
    }

    private fun startPlayer(url: String, position: Double?, fromStart: Boolean = false) {
        if (!isSiteUrl(url)) return
        restartHistoryRequested = fromStart
        pauseOnReady = false
        appliedPlayerPreferences = false
        playbackErrorCanRecover = false
        controlsClearance = 0f
        state = state.copy(screen = TvScreen.PLAYER, selectedUrl = url, loading = true, error = null,
            player = state.player.copy(ready = false, paused = true, currentTime = position ?: 0.0, duration = 0.0, controlsVisible = true,
                title = currentItem?.title ?: state.detail?.item?.title.orEmpty(), error = null,
                playbackId = "", introAvailable = false, scrubbing = false))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mediaSession.isActive = true
        armTimeout()
        command("play", json("url" to url).apply { if (position != null) put("position", position) })
    }

    private fun handleBack() {
        when (state.screen) {
            TvScreen.PLAYER -> {
                if (state.error != null || state.player.error != null) { exitPlayer(); return }
                if (state.player.scrubbing) { onAction(TvAction.CancelSeek(state.player.playbackId)); return }
                if (state.player.controlsVisible) {
                    state = state.copy(player = state.player.copy(controlsVisible = false))
                } else {
                    exitPlayer()
                }
            }
            TvScreen.LOGIN -> {
                command("closeLogin")
                state = state.copy(screen = TvScreen.CATALOG, loginReady = false, error = null)
                loadCatalog()
            }
            TvScreen.DETAIL -> {
                state = state.copy(screen = TvScreen.CATALOG, loading = false, error = null)
                if (state.category == CatalogCategory.CONTINUE) loadCatalog()
            }
            TvScreen.CATALOG -> {
                command("pause")
                moveTaskToBack(true)
            }
        }
    }

    private fun exitPlayer() {
        saveHistory()
        command("closePlayer")
        playerPanelOpen = false
        handler.removeCallbacks(hideControls)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mediaSession.isActive = false
        state = state.copy(screen = TvScreen.DETAIL, loading = false, error = null, player = state.player.copy(scrubbing = false, introAvailable = false),
            detail = state.detail?.let { withResume(it, state.selectedUrl) })
    }

    private fun onSiteEvent(event: JSONObject) {
        val type = event.optString("type")
        if (BuildConfig.DEBUG && type != "player") android.util.Log.d(TAG, "event=$type")
        when (type) {
            "ready" -> {
                adapterReady = true
                // Measured device-specific workaround: this TV renders the 1998px AVC source
                // with a green right edge. This is not a claimed universal codec capability limit.
                command("configure", json("problemVideoSizes" to JSONArray().apply {
                    if (Build.MODEL.equals("MiTV-MOOQ0", true)) put(json("width" to 1998, "height" to 1080))
                }))
                val pending = pendingCommand
                pendingCommand = null
                if (pauseOnReady || state.screen != TvScreen.PLAYER || !foreground) command("closePlayer")
                val allowPending = pending?.first != "play" || (state.screen == TvScreen.PLAYER && foreground && !pauseOnReady)
                pauseOnReady = false
                if (pending != null && allowPending) command(pending.first, pending.second)
                else if (state.screen == TvScreen.CATALOG && state.catalog.isEmpty()) loadCatalog()
                else if (state.screen == TvScreen.LOGIN) command("login")
                command("state")
                syncPlayerControls()
            }
            "catalog" -> {
                if (state.screen != TvScreen.CATALOG ||
                    event.optString("category") != catalogCategoryId() || event.optString("query") != state.query.trim().take(160) ||
                    event.optString("genre", "") != state.genre || event.optString("order", "site_popularity") != state.order) return
                val items = event.optJSONArray("items")?.objects()?.map(::parseCard).orEmpty()
                val page = event.optInt("page", 1)
                catalogPage = page
                val filters = event.optJSONObject("filters")
                if (state.category == CatalogCategory.CONTINUE) {
                    accountHistory = (if (page <= 1) items else accountHistory + items).distinctBy { HistoryPolicy.titleKey(it.url) }
                }
                state = state.copy(catalog = if (state.category == CatalogCategory.CONTINUE) historyItems() else (if (page <= 1) items else state.catalog + items).distinctBy { it.url },
                    hasMore = event.optBoolean("hasMore"), loading = false, error = null, status = "",
                    genreOptions = filters?.optJSONArray("genres")?.objects()?.map(::parsePlaybackOption).orEmpty().let {
                        if (it.isEmpty()) it else listOf(PlaybackOption("", "Все жанры")) + it.filter { option -> option.id.isNotBlank() }
                    },
                    orderOptions = filters?.optJSONArray("orders")?.objects()?.map(::parsePlaybackOption).orEmpty(),
                    total = event.optInt("total"))
                handler.removeCallbacks(loadingTimeout)
            }
            "detail" -> {
                if (state.screen != TvScreen.DETAIL && state.screen != TvScreen.PLAYER) return
                if (event.optString("url").substringBefore('#') != state.selectedUrl.substringBefore('#')) return
                val item = parseCard(event).let { parsed ->
                    val previous = currentItem?.takeIf { HistoryPolicy.titleKey(it.url) == HistoryPolicy.titleKey(parsed.url) }
                    if (parsed.title.isBlank()) previous ?: parsed else parsed.copy(
                        poster = parsed.poster.ifBlank {
                            previous?.poster?.takeIf { it.isNotBlank() && it != parsed.landscape && it != previous?.landscape }.orEmpty()
                        }
                    )
                }
                val episodes = event.optJSONArray("episodes")?.objects()?.mapIndexed { index, o ->
                    EpisodeItem(o.optString("id", index.toString()), o.optString("title"), o.optString("url"), o.optInt("season", 1), o.optInt("number", index + 1))
                }.orEmpty()
                val resume = event.optJSONArray("resumeItems")?.objects()?.mapNotNull { point ->
                    val url = point.optString("url")
                    val position = point.optDouble("position", -1.0)
                    if (!isSiteUrl(url) || HistoryPolicy.titleKey(url) != HistoryPolicy.titleKey(item.url) || !position.isFinite() || position < 0) null
                    else ResumePoint(url, position, point.optLong("updatedAt"), point.optDouble("duration", 0.0))
                }.orEmpty()
                accountResume = accountResume.filter { HistoryPolicy.titleKey(it.url) != HistoryPolicy.titleKey(item.url) } + resume
                val playbackUrl = if (state.screen == TvScreen.DETAIL) {
                    HistoryPolicy.episodePlaybackUrl(HistoryPolicy.resumeUrl(requestedDetailUrl, resumePoints().map { it.url }), episodes)
                } else state.selectedUrl
                currentItem = item
                resolvedDetailUrl = HistoryPolicy.titleKey(item.url)
                state = state.copy(selectedUrl = playbackUrl,
                    detail = withResume(FilmDetail(item, event.optString("description"), event.optString("meta"), episodes,
                    ratings = event.optJSONArray("ratings")?.objects()?.map { RatingItem(it.optString("source"), it.optString("label"), it.optString("value"), it.optString("iconUrl")) }.orEmpty()), playbackUrl),
                    loading = if (state.screen == TvScreen.DETAIL) false else state.loading,
                    error = if (state.screen == TvScreen.DETAIL) null else state.error)
                if (state.screen == TvScreen.DETAIL) handler.removeCallbacks(loadingTimeout)
            }
            "auth" -> {
                val loggedIn = event.optBoolean("loggedIn")
                val wasLogin = state.screen == TvScreen.LOGIN
                state = state.copy(loggedIn = loggedIn, authKnown = true, accountName = event.optString("displayName"))
                if (!loggedIn) { accountHistory = emptyList(); accountResume = emptyList() }
                if (loggedIn && wasLogin) {
                    CookieManager.getInstance().flush()
                    command("closeLogin")
                    web.visibility = View.INVISIBLE
                    state = state.copy(screen = TvScreen.CATALOG, loginReady = false, status = "Вход выполнен", error = null)
                    loadCatalog()
                }
            }
            "login" -> if (state.screen == TvScreen.LOGIN) {
                val ready = event.optBoolean("ready")
                if (!ready) web.visibility = View.INVISIBLE
                state = state.copy(loginReady = ready, status = if (ready) "Отсканируйте QR-код камерой телефона и подтвердите вход в 2SUB." else "Подключаем аккаунт…", loading = false)
                if (ready && BuildConfig.DEBUG) android.util.Log.i(TAG, "LOGIN_QR_READY")
            }
            "player" -> {
                val old = state.player
                val ready = event.optBoolean("ready")
                val paused = event.optBoolean("paused", old.paused)
                if (state.screen == TvScreen.PLAYER && playbackErrorCanRecover && event.optInt("readyState") >= 3 && event.optInt("mediaErrorCode") == 0 && !event.optBoolean("buffering")) {
                    state = state.copy(error = null)
                    playbackErrorCanRecover = false
                }
                val reconcile = appliedPlayerPreferences && ready
                val actualMode = when (event.optString("subtitleMode")) {
                    "both" -> SubtitleMode.BOTH
                    "en" -> SubtitleMode.EN
                    "ru" -> SubtitleMode.RU
                    "off" -> SubtitleMode.OFF
                    else -> old.subtitleMode
                }
                state = state.copy(loading = if (ready && state.screen == TvScreen.PLAYER) false else state.loading,
                    player = old.copy(ready = ready, paused = paused,
                        scrubbing = old.scrubbing && ready && old.playbackId == event.optString("playbackId"),
                        currentTime = event.optDouble("currentTime", old.currentTime).takeIf { it.isFinite() } ?: 0.0,
                        duration = event.optDouble("duration", old.duration).takeIf { it.isFinite() } ?: 0.0,
                        title = event.optString("title").ifBlank { old.title },
                        subtitleMode = if (reconcile) actualMode else old.subtitleMode,
                        subtitleScale = if (reconcile) event.optDouble("subtitleScale", old.subtitleScale.toDouble()).toFloat() else old.subtitleScale,
                        subtitleBackground = if (reconcile) event.optBoolean("subtitleBackground", old.subtitleBackground) else old.subtitleBackground,
                        speed = if (reconcile) event.optDouble("speed", old.speed.toDouble()).toFloat() else old.speed,
                        videoQualityMode = event.optString("qualityMode", old.videoQualityMode),
                        actualQuality = event.optString("actualQuality", old.actualQuality),
                        qualityOptions = event.optJSONArray("qualityOptions")?.objects()?.map(::parsePlaybackOption) ?: old.qualityOptions,
                        serverId = event.optString("serverId", old.serverId),
                        serverOptions = event.optJSONArray("serverOptions")?.objects()?.map(::parsePlaybackOption) ?: old.serverOptions,
                        buffering = event.optBoolean("buffering"),
                        bufferedSeconds = event.optDouble("bufferedSeconds", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        playbackNotice = event.optString("playbackNotice"),
                        playbackId = event.optString("playbackId"),
                        introAvailable = event.optBoolean("introAvailable"),
                        englishAvailable = event.optBoolean("englishAvailable", true),
                        russianAvailable = event.optBoolean("russianAvailable", true)))
                if (ready && state.screen == TvScreen.PLAYER) {
                    if (foreground && !paused) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    handler.removeCallbacks(loadingTimeout)
                    if (!appliedPlayerPreferences) {
                        appliedPlayerPreferences = true
                        command("subtitle", json("mode" to state.player.subtitleMode.name.lowercase()))
                        command("subtitleSize", json("scale" to state.player.subtitleScale))
                        command("subtitleBackground", json("enabled" to state.player.subtitleBackground))
                        command("speed", json("value" to state.player.speed))
                        scheduleHide()
                    }
                    if (!foreground) command("pause")
                    if ((restartHistoryRequested && state.player.currentTime in 0.0..10.0) || (!old.paused && paused) || System.currentTimeMillis() - lastHistoryWrite > 10_000) saveHistory()
                }
                mediaSession.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND)
                    .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, (state.player.currentTime * 1000).toLong(), state.player.speed).build())
                if (BuildConfig.DEBUG && System.currentTimeMillis() - lastPlayerLog > 10_000 && state.screen == TvScreen.PLAYER) {
                    lastPlayerLog = System.currentTimeMillis()
                    android.util.Log.d(TAG, "player ready=$ready paused=$paused position=${state.player.currentTime.toInt()} duration=${state.player.duration.toInt()}")
                }
            }
            "error" -> {
                val context = event.optString("context")
                if (context == "catalog" && state.screen != TvScreen.CATALOG) return
                if (context == "detail" && state.screen != TvScreen.DETAIL) return
                if (context == "login" && state.screen != TvScreen.LOGIN) return
                if (context in setOf("play", "media", "quality", "server", "toggle", "seek", "seekTo", "seekPreview", "seekPreviewCancel", "skipIntro", "subtitle", "subtitleSize", "subtitleBackground", "speed") && state.screen != TvScreen.PLAYER) return
                playbackErrorCanRecover = state.screen == TvScreen.PLAYER && context in setOf("play", "media")
                state = state.copy(loading = false, error = event.optString("message", "Не удалось выполнить действие. Повторите попытку."))
                handler.removeCallbacks(loadingTimeout)
            }
        }
    }

    private fun parseCard(o: JSONObject): CardItem = CardItem(
        id = o.optString("id", o.optString("url")), url = o.optString("url", state.selectedUrl), title = o.optString("title"),
        originalTitle = o.optString("originalTitle"), poster = o.optString("poster"), year = o.optString("year"), kind = o.optString("kind"),
        landscape = o.optString("landscape"), addedLabel = o.optString("addedLabel"), episodeLabel = o.optString("episodeLabel"), updateLabel = o.optString("updateLabel"),
        season = o.optInt("season"), episode = o.optInt("episode"),
        progress = o.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f), remainingLabel = o.optString("remainingLabel")
    )
    private fun parsePlaybackOption(o: JSONObject) = PlaybackOption(o.optString("id"), o.optString("label"), o.optBoolean("enabled", true))
    private fun syncPlayerControls() = command("controls", json(
        "visible" to (state.screen == TvScreen.PLAYER && state.player.controlsVisible),
        "panelOpen" to (state.screen == TvScreen.PLAYER && state.player.controlsVisible && playerPanelOpen),
        "clearance" to controlsClearance
    ))
    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (!playerPanelOpen && !state.player.scrubbing && state.screen == TvScreen.PLAYER && state.player.controlsVisible) handler.postDelayed(hideControls, 7_000)
    }
    private fun armTimeout() { handler.removeCallbacks(loadingTimeout); handler.postDelayed(loadingTimeout, 40_000) }
    private fun history(): JSONArray = runCatching { JSONArray(preferences.getString("history", "[]")) }.getOrDefault(JSONArray())
    private fun resumePoints(): List<ResumePoint> = HistoryPolicy.mergeResume(history().objects().map {
        ResumePoint(it.optString("url"), it.optDouble("position", 0.0), it.optLong("updatedAt"), it.optDouble("duration", 0.0))
    }, accountResume)
    private fun resumePosition(url: String): Double? = resumePoints().firstOrNull { it.url == url }?.position
    private fun historyItems(): List<CardItem> {
        val local = history().objects().map { o ->
            val episode = HistoryPolicy.episode(o.optString("url"))
            parseCard(o).copy(
                progress = if (o.optDouble("duration") > 0) (o.optDouble("position") / o.optDouble("duration")).toFloat().coerceIn(0f, 1f) else 0f,
                positionSeconds = o.optDouble("position", 0.0), durationSeconds = o.optDouble("duration", 0.0),
                season = episode?.first ?: 0, episode = episode?.second ?: 0,
                episodeLabel = episode?.let { "Сезон ${it.first} · Серия ${it.second}" }.orEmpty()
            )
        }
        val points = resumePoints()
        val remote = accountHistory.map { item ->
            val cached = local.firstOrNull { HistoryPolicy.titleKey(it.url) == HistoryPolicy.titleKey(item.url) }
            val point = points.firstOrNull { it.url == item.url }
            item.copy(poster = item.poster.ifBlank { cached?.poster.orEmpty() },
                year = item.year.ifBlank { cached?.year.orEmpty() },
                positionSeconds = point?.position ?: 0.0, durationSeconds = point?.duration ?: 0.0)
        }
        return HistoryPolicy.latestPerTitle(remote + local)
    }
    private fun withResume(detail: FilmDetail, url: String): FilmDetail {
        val numbers = HistoryPolicy.episode(url)
        val episode = if (numbers == null) detail.episodes.firstOrNull() else detail.episodes.firstOrNull {
            it.season == numbers.first && it.number == numbers.second
        }
        return detail.copy(resumeSeconds = resumePosition(url) ?: 0.0, resumeUrl = url,
            resumeSeason = numbers?.first ?: episode?.season ?: 0,
            resumeEpisode = numbers?.second ?: episode?.number ?: 0, resumeTitle = episode?.title.orEmpty())
    }
    private fun saveHistory() {
        val player = state.player
        val confirmedRestart = restartHistoryRequested && player.currentTime in 0.0..10.0
        // A paused, loaded video is valid history; an unfinished startup must not replace it.
        if (state.screen != TvScreen.PLAYER || !player.ready || !isSiteUrl(state.selectedUrl) ||
            !player.currentTime.isFinite() || (player.currentTime < 2 && !confirmedRestart) ||
            !player.duration.isFinite() || player.duration <= 0) return
        val item = currentItem ?: state.detail?.item ?: return
        val previous = history().objects().firstOrNull { it.optString("url") == state.selectedUrl }
        if (!confirmedRestart && previous != null && kotlin.math.abs(previous.optDouble("position") - player.currentTime) < .5 &&
            kotlin.math.abs(previous.optDouble("duration") - player.duration) < 1) {
            lastHistoryWrite = System.currentTimeMillis()
            return
        }
        val record = json("id" to item.id, "url" to state.selectedUrl, "title" to item.title, "originalTitle" to item.originalTitle,
            "poster" to item.poster, "landscape" to item.landscape, "year" to item.year, "kind" to item.kind,
            "position" to player.currentTime.coerceAtMost(player.duration), "duration" to player.duration, "updatedAt" to System.currentTimeMillis())
        val records = JSONArray().put(record)
        history().objects().filter { it.optString("url") != state.selectedUrl }.take(99).forEach { records.put(it) }
        preferences.edit().putString("history", records.toString()).apply()
        if (confirmedRestart) restartHistoryRequested = false
        lastHistoryWrite = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::web.isInitialized && !rendererGone) web.onResume()
        if (::mediaSession.isInitialized) mediaSession.isActive = state.screen == TvScreen.PLAYER
        if (adapterReady && state.screen == TvScreen.CATALOG && state.category == CatalogCategory.CONTINUE) loadCatalog()
        if (state.screen == TvScreen.PLAYER) state = state.copy(player = state.player.copy(controlsVisible = true))
    }
    override fun onPause() {
        foreground = false
        state = state.copy(player = state.player.copy(scrubbing = false))
        if (::web.isInitialized && !rendererGone) { command("pause"); saveHistory(); CookieManager.getInstance().flush(); web.onPause() }
        if (::mediaSession.isInitialized) mediaSession.isActive = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun json(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply { fields.forEach { put(it.first, it.second) } }
    private fun isSiteUrl(url: String): Boolean = runCatching { val u = Uri.parse(url); u.scheme == "https" && u.host == "2sub.movie" && (u.port == -1 || u.port == 443) }.getOrDefault(false)
    companion object { private const val SITE = "https://2sub.movie/"; private const val TAG = "TwoSubTV" }
}
