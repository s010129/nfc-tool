package io.github.s010129.nfctool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

fun Tab.historyTab(vm: AppVm) {
    item { TemplatesCard(vm) }
    item { HistoryCard(vm) }
}

@Composable
private fun TemplatesCard(vm: AppVm) {
    SectionCard(title = "常用內容") {
        if (vm.templates.isEmpty()) Hint("在「寫入」頁按「存成常用」就會出現在這裡。")
        vm.templates.toList().forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.bodyMedium)
                    Hint("${t.records.size} 筆記錄")
                }
                TextButton(onClick = { vm.loadForms(t.records); vm.tab = 1 }) { Text("載入") }
                TextButton(onClick = { vm.deleteTemplate(t) }) { Text("刪除") }
            }
        }
    }
}

@Composable
private fun HistoryCard(vm: AppVm) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    SectionCard(title = "操作紀錄", action = {
        Row {
            TextButton(enabled = vm.history.isNotEmpty(), onClick = { shareText(ctx, vm.exportHistory(), "NFC 操作紀錄") }) { Text("匯出") }
            TextButton(enabled = vm.history.isNotEmpty(), onClick = { confirm = true }) { Text("清空") }
        }
    }) {
        if (vm.history.isEmpty()) Hint("還沒有紀錄。")
        vm.history.take(200).forEachIndexed { i, h ->
            if (i > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text((if (h.ok) "" else "✗ ") + h.action, style = MaterialTheme.typography.labelLarge,
                        color = if (h.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    Text(timeStr(h.time), style = MaterialTheme.typography.labelSmall)
                }
                Text(h.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (h.uid.isNotEmpty()) Hint(h.uid)
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("清空所有紀錄？") },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); confirm = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}
