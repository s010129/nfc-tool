package io.github.s010129.nfctool

import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

enum class FieldKind { TEXT, MULTI, URL, PHONE, EMAIL, NUMBER, SELECT }

data class Field(
    val k: String,
    val label: String,
    val kind: FieldKind = FieldKind.TEXT,
    val ph: String = "",
    val options: List<Pair<String, String>> = emptyList(),
    val def: String = "",
)

class RecType(
    val key: String,
    val label: String,
    val fields: List<Field>,
    val note: String? = null,
    val check: (Map<String, String>) -> String? = { null },
    val build: (Map<String, String>) -> NdefRecord,
)

/** 寫入頁面上一筆記錄的表單內容 */
data class RecForm(val type: String, val values: Map<String, String> = emptyMap()) {
    fun v(k: String) = values[k] ?: ""
    fun toJson() = JSONObject().put("type", type).put("values", JSONObject(values))

    companion object {
        fun fromJson(o: JSONObject): RecForm {
            val vo = o.optJSONObject("values") ?: JSONObject()
            return RecForm(o.optString("type", "url"), vo.keys().asSequence().associateWith { vo.optString(it) })
        }
        fun listToJson(l: List<RecForm>) = JSONArray().apply { l.forEach { put(it.toJson()) } }
        fun listFromJson(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { fromJson(a!!.getJSONObject(it)) }
    }
}

object Records {
    private val utf8 = Charsets.UTF_8

