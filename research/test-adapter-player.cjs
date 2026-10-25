// Playback contract tests: preview never seeks; commit/cancel preserve intent and video identity.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {parseHTML} = require('linkedom');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/2sub-adapter.js'), 'utf8');

function fixture({sprite = 'https://2sub.movie/example-sprite.jpg'} = {}) {
  const {document} = parseHTML('<html><head></head><body><div id="layout-dialog"><video></video></div></body></html>');
  const media = document.querySelector('video');
  Object.assign(media, {paused: false, readyState: 4, currentTime: 12, duration: 500, playbackRate: 1, videoWidth: 1920, videoHeight: 1080,
    buffered: {length: 0}, pause() {this.paused = true;}, play() {this.paused = false;}});
  const events = [], seeks = [], images = [], timers = [];
  let interval, now = 1_000_000, plays = 0, skips = 0;
  const storage = values => ({getItem: k => values[k] || null, setItem: (k, v) => {values[k] = v;}, removeItem: k => {delete values[k];}});
  const config = {subtitleSettings: {}, currentVideo: {id: 'one', thumbnailSpriteUrl: sprite, getAvailableQualitiesForUI: () => []}};
  const intro = {isShown: true, skipDismissed: false, getOptions: () => ({skipStart: 1, skipDuration: 360, skipTarget: 90}),
    skipToTarget() {skips++; player.seekTo(90.03, 'skip_intro', {resumePlaybackIntent: !media.paused}); this.isShown = false;}};
  const player = {player: {el: () => document.querySelector('#layout-dialog')}, skipIntroManager: intro,
    pause() {media.pause();}, play() {plays++; media.play();}, setActiveSubtitleTrack() {}, setPlaybackSpeed() {},
    seekTo(seconds, reason, opts) {seeks.push({seconds, reason, opts}); media.currentTime = seconds; media.paused = !opts.resumePlaybackIntent; return true;}};
  const combo = {state: {config}, config, yoPlayer: () => player, onSubtitleSettingsChange() {}};
  const context = {document, location: new URL('https://2sub.movie/series/example/#watch'), yoComboPlayer: combo,
    innerHeight: 540, innerWidth: 960, addEventListener() {}, filmPagePlayer: {play() {}},
    localStorage: storage({}), sessionStorage: storage({twosub_tv_pending_v1: JSON.stringify({kind: 'play', createdAt: now})}),
    TwoSubBridge: {postMessage: value => events.push(JSON.parse(value))},
    setInterval: fn => {interval = fn;}, setTimeout: fn => {timers.push(fn); return timers.length;}, clearTimeout() {},
    Image: function () {const image = document.createElement('img'); images.push(image); return image;},
    URL, URLSearchParams, Date: class extends Date {static now() {return now;}}};
  context.window = context; context.top = context;
  vm.runInNewContext(source, context);
  function flush() {while (timers.length) timers.shift()();}
  return {document, media, config, intro, events, seeks, images,
    command(name, data = {}) {context.TwoSubTV.command(name, {videoId: 'one', ...data}); flush();},
    tick() {now += 1000; interval(); flush();},
    state: () => events.filter(e => e.type === 'player').at(-1),
    plays: () => plays, skips: () => skips,
    preview: () => document.querySelector('.twosub-tv-seek-preview')};
}

const seek = fixture();
assert.equal(seek.state().introAvailable, true);
assert.equal(seek.state().playbackId, 'one');
seek.command('seekPreview', {seconds: 120, x: .01, top: .65});
assert.equal(seek.media.paused, true);
assert.equal(seek.media.currentTime, 12);
assert.equal(seek.state().introAvailable, false, 'The contextual intro action hides during scrubbing');
assert.equal(seek.seeks.length, 0);
assert.equal(seek.preview().textContent, '2:00');
assert.equal(seek.preview().style.left, '116px', 'Preview stays on screen at the left edge');
assert.equal(seek.preview().querySelector('div').style.display, 'none', 'No empty thumbnail rectangle while loading');
for (const seconds of [150, 180, 240, 300]) seek.command('seekPreview', {seconds, x: .5, top: .65});
assert.equal(seek.seeks.length, 0, 'A held key cannot restart HLS for every preview position');
assert.equal(seek.images.length, 1, 'The sprite is loaded once per preview session');
Object.assign(seek.images[0], {naturalWidth: 320, naturalHeight: 1800});
seek.images[0].onload();
assert.equal(seek.preview().querySelector('img').style.transform, 'translateY(-702px)', 'Same vertical-sprite frame mapping as 2SUB');
assert.equal(seek.preview().querySelector('div').style.height, '117px');
seek.command('seekTo', {seconds: 300});
assert.equal(seek.seeks.length, 1);
assert.equal(seek.seeks[0].seconds, 300);
assert.equal(seek.seeks[0].reason, 'tv-timeline');
assert.equal(seek.seeks[0].opts.resumePlaybackIntent, true);
assert.equal(seek.media.paused, false);
assert.equal(seek.preview(), null);

