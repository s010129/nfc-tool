package io.github.s010129.nfctool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ---------- 共用元件 ----------

@Composable
fun SectionCard(
    title: String? = null,
    danger: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (danger) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || action != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified, modifier = Modifier.weight(1f))
                    action?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun CheckRow(text: String, checked: Boolean, danger: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked, onChange)
        Text(text, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** 下拉選單 */
@Composable
fun Picker(label: String, options: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, compact: Boolean = false, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = if (compact) Modifier else Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Column(if (compact) Modifier else Modifier.weight(1f)) {
                if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(options.firstOrNull { it.first == value }?.second ?: value, color = MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onPick(k) }) }
        }
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier.fillMaxWidth(), danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick, modifier.height(52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("NFC", text))
}

fun shareText(ctx: Context, text: String, title: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
    ctx.startActivity(Intent.createChooser(i, title))
}

// ---------- 主畫面 ----------

private val TABS = listOf("讀取" to Icons.Filled.Search, "寫入" to Icons.Filled.Edit, "工具" to Icons.Filled.Build, "紀錄" to Icons.AutoMirrored.Filled.List)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppVm) {
    val snackHost = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    LaunchedEffect(vm.snackKey) {
        val s = vm.snack ?: return@LaunchedEffect
        snackHost.currentSnackbarData?.dismiss()
        snackHost.showSnackbar(s.first, withDismissAction = s.second, duration = if (s.second) SnackbarDuration.Long else SnackbarDuration.Short)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Text("N", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("NFC 讀寫工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                when (vm.nfcState) { NfcState.OK -> "NFC 已開啟，隨時可以感應"; NfcState.OFF -> "NFC 未開啟"; NfcState.NONE -> "這支手機沒有 NFC" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (vm.nfcState == NfcState.OK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = vm.tab == i, onClick = { vm.tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackHost) { d ->
                val err = vm.snack?.second == true
                Snackbar(d, containerColor = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.inverseSurface,
                    contentColor = if (err) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.inverseOnSurface)
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.nfcState != NfcState.OK) item {
                SectionCard(danger = true) {
                    if (vm.nfcState == NfcState.OFF) {
                        Text("手機的 NFC 沒有開啟", fontWeight = FontWeight.Bold)
                        Hint("開啟後回到這個 App 就能使用。")
                        BigButton("開啟 NFC 設定") { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                    } else {
                        Text("這支手機沒有 NFC 功能", fontWeight = FontWeight.Bold)
                        Hint("沒有 NFC 的手機無法讀寫標籤。")
                    }
                }
            }
            when (vm.tab) {
                0 -> readTab(vm)
                1 -> writeTab(vm)
                2 -> toolsTab(vm)
                else -> historyTab(vm)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    vm.op?.let { WaitDialog(it, vm) }
}

@Composable
fun WaitDialog(op: Op, vm: AppVm) {
    Dialog(onDismissRequest = { vm.cancel() }, properties = DialogProperties(dismissOnClickOutside = false)) {
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Pulse()
                Text(op.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(op.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (op.count > 0) Text("已完成 ${op.count} 張", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                val batchLike = op is Op.CloneWrite || (op is Op.Write && op.batch)
                if (batchLike) BigButton("完成") { vm.cancel() }
                else TextButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}

@Composable
private fun Pulse() {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(0.6f, 1.25f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "s")
    val a by t.animateFloat(0.55f, 0f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "a")
    Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(110.dp).scale(s).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("NFC", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
        }
    }
}

typealias Tab = LazyListScope
