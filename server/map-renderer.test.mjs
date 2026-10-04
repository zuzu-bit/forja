import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import {createRequire} from 'node:module';
const source=fs.readFileSync(new URL('./map-renderer.js.txt',import.meta.url),'utf8');
function fixture({supported=null,reduced=false}={}){
 const timers=new Map(),maps=[];let now=0,nextTimer=0;
 function advance(ms){const end=now+ms;while(true){const next=[...timers].filter(([,t])=>t.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];if(!next)break;now=next[1].at;timers.delete(next[0]);next[1].fn();}now=end;}
 class MockMap{
  constructor(options){this.options=options;this.events={};this.sources={};this.layers={};this.loaded=false;maps.push(this);}
  addControl(){} on(name,callback){this.events[name]=callback;return this;} getStyle(){return {layers:[{id:'label',type:'symbol'}]};}
  addSource(id,data){this.sources[id]={...data,setData(value){this.data=value;}};} getSource(id){return this.sources[id];}
  addLayer(layer,before){this.layers[layer.id]={...layer,before};} setLayoutProperty(id,key,value){this.layers[id].layout[key]=value;}
  easeTo(value){this.lastEase=value;} flyTo(value){this.lastFly=value;} fitBounds(){} getCenter(){return {lat:44, lng:26};} getZoom(){return 15;}
  resize(){this.resized=true;} remove(){this.removed=true;} isStyleLoaded(){return this.loaded;} queryRenderedFeatures(){return this.picks||[];}
  setStyle(url,options){(this.retries??=[]).push({url,options});if(this.retryError)throw this.retryError;this.loaded=false;this.sources={};this.layers={};}
  getCanvas(){return {style:{}};} emit(name,event={}){if(name==='load')this.loaded=true;this.events[name]?.(event);}
 }
 const global={maplibregl:{...(supported===null?{}:{supported:()=>supported}),Map:MockMap,NavigationControl:class{},LngLatBounds:class{extend(){}}},matchMedia:()=>({matches:reduced}),setTimeout(fn,ms){const id=++nextTimer;timers.set(id,{fn,at:now+ms});return id;},clearTimeout(id){timers.delete(id);}};
 vm.runInNewContext(source,global);return {api:global.ForjaMapRenderer,maps,timers,advance,global};
}
test('2D/3D uses genuine vector building heights and one consistent geographic data set',()=>{
 const f=fixture();let ready=false;const renderer=f.api.create('map',{onReady(){ready=true;}}),map=f.maps[0];
 assert.equal(renderer.set3D(true),false);assert.equal(map.options.style,'https://tiles.openfreemap.org/styles/liberty');
 const routes={type:'FeatureCollection',features:[{type:'Feature',geometry:{type:'LineString',coordinates:[[26,44],[26.1,44.1]]},properties:{id:'observed'}}]};renderer.setData({routes});map.emit('load');
 assert.equal(ready,true);assert.equal(map.sources['forja-routes'].data,routes);assert.equal(map.sources['forja-buildings'].url,'https://tiles.openfreemap.org/planet');
 const building=map.layers['forja-buildings-3d'];assert.equal(building['source-layer'],'building');assert.match(JSON.stringify(building.paint['fill-extrusion-height']),/render_height/);assert.equal(building.layout.visibility,'none');
 assert.equal(renderer.set3D(true),true);assert.equal(building.layout.visibility,'visible');assert.equal(map.lastEase.pitch,55);assert.equal(map.sources['forja-routes'].data,routes);
 renderer.set3D(false);assert.equal(map.lastEase.pitch,0);assert.equal(building.layout.visibility,'none');
});
test('unsupported WebGL and unavailable style produce an honest fallback without a ready 3D mode',()=>{
 assert.throws(()=>fixture({supported:false}).api.create('map'),/nu este disponibil/);
 const f=fixture();let errors=0,ready=0;const renderer=f.api.create('map',{onError(){errors++;},onReady(){ready++;}});f.advance(20000);f.maps[0].emit('load');
 assert.equal(errors,1);assert.equal(ready,0);assert.equal(renderer.ready,false);assert.equal(renderer.set3D(true),false);
});
test('an early resource error can recover without retry or fallback',()=>{
 const f=fixture();let errors=0,ready=0;const renderer=f.api.create('map',{onError(){errors++;},onReady(){ready++;}}),map=f.maps[0];
 const routes={type:'FeatureCollection',features:[]};renderer.setData({routes});
 map.emit('error',{sourceId:'openmaptiles',error:Error('temporary tile failure')});f.advance(500);map.emit('load');map.emit('load');f.advance(30000);
 assert.equal(errors,0);assert.equal(ready,1);assert.equal(renderer.ready,true);assert.equal(renderer.set3D(true),true);assert.equal(map.sources['forja-routes'].data,routes);assert.equal(map.retries,undefined);assert.equal(f.timers.size,0);
});
test('root style errors coalesce into one delayed retry and can still recover',()=>{
 const f=fixture();let errors=0,ready=0;const renderer=f.api.create('map',{onError(){errors++;},onReady(){ready++;}}),map=f.maps[0];
 const event={error:{url:map.options.style,status:503}};map.emit('error',event);f.advance(500);map.emit('error',event);f.advance(499);assert.equal(map.retries,undefined);f.advance(1);
 assert.equal(map.retries.length,1);assert.equal(map.retries[0].url,map.options.style);assert.equal(map.retries[0].options.diff,false);
 map.emit('error',event);f.advance(500);map.emit('load');map.emit('error',event);f.advance(30000);
 assert.equal(errors,0);assert.equal(ready,1);assert.equal(renderer.ready,true);assert.equal(renderer.set3D(true),true);assert.equal(map.retries.length,1);assert.equal(f.timers.size,0);
});
test('a silent or opaque loading stall gets one retry within the original 20 second budget',()=>{
 for(const withError of [false,true]){
  const f=fixture();let errors=0;const renderer=f.api.create('map',{onError(){errors++;}}),map=f.maps[0];
  if(withError)map.emit('error',{error:Error('Failed to fetch')});f.advance(9999);assert.equal(map.retries,undefined);f.advance(1);assert.equal(map.retries.length,1);
  f.advance(9999);assert.equal(errors,0);f.advance(1);assert.equal(errors,1);map.emit('load');f.advance(30000);
  assert.equal(map.retries.length,1);assert.equal(errors,1);assert.equal(renderer.ready,false);assert.equal(renderer.set3D(true),false);assert.equal(f.timers.size,0);
 }
});
test('persistent style failure keeps the real journey client in its Leaflet 2D fallback',()=>{
 const f=fixture(),nodes={'social-vector':{hidden:true},'map-dimension':{disabled:true,addEventListener(){}},'map-render-status':{textContent:''}},leaflet={};
 Object.assign(f.global,{window:f.global,$:id=>nodes[id],auth:null,socialUI:{map:leaflet}});
 vm.runInNewContext(fs.readFileSync(new URL('./journey-client.js.txt',import.meta.url),'utf8')+'\nwindow.journey=journeyUI;openJourney();',f.global);
 const map=f.maps[0],renderer=f.global.journey.vector,event={error:{url:map.options.style,status:403}};
 map.emit('error',event);f.advance(1000);map.emit('error',event);f.advance(18999);assert.equal(nodes['map-render-status'].textContent,'');f.advance(1);
 assert.equal(map.retries.length,1);assert.match(nodes['map-render-status'].textContent,/Harta 2D/);assert.equal(nodes['social-vector'].hidden,true);assert.equal(nodes['map-dimension'].disabled,true);assert.equal(f.global.journey.vectorReady,false);assert.equal(f.global.socialUI.map,leaflet);
 map.emit('load');map.emit('error',event);f.advance(30000);assert.equal(renderer.ready,false);assert.equal(renderer.set3D(true),false);assert.equal(map.retries.length,1);assert.equal(f.timers.size,0);
});
test('destroy cancels pending retry and fallback and ignores late map events',()=>{
 for(const afterRetry of [false,true]){
  const f=fixture();let calls=0;const renderer=f.api.create('map',{onError(){calls++;},onReady(){calls++;}}),map=f.maps[0];
  map.emit('error',{error:{url:map.options.style,status:503}});if(afterRetry)f.advance(1000);renderer.destroy();f.advance(30000);map.emit('load');map.emit('error');
  assert.equal(calls,0);assert.equal(renderer.ready,false);assert.equal(map.removed,true);assert.equal(map.retries?.length||0,afterRetry?1:0);assert.equal(f.timers.size,0);
 }
});
test('unrecoverable retry or layer setup exceptions report fallback once',()=>{
 for(const stage of ['retry','layers']){
  const f=fixture();let errors=0,ready=0;const renderer=f.api.create('map',{onError(){errors++;},onReady(){ready++;}}),map=f.maps[0];
  if(stage==='retry'){map.retryError=Error('unavailable context');f.advance(10000);}else{map.addLayer=()=>{throw Error('cannot initialize layers');};map.emit('load');}
  f.advance(30000);map.emit('load');assert.equal(errors,1);assert.equal(ready,0);assert.equal(renderer.ready,false);assert.equal(f.timers.size,0);
 }
});
test('reduced motion and map picks preserve actual coordinates',()=>{
 const f=fixture({reduced:true});let pick;const renderer=f.api.create('map',{onPick(value){pick=value;}}),map=f.maps[0];map.emit('load');renderer.set3D(true);renderer.focus({lat:44.41,lon:26.09});
 assert.equal(map.lastEase.duration,0);assert.equal(map.lastFly.duration,0);
 map.picks=[{geometry:{coordinates:[26.09,44.41]},properties:{kind:'visit',id:'real-visit',name:'Loc observat'}}];map.emit('click',{point:{x:10,y:20}});
 assert.equal(pick.lat,44.41);assert.equal(pick.lon,26.09);assert.equal(pick.id,'real-visit');renderer.destroy();assert.equal(map.removed,true);
});

test('the actual pinned MapLibre exports Map without the removed supported helper',()=>{
 const library=createRequire(import.meta.url)('./vendor/maplibre-5.10.0.js.txt');
 assert.equal(library.getVersion(),'5.10.0');assert.equal(typeof library.Map,'function');assert.equal(library.supported,undefined);
 const f=fixture();assert.doesNotThrow(()=>f.api.create('map'));
});
