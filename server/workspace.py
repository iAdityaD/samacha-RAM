"""Versioned documents, auditable review, typed connections, and retrieval."""
import hashlib
import json
import math
import re
import subprocess
import sys
import uuid
from datetime import datetime,timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit
from . import network
from .feeds import canonical
from .store import now

STATES={'Supported by primary evidence','Independently corroborated','Single-source report','Disputed','Insufficient evidence','Corrected','Withdrawn'}
RELATIONS={'same_event','follow_up','background','correction','contradiction','citation','supersedes','implementation','dataset','benchmark','topic_similarity'}

def uid(): return str(uuid.uuid4())
def tokens(s): return set(re.findall(r'\w{3,}',s.lower()))

class ArticleParser(HTMLParser):
    def __init__(self):
        super().__init__();self.hidden=0;self.parts=[];self.current=[];self.in_article=0;self.article_parts=[]
    def handle_starttag(self,tag,attrs):
        if tag in ('script','style','nav','footer','header','aside'): self.hidden+=1
        if tag in ('article','main'): self.in_article+=1
        if tag in ('p','h1','h2','h3','li','pre'): self.flush()
    def handle_endtag(self,tag):
        if tag in ('p','h1','h2','h3','li','pre'): self.flush()
        if tag in ('script','style','nav','footer','header','aside'): self.hidden=max(0,self.hidden-1)
        if tag in ('article','main'): self.in_article=max(0,self.in_article-1)
    def handle_data(self,data):
        if not self.hidden:self.current.append(data)
    def flush(self):
        t=re.sub(r'\s+',' ',' '.join(self.current)).strip();self.current=[]
        if len(t)>20:
            self.parts.append(t)
            if self.in_article:self.article_parts.append(t)

