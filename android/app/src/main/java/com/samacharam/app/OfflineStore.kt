package com.samacharam.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.UUID

class OfflineStore(context: Context): SQLiteOpenHelper(context,"offline.db",null,1) {
    private val cache=File(context.filesDir,"state.json")
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE queue(id TEXT PRIMARY KEY,path TEXT NOT NULL,body TEXT NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion:Int,newVersion:Int) {}
    fun cached(): JSONObject = try { JSONObject(cache.readText()) } catch (_:Exception) { JSONObject() }
    fun cache(state: JSONObject) { val temp=File(cache.path+".tmp");temp.writeText(state.toString());check(temp.renameTo(cache)) }
    fun enqueue(path:String,body:JSONObject) {
        require(path in setOf("/api/save","/api/history","/api/profile"))
        val id=UUID.randomUUID().toString();body.put("operation_id",id)
        writableDatabase.execSQL("INSERT INTO queue VALUES (?,?,?)",arrayOf(id,path,body.toString()))
    }
    fun sync(api:ApiClient):Int {
        var sent=0
        readableDatabase.rawQuery("SELECT id,path,body FROM queue ORDER BY rowid",null).use { c ->
            while(c.moveToNext()) {
                api.request(c.getString(1),JSONObject(c.getString(2)))
                writableDatabase.delete("queue","id=?",arrayOf(c.getString(0)));sent++
            }
        }
        return sent
    }
    fun count():Int=readableDatabase.rawQuery("SELECT COUNT(*) FROM queue",null).use{it.moveToFirst();it.getInt(0)}
}
