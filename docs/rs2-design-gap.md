# RS2 Design Heuristics — Gap Analysis

> 對照 Refined Storage 2 的設計慣例(design heuristics),盤點 Magic_Storage 尚未採用的。
> 僅取**模式**,不照抄(授權不同)。RS2 參考:DeepWiki `refinedmods/refinedstorage2`。
> 注意:Magic_Storage 有**刻意的哲學**(配方書一鍵合成、無限數量/限種類、合成能量時間銀行),
> 部分 RS2 慣例是**故意分歧**——下方分「該採用」與「故意分歧」。

## A. 該採用(契合現有哲學,提升健壯/擴展/UX)

| # | RS2 慣例 | Magic_Storage 現況 | 缺口/風險 | 建議 | 工作量 |
|---|---------|------------------|----------|------|--------|
| A1 | **持久化 network graph + node visitor 增量更新**:RS2 維護常駐的 NodeGraph,方塊增刪時只增量更新受影響節點 | 每次變更從 core 全圖 BFS;無 core 的 bus 每 N tick 重掃(已加上限) | 大網路每次變更 O(全網)重掃,擴展性差 | 改成常駐鄰接圖 + 增量 invalidate(core 持圖,方塊事件只更新局部) | 大 |
| A2 | **統一 Storage 介面**:`insert/extract(resource, amount, Action, Actor)`,回傳實際處理量 | 已有 item map + simulate(本次修);**無 Actor(來源)**、僅 item | 無來源追蹤(autocraft/防自我抽取需要);只支援 item | 加 `Actor` 參數(為未來預留);泛型資源(流體)可暫緩 | 中 |
| A3 | **事件驅動 + 增量 delta**:storage 變動發事件,grid/autocraft 訂閱只收差異 | `cacheDirty` flag → 每次**整份重建** display list + 整份同步 | 頻寬與 CPU 浪費;大量物品時卡 | grid 改增量更新(只送變動的資源);storage 發變動事件 | 中 |
| A4 | **以資源身分(ResourceKey)為主,而非槽位 index**:選取、捲動、配方面板都綁資源身分 | crafting 選取用 grid slot index(排序/捲動後指向錯物品 — 待修 #5) | 排序/捲動後 UI 指向錯物品 | 選取/配方面板改綁 `ItemKey`(已在剩餘清單) | 小-中 |
| A5 | **同步的 view 設定 + 比對模式(fuzzy/ignore-NBT/ignore-damage)** | 排序/搜尋**只在 client**(desync 待修 #7);配對僅**完全相同 components**(本次合成配對 bug 根源) | 按鈕標籤不反映真實狀態;帶 NBT 材料配不到 | view 設定用 data slot 同步;加比對模式(至少 fuzzy/ignore-NBT 供合成配對) | 中 |
| A6 | **simulate-then-commit 貫穿所有操作**(I/O、autocraft) | 本次已補:import bus、craftItem;Core insert/extract 有 simulate | (大致到位) | 確保所有未來新增的搬運/合成都走 simulate-first | 小(慣例) |
| A7 | **合成前 preview「缺什麼/可做幾個」**:RS2 autocraft 先算缺料再執行 | 無 preview;直接試、失敗無明確回饋 | 玩家不知為何不能合成 | 用已有 simulate 算「可做 N 個 / 缺 X」顯示在配方面板 | 小-中 |

## B. 故意分歧(要改哲學才採用 — 先別動,列為選項)

| # | RS2 慣例 | 為何 Magic_Storage 不同 | 採用條件 |
|---|---------|----------------------|---------|
| B1 | **autocrafting 依賴樹任務系統**(Pattern → 算樹 → 遞迴合成中間物 → 執行) | 哲學是「配方書一鍵、單層合成、合成能量取代等待」——刻意不做 RS2 那套複雜 pattern/排程 | 若要「自動合成多階產物」才採用(大工程) |
| B2 | **每個 disk 有容量 + 優先序 + 分區(白/黑名單/voiding)** | 哲學是「無限數量、只限種類數;Unit 只貢獻種類槽」 | 若要改成「容量制」才採用 |
| B3 | **External Storage(把相鄰箱子當網路儲存)** | 目前用 import/export bus 搬運,不直接暴露 | 想要無縫整合外部箱子時 |
| B4 | **升級系統(speed/stack/range/fortune/silk)** | 尚未引入升級概念 | 想加深度時(可選) |
| B5 | **安全/權限(security card)** | 單機為主,未需要 | 多人共享網路要控權限時 |

## C. 建議採用順序(契合哲學者)

1. **A4 資源身分化選取** + **A5 view 設定同步 / fuzzy 配對** — 修掉現有 desync 與合成配對 bug,UX 直接受益。(已部分在剩餘修正清單)
2. **A3 事件驅動 + 增量 grid** — 解決大量物品時的效能/頻寬;為 A1 鋪路。
3. **A7 合成 preview** — 低成本、高 UX(用已有 simulate)。
4. **A2 Actor 參數** — 低成本預留,為任何未來自動化鋪路。
5. **A1 增量 network graph** — 擴展性根本改善,但工程較大,可最後做。

## 參考

- Refined Storage 2 架構(NetworkController / ResourceRepository / NodeGraph + visitors / autocrafting / I-O 元件、API 與平台分層、事件驅動):https://deepwiki.com/refinedmods/refinedstorage2
- What's new in RS2:https://refinedmods.com/refined-storage/news/20250308-whats-new-in-refined-storage-2.html
