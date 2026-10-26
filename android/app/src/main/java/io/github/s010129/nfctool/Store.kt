package io.github.s010129.nfctool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val time: Long, val action: String, val uid: String, val detail: String, val ok: Boolean) {
    fun toJson() = JSONObject().put("time", time).put("action", action).put("uid", uid).put("detail", detail).put("ok", ok)
    companion object {
        fun fromJson(o: JSONObject) = HistoryItem(o.optLong("time"), o.optString("action"), o.optString("uid"), o.optString("detail"), o.optBoolean("ok", true))
    }
}

data class Template(val name: String, val records: List<RecForm>) {
    fun toJson() = JSONObject().put("name", name).put("records", RecForm.listToJson(records))
    companion object {
        fun fromJson(o: JSONObject) = Template(o.optString("name"), RecForm.listFromJson(o.optJSONArray("records")))
    }
}

/** 紀錄和常用內容只存在手機裡 */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("nfc_tool", Context.MODE_PRIVATE)

    private fun arr(key: String) = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
    private fun <T> list(key: String, f: (JSONObject) -> T) = arr(key).let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } }

    fun history() = list("history", HistoryItem::fromJson)
    fun saveHistory(l: List<HistoryItem>) =
        prefs.edit().putString("history", JSONArray().apply { l.take(500).forEach { put(it.toJson()) } }.toString()).apply()

    fun templates() = list("templates", Template::fromJson)
    fun saveTemplates(l: List<Template>) =
        prefs.edit().putString("templates", JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()).apply()

    fun draft(): List<RecForm> = try { RecForm.listFromJson(JSONArray(prefs.getString("draft", "[]"))) } catch (_: Exception) { emptyList() }
    fun saveDraft(l: List<RecForm>) = prefs.edit().putString("draft", RecForm.listToJson(l).toString()).apply()

    fun getInt(k: String, d: Int) = prefs.getInt(k, d)
    fun putInt(k: String, v: Int) = prefs.edit().putInt(k, v).apply()
}
