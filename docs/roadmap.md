# Roadmap

## Backlog

- 擴充方塊(第二階段,PLAN §四)。
- Tier 3 遠端存取完整體驗(背包開關、遠端 GUI 優化)。
- Patchouli 指南書(教學、配方參考、升級路線)。

## Recently Done

- Terminal UI 重設計:動態列數(依視窗高度,3–9 列)、排序(名稱/數量/ID × 升降序)、搜尋模式(`#tag` / `@mod` 前綴)、程式繪製背景(取代固定 6 列材質,解除 256px blit 限制)。SelfTest 85 + GameTest 63 全綠;GUI 外觀待實機確認。
- 方塊 & BlockEntity 註冊、模型、材質(M1)。
- 網路 BFS 掃描、Core ↔ Unit 連通(M2)。
- Storage Unit 六 Tier、`storage_terminal`、能量池系統、`crafting_terminal`、即時/熔爐類合成(M3–M8 大致到位,以程式碼為準)。
