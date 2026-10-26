package io.github.s010129.nfctool

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Tab.readTab(vm: AppVm) {
    item {
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("把標籤貼近手機背面", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("App 開著就會自動讀取，不用按按鈕。NFC 位置通常在相機下方或背面中間。",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (vm.reports.isNotEmpty()) item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("讀取結果", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.reports.clear() }) { Text("清除畫面") }
        }
    }
    vm.reports.forEachIndexed { i, r ->
        item(key = r.time) { ReportCard(r, vm, expandedByDefault = i == 0) }
    }
}

@Composable
private fun InfoRow(k: String, v: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ReportCard(r: TagReport, vm: AppVm, expandedByDefault: Boolean) {
    val ctx = LocalContext.current
    var showRaw by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(expandedByDefault) }
    val ok = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error

    SectionCard(title = r.chip?.name ?: r.ndefType ?: "標籤", action = { Text(timeStr(r.time), style = MaterialTheme.typography.labelSmall) }) {
        InfoRow("序號 UID", r.uidHex)
        val m = r.message
        if (m == null || m.records.isEmpty()) {
            Text(if (r.formatable) "空白卡（尚未格式化，寫入時會自動格式化）" else if (r.ndefType != null) "沒有內容" else "不是 NDEF 標籤（可能是悠遊卡、門禁卡等加密卡）",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            m.records.forEachIndexed { i, rec ->
                val s = Records.show(rec)
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${i + 1}. ${s.kind}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(s.value, style = MaterialTheme.typography.bodyMedium) }
                        s.detail?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.uri?.let { u ->
                                OutlinedButton(onClick = {
                                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: ActivityNotFoundException) { vm.toast("沒有 App 可以開啟", true) }
                                }) { Text("開啟") }
                            }
                            OutlinedButton(onClick = { copyText(ctx, s.value); vm.toast("已複製") }) { Text("複製") }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (m != null && !Nfc.isEmpty(m)) {
                OutlinedButton(onClick = { vm.loadForms(m.records.map { Records.toForm(it) }); vm.tab = 1 }) { Text("編輯後寫入") }
                OutlinedButton(onClick = { vm.startCloneFrom(r) }) { Text("複製到別張") }
            }
            OutlinedButton(onClick = { open = !open }) { Text(if (open) "收起詳細" else "詳細資料") }
        }

        if (open) {
            HorizontalDivider()
            InfoRow("晶片", r.chip?.name ?: "無法判斷")
            InfoRow("技術", r.techs.joinToString(", "))
            r.ndefType?.let { InfoRow("NDEF 類型", it) }
            r.maxSize?.let { InfoRow("容量", "已用 ${r.messageSize} / $it bytes") }
            r.writable?.let { w ->
                InfoRow("可否寫入", if (w) "可以寫入" + if (r.canLock == true) "（可鎖定）" else "" else "已鎖定，無法修改", if (w) Color.Unspecified else bad)
            }
            r.passwordState?.let { InfoRow("密碼保護", it, if (r.hasPassword) bad else Color.Unspecified) }
            when {
                r.signature == null && r.version != null -> InfoRow("原廠驗證", "這張晶片不提供原廠簽章", MaterialTheme.colorScheme.onSurfaceVariant)
                r.signature == null -> {}
                r.signatureOk != null -> InfoRow("原廠驗證", "✓ NXP 原廠晶片（簽章正確）", ok)
                else -> InfoRow("原廠驗證", "✗ 簽章不符：可能是相容晶片（仍可正常使用）", bad)
            }
            r.version?.let { InfoRow("版本資訊", it.hex()) }
            r.signature?.let { InfoRow("簽章", it.hex()) }
            r.extra.forEach { (k, v) -> InfoRow(k, v) }

            if (r.pages.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("原始資料（${r.pages.size}/${r.pagesTotal} 頁）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "隱藏" else "顯示") }
                }
                if (showRaw) {
                    val dump = dumpText(r)
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        SelectionContainer {
                            Text(dump, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                                modifier = Modifier.padding(10.dp).horizontalScroll(rememberScrollState()))
                        }
                    }
                    if (r.pages.size < r.pagesTotal) Hint("後面的頁面讀不到，通常是設了密碼的讀取保護。")
                    Hint("密碼（PWD）和 PACK 頁讀出來永遠是 00，這是晶片的保護設計。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyText(ctx, dump); vm.toast("已複製原始資料") }) { Text("複製") }
                        OutlinedButton(onClick = { shareText(ctx, dump, "NFC 原始資料 ${r.uidHex}") }) { Text("分享") }
                    }
                }
            }
        }
    }
}

fun dumpText(r: TagReport): String {
    val c = r.chip?.cfgPage
    return buildString {
        appendLine("UID ${r.uidHex}  ${r.chip?.name ?: ""}")
        r.pages.forEachIndexed { i, p ->
            val ascii = p.map { val b = it.toInt() and 0xFF; if (b in 0x20..0x7E) b.toChar() else '.' }.joinToString("")
            val note = when {
                i <= 1 -> "UID"
                i == 2 -> "UID/鎖定"
                i == 3 -> "CC"
                c != null && i == c - 1 -> "動態鎖定"
                c != null && i == c -> "CFG0"
                c != null && i == c + 1 -> "CFG1"
                c != null && i == c + 2 -> "PWD"
                c != null && i == c + 3 -> "PACK"
                else -> ""
            }
            appendLine("%3d %02X  %s  %s  %s".format(i, i, p.hex(), ascii, note).trimEnd())
        }
    }.trimEnd()
}
