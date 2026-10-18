import hashlib
import html
import json
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from urllib.parse import urlsplit, urlunsplit, parse_qsl, urlencode
from . import network
from .store import now

SOURCES = [
    {'id':'arxiv-ai','name':'arXiv · AI','url':'https://rss.arxiv.org/rss/cs.AI','kind':'Paper','topic':'LLMs','color':'#b34c47'},
    {'id':'arxiv-cl','name':'arXiv · Language','url':'https://rss.arxiv.org/rss/cs.CL','kind':'Paper','topic':'NLP','color':'#b34c47'},
    {'id':'hf','name':'Hugging Face','url':'https://huggingface.co/blog/feed.xml','kind':'Blog','topic':'LLMs','color':'#d3a640'},
    {'id':'pytorch','name':'PyTorch','url':'https://pytorch.org/blog/feed.xml','kind':'Blog','topic':'Software','color':'#d46b45'},
    {'id':'google','name':'Google Research','url':'https://research.google/blog/rss/','kind':'Blog','topic':'LLMs','color':'#568578'},
]


class Text(HTMLParser):
    def __init__(self):
        super().__init__(); self.parts=[]; self.hidden=0
    def handle_starttag(self, tag, attrs):
        if tag in ('script','style'): self.hidden+=1
        if tag in ('p','div','br','li'): self.parts.append(' ')
    def handle_endtag(self, tag):
        if tag in ('script','style'): self.hidden=max(0,self.hidden-1)
    def handle_data(self, data):
        if not self.hidden: self.parts.append(data)


def text(value):
    p=Text(); p.feed(value or '')
    return re.sub(r'\s+', ' ', html.unescape(' '.join(p.parts))).strip()


def canonical(url):
    p=urlsplit(url)
    if p.scheme not in ('http','https') or not p.hostname or p.username or p.password:
        raise ValueError('Article URL must be HTTP(S) without embedded credentials.')
    query=urlencode([(k,v) for k,v in parse_qsl(p.query) if not k.lower().startswith('utm_') and k not in ('fbclid','gclid')])
    return urlunsplit((p.scheme,p.netloc.lower(),p.path.rstrip('/') or '/',query,''))


def topics_for(value, fallback):
    v=value.lower(); result=[]
    for topic, tokens in {'LLMs':['language model','llm','transformer','retrieval','reasoning'], 'NLP':['language','translation','nlp','text'], 'AI audio':['speech','audio','voice'], 'AI video':['video','diffusion','vision'], 'Hardware':['gpu','chip','hardware','cuda','accelerator'], 'Software':['code','software','python','pytorch','database']}.items():
        if any(x in v for x in tokens): result.append(topic)
    return result or [fallback]


def date(value):
    if not value: return None
    try:
        return datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(timezone.utc).isoformat()
    except (ValueError,TypeError):
        try: return parsedate_to_datetime(value).astimezone(timezone.utc).isoformat()
        except (ValueError,TypeError,AttributeError): return None


def parse_feed(data, source):
    if data.lstrip().startswith(b'{'):
        obj=json.loads(data)
        items=obj.get('items',[])
        if not isinstance(items,list) or not items: raise ValueError('JSON Feed contains no items.')
        # Normalize JSON Feed into the same bounded entry extractor.
        root=ET.Element('rss'); channel=ET.SubElement(root,'channel')
        for item in items[:60]:
            if not isinstance(item,dict): continue
            entry=ET.SubElement(channel,'item')
            for tag,value in {'title':item.get('title',''),'link':item.get('url') or item.get('external_url',''),'description':item.get('content_text') or item.get('content_html') or item.get('summary',''),'pubDate':item.get('date_published','')}.items():
                ET.SubElement(entry,tag).text=str(value)
        data=ET.tostring(root)
    if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
        raise ValueError('DTD/entity declarations are not accepted.')
    root=ET.fromstring(data)
    local=lambda e: e.tag.rsplit('}',1)[-1]
    entries=[x for x in root.iter() if local(x) in ('item','entry')][:60]
    if not entries: raise ValueError('Feed contains no recognized entries.')
    result=[]
    for entry in entries:
        fields={}; links=[]
        for e in entry:
            name=local(e)
            if name=='link': links.append((e.attrib.get('rel','alternate'),e.attrib.get('href') or e.text or ''))
            if e.text: fields[name]=e.text
        title=text(fields.get('title',''))[:500]
        url=next((v for rel,v in links if rel=='alternate'), '')
        try: url=canonical(url)
        except ValueError: continue
        if not title: continue
        description=text(fields.get('description') or fields.get('summary') or fields.get('encoded') or fields.get('content') or '')[:16000]
        authors=fields.get('creator') or fields.get('author') or source['name']
        for child in entry:
            if local(child)=='author':
                authors=' '.join(x.text or '' for x in child.iter() if local(x)=='name') or authors
        result.append({'id':hashlib.sha256(url.encode()).hexdigest()[:20],'title':title,'url':url,'source_id':source['id'],'source':source['name'],'type':source['kind'],'topics':topics_for(title+' '+description,source['topic']),'excerpt':description,'authors':text(authors)[:500], 'published':date(fields.get('pubDate') or fields.get('published') or fields.get('date') or fields.get('updated')),'fetched':now(),'version':hashlib.sha256((title+description).encode()).hexdigest()[:16],'evidence':'Not assessed','access':'Feed excerpt only','publication':'arXiv record · peer review not assessed' if source['kind']=='Paper' else 'Publisher feed','starter':False,'minutes':max(1,round(len(description.split())/200)),'color':source.get('color','#719485')})
    return result


