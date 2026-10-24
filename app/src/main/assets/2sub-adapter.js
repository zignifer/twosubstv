(function () {
  'use strict';
  if (window !== window.top || location.origin !== 'https://2sub.movie') return;
  if (window.TwoSubTV && window.TwoSubTV.version === 1) {
    window.TwoSubTV.command('state', {});
    return;
  }

  var KEY = 'twosub_tv_preferences_v1', PENDING = 'twosub_tv_pending_v1', KNOWN_PROBLEMS = 'twosub_tv_known_problem_video_ids_v1';
  var defaults = { mode: 'both', scale: 1, background: true, speed: 1, qualityMode: 'auto' };
  var preferences = read(localStorage, KEY, defaults);
  preferences = { mode: ['both', 'en', 'ru', 'off'].indexOf(preferences.mode) >= 0 ? preferences.mode : 'both',
    scale: clamp(number(preferences.scale, 1), 0.5, 2), background: preferences.background !== false,
    speed: clamp(number(preferences.speed, 1), 0.5, 2),
    qualityMode: ['auto', 'hd', 'sd'].indexOf(preferences.qualityMode) >= 0 ? preferences.qualityMode : 'auto' };
  var pending = read(sessionStorage, PENDING, null);
  var catalogGeneration = 0, detailGeneration = 0, playerActive = false, pauseLatch = false, navigating = false;
  var catalogMetadata = null, catalogMetadataRequest = null;
  var portraitCache = {}, portraitCacheOrder = [];
  var catalogOrders = [
    { id: 'site_popularity', label: 'Популярные' }, { id: 'add_date', label: 'Недавно добавленные' },
    { id: 'updated', label: 'Недавно обновлённые' }, { id: 'year', label: 'Новые по году' },
    { id: 'year_increase', label: 'Старые по году' }, { id: 'rating_kp', label: 'Рейтинг Кинопоиск' },
    { id: 'rating_imdb', label: 'Рейтинг IMDb' }
  ];
  var startAttempted = false, startDeadline = 0, startHardDeadline = 0, startupNotice = '', resumePending = null;
  var zeroPrepared = false, zeroReadyAt = 0, zeroSeek = null;
  var loginActive = false, loginStarted = false, loginReady = false, loginDeadline = 0;
  var loginObserver = null, loginConfirming = false;
  var appliedPlayer = null, lastPlayer = '', lastAuth = '', lastError = '';
  var deviceConfig = read(sessionStorage, 'twosub_tv_device_v1', { problemVideoSizes: [] });
  var knownProblemVideoIds = read(localStorage, KNOWN_PROBLEMS, []);
  knownProblemVideoIds = Array.isArray(knownProblemVideoIds) ? knownProblemVideoIds.filter(function (entry) {
    return entry && typeof entry.id === 'string' && entry.id.length <= 128 && entry.width > 0 && entry.height > 0;
  }).slice(-50) : [];
  var qualityVideoKey = '', qualityApplied = false, widthGuardAttempted = false, qualityChange = null;
  var playbackNotice = '', observedVideo = null, observedHandlers = null, waitingSince = 0, lastMediaTime = -1, mediaMetadataRevision = 0;

  function read(storage, key, fallback) {
    try { return JSON.parse(storage.getItem(key)) || fallback; } catch (_) { return fallback; }
  }
  function save(storage, key, value) {
    try { value === null ? storage.removeItem(key) : storage.setItem(key, JSON.stringify(value)); } catch (_) {}
  }
  function emit(type, data) {
    try {
      if (window.TwoSubBridge && typeof window.TwoSubBridge.postMessage === 'function')
        window.TwoSubBridge.postMessage(JSON.stringify(Object.assign({ type: type }, data || {})));
    } catch (_) {}
  }
  function error(message, context) {
    var key = context + ':' + message;
    if (key !== lastError) { lastError = key; emit('error', { message: message, context: context }); }
  }
  function text(el) { return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  function attr(root, selector, name) { var el = root.querySelector(selector); return el ? el.getAttribute(name) || '' : ''; }
  function number(value, fallback) { var n = Number(value); return Number.isFinite(n) ? n : fallback; }
  function clamp(value, low, high) { return Math.min(high, Math.max(low, value)); }
  function contentUrl(value) {
    var u = new URL(value || location.href, location.origin);
    if (u.origin !== location.origin || !/^\/(film|series|animation)\/[^/]+\/?$/.test(u.pathname))
      throw new Error('Ожидалась карточка фильма или сериала 2SUB.');
    u.search = '';
    if (!/^#watch(?:-s\d+e\d+)?$/.test(u.hash)) u.hash = '';
    return u.href;
  }
  function cleanImage(value) {
    if (typeof value !== 'string' || !value.trim()) return '';
    try { var u = new URL(value, location.origin); return u.protocol === 'https:' ? u.href : ''; } catch (_) { return ''; }
  }
  function pageUrl(value) { var u = new URL(value); u.hash = ''; return u.href; }
  function kindFor(url) { return url.indexOf('/series/') >= 0 ? 'series' : url.indexOf('/animation/') >= 0 ? 'animation' : 'film'; }
  function rememberPortrait(item) {
    if (!item.poster) return;
    var key = pageUrl(item.url);
    portraitCache[key] = { id: String(item.id), poster: item.poster };
    portraitCacheOrder = portraitCacheOrder.filter(function (url) { return url !== key; });
    portraitCacheOrder.push(key);
    if (portraitCacheOrder.length > 128) delete portraitCache[portraitCacheOrder.shift()];
  }
  function knownPortrait(item) {
    var entry = portraitCache[pageUrl(item.url)];
    return entry && (!/^\d+$/.test(String(item.id)) || entry.id === String(item.id)) ? entry.poster : '';
  }
  function parseRatings(root) {
    var ratings = [], seen = {};
    if (!root) return ratings;
    root.querySelectorAll('.movie-hero-new__rating').forEach(function (badge) {
      var image = badge.querySelector('img'), identity = String(badge.getAttribute('title') || '') + ' ' + (image ? image.getAttribute('alt') || '' : '');
      var source = /кинопоиск/i.test(identity) ? 'kp' : /imdb/i.test(identity) ? 'imdb' : /tmdb/i.test(identity) ? 'tmdb' : '';
      var value = number(text(badge).replace(',', '.'), 0);
      if (!source || seen[source] || value <= 0 || value > 10) return;
      seen[source] = true;
      ratings.push({ source: source, label: { kp: 'Кинопоиск', imdb: 'IMDb', tmdb: 'TMDB' }[source], value: value,
        iconUrl: cleanImage(image ? image.getAttribute('src') || '' : '') });
    });
    return ratings;
  }
  function relativeAddedLabel(timestamp) {
    if (!timestamp || timestamp > Date.now() / 1000 + 300) return '';
    var elapsed = Math.max(0, Math.floor(Date.now() / 1000 - timestamp));
    function plural(value, forms) { var mod = value % 100; return forms[mod >= 11 && mod <= 14 ? 2 : value % 10 === 1 ? 0 : value % 10 >= 2 && value % 10 <= 4 ? 1 : 2]; }
    if (elapsed < 60) return 'Только что';
    var count = Math.floor(elapsed / 60), forms = ['минуту', 'минуты', 'минут'];
    if (elapsed >= 86400) { count = Math.floor(elapsed / 86400); forms = ['день', 'дня', 'дней']; }
    else if (elapsed >= 3600) { count = Math.floor(elapsed / 3600); forms = ['час', 'часа', 'часов']; }
    return count + ' ' + plural(count, forms) + ' назад';
  }
  function parseCards(doc) {
    var seen = {}, items = [];
    doc.querySelectorAll('a[data-film-list-card-id], a.js-film-list-card-link, a.vertical-card[href], a.horizontal-card[href]').forEach(function (card) {
      try {
        var url = contentUrl(card.getAttribute('href'));
        if (seen[url]) return;
        var title = text(card.querySelector('.card-content__title:not(.card-content__subtitle)'));
        var image = card.querySelector('.vertical-card__img,.horizontal-card__img');
        if (!title && image) title = image.getAttribute('alt') || '';
        if (!title) return;
        seen[url] = true;
        var meta = text(card.querySelector('h4.card-content__subtitle'));
        var year = (meta.match(/\b(?:19|20)\d{2}(?:\s*[-–]\s*(?:19|20)\d{2})?/) || [''])[0];
        var addedAt = number(attr(card, '.from-now[data-time]', 'data-time'), 0);
        var updateChip = card.querySelector('.update-chip');
        var episodeLabel = updateChip ? String(updateChip.getAttribute('title') || '').trim() || text(updateChip) : text(card.querySelector('.update-chip__text'));
        var requested = requestedEpisode(url);
        var updateSeason = (episodeLabel.match(/^(\d+)\s+сезон\s*·/) || [])[1];
        var updateEpisode = (episodeLabel.match(/(?:^|[·\s])серия\s+(\d+)$/i) || [])[1];
        var landscape = card.querySelector('.horizontal-card__img');
        var item = { id: card.getAttribute('data-film-list-card-id') || attr(card, '[data-filmid]', 'data-filmid') || url,
          url: url, title: title, originalTitle: text(card.querySelector('.card-content__title.card-content__subtitle')),
          poster: cleanImage(image ? image.getAttribute('src') || image.getAttribute('data-src') || '' : ''),
          landscape: cleanImage(landscape ? landscape.getAttribute('src') || landscape.getAttribute('data-src') || '' : ''),
          year: year, kind: kindFor(url), ratings: parseRatings(card), addedAt: addedAt,
          addedLabel: relativeAddedLabel(addedAt), episodeLabel: episodeLabel,
          season: requested ? requested.season : number(updateSeason, 0), episode: requested ? requested.number : number(updateEpisode, 0) };
        items.push(item);
        if (image && image.classList.contains('vertical-card__img')) rememberPortrait(item);
      } catch (_) {}
    });
    return items;
  }
  async function request(url, format, timeoutMs) {
    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = setTimeout(function () { if (controller) controller.abort(); }, timeoutMs || 20000);
    try {
      var response = await fetch(url, { credentials: 'include', signal: controller ? controller.signal : undefined });
      if (!response.ok) throw new Error('Сайт ответил HTTP ' + response.status + '.');
      return format === 'json' ? await response.json() : await response.text();
    } finally { clearTimeout(timer); }
  }
  function genresFromDocument(doc) {
    var scripts = doc.querySelectorAll('script:not([src])');
    for (var i = 0; i < scripts.length; i++) {
      var match = (scripts[i].textContent || '').match(/window\.yoGenresConfig\s*=\s*(\{[^;]+\});/);
      if (match) { try { return JSON.parse(match[1]); } catch (_) {} }
    }
    return null;
  }
  async function loadCatalogMetadata() {
    if (catalogMetadata) return catalogMetadata;
    if (window.yoGenresConfig) { catalogMetadata = window.yoGenresConfig; return catalogMetadata; }
    if (!catalogMetadataRequest) catalogMetadataRequest = request('/catalog/', 'text').then(function (html) {
      catalogMetadata = genresFromDocument(new DOMParser().parseFromString(html, 'text/html'));
      return catalogMetadata;
    }).catch(function () { return null; }).finally(function () { catalogMetadataRequest = null; });
    return catalogMetadataRequest;
  }
  function filtersFor(metadata, type) {
    var list = metadata && metadata.genresByType && metadata.genresByType[type] || metadata && metadata.genres || [];
    return { genres: list.filter(function (g) { return g && /^[a-z_]+$/.test(g.id) && typeof g.name === 'string'; }).map(function (g) { return { id: g.id, label: g.name }; }), orders: catalogOrders };
  }
  async function catalog(data) {
    var generation = ++catalogGeneration;
    var query = String(data.query || '').trim().slice(0, 160), page = clamp(number(data.page, 1), 1, 10000);
    var category = String(data.category || 'all').toLowerCase(), params = new URLSearchParams();
    var genre = String(data.genre || ''), order = String(data.order || 'site_popularity');
    if (!/^[a-z_]*$/.test(genre)) genre = '';
    if (!catalogOrders.some(function (option) { return option.id === order; })) order = 'site_popularity';
    var type = { films: 'movie', film: 'movie', series: 'series', animation: 'animation', cartoons: 'animation' }[category];
    var filter = new URLSearchParams();
    if (type) filter.set('type', type);
    if (genre) filter.set('genres', genre);
    filter.set('order', order);
    params.set('page', String(page));
    if (query) { params.set('content_type', 'search'); params.set('query', query); }
    else if (category === 'new') { params.set('content_type', 'list'); params.set('list_name', 'newest'); }
    else {
      params.set('content_type', 'list'); params.set('list_name', 'by_filter');
      params.set('filter_q', filter.toString());
    }
    try {
      var results = await Promise.all([request('/wp-json/yo/v1/filmlist?' + params.toString(), 'json'), category === 'new' ? Promise.resolve(null) : loadCatalogMetadata()]);
      var result = results[0], filters = category === 'new' || query ? { genres: [], orders: [] } : filtersFor(results[1], type);
      if (generation !== catalogGeneration) return;
      var context = { page: page, category: category, query: query, genre: genre, order: order, total: typeof result.total === 'number' ? result.total : null, filters: filters };
      if (result.error === 'all_done') { emit('catalog', Object.assign(context, { items: [], hasMore: false })); return; }
      if (result.error || typeof result.data !== 'string') throw new Error('Не удалось прочитать ответ каталога 2SUB.');
      var items = parseCards(new DOMParser().parseFromString(result.data, 'text/html'));
      var more = result.rest === true || result.rest === 'yes' || result.rest === 'true' || number(result.rest, 0) > 0;
      emit('catalog', Object.assign(context, { items: items, hasMore: items.length > 0 && more }));
    } catch (e) { if (generation === catalogGeneration) error(e.name === 'AbortError' ? 'Каталог не ответил. Проверьте сеть и повторите.' : e.message, 'catalog'); }
  }
  function parseDetail(doc, url) {
    var mainTitle = text(doc.querySelector('h1'));
    var originalTitle = attr(doc, '.movie-hero-new__title-img', 'alt');
    var metaItems = [], releaseYear = '';
    doc.querySelectorAll('.movie-hero-new__meta-list > .movie-hero-new__meta-item').forEach(function (item) {
      if (item.querySelector('.movie-hero-new__level') || item.getAttribute('title') === 'Субтитры') return;
      var value = text(item);
      if (!value) return;
      if (/^(?:19|20)\d{2}(?:\s*[-–]\s*(?:19|20)\d{2})?$/.test(value)) { releaseYear = value.replace(/\s*[-–]\s*/, '–'); return; }
      if (metaItems.indexOf(value) < 0) metaItems.push(value);
    });
    var meta = (releaseYear ? [releaseYear] : []).concat(metaItems).join(' · ');
    var description = text(doc.querySelector('.movie-about__descriptions')) || text(doc.querySelector('.hero__slide-sub-title-description')) || attr(doc, 'meta[name="description"]', 'content');
    // Hero/OG images are landscape art. Only an exact title's vertical card is a poster;
    // otherwise native retains the portrait supplied by the catalog that opened this detail.
    var poster = '';
    doc.querySelectorAll('a[href] .vertical-card__img').forEach(function (image) {
      if (poster) return;
      try {
        var card = image.closest('a[href]');
        if (card && pageUrl(contentUrl(card.getAttribute('href'))) === pageUrl(url))
          poster = image.getAttribute('src') || image.getAttribute('data-src') || '';
      } catch (_) {}
    });
    var id = attr(doc, '.movie-hero-new__action[data-filmid]', 'data-filmid');
    var episodes = [];
    // This JSON literal is part of the site's SSR VideosInfo data; never eval page scripts.
    var scripts = doc.querySelectorAll('script:not([src])');
    for (var i = 0; i < scripts.length; i++) {
      var source = scripts[i].textContent || '';
      if (source.indexOf('class VideosInfo') < 0) continue;
      var match = source.match(/this\.data\s*=\s*(\[[\s\S]*?\]);/);
      if (match) {
        try {
          var list = JSON.parse(match[1]);
          if (Array.isArray(list)) episodes = list.filter(function (ep) { return ep.is_published && Number(ep.season_number) > 0; }).map(function (ep) {
            var season = Number(ep.season_number), num = Number(ep.episode_number);
            return { id: String(ep.id), title: ep.displayTitle || ep.ruTitle || ep.title || ('Серия ' + num),
              season: season, number: num, url: pageUrl(url) + '#watch-s' + season + 'e' + num };
          });
        } catch (_) {}
      }
      break;
    }
    if (!mainTitle) throw new Error('Карточка 2SUB изменилась: название не найдено.');
    return { url: pageUrl(url), id: id || pageUrl(url), title: mainTitle, originalTitle: originalTitle, description: description,
      poster: cleanImage(poster), landscape: cleanImage(attr(doc, '.movie-hero-new__img-bg', 'src')), ratings: parseRatings(doc.querySelector('.movie-hero-new__ratings')),
      meta: meta, year: releaseYear, kind: kindFor(url), episodes: episodes };
  }
  async function resolveDetailPortrait(item) {
    if (item.poster) { rememberPortrait(item); return item; }
    item.poster = knownPortrait(item);
    if (item.poster) return item;
    var query = String(item.originalTitle || item.title || '').trim().slice(0, 160);
    if (!query) return item;
    try {
      // One ordinary site search; only the same canonical title and numeric ID may supply art.
      var params = new URLSearchParams({ content_type: 'search', query: query, page: '1' });
      var result = await request('/wp-json/yo/v1/filmlist?' + params.toString(), 'json', 8000);
      if (!result.error && typeof result.data === 'string') {
        parseCards(new DOMParser().parseFromString(result.data, 'text/html'));
        item.poster = knownPortrait(item);
      }
    } catch (_) { /* Missing artwork must not fail an otherwise usable detail page. */ }
    return item;
  }
  async function detail(data) {
    var generation = ++detailGeneration;
    try {
      var url = contentUrl(data.url);
      var doc = pageUrl(location.href) === pageUrl(url) ? document : new DOMParser().parseFromString(await request(pageUrl(url), 'text'), 'text/html');
      if (generation !== detailGeneration) return;
      var item = await resolveDetailPortrait(parseDetail(doc, url));
      if (generation === detailGeneration) emit('detail', item);
    } catch (e) { if (generation === detailGeneration) error(e.name === 'AbortError' ? 'Карточка не ответила. Попробуйте ещё раз.' : e.message, 'detail'); }
  }
  function combo() { return window.yoComboPlayer || null; }
  function originalPlayer() {
    var c = combo();
    try { return c && typeof c.yoPlayer === 'function' ? c.yoPlayer() : window.yoPlayer || null; } catch (_) { return null; }
  }
  function video() {
    var p = originalPlayer();
    try { if (p && p.player && p.player.el()) return p.player.el().querySelector('video'); } catch (_) {}
    return document.querySelector('#layout-dialog video:not(#yo-roll), video.vjs-tech:not(#yo-roll)');
  }
  function subtitleSettings() {
    var c = combo(), p = originalPlayer();
    return (c && c.state && c.state.config && c.state.config.subtitleSettings) || (p && p.props && p.props.config && p.props.config.subtitleSettings) || {};
  }
  function playerConfig() {
    var c = combo();
    return c && ((c.state && c.state.config) || c.config) || {};
  }
  function currentPlaybackVideo() {
    var c = combo(), p = originalPlayer();
    return (c && c.config && c.config.currentVideo) || (p && p.currentVideo) || playerConfig().currentVideo || {};
  }
  function requestedEpisode(url) {
    var match = String(url || '').match(/#watch-s(\d+)e(\d+)$/i);
    return match ? { season: Number(match[1]), number: Number(match[2]) } : null;
  }
  function matchesEpisode(current, requested) {
    return !!(current && current.id && Number(current.season_number) === requested.season && Number(current.episode_number) === requested.number &&
      (!requested.id || String(current.id) === String(requested.id)));
  }
  function pendingEpisodeReady() {
    var requested = pending && pending.kind === 'play' && requestedEpisode(pending.url);
    if (!requested) return true;
    requested.id = pending.episodeId;
    return !!requested.id && matchesEpisode(currentPlaybackVideo(), requested);
  }
  function seekPlayback(seconds, reason, resumeIntent) {
    var p = originalPlayer(), options = {};
    if (typeof resumeIntent === 'boolean') options.resumePlaybackIntent = resumeIntent;
    // This path cancels the site's old resume lock and restarts HLS loading at the target.
    // Assigning HTMLVideoElement.currentTime bypasses those network-seek operations.
    if (p && typeof p.seekTo === 'function') return p.seekTo(seconds, reason, options) !== false;
    if (p && p.player && typeof p.player.yoPerformNetworkSeek === 'function')
      return p.player.yoPerformNetworkSeek(seconds, Object.assign({ reason: reason }, options)) !== false;
    if (p && typeof p.setCurrentTime === 'function') { p.setCurrentTime(seconds); return true; }
    return false;
  }
  function availableQualities(config) {
    var current = config.currentVideo, ids = [];
    try {
      if (current && typeof current.getAvailableQualitiesForUI === 'function') ids = current.getAvailableQualitiesForUI(config);
      else if (Array.isArray(config.qualityAvailable)) ids = config.qualityAvailable;
    } catch (_) {}
    return Array.isArray(ids) ? ids.filter(function (id) { return ['hls', 'hd', 'hlssd', 'sd', 'ld'].indexOf(id) >= 0; }) : [];
  }
  function preferredSource(ids, mode) {
    var candidates = mode === 'sd' ? ['hlssd', 'sd'] : ['hls', 'hd'];
    for (var i = 0; i < candidates.length; i++) if (ids.indexOf(candidates[i]) >= 0) return candidates[i];
    return null;
  }
  function cachedProblemVideo(id, sizes) {
    return knownProblemVideoIds.some(function (entry) {
      return entry.id === id && sizes.some(function (size) { return size.width === entry.width && size.height === entry.height; });
    });
  }
  function rememberProblemVideo(id, width, height) {
    if (!id || id.length > 128) return;
    knownProblemVideoIds = knownProblemVideoIds.filter(function (entry) { return entry.id !== id; });
    knownProblemVideoIds.push({ id: id, width: width, height: height });
    knownProblemVideoIds = knownProblemVideoIds.slice(-50);
    save(localStorage, KNOWN_PROBLEMS, knownProblemVideoIds);
  }
  function switchQuality(source) {
    var c = combo(), config = playerConfig();
    if (!c || typeof c.onQualityChange !== 'function' || availableQualities(config).indexOf(source) < 0) return false;
    if (config.quality === source && !qualityChange) return true;
    qualityChange = { source: source, at: Date.now(), revision: mediaMetadataRevision, video: video() };
    // Exact source for this playback; our Auto/HD/SD preference is stored separately.
    c.onQualityChange(source, { persistPreference: false });
    return true;
  }
  function watchMedia(v) {
    if (observedVideo === v) return;
    if (observedVideo && observedHandlers) Object.keys(observedHandlers).forEach(function (name) { observedVideo.removeEventListener(name, observedHandlers[name]); });
    observedVideo = v; waitingSince = 0; lastMediaTime = -1;
    if (!v) return;
    var waiting = function () { if (!v.paused && !waitingSince) waitingSince = Date.now(); };
    var progressing = function () { waitingSince = 0; };
    observedHandlers = { waiting: waiting, stalled: waiting, playing: progressing, pause: progressing, ended: progressing,
      loadedmetadata: function () { mediaMetadataRevision += 1; },
      timeupdate: function () {
        if (!v.paused && Math.abs(v.currentTime - lastMediaTime) > 0.05) waitingSince = 0;
        lastMediaTime = v.currentTime;
      }, error: function () {
        var code = v.error && v.error.code;
        if (code === 2 || code === 3 || code === 4) error(code === 2 ? 'Не удалось загрузить видео. Проверьте сеть или выберите другой сервер.' : 'Этот источник видео не воспроизводится. Выберите SD или другой сервер.', 'media');
      } };
    Object.keys(observedHandlers).forEach(function (name) { v.addEventListener(name, observedHandlers[name]); });
  }
  function updateQualityPolicy(explicitSelection) {
    if (!playerActive || !pendingEpisodeReady()) return;
    var v = video(), config = playerConfig(), current = config.currentVideo || {}, ids = availableQualities(config);
    // The site's bookmark protection treats the first 30 seconds as accidental zero.
    if (zeroSeek && zeroSeek.confirmed && String(current.id) === zeroSeek.id) {
      if (v && v.currentTime > 30) zeroSeek = null;
      else if (explicitSelection !== true) return;
    }
    watchMedia(v);
    var key = String(current.id || '');
    if (key && qualityVideoKey !== key) {
      qualityVideoKey = key; qualityApplied = false; widthGuardAttempted = false; qualityChange = null; playbackNotice = '';
    }
    if (!key || !ids.length || !originalPlayer()) return;
    if (qualityChange) {
      if (!v) return;
      var selected = qualityChange.domain ? config.domain === qualityChange.domain : config.quality === qualityChange.source;
      var loaded = mediaMetadataRevision > qualityChange.revision || v !== qualityChange.video || Date.now() - qualityChange.at >= 5000;
      if (selected && loaded && v.readyState >= 2) qualityChange = null;
      else if (Date.now() - qualityChange.at > 40000) { qualityChange = null; playbackNotice = 'Источник долго загружается. Выберите другое качество или сервер.'; }
      else return;
    }
    var sizes = Array.isArray(deviceConfig.problemVideoSizes) ? deviceConfig.problemVideoSizes : [];
    var cachedProblem = cachedProblemVideo(key, sizes);
    var observedProblem = !!(v && v.readyState >= 1 && sizes.some(function (size) { return size.width === v.videoWidth && size.height === v.videoHeight; }));
    // Native supplies only video sizes empirically confirmed problematic on this TV model.
    if (preferences.qualityMode === 'auto' && (observedProblem || cachedProblem) && !widthGuardAttempted) {
      var lower = preferredSource(ids, 'sd');
      if (observedProblem) rememberProblemVideo(key, v.videoWidth, v.videoHeight);
      widthGuardAttempted = true; qualityApplied = true;
      if (lower && switchQuality(lower)) playbackNotice = 'Для этого видео включено SD: HD отображается с артефактами на этом ТВ.';
      else playbackNotice = 'HD этого видео может отображаться с артефактами на этом ТВ. SD на выбранном сервере недоступно.';
      return;
    }
    // Do not introduce a quality reload while the original source/seek is still settling.
    // Explicit selection and the empirical SD guard can recover a source before readiness.
    var p = originalPlayer(), c = combo();
    if (explicitSelection !== true && (!v || v.readyState < 3 || v.seeking || pending || (p && p.isSettingSrc) || (c && c.isChangingPlayerData))) return;
    if (!qualityApplied) {
      var target = preferredSource(ids, preferences.qualityMode === 'sd' || (preferences.qualityMode === 'auto' && widthGuardAttempted) ? 'sd' : 'hd');
      if (!target && preferences.qualityMode === 'auto' && !widthGuardAttempted && ids.indexOf('ld') >= 0) target = 'ld';
      qualityApplied = true;
      if (target && config.quality !== target) switchQuality(target);
    }
  }
  function videoOptions(config, v) {
    var ids = availableQualities(config), servers = Array.isArray(config.videoDomains) ? config.videoDomains : [];
    var buffered = 0;
    if (v) {
      try { for (var i = 0; i < v.buffered.length; i++) if (v.currentTime >= v.buffered.start(i) && v.currentTime <= v.buffered.end(i)) { buffered = Math.max(0, v.buffered.end(i) - v.currentTime); break; } } catch (_) {}
      if (v.paused || v.ended) waitingSince = 0;
      else if (v.readyState < 3 && !waitingSince) waitingSince = Date.now();
    }
    var buffering = !!(v && !v.paused && !v.ended && (v.readyState < 3 || waitingSince));
    var waitMs = buffering && waitingSince ? Date.now() - waitingSince : 0;
    var notice = waitMs >= 40000 ? 'Видео долго буферизуется. Откройте «Видео» и выберите SD или другой сервер.' :
      waitMs >= 10000 ? 'Буферизация затянулась. Можно выбрать SD или другой сервер в меню «Видео».' : startupNotice || playbackNotice;
    var label = config.quality === 'ld' ? 'LD' : ['sd', 'hlssd'].indexOf(config.quality) >= 0 ? 'SD' : config.quality ? 'HD' : '';
    if (v && v.videoWidth > 0 && v.videoHeight > 0) label += (label ? ' · ' : '') + v.videoWidth + ' × ' + v.videoHeight;
    return { qualityMode: preferences.qualityMode, actualQuality: label,
      qualityOptions: [{ id: 'auto', label: 'Авто', enabled: ids.length > 0 }, { id: 'hd', label: 'HD', enabled: !!preferredSource(ids, 'hd') }, { id: 'sd', label: 'SD', enabled: !!preferredSource(ids, 'sd') }],
      serverId: String(config.domain || ''), serverOptions: servers.filter(function (d) { return d && typeof d.key === 'string' && d.key.length <= 100; }).map(function (d) {
        return { id: d.key, label: String(d.name || d.key).slice(0, 120), enabled: d.paidOnly !== true || window.g_payed === true };
      }), buffering: buffering, bufferedSeconds: Math.round(buffered * 10) / 10, playbackNotice: notice };
  }
  function savePreferences() { save(localStorage, KEY, preferences); }
  function updateSettings(changes) {
    var c = combo(), p = originalPlayer();
    if (c && typeof c.onSubtitleSettingsChange === 'function') { c.onSubtitleSettingsChange(changes); return true; }
    if (p && p.props && typeof p.props.onSubtitleSettingsChange === 'function') { p.props.onSubtitleSettingsChange(changes); return true; }
    return false;
  }
  function applyPreferences() {
    var p = originalPlayer();
    if (!p) return false;
    var lang = { both: 'double', en: 'en', ru: 'ru', off: '' }[preferences.mode];
    if (preferences.mode === 'en' && typeof p.getOrigTrackId === 'function') lang = p.getOrigTrackId() || 'en';
    if (typeof p.setActiveSubtitleTrack === 'function') p.setActiveSubtitleTrack(lang);
    else if (!updateSettings({ language: lang, preferredTextTrack: lang })) return false;
    // The site's control has a 0..100 range and a default of 50.
    updateSettings({ textSize: clamp(Math.round(50 * preferences.scale), 0, 100),
      backgroundTransparencyEn: preferences.background ? 0.55 : 0,
      backgroundTransparencyRu: preferences.background ? 0.55 : 0 });
    if (typeof p.setPlaybackSpeed === 'function') p.setPlaybackSpeed(preferences.speed);
    else if (video()) video().playbackRate = preferences.speed;
    appliedPlayer = p;
    return true;
  }
  function installStyle() {
    if (document.getElementById('twosub-tv-style')) return;
    var style = document.createElement('style'); style.id = 'twosub-tv-style';
    style.textContent =
      'html.twosub-tv-player,html.twosub-tv-player body{margin:0!important;overflow:hidden!important;background:#000!important;height:var(--twosub-viewport-height)!important;min-height:var(--twosub-viewport-height)!important;max-height:var(--twosub-viewport-height)!important}' +
      'html.twosub-tv-player #layout-dialog{position:fixed!important;inset:0!important;width:100vw!important;max-width:none!important;margin:0!important;padding:0!important}' +
      'html.twosub-tv-player #layout-dialog,html.twosub-tv-player #layout-dialog .layout-dialog__left-side,html.twosub-tv-player #layout-dialog .video-js,html.twosub-tv-player #media-child,html.twosub-tv-player #video,html.twosub-tv-player #playerjs_container,html.twosub-tv-player #playerContainer{height:var(--twosub-viewport-height)!important;min-height:var(--twosub-viewport-height)!important;max-height:var(--twosub-viewport-height)!important;box-sizing:border-box!important}' +
      'html.twosub-tv-player #layout-dialog .layout-dialog__right-side,html.twosub-tv-player #layout-dialog .layout-dialog__resize-zone{display:none!important}' +
      'html.twosub-tv-player #layout-dialog .layout-dialog__left-side{width:100%!important;max-width:100%!important;flex:1!important}' +
      'html.twosub-tv-player #layout-dialog .video-js{width:100%!important;padding-top:0!important;aspect-ratio:auto!important}' +
      'html.twosub-tv-player #layout-dialog .vjs-control-bar,html.twosub-tv-player #layout-dialog .yo-player-header,html.twosub-tv-player #layout-dialog .vjs-big-play-button{opacity:0!important;pointer-events:none!important}' +
      'html.twosub-tv-player.twosub-tv-controls #theSubtitle{top:auto!important;bottom:var(--twosub-control-clearance)!important}' +
      'html.twosub-tv-player.twosub-tv-controls.twosub-tv-panel #theSubtitle{bottom:var(--twosub-panel-clearance)!important}' +
      'html.twosub-tv-player .yo-film-logo-overlay,html.twosub-tv-player .yo-pause-overlay,html.twosub-tv-player .subtitle__actions{display:none!important}' +
      'html.twosub-tv-player .subtitle-resize-handle,html.twosub-tv-player .subtitle-settings-gear{display:none!important}' +
      'html.twosub-tv-player #theSubtitle,html.twosub-tv-player #theSubtitle *,html.twosub-tv-player .en-subtitle,html.twosub-tv-player .en-subtitle *,html.twosub-tv-player .ru-subtitle,html.twosub-tv-player .ru-subtitle *{font-family:Roboto,"Noto Sans",Arial,sans-serif!important;font-weight:500!important}' +
      'html.twosub-tv-login .easy-modal.open{padding-top:76px!important;box-sizing:border-box!important}' +
      'html.twosub-tv-login .tv-pairing__qr canvas{max-width:240px!important;max-height:240px!important}';
    (document.head || document.documentElement).appendChild(style);
  }
  function updateViewport() {
    // Android TV WebView can report a correct innerHeight while vh resolves to zero.
    var height = Math.round(number(window.innerHeight, 0) || document.documentElement.clientHeight);
    if (height > 0) {
      document.documentElement.style.setProperty('--twosub-viewport-height', height + 'px');
      document.documentElement.style.setProperty('--twosub-control-clearance', Math.round(clamp(height * 0.25, 100, 180)) + 'px');
      document.documentElement.style.setProperty('--twosub-panel-clearance', Math.round(height * 0.5) + 'px');
    }
  }
  function setPlayerActive(active) {
    playerActive = active;
    updateViewport();
    document.documentElement.classList.toggle('twosub-tv-player', active);
    if (!active) document.documentElement.classList.remove('twosub-tv-controls', 'twosub-tv-panel');
  }
  function stop(clear) {
    pauseLatch = true; navigating = false;
    pending = null; save(sessionStorage, PENDING, null); resumePending = null; startDeadline = 0; startHardDeadline = 0; startupNotice = '';
    zeroPrepared = false; zeroReadyAt = 0; zeroSeek = null;
    var p = originalPlayer(), v = video();
    try { if (p && typeof p.pause === 'function') p.pause(); else if (v) v.pause(); } catch (_) {}
    document.querySelectorAll('video').forEach(function (el) { try { el.pause(); } catch (_) {} });
    if (clear) setPlayerActive(false);
  }
  function beginPlay(data) {
    var url = contentUrl(data.url || location.href), current = pageUrl(location.href);
    var requestedPosition = data.position === undefined || data.position === null ? null : Math.max(0, number(data.position, 0));
    loginActive = false; document.documentElement.classList.remove('twosub-tv-login');
    var v = video(), requested = requestedEpisode(url), correctEpisode = !requested || matchesEpisode(currentPlaybackVideo(), requested);
    if (current === pageUrl(url) && location.hash === new URL(url).hash && correctEpisode && playerActive && v && v.readyState > 0) {
      // Duplicate command delivery must not reset progress or resume a deliberate pause.
      emitPlayer(true); return;
    }
    stop(false);
    var reuseReadyPlayer = current === pageUrl(url) && correctEpisode && v && v.readyState > 0 && (!requested || location.hash === new URL(url).hash);
    if (reuseReadyPlayer && requestedPosition !== 0) {
      pauseLatch = false; setPlayerActive(true); applyPreferences();
      if (requestedPosition !== null && !seekPlayback(clamp(requestedPosition, 0, Math.max(0, v.duration - 1)), 'tv-resume', true))
        throw new Error('Плеер пока не готов к восстановлению позиции. Повторите запуск.');
      var livePlayer = originalPlayer();
      var resumed = livePlayer && typeof livePlayer.play === 'function' ? livePlayer.play() : v.play();
      if (resumed && resumed.catch) resumed.catch(function () { error('Не удалось продолжить видео.', 'play'); });
      return emitPlayer(true);
    }
    pending = { kind: 'play', url: url, position: requestedPosition, createdAt: Date.now() };
    if (reuseReadyPlayer && requested) pending.episodeId = String(currentPlaybackVideo().id);
    save(sessionStorage, PENDING, pending);
    if (current !== pageUrl(url) || (/^#watch-s\d+e\d+$/.test(new URL(url).hash) && location.hash !== new URL(url).hash)) {
      // Neither visibilitychange nor a late tick may consume the next page's intent.
      navigating = true; setPlayerActive(false);
      try { location.assign(url); if (current === pageUrl(url)) location.reload(); }
      catch (e) { stop(true); throw e; }
      return;
    }
    pauseLatch = false; setPlayerActive(true); startAttempted = !!reuseReadyPlayer; startDeadline = Date.now() + 45000; startHardDeadline = Date.now() + 120000;
    tryStart();
  }
  function tryStart() {
    if (!pending || pending.kind !== 'play' || !playerActive || navigating) return;
    if (!startDeadline) { startDeadline = Date.now() + 45000; startHardDeadline = Date.now() + 120000; }
    var fpp = window.filmPagePlayer, requested = requestedEpisode(pending.url);
    if (!startAttempted && fpp && typeof fpp.play === 'function') {
      try {
        if (requested) {
          // The site may replace #watch with #season and restore a different episode.
          // Resolve our saved URL, then use the exact path used by its episode cards.
          if (typeof fpp.resolveInitialSeriesVideo === 'function' && typeof fpp.setCurrentVideoEpisode === 'function') {
            var target = fpp.resolveInitialSeriesVideo(pending.url);
            if (matchesEpisode(target, requested)) {
              pending.episodeId = String(target.id); save(sessionStorage, PENDING, pending);
              startAttempted = true;
              fpp.setCurrentVideoEpisode(target, true, false, 'episode_card');
            }
          }
        } else { startAttempted = true; fpp.play(true); }
      } catch (_) { error('Плеер сайта не удалось открыть.', 'play'); }
    }
    var p = originalPlayer(), v = video();
    var correctEpisode = pendingEpisodeReady();
    if (!correctEpisode && v && !v.paused) { try { v.pause(); } catch (_) {} }
    if (correctEpisode && p && appliedPlayer !== p) applyPreferences();
    if (correctEpisode && v && v.readyState >= 1 && number(v.duration, 0) > 0) {
      var zero = pending.position === 0;
      if (zero) {
        // Finish any initial quality/source reload BEFORE the final zero seek.
        if (!zeroPrepared) { zeroPrepared = true; updateQualityPolicy(true); }
        var c = combo(), settled = v.readyState >= 3 && !v.seeking && !qualityChange && !(p && p.isSettingSrc) && !(c && c.isChangingPlayerData);
        if (!settled) zeroReadyAt = 0;
        else if (!zeroReadyAt) zeroReadyAt = Date.now();
        if (!zeroSeek && !v.paused) v.pause();
        if (settled && Date.now() - zeroReadyAt >= 500) {
          if (!zeroSeek) {
            if (seekPlayback(0, 'tv-resume', true)) zeroSeek = { id: String(currentPlaybackVideo().id), at: Date.now(), retries: 0, confirmed: false };
          } else if (Date.now() > zeroSeek.at && v.currentTime >= 0 && v.currentTime < 10) zeroSeek.confirmed = true;
          else if (Date.now() - zeroSeek.at >= 2000 && zeroSeek.retries === 0) {
            // One correction for a late source restore; never a repeating seek loop.
            if (seekPlayback(0, 'tv-resume', true)) { zeroSeek.retries = 1; zeroSeek.at = Date.now(); zeroReadyAt = 0; }
          }
        }
        resumePending = zeroSeek && zeroSeek.confirmed ? null : 0;
      } else if (resumePending === null && pending.position !== undefined && pending.position !== null) resumePending = Math.max(0, number(pending.position, 0));
      if (!zero && resumePending !== null) {
        var target = clamp(resumePending, 0, Math.max(0, v.duration - 1));
        try { if (seekPlayback(target, 'tv-resume', true)) resumePending = null; } catch (_) {}
      }
      if (resumePending === null) {
        startupNotice = ''; startDeadline = 0; startHardDeadline = 0;
        pending = null; save(sessionStorage, PENDING, null);
        // An explicit native Watch/Episode action should also start a saved paused episode.
        // A user pause or navigation cancels pending before this branch can run.
        if (!pauseLatch && v.paused) {
          var started = p && typeof p.play === 'function' ? p.play() : v.play();
          if (started && started.catch) started.catch(function () { error('Нажмите «Смотреть», чтобы продолжить видео.', 'play'); });
        }
        emitPlayer(true); return;
      }
    }
    if (Date.now() > startHardDeadline) {
      // Stop late autoplay before presenting a recoverable Retry error.
      stop(false);
      playbackNotice = 'Видео не запустилось. Выберите другое качество или сервер и повторите.';
      error('Видео не запустилось за две минуты. Выберите другое качество или сервер и повторите.', 'play');
    } else if (Date.now() > startDeadline) {
      startupNotice = 'Первый запуск занимает больше времени. Ожидаем видео; можно выбрать SD или другой сервер в меню «Видео».';
      // Keep pending.position and resumePending until metadata and seeking succeed.
      emitPlayer(false);
    }
  }
  function emitPlayer(force) {
    if (!playerActive && !force) return;
    var v = video(), p = originalPlayer(), c = combo(), settings = subtitleSettings(), config = playerConfig();
    watchMedia(v);
    var lang = typeof settings.language === 'string' ? settings.language : 'double';
    var current = currentPlaybackVideo(), correctEpisode = pendingEpisodeReady();
    var tracks = current.subs_exist || [];
    var originalAvailable = tracks.length ? tracks.indexOf('double') >= 0 || tracks.indexOf('en') >= 0 || tracks.indexOf(current.orig_lang) >= 0 : !!(p && p.state && p.state.subtitleListData);
    var ruAvailable = tracks.length ? tracks.indexOf('double') >= 0 || tracks.indexOf('ru') >= 0 : originalAvailable;
    var waitingForZero = pending && pending.kind === 'play' && pending.position === 0;
    var state = { ready: !!(!navigating && !waitingForZero && correctEpisode && v && v.readyState >= 1), readyState: v ? number(v.readyState, 0) : 0,
      mediaErrorCode: v && v.error ? number(v.error.code, 0) : 0, paused: !v || v.paused, currentTime: v ? Math.round(v.currentTime * 10) / 10 : 0,
      duration: v ? number(v.duration, 0) : 0, title: current.displayTitle || current.ruTitle || current.title || text(document.querySelector('h1')),
      subtitleMode: lang === 'double' ? 'both' : lang === '' ? 'off' : lang === 'ru' ? 'ru' : 'en',
      subtitleScale: number(settings.textSize, 50) / 50, subtitleSize: number(settings.textSize, 50),
      subtitleBackground: number(settings.backgroundTransparencyEn, 0.5) > 0 || number(settings.backgroundTransparencyRu, 0.5) > 0,
      englishAvailable: originalAvailable, russianAvailable: ruAvailable, speed: v ? v.playbackRate : preferences.speed };
    Object.assign(state, videoOptions(config, v));
    var serialized = JSON.stringify(state);
    if (force || serialized !== lastPlayer) { lastPlayer = serialized; emit('player', state); }
  }
  function emitAuth(force) {
    var loggedIn = window.g_loggedIn === true || window.g_loggedIn === 1 || !!(window.yoTvLoginPageConfig && window.yoTvLoginPageConfig.isLoggedIn);
    var name = loggedIn ? text(document.querySelector('.header__profile-name,.profile-name,.user-name')) : '';
    var value = JSON.stringify({ loggedIn: loggedIn, displayName: name });
    if (force || value !== lastAuth) { lastAuth = value; emit('auth', { loggedIn: loggedIn, displayName: name }); }
    if (loggedIn && loginActive) { hideLoginSurface(); loginActive = false; save(sessionStorage, PENDING, null); document.documentElement.classList.remove('twosub-tv-login'); }
    return loggedIn;
  }
  function hideLoginSurface() {
    if (loginReady) { loginReady = false; emit('login', { ready: false }); }
    if (loginObserver) { loginObserver.disconnect(); loginObserver = null; }
  }
  function startLogin() {
    hideLoginSurface(); stop(true); loginActive = true; loginStarted = false; loginReady = false; loginConfirming = false; loginDeadline = Date.now() + 30000;
    document.documentElement.classList.add('twosub-tv-login');
    save(sessionStorage, PENDING, { kind: 'login', createdAt: Date.now() });
    if (emitAuth(true)) return;
    if (typeof window.login === 'function') window.login(0, ['google', 'telegram', 'vkid', 'yandex', 'email']);
    else location.assign('/login/');
  }
  function checkLogin() {
    if (!loginActive) { if (loginObserver) hideLoginSurface(); return; }
    if (emitAuth(false)) return;
    if (loginConfirming) return;
    var pairingStatus = document.getElementById('tv-pairing-error');
    if (!loginObserver && pairingStatus && typeof MutationObserver !== 'undefined') {
      loginObserver = new MutationObserver(function () {
        // Only observe the site's visible confirmation wording, never pairing identifiers.
        if (loginActive && /Код подтверждён\. Выполняем вход/.test(text(pairingStatus))) {
          loginConfirming = true; hideLoginSurface();
        }
      });
      loginObserver.observe(pairingStatus, { childList: true, characterData: true, subtree: true });
    }
    var block = document.getElementById('tv_block');
    if (!loginStarted && block && typeof window.tvLoginStart === 'function') {
      loginStarted = true;
      if (typeof window.tvLoginShowBlock === 'function') window.tvLoginShowBlock('tv_app');
      window.tvLoginStart(false, 'tv_app');
    }
    var canvas = document.getElementById('tv-pairing-qr-canvas');
    var code = document.getElementById('tv-pairing-code');
    if (!loginReady && canvas && canvas.classList.contains('is-ready') && canvas.getBoundingClientRect().width > 0 && code && /^\d{5}$/.test(text(code))) {
      loginReady = true; emit('login', { ready: true });
    }
    if (!loginReady && Date.now() > loginDeadline) {
      loginDeadline = Infinity;
      error('QR-код не появился. Проверьте сеть и повторно откройте вход.', 'login');
    }
  }
  function command(name, data) {
    data = data || {};
    try {
      lastError = '';
      if (name === 'catalog') { stop(true); loginActive = false; return catalog(data); }
      if (name === 'detail') { stop(true); return detail(data); }
      if (name === 'login') return startLogin();
      if (name === 'closeLogin') {
        loginActive = false; save(sessionStorage, PENDING, null);
        if (typeof window.tvLoginStopPolling === 'function') window.tvLoginStopPolling();
        document.documentElement.classList.remove('twosub-tv-login');
        document.querySelectorAll('.easy-modal.open').forEach(function (modal) { modal.classList.remove('open'); });
        return;
      }
      if (name === 'logout') { stop(true); loginActive = false; if (typeof window.tvLoginStopPolling === 'function') window.tvLoginStopPolling(); return; }
      if (name === 'play') return beginPlay(data);
      if (name === 'configure') {
        var sizes = Array.isArray(data.problemVideoSizes) ? data.problemVideoSizes : [];
        deviceConfig = { problemVideoSizes: sizes.slice(0, 16).map(function (size) {
          return { width: Math.round(clamp(number(size && size.width, 0), 0, 8192)), height: Math.round(clamp(number(size && size.height, 0), 0, 8192)) };
        }).filter(function (size) { return size.width > 0 && size.height > 0; }) };
        save(sessionStorage, 'twosub_tv_device_v1', deviceConfig);
        updateQualityPolicy(); return emitPlayer(true);
      }
      if (name === 'controls') {
        updateViewport();
        document.documentElement.classList.toggle('twosub-tv-controls', data.visible === true);
        document.documentElement.classList.toggle('twosub-tv-panel', data.visible === true && data.panelOpen === true);
        return;
      }
      if (name === 'pause') { stop(false); return emitPlayer(true); }
      if (name === 'closePlayer') { stop(true); return emitPlayer(true); }
      var v = video(), p = originalPlayer();
      if (name === 'quality') {
        if (['auto', 'hd', 'sd'].indexOf(data.value) < 0) throw new Error('Неизвестный режим качества.');
        var ids = availableQualities(playerConfig());
        if (data.value !== 'auto' && !preferredSource(ids, data.value)) throw new Error('Это качество недоступно на выбранном сервере.');
        preferences.qualityMode = data.value; savePreferences();
        qualityApplied = false; widthGuardAttempted = false; qualityChange = null; playbackNotice = '';
        updateQualityPolicy(true);
      } else if (name === 'server') {
        var c = combo(), config = playerConfig();
        var domains = Array.isArray(config.videoDomains) ? config.videoDomains : [];
        var server = domains.find(function (d) { return d && d.key === data.value; });
        if (!server) throw new Error('Сервер отсутствует в текущем списке 2SUB.');
        if (server.paidOnly === true && window.g_payed !== true) throw new Error('Этот сервер доступен по подписке 2SUB.');
        if (!c || typeof c.onDomainChange !== 'function') throw new Error('Переключение сервера пока недоступно.');
        if (config.domain !== server.key) {
          qualityChange = { domain: server.key, at: Date.now(), revision: mediaMetadataRevision, video: video() };
          c.onDomainChange(server.key, { actor: 'user', persistDomainSelection: true });
          // The original player preserves position and pause state while reloading.
          qualityApplied = false; waitingSince = 0;
        }
      } else if (name === 'toggle' || name === 'resume') {
        if (!v) throw new Error('Видео ещё не готово.');
        if (v.paused) {
          pauseLatch = false;
          var promise = p && typeof p.play === 'function' ? p.play() : v.play();
          if (promise && promise.catch) promise.catch(function () { error('Не удалось продолжить видео.', 'toggle'); });
        } else if (name === 'toggle') stop(false);
      } else if (name === 'seek') {
        if (!v || !Number.isFinite(v.duration)) throw new Error('Перемотка пока недоступна.');
        if (!seekPlayback(clamp(v.currentTime + number(data.delta, 0), 0, Math.max(0, v.duration - 0.25)), 'tv-remote', !v.paused))
          throw new Error('Перемотка пока недоступна. Попробуйте ещё раз.');
      } else if (name === 'subtitle') {
        if (['both', 'en', 'ru', 'off'].indexOf(data.mode) < 0) throw new Error('Неизвестный режим субтитров.');
        preferences.mode = data.mode; savePreferences(); if (p) applyPreferences();
      } else if (name === 'subtitleSize') {
        preferences.scale = clamp(number(data.scale, 1), 0.5, 2); savePreferences(); if (p) applyPreferences();
      } else if (name === 'subtitleBackground') {
        preferences.background = data.enabled !== false; savePreferences(); if (p) applyPreferences();
      } else if (name === 'speed') {
        preferences.speed = clamp(number(data.value, 1), 0.5, 2); savePreferences(); if (p) applyPreferences();
      } else if (name === 'state') { emitAuth(true); }
      else throw new Error('Неизвестная команда клиента.');
      setTimeout(function () { emitPlayer(true); }, 80);
    } catch (e) { error(e.message || 'Не удалось выполнить действие.', name); }
  }

  installStyle();
  updateViewport();
  window.addEventListener('resize', updateViewport);
  window.addEventListener('orientationchange', updateViewport);
  window.TwoSubTV = { version: 1, command: command };
  if (pending && Date.now() - number(pending.createdAt, 0) < 120000) {
    if (pending.kind === 'play') { pauseLatch = false; setPlayerActive(true); startDeadline = Date.now() + 45000; startHardDeadline = Date.now() + 120000; }
    else if (pending.kind === 'login') { loginActive = true; loginDeadline = Date.now() + 30000; document.documentElement.classList.add('twosub-tv-login'); }
  } else { pending = null; save(sessionStorage, PENDING, null); }
  window.addEventListener('pagehide', function () { var v = video(); if (v) v.pause(); });
  window.addEventListener('pagehide', function () { if (loginActive) hideLoginSurface(); });
  document.addEventListener('visibilitychange', function () { if (document.hidden && playerActive && !navigating) stop(false); });
  setInterval(function () {
    try {
      if (pauseLatch) document.querySelectorAll('video').forEach(function (el) { if (!el.paused) el.pause(); });
      tryStart(); updateQualityPolicy(); checkLogin(); emitAuth(false); emitPlayer(false);
    } catch (_) {}
  }, 1000);
  emit('ready', { version: 1, path: location.pathname });
  emitAuth(true);
  tryStart();
})();
