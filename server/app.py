"""Run: python -m server.app. Default loopback-only, single-owner app host."""
import argparse
import hashlib
import hmac
import json
import mimetypes
import os
import secrets
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, unquote, parse_qs
from zoneinfo import ZoneInfo
from .feeds import canonical, refresh, seed
from .models import Router, ModelError, TASKS
from .network import NetworkError, resolve
from .store import Store, now

WEB=Path(__file__).resolve().parent.parent/'web'


class App:
    def __init__(self,root,password=''):
        self.store=Store(root); seed(self.store); self.router=Router(self.store)
        self.password=password; self.sessions={}; self.session_lock=threading.Lock()
        self.pool=ThreadPoolExecutor(max_workers=2); self.refresh_lock=threading.Lock(); self.upload_lock=threading.Lock(); self.stopped=threading.Event()
        # A killed process cannot leave jobs falsely "running" forever.
        for job in self.store.items('jobs'):
            if job.get('status') in ('queued','running','downloading'):
                self.store.put('jobs',job['id'],{**job,'status':'interrupted','error':'The app host stopped before this job finished. Start a new request.'})

    def state(self):
        return {'articles':self.store.items('articles'),'sources':self.store.items('sources'),'profile':self.store.profile(),'saved':self.store.saved(),'history':self.store.history(),'missions':self.store.items('missions'),'queries':self.store.items('queries'),'models':self.router.public_providers(),'routes':self.store.setting('routes',{}),'jobs':self.store.items('jobs')[-20:],'digest':self.store.setting('digest'),'features':{'transformers_installed':__import__('importlib.util',fromlist=['find_spec']).find_spec('transformers') is not None,'host_local_inference':True,'on_phone_inference':False},'schedule':self.store.setting('schedule',{'enabled':False}),'last_refresh':self.store.setting('last_refresh')}

    def start_refresh(self):
        if not self.refresh_lock.acquire(blocking=False): raise ValueError('A source refresh is already running.')
        job={'id':str(uuid.uuid4()),'kind':'refresh','status':'running','created':now()}
        self.store.put('jobs',job['id'],job)
        def work():
            try:
                sources=refresh(self.store)
                route=self.store.setting('routes',{}).get('classification',{}).get('primary','builtin')
                if route!='builtin':
                    # A strict batch bound prevents an expensive whole-library inference sweep.
                    candidates=[a for a in self.store.items('articles') if not a['starter'] and a.get('classification_version')!=a['version']][:10]
                    for article in candidates:
                        try:
                            out=self.router.run('classification',(article['title']+'\n'+article['excerpt'])[:24000])
                            article.update(topics=out['result']['labels'],classification_version=article['version'],classification_provider=out['provider'])
                            self.store.put('articles',article['id'],article)
                        except ModelError:
                            article['classification_notice']='Configured classifier failed; source/keyword topics retained.'
                            self.store.put('articles',article['id'],article)
                job.update(status='complete',healthy=sum(s.get('health')=='healthy' for s in sources))
                self.store.set_setting('last_refresh',now())
            except Exception: job.update(status='failed',error='Refresh failed. Existing data is intact.')
            finally:
                job['finished']=now(); self.store.put('jobs',job['id'],job); self.refresh_lock.release()
        self.pool.submit(work)
        return job

    def make_digest(self):
        p=self.store.profile(); articles=self.store.items('articles')
        weights={'Core':4,'Near':3,'Adjacent':2,'Discovery':1,'Muted':-999}
        def score(a):
            values=[weights.get(p['topics'].get(t,'Discovery'),1) for t in a['topics']]
            return -999 if -999 in values else max(values or [1])
        candidates=[a for a in articles if score(a)>0]
        # Exact URL deduplication, preferring freshly gathered source records.
        unique={}
        for a in sorted(candidates,key=lambda a:not a.get('starter',False)):
            unique[a['url']]=a
        candidates=list(unique.values())
        candidates.sort(key=lambda a:(score(a),a.get('published') or ''),reverse=True)
        selected=[]; used=0
        for a in candidates:
            if used+a['minutes']<=p['budget']:
                selected.append(a['id']); used+=a['minutes']
        digest={'date':now(),'article_ids':selected[:15],'minutes':used,'budget':p['budget'],'notice':'Estimated excerpt reading time. Historical starter records are not daily news.'}
        self.store.set_setting('digest',digest)
        return digest

    def scheduler(self):
        while not self.stopped.wait(30):
            try:
                if not self.store.setting('schedule',{}).get('enabled'): continue
                p=self.store.profile(); current=datetime.now(ZoneInfo(p['timezone']))
                key=current.date().isoformat()
                if current.strftime('%H:%M')>=p['digest_hour'] and self.store.setting('last_digest_day')!=key:
                    try: self.start_refresh()
                    except ValueError: continue
                    # Wait for the bounded refresh to finish before building a digest.
                    with self.refresh_lock: self.make_digest()
                    self.store.set_setting('last_digest_day',key)
            except Exception:
                # Keep scheduler alive without printing content or credentials.
                continue

    def mutation(self,path,b):
        s=self.store
        if path=='/api/profile':
            old=s.profile(); allowed={'topics','budget','tracking','local_only','cloud_fallback','theme','digest_hour','timezone'}
            if not isinstance(b,dict) or not set(b)<=allowed: raise ValueError('Unknown preference.')
            p={**old,**b}
            if not isinstance(p['topics'],dict) or len(p['topics'])>40 or any(v not in ('Core','Near','Adjacent','Discovery','Muted') for v in p['topics'].values()): raise ValueError('Invalid topic layers.')
            if not isinstance(p['budget'],int) or not 1<=p['budget']<=120: raise ValueError('Reading budget must be 1–120 minutes.')
            if not all(isinstance(p[x],bool) for x in ('tracking','local_only','cloud_fallback')): raise ValueError('Privacy preferences must be boolean.')
            if p['theme'] not in ('light','dark'): raise ValueError('Invalid theme.')
            import re
            if not re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d',p['digest_hour']): raise ValueError('Invalid daily time.')
            try: ZoneInfo(p['timezone'])
            except Exception: raise ValueError('Invalid IANA timezone.')
            s.set_setting('profile',p); return p
        if path=='/api/save':
            self.article(b['id']); s.save(b['id'],bool(b.get('active',True)),b.get('note')); return {'saved':s.saved()}
        if path=='/api/history':
            self.article(b['id']); s.event(b['id'],b['kind']); return {'history':s.history()}
        if path=='/api/history/clear':
            s.clear_history(); return {'cleared':True}
        if path=='/api/mission':
            title=str(b.get('title','')).strip()[:120]; topics=b.get('topics',[])
            if not title or not isinstance(topics,list) or not topics or not all(isinstance(t,str) and t in s.profile()['topics'] for t in topics): raise ValueError('Choose a mission name and known topics.')
            expires=str(b.get('expires',''))
            try: datetime.strptime(expires,'%Y-%m-%d')
            except ValueError: raise ValueError('Choose an expiry date.')
            m={'id':str(uuid.uuid4()),'title':title,'topics':topics,'expires':expires,'created':now()}; s.put('missions',m['id'],m); return m
        if path=='/api/mission/delete': s.delete('missions',b['id']); return {'deleted':True}
        if path=='/api/query':
            query=str(b.get('query','')).strip()[:300]
            if not query: raise ValueError('Enter a search query.')
            q={'id':str(uuid.uuid4()),'query':query}; s.put('queries',q['id'],q); return q
        if path=='/api/query/delete': s.delete('queries',b['id']); return {'deleted':True}
        if path=='/api/source':
            if b.get('id'):
                old=next((x for x in s.items('sources') if x['id']==b['id']),None)
                if not old: raise ValueError('Source not found.')
                old['enabled']=bool(b['enabled']); s.put('sources',old['id'],old); return old
            url=canonical(str(b.get('url','')))
            resolve(url)
            if any(x['url']==url for x in s.items('sources')): raise ValueError('Already subscribed to this feed.')
            name=str(b.get('name','')).strip()[:100]
            if not name: raise ValueError('Source name is required.')
            kind=b.get('kind') or 'Blog'
            if kind not in ('News','Blog','Paper'): raise ValueError('Unknown source content type.')
            source={'id':str(uuid.uuid4()),'name':name,'url':url,'kind':kind,'topic':b.get('topic','Software'),'enabled':True,'health':'not_checked','color':'#648f78','rights':'User-added feed; excerpts only. Check publisher terms before further reuse.'}
            s.put('sources',source['id'],source); return source
        if path=='/api/opml/preview':
            raw=str(b.get('xml',''))
            if '<!DOCTYPE' in raw.upper() or '<!ENTITY' in raw.upper(): raise ValueError('DTD/entity declarations are not accepted.')
            root=ET.fromstring(raw)
            found=[]
            for node in root.iter('outline'):
                if len(found)>=100: break
                if node.get('xmlUrl'):
                    try: url=canonical(node.get('xmlUrl'))
                    except ValueError: continue
                    found.append({'name':node.get('title') or node.get('text') or url,'url':url})
            return {'sources':found}
        if path=='/api/refresh': return self.start_refresh()
        if path=='/api/digest': return self.make_digest()
        if path=='/api/schedule':
            s.set_setting('schedule',{'enabled':bool(b.get('enabled'))}); return s.setting('schedule')
        if path=='/api/models': return self.router.save_provider(b)
        if path=='/api/models/delete':
            id=b['id']; s.delete('providers',id)
            routes=s.setting('routes',{})
            for r in routes.values():
                if r.get('primary')==id: r['primary']='builtin'
                if r.get('fallback')==id: r['fallback']='none'
            s.set_setting('routes',routes); return {'deleted':True}
        if path=='/api/routes': return self.router.configure(b)
        if path=='/api/models/test': return self.router.test(b['id'])
        if path=='/api/models/download': return self.router.download(b.get('repo',''),b.get('revision',''),b.get('license_ack',False))
        if path=='/api/run':
            if b.get('article_id'):
                a=self.article(b['article_id']); text=a['title']+'\n'+a['excerpt']
            else: text=str(b.get('text',''))
            result=self.router.run(b.get('task'),text,str(b.get('question','')),b.get('provider_id'))
            if b.get('article_id'):
                result.update(article_id=a['id'],article_version=a['version'],access=a['access'])
                if s.profile()['tracking']:
                    s.put('outputs',str(uuid.uuid4()),result)
            return result
        raise ValueError('Unknown operation.')

    def article(self,id):
        a=next((a for a in self.store.items('articles') if a['id']==id),None)
        if not a: raise ValueError('Article not found.')
        return a

    def handler(self):
        app=self
        class Handler(BaseHTTPRequestHandler):
            server_version='SamachaRAM/0.1'
            def log_message(self,*args): pass
            def json(self,status,value,headers=None):
                self.send_response(status); self.send_header('Content-Type','application/json; charset=utf-8'); self.send_header('Cache-Control','no-store'); self.security()
                for k,v in (headers or {}).items(): self.send_header(k,v)
                self.end_headers(); self.wfile.write(json.dumps(value).encode())
            def security(self):
                self.send_header('X-Content-Type-Options','nosniff'); self.send_header('Referrer-Policy','no-referrer'); self.send_header('X-Frame-Options','DENY')
                self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")
            def sid(self):
                try:
                    cookie=SimpleCookie(self.headers.get('Cookie','')); value=cookie.get('samacha_session'); token=value.value if value else ''
                    with app.session_lock: expires=app.sessions.get(token,0)
                    return token if expires>time.time() else None
                except Exception: return None
            def check_host(self):
                # Reject hostile Host headers (DNS rebinding) unless operator explicitly lists a trusted host.
                host=urlsplit('http://'+self.headers.get('Host','')).hostname or ''
                allowed={'localhost','127.0.0.1','::1'}|set(os.environ.get('SAMACHA_ALLOWED_HOSTS','').lower().split(','))
                if host not in allowed:
                    self.json(403,{'error':'Host is not allowed. Configure SAMACHA_ALLOWED_HOSTS explicitly.'}); return False
                return True
            def do_GET(self):
                if not self.check_host(): return
                path=urlsplit(self.path).path
                if path=='/api/session':
                    if self.sid(): self.json(200,{'authenticated':True}); return
                    if app.password: self.json(200,{'authenticated':False,'password_required':True}); return
                    self.new_session(); return
                if path.startswith('/api/'):
                    if not self.sid(): self.json(401,{'error':'Sign in to this app host.'}); return
                    if path=='/api/state': self.json(200,app.state())
                    elif path=='/api/bundles': self.json(200,{'bundles':app.router.bundles()})
                    elif path=='/api/export':
                        state=app.state(); state.pop('models',None)
                        self.json(200,state,{'Content-Disposition':'attachment; filename="samacha-library.json"'})
                    else: self.json(404,{'error':'Not found.'})
                    return
                target=(WEB/unquote(path).lstrip('/')).resolve() if path!='/' else WEB/'index.html'
                if not target.is_relative_to(WEB) or not target.is_file(): self.json(404,{'error':'Not found.'}); return
                self.send_response(200); self.send_header('Content-Type',mimetypes.guess_type(str(target))[0] or 'application/octet-stream'); self.send_header('Cache-Control','no-cache'); self.security(); self.end_headers(); self.wfile.write(target.read_bytes())
            def new_session(self):
                token=secrets.token_urlsafe(32)
                with app.session_lock:
                    app.sessions={k:v for k,v in app.sessions.items() if v>time.time()}
                    app.sessions[token]=time.time()+3600*12
                secure='; Secure' if os.environ.get('SAMACHA_SECURE_COOKIE')=='1' else ''
                self.json(200,{'authenticated':True},{'Set-Cookie':f'samacha_session={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200'+secure})
            def do_POST(self):
                if not self.check_host(): return
                origin=self.headers.get('Origin')
                if self.headers.get('X-Samacha')!='1' or (origin and urlsplit(origin).netloc!=self.headers.get('Host')):
                    self.json(403,{'error':'Same-origin app request required.'}); return
                try:
                    if urlsplit(self.path).path=='/api/models/upload':
                        if not self.sid(): self.json(401,{'error':'Sign in before importing a model.'}); return
                        self.upload_model(); return
                    size=int(self.headers.get('Content-Length','0'))
                    if not 0<size<=160000: raise ValueError('Request must contain less than 160 KB of JSON.')
                    b=json.loads(self.rfile.read(size))
                    if not isinstance(b,dict): raise ValueError('Expected a JSON object.')
                    path=urlsplit(self.path).path
                    if path=='/api/login':
                        if app.password and not hmac.compare_digest(str(b.get('password','')),app.password):
                            time.sleep(0.5); self.json(401,{'error':'Incorrect app password.'}); return
                        self.new_session(); return
                    if not self.sid(): self.json(401,{'error':'Sign in to this app host.'}); return
                    if path=='/api/logout':
                        token=self.sid()
                        with app.session_lock: app.sessions.pop(token,None)
                        self.json(200,{'ok':True},{'Set-Cookie':'samacha_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0'}); return
                    self.json(200,app.mutation(path,b))
                except (ValueError,KeyError,TypeError,ET.ParseError,ModelError,NetworkError) as e:
                    self.json(400,{'error':str(e) if not isinstance(e,KeyError) else 'A required field is missing.'})
                except Exception:
                    self.json(500,{'error':'Operation failed. No secrets are included in error responses.'})
            def upload_model(self):
                import re
                import shutil
                params=parse_qs(urlsplit(self.path).query)
                bundle=params.get('bundle',[''])[0]; relative=params.get('path',[''])[0]
                parts=relative.split('/')
                if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,100}',bundle) or bundle in ('.','..') or not parts or any(not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,150}',p) or p in ('.','..') for p in parts):
                    raise ValueError('Unsafe model file path.')
                if not relative.endswith(('.json','.txt','.safetensors','.model')):
                    raise ValueError('Only safetensors, tokenizer, and configuration files can be imported.')
                size=int(self.headers.get('Content-Length','0'))
                if not 0<size<=4*1024**3: raise ValueError('Model file must contain 1 byte to 4 GiB.')
                if not app.upload_lock.acquire(blocking=False): raise ValueError('Another model import is active.')
                tmp=None
                try:
                    directory=(app.router.root/bundle).resolve()
                    target=(directory/relative).resolve()
                    if not target.is_relative_to(app.router.root) or not target.is_relative_to(directory): raise ValueError('Model path escapes its bundle.')
                    if target.exists(): raise ValueError('This model file already exists. Choose a different bundle folder for a new version.')
                    total=sum(f.stat().st_size for f in app.router.root.rglob('*') if f.is_file() and not f.is_symlink())
                    if total+size>8*1024**3 or shutil.disk_usage(app.router.root).free<size+128*1024**2: raise ValueError('Import exceeds the 8 GiB managed-model quota or available disk space.')
                    target.parent.mkdir(parents=True,exist_ok=True)
                    tmp=target.with_name(target.name+'.'+uuid.uuid4().hex+'.part')
                    digest=hashlib.sha256(); remaining=size
                    with tmp.open('xb') as f:
                        while remaining:
                            chunk=self.rfile.read(min(1024**2,remaining))
                            if not chunk: raise ValueError('Upload ended early.')
                            digest.update(chunk); f.write(chunk); remaining-=len(chunk)
                    tmp.replace(target); tmp=None
                    self.json(200,{'bundle':bundle,'path':relative,'bytes':size,'sha256':digest.hexdigest()})
                finally:
                    if tmp: tmp.unlink(missing_ok=True)
                    app.upload_lock.release()
        return Handler


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--host',default='127.0.0.1'); parser.add_argument('--port',type=int,default=8787); parser.add_argument('--data',default=os.environ.get('SAMACHA_DATA','data'))
    args=parser.parse_args(); password=os.environ.get('SAMACHA_PASSWORD','')
    if args.host not in ('127.0.0.1','localhost','::1') and not password:
        parser.error('Set SAMACHA_PASSWORD before exposing the single-owner app beyond loopback. Configure trusted hosts and use a TLS reverse proxy.')
    app=App(args.data,password); httpd=ThreadingHTTPServer((args.host,args.port),app.handler())
    threading.Thread(target=app.scheduler,daemon=True).start()
    print(f'samacha-RAM is running at http://{args.host}:{args.port}. Single-owner data is stored locally. Press Ctrl+C to stop.')
    try: httpd.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        app.stopped.set(); httpd.server_close(); app.pool.shutdown(wait=True); app.store.close()


if __name__=='__main__': main()
