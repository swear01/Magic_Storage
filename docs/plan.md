# Plan

> 完整里程碑與設計見 `PLAN.md`(§八 開發里程碑 M1–M12)。狀態以程式碼為準(已有 ~36 Java 檔 + SelfTest/GameTest)。

M1–M11 大致到位(方塊/網路/六階 Unit/三階 Terminal/能量/即時+爐系合成/Compact/remote/buses,以程式碼為準)。近期已完成**健壯化** + **RS2 慣例採用**(fuzzy 配對、view 同步、選取身分化、合成 preview、增量網路成長),見 `docs/roadmap.md` / `docs/rs2-design-gap.md`。測試:SelfTest 101 + GameTest 72。

## In Progress

- (無進行中;上一批 RS2 P1/P2/P4 已 commit 並驗證)

## Next Up

- **P3 增量 grid delta**(grid 改收差異、不整份重建;前置 A2 Actor / A3 變更事件)。計劃見 `docs/superpowers/plans/2026-06-21-rs2-heuristics-adoption.md`。
- M12 Patchouli 遊戲內指南書。
