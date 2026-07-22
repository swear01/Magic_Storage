# RS2 Design Heuristics — Gap Analysis

> 對照 Refined Storage 2 的設計慣例(design heuristics),盤點 Magic_Storage 尚未採用的。
> 僅取**模式**,不照抄(授權不同)。RS2 參考:DeepWiki `refinedmods/refinedstorage2`。
> 注意:Magic_Storage 有**刻意的哲學**(配方書一鍵合成、無限數量/限種類、合成能量時間銀行),
> 部分 RS2 慣例是**故意分歧**——下方分「該採用」與「故意分歧」。

## A. 該採用(契合現有哲學,提升健壯/擴展/UX)

| # | RS2 慣例 | Magic_Storage 現況 | 缺口/風險 | 建議 | 工作量 |
|---|---------|------------------|----------|------|--------|
| A1 | **持久化 network graph + node visitor 增量更新**:RS2 維護常駐的 NodeGraph,方塊增刪時只增量更新受影響節點 | 已採用安全範圍:放置 callback 合併到 next-tick pass；單一安全放置用 cached-set bounded path check 後 `tryIncrementalAdd`，批次/破壞/不確定拓樸用 bounded full `rebuildNetwork`;活塞 old/new positions 與指令放置同樣覆蓋。Terminal/Bus 另 cache access path，每次只驗目前 loaded chunks/network blocks，失效時找 alternate loaded path | 大網路增量 depth check 仍需走 cached set BFS，破壞/分裂仍 O(min(全網,`MAX_NETWORK_BLOCKS`));尚無常駐 adjacency graph | 若真的需要,再改成常駐鄰接圖 + 局部 invalidation;目前低優先 | 大 |
| A2 | **統一 Storage 介面**:`insert/extract(resource, amount, Action, Actor)`,回傳實際處理量 | 已採用:`Action.SIMULATE/EXECUTE` + `Actor` 傳入 core insert/extract/extractMatching;舊 boolean/no-arg overload 保留為 bridge | 僅 item 資源;Actor 目前用於事件/追蹤,尚未做防自抽邏輯 | 泛型資源(流體)可暫緩;未來自動化可用 Actor 做來源隔離 | 小 |
| A3 | **事件驅動 + 增量 delta**:storage 變動發事件,grid/autocraft 訂閱只收差異 | `StorageListener.onChanged(...)` 由 execute insert/extract 觸發;開啟中的 server menu 訂閱後即時刷新 grid/preview,仍用 vanilla slot sync。多步合成用 mutation batch 延後 listener callback，玩家背包 fingerprint 與 Core topology revision 也會使 preview 失效 | 尚未做整列表 client grid 的自訂 delta packet;目前分頁式 grid ≤81 格,實益偏低 | 若改成整列表 client grid,再基於 listener 實作 P3 delta sync | 中 |
| A4 | **以資源身分(ResourceKey)為主,而非槽位 index**:選取、捲動、配方面板都綁資源身分 | 已採用：一般 selection 用 `ItemKey`；Storage/Craftable display stack 的 exact `long` amount 是可 strip 的 server metadata，Craftable server catalog 以 recipe/output identity 產生 zero-storage synthetic output並顯示目前 Core count；EMI request 傳 exact recipe id/amount/destination，diagram adapter 也只接受 server-synced ID 與 EMI backing holder 完全一致的 public recipe representation，不再依賴 output 已存或目前可見槽位 | (到位；synthetic output extraction 仍由 server count=0 明確拒絕，marker 不進 `ItemKey`；internal axe 明確走 native renderer) | 新 recipe type 仍須保留 exact identity/reload revalidation；不可接 EMI internal screen 作隱式 fallback | 小(慣例) |
| A5 | **同步且持久化的 view 設定 + 比對模式(fuzzy/ignore-NBT/ignore-damage)** | 已採用：sort/order/search mode/resource view及Crafting專用page/source/output/Fuel Target保存在physical-client全域NeoForge CLIENT config，開啟任何Terminal時送至server menu套用並由data slots確認；ack前只以持久值呈現UI、ack後回到server值，避免初始defaults閃動且不建立client storage state；合成材料 fuzzy 配對走 `Ingredient.test` | (到位；search query/scroll/selection/layout刻意只屬session，Core資料不進client config) | 後續若加 ignore-damage/ignore-NBT UI，仍走同一validated preference packet + server權威同步 | 小(慣例) |
| A6 | **simulate-then-commit 貫穿所有操作**(I/O、autocraft) | Import/export bus 與 `craftItem` 皆先驗證；Import 的正面 active pull 之外，所有面另暴露不保存物品、不可 extract 的 passive insert capability，simulate/execute 都尊重 Core 實際 accepted count並回傳 exact remainder；active/passive missing-Core lookup 共用10-tick negative cache，active bus commit 後精確交還 remainder，來源/Core 若拒收 rollback 就顯式掉出世界。Crafting 保留每個 `Ingredient` predicate 身分並建 joint reservation，再聯合規劃 post-consumption 玩家 inventory + Core output。兩邊無法完整收下 requested output 就整批拒絕；execute 前重解 recipe/station/Axe Energy identity，整個扣料/插 output/扣 process/Fuel/Axe Energy transaction 用 deferred-listener mutation batch 包住並可 rollback；每個 Core key 以單次 long-count mutation處理至 `Long.MAX_VALUE`，不做無界 int chunk loop | (大致到位；只接受能完整建模的 exact recipe class，其他 dynamic/context behavior fail closed) | 未來搬運/合成都共用 reserve/commit contract；unsupported/stale recipe fail closed，不加 world-drop output fallback；Export 無方向模式另案設計 | 小(慣例) |
| A7 | **合成前 preview「缺什麼/可做幾個」**:RS2 autocraft 先算缺料再執行 | 已採用：配方面板用同一 simulate/joint-reservation path 顯示 `Ready ×N`；server 另同步每個 ingredient predicate、finite/∞ Axe Energy 與 cooking process/Fuel 的 `Available / Required for one`。每次 refresh 只 snapshot 一次 Core/可選玩家材料來源，再供上界與 binary search 重用；overlapping predicates 的 row available 只是匹配量，整體 Ready 才是共同保留後真值 | (到位) | 新增 recipe type 時保持 preview、station/Axe Energy gate、resource rows、destination planning 與 execute 共用 contract | 小(慣例) |
| A8 | **大量資源數量先 compact format，再受 slot 邊界約束 render** | 已採用：`TerminalAmountFormatter` 產生不高估的 K/M/G/T/P/E；所有 visible amounts 使用畫面初始化時由 cell bound推導的一個 fixed scale，再在各自 16px cell 內右對齊，exact server-owned `long` 留在 tooltip。模式參考 RS2 `ItemGridResource` + `ResourceSlotRendering`，實作未照抄 | (到位；synthetic stack count 不承載邏輯量) | 若未來做 full-list custom packet，仍保持 server-owned long + cell-local rendering，不回退到 `ItemStack#getCount` | 小 |

