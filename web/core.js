export const layers = ['Core', 'Near', 'Adjacent', 'Discovery', 'Muted'];
export const tasks = {classification:'Classification',summarization:'Summarization',embeddings:'Embeddings',reranking:'Reranking',evidence:'Evidence review',qa:'Questions & answers'};
export const weights = {Core: 4, Near: 3, Adjacent: 2, Discovery: 1, Muted: -999};
export function layerOf(article, profile) {
  const ls = article.topics.map(t => profile.topics[t] || 'Discovery');
  if (ls.includes('Muted')) return 'Muted';
  return ls.sort((a,b) => weights[b]-weights[a])[0] || 'Discovery';
}
export function rankArticles(articles, profile, {query='',kind='All',layer='All',mode='For you',mission=null,following=[],feedback=[],clock=Date.now()}={}) {
  const words=query.toLowerCase().trim().split(/\s+/).filter(Boolean);
  let rows=articles.filter(a => {
    if(layerOf(a,profile)==='Muted') return false;
    if(kind!=='All' && a.type!==kind) return false;
    if(layer!=='All' && layerOf(a,profile)!==layer) return false;
    if(mode==='Following' && !following.includes(a.source_id)) return false;
    if(mission && !a.topics.some(t=>mission.topics.includes(t))) return false;
    const hay=[a.title,a.source,a.authors,...a.topics,a.excerpt].join(' ').toLowerCase();
    return words.every(w=>hay.includes(w));
  });
  const seen=new Set();
  // Prefer a fetched source record over a historical starter for the same URL.
  rows.sort((a,b)=>Number(Boolean(a.starter))-Number(Boolean(b.starter)));
  rows=rows.filter(a=>{const key=a.url.replace(/\/$/,''); if(seen.has(key)) return false; seen.add(key); return true;});
  const inferred=a=>mission?0:feedback.reduce((n,f)=>{
    const age=Math.max(0,(clock-new Date(f.created).getTime())/86400000);
    const sign=f.reason==='useful'?1:f.reason==='less_relevant'?-1:0;
    const match=(f.topics||[]).some(t=>a.topics.includes(t));
    return n+(match?sign*(.65*Math.exp(-age/7)+.35*Math.exp(-age/60)):0)-(f.article_id===a.id&&f.reason==='duplicate'?2:0);
  },0);
  rows.sort((a,b) => (mode==='Following'?0:weights[layerOf(b,profile)]-weights[layerOf(a,profile)]) || (mode==='Following'?0:inferred(b)-inferred(a)) || (b.published||'').localeCompare(a.published||'') || a.id.localeCompare(b.id));
  if(mode!=='Following'){
    const diverse=[];
    while(rows.length){let index=0;if(diverse.length>=2&&diverse.at(-1).source_id===diverse.at(-2).source_id&&rows[0].source_id===diverse.at(-1).source_id){const i=rows.findIndex(a=>a.source_id!==diverse.at(-1).source_id&&layerOf(a,profile)===layerOf(rows[0],profile));if(i>=0)index=i;}diverse.push(rows.splice(index,1)[0]);}
    return diverse;
  }
  return rows;
}
export function readingQueue(articles, minutes) {
  let used=0;
  return articles.filter(a=>{const time=Math.max(1,a.minutes||1); if(used+time>minutes)return false; used+=time; return true;}).slice(0,15);
}
export function activeMission(mission,today=new Date().toISOString().slice(0,10)) {return mission.expires>=today;}
export function relatedTo(article, articles) {
  return articles.filter(a=>a.id!==article.id).map(a=>({article:a,topics:a.topics.filter(t=>article.topics.includes(t))})).filter(x=>x.topics.length).sort((a,b)=>b.topics.length-a.topics.length).slice(0,3);
}
export function escapeHTML(value='') {return String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
export function safeUrl(value) {try{const u=new URL(value);return ['https:','http:'].includes(u.protocol)&&!u.username&&!u.password?u.href:'#';}catch{return '#';}}
