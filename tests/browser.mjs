import {chromium} from 'playwright';
import {spawn} from 'node:child_process';
import {mkdtemp,mkdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';

const data=await mkdtemp(join(tmpdir(),'samacha-browser-'));
const host=spawn('python',['-m','server.app','--port','8798','--data',data],{stdio:['ignore','pipe','pipe']});
let browser;
try {
 let ready=false;
 for(let i=0;i<80;i++){try{await fetch('http://127.0.0.1:8798/api/session');ready=true;break;}catch{await new Promise(r=>setTimeout(r,100));}}
 assert.ok(ready,'Test host did not start');
 browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1100}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:8798');await page.getByRole('button',{name:'Find my signal'}).click();
 await page.locator('.article-card').first().waitFor();assert.equal(await page.locator('.article-card').count(),4);
 await mkdir('browser-artifacts',{recursive:true});await page.screenshot({path:'browser-artifacts/desktop.png',fullPage:true});
 await page.locator('.card-title').first().click();await page.getByRole('button',{name:'Summarize excerpt'}).click();await page.locator('#reader-result .result').waitFor();
 await page.locator('#note-form textarea').fill('Browser-tested persistent reading note.');await page.locator('#note-form button[type=submit]').click();await page.waitForTimeout(200);
 await page.getByRole('button',{name:'Close dialog',exact:true}).click();await page.locator('.sidebar [data-nav=library]').click();assert.equal(await page.locator('.article-card').count(),1);
 await page.locator('.sidebar [data-nav=explore]').click();await page.locator('[data-topic=LLMs]').selectOption('Near');await page.waitForTimeout(200);assert.equal(await page.locator('[data-topic=LLMs]').inputValue(),'Near');
 await page.locator('.sidebar [data-nav=workbench]').click();await page.getByRole('button',{name:'Record claim',exact:true}).click();await page.locator('[name=statement]').fill('The authors report a particular research method.');await page.locator('#work-form button[type=submit]').click();await page.waitForTimeout(200);assert.ok((await page.locator('.main-content, .page-width').innerText()).includes('The authors report'));
 await page.locator('.sidebar [data-nav=models]').click();await page.locator('#play-text').fill('A language model analyzes source text. This source makes no independent verification claim.');await page.locator('#play-task').selectOption('summarization');await page.locator('#playground button[type=submit]').click();await page.locator('#play-result .result').waitFor();
 await page.setViewportSize({width:390,height:844});await page.locator('.mobile-nav [data-nav=today]').click();await page.screenshot({path:'browser-artifacts/android-layout.png',fullPage:true});
 assert.ok(await page.locator('.mobile-nav').isVisible());assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true,'Mobile layout overflows horizontally');
 assert.deepEqual(errors,[]);console.log('Browser checks passed: onboarding, source view, summary, notes/library, interest editing, evidence claim, model playground, responsive navigation.');
} finally {
 await browser?.close();host.kill('SIGTERM');await new Promise(resolve=>host.once('exit',resolve));await rm(data,{recursive:true,force:true});
}
