# 外送計時收入 Android App v0.5 — 主頁 UI 更新

本版在 v0.4 原生 Android 專案中實作新的首頁版面，保留分享 Uber 連結自動啟動監測、計時、結束定位、中文地址查詢、SQLite 歷史紀錄與 CSV 匯出。

## 試用 UI
- `外送計時-主頁UI試用.html` 在 Android Chrome 開啟即可看到全新儀表板，可操作模擬接單、結束配送、試算與記錄、GPS 地址及匯出 CSV。
- HTML 是瀏覽器前端示範，**無法直接接收 Android 分享事件或自動讀取 Uber 分享頁面的文字**。請使用原生 App 專案測試此功能。
- UI 展示頁今日收入**排除模擬與測試單**。正式原生 App 依 SQLite 資料表中的來源排除測試資料。

## 原生 App 邏輯
1. Uber -> 分享 -> 外送計時收入。接受 ACTION_SEND 的文字網址，或使用 ACTION_VIEW https://trip.uber.com/...。
2. 接收後開始 WebView 監測，連續兩次確認「正在途中」才推定接單開始；連續兩次確認「上線中」才推定配送結束。只分享網址不會觸發開始計時。
3. 費率每單前 11 分鐘 NT$45；超過後每完整分鐘加 NT$4.10；剩餘秒數滿 30 秒進位。
4. 配送結束即固定結算時間並保存在 SQLite，隨後要求 GPS 授權，轉為地址。未能取得門牌可編輯地址。

## 功能限制與安全
- 未取得 Uber 官方資料 API。透過 WebView 尋找可見狀態文字的方法未經真實裝置驗證，可能受 JavaScript 介面、頁面改版、網頁登入或 Uber 阻擋而失敗。
- 目前只有 App 在前景、螢幕開啟時能嘗試監測；沒有穩定的背景持續監測功能。
- 定位需要手機 GPS 授權，地址轉換可能無法精確到門牌。
- 尚未編譯 Android APK；需要 Android Studio/Android SDK 或 GitHub Actions 編譯後實機測試。

## 建置
Android Studio 開啟本資料夾，等 Gradle Sync 完成，使用 Build > Build Bundle(s)/APK(s) > Build APK(s)，產生 `app/build/outputs/apk/debug/app-debug.apk`。

專案為測試用途，不是 Uber 官方軟體，所有收入屬估算。
