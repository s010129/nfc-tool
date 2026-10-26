package io.github.s010129.nfctool

import android.app.Application
import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.TagLostException
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.atomic.AtomicBoolean

/** 等待使用者把標籤靠近時要做的事 */
sealed class Op(val title: String, val text: String) {
    open val count: Int get() = 0

    class Write(val msg: NdefMessage, val opts: Nfc.WriteOpts, val batch: Boolean, val summary: String) :
        Op(if (batch) "連續寫入中" else "請把標籤靠近手機", if (batch) "一張一張貼上去，同一張不會重複寫。完成後按「完成」。" else "要寫入：$summary") {
        val done = mutableSetOf<String>()
        override var count by mutableIntStateOf(0)
    }

    object CloneRead : Op("複製：先貼來源標籤", "請把「要被複製的」標籤靠近手機")

    class CloneWrite(val msg: NdefMessage, val sourceUid: String) :
        Op("複製：換貼新標籤", "請把「要寫入的」標籤靠近手機，可以連續複製多張。") {
        override var count by mutableIntStateOf(0)
    }

    object Erase : Op("清除標籤", "請把要清除的標籤靠近手機")
    object Lock : Op("永久鎖定", "請把要鎖定的標籤靠近手機。鎖定後無法復原！")
    class SetPwd(val opts: Nfc.PwdOpts) : Op("設定密碼", "請把標籤靠近手機，貼穩不要移動")
    class RemovePwd(val pwd: ByteArray) : Op("移除密碼", "請把標籤靠近手機，貼穩不要移動")
    class TestPwd(val pwd: ByteArray) : Op("測試密碼", "請把標籤靠近手機")
}

enum class NfcState { OK, OFF, NONE }