    fun normalizeUrl(u: String): String {
        val t = u.trim()
        if (t.isEmpty()) return ""
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t)) t else "https://$t"
    }

    fun igHandle(v: String): String {
        var t = v.trim()
        Regex("instagram\\.com/([^/?#]+)", RegexOption.IGNORE_CASE).find(t)?.let { t = it.groupValues[1] }
        return t.removePrefix("@").trimEnd('/')
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun phone(s: String) = s.replace(Regex("[\\s-]"), "")
    private fun vEsc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    // ---------- Wi‑Fi Simple Config ----------
    private val WIFI_AUTH = mapOf("wpa2" to 0x0020, "mixed" to 0x0022, "wpa" to 0x0002, "open" to 0x0001)

    fun wifiPayload(ssid: String, auth: String, key: String): ByteArray {
        val parts = mutableListOf<ByteArray>()
        fun tlv(type: Int, b: ByteArray) {
            parts += byteArrayOf((type shr 8).toByte(), type.toByte(), (b.size shr 8).toByte(), b.size.toByte()) + b
        }
        fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())
        val open = auth == "open"
        tlv(0x1026, byteArrayOf(1))
        tlv(0x1045, ssid.toByteArray(utf8))
        tlv(0x1003, u16(WIFI_AUTH[auth] ?: 0x0020))
        tlv(0x100F, u16(if (open) 0x0001 else 0x0008))
        tlv(0x1027, (if (open) "" else key).toByteArray(utf8))
        tlv(0x1020, ByteArray(6) { -1 })
        val inner = parts.fold(ByteArray(0)) { a, b -> a + b }
        return byteArrayOf(0x10, 0x0E, (inner.size shr 8).toByte(), inner.size.toByte()) + inner
    }

    fun parseWifi(b: ByteArray): Map<String, String> {
        val res = mutableMapOf<String, String>()
        fun walk(d: ByteArray) {
            var i = 0
            while (i + 4 <= d.size) {
                val type = (d.u(i) shl 8) or d.u(i + 1)
                val len = (d.u(i + 2) shl 8) or d.u(i + 3)
                if (i + 4 + len > d.size) break
                val v = d.copyOfRange(i + 4, i + 4 + len)
                when (type) {
                    0x100E -> walk(v)
                    0x1045 -> res["ssid"] = String(v, utf8)
                    0x1027 -> res["key"] = String(v, utf8)
                    0x1003 -> {
                        val a = (v.u(0) shl 8) or v.u(1)
                        res["auth"] = WIFI_AUTH.entries.firstOrNull { it.value == a }?.key ?: "wpa2"
                    }
                }
                i += 4 + len
            }
        }
        walk(b)
        return res
    }

    private fun mime(type: String, data: ByteArray) = NdefRecord.createMime(type, data)

    val TYPES: List<RecType> = listOf(
        RecType("url", "網址", listOf(Field("url", "網址", FieldKind.URL, "https://example.com")),
            check = { if (it["url"].isNullOrBlank()) "請輸入網址" else null },
            build = { NdefRecord.createUri(normalizeUrl(it["url"] ?: "")) }),
        RecType("ig", "Instagram 帳號", listOf(Field("handle", "IG 帳號（可貼整個網址）", ph = "@your_club")),
            check = { if (igHandle(it["handle"] ?: "").isEmpty()) "請輸入 IG 帳號" else null },
            build = { NdefRecord.createUri("https://www.instagram.com/${igHandle(it["handle"] ?: "")}/") }),
        RecType("text", "文字", listOf(
            Field("text", "文字內容", FieldKind.MULTI, "想放的文字"),
            Field("lang", "語言", FieldKind.SELECT, options = listOf("zh-TW" to "中文", "en" to "English", "ja" to "日本語"), def = "zh-TW"),
        ), check = { if (it["text"].isNullOrEmpty()) "請輸入文字" else null },
            build = { NdefRecord.createTextRecord(it["lang"]?.ifEmpty { null } ?: "zh-TW", it["text"] ?: "") }),
        RecType("tel", "電話", listOf(Field("tel", "電話號碼", FieldKind.PHONE, "0912345678 或 +886912345678")),
            check = { if (it["tel"].isNullOrBlank()) "請輸入電話" else null },
            build = { NdefRecord.createUri("tel:" + phone(it["tel"] ?: "")) }),
        RecType("sms", "簡訊", listOf(
            Field("to", "收件號碼", FieldKind.PHONE, "0912345678"),
            Field("body", "預設內容（可空白）", FieldKind.MULTI),
        ), check = { if (it["to"].isNullOrBlank()) "請輸入號碼" else null },
            build = {
                val b = it["body"] ?: ""
                NdefRecord.createUri("sms:" + phone(it["to"] ?: "") + if (b.isNotEmpty()) "?body=" + enc(b) else "")
            }),
        RecType("email", "Email", listOf(
            Field("to", "收件人", FieldKind.EMAIL, "name@example.com"),
            Field("subject", "主旨（可空白）"),
            Field("body", "內容（可空白）", FieldKind.MULTI),
        ), check = { if (it["to"].isNullOrBlank()) "請輸入收件人" else null },
            build = {
                val q = mutableListOf<String>()
                if (!it["subject"].isNullOrEmpty()) q += "subject=" + enc(it["subject"]!!)
                if (!it["body"].isNullOrEmpty()) q += "body=" + enc(it["body"]!!)
                NdefRecord.createUri("mailto:" + (it["to"] ?: "").trim() + if (q.isNotEmpty()) "?" + q.joinToString("&") else "")
            }),
        RecType("wifi", "Wi‑Fi 連線", listOf(
            Field("ssid", "網路名稱（SSID）"),
            Field("auth", "加密方式", FieldKind.SELECT, options = listOf("wpa2" to "WPA2", "mixed" to "WPA/WPA2", "wpa" to "WPA", "open" to "不加密（開放）"), def = "wpa2"),
            Field("key", "密碼"),
        ), note = "Android 碰一下就會詢問是否連線；iPhone 不支援 Wi‑Fi 標籤。",
            check = {
                if (it["ssid"].isNullOrEmpty()) "請輸入網路名稱"
                else if (it["auth"] != "open" && (it["key"] ?: "").length < 8) "密碼至少 8 個字元" else null
            },
            build = { mime("application/vnd.wfa.wsc", wifiPayload(it["ssid"] ?: "", it["auth"]?.ifEmpty { null } ?: "wpa2", it["key"] ?: "")) }),
        RecType("vcard", "名片（vCard）", listOf(
            Field("name", "姓名"), Field("tel", "電話", FieldKind.PHONE), Field("email", "Email", FieldKind.EMAIL),
            Field("org", "公司／社團"), Field("url", "網址", FieldKind.URL),
        ), note = "Android 會詢問是否加入聯絡人。名片內容較多，小容量標籤（NTAG213）可能放不下。",
            check = { if (it["name"].isNullOrEmpty()) "請輸入姓名" else null },
            build = {
                val l = mutableListOf("BEGIN:VCARD", "VERSION:3.0", "N:" + vEsc(it["name"] ?: "") + ";;;;", "FN:" + vEsc(it["name"] ?: ""))
                if (!it["tel"].isNullOrBlank()) l += "TEL;TYPE=CELL:" + it["tel"]!!.trim()
                if (!it["email"].isNullOrBlank()) l += "EMAIL:" + it["email"]!!.trim()
                if (!it["org"].isNullOrBlank()) l += "ORG:" + vEsc(it["org"]!!)
                if (!it["url"].isNullOrBlank()) l += "URL:" + normalizeUrl(it["url"]!!)
                l += "END:VCARD"
                mime("text/vcard", l.joinToString("\r\n").toByteArray(utf8))
            }),
        RecType("geo", "地圖位置", listOf(
            Field("lat", "緯度", FieldKind.NUMBER, "24.1790"),
            Field("lng", "經度", FieldKind.NUMBER, "120.6487"),
            Field("name", "地點名稱（可空白）", ph = "逢甲大學"),
        ), check = {
            val lat = it["lat"]?.trim()?.toDoubleOrNull(); val lng = it["lng"]?.trim()?.toDoubleOrNull()
            if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) "請輸入正確的經緯度" else null
        }, build = {
            val ll = "${it["lat"]!!.trim()},${it["lng"]!!.trim()}"
            val n = it["name"]?.trim() ?: ""
            NdefRecord.createUri("geo:$ll" + if (n.isNotEmpty()) "?q=$ll(${enc(n)})" else "")
        }),
        RecType("app", "開啟 Android App", listOf(Field("pkg", "App 套件名稱", ph = "com.instagram.android")),
            note = "碰一下就會開啟這個 App，沒安裝的話會開 Play 商店。通常放在網址記錄後面。",
            check = { if (!Regex("^[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+$").matches(it["pkg"]?.trim() ?: "")) "套件名稱格式像 com.example.app" else null },
            build = { NdefRecord.createApplicationRecord(it["pkg"]!!.trim()) }),
        RecType("mime", "自訂 MIME", listOf(
            Field("mtype", "MIME 類型", def = "application/json", ph = "application/json"),
            Field("data", "內容", FieldKind.MULTI, "{\"id\": 1}"),
        ), check = { if (!Regex("^[\\w.+-]+/[\\w.+-]+$").matches(it["mtype"]?.trim() ?: "")) "MIME 類型格式像 text/plain" else null },
            build = { mime(it["mtype"]!!.trim(), (it["data"] ?: "").toByteArray(utf8)) }),
        RecType("raw", "進階：原始記錄", listOf(
            Field("tnf", "TNF", FieldKind.SELECT, options = listOf(
                "1" to "1 Well-known", "2" to "2 MIME", "3" to "3 絕對 URI", "4" to "4 外部類型", "5" to "5 未知"), def = "4"),
            Field("rtype", "類型（文字）", ph = "example.com:mytype"),
            Field("payload", "內容（十六進位）", FieldKind.MULTI, "01 02 03"),
        ), check = { if ((it["tnf"] ?: "4") != "5" && it["rtype"].isNullOrEmpty()) "請輸入類型" else null },
            build = {
                val tnf = (it["tnf"]?.toIntOrNull() ?: 4).toShort()
                val t = if (tnf == NdefRecord.TNF_UNKNOWN) ByteArray(0) else (it["rtype"] ?: "").toByteArray(utf8)
                NdefRecord(tnf, t, ByteArray(0), fromHex(it["payload"] ?: ""))
            }),
        RecType("empty", "空記錄", emptyList(), build = { NdefRecord(NdefRecord.TNF_EMPTY, null, null, null) }),
    )

    fun type(key: String) = TYPES.firstOrNull { it.key == key } ?: TYPES[0]

    fun defaults(key: String) = RecForm(key, type(key).fields.filter { it.def.isNotEmpty() }.associate { it.k to it.def })

    /** 表單 → NDEF。有錯誤時回傳 (null, 錯誤訊息) */
    fun build(forms: List<RecForm>): Pair<NdefMessage?, String?> {
        if (forms.isEmpty()) return null to "至少要有一筆記錄"
        val recs = mutableListOf<NdefRecord>()
        forms.forEachIndexed { i, f ->
            val t = type(f.type)
            t.check(f.values)?.let { return null to "第 ${i + 1} 筆：$it" }
            try { recs += t.build(f.values) } catch (e: Exception) { return null to "第 ${i + 1} 筆：${e.message ?: "格式錯誤"}" }
        }
        return NdefMessage(recs.toTypedArray()) to null
    }

    fun recordSize(f: RecForm): Int? = try {
        NdefMessage(type(f.type).build(f.values)).byteArrayLength
    } catch (_: Exception) { null }

    /** NDEF 外面還要包 TLV（Type 2 標籤） */
    fun tlvSize(msgLen: Int) = msgLen + (if (msgLen < 255) 2 else 4) + 1

    // ---------- 讀出的記錄 → 顯示 ----------

    data class Shown(val kind: String, val value: String, val uri: Uri? = null, val detail: String? = null)

    private fun text(r: NdefRecord): Pair<String, String> {
        val p = r.payload
        val status = p.u(0)
        val langLen = status and 0x3F
        val cs: Charset = if (status and 0x80 != 0) Charsets.UTF_16 else utf8
        val lang = String(p, 1, langLen, Charsets.US_ASCII)
        return lang to String(p, 1 + langLen, p.size - 1 - langLen, cs)
    }

    fun show(r: NdefRecord): Shown {
        try {
            when (r.tnf) {
                NdefRecord.TNF_EMPTY -> return Shown("空記錄", "（沒有內容）")
                NdefRecord.TNF_WELL_KNOWN -> {
                    if (r.type.contentEquals(NdefRecord.RTD_URI)) {
                        val u = r.toUri()
                        val s = u.toString()
                        val kind = when {
                            s.startsWith("tel:") -> "電話"
                            s.startsWith("sms:") -> "簡訊"
                            s.startsWith("mailto:") -> "Email"
                            s.startsWith("geo:") -> "地圖位置"
                            s.contains("instagram.com/") -> "Instagram"
                            else -> "網址"
                        }
                        return Shown(kind, s, u)
                    }
                    if (r.type.contentEquals(NdefRecord.RTD_TEXT)) {
                        val (lang, t) = text(r)
                        return Shown("文字（$lang）", t)
                    }
                    if (r.type.contentEquals(NdefRecord.RTD_SMART_POSTER)) {
                        val inner = NdefMessage(r.payload).records.map { show(it) }
                        val u = inner.firstOrNull { it.uri != null }
                        return Shown("Smart Poster", inner.joinToString("\n") { it.value }, u?.uri)
                    }
                    return Shown("Well-known：${String(r.type, utf8)}", r.payload.hex())
                }
                NdefRecord.TNF_MIME_MEDIA -> {
                    val m = String(r.type, Charsets.US_ASCII)
                    return when (m.lowercase()) {
                        "application/vnd.wfa.wsc" -> {
                            val w = parseWifi(r.payload)
                            Shown("Wi‑Fi", "${w["ssid"] ?: ""}" + if (w["key"].isNullOrEmpty()) "" else "\n密碼：${w["key"]}")
                        }
                        "text/vcard", "text/x-vcard" -> Shown("名片", vcardSummary(String(r.payload, utf8)))
                        else -> if (m.startsWith("text/") || m.endsWith("json") || m.endsWith("xml"))
                            Shown("MIME：$m", String(r.payload, utf8)) else Shown("MIME：$m", r.payload.hex())
                    }
                }
                NdefRecord.TNF_ABSOLUTE_URI -> {
                    val s = String(r.type, utf8)
                    return Shown("絕對網址", s, Uri.parse(s))
                }
                NdefRecord.TNF_EXTERNAL_TYPE -> {
                    val t = String(r.type, utf8)
                    if (t == "android.com:pkg") return Shown("開啟 Android App", String(r.payload, utf8))
                    return Shown("外部類型：$t", r.payload.hex(), detail = printable(r.payload))
                }
                else -> return Shown("TNF ${r.tnf}", r.payload.hex())
            }
        } catch (e: Exception) {
            return Shown("無法解析的記錄", r.payload.hex())
        }
    }

    private fun printable(b: ByteArray): String? {
        val s = String(b, utf8)
        return if (s.all { !it.isISOControl() || it == '\n' }) s else null
    }

    private fun vcardSummary(v: String): String {
        val lines = v.lines().map { it.trim() }
        fun f(k: String) = lines.firstOrNull { it.uppercase().startsWith(k) }?.substringAfter(':')?.replace("\\,", ",")?.replace("\\;", ";")
        return listOfNotNull(f("FN"), f("TEL"), f("EMAIL"), f("ORG"), f("URL")).joinToString("\n")
    }

    // ---------- 讀出的記錄 → 可編輯的表單 ----------

    fun toForm(r: NdefRecord): RecForm {
        try {
            if (r.tnf == NdefRecord.TNF_EMPTY) return RecForm("empty")
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI)) {
                val s = r.toUri().toString()
                fun q(name: String) = Regex("[?&]$name=([^&]*)").find(s)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                Regex("^https?://(www\\.)?instagram\\.com/([^/?#]+)/?$", RegexOption.IGNORE_CASE).find(s)?.let {
                    return RecForm("ig", mapOf("handle" to it.groupValues[2]))
                }
                when {
                    s.startsWith("tel:") -> return RecForm("tel", mapOf("tel" to s.removePrefix("tel:")))
                    s.startsWith("sms:") -> return RecForm("sms", mapOf("to" to s.removePrefix("sms:").substringBefore('?'), "body" to q("body")))
                    s.startsWith("mailto:") -> return RecForm("email", mapOf(
                        "to" to s.removePrefix("mailto:").substringBefore('?'), "subject" to q("subject"), "body" to q("body")))
                    s.startsWith("geo:") -> {
                        val ll = s.removePrefix("geo:").substringBefore('?').split(',')
                        val name = Regex("\\(([^)]*)\\)").find(s)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                        return RecForm("geo", mapOf("lat" to ll.getOrElse(0) { "" }, "lng" to ll.getOrElse(1) { "" }, "name" to name))
                    }
                }
                return RecForm("url", mapOf("url" to s))
            }
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT)) {
                val (lang, t) = text(r)
                return RecForm("text", mapOf("text" to t, "lang" to lang))
            }
            if (r.tnf == NdefRecord.TNF_MIME_MEDIA) {
                val m = String(r.type, Charsets.US_ASCII)
                if (m.equals("application/vnd.wfa.wsc", true)) {
                    val w = parseWifi(r.payload)
                    return RecForm("wifi", mapOf("ssid" to (w["ssid"] ?: ""), "auth" to (w["auth"] ?: "wpa2"), "key" to (w["key"] ?: "")))
                }
                if (m.equals("text/vcard", true)) {
                    val lines = String(r.payload, utf8).lines().map { it.trim() }
                    fun f(k: String) = lines.firstOrNull { it.uppercase().startsWith(k) }?.substringAfter(':')
                        ?.replace("\\,", ",")?.replace("\\;", ";")?.replace("\\n", "\n") ?: ""
                    return RecForm("vcard", mapOf("name" to f("FN"), "tel" to f("TEL"), "email" to f("EMAIL"), "org" to f("ORG"), "url" to f("URL")))
                }
                val s = String(r.payload, utf8)
                if (s.toByteArray(utf8).contentEquals(r.payload)) return RecForm("mime", mapOf("mtype" to m, "data" to s))
            }
            if (r.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(r.type, utf8) == "android.com:pkg")
                return RecForm("app", mapOf("pkg" to String(r.payload, utf8)))
        } catch (_: Exception) {
        }
        val tnf = r.tnf.toInt().takeIf { it in 1..5 } ?: 5
        return RecForm("raw", mapOf("tnf" to tnf.toString(), "rtype" to String(r.type, utf8), "payload" to r.payload.hex()))
    }

    fun summary(m: NdefMessage?): String {
        if (m == null) return "（沒有 NDEF 內容）"
        return m.records.joinToString("、") { show(it).value.lineSequence().first().take(60) }
    }
}
