import test from 'node:test';
import assert from 'node:assert/strict';
import {rankArticles,layerOf,readingQueue,relatedTo,safeUrl,escapeHTML,activeMission} from '../web/core.js';
const profile={topics:{LLMs:'Core',NLP:'Near',Hardware:'Adjacent','AI audio':'Muted'}};
const a=(id,topics,date='2025-01-01')=>({id,title:id,topics,published:date,url:'https://example.org/'+id,source_id:'s',source:'Publisher',authors:'Author',excerpt:'Language research',type:'Paper',minutes:3});
test('explicit muted topic wins over core topic',()=>assert.equal(layerOf(a('x',['LLMs','AI audio']),profile),'Muted'));
test('core outranks a newer adjacent item',()=>assert.deepEqual(rankArticles([a('hardware',['Hardware'],'2026-01-01'),a('llm',['LLMs'])],profile).map(x=>x.id),['llm','hardware']));
test('following is chronological and excludes unfollowed sources',()=>{
 const rows=[a('old',['LLMs']),{...a('new',['NLP'],'2026-01-01'),source_id:'other'}];
 assert.deepEqual(rankArticles(rows,profile,{mode:'Following',following:['s']}).map(x=>x.id),['old']);
});
test('all query terms must match',()=>assert.equal(rankArticles([a('hello',['LLMs'])],profile,{query:'hello missing'}).length,0));
test('mission filtering never changes global preferences',()=>{
 const before=JSON.stringify(profile);const rows=rankArticles([a('h',['Hardware']),a('l',['LLMs'])],profile,{mission:{topics:['Hardware']}});
 assert.equal(rows.length,1);assert.equal(rows[0].id,'h');assert.equal(JSON.stringify(profile),before);
});
test('reading queue stays within the selected budget',()=>assert.equal(readingQueue([a('1',[]),a('2',[]),a('3',[])],5).length,1));
test('duplicate canonical URL is not repeated',()=>{const first=a('a',['NLP']);assert.equal(rankArticles([first,{...first,id:'b'}],profile).length,1);});
test('fetched record supersedes a historical starter at the same URL',()=>{const first={...a('a',['NLP']),starter:true};const rows=rankArticles([first,{...first,id:'live',starter:false}],profile);assert.equal(rows.length,1);assert.equal(rows[0].id,'live');});
test('related content explicitly returns shared topics only',()=>{const r=relatedTo(a('a',['NLP']),[a('b',['NLP']),a('c',['Hardware'])]);assert.equal(r.length,1);assert.deepEqual(r[0].topics,['NLP']);});
test('unsafe URLs and HTML are not rendered as active content',()=>{assert.equal(safeUrl('javascript:alert(1)'),'#');assert.equal(safeUrl('https://a:b@example.com'),'#');assert.equal(escapeHTML('<script>'), '&lt;script&gt;');});
test('mission expiry is date-aware',()=>{assert.equal(activeMission({expires:'2026-10-18'},'2026-10-18'),true);assert.equal(activeMission({expires:'2026-10-17'},'2026-10-18'),false);});
