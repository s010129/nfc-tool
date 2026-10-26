# NFC 讀寫工具

網址：https://s010129.github.io/nfc-tool/

在手機瀏覽器上讀取、寫入、清除、鎖定、複製 NFC 標籤（NTAG213/215/216、MIFARE Ultralight 等 NDEF 標籤）。

**只支援 Android 的 Chrome**（Web NFC）。

**Android App 版**（功能更多：密碼保護、原廠驗證、原始資料、自動感應）：[下載 nfc-tool.apk](https://github.com/s010129/nfc-tool/releases/latest/download/nfc-tool.apk)，說明見 [android/](android/)。iPhone 的瀏覽器都不支援網頁 NFC，但 iPhone 可以直接讀取寫好的網址標籤。

## 功能
- 讀取：序號（UID）、每筆記錄的內容與原始資料，可連續讀多張
- 寫入：網址、Instagram 帳號、文字、電話、簡訊、Email、Wi‑Fi、名片（vCard）、地圖位置、Android App、自訂 MIME、空記錄；可多筆
- 容量估算（依晶片型號）
- 連續寫入多張（同一張不會重複寫）、寫入後可選擇永久鎖定
- 複製標籤、清除標籤、永久鎖定
- 常用內容、操作紀錄（只存在瀏覽器，可匯出 JSON）

純靜態網頁（index.html、style.css、app.js），GitHub Pages 從 main 分支根目錄發佈。
