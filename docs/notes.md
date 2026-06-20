# Notes

> Tacit knowledge an agent can't infer from reading code.

## Build & Test

先設 `JAVA_HOME`(JDK 21,不在 PATH):

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

| 用途 | 指令 |
|------|------|
| 編譯(最快檢查) | `./gradlew compileJava` |
| 完整建置 | `./gradlew build` |
| 自動測試(SelfTest 於 mod init + GameTest) | `./gradlew runGameTestServer` |
| **手動測試 GUI**(終端機介面只能這樣看) | `./gradlew runClient` |

手動測 GUI:`runClient` 開遊戲 → 創造模式 → 放 `storage_core`,旁邊接 `storage_unit`,再接 `storage_terminal` / `crafting_terminal` → 右鍵終端機開介面。調整視窗大小可驗證動態列數。GUI 外觀無法用 GameTest 驗證,只能 `runClient` 目視。

## Reference Source — Refined Storage 2

實作儲存機制、網路邏輯、grid UI、物品/流體處理卡關時,**先參考 Refined Storage 2 原始碼**。

| Topic | Where to look |
|-------|--------------|
| 儲存網路架構 | `refinedstorage2-platform-common/src/main/java/.../network/` |
| Grid(物品瀏覽 UI) | `refinedstorage2-platform-common/src/main/java/.../grid/` |
| Storage provider / disk API | `refinedstorage2-api/src/main/java/.../storage/` |
| 物品/流體資源處理 | `refinedstorage2-api/src/main/java/.../resource/` |
| 自動合成 | `refinedstorage2-platform-common/src/main/java/.../autocrafting/` |
| NeoForge 平台橋接 | `refinedstorage2-platform-neoforge/` |

Source: https://github.com/refinedmods/refinedstorage2

卡關工作流:1) 在 RS2 repo 搜你要的介面/類別 → 2) 讀實作理解模式 → 3) 改寫(**勿整段照抄**,授權不同)→ 4) 仍不確定就問使用者。

## Gotchas

- **網路邏輯一律伺服器端**;客戶端靠封包同步,絕不在客戶端保存儲存狀態。
- Capability 註冊遵循 NeoForge 慣例。
- 實作進度以**程式碼為準**:已有 ~36 個 Java 檔 + SelfTest/GameTest;設計細節見 `PLAN.md`,即時狀態見 `docs/plan.md`。
- 建置前需設 `JAVA_HOME`(JDK 21,見根 repo `docs/notes.md`)。
- **SelfTest 在 dedicated server 也會跑**(mod 建構時呼叫):絕不可從 SelfTest 參照 client-only 類別(如 `*Screen` extends `AbstractContainerScreen`),否則 server 端 RuntimeDistCleaner 會擋下、整個 mod 載入失敗。純邏輯放 dist-中性類別(如 enum:`SearchMode.apply`)再測。
- **`Slot.x`/`y` 是 `final`**:GUI 動態調整版面時不能改 slot 座標,需在 Screen(client)端 `menu.slots.set(i, new Slot(...))` 重建 slot(座標只供 client 渲染/命中,不上傳)。見 `StorageTerminalScreen.repositionPlayerInventory`。
- **終端機背景為程式繪製**(`StorageTerminalScreen.drawPanels`,`g.fill`/`renderOutline`):`grid.png`/`crafting_grid.png` 是針對固定 6 列手繪,且 6 參數 `blit` 以 256×256 正規化、動態高度會破圖,故改程式繪製扁平面板;那兩張 PNG 已不再使用。

## Decisions

- **統一能量池模型**:所有能量池同為 `Map<EnergyType, Long>` 無上限,差異只在來源,降低特例。
- **參考 RS2 而非依賴**:借設計模式,不加為依賴;加任何依賴前先問使用者(minimize deps)。
- **絕不整段照抄 RS2**:授權不同;只取模式自行實作。
