// Browserless adapter regression. Install test dependency outside the application:
// npm install --prefix "$env:TEMP\twosub-adapter-test-deps" linkedom --no-audit --no-fund
// node research/test-adapter-catalog.cjs [--live]
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
let DOMParser;
try { ({DOMParser} = require('linkedom')); }
catch { ({DOMParser} = require(path.join(os.tmpdir(), 'twosub-adapter-test-deps/node_modules/linkedom'))); }
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/2sub-adapter.js'), 'utf8');
const events = [], requests = [];
const store = {getItem: () => null, setItem() {}, removeItem() {}};
const metadata = {genres: [{id: 'drama', name: 'Драма'}, {id: 'comedy', name: 'Комедия'}], genresByType: {movie: [{id: 'drama', name: 'Драма'}]}};
const now = 1792834499000;
const card = `<a data-film-list-card-id="15" class="vertical-card" href="https://2sub.movie/series/example-2026/#watch-s2e3">
  <img class="vertical-card__img" src="https://2sub.movie/poster.jpg" alt="Пример">
  <div class="movie-hero-new__rating" title="Рейтинг IMDB"><img src="/wp-content/themes/2sub/images/icons/imd.svg" alt="Рейтинг IMDB">8.2</div>
  <div class="movie-hero-new__rating" title="Рейтинг Кинопоиск"><img src="/wp-content/themes/2sub/images/icons/kp.svg">7,9</div>
  <h3 class="card-content__title">Пример</h3><h3 class="card-content__title card-content__subtitle">Example</h3>
  <h4 class="card-content__subtitle">2025–2026, Драма</h4><span class="from-now" data-time="${now / 1000 - 3 * 3600}"></span>
  <span class="update-chip__text">С2 · 3 серия</span></a>`;
let response = {error: '', data: card, rest: 'yes', total: 57};
const context = {location: {origin: 'https://2sub.movie', href: 'https://2sub.movie/'}, document: new DOMParser().parseFromString('<html><head></head><body></body></html>', 'text/html'),
  localStorage: store, sessionStorage: store, URL, URLSearchParams, DOMParser, AbortController, setTimeout, clearTimeout,
  Date: class extends Date {static now() {return now;}},
  TwoSubBridge: {postMessage: raw => events.push(JSON.parse(raw))},
  fetch: async url => { requests.push(url); return {ok: true, json: async () => response, text: async () => `<script>window.yoGenresConfig = ${JSON.stringify(metadata)};</script>`}; }};
context.window = context; context.top = context;
vm.runInNewContext(source.replace('  installStyle();', '  window.test = {parseCards, parseDetail, resolveDetailPortrait, catalog, filtersFor, relativeAddedLabel, installStyle, startLogin, checkLogin}; return;\n  installStyle();'), context);
const api = context.test;
const doc = html => new DOMParser().parseFromString(html, 'text/html');

