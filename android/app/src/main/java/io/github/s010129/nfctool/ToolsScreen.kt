package io.github.s010129.nfctool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun Tab.toolsTab(vm: AppVm) {
    item {
        SectionCard(title = "複製標籤") {
            Hint("先讀取來源標籤，再把一模一樣的內容寫到另一張，可以連續複製多張。")
            BigButton("開始複製") { vm.op = Op.CloneRead }
        }
    }
    item {
        SectionCard(title = "清除標籤") {
            Hint("清掉內容，標籤變成空白但仍可再寫入。已鎖定的標籤無法清除；有密碼的標籤要先在「寫入」頁填密碼。")
            BigButton("清除標籤") { vm.op = Op.Erase }
        }
    }
    item { PasswordCard(vm) }
    item { LockCard(vm) }
    item {
        SectionCard(title = "驗真偽與原始資料") {
            Hint("讀取標籤後按「詳細資料」，可以看到晶片型號、NXP 原廠簽章驗證、密碼保護狀態，以及每一頁的原始資料（可複製或分享）。")
            OutlinedButton(onClick = { vm.tab = 0 }, modifier = Modifier.fillMaxWidth()) { Text("去讀取") }
        }
    }
    item {
        SectionCard(title = "說明") {
            listOf(
                "App 開著時，碰到標籤會由 App 接手，不會跳去瀏覽器。",
                "App 沒開時，碰到空白標籤或非網址的標籤，會直接打開這個 App 讀取；網址標籤照常用瀏覽器開啟。",
                "只能讀寫 NDEF 標籤（NTAG213/215/216、Ultralight、MIFARE Classic 等）。悠遊卡、信用卡等加密卡片只能看到序號；也不能複製門禁卡。",
                "常用的白色貼紙（NTAG215）可放約 500 bytes，一個 IG 網址只佔約 40 bytes。",
                "iPhone 不能用這個 App，但可以讀取寫好的網址標籤：iPhone XS 以後靠近就會跳出通知。",
                "紀錄和常用內容只存在這支手機，不會上傳。",
            ).forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PasswordCard(vm: AppVm) {
    var pwd by rememberSaveable { mutableStateOf("") }
    var oldPwd by rememberSaveable { mutableStateOf("") }
    var protectRead by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf("0") }
    var confirm by remember { mutableStateOf(false) }

    SectionCard(title = "密碼保護（NTAG21x）") {
        Hint("設定後別人就不能改寫或清除你的標籤，但你自己知道密碼仍然可以改。和永久鎖定不同，可以隨時移除。")
        OutlinedTextField(pwd, { pwd = it }, label = { Text("密碼") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (pwd.isNotBlank()) {
            val (p, pk) = derivePassword(pwd)
            Hint("晶片密碼 PWD ${p.hex("")}、PACK ${pk.hex("")}。在其他 App（例如 NFC Tools）要輸入這組 PWD。")
        }
        Column {
            listOf(false to "只保護寫入（推薦）：大家都能讀，改寫要密碼", true to "讀寫都保護：手機碰了也讀不到，網址不會自動開啟").forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().selectable(protectRead == v) { protectRead = v }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(protectRead == v, { protectRead = v })
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Picker("密碼錯誤次數限制", listOf("0" to "不限制（推薦）", "3" to "錯 3 次就永久無法再寫入", "7" to "錯 7 次就永久無法再寫入"), limit, Modifier.fillMaxWidth()) { limit = it }
        OutlinedTextField(oldPwd, { oldPwd = it }, label = { Text("舊密碼（要更換密碼時才填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        BigButton("設定密碼", enabled = pwd.isNotBlank()) { confirm = true }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = pwd.isNotBlank(), onClick = { vm.op = Op.RemovePwd(derivePassword(pwd).first) }, modifier = Modifier.weight(1f)) { Text("移除密碼") }
            OutlinedButton(enabled = pwd.isNotBlank(), onClick = { vm.op = Op.TestPwd(derivePassword(pwd).first) }, modifier = Modifier.weight(1f)) { Text("測試密碼") }
        }
        Hint("移除和測試都用上面「密碼」欄位的密碼。忘記密碼就沒辦法移除，請記下來。")
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("確定設定密碼？") },
        text = {
            Text(buildString {
                append("密碼：$pwd\n請記下這個密碼，忘記就無法再修改這張標籤。")
                if (protectRead) append("\n\n你選了「讀寫都保護」，別人的手機碰了不會開網址。")
                if (limit != "0") append("\n\n密碼錯 $limit 次後，這張標籤會永久無法再寫入。")
            })
        },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                val (p, pk) = derivePassword(pwd)
                vm.op = Op.SetPwd(Nfc.PwdOpts(p, pk, protectRead, 4, limit.toInt(), oldPwd.trim().takeIf { it.isNotEmpty() }?.let { derivePassword(it).first }))
            }) { Text("開始設定", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}

@Composable
private fun LockCard(vm: AppVm) {
    var confirm by remember { mutableStateOf(false) }
    SectionCard(title = "永久鎖定", danger = true) {
        Hint("鎖定後標籤內容永遠無法修改或清除，適合發放出去的成品。NTAG 系列鎖定無法復原。想保留修改的機會，請改用上面的密碼保護。")
        BigButton("鎖定標籤", danger = true) { confirm = true }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("確定永久鎖定？") },
        text = { Text("下一張靠近的標籤會被永久鎖定，之後再也無法修改或清除。") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.op = Op.Lock }) { Text("確定鎖定", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}
