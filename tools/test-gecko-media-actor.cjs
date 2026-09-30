// Run against actors/MediaControlDelegateChild.sys.mjs extracted from the built APK's omni.ja.
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync(process.argv[2], 'utf8').replace(/^import .*;\s*$/mg, '').replace('export class ', 'class ');
async function scenario(activateAt, exitAt = Infinity, latePlay = 0) {
  let clock = 0, calls = 0, accepted = false;
  const timers = [], snapshots = [];
  const element = Object.freeze({currentSrc:'https://media.test/video', currentTime:42, duration:90,
    playbackRate:1.25, volume:.4, paused:false, muted:false, textTracks:[], querySelectorAll:()=>[]});
  const document = {fullscreenElement:element, documentURI:'https://frame.test/player'};
  class Base { static initLogging() { return {debug(){}}; } }
  const box = {GeckoViewActorChild:Base, Date:{now:()=>clock}, ChromeUtils:{defineESModuleGetters(obj) {
    Object.assign(obj, {MediaUtils:{findMediaElement:e=>e, getMetadata:e=>e?{source:e.currentSrc}:null},
      setTimeout:(fn,ms)=>timers.push({fn,at:clock+ms})});
  }}};
  vm.runInNewContext(source+'\nglobalThis.Actor=MediaControlDelegateChild;', box);
  const actor = new box.Actor(); actor.document = document;
  actor.eventDispatcher = {sendRequest:p=>snapshots.push(p), sendRequestForResult:async p=>{
    calls++; const ready = clock >= activateAt; if (ready && p.enabled) accepted = true; return ready;
  }};
  async function drain() {
    await new Promise(setImmediate);
    while (timers.length) {
      assert.ok(calls < 150); const t = timers.shift(); clock = t.at;
      if (clock >= exitAt) document.fullscreenElement = null;
      t.fn(); await new Promise(setImmediate);
    }
  }
  actor.handleEvent({type:'MozDOMFullscreen:Entered'}); await drain();
  if (latePlay) {clock = latePlay; actor.handleEvent({type:'playing'}); await drain();}
  return {calls,clock,accepted,snapshots};
}
(async()=>{
  const slow = await scenario(1600); assert.equal(slow.accepted, true);
  const never = await scenario(Infinity); assert.equal(never.accepted, false); assert.ok(never.clock <= 5000);
  const exit = await scenario(1600,300); assert.equal(exit.accepted,false); assert.ok(exit.clock <= 300);
  const late = await scenario(12000,Infinity,10000); assert.equal(late.accepted,true);
  const state = slow.snapshots.find(x=>x.enabled);
  assert.equal(state.position,42); assert.equal(state.volume,.4); assert.equal(state.rate,1.25); assert.equal(state.frame,'https://frame.test/player');
  let plays=0, pauses=0;
  const media={currentSrc:'https://media.test/video',currentTime:0,duration:90,play:async()=>{plays++;},pause:()=>{pauses++;}};
  class Base {static initLogging(){return {debug(){}};}}
  const box={GeckoViewActorChild:Base,ChromeUtils:{defineESModuleGetters(o){o.MediaUtils={findMediaElement:e=>e};}}};
  vm.runInNewContext(source+'\nglobalThis.Actor=MediaControlDelegateChild;',box);
  const actor=new box.Actor();actor.document={documentURI:'https://frame.test/player',fullscreenElement:null,querySelectorAll:()=>[media]};
  const request={name:'TVBro:RestoreMedia',data:{frame:'https://frame.test/player',source:media.currentSrc,time:42,resume:true}};
  assert.equal(await actor.receiveMessage(request),true);assert.equal(media.currentTime,42);assert.equal(plays,1);
  assert.equal(await actor.receiveMessage({...request,data:{...request.data,frame:'https://other.test/'}}),false);assert.equal(plays,1);
  actor.document.querySelectorAll=()=>[media,{...media}];
  assert.equal(await actor.receiveMessage(request),false);
  actor.document.querySelectorAll=()=>[media];
  assert.equal(await actor.receiveMessage({...request,data:{...request.data,time:12,resume:false}}),true);assert.equal(media.currentTime,12);assert.equal(pauses,1);
  console.log(JSON.stringify({passed:9,slow:{calls:slow.calls,clock:slow.clock},deadline:never.clock,exit:exit.clock,late:late.clock,readOnlyState:true,scopedRestore:true}));
})().catch(error=>{console.error(error);process.exitCode=1;});
