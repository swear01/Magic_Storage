# macOS Minecraft F11 全螢幕與關閉指南

本指南是 Prism dev 視覺 GUI session 在 macOS 的現行契約。它只適用開發測試 instance `MagicStorageGuiTest`；不改變玩家一般啟動方式。

## 保證與原因

macOS Retina 桌面可用點數尺寸不一定是 GLFW 可切換的顯示模式。若讓 Minecraft 的原生 F11 路徑把 GLFW 視窗 attach 到 monitor，GLFW 可能選擇另一個實體解析度，造成整個 macOS 桌面短暫變更解析度。

`MacOsWindowMixin` 因此只在 macOS 改寫 Minecraft F11：它把 Java 視窗設為無邊框 Cocoa 視窗、隱藏 Dock/menu bar，且 **絕不把 GLFW 視窗 attach 到 monitor，也不選擇或切換桌面 display mode**。非 macOS 仍走原版 GLFW 行為。

## 啟動與預檢

```bash
python3 scripts/run_prism_gui_session.py --scenario terminal-left-rail
# 或
python3 scripts/run_prism_gui_session.py --scenario crafting-fuel-page
```

runner 會：

1. 先從明確的 `/Applications/Prism Launcher.app` 讀版本並要求11.0.3+。
2. 要求一般Prism Launcher已開啟且account initialization完成；若沒有warm normal-root process，runner在改世界與啟client前fail。這避免Prism cold start即使帶`-o`仍刷新Microsoft/Xbox ownership。runner不建立`-d` data root，也不建立或改寫`accounts.json`。Offline-only root沒有owning account，Prism會進Demo/account-selection，不能拿來啟動完整遊戲。
3. 對該已執行process送出 `"/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" -l dev -w MagicStorageGuiTest -o MagicStorageBot`。這是Prism官方CLI的既有instance離線launch路徑，不透過`open -n`。
4. launcher subprocess只帶HOME/PATH/TMPDIR/locale等必要環境；run artifact會移除Prism列出的process/native environment。
5. 寫入 `fullscreen:true`，移除任何舊的 `fullscreenResolution`；`overrideWidth=1280`、`overrideHeight=720` 僅供離開全螢幕後的 windowed fallback。
6. 在準備世界時記錄 macOS 桌面 display mode（點數、像素、refresh、depth）。
7. 等 `MS_GUI_TEST_READY` 後只掃normal-root `PrismLauncher-0.log`的本次cursor片段；任何`AuthFlow:`實際step或Microsoft/Xbox/XSTS/Minecraft-services endpoint都fail closed。generic Offline task與`RefreshSchedule` bookkeeping不代表網路登入。
8. 再讀一次 desktop mode；任一欄不同也fail closed。
9. 將 `manifest.json`、`session.json`、`checklist.md`、Minecraft log、已清理的`prism-launcher.log` 與 shutdown artifacts 寫進同一run directory。

visual scenario 的 owner 是使用者。READY、GameTest 或 client smoke 都不等於 GUI 視覺驗收。

## 使用者全螢幕 gate

READY 後先確認完整遊戲內容、左下版本文字、底部 hotbar 與 GUI 坐標都沒有被裁切；通過後才可執行該次 `checklist.md` 的 `u`、hotbar、點擊、滾輪或截圖步驟。

禁止使用 macOS 綠色按鈕、Control-Command-F，或將 macOS native fullscreen 與 Minecraft F11 疊加。

## 唯一允許的關閉順序

1. 按 **F11** 離開 Minecraft borderless fullscreen。
2. 確認正常、有標題列的 windowed 視窗已出現。
3. 再按 **Command-Q**。

不得直接在 F11 全螢幕按 Command-Q，也不要用廣泛的 `pkill java`。若本次 log 已出現 `Stopping!` 而該次精確 Java process 五秒仍未結束，runner 的 exact-PID watchdog 才會只終止那個測試 client。

關閉後檢查同一 run 的 `shutdown.json`：`graceful` 代表自行結束；`forced_after_glfw_shutdown_stall` 代表 watchdog 收尾，應附帶該 run artifacts 回報。watchdog 是測試 session 的最後防線，不是正常關閉成功的替代證據。

## 排查

- desktop mode 驗證失敗：先喚醒並解鎖顯示器後重跑；不要手動設定 `fullscreenResolution` 來繞過。
- 畫面遭裁切或全螢幕 gate 不通過：停止該 run，不要改用 macOS native fullscreen。
- 關閉後黑窗或 watchdog 介入：保留該 run directory 的 `shutdown.json`、`shutdown-watchdog.log` 與 `log-excerpt.log` 再排查。

相關實作：`src/main/java/com/swearprom/magicstorage/magic_storage/mixin/MacOsWindowMixin.java`；流程細節：[`docs/notes.md`](notes.md#prism-dev--manual-handoff)。
