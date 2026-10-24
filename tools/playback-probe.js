// Evaluate via webview-inspect.mjs --file. Collects no media URLs, account or storage data.
(() => {
  const v = document.querySelector('video');
  if (!v) throw new Error('No video element');
  if (window.__tvProbe?.timer) clearInterval(window.__tvProbe.timer);
  const probe = window.__tvProbe = {start:performance.now(),startTime:v.currentTime,events:[],samples:[]};
  for(const name of ['waiting','stalled','playing','error']) v.addEventListener(name, () => {
    if(window.__tvProbe===probe) probe.events.push({name,t:Math.round(performance.now()-probe.start),at:v.currentTime});
  });
  probe.timer=setInterval(() => {
    if(window.__tvProbe!==probe) return clearInterval(probe.timer);
    const q=v.getVideoPlaybackQuality();
    probe.samples.push({s:Math.round((performance.now()-probe.start)/1000),t:Math.round(v.currentTime*10)/10,ready:v.readyState,
      buffer:v.buffered.length?Math.round((v.buffered.end(v.buffered.length-1)-v.currentTime)*10)/10:0,
      totalFrames:q.totalVideoFrames,droppedFrames:q.droppedVideoFrames});
    if(probe.samples.length>=24) clearInterval(probe.timer);
  },5000);
  window.TwoSubTV.command('resume');
  return {started:true,quality:window.yoComboPlayer.state.config.quality,server:window.yoComboPlayer.state.config.domain,
    width:v.videoWidth,height:v.videoHeight};
})();
