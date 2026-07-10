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
| 版本化建置 + Prism dev 部署 | `python3 scripts/deploy_prism_dev.py`（自動 bump `mod_version` patch、`./gradlew build`、備份舊 jar、確保 mods 內只剩一個 `magic_storage-*.jar`;若 build 或 jar 檢查失敗會還原原本 `mod_version` 且不搬動 mods） |
| 自動測試(SelfTest 於 mod init + GameTest) | `./gradlew runGameTestServer` |
| CI(GitHub Actions) | `.github/workflows/ci.yml`:JDK 21 + `./gradlew build` + `./gradlew runGameTestServer` + `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts` + `./gradlew runData` datagen drift check,並上傳 jar + logs/reports artifacts |
| CD(GitHub Release) | push tag `v<mod_version>` 觸發 `.github/workflows/release.yml`;workflow 先驗 tag 等於 `gradle.properties` 的 `mod_version`,再建置/測試/datagen drift check,產生 release notes 並發布 jar |
| **手動測試 GUI**(終端機介面只能這樣看) | `./gradlew runClient` 或 Prism dev |
| Prism dev + Computer Use GUI 驗證 | 見下方「Prism dev / Computer Use」 |

GitHub public repo:https://github.com/swear01/Magic_Storage 。release tag 範例:`git tag v0.1.3 && git push origin main v0.1.3`。CI/CD 會保存 `build/ci-logs/**`、`run/logs/**`、`run/crash-reports/**`、`build/reports/**` 方便遠端失敗排查。GUI/Patchouli/視覺變更仍必須跑下方 Prism dev / Computer Use 固定流程;CI/GameTest 不能替代目視 GUI 驗證。

手動測 GUI 優先用固定 Prism world:`python3 scripts/prepare_prism_gui_world.py` 會重建 `MagicStorageGuiTest`、安裝無 command block datapack rig、啟用 cheats、把 use 鍵改成 `u` 並固定 1280×720 windowed 起跑。GUI 外觀無法用 GameTest 驗證,只能實機目視/截圖;不要再手放方塊當主要流程。

### Prism dev / Computer Use

當需要由 Computer Use 讀取/操作 Minecraft 視窗時,不要直接 target Prism 產生的 `java` 子程序:Prism-launched LWJGL 視窗沒有正常 `.app` bundle identity,`get_app_state("java")` / `get_app_state("Minecraft...")` 會失敗。已驗證可行做法是用 wrapper 讓 Prism 透過真正 macOS app 啟動 Java:

- app bundle:`/tmp/MagicStorageMinecraftCU.app`,bundle id:`run.hapi.magicstorage.minecraftcu`。Computer Use 用 bundle id: `get_app_state("run.hapi.magicstorage.minecraftcu")`。
- Prism dev instance 設定:`~/Library/Application Support/PrismLauncher/instances/dev/instance.cfg` 需有 `OverrideCommands=true` 與 `WrapperCommand=/tmp/magic_storage_minecraft_cu_wrapper.sh`。
- wrapper 必須把 Prism `stdin` 透過 FIFO 轉接進 `.app` 內的 Java;Prism 的 `NewLaunch.jar` 會從 stdin 讀 launch script,單純 `open .app --args java ...` 會因 EOF 變成 `Launch aborted by the launcher`。
- 目前這是本機 `/tmp` wrapper;若 `/tmp` 被清掉,先重建 wrapper 或把 dev instance 的 `WrapperCommand` 清空,否則 Prism dev 會找不到 wrapper。
- Computer Use 能讀截圖與送鍵盤;LWJGL canvas 可能讓 `click` 回 `noWindowsAvailable`,選單優先用 `Tab`/`Return`。遊戲內要觸發右鍵時,最穩的是啟動前暫時把 `options.txt` 的 `key_key.use` 改成鍵盤(例:`key.keyboard.u`)並把 `pauseOnLostFocus=false`,用完立刻還原;滑鼠視角可用 CGEvent delta 輔助,不要用 Computer Use drag 對準方塊(會等同左鍵破壞方塊)。
- LWJGL 文字輸入目前不可靠:Computer Use `type_text` / paste 對 Minecraft chat、creative search、terminal search 不穩。能用點擊/滾輪完成就避免輸入;Patchouli guide 可從 creative search tab 捲到底部拿 `Magic Storage Guide` 再用 `u` 開。若一定要送 chat 指令,先把 macOS input source 切到 `ABC`;注音輸入法會讓 `/tp`/`/setblock` 之類指令輸入成錯字。
- 2026-07-07 Prism dev fullscreen 實測:Storage Terminal 與 Crafting Terminal 都能開啟且版面未與 EMI 重疊。
- 2026-07-08 Prism dev 已部署並實機驗證 `magic_storage-0.1.3.jar`(0.1.3 build + GameTest 已驗證,mods 內僅此一個 Magic Storage jar)。`latest.log` 看到 `SelfTest: 104 passed` 與 `BookContentResourceListenerLoader preloaded 17 jsons`;Patchouli content 路徑問題已修。Computer Use 在 native macOS fullscreen 下已可讀畫面並操作 Storage Terminal / Crafting Terminal:左側 view buttons 可點、>64 數量顯示為 128/192/8192 等實際網路數量。當 macOS 處於 lock screen 時,Computer Use 會 `cgWindowNotFound` 且全螢幕截圖只會看到鎖定畫面,必須先解鎖再做 GUI 目視/點擊驗證。
- 2026-07-08 再測發現:**不要在啟動前用 Minecraft `fullscreen:true` 搭配 macOS Stage Manager/Computer Use**。LWJGL 啟動即 fullscreen 會被 Stage Manager 放到偏移/裁切的桌面區域,截圖與 click 座標全偏。正確 GUI 驗證開法是先固定 windowed (`fullscreen:false`, `overrideWidth/Height`) + Prism `-w` 直接進測試世界;世界載入且視窗已 foreground 後,再用 F11/原生 fullscreen 切到「好全螢幕」並用截圖確認沒有偏移/裁切。若截圖仍偏,立刻 F11 回 windowed,不要繼續點 GUI。本機實測直接執行 `/Applications/.../prismlauncher -l dev -w "New World"` 可能只印出 world 參數後退出;用 `open -a "Prism Launcher" --args -l dev -w "New World"` 較穩。若 Prism Launcher 本體已開著但沒有啟動 Minecraft,`open -a ... -w MagicStorageGuiTest` 可能只 focus 舊 launcher、不傳新 quickPlay args;先 quit Prism Launcher 再重跑 `open -a`。log polling 要等本次新 log/mtime,不要被舊的 `MS_GUI_TEST_READY` 誤判。

固定 GUI test world 流程(先 windowed 直接進世界,再強制通過「全螢幕 gate」):

```bash
rg -n '^(OverrideCommands|WrapperCommand)=' \
  "$HOME/Library/Application Support/PrismLauncher/instances/dev/instance.cfg"
python3 scripts/prepare_prism_gui_world.py
open -a "Prism Launcher" --args -l dev -w "MagicStorageGuiTest"
```

`prepare_prism_gui_world.py` 只會覆蓋帶有 `.magic_storage_gui_test_world` marker 的 `MagicStorageGuiTest`;若同名世界不是腳本生成或來源/目標世界仍被 Minecraft 開啟會直接失敗。腳本從 `New World` 複製模板、把 `level.dat` 的 `LevelName=MagicStorageGuiTest` 與 `allowCommands=1`、清掉測試世界 player state、安裝 datapack `magic_storage_gui_test`、patch Prism `options.txt`(`fullscreen:false`,`pauseOnLostFocus:false`,`key_key.use:key.keyboard.u`,`guiScale:4`,`overrideWidth:1280`,`overrideHeight:720`)。datapack 只用 `load`/`tick`/hotbar-triggered functions,不使用 command block。

datapack load 會重建固定 rig;玩家第一次進入時 tick 會創造模式、清背包、給 `remote_terminal` + 大量 vanilla 測試物、傳送到 storage terminal 前,並 `say MS_GUI_TEST_READY`。Computer Use 不可靠輸入 chat 文字,所以主要用 hotbar key 觸發 view functions:按數字鍵切 slot,datapack 偵測 `SelectedItemSlot` 後立刻 `/tp facing` 對準目標;再按 `u` 開方塊 GUI。固定座標/視角:

| Hotbar key | Target | Block | Function | Notes |
|------------|--------|-------|----------|-------|
| `1` | Storage Terminal | `(0,64,1)` | `/function magic_storage_gui_test:view_storage_terminal` | 十字準心對準後按 `u` 開儲存終端 |
| `2` | Crafting Terminal | `(1,64,0)` | `/function magic_storage_gui_test:view_crafting_terminal` | 十字準心對準後按 `u` 開合成終端 |
| `3` | Storage Core | `(0,64,0)` | `/function magic_storage_gui_test:view_storage_core` | 看 core/block tooltip 或破壞重放測試 |
| `4` | T6 Storage Unit | `(-1,64,0)` | `/function magic_storage_gui_test:view_storage_unit_t6` | 預接 core,供容量/網路測試 |
| `5` | Import Bus | `(-1,64,1)` | `/function magic_storage_gui_test:view_import_bus` | 旁邊有 barrel |
| `6` | Export Bus | `(1,64,1)` | `/function magic_storage_gui_test:view_export_bus` | 旁邊有 barrel |
| `9` | Reset rig | n/a | `/function magic_storage_gui_test:reset_from_hotbar` | 重放 setup + 玩家 ready,用於每輪 GUI 測試前歸零 |

GUI 測試項目仍由當次變更動態決定,但所有 GUI 測試都必須先通過全螢幕 gate:等本次 `MS_GUI_TEST_READY` 後,先切到 macOS native fullscreen 或 F11,並重新截圖確認畫面沒有偏移/裁切。任何 `u`、hotbar、點擊、滾輪、截圖前,都必須先通過全螢幕 gate;通過後 agent 再選需要的 hotbar key 對準目標,只做該次必要的 `u`、點擊、滾輪、截圖與 log 檢查。`/function` 仍保留給人類手動輸入,但 Computer Use 優先用 hotbar,不要依賴 chat paste/type。不要寫固定 sleep;等一幀可完成的事就用激進 condition polling(例如每 0.1–0.25s 檢查 screenshot/log 狀態,最多有限 timeout),只有真正需要世界載入/資源重載才等 log 條件。

啟動後用 Computer Use 讀 `run.hapi.magicstorage.minecraftcu`;因為 `--world` 會跳過主選單/世界列表,進世界後先截圖確認 windowed 畫面完整。接著立刻按 macOS 綠色 fullscreen button(或送 F11)切 fullscreen,再截圖確認;未通過前不可開始 GUI 測項:

- Minecraft 內容沒有被 Stage Manager 側欄/桌面裁切。
- 遊戲畫面左下角版本文字與底部 hotbar/GUI 坐標完整可見。
- Computer Use `get_app_state` 沒有 timeout 或 `cgWindowNotFound`。

只有以上通過才算「好全螢幕」與全螢幕 gate passed。所有 GUI 測項都在這個全螢幕狀態下進行;然後依當次需求按 hotbar `1`–`6`/`9` 對準目標、按 `u` 開 GUI、檢查畫面與 log。不要把 `fullscreen` 寫回 `true`,讓下次仍從安全 windowed 啟動。

```bash
rg -n 'MS_GUI_TEST_READY|Magic Storage [0-9.]+|SelfTest: 104 passed|BookContentResourceListenerLoader preloaded|advanced_container_set_data|ERROR|FATAL|Caused by' \
  "$HOME/Library/Application Support/PrismLauncher/instances/dev/minecraft/logs/latest.log"
```

