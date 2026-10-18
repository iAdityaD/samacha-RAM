import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from server.app import App
from server.feeds import parse_feed, canonical, seed
from server.models import ModelError, Router, validate_output, validate_provider
from server.network import resolve, NetworkError
from server.store import Store


class StoreTests(unittest.TestCase):
    def setUp(self): self.tmp=tempfile.TemporaryDirectory(); self.s=Store(self.tmp.name); seed(self.s)
    def tearDown(self): self.s.close(); self.tmp.cleanup()
    def test_seed_is_idempotent_and_historical(self):
        seed(self.s); self.assertEqual(len(self.s.items('articles')),4); self.assertTrue(all(a['starter'] for a in self.s.items('articles')))
    def test_encryption_is_not_plaintext(self):
        encrypted=self.s.encrypt('test-secret'); self.assertNotIn('test-secret',encrypted); self.assertEqual(self.s.decrypt(encrypted),'test-secret')
    def test_tracking_off_prevents_events(self):
        self.s.set_setting('profile',{'tracking':False}); self.s.event('a','metadata_open'); self.assertEqual(self.s.history(),[])
    def test_clear_history_preserves_saves(self):
        self.s.save('a',True,'a note'); self.s.event('a','mark_read'); self.s.put('outputs','o',{'question':'private reading question'}); self.s.clear_history(); self.assertEqual(self.s.history(),[]); self.assertEqual(self.s.saved()[0]['note'],'a note'); self.assertEqual(self.s.items('outputs'),[])
    def test_saved_note_survives_restart(self):
        self.s.save('a',True,'note'); self.s.close(); self.s=Store(self.tmp.name); self.assertEqual(self.s.saved()[0]['note'],'note')


class FeedTests(unittest.TestCase):
    source={'id':'s','name':'Test','kind':'Paper','topic':'LLMs'}
    def test_rss_normalization_and_unknown_evidence(self):
        raw=b'<rss><channel><item><title>LLM research</title><link>https://example.com/x?utm_source=foo</link><description>&lt;p&gt;Text &amp;amp; evidence&lt;/p&gt;</description><pubDate>Wed, 01 Jan 2025 10:00:00 GMT</pubDate></item></channel></rss>'
        a=parse_feed(raw,self.source)[0]; self.assertEqual(a['url'],'https://example.com/x'); self.assertEqual(a['excerpt'],'Text & evidence'); self.assertEqual(a['evidence'],'Not assessed'); self.assertTrue(a['published'].startswith('2025'))
    def test_atom_link_and_author(self):
        raw=b'<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Paper</title><link href="https://example.com/p"/><author><name>Alice</name></author><summary>Words.</summary></entry></feed>'
        self.assertEqual(parse_feed(raw,self.source)[0]['authors'],'Alice')
    def test_json_feed(self):
        raw=json.dumps({'items':[{'title':'Paper','url':'https://example.com/a','content_text':'Text'}]}).encode()
        self.assertEqual(parse_feed(raw,self.source)[0]['excerpt'],'Text')
    def test_xxe_and_empty_feed_rejected(self):
        with self.assertRaises(ValueError): parse_feed(b'<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss/>',self.source)
        with self.assertRaises(ValueError): parse_feed(b'<html/>',self.source)
    def test_javascript_links_rejected(self):
        raw=b'<rss><item><title>X</title><link>javascript:alert(1)</link></item></rss>'
        self.assertEqual(parse_feed(raw,self.source),[])
    def test_article_version_changes_when_content_changes(self):
        a=b'<rss><item><title>X</title><link>https://e.org/a</link><description>ONE</description></item></rss>'
        one=parse_feed(a,self.source)[0]; two=parse_feed(a.replace(b'ONE',b'TWO'),self.source)[0]
        self.assertEqual(one['id'],two['id']); self.assertNotEqual(one['version'],two['version'])
    def test_missing_date_does_not_become_today(self):
        raw=b'<rss><item><title>X</title><link>https://e.org/a</link></item></rss>'
        self.assertIsNone(parse_feed(raw,self.source)[0]['published'])