def refresh(store):
    results=[]
    for source in store.items('sources'):
        if not source.get('enabled',True): continue
        status={**source,'checked':now()}
        try:
            articles=parse_feed(network.request(source['url']),source)
            old={a['id']:a for a in store.items('articles')}
            for a in articles:
                if a['id'] in old and old[a['id']]['version']==a['version']:
                    for key in ('classification_version','classification_provider','classification_notice'):
                        if key in old[a['id']]: a[key]=old[a['id']][key]
                    if old[a['id']].get('classification_version')==a['version']:
                        a['topics']=old[a['id']]['topics']
                if a['id'] in old and old[a['id']]['version']!=a['version']:
                    a['previous_version']=old[a['id']]['version']
                    a['change']='Feed content changed; significance not assessed'
                store.put('articles',a['id'],a)
            status.update(health='healthy',count=len(articles),error=None,last_success=now())
        except Exception:
            status.update(health='unavailable',error='Could not refresh this source. Existing records were kept.')
        store.put('sources',source['id'],status); results.append(status)
    return results


def seed(store):
    if not store.items('sources'):
        for s in SOURCES: store.put('sources',s['id'],{**s,'enabled':True,'health':'not_checked','rights':'Metadata/feed excerpts only; original publisher access remains separate.'})
    if store.setting('seeded'): return
    papers=[
        ('1706.03762','Attention Is All You Need','2017-06-12','Vaswani et al.',['LLMs','NLP'],'The authors introduce the Transformer, an architecture based on attention rather than recurrent or convolutional layers. The paper reports machine translation experiments. Read the original for evaluation conditions.'),
        ('2005.11401','Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks','2020-05-22','Lewis et al.',['LLMs','NLP'],'The authors combine a parametric generation model with retrieved non-parametric information. The paper examines retrieval-augmented generation on knowledge-intensive NLP tasks.'),
        ('2106.09685','LoRA: Low-Rank Adaptation of Large Language Models','2021-06-17','Hu et al.',['LLMs','Hardware'],'The authors describe an adaptation method that freezes pretrained weights and adds trainable low-rank matrices. Efficiency and quality claims depend on the reported experimental setting.'),
        ('2303.08774','GPT-4 Technical Report','2023-03-15','OpenAI',['LLMs','AI video'],'A technical report describing a large multimodal model and author-reported evaluations. A technical report and a benchmark claim do not establish independent verification.'),
    ]
    for id,title,published,author,topics,excerpt in papers:
        store.put('articles',id,{'id':id,'title':title,'url':'https://arxiv.org/abs/'+id,'source_id':'starter','source':'arXiv · reading collection','type':'Paper','topics':topics,'excerpt':excerpt,'authors':author,'published':published+'T00:00:00+00:00','fetched':None,'version':'starter-1','evidence':'Not assessed','access':'Editorial overview · not full text','publication':'arXiv record · peer review not assessed','starter':True,'minutes':2,'color':'#b34c47'})
    store.set_setting('seeded',True)