(async () => {
  const items = api.parseCards(doc(card));
  assert.equal(items.length, 1);
  assert.equal(items[0].title, 'Пример');
  assert.equal(items[0].originalTitle, 'Example');
  assert.equal(items[0].year, '2025–2026');
  assert.equal(items[0].addedLabel, '3 часа назад');
  assert.equal(items[0].episodeLabel, 'С2 · 3 серия');
  assert.equal(items[0].season, 2); assert.equal(items[0].episode, 3);
  assert.equal(items[0].landscape, '', 'Never pretend a portrait poster is genuine wide art');
  assert.deepEqual(JSON.parse(JSON.stringify(items[0].ratings)).map(r => [r.source, r.value]), [['imdb', 8.2], ['kp', 7.9]]);
  assert.equal(items[0].ratings[0].iconUrl, 'https://2sub.movie/wp-content/themes/2sub/images/icons/imd.svg');
  assert.equal(api.parseCards(doc(card.replace(/<span class="from-now"[^>]*><\/span>/, '')))[0].addedLabel, '', 'No timestamp means no invented recency');
  for (const [hours, expected] of [[1, '1 час назад'], [4, '4 часа назад'], [11, '11 часов назад'], [21, '21 час назад'], [48, '2 дня назад']])
    assert.equal(api.relativeAddedLabel(now / 1000 - hours * 3600), expected);
  assert.equal(api.relativeAddedLabel(now / 1000 + 3600), '', 'Implausible future timestamps are not labelled just added');
  const updateCard = card.replace('#watch-s2e3', '').replace('<span class="update-chip__text">С2 · 3 серия</span>', '<div class="update-chip update-chip--episodes" title="7 сезон · серия 6"><span class="update-chip__seg">С7</span><span class="update-chip__text">серия 6</span></div>');
  const updateItem = api.parseCards(doc(updateCard))[0];
  assert.equal(updateItem.episodeLabel, '7 сезон · серия 6'); assert.equal(updateItem.season, 7); assert.equal(updateItem.episode, 6);
  const multiUpdate = api.parseCards(doc(updateCard.replace('7 сезон · серия 6', '7 сезон · серии 6–7')))[0];
  assert.equal(multiUpdate.episode, 0, 'An update range is not invented as one particular episode');
  const detail = api.parseDetail(doc(`<h1>Пример</h1><img class="movie-hero-new__img-bg" src="/wide.jpg"><div class="movie-hero-new__ratings"><div class="movie-hero-new__rating" title="Рейтинг TMDB"><img src="/wp-content/themes/2sub/images/icons/tmdb.svg">8.1</div></div>${card}`), 'https://2sub.movie/film/example/');
  assert.equal(detail.landscape, 'https://2sub.movie/wide.jpg');
  assert.equal(detail.poster, '', 'A hero backdrop is never returned as a portrait poster');
  const ownPoster = api.parseDetail(doc('<h1>Пример</h1><img class="movie-hero-new__img-bg" src="/wide.jpg">' + card), 'https://2sub.movie/series/example-2026/');
  assert.equal(ownPoster.poster, 'https://2sub.movie/poster.jpg', 'An exact matching title card can supply its actual portrait');
  const wideOg = api.parseDetail(doc('<meta property="og:image" content="https://2sub.movie/social-wide.jpg"><h1>Без портрета</h1>' + card), 'https://2sub.movie/film/example/');
  assert.equal(wideOg.poster, '', 'Social share art and other titles posters cannot substitute the own portrait');
  const beforeKnown = requests.length;
  const knownPortrait = await api.resolveDetailPortrait({id: '15', url: 'https://2sub.movie/series/example-2026/', title: 'Пример', poster: ''});
  assert.equal(knownPortrait.poster, 'https://2sub.movie/poster.jpg'); assert.equal(requests.length, beforeKnown, 'The catalog portrait cache avoids another search');
  const lookupUrl = 'https://2sub.movie/series/lookup-2026/';
  const lookupCard = card.replace('data-film-list-card-id="15"', 'data-film-list-card-id="77"').replace('https://2sub.movie/series/example-2026/#watch-s2e3', lookupUrl);
  response = {error: '', data: card + lookupCard, rest: 'yes', total: null};
  const beforeLookup = requests.length;
  const lookedUp = await api.resolveDetailPortrait({id: '77', url: lookupUrl, title: 'Название', originalTitle: 'Lookup', poster: '', landscape: 'https://2sub.movie/wide.jpg'});
  assert.equal(lookedUp.poster, 'https://2sub.movie/poster.jpg'); assert.equal(lookedUp.landscape, 'https://2sub.movie/wide.jpg');
  assert.equal(requests.length, beforeLookup + 1, 'Missing portrait makes only one search');
  assert.equal(new URL(requests.at(-1), 'https://2sub.movie').searchParams.get('query'), 'Lookup');
  await api.resolveDetailPortrait({id: '77', url: lookupUrl, title: 'Название', poster: ''});
  assert.equal(requests.length, beforeLookup + 1, 'Resolved portraits are reused');
  const wrongId = await api.resolveDetailPortrait({id: '88', url: lookupUrl, title: 'Название', poster: ''});
  assert.equal(wrongId.poster, '', 'Matching URL with a conflicting numeric title ID is rejected');
  const wrongUrl = await api.resolveDetailPortrait({id: '77', url: 'https://2sub.movie/series/another-2026/', title: 'Название', poster: ''});
  assert.equal(wrongUrl.poster, '', 'Matching ID on another canonical title URL is rejected');
  const previousFetch = context.fetch; context.fetch = async () => { throw new Error('offline'); };
  const failedLookup = await api.resolveDetailPortrait({id: '99', url: 'https://2sub.movie/series/offline-2026/', title: 'Офлайн', poster: ''});
  assert.equal(failedLookup.poster, '', 'A failed artwork search leaves detail usable');
  context.fetch = previousFetch; response = {error: '', data: card, rest: 'yes', total: 57};
  assert.equal(detail.ratings.length, 1, 'Detail does not collect recommendation-card ratings');
  assert.equal(detail.ratings[0].source, 'tmdb');
  assert.equal(api.parseDetail(doc('<h1>Нет рейтинга</h1>' + card), 'https://2sub.movie/film/example/').ratings.length, 0, 'Missing hero ratings stay empty even when recommendations have ratings');
  const cleanMeta = api.parseDetail(doc('<h1>Сериал</h1><div class="movie-hero-new__meta-list"><span class="movie-hero-new__meta-item"><span class="movie-hero-new__level">C1</span></span><span class="movie-hero-new__meta-item">Криминал, Драма</span><span class="movie-hero-new__meta-item">2025 - 2026</span><span class="movie-hero-new__meta-item">2 сезона, 16 эпизодов</span><span class="movie-hero-new__meta-item" title="Субтитры">Субтитры: EN · RU</span></div>'), 'https://2sub.movie/series/example/');
  assert.equal(cleanMeta.meta, '2025–2026 · Криминал, Драма · 2 сезона, 16 эпизодов'); assert.equal(cleanMeta.year, '2025–2026');
  await api.catalog({category: 'films', genre: 'drama', order: 'rating_imdb', page: 2});
  let event = events.at(-1);
  assert.equal(event.type, 'catalog'); assert.equal(event.total, 57); assert.equal(event.hasMore, true);
  assert.equal(event.genre, 'drama'); assert.equal(event.order, 'rating_imdb');
  assert.deepEqual(JSON.parse(JSON.stringify(event.filters.genres)), [{id: 'drama', label: 'Драма'}]);
  let url = new URL(requests.find(x => x.includes('list_name=by_filter')), 'https://2sub.movie');
  assert.equal(url.searchParams.get('filter_q'), 'type=movie&genres=drama&order=rating_imdb');
  assert.equal(url.searchParams.get('page'), '2');
  await api.catalog({category: 'all', genre: 'drama&accent=ru', order: 'difficulty'});
  event = events.at(-1);
  assert.equal(event.genre, ''); assert.equal(event.order, 'site_popularity');
  assert.equal(event.filters.orders.some(o => /difficulty|language|accent/.test(o.id)), false);
  response = {error: '', data: updateCard, rest: 'yes', total: null};
  await api.catalog({category: 'new', genre: 'drama', order: 'year', page: 3});
  event = events.at(-1); url = new URL(requests.at(-1), 'https://2sub.movie');
  assert.equal(url.searchParams.get('list_name'), 'newest'); assert.equal(url.searchParams.get('page'), '3');
  assert.equal(url.searchParams.has('filter_q'), false, 'New feed retains the official update order without catalog filtering');
  assert.equal(event.total, null); assert.equal(event.hasMore, true); assert.equal(event.filters.orders.length, 0);
  assert.equal(event.genre, 'drama'); assert.equal(event.order, 'year', 'Echo request keys for native stale-response rejection');
  await api.catalog({category: 'films', query: 'Truman', genre: 'drama'});
  url = new URL(requests.at(-1), 'https://2sub.movie');
  assert.equal(url.searchParams.get('content_type'), 'search'); assert.equal(url.searchParams.has('filter_q'), false);
  assert.equal(events.at(-1).filters.orders.length, 0, 'Search does not advertise unverified filters');
  response = {error: 'all_done', rest: 'no', total: 0};
  await api.catalog({category: 'films', genre: 'drama', order: 'updated', page: 999});
  event = events.at(-1);
  assert.equal(event.hasMore, false); assert.equal(event.items.length, 0); assert.equal(event.genre, 'drama'); assert.equal(event.order, 'updated');
  api.installStyle();
  const css = context.document.getElementById('twosub-tv-style').textContent;
  assert.match(css, /\.en-subtitle \*/); assert.match(css, /\.ru-subtitle \*/); assert.match(css, /font-family:Roboto,"Noto Sans",Arial,sans-serif!important;font-weight:500!important/);
  context.document.body.innerHTML = '<div id="tv_block"></div><div id="tv-pairing-error"></div><canvas id="tv-pairing-qr-canvas" class="is-ready"></canvas><div id="tv-pairing-code">11111</div>';
  context.document.getElementById('tv-pairing-qr-canvas').getBoundingClientRect = () => ({width: 200});
  context.MutationObserver = context.document.defaultView.MutationObserver;
  context.login = () => {}; context.tvLoginStart = () => {};
  api.startLogin(); api.checkLogin();
  assert.deepEqual(events.filter(e => e.type === 'login').at(-1), {type: 'login', ready: true});
  context.document.getElementById('tv-pairing-error').textContent = 'Код подтверждён. Выполняем вход…';
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(events.filter(e => e.type === 'login').at(-1), {type: 'login', ready: false}, 'Visible confirmation immediately hides the QR surface without exposing session data');
  api.checkLogin();
  assert.equal(events.filter(e => e.type === 'login').at(-1).ready, false, 'Confirmed pairing cannot re-expose the old QR');
  if (process.argv.includes('--live')) {
    const [catalog, film, feed] = await Promise.all([
      fetch('https://2sub.movie/wp-json/yo/v1/filmlist?' + new URLSearchParams({content_type:'list',list_name:'by_filter',filter_q:'type=movie&genres=drama&order=rating_imdb',page:'1'})).then(r => r.json()),
      fetch('https://2sub.movie/film/the-truman-show-1998/').then(r => r.text()),
      fetch('https://2sub.movie/wp-json/yo/v1/filmlist?' + new URLSearchParams({content_type:'list',list_name:'newest',page:'1'})).then(r => r.json())]);
    const liveCards = api.parseCards(doc(catalog.data));
    const liveDetail = api.parseDetail(doc(film), 'https://2sub.movie/film/the-truman-show-1998/');
    const liveRecency = api.parseCards(doc(feed.data)).filter(i => i.addedAt > 0);
    assert.ok(liveCards.length > 0); assert.ok(liveDetail.ratings.length >= 2); assert.ok(liveRecency.length > 0);
    assert.equal(liveDetail.meta, '1998 · Комедия, Драма · 1 ч 42 мин');
    assert.equal(liveDetail.poster, '', 'Current detail SSR has no own portrait; native must retain the catalog portrait');
    assert.ok(liveDetail.landscape.startsWith('https://2sub.movie/'));
    assert.ok(liveRecency.some(i => i.season > 0 && i.episode > 0));
    context.fetch = (url, options) => fetch(new URL(url, 'https://2sub.movie'), options);
    const moblandHtml = await fetch('https://2sub.movie/series/mobland-2025/').then(r => r.text());
    const mobland = await api.resolveDetailPortrait(api.parseDetail(doc(moblandHtml), 'https://2sub.movie/series/mobland-2025/'));
    assert.ok(mobland.poster.startsWith('https://2sub.movie/')); assert.notEqual(mobland.poster, mobland.landscape);
    console.log(JSON.stringify({live: {filteredCards: liveCards.length, total: catalog.total, ratings: liveDetail.ratings, recencyCards: liveRecency.length, firstUpdate: liveRecency[0].episodeLabel, firstEpisode: liveRecency.find(i => i.episode > 0).episodeLabel}}));
  }
  console.log('PASS: actual HTML parsing, ratings/brand assets, recency evidence, genre/order requests, filter metadata, pagination, search, subtitle typography.');
})().catch(error => {console.error(error); process.exitCode = 1;});