for (const paused of [false, true]) {
  const f = fixture();
  if (paused) f.command('pause');
  const playsBefore = f.plays();
  f.command('seekPreview', {seconds: 200});
  f.command('seekPreview', {seconds: 300});
  f.command('seekPreviewCancel');
  assert.equal(f.seeks.length, 0, 'Cancel does not seek');
  assert.equal(f.media.currentTime, 12, 'Cancel retains the original frame');
  assert.equal(f.media.paused, paused);
  assert.equal(f.plays() - playsBefore, paused ? 0 : 1, 'Cancel restores the original playback intent');
  f.command('seekPreview', {seconds: 220});
  f.command('seekTo', {seconds: 220});
  assert.equal(f.seeks[0].opts.resumePlaybackIntent, !paused);
  assert.equal(f.media.paused, paused, 'Commit preserves pause as well as playing state');
}

const stale = fixture();
stale.command('seekPreview', {seconds: 200});
stale.config.currentVideo.id = 'two';
stale.tick();
assert.equal(stale.preview(), null);
stale.command('seekTo', {seconds: 300});
stale.command('seekPreviewCancel');
assert.equal(stale.seeks.length, 0, 'An old episode cannot seek the new episode');
assert.equal(stale.plays(), 0, 'A stale preview cannot autoplay another episode');

const closed = fixture();
closed.command('seekPreview', {seconds: 200});
closed.command('closePlayer');
closed.command('seekPreviewCancel');
assert.equal(closed.preview(), null);
assert.equal(closed.media.paused, true, 'Exit/background pause cannot be undone by a late cancel');
assert.equal(closed.state().introAvailable, false);
const background = fixture();
background.command('seekPreview', {seconds: 200});
background.command('pause');
background.command('seekPreviewCancel');
assert.equal(background.preview(), null);
assert.equal(background.media.paused, true, 'Background/MediaSession pause cancels without resuming');
const hardwarePlay = fixture();
hardwarePlay.command('seekPreview', {seconds: 200});
hardwarePlay.command('resume');
assert.equal(hardwarePlay.seeks[0].seconds, 200, 'MediaSession play confirms the selected position');
assert.equal(hardwarePlay.media.paused, false);
assert.equal(hardwarePlay.preview(), null);
const hardwareSeek = fixture();
hardwareSeek.command('seekPreview', {seconds: 200});
hardwareSeek.command('seek', {delta: 10});
assert.equal(hardwareSeek.seeks[0].seconds, 210, 'A dedicated seek key uses the preview position');
assert.equal(hardwareSeek.media.paused, false);
assert.equal(hardwareSeek.preview(), null);

const missing = fixture({sprite: ''});
missing.command('seekPreview', {seconds: 220});
assert.equal(missing.images.length, 0);
assert.equal(missing.preview().textContent, '3:40', 'Timestamp-only fallback works without a sprite');
const failed = fixture();
failed.command('seekPreview', {seconds: 220});
failed.images[0].onerror();
assert.equal(failed.preview().textContent, '3:40');
assert.equal(failed.preview().querySelector('div').style.display, 'none');

const skip = fixture();
skip.command('skipIntro');
skip.command('skipIntro');
assert.equal(skip.skips(), 1, 'Duplicate OK cannot skip twice');
assert.equal(skip.seeks[0].reason, 'skip_intro', 'Use the original site action and its seek policy');
assert.equal(skip.state().introAvailable, false);
const expired = fixture();
expired.media.currentTime = 91;
expired.command('skipIntro');
assert.equal(expired.skips(), 0, 'Revalidate availability at the time of the click');
const wrongEpisode = fixture();
wrongEpisode.command('skipIntro', {videoId: 'old'});
wrongEpisode.command('seekPreview', {videoId: 'old', seconds: 30});
assert.equal(wrongEpisode.skips(), 0);
assert.equal(wrongEpisode.preview(), null);

const limits = fixture();
limits.command('seekTo', {seconds: -50});
limits.command('seekTo', {seconds: 1000});
assert.deepEqual(limits.seeks.map(s => s.seconds), [0, 499.75]);
limits.command('seekTo', {seconds: NaN});
assert.equal(limits.seeks.length, 2);
console.log('Player adapter: preview/commit/cancel, pause intent, sprites, intro and stale episode checks passed.');
