# Overview

## What This Is

`magic_storage` — NeoForge 1.21.1 的一站式儲存 + 合成 mod。玩家用一個終端完成儲存、取用與合成:無限容量(只限物品種類數)、配方書式一鍵合成、以及由 Fuel 頁已安裝機器產生、合成時一次消費的「合成能量」。

概念源自 **Terraria 的 Magic Storage**(無限容量、配方書操作);NeoForge 的儲存網路 / grid UI / 物品處理等**技術模式參考 Refined Storage 2** 原始碼(見 `docs/notes.md`)。

## Key Concepts / Domain

- **Storage Core** — 網路中樞,所有計算(容量、合成、能量)都在 Core;Unit 只提供空間。
- **Storage Unit** — 六個 Tier 的儲存方塊,獨立、不可逆、可並聯不限數量。
- **Crafting Energy / Installed Stations** — 多個能量池統一用 `Map<EnergyType, Long>`。Core 持久化九格 equipment：Furnace/Blast Furnace/Smoker/Campfire/Brewing Stand 會按 stack `N` 為對應 process pool 產生 `N/tick`；Crafting Table/Stonecutter/Smithing Table/axe 只解鎖配方或工具動作，不產能。`ItemStack#getBurnTime(null)` 是 Fuel 真值；具體 cooking recipe 的 `cookingtime` 同時決定 process/Fuel 消耗。內部 `furnace_fuel` ID 不變，玩家只看到 **Fuel**。所有 mutation 在 server，client 只接同步。
- **Crafting/Fuel GUI** — `TerminalLayout.Geometry` 是 rendering、widgets、slots、hitboxes、tooltips、scroll 與 EMI exclusion 的單一幾何來源。Crafting 使用明確的 Storage/Craftable/Fuel pages；item pages 的三個 page tabs 與 sorting/search controls 有 geometry-defined gap，Fuel rail 則只保留三個 page tabs。Storage/Craftable 的每個 synthetic display stack 都攜帶 server-owned exact `long` amount metadata，`ItemStack#getCount` 固定只維持可見 icon；Craftable 顯示目前 Core 庫存量而非可合成產量，零庫存仍可見但數量為零。Storage/Craftable 共用 Name/Quantity/Mod/ID comparator，最後以完整 registry/component identity 穩定排序。Energy Reserves header 內單一 vanilla `CycleButton` 同時顯示並切換目前 Auto/Fuel/Brew/Bottle target；普通點擊向前，Shift+點擊或 hovered wheel 反向，server 只接受 Auto/exact target IDs。Storage/Craftable 使用 server-synced input → operation → output、Ready/Missing 與 `Available / Required` recipe workspace，不嵌入 EMI 私有畫面。Fuel 的 Installed Stations 使用兩個 flow rows；wide layout 的 machine/reserve panels 使用完整 inner width，右下 `fuelControlPanel` 與 player inventory 同高並容納 target selector/Fuel input，descriptor 超出 capacity 才採 panel-local wheel paging。全部八種 energy 用對應 machine/fuel item 作代表物，Fuel 頁另顯示會隨 storage mutation/topology revision 更新的 server-synced stored/max types。常駐畫面不揭露 `stack = energy/t`，精確 identity/rate 由 hover tooltip 發現；所有 status values 無文字陰影。Grid 數量先 compact-format，再依 16px 門檻正常或半尺寸 right-align，不能侵入相鄰 slot。數量列為 live-disabled `×1/×8/×64` 加不受 9,999 顯示上限截斷的 server-revalidated `Max`。Grid 永遠保留完整 `ItemKey` variants；slot reconstruction 永遠委派 constructor 時保存的 original semantic slots。0.1.15 的 descriptor 集合仍由程式碼定義，跨 mod runtime registration/sync API 列入 roadmap。
- **Terminal(三階)** — `storage_terminal`(T1 取存)、`crafting_terminal`(T2 配方+一鍵合成)、`remote_terminal`(T3 物品,同維度遠端存取；綁 exact Core UUID,同座標 replacement 不會被靜默接管；重綁不覆蓋其他 `CUSTOM_DATA`)。
- **Network** — Core 掃描/維護 Core ↔ Unit ↔ Terminal/Bus 連通;一般/指令/活塞放置會合併到 next-tick topology pass，安全範圍內增量成長，破壞/不確定拓樸時 full rebuild。Terminal/Bus 只沿目前已載入且仍為 network block 的 cached path 工作，失效時改找另一條已載入路徑，絕不為驗證連線載入 chunk；Import/Export Bus 做自動化。
- **Supported recipes / safe output** — Vanilla exact-concrete Shaped/Shapeless Crafting、Stonecutting、Smelting、Blasting、Smoking、Campfire、component-exact `SmithingTransformRecipe`，以及只掃 `minecraft` namespace default state 的 internal synthetic axe strip/scrape/wax-off；每種都先要求目前 Core 安裝 matching station/tool。Smithing 三個 role 直接用原 recipe predicate 比對實際完整 stack，不用 registry-default Ingredient 近似重建。Preview/execute 從目前 server `RecipeManager` 或 exact synthetic axe catalog 重取 holder。Craftable 由 server recipe catalog 產生目前可合成的 output union，display amount 只取該 output 的目前 Core 庫存；synthetic marker 在建立 `ItemKey` 或實際輸出前會移除。EMI 傳 exact recipe ID + amount + destination，NONE 只選取，CURSOR/INVENTORY 執行單層 server-authoritative magic craft。Inventory output 先進玩家 36 格，overflow 回 Core；兩者都放不下就整批拒絕，不掉落、不消失。Max 先計算資源上限，再找出 destination 可完整接收的最大 craft 數；midpoint、Core reservation/extraction 與 output insertion 全程可跨 `Integer.MAX_VALUE`。Commit 在 deferred-listener mutation batch 內驗證、扣料、插入 overflow、扣能、耗工具耐久再套用玩家 inventory/cursor。仍不做 RS2 recursive pattern tree；Smithing Trim、special/dynamic subclasses、Brewing、任意 mod block hook 與自由 world/player-context recipe 目前不執行。
- **Assets** — 所有 block/item model 實際引用的 mod texture 都是 vanilla resolution 16×16；生成 metadata/preview 保存在 runtime tree 外的 `art/texture-generation/`。尺寸 static test 不取代 fullscreen 實機視覺 gate。

## External Resources

- 技術參考(實作模式):Refined Storage 2 — https://github.com/refinedmods/refinedstorage2
- 概念來源:Terraria Magic Storage。
- 完整設計書:`PLAN.md`(同目錄)。
