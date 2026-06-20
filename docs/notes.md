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
- **Menu data-slot client/server parity**:server(core 建構子)與 client(buf 建構子)的 `addDataSlots` 數量必須**完全一致**,否則 `advanced_container_set_data` 同步會 `IndexOutOfBounds` 崩潰(開 storage terminal 即崩)。基礎 type-count slots 放在**兩條建構子都會經過**的 `addTypeDataSlots()`;crafting 在其上再 `addContainerData()`(兩邊一致)。有 `data_slot_parity_server_vs_buf_ctor` GameTest 守護。畫面外/同步用的 slot(如 crafting 的 `selectionContainer`)要加在**玩家背包之後**,否則 `repositionPlayerInventory` 會錯位。

## Decisions

- **統一能量池模型**:所有能量池同為 `Map<EnergyType, Long>` 無上限,差異只在來源,降低特例。
- **參考 RS2 而非依賴**:借設計模式,不加為依賴;加任何依賴前先問使用者(minimize deps)。
- **絕不整段照抄 RS2**:授權不同;只取模式自行實作。

## Hardening Pass (2026-06-21)

地毯式 bug 掃描 + 修正。關鍵點與借鏡的 RS2/AE2 模式(僅取模式,未照抄):

- **持久化**:`StorageCoreBlockEntity` 每次異動呼叫 `setChanged()`(否則正常存檔/卸載區塊會掉光物品+能量);庫存存讀改存完整 `ItemStack`(含 components:`toStack(1).save(registries)` / `ItemStack.parse(registries, tag)`),否則帶 NBT 的變體存讀後崩塌互蓋而損毀。參考 AE2「持久化完整 item key(item + components),勿只存 item id」。
- **匯流排/合成原子性**:Import bus 與 `craftItem` 改「先 simulate 再 commit」——能量/材料先驗證全部可行才實際扣除,任一不足則不動任何狀態。參考 RS2 importer / autocrafting 的 `Action.SIMULATE` 兩階段;`ExportBusBlockEntity` 原本已是此 pattern。
- **insert/extract 契約**:`insertItem(stack, simulate)` 回傳實際接受量、`extractItem` 回傳實際取出量且庫存只減該量;呼叫端必須尊重回傳值(勿丟棄剩餘 → 否則網路滿時吃物)。
- **數值安全**:`consumeEnergy` 用 `Math.multiplyExact` 防 long 溢位(否則大 multiplier 使守衛失效→倒加能量/無限能量);`extractItem` 的 long→int 夾限。
- **GUI**:隱藏的 `GhostSlot` 以 `isActive()=index<visibleRows*9` 停用(否則 vanilla 仍對其畫 hover 白框/設 hoveredSlot/tooltip);按鈕點完 `setFocused(null)` 清焦點白邊;`applySettings` 的 `visibleRows` 在 server 端 `Math.clamp(…,3,9)`,不信任 client。
- **網路**:`onBlockBroken` 用 `server.execute(...)` 延到方塊真的移除後再重算容量(`BreakEvent` 在移除前觸發);`bfsFindCore` 加 `MAX_NETWORK_BLOCKS` 上限;封包 handler 包 `ctx.enqueueWork(...)` 確保 server 執行緒。參考 RS2「post-change 反應、單一 controller」。

## RS2 Heuristics Adopted (2026-06-21)

完整對照見 `docs/rs2-design-gap.md`(該採用 vs 故意分歧)+ 計劃 `docs/superpowers/plans/2026-06-21-rs2-heuristics-adoption.md`。已採用(僅取模式):

- **fuzzy 配對(A5b)**:合成材料以 `Ingredient.test` 跨變體,經 `core.countMatching` / `extractMatching(Predicate<ItemStack>)`(取代完全相同 `ItemKey` 比對 → 帶 NBT / tag 分散的材料現在配得到)。
- **view 設定 server 同步(A5a)**:sort / order / search 模式為 server 權威、經 data slot 同步;終端按鈕標籤/tooltip 反映真實狀態(非 client 私有)。
- **選取物品身分化(A4)**:crafting 選取以 `ItemKey` 身分為準(經畫面外 `selectionContainer` slot 自動同步),非 grid slot index → 排序/捲動後配方面板不再亂指。
- **合成 preview(A7)**:用 simulate 路徑算「可做 N / 缺什麼」顯示,不異動狀態。
- **增量網路成長(A1,安全範圍)**:放置時 `StorageCoreBlockEntity.tryIncrementalAdd` O(1) 加入(無 full BFS);**破壞/不確定一律 full `rebuildNetwork`**(移除可能分裂網路,難增量)。`capacityOf()` 是兩條路徑唯一的容量計算來源。
- **未採用(下次)**:A2 Actor、A3 變更事件、**P3 增量 grid delta**(grid 改收差異、不整份重建)。
