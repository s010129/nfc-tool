package io.github.s010129.nfctool

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 每個分頁都畫一次，確認不會當掉，並存成截圖 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allTabs() {
        val vm = rule.activity.run { androidx.lifecycle.ViewModelProvider(this)[AppVm::class.java] }
        val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }
        rule.runOnUiThread {
            vm.nfcState = NfcState.OK
            val (m, _) = Records.build(listOf(RecForm("ig", mapOf("handle" to "fcu_maker_club"))))
            val pages = (0 until 135).map { ByteArray(4) { b -> (it * 4 + b).toByte() } }
            vm.reports.add(TagReport(1L, fromHex("04A1B2C3D4E5F6"), listOf("NfcA", "MifareUltralight", "Ndef"),
                Chip("NTAG215", 135, 0x83), fromHex("0004040201001103"), ByteArray(32), "NTAG21x", pages, 135,
                4, 0, "MIFARE Ultralight", 496, true, true, false, m, listOf("ATQA / SAK" to "4400 / 00")))
        }
        for (t in 0..3) {
            rule.runOnUiThread { vm.tab = t }
            
            rule.waitForIdle()
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
            File(out, "tab$t.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