class ModelTests(unittest.TestCase):
    def setUp(self): self.tmp=tempfile.TemporaryDirectory(); self.s=Store(self.tmp.name); self.r=Router(self.s)
    def tearDown(self): self.s.close(); self.tmp.cleanup()
    def cloud(self,task='summarization'):
        return self.r.save_provider({'name':'Cloud','model':'test-model','kind':'openai','endpoint':'https://example.com/v1','local':False,'allow_cloud':True,'capabilities':[task],'key':'test-secret'})
    def test_keys_are_redacted(self):
        p=self.cloud(); self.assertNotIn('secret',p); self.assertNotIn('test-secret',json.dumps(self.r.public_providers())); self.assertTrue(p['has_key'])
    def test_requires_explicit_cloud_consent(self):
        with self.assertRaises(ModelError): self.r.save_provider({'name':'x','model':'x','kind':'openai','endpoint':'https://example.com/v1','capabilities':['qa']})
    def test_capability_mismatch_rejected(self):
        p=self.cloud()
        with self.assertRaises(ModelError): self.r.configure({'embeddings':{'primary':p['id']}})
    def test_local_only_blocks_cloud_before_request(self):
        p=self.cloud(); self.r.configure({'summarization':{'primary':p['id'],'fallback':'none'}}); self.s.set_setting('profile',{'local_only':True})
        with patch('server.models.network.request_json') as call:
            with self.assertRaisesRegex(ModelError,'Privacy policy'): self.r.run('summarization','A sufficiently long text for source analysis.')
            call.assert_not_called()
    def test_cloud_fallback_blocked_by_default(self):
        p=self.cloud('embeddings'); self.r.configure({'embeddings':{'primary':'builtin','fallback':p['id']}})
        with patch('server.models.network.request_json') as call:
            with self.assertRaisesRegex(ModelError,'Cloud fallback is disabled'): self.r.run('embeddings','Text for embedding.')
            call.assert_not_called()
    def test_separate_task_routes(self):
        p=self.cloud('classification'); self.r.configure({'classification':{'primary':p['id'],'fallback':'none'}})
        with patch('server.models.network.request_json',return_value={'choices':[{'message':{'content':'{"labels":["NLP"]}'}}]}) as call:
            out=self.r.run('classification','Text about language.'); self.assertEqual(out['result']['labels'],['NLP']); self.assertTrue(call.called)
        self.assertEqual(self.r.run('summarization','This source contains enough text to summarize.')['model'],'rules/extractive-v1')
    def test_invalid_citation_fails_closed(self):
        with self.assertRaises(ModelError): validate_output('summarization',{'sentences':[{'text':'Claim','quote':'invented passage'}]},'Original source only.')
    def test_exact_quote_retains_uncertainty_label(self):
        out=validate_output('summarization',{'sentences':[{'text':'Claim','quote':'Original source'}]},'Original source only.')
        self.assertEqual(out['assessment'],'Not independently assessed')
    def test_explicit_builtin_fallback_is_reported(self):
        p=self.cloud();self.r.configure({'summarization':{'primary':p['id'],'fallback':'builtin'}})
        with patch('server.models.network.request_json',side_effect=NetworkError('Provider unavailable')):
            out=self.r.run('summarization','The original source contains a complete sentence about a method.')
            self.assertTrue(out['fallback_used']);self.assertTrue(out['local']);self.assertEqual(len(out['prior_failures']),1)
    def test_empty_model_answer_is_permitted_abstention(self):
        self.assertEqual(validate_output('qa',{'sentences':[]},'source')['sentences'],[])
    def test_invalid_embedding_rejected(self):
        with self.assertRaises(ModelError): validate_output('embeddings',{'vectors':[[float('nan')]]},'text')
    def test_baseline_never_fakes_verification_or_vectors(self):
        for task in ('embeddings','evidence','reranking'):
            with self.assertRaises(ModelError): self.r.run(task,'A simple source text.')
    def test_local_path_cannot_escape_root(self):
        with self.assertRaises(ModelError): self.r.local_path('../escape')
    def test_local_bundle_rejects_pickle_only(self):
        p=self.r.root/'bundle';p.mkdir();(p/'config.json').write_text('{}');(p/'pytorch_model.bin').write_bytes(b'x')
        with self.assertRaisesRegex(ModelError,'safetensors'): self.r.local_path('bundle')
    def test_model_download_requires_license_and_pinned_revision(self):
        with self.assertRaises(ModelError): self.r.download('owner/model','main',False)
        with self.assertRaisesRegex(ModelError,'40-character'): self.r.download('owner/model','main',True)
    def test_local_endpoint_must_resolve_only_to_loopback(self):
        addresses=[(2,1,6,'',('10.0.0.1',80))]
        with patch('server.network.socket.getaddrinfo',return_value=addresses):
            with self.assertRaises(NetworkError): resolve('http://localhost:11434',True)
    def test_public_fetch_blocks_private_network(self):
        with self.assertRaises(NetworkError): resolve('https://127.0.0.1/private')


class HTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.app=App(cls.tmp.name,password='app-password')
        cls.http=ThreadingHTTPServer(('127.0.0.1',0),cls.app.handler());cls.port=cls.http.server_port
        cls.thread=threading.Thread(target=cls.http.serve_forever,daemon=True);cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown();cls.http.server_close();cls.app.pool.shutdown();cls.app.store.close();cls.tmp.cleanup()
    def req(self,path,body=None,cookie='',headers=None):
        c=http.client.HTTPConnection('127.0.0.1',self.port,timeout=5); h={'Content-Type':'application/json','X-Samacha':'1','Cookie':cookie,**(headers or {})}
        c.request('POST' if body is not None else 'GET',path,json.dumps(body) if body is not None else None,h)
        r=c.getresponse(); raw=r.read(); status=r.status; response_headers=dict(r.getheaders());c.close()
        return status,json.loads(raw) if 'application/json' in response_headers.get('Content-Type','') else raw,response_headers
    def login(self):return self.req('/api/login',{'password':'app-password'})[2]['Set-Cookie'].split(';')[0]
    def test_api_requires_owner_session(self): self.assertEqual(self.req('/api/state')[0],401)
    def test_wrong_password_rejected(self): self.assertEqual(self.req('/api/login',{'password':'no'})[0],401)
    def test_host_and_origin_protection(self):
        self.assertEqual(self.req('/api/state',headers={'Host':'attacker.example'})[0],403)
        self.assertEqual(self.req('/api/login',{},headers={'Origin':'https://attacker.example'})[0],403)
    def test_end_to_end_save_note_run_and_clear(self):
        cookie=self.login();status,state,_=self.req('/api/state',cookie=cookie);self.assertEqual(status,200);id=state['articles'][0]['id']
        self.assertEqual(self.req('/api/save',{'id':id,'note':'my note'},cookie)[0],200)
        out=self.req('/api/run',{'article_id':id,'task':'summarization'},cookie)[1]
        self.assertTrue(out['local']);self.assertEqual(out['result']['sentences'][0]['text'],out['result']['sentences'][0]['quote'])
        self.req('/api/history',{'id':id,'kind':'summary_open'},cookie);self.req('/api/history/clear',{},cookie)
        state=self.req('/api/state',cookie=cookie)[1];self.assertEqual(state['history'],[]);self.assertEqual(state['saved'][0]['note'],'my note')
    def test_model_upload_traversal_rejected(self):
        cookie=self.login();self.assertEqual(self.req('/api/models/upload?bundle=test&path=../evil.json',{},cookie)[0],400)
    def test_model_upload_streams_file_and_rejects_overwrite(self):
        cookie=self.login();path='/api/models/upload?bundle=imported&path=config.json'
        self.assertEqual(self.req(path,{'model_type':'bert'},cookie)[0],200)
        self.assertTrue((self.app.router.root/'imported'/'config.json').exists())
        self.assertEqual(self.req(path,{'model_type':'bert'},cookie)[0],400)
    def test_model_upload_rejects_executable_and_pickle_weights(self):
        cookie=self.login()
        for name in ('model.py','pytorch_model.bin'):
            self.assertEqual(self.req('/api/models/upload?bundle=unsafe&path='+name,{},cookie)[0],400)
    def test_export_excludes_provider_credentials(self):
        cookie=self.login();self.app.router.save_provider({'name':'Export test','kind':'openai','model':'m','endpoint':'https://example.org/v1','allow_cloud':True,'capabilities':['qa'],'key':'private-key'})
        status,data,_=self.req('/api/export',cookie=cookie);self.assertEqual(status,200);self.assertNotIn('models',data);self.assertNotIn('private-key',json.dumps(data))
    def test_logout_revokes_session_without_deadlock(self):
        cookie=self.login();self.assertEqual(self.req('/api/logout',{},cookie)[0],200);self.assertEqual(self.req('/api/state',cookie=cookie)[0],401)
    def test_frontend_and_manifest_served_with_security_headers(self):
        status,raw,h=self.req('/');self.assertEqual(status,200);self.assertIn(b'samacha-RAM',raw);self.assertIn('Content-Security-Policy',h)
        self.assertEqual(self.req('/manifest.webmanifest')[0],200)
    def test_missions_do_not_mutate_profile(self):
        old=self.app.store.profile();cookie=self.login()
        status,_,_=self.req('/api/mission',{'title':'Audio this week','topics':['AI audio'],'expires':'2099-01-01'},cookie)
        self.assertEqual(status,200);self.assertEqual(self.app.store.profile(),old)


if __name__=='__main__': unittest.main()
