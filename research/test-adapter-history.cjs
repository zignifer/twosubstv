// Offline fixtures for the site's authenticated Continue and playback-progress contracts.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {DOMParser} = require('linkedom');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/2sub-adapter.js'), 'utf8');
const doc = html => new DOMParser().parseFromString(html, 'text/html');
const events = [], requests = [];
let response;
const store = {getItem: () => null, setItem() {}, removeItem() {}};
const context = {
  location: {origin: 'https://2sub.movie', href: 'https://2sub.movie/'}, document: doc('<html><head></head><body></body></html>'),
  localStorage: store, sessionStorage: store, URL, URLSearchParams, DOMParser, AbortController, setTimeout, clearTimeout, Date,
  auth: {nonce: 'fixture-session-nonce'}, g_loggedIn: true,
  TwoSubBridge: {postMessage: raw => events.push(JSON.parse(raw))},
  fetch: async (url, options) => {requests.push({url, options}); return {ok: true, json: async () => response};},
};
context.window = context; context.top = context;
vm.runInNewContext(source.replace('  installStyle();', '  window.test = {parseCards, parseDetail, catalog, loadAccountResume, resumePoint}; return;\n  installStyle();'), context);
const api = context.test;
const url = 'https://2sub.movie/series/example/';
const watching = `<a class="watching-card js-film-list-card-link" href="${url}#watch-s2e3" data-film-list-card-id="15">
  <img class="watching-card__img" src="https://2sub.movie/landscape.jpg" alt="Пример">
  <progress class="watching-card__progress" value="42" max="100"></progress>
  <h3 class="card-content__title">Пример</h3><h4 class="watching-card__remaining">осталось 24 мин</h4></a>`;
const episodes = [
  {id: 101, is_published: true, season_number: 2, episode_number: 2, title: 'Second'},
  {id: 102, is_published: true, season_number: 2, episode_number: 3, title: 'Third'},
];
const config = {userState: {progress: {series: 15, items_compact: [[101, 1500, 50, 1792900000, 0], [102, 120, 4, 1792900100, 0]]}}};
const detailHtml = `<h1>Пример</h1><button class="movie-hero-new__action" data-filmid="15"></button>
  <script>class VideosInfo {constructor() {this.data = ${JSON.stringify(episodes)};}}</script>
  <script>window.yoFilmEpisodesConfig = ${JSON.stringify(config)};</script>`;

(async () => {
  response = {error: '', data: watching, rest: 'yes', total: null};
  await api.catalog({category: 'continue', page: 2});
  const event = events.at(-1), request = requests.at(-1);
  assert.equal(event.type, 'catalog'); assert.equal(event.category, 'continue'); assert.equal(event.page, 2);
  assert.equal(event.hasMore, true); assert.equal(event.total, null); assert.equal(event.filters.orders.length, 0);
  assert.equal(new URL(request.url, url).searchParams.get('list_name'), 'continue_watching');
  assert.equal(request.options.credentials, 'include'); assert.equal(request.options.headers['X-WP-Nonce'], context.auth.nonce);
  assert.equal(request.options.cache, 'no-store');
  assert.equal(event.items[0].landscape, 'https://2sub.movie/landscape.jpg'); assert.equal(event.items[0].poster, '');
  assert.equal(event.items[0].progress, .42); assert.equal(event.items[0].remainingLabel, 'осталось 24 мин');
  assert.equal(event.items[0].season, 2); assert.equal(event.items[0].episode, 3);
  assert.equal(JSON.stringify(events).includes(context.auth.nonce), false, 'Session data stays inside WebView');
  const item = api.parseDetail(doc(detailHtml), url);
  assert.equal(item.resumeItems.length, 2); assert.equal(item.resumeItems[1].position, 120);
  assert.equal(item.resumeItems[1].updatedAt, 1792900100000);
  response = {item: {id: 102, progress: 30, percent: 1, ts: 1792900200}};
  await api.loadAccountResume(item, url);
  assert.equal(requests.at(-1).url, '/wp-json/yo/light/playback-progress/102', 'Newest save selects the episode, not furthest playback');
  assert.equal(item.resumeItems.find(p => p.url.endsWith('s2e3')).position, 30, 'A server rewind replaces an older snapshot');
  await api.loadAccountResume(api.parseDetail(doc(detailHtml), url), `${url}#watch-s2e2`);
  assert.equal(requests.at(-1).url, '/wp-json/yo/light/playback-progress/101', 'Explicit episode is preserved');
  const film = {id: '50', url: 'https://2sub.movie/film/example/', episodes: [], resumeItems: []};
  response = {item: {progress: 420, ts: 1792900300}};
  await api.loadAccountResume(film, film.url);
  assert.equal(film.resumeItems[0].position, 420);
  response = {item: null}; const fresh = {...film, resumeItems: []}; await api.loadAccountResume(fresh, fresh.url);
  assert.equal(fresh.resumeItems.length, 0, 'Missing account progress never becomes an explicit restart at zero');
  const fetch = context.fetch; context.fetch = async () => {throw Error('offline');};
  const offline = api.parseDetail(doc(detailHtml), url); await api.loadAccountResume(offline, url);
  assert.equal(offline.resumeItems.length, 2, 'Embedded account snapshot survives a failed refresh');
  context.fetch = fetch; context.g_loggedIn = false; const count = requests.length;
  await api.catalog({category: 'continue'}); assert.equal(requests.length, count);
  assert.equal(events.at(-1).type, 'error');
  console.log('PASS: authenticated Continue, pagination, wide artwork, exact episode, account timestamps, rewinds, film resume, offline fallback and session isolation.');
})().catch(error => {console.error(error); process.exitCode = 1;});
