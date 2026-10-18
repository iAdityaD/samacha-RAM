package com.samacharam.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull{optJSONObject(it)}
val taskNames=listOf("classification","summarization","embeddings","reranking","evidence","qa")

class MainActivity:ComponentActivity() {
    private lateinit var api:ApiClient
    private lateinit var offline:OfflineStore
    private lateinit var device:DeviceModels
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);api=ApiClient(this);offline=OfflineStore(this);device=DeviceModels(this)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xff315645))) { Workspace() } }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable fun Workspace() {
        var state by remember { mutableStateOf(offline.cached()) };var tab by remember { mutableStateOf("Today") }
        var status by remember { mutableStateOf("Cached workspace. Connect in Settings to synchronize.") };var busy by remember { mutableStateOf(false) }
        val shared=if(intent.action==Intent.ACTION_SEND)intent.getStringExtra(Intent.EXTRA_TEXT) else null
        var selected by remember { mutableStateOf<JSONObject?>(null) };var note by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("") };var question by remember { mutableStateOf("") };var query by remember { mutableStateOf("") }
        val routes=getSharedPreferences("routes",MODE_PRIVATE)
        fun job(block:()->String) { if(busy)return;busy=true;lifecycleScope.launch {status=try{withContext(Dispatchers.IO){block()}}catch(e:Exception){e.message?:"Operation failed"};busy=false} }
        fun refresh()=job { offline.sync(api);val value=api.request("/api/state");offline.cache(value);withContextOnMain{state=value};"Synchronized. ${offline.count()} offline operations remain." }
        fun mutation(path:String,body:JSONObject) = job {
            try { api.request(path,body);val value=api.request("/api/state");offline.cache(value);withContextOnMain{state=value};"Saved." }
            catch(e:Exception) { if(path in setOf("/api/save","/api/history","/api/profile")){offline.enqueue(path,body);"Queued offline (${offline.count()}). Reconnect to synchronize; conflicting edits remain queued."}else throw e }
        }
        fun runTask(task:String,text:String) = job {
            val choice=routes.getString(task,"host")?:"host"
            val answer=if(choice=="host")api.request("/api/run",JSONObject().put("task",task).put("text",text.take(24000)).put("question",question)).toString(2)
            else device.run(choice,task,text.take(16000),question)
            withContextOnMain{output=answer};"${if(choice=="host")"Host route" else "On-device model"} completed. Output is not independent verification."
        }
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
            if(uri!=null)job {val prefs=getSharedPreferences("import",MODE_PRIVATE);val name=prefs.getString("bundle","my-model")!!;val file=prefs.getString("file","model.litertlm")!!;val hash=device.importFile(uri,name,file);"Imported $file into $name · SHA-256 ${hash.take(16)}…"}
        }
        val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
        Scaffold(topBar={TopAppBar(title={Text("samacha·RAM")},actions={TextButton(onClick={refresh()},enabled=!busy){Text("Sync")}})},bottomBar={NavigationBar {listOf("Today","Library","Interests","Models","Settings").forEach { name->NavigationBarItem(selected=tab==name,onClick={tab=name},icon={Text(name.take(1))},label={Text(name)})}}}) { padding->
            Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(status,style=MaterialTheme.typography.labelMedium)
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                when(tab) {
                    "Today","Library" -> {
                        Text(if(tab=="Today")"Follow your curiosity." else "Keep the good things.",style=MaterialTheme.typography.headlineMedium)
                        if(!shared.isNullOrBlank())Button(onClick={mutation("/api/clip",JSONObject().put("url",shared.trim()).put("title",intent.getStringExtra(Intent.EXTRA_SUBJECT)?:""))}){Text("Save shared URL to library")}
                        if(tab=="Today")TextButton(onClick={mutation("/api/refresh",JSONObject())}){Text("Refresh source feeds (sync again after processing)")}
                        OutlinedTextField(query,{query=it},label={Text("Search your collection")},modifier=Modifier.fillMaxWidth())
                        val saves=state.optJSONArray("saved")?.objects()?.map{it.optString("id")}?:emptyList()
                        val topics=state.optJSONObject("profile")?.optJSONObject("topics")
                        val articles=state.optJSONArray("articles")?.objects()?:emptyList()
                        val visible=articles.filter{a -> (tab!="Library"||a.optString("id") in saves) && (a.optString("title")+a.optString("excerpt")).contains(query,true) && !(a.optJSONArray("topics")?:JSONArray()).let { ts ->(0 until ts.length()).any{topics?.optString(ts.getString(it))=="Muted"}}}
                        if(visible.isEmpty())Text("No matching records. Connect to your host, refresh its sources, or change the filter.")
                        visible.forEach { a ->Card(modifier=Modifier.fillMaxWidth(),onClick={selected=a;note=state.optJSONArray("saved")?.objects()?.find{it.optString("id")==a.optString("id")}?.optString("note")?:"";output=""}) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(a.optString("source"),style=MaterialTheme.typography.labelSmall);Text(a.optString("title"),style=MaterialTheme.typography.titleLarge);Text(a.optString("excerpt").take(220));Text("${a.optString("access")} · Evidence not assessed",style=MaterialTheme.typography.labelSmall)} } }
                    }
                    "Interests" -> {
                        Text("Your interests, in layers.",style=MaterialTheme.typography.headlineMedium)
                        val topics=state.optJSONObject("profile")?.optJSONObject("topics")?:JSONObject()
                        topics.keys().asSequence().toList().forEach { topic->
                            Text(topic,style=MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("Core","Near","Adjacent","Discovery","Muted").forEach { l->TextButton(onClick={topics.put(topic,l);mutation("/api/profile",JSONObject().put("topics",topics))}){Text(if(topics.optString(topic)==l)"•$l" else l,style=MaterialTheme.typography.labelSmall)}}
                        }
                    }
                    "Models" -> {
                        Text("A specialist for each task.",style=MaterialTheme.typography.headlineMedium)
                        Text("Host routes use the server's encrypted provider settings. Device routes stay entirely on this phone, with no cloud fallback.")
                        val bundles=device.names()
                        taskNames.forEach { task->
                            var chosen by remember(task,status){mutableStateOf(routes.getString(task,"host")?:"host")}
                            var expanded by remember{mutableStateOf(false)}
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(task);Box {TextButton(onClick={expanded=true}){Text(chosen)};DropdownMenu(expanded,{expanded=false}){(listOf("host")+bundles).forEach { name->DropdownMenuItem(text={Text(name)},onClick={routes.edit().putString(task,name).apply();chosen=name;expanded=false})}}}
                        }
                        var bundle by remember{mutableStateOf("my-model")};var filename by remember{mutableStateOf("model.litertlm")}
                        OutlinedTextField(bundle,{bundle=it},label={Text("Bundle name")})
                        Row {listOf("model.litertlm","model.onnx","vocab.txt","config.json").forEach { name->TextButton(onClick={filename=name}){Text(if(name==filename)"•"+name else name,style=MaterialTheme.typography.labelSmall)}}}
                        Button(onClick={getSharedPreferences("import",MODE_PRIVATE).edit().putString("bundle",bundle).putString("file",filename).apply();picker.launch(arrayOf("*/*"))},enabled=!busy){Text("Import selected model file")}
                        Text("Review the model license first. LiteRT-LM bundles support local generation. ONNX adapter requires uncased BERT, vocab.txt, config.json, and model.onnx. Not every model or phone is compatible.")
                        var testText by remember{mutableStateOf("A language model processes text. The source reports a method, not independent validation.")}
                        OutlinedTextField(testText,{testText=it},label={Text("Source text")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(question,{question=it},label={Text("Question / claim")},modifier=Modifier.fillMaxWidth())
                        taskNames.chunked(3).forEach { row->Row {row.forEach { task->TextButton(onClick={runTask(task,testText)},enabled=!busy){Text(task,style=MaterialTheme.typography.labelSmall)}}} }
                        if(output.isNotBlank())Text(output)
                        var providerName by remember{mutableStateOf("")};var providerModel by remember{mutableStateOf("")};var endpoint by remember{mutableStateOf("")};var key by remember{mutableStateOf("")};var cloud by remember{mutableStateOf(false)};var capability by remember{mutableStateOf("summarization")}
                        Text("Add a host API specialist",style=MaterialTheme.typography.titleLarge)
                        OutlinedTextField(providerName,{providerName=it},label={Text("Name")});OutlinedTextField(providerModel,{providerModel=it},label={Text("Model ID")});OutlinedTextField(endpoint,{endpoint=it},label={Text("HTTPS compatible API base /v1")});OutlinedTextField(key,{key=it},label={Text("API key (sent only to your host)")},visualTransformation=PasswordVisualTransformation())
                        Row {Checkbox(cloud,{cloud=it});Text("Allow selected source text to this cloud provider")}
                        taskNames.chunked(3).forEach { row->Row {row.forEach{t->TextButton(onClick={capability=t}){Text(if(capability==t)"•$t" else t,style=MaterialTheme.typography.labelSmall)}}} }
                        Button(onClick={mutation("/api/models",JSONObject().put("name",providerName).put("model",providerModel).put("endpoint",endpoint).put("key",key).put("kind","openai").put("local",false).put("allow_cloud",cloud).put("capabilities",JSONArray().put(capability)));key=""}){Text("Register provider on host")}
                        val hostModels=state.optJSONArray("models")?.objects()?:emptyList()
                        taskNames.forEach { task ->
                            var expanded by remember{mutableStateOf(false)}
                            Box {TextButton(onClick={expanded=true}){Text("Assign host $task route")};DropdownMenu(expanded,{expanded=false}){hostModels.filter{m->m.optJSONArray("capabilities")?.let{c->(0 until c.length()).any{c.getString(it)==task}}==true}.forEach{m->DropdownMenuItem(text={Text(m.optString("name"))},onClick={val r=state.optJSONObject("routes")?:JSONObject();r.put(task,JSONObject().put("primary",m.getString("id")).put("fallback","none"));mutation("/api/routes",r);expanded=false})}}}
                        }
                    }
                    "Settings" -> {
                        var host by remember{mutableStateOf(api.base)};var password by remember{mutableStateOf("")};var rememberLogin by remember{mutableStateOf(false)}
                        Text("Connect your workspace.",style=MaterialTheme.typography.headlineMedium)
                        OutlinedTextField(host,{host=it},label={Text("App host URL")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(password,{password=it},label={Text("Host password")},visualTransformation=PasswordVisualTransformation())
                        Row {Checkbox(rememberLogin,{rememberLogin=it});Text("Remember encrypted login for background briefing")}
                        Button(onClick={job{api.configure(host);api.login(password);SecureStore(this@MainActivity).put("remembered-password",if(rememberLogin)password else "");val value=api.request("/api/state");offline.cache(value);withContextOnMain{state=value};"Connected. Host model routes and saved records are synchronized."}},enabled=!busy){Text("Connect")}
                        TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(api.base)))}){Text("Open full evidence workspace in browser")}
                        Text("Release builds require HTTPS. Debug emulator uses 10.0.2.2; allow it in SAMACHA_ALLOWED_HOSTS. Queued edits: ${offline.count()}.")
                        Button(onClick={if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS);val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();WorkManager.getInstance(this@MainActivity).enqueueUniquePeriodicWork("daily-briefing",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<BriefingWorker>(1,TimeUnit.DAYS).setConstraints(constraints).build());status="Daily background briefing requested. Android may delay work; host access is required."}){Text("Enable daily briefing notification")}
                        TextButton(onClick={WorkManager.getInstance(this@MainActivity).cancelUniqueWork("daily-briefing");status="Background briefing disabled."}){Text("Disable background briefing")}
                    }
                }
            }
        }
        selected?.let { a->AlertDialog(onDismissRequest={selected=null},title={Text(a.optString("title"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(a.optString("excerpt"));Text("${a.optString("publication")} · Not assessed");OutlinedTextField(note,{note=it},label={Text("Private note")});TextButton(onClick={mutation("/api/save",JSONObject().put("id",a.getString("id")).put("active",true).put("base_note",state.optJSONArray("saved")?.objects()?.find{it.optString("id")==a.optString("id")}?.optString("note")?:"").put("note",note))}){Text("Save article & note")};TextButton(onClick={runTask("summarization",a.optString("excerpt"))}){Text("Summarize with chosen route")};if(output.isNotBlank())Text(output)}},confirmButton={TextButton(onClick={val u=Uri.parse(a.optString("url"));if(u.scheme in listOf("https","http"))startActivity(Intent(Intent.ACTION_VIEW,u))}){Text("Original")}},dismissButton={TextButton(onClick={selected=null}){Text("Close")}}) }
    }
    private fun withContextOnMain(block:()->Unit) { runOnUiThread(block) }
}