期望:看到 `MS_GUI_TEST_READY`、目前部署版本、`SelfTest: 104 passed, 0 failed, 104 total`、Patchouli `preloaded 17 jsons`,且沒有 `advanced_container_set_data` / `ERROR` / `FATAL` / `Caused by`。2026-07-09 已實測:固定 world 載入後通過全螢幕 gate,hotbar `1` 對準 Storage Terminal、hotbar `2` 對準 Crafting Terminal,按 `u` 都可在 fullscreen 下開 GUI。

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
- 實作進度以**程式碼為準**:已有 40 個 Java 檔 + SelfTest 104 / GameTest 102;設計細節見 `PLAN.md`,即時狀態見 `docs/plan.md`。
- 建置前需設 `JAVA_HOME`(JDK 21,見根 repo `docs/notes.md`)。
- **SelfTest 在 dedicated server 也會跑**(mod 建構時呼叫):絕不可從 SelfTest 參照 client-only 類別(如 `*Screen` extends `AbstractContainerScreen`),否則 server 端 RuntimeDistCleaner 會擋下、整個 mod 載入失敗。純邏輯放 dist-中性類別(如 enum:`SearchMode.apply`)再測。
- **`Slot.x`/`y` 是 `final`**:GUI 動態調整版面時不能改 slot 座標,需在 Screen(client)端 `menu.slots.set(i, new Slot(...))` 重建 slot(座標只供 client 渲染/命中,不上傳)。見 `StorageTerminalScreen.repositionPlayerInventory`。
- **終端機背景為程式繪製**(`StorageTerminalScreen.drawPanels`,`g.fill`/`renderOutline`):`grid.png`/`crafting_grid.png` 是針對固定 6 列手繪,且 6 參數 `blit` 以 256×256 正規化、動態高度會破圖,故改程式繪製扁平面板;那兩張 PNG 已不再使用。
- **終端 view 控制在左側 rail**:sort order / sort mode / search mode 三個按鈕位置走 dist-neutral `TerminalLayout`,位於 terminal 面板左外側(類 RS2 side buttons),避免佔用 grid/scrollbar;SelfTest 守護不可重新塞回右側。
- **顯示數量不可 clamp 到 max stack size**:`StorageCoreBlockEntity.getDisplayStacks` 的 display `ItemStack` count 代表網路實際數量(夾到 `Integer.MAX_VALUE`),可顯示 999 等大於 64 的數字;`StorageTerminalMenu` 的 display-only `SimpleContainer` 必須 override `getMaxStackSize(ItemStack)` 避免 `setItem` 再夾回 64。取物仍由 menu click path 決定每次搬運量。
- **Patchouli 1.20+ mod book 路徑**:`book.json` 留在 `data/magic_storage/patchouli_books/guide/book.json`,但 categories/entries/templates 必須放 `assets/magic_storage/patchouli_books/guide/en_us/...`;放在 `data/.../en_us` 會導致 `BookContentResourceListenerLoader preloaded 0 jsons` 與書內 `No Entries`。
- **Menu data-slot client/server parity**:server(core 建構子)與 client(buf 建構子)的 `addDataSlots` 數量必須**完全一致**,否則 `advanced_container_set_data` 同步會 `IndexOutOfBounds` 崩潰(開 storage terminal 即崩)。基礎 type-count slots 放在**兩條建構子都會經過**的 `addTypeDataSlots()`;crafting 在其上再 `addContainerData()`(兩邊一致)。有 `crafting_menu_data_slot_parity_server_vs_buf_ctor` GameTest 守護。畫面外/同步用的 slot(如 crafting 的 `selectionContainer`)要加在**玩家背包之後**且保持 inactive/offscreen,否則 `repositionPlayerInventory` 會錯位;EMI input sources 也必須排除這些 hidden metadata slots。
- **Menu open buffer contract**:storage/crafting terminal 的 buf 依序寫 `corePos`、`accessPos`、`remoteAccess`。本地 terminal `stillValid` 以 `accessPos`(開啟的 terminal block)距離判斷,不是 core 距離;remote terminal 寫 `remoteAccess=true` 且不受距離限制。新增 buf 欄位時兩端 constructor 與 parity GameTest 要一起改。
- **Storage API contract**:Core 的實際 mutation API 是 `insertItem(stack, Action, Actor)` / `extractItem(key, amount, Action, Actor)` / `extractMatching(predicate, amount, Action, Actor)`;`Action.SIMULATE` 只回報可處理量、不改 storage、不 fire event,`Action.EXECUTE` 才 setChanged + fire `StorageListener.onChanged(ItemKey, delta, newAmount, Actor)`。舊 boolean/no-arg overload 只是 bridge。
- **Client crafting screen 不做 server 邏輯**:`CraftingTerminalScreen` 不掃 `RecipeManager`、不直接讀 core storage/energy;recipe count/type、craftable count、missing preview 由 `CraftingTerminalMenu` server data/hidden slots 同步。SelfTest/GameTest 在 dedicated server 跑,client-only 類別仍不可被 server test 直接引用;Python static regressions 會守護這點。

## Decisions

- **統一能量池模型**:所有能量池同為 `Map<EnergyType, Long>` 無上限,差異只在來源,降低特例。
- **參考 RS2 而非依賴**:借設計模式,不加為依賴;加任何依賴前先問使用者(minimize deps)。
- **絕不整段照抄 RS2**:授權不同;只取模式自行實作。
- **一網一 core(multi-core 不支援)**:`rebuildNetwork` BFS 碰到第二個 core 即設 `conflicted` → 該 core 停止接受物品 + log 警告,避免雙重容量/非決定性;`isConflicted()` 可查、`tryIncrementalAdd` 在 conflicted 時退回 full rebuild。
- **Bus 吞吐 vs 省資源**:Import/Export bus 每個 cooldown(10 tick)搬「一整疊(≤64)」而非 1 個(吞吐 ×64、操作頻率不變 = 省資源);Export 只抽出目標放得下的量;無 core 時 BFS 也受 cooldown 節流(非每 tick)。
- **Bus cached core 必須驗證仍連通**:Import/Export bus 的 `cachedCore` 只有在 `core.getConnectedBlocks().contains(busPos)` 時可重用;中間 unit 被拆後不能靠舊 reference 繼續搬物。

