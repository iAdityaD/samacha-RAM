// DOM-contract smoke tests. These execute the actual app module, not a second renderer.
// They do not substitute for browser layout, accessibility or service-worker testing.
import test from 'node:test';
import assert from 'node:assert/strict';
import {setImmediate as tick} from 'node:timers/promises';

test('app boots, renders all primary screens, opens source detail and saves an item',async()=>{
  const listeners={};const elements=new Map();
  function element(){return {innerHTML:'',textContent:'',children:[],style:{},classList:{add(){},remove(){}},addEventListener(){},focus(){},setSelectionRange(){},querySelector(){return element();},querySelectorAll(){return [];}};}
  for(const id of ['#app','#modal-root','#toast','#search'])elements.set(id,element());
  const store=new Map([['samacha-welcome','1']]);
  const state={articles:[{id:'a',title:'Test research source',url:'https://example.com/paper',source:'Publisher',source_id:'s',authors:'Test author',published:'2025-01-01',topics:['LLMs'],excerpt:'The source reports research about language models.',minutes:2,starter:true,type:'Paper',access:'Excerpt only',publication:'Not assessed'}],sources:[{id:'s',name:'Source',enabled:true,health:'not_checked',url:'https://example.org/rss'}],saved:[],history:[],missions:[],queries:[],models:[],routes:{},jobs:[],features:{},schedule:{enabled:false},profile:{topics:{LLMs:'Core',NLP:'Near',Hardware:'Adjacent','AI audio':'Adjacent','AI video':'Discovery',Software:'Near'},theme:'light',budget:15,tracking:true,timezone:'Asia/Kolkata',digest_hour:'08:00',local_only:false,cloud_fallback:false}};
  globalThis.document={body:{classList:{toggle(){}},style:{}},querySelector:id=>elements.get(id)||element(),addEventListener:(type,fn)=>listeners[type]=fn};
  globalThis.window={isSecureContext:false,addEventListener(){},scrollTo(){}};
  globalThis.localStorage={setItem:(k,v)=>store.set(k,v),getItem:k=>store.get(k),removeItem:k=>store.delete(k)};
  globalThis.fetch=async(path,options)=>{
    if(path==='/api/save'){const b=JSON.parse(options.body);state.saved=b.active?[{id:b.id,note:''}]:[];}
    return {ok:true,json:async()=>path==='/api/session'?{authenticated:true}:path==='/api/state'?structuredClone(state):{ok:true}};
  };
  await import('../web/app.js');await tick();await tick();
  assert.match(elements.get('#app').innerHTML,/Follow your curiosity/);
  const click=async(dataset)=>{const button={dataset};listeners.click({target:{closest(selector){return selector==='[data-original]'?null:button;},classList:{contains(){return false;}}},preventDefault(){}});await tick();await tick();await tick();};
  for(const [page,heading] of [['models','The right model for the job'],['settings','Make it feel like yours'],['missions','A question to follow'],['explore','Your world, in layers'],['research','Ideas worth a closer read'],['library','Keep the good things']]){
    await click({nav:page});assert.match(elements.get('#app').innerHTML,new RegExp(heading));
  }
  await click({open:'a'});assert.match(elements.get('#modal-root').innerHTML,/Test research source/);assert.match(elements.get('#modal-root').innerHTML,/not established citations/);
  await click({act:'close'});await click({save:'a'});assert.equal(state.saved.length,1);assert.match(elements.get('#app').innerHTML,/Test research source/);
});