class Workspace:
    def __init__(self,app):self.app=app;self.s=app.store
    def get(self,table,id):
        row=next((x for x in self.s.items(table) if x['id']==id),None)
        if row is None:raise ValueError(f'{table} record not found.')
        return row
    def snapshot(self):
        return {k:self.s.items(k) for k in ('documents','claims','reviews','relations','watches','alerts','feedback','annotations','comparisons')}
    def anchors(self,article):
        docs=[d for d in self.s.items('documents') if d['article_id']==article['id'] and d.get('status')=='ready']
        if docs:return docs[-1]['anchors']
        return [{'id':'excerpt','text':article['title']+'\n'+article['excerpt'],'page':None,'version':article['version']}]
    def source(self,article):return '\n\n'.join(x['text'] for x in self.anchors(article))
    def document(self,b):
        a=self.app.article(b['article_id'])
        if not b.get('rights_ack'):raise ValueError('Confirm that you may fetch and store this document for personal reading.')
        url=canonical(b.get('url') or a['url']);fmt=b.get('format','html')
        if fmt not in ('html','pdf'):raise ValueError('Choose HTML or PDF.')
        job={'id':uid(),'kind':'document','status':'queued','article_id':a['id'],'created':now()}
        self.s.put('jobs',job['id'],job)
        def work():
            try:
                job['status']='running';self.s.put('jobs',job['id'],job)
                raw=network.request(url,limit=25*1024**2 if fmt=='pdf' else 3_000_000,timeout=25)
                if self.cancelled(job):return
                digest=hashlib.sha256(raw).hexdigest()
                if fmt=='pdf':
                    if not raw.startswith(b'%PDF-'):raise ValueError('The response is not a PDF.')
                    result=subprocess.run([sys.executable,'-m','server.pdf_extract'],input=raw,capture_output=True,timeout=20)
                    if result.returncode:raise ValueError(result.stderr.decode()[:300])
                    pages=json.loads(result.stdout)
                else:
                    parser=ArticleParser();parser.feed(raw.decode('utf-8',errors='replace'));parser.flush()
                    paragraphs=parser.article_parts or parser.parts
                    if not paragraphs:raise ValueError('No usable article text was extracted.')
                    pages=[{'page':None,'text':'\n\n'.join(paragraphs)}]
                anchors=[]
                for p in pages:
                    for paragraph in re.split(r'\n\s*\n',p['text']):
                        if not paragraph.strip():continue
                        # Bounded paragraph chunks retain exact source text and character positions.
                        for offset in range(0,len(paragraph),2500):
                            t=paragraph[offset:offset+2500]
                            anchors.append({'id':str(len(anchors)+1),'page':p['page'],'offset':offset,'text':t,'version':digest})
                if self.cancelled(job):return
                asset=self.s.root/'documents';asset.mkdir(exist_ok=True)
                if fmt=='pdf':(asset/(digest+'.pdf')).write_bytes(raw)
                doc={'id':digest,'article_id':a['id'],'article_version':a['version'],'version':digest,'url':url,'format':fmt,'bytes':len(raw),'status':'ready','anchors':anchors,'created':now(),'rights':'User confirmed personal storage permission','quality':'Embedded-text extraction; tables/reading order require review' if fmt=='pdf' else 'HTML extraction; completeness requires review'}
                self.s.put('documents',doc['id'],doc);job.update(status='complete',document_id=digest)
            except Exception as error:job.update(status='failed',error=str(error)[:400] if isinstance(error,ValueError) else 'Fetch or document parsing failed; prior content was retained.')
            finally:
                if job.get('status')!='cancelled':job['finished']=now();self.s.put('jobs',job['id'],job)
        self.app.pool.submit(work);return job
    def cancelled(self,job):
        current=self.get('jobs',job['id'])
        if current.get('cancel_requested'):
            job.update(status='cancelled',finished=now());self.s.put('jobs',job['id'],job);return True
        return False
    def reference(self,b):
        a=self.app.article(b['article_id']);quote=str(b.get('quote',''))
        anchor=next((x for x in self.anchors(a) if x['id']==str(b.get('anchor_id','excerpt'))),None)
        if not anchor or len(quote)<8 or quote not in anchor['text']:raise ValueError('Evidence must be an exact passage in the chosen document anchor.')
        return {'article_id':a['id'],'article_version':a['version'],'anchor_id':anchor['id'],'document_version':anchor['version'],'page':anchor.get('page'),'quote':quote,'url':a['url'],'origin':urlsplit(a['url']).hostname}
    def review(self,b):
        claim=self.get('claims',b['claim_id']);status=b.get('status')
        if status not in STATES:raise ValueError('Unknown assessment state.')
        refs=[self.reference(x) for x in b.get('evidence',[])]
        if status!='Insufficient evidence' and not refs:raise ValueError('This assessment needs cited evidence.')
        if status=='Independently corroborated' and len({r['origin'] for r in refs})<2:raise ValueError('Corroboration needs at least two recorded source origins, plus reviewer judgment about independence.')
        note=str(b.get('note','')).strip()
        if len(note)<15:raise ValueError('Explain the assessment, scope, and evidence independence in at least 15 characters.')
        review={'id':uid(),'claim_id':claim['id'],'status':status,'note':note,'evidence':refs,'created':now(),'reviewer':'Workspace owner','method':'Human-entered assessment; not an automated truth guarantee','stale':False}
        self.s.put('reviews',review['id'],review)
        if status in ('Corrected','Withdrawn'):
            for ref in refs:
                self.s.put('alerts',review['id']+ref['article_id'],{'id':review['id']+ref['article_id'],'article_id':ref['article_id'],'type':'correction','message':claim['statement'],'created':now()})
        return review
    def mutate(self,path,b):
        if path=='/api/documents':return self.document(b)
        if path=='/api/documents/delete':
            doc=self.get('documents',b['id']);self.s.delete('documents',doc['id'])
            (self.s.root/'documents'/(doc['id']+'.pdf')).unlink(missing_ok=True)
            for table in ('reviews','relations','annotations','comparisons'):
                for x in self.s.items(table):
                    if doc['version'] in json.dumps(x):x['stale']=True;self.s.put(table,x['id'],x)
            return {'deleted':True}
        if path=='/api/claims':
            statement=str(b.get('statement','')).strip()
            if len(statement)<10 or len(statement)>3000:raise ValueError('Claim must contain 10–3000 characters.')
            claim={'id':uid(),'statement':statement,'article_id':b.get('article_id'),'created':now()};self.s.put('claims',claim['id'],claim);return claim
        if path=='/api/reviews':return self.review(b)
        if path=='/api/relations':
            left=self.app.article(b['from']);right=self.app.article(b['to']);kind=b.get('type')
            if left['id']==right['id'] or kind not in RELATIONS:raise ValueError('Select different items and a supported connection type.')
            evidence=[self.reference(x) for x in b.get('evidence',[])]
            if kind!='topic_similarity' and not evidence:raise ValueError('Non-topic connections require a cited supporting passage.')
            if any(x['article_id'] not in (left['id'],right['id']) for x in evidence):raise ValueError('Connection evidence must belong to one of its endpoints.')
            reason=str(b.get('reason','')).strip()
            if len(reason)<10:raise ValueError('Explain the connection.')
            rel={'id':uid(),'from':left['id'],'to':right['id'],'type':kind,'reason':reason,'evidence':evidence,'created':now(),'reviewer':'Workspace owner','stale':False};self.s.put('relations',rel['id'],rel);return rel
        if path=='/api/relations/delete':self.s.delete('relations',b['id']);return {'deleted':True}
        if path=='/api/annotations':
            ref=self.reference(b);record={'id':uid(),'reference':ref,'note':str(b.get('note',''))[:10000],'created':now(),'stale':False};self.s.put('annotations',record['id'],record);return record
        if path=='/api/watches':
            query=str(b.get('query','')).strip();aliases=b.get('aliases',[])
            if not query or len(query)>100 or not isinstance(aliases,list) or len(aliases)>10:raise ValueError('Enter a watch term and at most ten aliases.')
            record={'id':uid(),'query':query,'aliases':[str(x)[:100] for x in aliases],'created':now()};self.s.put('watches',record['id'],record);self.scan_watches();return record
        if path=='/api/watches/delete':self.s.delete('watches',b['id']);return {'deleted':True}
        if path=='/api/feedback':
            a=self.app.article(b['article_id']);reason=b.get('reason')
            if reason not in ('useful','less_relevant','duplicate','poor_evidence','not_now'):raise ValueError('Unknown feedback reason.')
            if not self.s.profile()['tracking']:raise ValueError('Personalization tracking is paused.')
            record={'id':uid(),'article_id':a['id'],'topics':a['topics'],'reason':reason,'created':now()};self.s.put('feedback',record['id'],record);return record
        if path=='/api/retrieve':return self.retrieve(b)
        if path=='/api/index':return self.index(b)
        if path=='/api/compare':return self.compare(b)
        if path=='/api/jobs/cancel':
            job=self.get('jobs',b['id'])
            if job.get('status') in ('complete','failed','cancelled','interrupted'):return job
            job['cancel_requested']=True;self.s.put('jobs',job['id'],job);return job
        return None
    def scan_watches(self):
        for watch in self.s.items('watches'):
            for a in self.s.items('articles'):
                hay=(a['title']+' '+a['excerpt']).lower()
                if any(re.search(r'(?<!\w)'+re.escape(term.lower())+r'(?!\w)',hay) for term in [watch['query']]+watch['aliases']):
                    id=hashlib.sha256((watch['id']+a['url']+a['version']).encode()).hexdigest()
                    self.s.put('alerts',id,{'id':id,'article_id':a['id'],'watch_id':watch['id'],'type':'watch','message':watch['query']+': '+a['title'],'created':now()})
    def index(self,b):
        ids=b.get('article_ids',[])[:20];count=0
        for id in ids:
            a=self.app.article(id);out=self.app.router.run('embeddings',self.source(a)[:24000]);v=out['result']['vectors'][0]
            self.s.put('vectors',id,{'id':id,'article_id':id,'version':a['version'],'model':out['model'],'provider':out['provider'],'vector':v});count+=1
        return {'indexed':count}
    def retrieve(self,b):
        query=str(b.get('query','')).strip()[:2000]
        if not query:raise ValueError('Enter a query.')
        rows=[];semantic=b.get('semantic',False);qvec=None;model=None;provider=None
        if semantic:
            out=self.app.router.run('embeddings',query);qvec=out['result']['vectors'][0];model=out['model'];provider=out['provider']
        for a in self.s.items('articles'):
            if any(self.s.profile()['topics'].get(t)=='Muted' for t in a['topics']):continue
            if semantic:
                v=next((x for x in self.s.items('vectors') if x['article_id']==a['id'] and x['version']==a['version'] and x['model']==model and x['provider']==provider),None)
                if not v or len(v['vector'])!=len(qvec):continue
                den=math.sqrt(sum(x*x for x in qvec)*sum(x*x for x in v['vector']))
                score=sum(x*y for x,y in zip(qvec,v['vector']))/den if den else 0
            else:score=len(tokens(query)&tokens(a['title']+' '+self.source(a)))/max(1,len(tokens(query)))
            if score>0:rows.append({'article_id':a['id'],'score':score})
        rows.sort(key=lambda x:x['score'],reverse=True);rows=rows[:10]
        if b.get('rerank'):
            for row in rows[:5]:
                out=self.app.router.run('reranking',self.source(self.app.article(row['article_id']))[:24000],query)
                if 'score' not in out['result']:raise ValueError('Selected reranker lacks a normalized score contract; configure a compatible route.')
                row['score']=out['result']['score']
            rows.sort(key=lambda x:x['score'],reverse=True)
        return {'results':rows,'mode':'semantic' if semantic else 'lexical','model':model}
    def compare(self,b):
        ids=list(dict.fromkeys(b.get('article_ids',[])))[:6]
        if len(ids)<2:raise ValueError('Select 2–6 research records.')
        cells=b.get('cells',[])
        checked=[]
        for cell in cells:
            if cell.get('article_id') not in ids:raise ValueError('Comparison cell is outside the selected papers.')
            checked.append({'field':str(cell.get('field',''))[:100],'value':str(cell.get('value',''))[:2000],'evidence':self.reference(cell)})
        record={'id':uid(),'article_ids':ids,'cells':checked,'created':now(),'notice':'Missing fields remain unknown. No automatic winner; benchmark setups may differ.'};self.s.put('comparisons',record['id'],record);return record

def bibliography(articles,fmt):
    def clean(x):return re.sub(r'[{}\\\n\r]',' ',str(x))
    if fmt=='bibtex':return '\n\n'.join('@misc{samacha_'+clean(a['id'])+',\n  title={'+clean(a['title'])+'},\n  author={'+clean(a.get('authors',''))+'},\n  url={'+clean(a['url'])+'}\n}' for a in articles)
    if fmt=='ris':return '\n'.join('TY  - GEN\nTI  - '+clean(a['title'])+'\nAU  - '+clean(a.get('authors',''))+'\nUR  - '+clean(a['url'])+'\nER  -\n' for a in articles)
    raise ValueError('Choose bibtex or ris.')
