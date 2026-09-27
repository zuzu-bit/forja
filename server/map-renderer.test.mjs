import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import {createRequire} from 'node:module';
const source=fs.readFileSync(new URL('./map-renderer.js.txt',import.meta.url),'utf8');
function fixture({supported=null,reduced=false}={}){
 const timers=[],maps=[];
 class Map{
  constructor(options){this.options=options;this.events={};this.sources={};this.layers={};this.loaded=false;maps.push(this);}
  addControl(){} on(name,callback){this.events[name]=callback;return this;} getStyle(){return {layers:[{id:'label',type:'symbol'}]};}
  addSource(id,data){this.sources[id]={...data,setData(value){this.data=value;}};} getSource(id){return this.sources[id];}
  addLayer(layer,before){this.layers[layer.id]={...layer,before};} setLayoutProperty(id,key,value){this.layers[id].layout[key]=value;}
  easeTo(value){this.lastEase=value;} flyTo(value){this.lastFly=value;} fitBounds(){} getCenter(){return {lat:44, lng:26};} getZoom(){return 15;}
  resize(){this.resized=true;} remove(){this.removed=true;} isStyleLoaded(){return this.loaded;} queryRenderedFeatures(){return this.picks||[];}
  getCanvas(){return {style:{}};} emit(name,event={}){if(name==='load')this.loaded=true;this.events[name]?.(event);}
 }
 const global={maplibregl:{...(supported===null?{}:{supported:()=>supported}),Map,NavigationControl:class{},LngLatBounds:class{extend(){}}},matchMedia:()=>({matches:reduced}),setTimeout(fn){timers.push(fn);return timers.length;},clearTimeout(){}};
 vm.runInNewContext(source,global);return {api:global.ForjaMapRenderer,maps,timers};
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
 const f=fixture();let errors=0,ready=0;const renderer=f.api.create('map',{onError(){errors++;},onReady(){ready++;}});f.timers[0]();f.maps[0].emit('load');
 assert.equal(errors,1);assert.equal(ready,0);assert.equal(renderer.ready,false);assert.equal(renderer.set3D(true),false);
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
