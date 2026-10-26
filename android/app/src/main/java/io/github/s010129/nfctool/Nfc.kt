package io.github.s010129.nfctool

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcV
import java.io.IOException

class NfcFail(msg: String) : Exception(msg)

/** 晶片型號（Type 2 標籤用 GET_VERSION 判斷） */
data class Chip(val name: String, val pages: Int, val cfgPage: Int?) {
    val userBytes get() = if (cfgPage != null) (cfgPage - 1 - 4) * 4 else (pages - 4) * 4
}

data class TagReport(
    val time: Long,
    val uid: ByteArray,
    val techs: List<String>,
    val chip: Chip?,
    val version: ByteArray?,
    val signature: ByteArray?,
    val signatureOk: String?,         // 驗證通過時是金鑰名稱
    val pages: List<ByteArray>,       // 讀得到的頁（每頁 4 bytes）
    val pagesTotal: Int,
    val auth0: Int?,
    val access: Int?,
    val ndefType: String?,
    val maxSize: Int?,
    val writable: Boolean?,
    val canLock: Boolean?,
    val formatable: Boolean,
    val message: NdefMessage?,
    val extra: List<Pair<String, String>>,
) {
    val uidHex get() = uid.hex(":")
    val passwordState: String?
        get() {
            val a = auth0 ?: return null
            val total = chip?.pages ?: return null
            if (a >= total) return "沒有密碼"
            val prot = (access ?: 0) and 0x80 != 0
            return if (prot) "讀寫都需要密碼（從第 $a 頁起）" else "寫入需要密碼（從第 $a 頁起）"
        }
    val hasPassword get() = auth0 != null && chip != null && auth0 < chip.pages
    val messageSize get() = message?.byteArrayLength ?: 0
}

object Nfc {
    private const val CMD_GET_VERSION: Byte = 0x60
    private const val CMD_READ_SIG: Byte = 0x3C
    private const val CMD_PWD_AUTH: Byte = 0x1B

    fun chipFromVersion(v: ByteArray?, mu: MifareUltralight?): Chip? {
        if (v != null && v.size >= 8 && v.u(1) == 0x04) {
            val type = v.u(2)
            val size = v.u(6)
            if (type == 0x04) when (size) {
                0x0B -> return Chip("NTAG210", 20, 0x10)
                0x0E -> return Chip("NTAG212", 41, 0x25)
                0x0F -> return Chip("NTAG213", 45, 0x29)
                0x11 -> return Chip("NTAG215", 135, 0x83)
                0x13 -> return Chip("NTAG216", 231, 0xE3)
            }
            if (type == 0x03) when (size) {
                0x0B -> return Chip("MIFARE Ultralight EV1 (48 B)", 20, 0x10)
                0x0E -> return Chip("MIFARE Ultralight EV1 (128 B)", 41, 0x25)
            }
            return Chip("NXP Type 2（版本 ${v.hex("")}）", 16, null)
        }
        return when (mu?.type) {
            MifareUltralight.TYPE_ULTRALIGHT_C -> Chip("MIFARE Ultralight C", 48, null)
            MifareUltralight.TYPE_ULTRALIGHT -> Chip("MIFARE Ultralight（或相容晶片）", 16, null)
            else -> null
        }
    }

    private fun MifareUltralight.tryCmd(vararg cmd: Byte): ByteArray? = try {
        transceive(cmd)
    } catch (e: TagLostException) {
        throw e
    } catch (e: IOException) {
        // 指令失敗後晶片會進入休眠，重新連線才能繼續
        reconnect(); null
    }

    private fun MifareUltralight.reconnect() {
        try { close() } catch (_: IOException) {}
        connect()
    }

    private fun MifareUltralight.auth(pwd: ByteArray): ByteArray {
        val r = try { transceive(byteArrayOf(CMD_PWD_AUTH) + pwd) } catch (e: TagLostException) { throw e } catch (e: IOException) { null }
        if (r == null || r.size < 2) throw NfcFail("密碼錯誤，標籤拒絕了這個密碼")
        return r
    }

    private fun MifareUltralight.write(page: Int, data: ByteArray) {
        try {
            writePage(page, data)
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            throw NfcFail("第 $page 頁寫入失敗：標籤可能已鎖定或有密碼保護")
        }
    }

    // ---------- 讀取 ----------

