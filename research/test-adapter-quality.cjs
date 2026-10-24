// No network, user profile, or television access. Exercises adapter policy with a fake site player.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/2sub-adapter.js'), 'utf8');

function fixture(width = 1998, height = 1080, options = {}) {
  let now = 1000000, interval;
  const events = [], qualityCalls = [], serverCalls = [], listeners = {}, documentListeners = {}, windowListeners = {}, timers = [], navigation = [], siteStarts = [], episodeResolves = [], episodeSelections = [], seeks = [];
  const storage = (values = {}) => ({getItem: k => values[k] || null, setItem: (k, v) => {values[k] = v;}, removeItem: k => {delete values[k];}});
  const localValues = options.localValues || {};
  const initialPending = {kind: 'play', createdAt: now};
  if (Object.hasOwn(options, 'position')) initialPending.position = options.position;
  const sessionValues = options.sessionValues || {twosub_tv_pending_v1: JSON.stringify(initialPending)};
  const media = {videoWidth: width, videoHeight: height, paused: false, ended: false, readyState: options.readyState ?? 4, duration: 500, currentTime: 12, playbackRate: 1,
    buffered: {length: 1, start: () => 0, end: () => 34}, pause() {this.paused = true;}, play() {this.paused = false;},
    addEventListener: (k, v) => {listeners[k] = v;}, removeEventListener: k => {delete listeners[k];}};
  const config = {quality: options.quality || 'hls', domain: 'auto', subtitleSettings: {}, qualityAvailable: ['hls', 'hlssd'],
    currentVideo: {id: 'one', getAvailableQualitiesForUI: () => config.qualityAvailable},
    videoDomains: [{key: 'auto', name: 'Авто'}, {key: 'public', name: 'Обычный'}, {key: 'paid', name: 'Платный', paidOnly: true}]};
  const player = {player: {el: () => ({querySelector: () => media})}, pause: () => media.pause(), play: () => media.play(),
    setActiveSubtitleTrack() {}, setPlaybackSpeed() {},
    seekTo(seconds, reason, opts) {
      seeks.push({seconds, reason, opts});
      if (options.rejectSeek) return false;
      media.currentTime = seconds;
      this._playbackResumeAfterSourceLoad = 0;
      if (options.seekStillPending) media.seeking = true;
      if (opts.resumePlaybackIntent === true) media.play();
      return true;
    }};
  const combo = {state: {config}, config, yoPlayer: () => player, onSubtitleSettingsChange() {},
    onQualityChange(id, opts) {qualityCalls.push({id, opts}); config.quality = id;},
    onDomainChange(id, opts) {serverCalls.push({id, opts}); config.domain = id;}};
  const root = {clientHeight: 540, style: {setProperty() {}}, classList: {toggle() {}, add() {}, remove() {}}};
  const document = {documentElement: root, hidden: false, head: {appendChild() {}}, addEventListener: (name, listener) => {documentListeners[name] = listener;},
    getElementById: () => null, querySelector: () => null, querySelectorAll: () => [media], createElement: () => ({})};
  const initialUrl = new URL(options.url || 'https://2sub.movie/film/example/');
  const location = {origin: initialUrl.origin, href: initialUrl.href, pathname: initialUrl.pathname, hash: initialUrl.hash,
    assign(url) {navigation.push({kind: 'assign', url}); this.href = url; this.hash = new URL(url).hash;},
    reload() {navigation.push({kind: 'reload'});}};
  const makeEpisode = (season, number, id = String(season * 100 + number)) => ({id, season_number: season, episode_number: number, getAvailableQualitiesForUI: () => config.qualityAvailable});
  const originalEpisode = options.currentEpisode || (initialUrl.hash.match(/#watch-s(\d+)e(\d+)/) || []).slice(1).map(Number);
  if (originalEpisode.length >= 2) config.currentVideo = makeEpisode(originalEpisode[0], originalEpisode[1], originalEpisode[2]);
  const fpp = {play: () => siteStarts.push(location.href),
    resolveInitialSeriesVideo(url) {
      episodeResolves.push(url);
      if (options.wrongResolution) return makeEpisode(2, 6, '323756');
      const match = url.match(/#watch-s(\d+)e(\d+)/); return match ? makeEpisode(Number(match[1]), Number(match[2])) : null;
    },
    setCurrentVideoEpisode(target, autoplay, flag, sourceName) {
      episodeSelections.push({target, autoplay, flag, sourceName});
      if (!options.deferEpisodeMount) config.currentVideo = target;
      if (autoplay) this.play(true);
    }};
  const context = {document, location,
    innerHeight: 540, addEventListener: (name, listener) => {windowListeners[name] = listener;}, yoComboPlayer: combo, g_payed: false,
    filmPagePlayer: fpp,
    localStorage: storage(localValues), sessionStorage: storage(sessionValues),
    TwoSubBridge: {postMessage: value => events.push(JSON.parse(value))},
    setInterval: fn => {interval = fn;}, setTimeout: fn => {timers.push(fn); return timers.length;}, clearTimeout() {},
    URL, URLSearchParams, Date: class extends Date {static now() {return now;}}};
  context.window = context; context.top = context;
  vm.runInNewContext(source, context);
  const flush = () => {while (timers.length) timers.shift()();};
  return {media, config, qualityCalls, serverCalls, events, localValues, sessionValues, navigation, siteStarts, episodeResolves, episodeSelections, seeks, player,
    command(name, payload = {}) {context.TwoSubTV.command(name, payload); flush();},
    tick(ms = 1000) {now += ms; interval(); flush();},
    event(name) {if (listeners[name]) listeners[name]();},
    hidden(value = true) {document.hidden = value; if (documentListeners.visibilitychange) documentListeners.visibilitychange();},
    pagehide() {if (windowListeners.pagehide) windowListeners.pagehide();},
    state() {return events.filter(e => e.type === 'player').at(-1);}};
}

const guarded = fixture();
guarded.command('configure', {problemVideoSizes: [{width: 1998, height: 1080}]});
assert.equal(guarded.qualityCalls.length, 1);
assert.equal(guarded.qualityCalls[0].id, 'hlssd');
assert.equal(guarded.qualityCalls[0].opts.persistPreference, false);
guarded.tick(); guarded.tick();
assert.equal(guarded.qualityCalls.length, 1, 'No repeated switches while a source is loading');
guarded.media.videoWidth = 888; guarded.media.videoHeight = 480; guarded.event('loadedmetadata'); guarded.tick();
assert.match(guarded.state().actualQuality, /SD.*888.*480/);
assert.match(guarded.state().playbackNotice, /включено SD/);
assert.equal(guarded.state().bufferedSeconds, 22);
guarded.command('quality', {value: 'hd'});
assert.equal(guarded.qualityCalls.at(-1).id, 'hls');
guarded.media.videoWidth = 1998; guarded.media.videoHeight = 1080; guarded.event('loadedmetadata'); guarded.tick(); guarded.tick(6000);
assert.equal(guarded.qualityCalls.length, 2, 'Manual HD disables the empirical Auto fallback');
guarded.command('quality', {value: 'auto'});
assert.equal(guarded.qualityCalls.at(-1).id, 'hlssd');
guarded.media.videoWidth = 888; guarded.media.videoHeight = 480; guarded.event('loadedmetadata'); guarded.tick();
guarded.command('server', {value: 'public'});
assert.equal(guarded.serverCalls.at(-1).id, 'public');
guarded.event('loadedmetadata'); guarded.tick();
assert.equal(guarded.qualityCalls.at(-1).id, 'hlssd', 'Auto keeps the observed compatibility fallback after server change');
guarded.command('server', {value: 'paid'});
guarded.command('server', {value: 'not-in-list'});
assert.equal(guarded.serverCalls.length, 1, 'Unavailable and unknown servers cannot be selected');
assert.equal(guarded.state().serverOptions.find(s => s.id === 'paid').enabled, false);
assert.deepEqual(Object.keys(guarded.state().serverOptions[0]).sort(), ['enabled', 'id', 'label']);
const priorErrors = guarded.events.filter(e => e.type === 'error').length;
guarded.media.readyState = 2; guarded.event('waiting'); guarded.tick(11000);
assert.equal(guarded.state().buffering, true);
assert.match(guarded.state().playbackNotice, /Буферизация затянулась/);
guarded.tick(30000);
assert.match(guarded.state().playbackNotice, /долго буферизуется/);
assert.equal(guarded.events.filter(e => e.type === 'error').length, priorErrors, 'A long stall is recoverable state, not a global error');
guarded.command('pause');
assert.equal(guarded.state().buffering, false);

for (const [width, height] of [[1920, 1080], [2048, 1080], [1998, 720]]) {
  const other = fixture(width, height);
  other.command('configure', {problemVideoSizes: [{width: 1998, height: 1080}]}); other.tick();
  assert.equal(other.qualityCalls.length, 0, `No compatibility restriction for ${width}x${height}`);
}
const unrestricted = fixture(); unrestricted.command('configure', {problemVideoSizes: []}); unrestricted.tick();
assert.equal(unrestricted.qualityCalls.length, 0, 'Other TV models remain unrestricted');

const startup = fixture(0, 0, {readyState: 0, quality: 'hlssd', position: 125});
startup.command('configure', {problemVideoSizes: [{width: 1998, height: 1080}]}); startup.tick(46000);
assert.equal(startup.qualityCalls.length, 0, 'Default Auto does not force initial HD before metadata');
assert.equal(startup.events.filter(e => e.type === 'error').length, 0, '45 second startup wait remains recoverable');
assert.ok(startup.sessionValues.twosub_tv_pending_v1, 'Slow startup retains pending resume position');
assert.equal(startup.state().readyState, 0);
assert.equal(startup.state().mediaErrorCode, 0);
assert.ok(startup.state().playbackNotice.length > 0);
startup.media.videoWidth = 888; startup.media.videoHeight = 480; startup.media.readyState = 2; startup.tick(12000);
assert.equal(startup.media.currentTime, 125, 'A 58-second startup restores requested position');
assert.equal(startup.sessionValues.twosub_tv_pending_v1, undefined);
assert.equal(startup.events.filter(e => e.type === 'error').length, 0);

const delayedSeek = fixture(0, 0, {readyState: 0, position: 125});
let seekAttempts = 0, seekTime = 12;
Object.defineProperty(delayedSeek.media, 'currentTime', {get: () => seekTime, set: value => {
  if (++seekAttempts === 1) throw new Error('Not yet seekable'); seekTime = value;
}});
delayedSeek.tick(46000); delayedSeek.media.readyState = 1; delayedSeek.tick();
assert.ok(delayedSeek.sessionValues.twosub_tv_pending_v1, 'A rejected seek retains pending resume');
delayedSeek.tick();
assert.equal(seekTime, 125);
assert.equal(delayedSeek.sessionValues.twosub_tv_pending_v1, undefined);

const timedOut = fixture(0, 0, {readyState: 0, position: 125});
timedOut.tick(121000);
assert.equal(timedOut.media.paused, true, 'Hard timeout pauses before emitting an error');
assert.equal(timedOut.sessionValues.twosub_tv_pending_v1, undefined);
assert.equal(timedOut.events.filter(e => e.type === 'error' && e.context === 'play').length, 1);
timedOut.media.readyState = 4; timedOut.media.paused = false; timedOut.tick();
assert.equal(timedOut.media.paused, true, 'Late autoplay stays stopped behind Retry');
assert.equal(timedOut.events.filter(e => e.type === 'error' && e.context === 'play').length, 1);

const cacheKey = 'twosub_tv_known_problem_video_ids_v1';
const cachedValues = {[cacheKey]: guarded.localValues[cacheKey]};
assert.equal(JSON.parse(cachedValues[cacheKey])[0].id, 'one');
const cachedStart = fixture(0, 0, {readyState: 0, localValues: {...cachedValues}});
cachedStart.command('configure', {problemVideoSizes: [{width: 1998, height: 1080}]});
assert.equal(cachedStart.qualityCalls.at(-1).id, 'hlssd', 'Known problematic video selects SD before loading HD metadata');
const cacheOtherModel = fixture(0, 0, {readyState: 0, localValues: {...cachedValues}});
cacheOtherModel.command('configure', {problemVideoSizes: []});
assert.equal(cacheOtherModel.qualityCalls.length, 0, 'Cached ID has no effect with an inactive model profile');
const cacheOtherSize = fixture(0, 0, {readyState: 0, localValues: {...cachedValues}});
cacheOtherSize.command('configure', {problemVideoSizes: [{width: 2000, height: 1080}]});
assert.equal(cacheOtherSize.qualityCalls.length, 0, 'Cached ID only applies to its empirical size profile');
const manualRecovery = fixture(0, 0, {readyState: 0}); manualRecovery.command('quality', {value: 'sd'});
assert.equal(manualRecovery.qualityCalls.at(-1).id, 'hlssd', 'Explicit selection remains available before metadata');
const oversizedCache = Array.from({length: 60}, (_, i) => ({id: String(i), width: 1998, height: 1080}));
const bounded = fixture(1998, 1080, {localValues: {[cacheKey]: JSON.stringify(oversizedCache)}});
bounded.command('configure', {problemVideoSizes: [{width: 1998, height: 1080}]});
assert.equal(JSON.parse(bounded.localValues[cacheKey]).length, 50);

for (const target of ['https://2sub.movie/film/next-film/', 'https://2sub.movie/series/mobland-2025/#watch-s1e2']) {
  const sourceUrl = 'https://2sub.movie/series/mobland-2025/#watch-s1e1';
  const switching = fixture(1920, 1080, {url: sourceUrl});
  switching.command('play', {url: target, position: 72});
  assert.equal(switching.navigation[0].url, target);
  assert.equal(switching.media.paused, true);
  switching.hidden(); switching.pagehide(); switching.tick();
  assert.equal(JSON.parse(switching.sessionValues.twosub_tv_pending_v1).url, target, 'Navigation keeps the new title/episode intent');
  assert.equal(JSON.parse(switching.sessionValues.twosub_tv_pending_v1).position, 72);
  assert.equal(switching.siteStarts.length, 1, 'Old page does not restart its player while navigating');
  if (target.includes('/series/')) assert.equal(switching.navigation[1].kind, 'reload');
  const arrived = fixture(1920, 1080, {url: target, readyState: 0, sessionValues: switching.sessionValues});
  assert.equal(arrived.siteStarts[0], target, 'New adapter opens the requested episode URL');
  arrived.media.readyState = 2; arrived.tick();
  assert.equal(arrived.media.currentTime, 72);
  assert.equal(arrived.sessionValues.twosub_tv_pending_v1, undefined);
}
const homeDuringStartup = fixture(0, 0, {readyState: 0});
homeDuringStartup.hidden();
assert.equal(homeDuringStartup.media.paused, true);
assert.equal(homeDuringStartup.sessionValues.twosub_tv_pending_v1, undefined, 'Home still cancels a pending startup');
const homeDuringPlayback = fixture(); homeDuringPlayback.hidden();
assert.equal(homeDuringPlayback.media.paused, true, 'Home still pauses active playback');
const cancelledNavigation = fixture(); cancelledNavigation.command('play', {url: 'https://2sub.movie/film/next-film/'});
cancelledNavigation.command('pause');
assert.equal(cancelledNavigation.sessionValues.twosub_tv_pending_v1, undefined, 'Explicit native pause can cancel navigation intent');

const episodeUrl = 'https://2sub.movie/series/mobland-2025/#watch-s1e2';
const cleanedUrl = 'https://2sub.movie/series/mobland-2025/#season2';
const episodePending = () => ({twosub_tv_pending_v1: JSON.stringify({kind: 'play', createdAt: 1000000, url: episodeUrl, position: 72})});
const restoredWrongEpisode = fixture(1920, 1080, {url: cleanedUrl, sessionValues: episodePending(), currentEpisode: [2, 6, '323756'], deferEpisodeMount: true});
restoredWrongEpisode.command('state');
assert.equal(restoredWrongEpisode.episodeResolves[0], episodeUrl, 'Resolve saved URL rather than cleaned location hash');
assert.equal(restoredWrongEpisode.episodeSelections.length, 1);
assert.equal(restoredWrongEpisode.episodeSelections[0].target.season_number, 1);
assert.equal(restoredWrongEpisode.episodeSelections[0].target.episode_number, 2);
assert.equal(restoredWrongEpisode.episodeSelections[0].autoplay, true);
assert.equal(restoredWrongEpisode.episodeSelections[0].flag, false);
assert.equal(restoredWrongEpisode.episodeSelections[0].sourceName, 'episode_card');
assert.equal(restoredWrongEpisode.siteStarts.length, 1, 'Episode-card API owns its play call; adapter does not double-start');
assert.equal(restoredWrongEpisode.state().ready, false, 'Wrong loaded episode cannot expose native playback');
assert.equal(restoredWrongEpisode.media.paused, true);
assert.equal(restoredWrongEpisode.media.currentTime, 12, 'Resume position is never applied to the wrong episode');
assert.ok(restoredWrongEpisode.sessionValues.twosub_tv_pending_v1);
restoredWrongEpisode.tick();
assert.equal(restoredWrongEpisode.episodeSelections.length, 1, 'Episode selection is not reissued each second');
restoredWrongEpisode.config.currentVideo = {...restoredWrongEpisode.episodeSelections[0].target, id: 'different-id'};
restoredWrongEpisode.tick(); restoredWrongEpisode.command('state');
assert.equal(restoredWrongEpisode.state().ready, false, 'Matching season/number with the wrong video ID is still rejected');
restoredWrongEpisode.config.currentVideo = restoredWrongEpisode.episodeSelections[0].target;
restoredWrongEpisode.media.readyState = 2; restoredWrongEpisode.tick();
assert.equal(restoredWrongEpisode.state().ready, true);
assert.equal(restoredWrongEpisode.media.currentTime, 72);
assert.equal(restoredWrongEpisode.sessionValues.twosub_tv_pending_v1, undefined);
const badResolution = fixture(1920, 1080, {url: cleanedUrl, sessionValues: episodePending(), currentEpisode: [2, 6, '323756'], wrongResolution: true});
badResolution.command('state');
assert.equal(badResolution.episodeSelections.length, 0, 'A resolver fallback to another season is not accepted');
assert.equal(badResolution.siteStarts.length, 0);
assert.equal(badResolution.state().ready, false);
assert.equal(badResolution.media.paused, true);
assert.ok(badResolution.sessionValues.twosub_tv_pending_v1);
const sameHashWrongEpisode = fixture(1920, 1080, {url: episodeUrl, currentEpisode: [2, 6, '323756']});
sameHashWrongEpisode.command('play', {url: episodeUrl, position: 72});
assert.equal(sameHashWrongEpisode.episodeSelections.length, 1, 'Duplicate/resume fast path also checks actual episode identity');
assert.equal(sameHashWrongEpisode.config.currentVideo.season_number, 1);
assert.equal(sameHashWrongEpisode.config.currentVideo.episode_number, 2);
assert.equal(sameHashWrongEpisode.media.currentTime, 72);

const restart = fixture(); restart.media.currentTime = 85; restart.command('closePlayer');
restart.command('play', {url: 'https://2sub.movie/film/example/', position: 0});
restart.command('state');
assert.equal(restart.state().ready, false, 'Restart is masked until real media confirms zero');
restart.tick(); restart.tick();
assert.equal(restart.media.currentTime, 0, 'Explicit restart seeks an existing paused video to zero');
assert.equal(restart.media.paused, false);
restart.media.currentTime = 55; restart.command('pause');
restart.command('play', {url: 'https://2sub.movie/film/example/', position: 0});
assert.equal(restart.media.currentTime, 55, 'Duplicate active play delivery does not reset progress');
assert.equal(restart.media.paused, true, 'Duplicate active play delivery preserves deliberate pause');
restart.command('closePlayer'); restart.command('play', {url: 'https://2sub.movie/film/example/'});
assert.equal(restart.media.currentTime, 55, 'Omitted position preserves the existing site position');
const restartLoading = fixture(0, 0, {readyState: 0, position: 0});
restartLoading.tick(46000); restartLoading.media.readyState = 4; restartLoading.tick(); restartLoading.tick(); restartLoading.tick();
assert.equal(restartLoading.media.currentTime, 0, 'Explicit zero also survives a delayed pending startup');
assert.equal(restartLoading.sessionValues.twosub_tv_pending_v1, undefined);
const restartNavigation = fixture(); restartNavigation.command('play', {url: 'https://2sub.movie/film/next-film/', position: 0});
restartNavigation.hidden(); restartNavigation.tick();
assert.equal(JSON.parse(restartNavigation.sessionValues.twosub_tv_pending_v1).position, 0);
const restartedPage = fixture(1920, 1080, {url: 'https://2sub.movie/film/next-film/', sessionValues: restartNavigation.sessionValues});
restartedPage.tick(); restartedPage.tick();
assert.equal(restartedPage.media.currentTime, 0, 'Explicit zero survives page navigation and reinjection');

const zeroSettling = fixture(1920, 1080, {position: 0});
zeroSettling.player.isSettingSrc = true; zeroSettling.tick();
assert.equal(zeroSettling.seeks.length, 0, 'Zero waits for initial site source setup');
zeroSettling.player.isSettingSrc = false; zeroSettling.tick(); zeroSettling.tick();
assert.equal(zeroSettling.seeks.length, 1);
zeroSettling.media.currentTime = 53; zeroSettling.player.isSettingSrc = true; zeroSettling.tick();
assert.equal(zeroSettling.state().ready, false, 'An optimistic seek result cannot confirm a restored old media position');
zeroSettling.player.isSettingSrc = false; zeroSettling.tick(); zeroSettling.tick();
assert.equal(zeroSettling.seeks.length, 2, 'Late initial source restore permits one corrective zero seek');
zeroSettling.player.getCurrentTime = () => 53;
zeroSettling.tick(); zeroSettling.tick();
assert.equal(zeroSettling.state().ready, true, 'Confirmation uses real media rather than stale site reported time');
assert.equal(zeroSettling.media.currentTime, 0);
assert.equal(zeroSettling.seeks.length, 2, 'Confirmed zero never loops');
zeroSettling.config.quality = 'hlssd'; zeroSettling.command('server', {value: 'public'});
zeroSettling.event('loadedmetadata'); zeroSettling.tick();
zeroSettling.media.currentTime = 30; zeroSettling.tick();
assert.equal(zeroSettling.qualityCalls.length, 0, 'Auto source promotion stays blocked through real media time 30');
zeroSettling.media.currentTime = 31; zeroSettling.tick();
assert.equal(zeroSettling.qualityCalls.at(-1).id, 'hls', 'Normal Auto policy resumes after real media progresses beyond 30');

const zeroBounded = fixture(1920, 1080, {position: 0});
zeroBounded.tick(); zeroBounded.media.currentTime = 53; zeroBounded.tick(2000);
zeroBounded.media.currentTime = 53; zeroBounded.tick(); zeroBounded.tick(); zeroBounded.tick(5000);
assert.equal(zeroBounded.seeks.length, 2, 'A repeated source restore cannot create a zero-seek loop');
assert.equal(zeroBounded.state().ready, false);
for (const cancel of ['pause', 'closePlayer']) {
  const cancelledZero = fixture(1920, 1080, {position: 0});
  cancelledZero.tick(); cancelledZero.media.currentTime = 53;
  cancelledZero.command(cancel); cancelledZero.tick(5000); cancelledZero.tick();
  assert.equal(cancelledZero.seeks.length, 1, cancel + ' cancels deferred zero correction');
  assert.equal(cancelledZero.media.paused, true);
  assert.equal(cancelledZero.sessionValues.twosub_tv_pending_v1, undefined);
}
const homeZero = fixture(1920, 1080, {position: 0});
homeZero.hidden(); homeZero.tick(5000);
assert.equal(homeZero.seeks.length, 0, 'Home cancels a zero intent before its first seek');
assert.equal(homeZero.media.paused, true);

const originalSeek = fixture(); originalSeek.media.currentTime = 88.2;
originalSeek.player._playbackResumeAfterSourceLoad = 2562;
originalSeek.command('seek', {delta: 10});
assert.equal(originalSeek.seeks.at(-1).reason, 'tv-remote');
assert.equal(originalSeek.seeks.at(-1).seconds, 98.2);
assert.equal(originalSeek.player._playbackResumeAfterSourceLoad, 0, 'Native remote uses the original seek path that clears stale resume');
originalSeek.command('pause'); originalSeek.command('seek', {delta: -10});
assert.equal(originalSeek.seeks.at(-1).opts.resumePlaybackIntent, false);
assert.equal(originalSeek.media.paused, true, 'Seeking while paused preserves pause intent');
assert.equal(restartLoading.seeks.at(-1).reason, 'tv-resume');
assert.equal(restartLoading.seeks.at(-1).seconds, 0, 'Restart also uses original network seek');
const sourceSettling = fixture(854, 480, {quality: 'hlssd', position: 88.2, seekStillPending: true});
sourceSettling.command('configure', {problemVideoSizes: []}); sourceSettling.tick();
assert.equal(sourceSettling.qualityCalls.length, 0, 'Default Auto does not reload quality while initial seek is active');
sourceSettling.media.seeking = false; sourceSettling.media.readyState = 1; sourceSettling.tick();
assert.equal(sourceSettling.qualityCalls.length, 0, 'Default Auto waits for playable data');
sourceSettling.media.readyState = 4; sourceSettling.player.isSettingSrc = true; sourceSettling.tick();
assert.equal(sourceSettling.qualityCalls.length, 0, 'Default Auto waits for site source setup');
sourceSettling.player.isSettingSrc = false; sourceSettling.tick();
assert.equal(sourceSettling.qualityCalls.at(-1).id, 'hls');
const refusedNetworkSeek = fixture(0, 0, {readyState: 0, position: 88.2, rejectSeek: true});
refusedNetworkSeek.media.readyState = 2; refusedNetworkSeek.tick();
assert.equal(refusedNetworkSeek.media.currentTime, 12, 'Rejected network seek never falls back to raw DOM assignment');
assert.ok(refusedNetworkSeek.sessionValues.twosub_tv_pending_v1, 'Rejected network seek retains requested resume');
console.log('PASS: quality/server/cache/startup and episode policies; explicit restart; original network seek and pause intent; no Auto source reload during initial seek/setup; rejected seek retention.');