## B. 故意分歧(要改哲學才採用 — 先別動,列為選項)

| # | RS2 慣例 | 為何 Magic_Storage 不同 | 採用條件 |
|---|---------|----------------------|---------|
| B1 | **autocrafting 依賴樹任務系統**(Pattern → 算樹 → 遞迴合成中間物 → 執行) | 哲學是「配方書一鍵、單層合成、合成能量取代等待」——EMI 已支援 exact recipe 的單層立即執行，但刻意不做 RS2 pattern tree/排程 | 若要「自動合成多階產物」才採用(大工程) |
| B2 | **每個 disk 有容量 + 優先序 + 分區(白/黑名單/voiding)** | 哲學是「無限數量、只限種類數;Unit 只貢獻種類槽」 | 若要改成「容量制」才採用 |
| B3 | **External Storage(把相鄰箱子當網路儲存)** | 目前用 import/export bus 搬運,不直接暴露 | 想要無縫整合外部箱子時 |
| B4 | **升級系統(speed/stack/range/fortune/silk)** | 尚未引入升級概念 | 想加深度時(可選) |
| B5 | **安全/權限(security card)** | 單機為主,未需要 | 多人共享網路要控權限時 |

## C. 建議採用順序(契合哲學者)

1. **P3 增量 grid delta(可延後)** — A3 listener foundation 已有;但目前分頁式 grid(≤81 格,vanilla 已增量同步)價值偏低,除非改成整列表 client grid 才值得做。
2. **A1 常駐 network graph(可延後)** — 目前只採用安全範圍的放置增量;若大網路破壞/分裂成瓶頸再做完整常駐圖。

## 參考

- Refined Storage 2 架構(NetworkController / ResourceRepository / NodeGraph + visitors / autocrafting / I-O 元件、API 與平台分層、事件驅動):https://deepwiki.com/refinedmods/refinedstorage2
- What's new in RS2:https://refinedmods.com/refined-storage/news/20250308-whats-new-in-refined-storage-2.html
