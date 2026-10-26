package io.github.s010129.nfctool

import java.math.BigInteger

/**
 * NXP 原廠簽章（Originality Signature）驗證。
 * NTAG21x / Ultralight EV1 出廠時用 NXP 私鑰對 UID 簽章（secp128r1 ECDSA，不做雜湊），
 * 用公開的 NXP 公鑰驗證就能知道晶片是不是 NXP 原廠。
 */
object Ecc {
    private val P = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val N = BigInteger("FFFFFFFE0000000075A30D1B9038A115", 16)
    private val G = Pt(
        BigInteger("161FF7528B899B2D0C28607CA52C5B86", 16),
        BigInteger("CF5AC8395BAFEB13C02DA292DDED7A83", 16),
    )

    /** 已知的 NXP 公鑰（未壓縮格式 04||X||Y） */
    val NXP_KEYS = listOf(
        "NTAG21x" to "04494E1A386D3D3CFE3DC10E5DE68A499B1C202DB5B132393E89ED19FE5BE8BC61",
        "Ultralight EV1" to "0490933BDCD6E99B4E255E3DA55389A827564E11718E017292FAF23226A96614B8",
    )

    private data class Pt(val x: BigInteger, val y: BigInteger)

    private fun add(p: Pt?, q: Pt?): Pt? {
        if (p == null) return q
        if (q == null) return p
        val l = if (p.x == q.x) {
            if ((p.y + q.y).mod(P) == BigInteger.ZERO) return null
            (p.x.pow(2).multiply(BigInteger.valueOf(3)) + A) * (p.y.shiftLeft(1)).modInverse(P)
        } else {
            (q.y - p.y) * (q.x - p.x).modInverse(P)
        }.mod(P)
        val x = (l.pow(2) - p.x - q.x).mod(P)
        val y = (l * (p.x - x) - p.y).mod(P)
        return Pt(x, y)
    }

    private fun mul(k: BigInteger, p: Pt): Pt? {
        var r: Pt? = null
        var a: Pt? = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, a)
            a = add(a, a)
        }
        return r
    }

    private fun parseKey(hex: String): Pt {
        val b = hex.substring(2)
        return Pt(BigInteger(b.substring(0, 32), 16), BigInteger(b.substring(32), 16))
    }

    /** 驗證 32 bytes 簽章（r||s），message 直接當整數用 */
    fun verify(publicKeyHex: String, message: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != 32) return false
        val r = BigInteger(1, sig.copyOfRange(0, 16))
        val s = BigInteger(1, sig.copyOfRange(16, 32))
        if (r.signum() == 0 || r >= N || s.signum() == 0 || s >= N) return false
        val e = BigInteger(1, message)
        val w = s.modInverse(N)
        val u1 = (e * w).mod(N)
        val u2 = (r * w).mod(N)
        val pt = add(mul(u1, G), mul(u2, parseKey(publicKeyHex))) ?: return false
        return pt.x.mod(N) == r
    }

    /** 回傳符合的晶片名稱，沒有符合的回傳 null */
    fun checkNxp(uid: ByteArray, sig: ByteArray): String? =
        NXP_KEYS.firstOrNull { verify(it.second, uid, sig) }?.first
}
