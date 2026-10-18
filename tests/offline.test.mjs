import test from 'node:test';
import assert from 'node:assert/strict';
import {overlay,replay} from '../web/offline.js';
test('offline overlays preserve notes when merely bookmarking',()=>{const state={profile:{},saved:[{id:'a',note:'keep'}]};const out=overlay(state,[{path:'/api/save',body:{id:'a',active:true}}]);assert.equal(out.saved[0].note,'keep');assert.equal(state.saved[0].note,'keep');});
test('offline privacy changes apply locally without persisting operation metadata in profile',()=>{const out=overlay({profile:{tracking:true},saved:[]},[{path:'/api/profile',body:{tracking:false,operation_id:'op'}}]);assert.equal(out.profile.tracking,false);assert.equal(out.profile.operation_id,undefined);});
test('replay stops on a conflict and keeps subsequent edits unacknowledged',async()=>{const seen=[];await assert.rejects(replay([{path:'a',body:{operation_id:'1'}},{path:'b',body:{operation_id:'2'}},{path:'c',body:{operation_id:'3'}}],async p=>{if(p==='b')throw Error('conflict');},id=>seen.push(id)));assert.deepEqual(seen,['1']);});