    fun read(tag: Tag): TagReport {
        val techs = tag.techList.map { it.substringAfterLast('.') }
        var chip: Chip? = null
        var version: ByteArray? = null
        var sig: ByteArray? = null
        var sigOk: String? = null
        val pages = mutableListOf<ByteArray>()
        var total = 0
        var auth0: Int? = null
        var access: Int? = null
        val extra = mutableListOf<Pair<String, String>>()

        NfcA.get(tag)?.let {
            extra += "ATQA / SAK" to "${it.atqa.hex("")} / %02X".format(it.sak.toInt() and 0xFF)
        }

        MifareUltralight.get(tag)?.let { mu ->
            try {
                mu.connect()
                version = mu.tryCmd(CMD_GET_VERSION)?.takeIf { it.size >= 8 }
                chip = chipFromVersion(version, mu)
                if (version != null) {
                    sig = mu.tryCmd(CMD_READ_SIG, 0x00)?.takeIf { it.size == 32 }
                    sig?.let { s -> sigOk = Ecc.checkNxp(tag.id, s) }
                }
                total = chip?.pages ?: 16
                var p = 0
                while (p < total) {
                    val chunk = try { mu.readPages(p) } catch (e: TagLostException) { throw e } catch (e: IOException) { null }
                    if (chunk == null || chunk.size < 16) break
                    for (i in 0 until minOf(4, total - p)) pages += chunk.copyOfRange(i * 4, i * 4 + 4)
                    p += 4
                }
                chip?.cfgPage?.let { c ->
                    if (pages.size > c + 1) {
                        auth0 = pages[c].u(3)
                        access = pages[c + 1].u(0)
                    }
                }
            } catch (_: IOException) {
            } finally {
                try { mu.close() } catch (_: IOException) {}
            }
        }

        MifareClassic.get(tag)?.let {
            val t = when (it.type) {
                MifareClassic.TYPE_CLASSIC -> "Classic"; MifareClassic.TYPE_PLUS -> "Plus"; MifareClassic.TYPE_PRO -> "Pro"; else -> ""
            }
            extra += "MIFARE" to "$t ${it.size} bytes，${it.sectorCount} 個磁區"
        }
        IsoDep.get(tag)?.let { iso ->
            (iso.historicalBytes ?: iso.hiLayerResponse)?.let { extra += "ISO-DEP" to it.hex() }
        }
        NfcV.get(tag)?.let { extra += "NfcV DSFID" to "%02X".format(it.dsfId.toInt() and 0xFF) }

        var ndefType: String? = null
        var maxSize: Int? = null
        var writable: Boolean? = null
        var canLock: Boolean? = null
        var message: NdefMessage? = null
        Ndef.get(tag)?.let { nd ->
            ndefType = nd.type.substringAfterLast('.')
            maxSize = nd.maxSize
            writable = nd.isWritable
            canLock = nd.canMakeReadOnly()
            message = nd.cachedNdefMessage
            if (message == null) try {
                nd.connect(); message = nd.ndefMessage
            } catch (_: Exception) {
            } finally {
                try { nd.close() } catch (_: IOException) {}
            }
        }
        if (message == null && pages.size > 4) message = parseTlv(pages)

        return TagReport(
            System.currentTimeMillis(), tag.id, techs, chip, version, sig, sigOk, pages, total,
            auth0, access, ndefType, maxSize, writable, canLock,
            NdefFormatable.get(tag) != null, message, extra,
        )
    }

    /** 從原始頁面解析 Type 2 的 NDEF TLV */
    private fun parseTlv(pages: List<ByteArray>): NdefMessage? {
        val data = pages.drop(4).fold(ByteArray(0)) { a, b -> a + b }
        val (start, len) = findNdefTlv(data) ?: return null
        if (len == 0 || start + len > data.size) return null
        return try { NdefMessage(data.copyOfRange(start, start + len)) } catch (_: FormatException) { null }
    }

    /** 回傳 NDEF 內容在資料區中的位置與長度 */
    private fun findNdefTlv(data: ByteArray): Pair<Int, Int>? {
        var i = 0
        while (i < data.size) {
            val t = data.u(i)
            if (t == 0x00) { i++; continue }
            if (t == 0xFE || i + 1 >= data.size) return null
            var len = data.u(i + 1)
            var hdr = 2
            if (len == 0xFF) {
                if (i + 3 >= data.size) return null
                len = (data.u(i + 2) shl 8) or data.u(i + 3); hdr = 4
            }
            if (t == 0x03) return (i + hdr) to len
            i += hdr + len
        }
        return null
    }

    // ---------- 寫入 ----------

    data class WriteOpts(
        val protectExisting: Boolean = false,
        val password: ByteArray? = null,
        val lockAfter: Boolean = false,
    )

