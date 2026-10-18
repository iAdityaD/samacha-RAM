import io
import json
import tempfile
import time
import unittest
from unittest.mock import patch
from server.app import App
from server.models import ModelError
from server.workspace import bibliography

class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.app=App(self.tmp.name);self.s=self.app.store;self.w=self.app.workspace
        self.a=self.s.items('articles')[0];self.b=self.s.items('articles')[1]
    def tearDown(self):self.app.pool.shutdown(wait=True);self.s.close();self.tmp.cleanup()
    def ref(self,a=None):
        a=a or self.a
        return {'article_id':a['id'],'anchor_id':'excerpt','quote':a['excerpt'][:80]}
    def wait(self,id):
        for _ in range(250):
            job=self.w.get('jobs',id)
            if job['status'] not in ('queued','running'):return job
            time.sleep(.02)
        self.fail('Job did not complete')
    def test_document_rights_are_required(self):
        with self.assertRaisesRegex(ValueError,'Confirm'):self.w.document({'article_id':self.a['id']})
    def test_html_documents_have_versioned_anchors_and_ignore_scripts(self):
        raw=b'<html><nav>Ignore navigation that is long enough to look like text.</nav><article><p>A specific research finding with enough text to make an anchored paragraph.</p><script>steal the credentials now</script></article></html>'
        with patch('server.workspace.network.request',return_value=raw):
            job=self.w.document({'article_id':self.a['id'],'rights_ack':True,'format':'html'});job=self.wait(job['id'])
        self.assertEqual(job['status'],'complete');doc=self.w.get('documents',job['document_id'])
        self.assertIn('research finding',doc['anchors'][0]['text']);self.assertNotIn('credentials',json.dumps(doc));self.assertNotIn('navigation',json.dumps(doc))
        self.assertNotIn('anchors',self.w.snapshot()['documents'][0])
    def test_bad_pdf_does_not_replace_existing_text(self):
        with patch('server.workspace.network.request',return_value=b'not a pdf'):
            job=self.wait(self.w.document({'article_id':self.a['id'],'rights_ack':True,'format':'pdf'})['id'])
        self.assertEqual(job['status'],'failed');self.assertEqual(self.s.items('documents'),[])
    def test_actual_pdf_is_parsed_in_subprocess(self):
        from pypdf import PdfWriter
        from pypdf.generic import DictionaryObject,NameObject,DecodedStreamObject
        writer=PdfWriter();page=writer.add_blank_page(300,300)
        font=DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')})
        page[NameObject('/Resources')]=DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):writer._add_object(font)})})
        content=DecodedStreamObject();content.set_data(b'BT /F1 12 Tf 10 200 Td (Research evidence in a real PDF document.) Tj ET')
        page[NameObject('/Contents')]=writer._add_object(content);out=io.BytesIO();writer.write(out)
        with patch('server.workspace.network.request',return_value=out.getvalue()):job=self.wait(self.w.document({'article_id':self.a['id'],'rights_ack':True,'format':'pdf'})['id'])
        self.assertEqual(job['status'],'complete',job);doc=self.w.get('documents',job['document_id']);self.assertEqual(doc['anchors'][0]['page'],1)
        self.assertIn('Research evidence',doc['anchors'][0]['text']);self.assertTrue((self.s.root/'documents'/(doc['version']+'.pdf')).exists())
    def test_claim_without_assessment_does_not_gain_truth_status(self):
        c=self.w.mutate('/api/claims',{'statement':'The model improves a specific benchmark.'});self.assertNotIn('status',c)
    def test_review_needs_exact_quote_and_reason(self):
        c=self.w.mutate('/api/claims',{'statement':'A claim that needs primary support.'})
        with self.assertRaises(ValueError):self.w.review({'claim_id':c['id'],'status':'Supported by primary evidence','evidence':[dict(self.ref(),quote='Invented quotation')],'note':'A sufficiently detailed reviewer explanation.'})
        r=self.w.review({'claim_id':c['id'],'status':'Single-source report','evidence':[self.ref()],'note':'This only establishes what the authors reported in their source.'})
        self.assertFalse(r['stale']);self.assertEqual(r['reviewer'],'Workspace owner')
    def test_corroboration_does_not_count_two_same_origin_articles(self):
        c=self.w.mutate('/api/claims',{'statement':'A claim needing independent corroboration.'})
        with self.assertRaisesRegex(ValueError,'two recorded source origins'):self.w.review({'claim_id':c['id'],'status':'Independently corroborated','evidence':[self.ref(),self.ref(self.b)],'note':'Both are from the same host so independence is unproven.','independence_ack':True})
    def test_article_changes_invalidate_review_and_embedding(self):
        c=self.w.mutate('/api/claims',{'statement':'The source gives evidence for this claim.'});self.w.review({'claim_id':c['id'],'status':'Single-source report','evidence':[self.ref()],'note':'Only the source author has reported the result.'})
        self.s.put('vectors','v',{'article_id':self.a['id']});self.s.put('articles',self.a['id'],{**self.a,'version':'changed'})
        self.assertTrue(self.s.items('reviews')[0]['stale']);self.assertEqual(self.s.items('vectors'),[]);self.assertTrue(self.s.items('versions'))
    def test_typed_connection_requires_evidence(self):
        with self.assertRaises(ValueError):self.w.mutate('/api/relations',{'from':self.a['id'],'to':self.b['id'],'type':'citation','reason':'Both concern language models.'})
        r=self.w.mutate('/api/relations',{'from':self.a['id'],'to':self.b['id'],'type':'topic_similarity','reason':'Both concern language models.'});self.assertEqual(r['type'],'topic_similarity')
    def test_anchored_note_rejects_unknown_passage(self):
        with self.assertRaises(ValueError):self.w.mutate('/api/annotations',{**self.ref(),'anchor_id':'missing','note':'My note'})
    def test_watch_alias_is_boundary_matched_and_deduplicated(self):
        w=self.w.mutate('/api/watches',{'query':'Transformer','aliases':['attention']});self.w.scan_watches();count=len(self.s.items('alerts'));self.w.scan_watches();self.assertEqual(len(self.s.items('alerts')),count);self.assertGreater(count,0)
    def test_feedback_does_not_mutate_explicit_layers(self):
        old=self.s.profile();self.w.mutate('/api/feedback',{'article_id':self.a['id'],'reason':'less_relevant'});self.assertEqual(old,self.s.profile());self.s.clear_history();self.assertEqual(self.s.items('feedback'),[])
    def test_semantic_index_and_query_use_the_same_model_space(self):
        def run(task,text,*args):return {'result':{'vectors':[[1.,0.]]},'model':'encoder1','provider':'local'}
        with patch.object(self.app.router,'run',side_effect=run):
            self.w.index({'article_ids':[self.a['id']]});r=self.w.retrieve({'query':'method','semantic':True});self.assertEqual(r['results'][0]['article_id'],self.a['id'])
        with patch.object(self.app.router,'run',return_value={'result':{'vectors':[[1.,0.]]},'model':'encoder2','provider':'local'}):self.assertEqual(self.w.retrieve({'query':'method','semantic':True})['results'],[])
    def test_comparison_cells_need_source_provenance(self):
        with self.assertRaises(ValueError):self.w.compare({'article_ids':[self.a['id'],self.b['id']],'cells':[{'article_id':self.a['id'],'field':'dataset','value':'invented','quote':'not in source','anchor_id':'excerpt'}]})
        record=self.w.compare({'article_ids':[self.a['id'],self.b['id']],'cells':[]});self.assertEqual(record['cells'],[]);self.assertIn('No automatic winner',record['notice'])
    def test_offline_operation_retry_is_idempotent(self):
        b={'id':self.a['id'],'kind':'mark_read','operation_id':'unique-op'};self.app.mutation('/api/history',b);self.app.mutation('/api/history',b);self.assertEqual(len(self.s.history()),1)
        with self.assertRaises(ValueError):self.app.mutation('/api/history',{**b,'kind':'summary_open'})
    def test_offline_note_conflict_stays_visible(self):
        self.s.save(self.a['id'],True,'Host changed this')
        with self.assertRaisesRegex(ValueError,'Note conflict'):self.app.mutation('/api/save',{'id':self.a['id'],'note':'offline edit','base_note':'old note','operation_id':'offline-note'})
        self.assertEqual(self.s.saved()[0]['note'],'Host changed this')
    def test_bibliographic_export_escapes_braces(self):
        text=bibliography([{**self.a,'title':'Bad {title} \\ injected'}],'bibtex');self.assertIn('title={Bad  title',text);self.assertNotIn('\\ injected',text)
    def test_job_cancel_flag_is_observable(self):
        self.s.put('jobs','j',{'id':'j','status':'queued'});self.w.mutate('/api/jobs/cancel',{'id':'j'});self.assertTrue(self.w.cancelled({'id':'j'}));self.assertEqual(self.w.get('jobs','j')['status'],'cancelled')
    def test_background_results_not_persisted_when_tracking_paused(self):
        self.s.set_setting('profile',{'tracking':False})
        with self.assertRaisesRegex(ValueError,'persist'):self.w.mutate('/api/model-jobs',{'task':'qa','text':'text'})
    def test_model_controls_and_daily_request_budget(self):
        p=self.app.router.save_provider({'name':'Cloud','kind':'openai','model':'model','endpoint':'https://example.com/v1','allow_cloud':True,'capabilities':['classification'],'max_output_tokens':64,'temperature':.2,'max_input_chars':1000,'timeout':10})
        self.s.set_setting('cloud_request_limit',0)
        with patch('server.models.network.request_json') as request:
            with self.assertRaisesRegex(ModelError,'budget'):self.app.router.run('classification','A long enough source',provider_id=p['id'])
            request.assert_not_called()
    def test_story_candidates_are_not_confirmed_events(self):
        for i in range(2):self.s.put('articles','story'+str(i),{**self.a,'id':'story'+str(i),'title':'Acme releases speech inference model','url':'https://example.org/'+str(i),'published':'2026-10-18T00:00:00+00:00','starter':False})
        groups=self.w.suggest_stories()['stories'];self.assertEqual(len(groups),1);self.assertEqual(groups[0]['status'],'candidate')
        self.w.mutate('/api/stories/confirm',{'id':groups[0]['id']});self.assertEqual(self.s.items('stories')[0]['status'],'owner_confirmed')
    def test_story_changes_distinguish_summary_exposure_from_full_reading(self):
        self.s.put('stories','story',{'id':'story','article_ids':[self.a['id']]})
        self.s.event(self.a['id'],'summary_open')
        self.s.put('articles',self.a['id'],{**self.a,'version':'new','excerpt':'A changed source excerpt.'})
        out=self.w.story('story')['timeline'][0]
        self.assertIn('summary open',out['change']);self.assertNotIn('full paper',out['change']);self.assertEqual(out['previous_excerpt'],self.a['excerpt'])

if __name__=='__main__':unittest.main()
