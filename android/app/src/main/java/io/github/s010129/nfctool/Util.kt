package io.github.s010129.nfctool

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun ByteArray.hex(sep: String = " "): String = joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

fun fromHex(s: String): ByteArray {
    val clean = s.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun isHex(s: String): Boolean = s.isNotEmpty() && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

fun timeStr(t: Long): String = SimpleDateFormat("M/d HH:mm:ss", Locale.TAIWAN).format(Date(t))

/** 密碼文字 → NTAG 用的 4 bytes PWD + 2 bytes PACK。剛好 8 個十六進位字元時直接當 PWD 用。 */
fun derivePassword(text: String): Pair<ByteArray, ByteArray> {
    val t = text.trim()
    val pwd = if (t.length == 8 && isHex(t)) fromHex(t)
    else MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).copyOfRange(0, 4)
    val pack = MessageDigest.getInstance("SHA-256").digest(pwd).copyOfRange(0, 2)
    return pwd to pack
}
