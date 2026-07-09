# Roadmap

## Backlog

- 擴充方塊(第二階段,PLAN §四)。
- Tier 3 遠端存取完整體驗(背包開關、遠端 GUI 優化)。
- Modrinth / CurseForge 發布:未來等公開頁面 metadata、icon、description、dependency matrix、secrets 與授權策略確認後再接進 release workflow;目前 CD 僅建立 GitHub Release。

## Recently Done

- GitHub public repo / CI/CD:新增 public repo https://github.com/swear01/Magic_Storage；CI 在 push/PR/manual 跑 JDK 21 build、GameTest、Python unittest、datagen drift check,並上傳 jar/log/report artifacts；CD 在 tag `v<mod_version>` 時驗版本、重跑 gates、產生 release notes 並建立 GitHub Release。
- 健壯化 pass(地毯式掃描修正):持久化(setChanged、NBT components 存讀)、關閉複製、import bus 吃物、合成回滾、能量溢位、白框/tooltip/焦點、enqueueWork、BFS 上限等。
- RS2 設計慣例採用(見 `docs/rs2-design-gap.md` + `docs/superpowers/plans/2026-06-21-...`):fuzzy 合成配對(A5b)、view 設定 server 同步(A5a/#7)、選取物品身分化(A4/#5)、合成 preview(A7)、捲軸/版面修正(#8)。P4 增量網路成長(A1:放置增量 + 破壞 full rebuild 後盾)。剩 P3 增量 grid(+ 前置 A2 Actor / A3 變更事件)。SelfTest 104 + GameTest 74 全綠。
- Terminal UI 重設計:動態列數(依視窗高度,3–9 列)、排序(名稱/數量/ID × 升降序)、搜尋模式(`#tag` / `@mod` 前綴)、程式繪製背景(取代固定 6 列材質,解除 256px blit 限制)、RS2-like 左側 view buttons,以及超過 64 的網路實際數量顯示(含 menu display slot 不再被 `SimpleContainer` 夾回 64)。2026-07-08 已用 Prism dev 0.1.3 + Computer Use wrapper + macOS native fullscreen 確認 Storage Terminal / Crafting Terminal GUI 可開啟、左側 view buttons 可點、128/192/8192 等網路實際數量可見,且 log 無 container sync 崩潰。
- Patchouli 指南書內容載入修正:localized categories/entries 移到 `assets/.../patchouli_books/guide/en_us`;`book.json` 留在 `data/.../guide/book.json`。2026-07-08 Prism dev `latest.log` 顯示 `BookContentResourceListenerLoader preloaded 17 jsons`,不再是 0;實機開書可看到分類與說明內容。
- 方塊 & BlockEntity 註冊、模型、材質(M1)。
- 網路 BFS 掃描、Core ↔ Unit 連通(M2)。
- Storage Unit 六 Tier、`storage_terminal`、能量池系統、`crafting_terminal`、即時/熔爐類合成(M3–M8 大致到位,以程式碼為準)。
