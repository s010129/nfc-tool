# NFC 讀寫工具 Android 版

下載最新版：https://github.com/s010129/nfc-tool/releases/latest/download/nfc-tool.apk

原生 Android App（Kotlin + Jetpack Compose），功能涵蓋網頁版，另外加上網頁做不到的：

- App 開著時自動讀取，不會跳去瀏覽器；App 沒開時碰到空白或非網址標籤會直接開 App
- 晶片型號判斷（GET_VERSION）、NXP 原廠簽章驗證（READ_SIG，secp128r1）
- 每一頁的原始資料（可複製、分享）
- NTAG21x 密碼保護：設定、移除、測試（PWD/PACK、AUTH0、PROT、AUTHLIM）
- 有密碼的標籤可以先用密碼解鎖再寫入（直接寫頁面）

## 編譯

推到 main 時 GitHub Actions（`.github/workflows/android.yml`）會自動編譯、跑測試，並發佈成 GitHub Release。

本機：`cd android && ./gradlew assembleRelease`（需要 JDK 17+ 和 Android SDK 35）。

簽章金鑰不放在程式碼裡。在 GitHub 的 Settings → Secrets and variables → Actions 設定
`KEYSTORE_BASE64`（release.jks 的 base64）和 `KEYSTORE_PASSWORD`，每一版就會用同一把金鑰簽章，
新版可以直接覆蓋安裝。沒設定時會用臨時金鑰，更新前要先移除舊版。

## 密碼怎麼換算

輸入剛好 8 個十六進位字元時直接當作 4 bytes 的 PWD；其他文字取 SHA-256 前 4 bytes 當 PWD。
PACK 是 SHA-256(PWD) 的前 2 bytes。App 會顯示換算後的 PWD，在其他 App 使用時輸入那組十六進位。
