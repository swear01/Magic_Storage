# RS2 Design Heuristics — Gap Analysis

> 對照 Refined Storage 2 的設計慣例(design heuristics),盤點 Magic_Storage 尚未採用的。
> 僅取**模式**,不照抄(授權不同)。RS2 參考:DeepWiki `refinedmods/refinedstorage2`。
> 注意:Magic_Storage 有**刻意的哲學**(配方書一鍵合成、無限數量/限種類、合成能量時間銀行),
> 部分 RS2 慣例是**故意分歧**——下方分「該採用」與「故意分歧」。

## A. 該採用(契合現有哲學,提升健壯/擴展/UX)

| # | RS2 慣例 | Magic_Storage 現況 | 缺口/風險 | 建議 | 工作量 |
|---|---------|------------------|----------|------|--------|
| A1 | **持久化 network graph + node visitor 增量更新**:RS2 維護常駐的 NodeGraph,方塊增刪時只增量更新受影響節點 | 已採用安全範圍:放置時 `tryIncrementalAdd` O(1) 成長;破壞/不確定拓樸仍 full `rebuildNetwork`;無 core 的 bus 每 cooldown 重掃(已加上限) | 大網路破壞/分裂仍 O(全網);尚無常駐 adjacency graph | 若真的需要,再改成常駐鄰接圖 + 局部 invalidation;目前低優先 | 大 |
| A2 | **統一 Storage 介面**:`insert/extract(resource, amount, Action, Actor)`,回傳實際處理量 | 已採用:`Action.SIMULATE/EXECUTE` + `Actor` 傳入 core insert/extract/extractMatching;舊 boolean/no-arg overload 保留為 bridge | 僅 item 資源;Actor 目前用於事件/追蹤,尚未做防自抽邏輯 | 泛型資源(流體)可暫緩;未來自動化可用 Actor 做來源隔離 | 小 |
| A3 | **事件驅動 + 增量 delta**:storage 變動發事件,grid/autocraft 訂閱只收差異 | 已有 foundation:`StorageListener.onChanged(ItemKey, delta, newAmount, Actor)` 由 execute insert/extract 觸發;grid 仍保留 `cacheDirty` + vanilla slot sync | 尚未做整列表 client grid 的 delta packet;目前分頁式 grid ≤81 格,實益偏低 | 若改成整列表 client grid,再基於 listener 實作 P3 delta sync | 中 |
| A4 | **以資源身分(ResourceKey)為主,而非槽位 index**:選取、捲動、配方面板都綁資源身分 | 已採用:`crafting_terminal` selection 以 `ItemKey` 身分同步,非 grid slot index | (大致到位) | 後續 UI 新增選取功能仍要綁資源身分 | 小(慣例) |
| A5 | **同步的 view 設定 + 比對模式(fuzzy/ignore-NBT/ignore-damage)** | 已採用:sort/order/search 模式 server 同步;合成材料 fuzzy 配對走 `Ingredient.test` | (大致到位) | 後續若加 ignore-damage/ignore-NBT UI,仍需 server 權威同步 | 小-中 |
| A6 | **simulate-then-commit 貫穿所有操作**(I/O、autocraft) | 本次已補:import bus、craftItem;Core insert/extract 有 simulate | (大致到位) | 確保所有未來新增的搬運/合成都走 simulate-first | 小(慣例) |
| A7 | **合成前 preview「缺什麼/可做幾個」**:RS2 autocraft 先算缺料再執行 | 已採用:配方面板用 simulate path 顯示可做數/缺料 | (大致到位) | 新增 recipe type 時保持 preview 與 execute 共用 simulate contract | 小(慣例) |

## B. 故意分歧(要改哲學才採用 — 先別動,列為選項)

| # | RS2 慣例 | 為何 Magic_Storage 不同 | 採用條件 |
|---|---------|----------------------|---------|
| B1 | **autocrafting 依賴樹任務系統**(Pattern → 算樹 → 遞迴合成中間物 → 執行) | 哲學是「配方書一鍵、單層合成、合成能量取代等待」——刻意不做 RS2 那套複雜 pattern/排程 | 若要「自動合成多階產物」才採用(大工程) |
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
