package io.github.s010129.nfctool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

val CHIPS = listOf("NTAG213（144 B）" to 144, "NTAG215（504 B）" to 504, "NTAG216（888 B）" to 888,
    "MIFARE Ultralight（48 B）" to 48, "MIFARE Classic 1K（約 716 B）" to 716)

fun Tab.writeTab(vm: AppVm) {
    item {
        SectionCard(title = "要寫入的內容", action = {
            if (vm.templates.isNotEmpty()) Picker("", listOf("" to "載入常用…") + vm.templates.mapIndexed { i, t -> "$i" to t.name }, "", compact = true) { k ->
                k.toIntOrNull()?.let { vm.loadForms(vm.templates[it].records); vm.toast("已載入：${vm.templates[it].name}") }
            }
        }) {
            Hint("一張標籤可以放多筆記錄，但手機靠近時通常只會自動開啟第一筆。")
        }
    }
    vm.forms.forEachIndexed { i, f ->
        item(key = "rec$i") { RecordEditor(vm, i, f) }
    }
    item {
        OutlinedButton(onClick = { vm.forms.add(Records.defaults("url")); vm.saveDraft() }, modifier = Modifier.fillMaxWidth()) { Text("＋ 再加一筆記錄") }
    }
    item { CapacityCard(vm) }
    item { WriteOptions(vm) }
}

@Composable
private fun RecordEditor(vm: AppVm, i: Int, f: RecForm) {
    val t = Records.type(f.type)
    SectionCard(title = "第 ${i + 1} 筆", action = {
        Row {
            if (i > 0) TextButton(onClick = {
                val x = vm.forms.removeAt(i); vm.forms.add(i - 1, x); vm.saveDraft()
            }) { Text("上移") }
            if (vm.forms.size > 1) TextButton(onClick = { vm.forms.removeAt(i); vm.saveDraft() }) { Text("刪除") }
        }
    }) {
        Picker("類型", Records.TYPES.map { it.key to it.label }, f.type, Modifier.fillMaxWidth()) { k ->
            if (k != f.type) { vm.forms[i] = Records.defaults(k); vm.saveDraft() }
        }
        t.fields.forEach { fd ->
            val v = f.values[fd.k] ?: ""
            val set = { nv: String -> vm.forms[i] = f.copy(values = f.values + (fd.k to nv)); vm.saveDraft() }
            if (fd.kind == FieldKind.SELECT) {
                Picker(fd.label, fd.options, v.ifEmpty { fd.def }, Modifier.fillMaxWidth()) { set(it) }
            } else {
                OutlinedTextField(
                    value = v, onValueChange = set, label = { Text(fd.label) },
                    placeholder = if (fd.ph.isNotEmpty()) ({ Text(fd.ph) }) else null,
                    singleLine = fd.kind != FieldKind.MULTI, minLines = if (fd.kind == FieldKind.MULTI) 2 else 1,
                    keyboardOptions = KeyboardOptions(keyboardType = when (fd.kind) {
                        FieldKind.URL -> KeyboardType.Uri; FieldKind.PHONE -> KeyboardType.Phone; FieldKind.EMAIL -> KeyboardType.Email
                        FieldKind.NUMBER -> KeyboardType.Decimal; else -> KeyboardType.Text
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        t.note?.let { Hint(it) }
        val err = t.check(f.values)
        val size = if (err == null) Records.recordSize(f) else null
        val untouched = f.values.filterKeys { k -> t.fields.any { it.k == k && it.kind != FieldKind.SELECT } }.values.all { it.isBlank() }
        if (err != null) Hint(err, if (untouched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) else if (size != null) Hint("這筆約 $size bytes")
    }
}

@Composable
private fun CapacityCard(vm: AppVm) {
    val (msg, _) = Records.build(vm.forms)
    val cap = CHIPS.getOrElse(vm.chipIdx) { CHIPS[1] }.second
    val used = msg?.let { Records.tlvSize(it.byteArrayLength) }
    SectionCard(title = "容量", action = {
        Picker("", CHIPS.mapIndexed { i, c -> "$i" to c.first }, "${vm.chipIdx}", compact = true) { vm.setChip(it.toInt()) }
    }) {
        val frac = (used ?: 0).toFloat() / cap
        LinearProgressIndicator(
            progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp),
            color = if (frac > 1f) MaterialTheme.colorScheme.error else if (frac > 0.85f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
        Hint(
            if (used == null) "內容還沒填完" else if (used > cap) "約 $used / $cap bytes，放不下！請減少內容或換大容量標籤"
            else "約 $used / $cap bytes（${(frac * 100).toInt()}%）",
            if (used != null && used > cap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WriteOptions(vm: AppVm) {
    var confirmLock by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    SectionCard(title = "寫入選項") {
        CheckRow("標籤已有資料時不要覆寫", vm.optProtect) { vm.optProtect = it }
        CheckRow("連續寫入多張（大量製作用）", vm.optBatch) { vm.optBatch = it }
        CheckRow("寫入後永久鎖定（無法再修改）", vm.optLock, danger = true) { if (it) confirmLock = true else vm.optLock = false }
        OutlinedTextField(
            vm.writePwd, { vm.writePwd = it }, label = { Text("標籤密碼（有設密碼的標籤才要填）") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton("寫入標籤", Modifier.weight(1f)) { vm.startWrite() }
            OutlinedButton(onClick = { askName = true }, modifier = Modifier.height(52.dp)) { Text("存成常用") }
        }
    }
    if (confirmLock) AlertDialog(
        onDismissRequest = { confirmLock = false },
        title = { Text("確定要寫入後永久鎖定？") },
        text = { Text("鎖定後標籤內容永遠無法修改或清除，無法復原。適合發放出去的成品。") },
        confirmButton = { TextButton(onClick = { vm.optLock = true; confirmLock = false }) { Text("確定鎖定", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmLock = false }) { Text("取消") } },
    )
    if (askName) NameDialog("存成常用內容", Records.summary(Records.build(vm.forms).first).take(20), { askName = false }) { vm.saveTemplate(it) }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("名稱") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onOk(name.trim()); onDismiss() }) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
