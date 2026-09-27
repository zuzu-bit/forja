// Regression tests for the shipped public map frame. No network, WebGL or location access.
// These verify fallback/control behavior; real tile/render evidence requires the browser.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const server=path.resolve(__dirname,'../server');
const {JSDOM}=require(process.env.FORJA_JSDOM||require.resolve('jsdom',{paths:[server]}));
const frame=fs.readFileSync(path.join(server,'map-frame.html'),'utf8'),source=fs.readFileSync(path.join(server,'map-frame-client.js.txt'),'utf8');
const checks=[];
function fixture(mode='deferred'){
 const dom=new JSDOM(frame,{url:'https://forja.test/insights/map-frame',runScripts:'outside-only'}),w=dom.window,events=[],errors=[],creations=[],drawn=[],handlers={},tileHandlers={};
 const base={center:{lat:45.8,lng:24.9},zoom:6,removed:false,setView(p,z){this.center={lat:p[0],lng:p[1]};this.zoom=z;return this;},getCenter(){return this.center;},getZoom(){return this.zoom;},on(name,callback){handlers[name]=callback;return this;},fitBounds(){return this;},invalidateSize(){},remove(){this.removed=true;}};
 const tiles={addTo(){return this;},on(name,callback){tileHandlers[name]=callback;return this;}};
 const overlay={clearLayers(){drawn.length=0;},addTo(){return this;}};
 w.L={map:()=>base,control:{zoom:()=>({addTo(){}})},layerGroup:()=>overlay,tileLayer:()=>tiles,geoJSON(data){return {addTo(){drawn.push(data);return this;}}},marker(){return{}},divIcon(value){return value;}};
 w.ForjaMapHost={event(raw){events.push(JSON.parse(raw));}};w.console.error=(...args)=>errors.push(args.join(' '));
 w.ForjaMapRenderer={create(container,options){
  if(mode==='throw'){w.document.getElementById(container).append(w.document.createElement('canvas'));throw Error('Failed to initialize WebGL');}
  const vector={options,center:{lat:options.center[1],lon:options.center[0],zoom:options.zoom},destroyed:false,data:null,three:false,resizes:0,setData(data){this.data=data;},resize(){this.resizes++;},set3D(enabled){this.three=enabled;return true;},focus(p,z){this.center={...p,zoom:z};},fit(){},getCenter(){return this.center;},destroy(){this.destroyed=true;}};creations.push(vector);return vector;
 }};
 w.eval(source);return {dom,w,events,errors,creations,base,drawn,tileHandlers,$:id=>w.document.getElementById(id),close(){dom.window.close();}};
}
function check(name,fn){const f=fixture();try{fn(f);checks.push(name);}finally{f.close();}}
check('2D exists immediately and readiness waits for a real raster tile receipt',f=>{
 assert(f.w.ForjaMap);assert(!f.$('map').hidden);assert(f.$('vector').hidden);assert.equal(f.creations.length,0);assert.equal(f.w.ForjaMap.ready,false);
 f.tileHandlers.tileerror();assert.equal(f.w.ForjaMap.ready,false);assert.match(f.$('map-status').textContent,/conexiunea/);
 f.tileHandlers.tileload();assert.equal(f.w.ForjaMap.ready,true);assert.equal(f.events.filter(e=>e.type==='ready').length,1);assert.equal(f.events.find(e=>e.type==='ready').mode,'2d');f.tileHandlers.tileload();assert.equal(f.events.filter(e=>e.type==='ready').length,1);
});
{
 const f=fixture('throw');try{
  f.tileHandlers.tileload();f.w.ForjaMap.set3D(true);assert(!f.base.removed);assert(f.$('vector').hidden);assert.equal(f.$('vector').children.length,0);assert.equal(f.$('vector').style.pointerEvents,'none');assert.match(f.$('map-status').dataset.error,/Failed to initialize WebGL/);assert.equal(f.w.ForjaMap.mode3d,false);assert.equal(f.events.at(-1).recoverable,true);
  const data={people:{type:'FeatureCollection',features:[{type:'Feature',properties:{id:'p'},geometry:{type:'Point',coordinates:[26.1,44.4]}}]}};f.w.ForjaMap.setData(data);assert.equal(f.drawn[0],data.people);f.w.ForjaMap.focus({lat:44.4,lon:26.1},15);assert.equal(f.base.center.lat,44.4);assert.equal(f.base.zoom,15);
  checks.push('synchronous WebGL failure leaves interactive 2D with truthful error and retained overlays');
 }finally{f.close();}
}
check('3D receives the current 2D camera and overlays only when ready',f=>{
 f.w.ForjaMap.focus({lat:44.4,lon:26.1},16);const data={places:{type:'FeatureCollection',features:[]}};f.w.ForjaMap.setData(data);f.w.ForjaMap.set3D(true);const vector=f.creations[0];assert.equal(vector.options.zoom,16);assert.deepEqual([...vector.options.center],[26.1,44.4]);assert.equal(f.$('vector').style.opacity,'0');assert.equal(f.$('vector').style.pointerEvents,'none');assert.equal(f.w.ForjaMap.mode3d,false);
 vector.options.onReady();assert.equal(vector.data,data);assert.equal(vector.three,true);assert.equal(f.w.ForjaMap.mode3d,true);assert.equal(f.$('vector').style.pointerEvents,'auto');
 vector.center={lat:46.7,lon:23.6,zoom:18};f.w.ForjaMap.set3D(false);assert.equal(f.base.center.lat,46.7);assert.equal(f.base.zoom,18);assert(f.$('vector').hidden);
});
check('late 3D readiness after cancellation cannot hide the 2D map',f=>{
 f.w.ForjaMap.set3D(true);const vector=f.creations[0];f.w.ForjaMap.set3D(false);vector.options.onReady();assert.equal(f.w.ForjaMap.mode3d,false);assert(f.$('vector').hidden);assert.equal(f.$('vector').style.pointerEvents,'none');assert.equal(vector.destroyed,true,'Cancelled pending renderer must be released before another attempt');
});
check('post-ready vector failure preserves camera and overlays on 2D',f=>{
 const data={routes:{type:'FeatureCollection',features:[{type:'Feature',geometry:{type:'LineString',coordinates:[[26.1,44.4],[26.2,44.5]]},properties:{}}]}};f.w.ForjaMap.setData(data);f.w.ForjaMap.set3D(true);const vector=f.creations[0];vector.options.onReady();vector.center={lat:47.1,lon:27.5,zoom:17};vector.options.onError(Error('WebGL context lost'));
 assert.equal(f.w.ForjaMap.mode3d,false);assert(f.$('vector').hidden);assert.equal(vector.destroyed,true);assert.equal(f.base.center.lat,47.1);assert.equal(f.base.zoom,17);assert.equal(f.drawn[0],data.routes);assert.match(f.$('map-status').dataset.error,/WebGL context lost/);
});
check('destroyed frame ignores late readiness',f=>{
 f.w.ForjaMap.set3D(true);const vector=f.creations[0];f.w.ForjaMap.destroy();vector.options.onReady();assert.equal(vector.destroyed,true);assert.equal(f.base.removed,true);assert.equal(f.w.ForjaMap.mode3d,false);
});
function rendererFixture(mode='normal'){
 const dom=new JSDOM('<div id="vector"></div>',{runScripts:'outside-only'}),w=dom.window,timers=new Map(),errors=[],events={},maps=[],sources=new Map();let timerId=0,ready=0;
 w.matchMedia=()=>({matches:false});w.setTimeout=fn=>{timers.set(++timerId,fn);return timerId;};w.clearTimeout=id=>timers.delete(id);
 class MapStub{
  constructor(){if(mode==='constructor')throw Error('Failed to initialize WebGL');this.removed=false;maps.push(this);}
  addControl(){}on(name,callback){events[name]=callback;return this;}getStyle(){return {layers:[]};}
  addSource(id){sources.set(id,{setData(){}});}getSource(id){return sources.get(id);}addLayer(){if(mode==='layer')throw Error('Layer setup failed');}
  getCenter(){return {lat:44.4,lng:26.1};}getZoom(){return 16;}getCanvas(){return {style:{}};}queryRenderedFeatures(){return[];}
  setLayoutProperty(){}easeTo(){}flyTo(){}resize(){}remove(){this.removed=true;}
 }
 w.maplibregl={Map:MapStub,NavigationControl:class{}};w.eval(fs.readFileSync(path.join(server,'map-renderer.js.txt'),'utf8'));
 return {w,timers,errors,events,maps,create(){return w.ForjaMapRenderer.create('vector',{onError:e=>errors.push(e),onReady:()=>ready++});},get ready(){return ready;},close(){dom.window.close();}};
}
{
 const f=rendererFixture('constructor');try{assert.throws(()=>f.create(),/Failed to initialize WebGL/);assert.equal(f.timers.size,0);checks.push('renderer constructor failure clears its deadline and retains the exact cause');}finally{f.close();}
}
{
 const f=rendererFixture();try{const renderer=f.create();assert.equal(f.timers.size,1);[...f.timers.values()][0]();assert.match(f.errors[0].message,/timed out/);f.events.load();assert.equal(f.ready,0);assert.equal(renderer.ready,false);renderer.destroy();checks.push('renderer timeout rejects a late load instead of reviving failed 3D');}finally{f.close();}
}
{
 const f=rendererFixture('layer');try{const renderer=f.create();f.events.load();assert.equal(f.ready,0);assert.match(f.errors[0].message,/Layer setup failed/);assert.equal(renderer.ready,false);assert.equal(f.timers.size,0);renderer.destroy();checks.push('layer setup exceptions reach fallback instead of silently losing the watchdog');}finally{f.close();}
}
{
 const f=rendererFixture();try{const renderer=f.create();f.events.load();assert.equal(f.ready,1);assert.equal(renderer.ready,true);assert.equal(renderer.getCenter().zoom,16);f.events.webglcontextlost();assert.match(f.errors[0].message,/WebGL context lost/);assert.equal(renderer.ready,false);assert.equal(renderer.set3D(true),false);f.events.load();assert.equal(f.ready,1);renderer.destroy();checks.push('post-ready WebGL loss invalidates readiness and reports recoverable failure');}finally{f.close();}
}
console.log(JSON.stringify({passed:checks.length,checks,live_tiles:false,webgl_render:false},null,2));