## Hardening Pass (2026-06-21)

地毯式 bug 掃描 + 修正。關鍵點與借鏡的 RS2/AE2 模式(僅取模式,未照抄):

- **持久化**:`StorageCoreBlockEntity` 每次異動呼叫 `setChanged()`(否則正常存檔/卸載區塊會掉光物品+能量);庫存存讀改存完整 `ItemStack`(含 components:`toStack(1).save(registries)` / `ItemStack.parse(registries, tag)`),否則帶 NBT 的變體存讀後崩塌互蓋而損毀。參考 AE2「持久化完整 item key(item + components),勿只存 item id」。
- **匯流排/合成原子性**:Import bus 與 `craftItem` 改「先 simulate 再 commit」——能量/材料先驗證全部可行才實際扣除,任一不足則不動任何狀態。重複 ingredients 必須先彙總需求量(例:iron block 需要 9 ingots,8 ingots 不能 preview/craft)。參考 RS2 importer / autocrafting 的 `Action.SIMULATE` 兩階段;`ExportBusBlockEntity` 原本已是此 pattern。
- **insert/extract 契約**:`insertItem(stack, simulate)` 回傳實際接受量、`extractItem` 回傳實際取出量且庫存只減該量;呼叫端必須尊重回傳值(勿丟棄剩餘 → 否則網路滿時吃物)。
- **數值安全**:`consumeEnergy` 用 `Math.multiplyExact` 防 long 溢位(否則大 multiplier 使守衛失效→倒加能量/無限能量);`extractItem` 的 long→int 夾限。
- **GUI**:隱藏的 `GhostSlot` 以 `isActive()=index<visibleRows*9` 停用(否則 vanilla 仍對其畫 hover 白框/設 hoveredSlot/tooltip);按鈕點完 `setFocused(null)` 清焦點白邊;`applySettings` 的 `visibleRows` 在 server 端 `Math.clamp(…,3,9)`,不信任 client。
- **網路**:`onBlockBroken` 用 `server.execute(...)` 延到方塊真的移除後再重算容量(`BreakEvent` 在移除前觸發);`bfsFindCore` 加 `MAX_NETWORK_BLOCKS` 上限;封包 handler 包 `ctx.enqueueWork(...)` 確保 server 執行緒。參考 RS2「post-change 反應、單一 controller」。

## RS2 Heuristics Adopted (2026-06-21)

完整對照見 `docs/rs2-design-gap.md`(該採用 vs 故意分歧)+ 計劃 `docs/superpowers/plans/2026-06-21-rs2-heuristics-adoption.md`。已採用(僅取模式):

- **fuzzy 配對(A5b)**:合成材料以 `Ingredient.test` 跨變體,經 `core.countMatching` / `extractMatching(Predicate<ItemStack>)`(取代完全相同 `ItemKey` 比對 → 帶 NBT / tag 分散的材料現在配得到)。
- **Action/Actor + change events(A2/A3 foundation)**:storage mutation 都可帶 `Action` 與 `Actor`;execute insert/extract 會發 `StorageListener` delta event。P3 若要做 grid delta,應基於此 listener,不要再從 client 保存 storage state。
- **view 設定 server 同步(A5a)**:sort / order / search 模式為 server 權威、經 data slot 同步;終端按鈕標籤/tooltip 反映真實狀態(非 client 私有)。
- **選取物品身分化(A4)**:crafting 選取以 `ItemKey` 身分為準(經畫面外 `selectionContainer` slot 自動同步),非 grid slot index → 排序/捲動後配方面板不再亂指。
- **合成 preview(A7)**:用 simulate 路徑算「可做 N / 缺什麼」顯示,不異動狀態。
- **增量網路成長(A1,安全範圍)**:單純放置到既有 connected set 時 `StorageCoreBlockEntity.tryIncrementalAdd` O(1) 加入(無 full BFS);**破壞/不確定拓樸一律 full `rebuildNetwork`**(移除可能分裂網路,放置若 bridge detached segment 或連到多個 core 也屬不確定)。`capacityOf()` 是兩條路徑唯一的容量計算來源;GameTest 守護 simple growth、detached bridge full rebuild、multi-core bridge conflicted。
- **仍未採用(未來)**:**P3 增量 grid delta**(grid 改收差異、不整份重建)與完整常駐 network graph;目前分頁式 grid(≤81 格,vanilla 已增量同步)低價值,除非改成整列表 client grid 才值得。