    fun write(tag: Tag, msg: NdefMessage, o: WriteOpts) {
        val mu = MifareUltralight.get(tag)
        if (mu != null && writeType2(mu, msg, o)) {
            if (o.lockAfter) lock(tag)
            return
        }
        val nd = Ndef.get(tag)
        if (nd != null) {
            try {
                nd.connect()
                if (!nd.isWritable) throw NfcFail("這張標籤已鎖定，無法寫入")
                if (msg.byteArrayLength > nd.maxSize) throw NfcFail("內容太大：${msg.byteArrayLength} bytes，這張標籤最多 ${nd.maxSize} bytes")
                if (o.protectExisting) {
                    val old = try { nd.ndefMessage } catch (_: Exception) { null }
                    if (old != null && !isEmpty(old)) throw NfcFail("標籤已有資料，依設定不覆寫")
                }
                nd.writeNdefMessage(msg)
                if (o.lockAfter) {
                    if (!nd.canMakeReadOnly() || !nd.makeReadOnly()) throw NfcFail("已寫入，但這張標籤不支援鎖定")
                }
            } catch (e: FormatException) {
                throw NfcFail("內容格式錯誤")
            } finally {
                try { nd.close() } catch (_: IOException) {}
            }
            return
        }
        val fm = NdefFormatable.get(tag) ?: throw NfcFail("這張卡不支援 NDEF（例如悠遊卡、門禁卡），無法寫入")
        try {
            fm.connect()
            if (o.lockAfter) fm.formatReadOnly(msg) else fm.format(msg)
        } catch (e: FormatException) {
            throw NfcFail("格式化失敗")
        } catch (e: IOException) {
            throw NfcFail("格式化失敗：這張卡可能不是空白卡")
        } finally {
            try { fm.close() } catch (_: IOException) {}
        }
    }

    /** NTAG / Ultralight 直接寫頁面，才能先用密碼解鎖。回傳 false 表示不是 NDEF 格式的 Type 2，改用一般方式。 */
    private fun writeType2(mu: MifareUltralight, msg: NdefMessage, o: WriteOpts): Boolean {
        try {
            mu.connect()
            val head = try { mu.readPages(0) } catch (e: TagLostException) { throw e } catch (e: IOException) { return false }
            val cc = head.copyOfRange(12, 16)
            if (cc.u(0) != 0xE1) return false
            if (o.password != null) mu.auth(o.password)
            if (cc.u(3) and 0xF0 != 0) throw NfcFail("這張標籤已永久鎖定，無法寫入")
            val capacity = cc.u(2) * 8

            // 讀出舊內容，用來判斷是否有資料和清掉多出來的舊資料
            val oldArea = readArea(mu, capacity)
            val old = findNdefTlv(oldArea)
            if (o.protectExisting && old != null && old.second > 0) {
                val m = try { NdefMessage(oldArea.copyOfRange(old.first, old.first + old.second)) } catch (_: Exception) { null }
                if (m == null || !isEmpty(m)) throw NfcFail("標籤已有資料，依設定不覆寫")
            }

            val body = msg.toByteArray()
            val tlv = if (body.size < 0xFF) byteArrayOf(0x03, body.size.toByte()) + body + byteArrayOf(0xFE.toByte())
            else byteArrayOf(0x03, 0xFF.toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body + byteArrayOf(0xFE.toByte())
            if (tlv.size > capacity) throw NfcFail("內容太大：需要 ${tlv.size} bytes，這張標籤最多 $capacity bytes")

            val oldEnd = old?.let { it.first + it.second + 1 } ?: 0
            val end = maxOf(tlv.size, minOf(oldEnd, capacity))
            val area = ByteArray((end + 3) / 4 * 4)
            tlv.copyInto(area)
            // 先把第一頁改成「空內容」，寫完其他頁才寫回真正的長度，中途移開也不會留下壞掉的內容
            mu.write(4, byteArrayOf(0x03, 0x00, 0xFE.toByte(), 0x00))
            for (i in 1 until area.size / 4) mu.write(4 + i, area.copyOfRange(i * 4, i * 4 + 4))
            mu.write(4, area.copyOfRange(0, 4))
            return true
        } catch (e: TagLostException) {
            throw NfcFail("標籤移開太快，請貼穩再試一次")
        } catch (e: NfcFail) {
            throw e
        } catch (e: IOException) {
            throw NfcFail("寫入失敗：${e.message ?: "通訊錯誤"}")
        } finally {
            try { mu.close() } catch (_: IOException) {}
        }
    }

    private fun readArea(mu: MifareUltralight, capacity: Int): ByteArray {
        var out = ByteArray(0)
        var p = 4
        while (out.size < capacity) {
            val chunk = try { mu.readPages(p) } catch (e: TagLostException) { throw e } catch (e: IOException) { break }
            out += chunk
            // 讀到 TLV 結尾就不用再讀
            val t = findNdefTlv(out)
            if (t != null && t.first + t.second < out.size) break
            if (out.isNotEmpty() && out.u(0) == 0xFE) break
            p += 4
        }
        return if (out.size > capacity) out.copyOf(capacity) else out
    }

    fun isEmpty(m: NdefMessage) = m.records.all { it.tnf == NdefRecord.TNF_EMPTY }

    fun emptyMessage() = NdefMessage(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null))