class AppVm(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    var tab by mutableIntStateOf(0)
    var nfcState by mutableStateOf(NfcState.OK)
    var op by mutableStateOf<Op?>(null)
    val reports = mutableStateListOf<TagReport>()
    val forms = mutableStateListOf<RecForm>().apply { addAll(store.draft().ifEmpty { listOf(Records.defaults("url")) }) }
    var chipIdx by mutableIntStateOf(store.getInt("chip", 1))
    var optProtect by mutableStateOf(false)
    var optBatch by mutableStateOf(false)
    var optLock by mutableStateOf(false)
    var writePwd by mutableStateOf("")
    val history = mutableStateListOf<HistoryItem>().apply { addAll(store.history()) }
    val templates = mutableStateListOf<Template>().apply { addAll(store.templates()) }

    var snack by mutableStateOf<Pair<String, Boolean>?>(null)
    private var snackId = 0L
    var snackKey by mutableStateOf(0L)

    fun toast(msg: String, err: Boolean = false) {
        snack = msg to err
        snackKey = ++snackId
    }

    fun saveDraft() = store.saveDraft(forms.toList())

    fun setChip(i: Int) { chipIdx = i; store.putInt("chip", i) }

    private fun log(action: String, uid: String, detail: String, ok: Boolean = true) {
        history.add(0, HistoryItem(System.currentTimeMillis(), action, uid, detail, ok))
        while (history.size > 500) history.removeAt(history.lastIndex)
        store.saveHistory(history.toList())
    }

    fun clearHistory() { history.clear(); store.saveHistory(emptyList()) }

    fun saveTemplate(name: String) {
        templates.removeAll { it.name == name }
        templates.add(0, Template(name, forms.toList()))
        store.saveTemplates(templates.toList())
        toast("已存成常用：$name")
    }

    fun deleteTemplate(t: Template) { templates.remove(t); store.saveTemplates(templates.toList()) }

    fun loadForms(l: List<RecForm>) {
        forms.clear(); forms.addAll(l.ifEmpty { listOf(Records.defaults("url")) }); saveDraft()
    }

    private fun vibrate(ok: Boolean) {
        try {
            val v = getApplication<Application>().getSystemService(Vibrator::class.java) ?: return
            v.vibrate(if (ok) VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
            else VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        } catch (_: Exception) {}
    }

    private fun ui(block: () -> Unit) = main.post(block)

    // ---------- 開始操作 ----------

    fun startWrite() {
        val (msg, err) = Records.build(forms)
        if (msg == null) { toast(err ?: "內容有誤", true); return }
        val pwd = writePwd.trim().takeIf { it.isNotEmpty() }?.let { derivePassword(it).first }
        op = Op.Write(msg, Nfc.WriteOpts(optProtect, pwd, optLock), optBatch, Records.summary(msg))
    }

    fun startCloneFrom(r: TagReport) {
        val m = r.message ?: run { toast("這張標籤沒有內容可以複製", true); return }
        op = Op.CloneWrite(m, r.uidHex)
    }

    fun cancel() {
        val o = op
        op = null
        val n = o?.count ?: 0
        if (n > 0) toast("完成，共寫入 $n 張")
    }

    // ---------- 標籤靠近（在 NFC 執行緒上） ----------

    fun onTag(tag: Tag) {
        if (!busy.compareAndSet(false, true)) return
        val uid = tag.id.hex(":")
        val cur = op
        try {
            when (cur) {
                null -> {
                    val r = Nfc.read(tag)
                    ui {
                        reports.add(0, r)
                        while (reports.size > 20) reports.removeAt(reports.lastIndex)
                        tab = 0
                        log("讀取", uid, (r.chip?.name?.let { "$it：" } ?: "") + Records.summary(r.message))
                        vibrate(true)
                    }
                }
                is Op.Write -> {
                    if (cur.batch && uid in cur.done) {
                        ui { toast("這張已經寫過了，請換下一張"); vibrate(false) }
                        return
                    }
                    Nfc.write(tag, cur.msg, cur.opts)
                    ui {
                        val lock = if (cur.opts.lockAfter) "（已鎖定）" else ""
                        log("寫入", uid, cur.summary + lock)
                        vibrate(true)
                        if (cur.batch) {
                            cur.done += uid; cur.count++
                            toast("第 ${cur.count} 張寫入成功$lock")
                        } else {
                            op = null; toast("寫入成功$lock")
                        }
                    }
                }
                Op.CloneRead -> {
                    val r = Nfc.read(tag)
                    val m = r.message
                    ui {
                        if (m == null || Nfc.isEmpty(m)) { toast("這張標籤沒有內容可以複製", true); vibrate(false) }
                        else {
                            log("複製來源", uid, Records.summary(m))
                            op = Op.CloneWrite(m, uid); vibrate(true)
                        }
                    }
                }
                is Op.CloneWrite -> {
                    if (uid == cur.sourceUid) { ui { toast("這是來源標籤，請換另一張"); vibrate(false) }; return }
                    Nfc.write(tag, cur.msg, Nfc.WriteOpts())
                    ui { cur.count++; log("複製", uid, Records.summary(cur.msg)); toast("已複製 ${cur.count} 張"); vibrate(true) }
                }
                Op.Erase -> {
                    Nfc.write(tag, Nfc.emptyMessage(), Nfc.WriteOpts(password = writePwd.trim().takeIf { it.isNotEmpty() }?.let { derivePassword(it).first }))
                    ui { op = null; log("清除", uid, "已清除內容"); toast("已清除"); vibrate(true) }
                }
                Op.Lock -> {
                    Nfc.lock(tag)
                    ui { op = null; log("鎖定", uid, "已永久鎖定"); toast("已永久鎖定"); vibrate(true) }
                }
                is Op.SetPwd -> {
                    val chip = Nfc.setPassword(tag, cur.opts)
                    ui {
                        op = null
                        val mode = if (cur.opts.protectRead) "讀寫保護" else "寫入保護"
                        log("設定密碼", uid, "${chip.name}，$mode，PWD ${cur.opts.pwd.hex("")} / PACK ${cur.opts.pack.hex("")}")
                        toast("密碼設定完成（$mode）"); vibrate(true)
                    }
                }
                is Op.RemovePwd -> {
                    val chip = Nfc.removePassword(tag, cur.pwd)
                    ui { op = null; log("移除密碼", uid, chip.name); toast("密碼已移除"); vibrate(true) }
                }
                is Op.TestPwd -> {
                    val ok = Nfc.testPassword(tag, cur.pwd)
                    ui { op = null; toast(if (ok) "密碼正確 ✓" else "密碼不對", !ok); vibrate(ok) }
                }
            }
        } catch (e: NfcFail) {
            ui { fail(cur, uid, e.message ?: "失敗") }
        } catch (e: TagLostException) {
            ui { fail(cur, uid, "標籤移開太快，請貼穩再試一次") }
        } catch (e: Exception) {
            ui { fail(cur, uid, "失敗：${e.message ?: e.javaClass.simpleName}") }
        } finally {
            busy.set(false)
        }
    }

    private fun fail(cur: Op?, uid: String, msg: String) {
        vibrate(false)
        toast(msg, true)
        val action = when (cur) {
            null -> "讀取"; is Op.Write -> "寫入"; Op.CloneRead, is Op.CloneWrite -> "複製"; Op.Erase -> "清除"
            Op.Lock -> "鎖定"; is Op.SetPwd -> "設定密碼"; is Op.RemovePwd -> "移除密碼"; is Op.TestPwd -> "測試密碼"
        }
        log(action, uid, msg, ok = false)
    }

    fun exportHistory(): String = org.json.JSONArray().apply { history.forEach { put(it.toJson()) } }.toString(2)
}
