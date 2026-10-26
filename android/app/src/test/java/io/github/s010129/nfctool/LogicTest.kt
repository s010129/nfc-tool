package io.github.s010129.nfctool

import android.nfc.NdefMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicTest {
    @Test
    fun eccVerifiesKnownSignature() {
        // 用 python-ecdsa 在 secp128r1 上產生的測試簽章（不雜湊，UID 直接當訊息）
        val key = "048F008389DD9BB53E542C0FE6FCA8B7F0D47824702E1AA425DF228DAD490B36F8"
        val uid = fromHex("04A1B2C3D4E5F6")
        val sig = fromHex("DD2008F26A4B15BFD6FB1BBEB4FBE5A8A8ADA06550B6BDD8CBCED317C5A6AA32")
        assertTrue(Ecc.verify(key, uid, sig))
        assertFalse(Ecc.verify(key, fromHex("04A1B2C3D4E5F7"), sig))
        assertNull(Ecc.checkNxp(uid, sig))
    }

    @Test
    fun passwordDerivation() {
        assertEquals("12345678", derivePassword("12345678").first.hex(""))
        assertEquals(4, derivePassword("社團").first.size)
        assertEquals(2, derivePassword("社團").second.size)
    }

    private fun roundTrip(f: RecForm): RecForm {
        val (m, err) = Records.build(listOf(f))
        assertNull(err)
        val back = NdefMessage(m!!.toByteArray())
        return Records.toForm(back.records[0])
    }

    @Test
    fun recordsRoundTrip() {
        assertEquals("fcu_maker", roundTrip(RecForm("ig", mapOf("handle" to "@fcu_maker"))).v("handle"))
        assertEquals("https://example.com/a?b=1", roundTrip(RecForm("url", mapOf("url" to "example.com/a?b=1"))).v("url"))
        val t = roundTrip(RecForm("text", mapOf("text" to "逢甲資電", "lang" to "zh-TW")))
        assertEquals("逢甲資電", t.v("text")); assertEquals("zh-TW", t.v("lang"))
        val w = roundTrip(RecForm("wifi", mapOf("ssid" to "Club", "auth" to "wpa2", "key" to "12345678")))
        assertEquals("Club", w.v("ssid")); assertEquals("12345678", w.v("key")); assertEquals("wpa2", w.v("auth"))
        val s = roundTrip(RecForm("sms", mapOf("to" to "0912345678", "body" to "你好 hi")))
        assertEquals("0912345678", s.v("to")); assertEquals("你好 hi", s.v("body"))
        val e = roundTrip(RecForm("email", mapOf("to" to "a@b.c", "subject" to "Hi", "body" to "")))
        assertEquals("a@b.c", e.v("to")); assertEquals("Hi", e.v("subject"))
        val g = roundTrip(RecForm("geo", mapOf("lat" to "24.179", "lng" to "120.6487", "name" to "逢甲大學")))
        assertEquals("24.179", g.v("lat")); assertEquals("逢甲大學", g.v("name"))
        val v = roundTrip(RecForm("vcard", mapOf("name" to "王小明", "tel" to "0912", "org" to "A, B")))
        assertEquals("王小明", v.v("name")); assertEquals("A, B", v.v("org"))
        assertEquals("com.instagram.android", roundTrip(RecForm("app", mapOf("pkg" to "com.instagram.android"))).v("pkg"))
        assertEquals("tel", roundTrip(RecForm("tel", mapOf("tel" to "0912 345 678"))).type)
        assertEquals("empty", roundTrip(RecForm("empty")).type)
        val r = roundTrip(RecForm("raw", mapOf("tnf" to "4", "rtype" to "x.com:t", "payload" to "01 02")))
        assertEquals("raw", r.type); assertEquals("01 02", r.v("payload"))
    }

    @Test
    fun validation() {
        assertTrue(Records.build(listOf(RecForm("wifi", mapOf("ssid" to "a", "key" to "123")))).second!!.contains("8"))
        assertTrue(Records.build(listOf(RecForm("geo", mapOf("lat" to "999", "lng" to "1")))).second != null)
        // IG 網址大約 40 bytes，NTAG215 放得下
        val (m, _) = Records.build(listOf(RecForm("ig", mapOf("handle" to "fcu_maker_club"))))
        assertTrue(Records.tlvSize(m!!.byteArrayLength) < 50)
    }

    @Test
    fun showsRecords() {
        val (m, _) = Records.build(listOf(RecForm("ig", mapOf("handle" to "x")), RecForm("wifi", mapOf("ssid" to "S", "auth" to "open"))))
        val shown = m!!.records.map { Records.show(it) }
        assertEquals("Instagram", shown[0].kind)
        assertEquals("Wi‑Fi", shown[1].kind)
    }
}