    fun lock(tag: Tag) {
        val nd = Ndef.get(tag) ?: throw NfcFail("這張標籤不是 NDEF 格式，無法鎖定")
        try {
            nd.connect()
            if (!nd.isWritable) throw NfcFail("這張標籤本來就已經鎖定了")
            if (!nd.canMakeReadOnly()) throw NfcFail("這張標籤不支援鎖定")
            if (!nd.makeReadOnly()) throw NfcFail("鎖定失敗")
        } catch (e: TagLostException) {
            throw NfcFail("標籤移開太快，請貼穩再試一次")
        } catch (e: IOException) {
            throw NfcFail("鎖定失敗：標籤若有密碼請先移除密碼")
        } finally {
            try { nd.close() } catch (_: IOException) {}
        }
    }

    // ---------- 密碼 ----------

    data class PwdOpts(
        val pwd: ByteArray,
        val pack: ByteArray,
        val protectRead: Boolean,
        val startPage: Int,
        val authLimit: Int,
        val oldPwd: ByteArray?,
    )

    private fun <T> withChip(tag: Tag, block: (MifareUltralight, Chip) -> T): T {
        val mu = MifareUltralight.get(tag) ?: throw NfcFail("只有 NTAG213/215/216 等標籤可以設定密碼")
        try {
            mu.connect()
            val chip = chipFromVersion(mu.tryCmd(CMD_GET_VERSION)?.takeIf { it.size >= 8 }, mu)
            if (chip?.cfgPage == null) throw NfcFail("這張標籤（${chip?.name ?: "未知晶片"}）不支援密碼")
            return block(mu, chip)
        } catch (e: TagLostException) {
            throw NfcFail("標籤移開太快，請貼穩再試一次")
        } catch (e: NfcFail) {
            throw e
        } catch (e: IOException) {
            throw NfcFail("通訊失敗：${e.message ?: "請再試一次"}")
        } finally {
            try { mu.close() } catch (_: IOException) {}
        }
    }

    fun setPassword(tag: Tag, o: PwdOpts): Chip = withChip(tag) { mu, chip ->
        val c = chip.cfgPage!!
        if (o.oldPwd != null) mu.auth(o.oldPwd)
        val cfg = mu.readPages(c)
        val cfg0 = cfg.copyOfRange(0, 4)
        val cfg1 = cfg.copyOfRange(4, 8)
        val curAuth0 = cfg0.u(3)
        if (curAuth0 < chip.pages && o.oldPwd == null) throw NfcFail("這張標籤已經有密碼了，請填入舊密碼")
        if (cfg1.u(0) and 0x40 != 0) throw NfcFail("這張標籤的設定已被永久鎖住，無法設定密碼")
        mu.write(c + 2, o.pwd)
        mu.write(c + 3, o.pack + byteArrayOf(0, 0))
        cfg1[0] = ((if (o.protectRead) 0x80 else 0) or (o.authLimit and 0x07)).toByte()
        mu.write(c + 1, cfg1)
        cfg0[3] = o.startPage.toByte()
        mu.write(c, cfg0)
        chip
    }

    fun removePassword(tag: Tag, pwd: ByteArray): Chip = withChip(tag) { mu, chip ->
        val c = chip.cfgPage!!
        mu.auth(pwd)
        val cfg = mu.readPages(c)
        val cfg0 = cfg.copyOfRange(0, 4)
        val cfg1 = cfg.copyOfRange(4, 8)
        cfg0[3] = 0xFF.toByte()
        mu.write(c, cfg0)
        cfg1[0] = (cfg1.u(0) and 0x40).toByte()
        mu.write(c + 1, cfg1)
        mu.write(c + 2, byteArrayOf(-1, -1, -1, -1))
        mu.write(c + 3, byteArrayOf(0, 0, 0, 0))
        chip
    }

    /** 只測試密碼對不對 */
    fun testPassword(tag: Tag, pwd: ByteArray): Boolean = withChip(tag) { mu, _ ->
        try { mu.auth(pwd); true } catch (_: NfcFail) { false }
    }
}
