package io.github.s010129.nfctool

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()
    private var adapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        adapter = NfcAdapter.getDefaultAdapter(this)
        setContent { AppTheme { App(vm) } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** App 沒開時碰到標籤，系統把標籤交給我們 */
    private fun handleIntent(intent: Intent?) {
        val tag: Tag = (if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)) ?: return
        Thread { vm.onTag(tag) }.start()
    }

    override fun onResume() {
        super.onResume()
        val a = adapter
        vm.nfcState = when {
            a == null -> NfcState.NONE
            !a.isEnabled -> NfcState.OFF
            else -> NfcState.OK
        }
        if (a != null && a.isEnabled) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
            // App 開著時由 App 接手所有標籤，不會跳去瀏覽器
            a.enableReaderMode(this, { tag -> vm.onTag(tag) }, flags, Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            })
        }
    }

    override fun onPause() {
        super.onPause()
        try { adapter?.disableReaderMode(this) } catch (_: Exception) {}
    }
}
