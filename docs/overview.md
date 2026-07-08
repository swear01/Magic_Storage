# Overview

## What This Is

`magic_storage` — NeoForge 1.21.1 的一站式儲存 + 合成 mod。玩家用一個終端完成儲存、取用與合成:無限容量(只限物品種類數)、配方書式一鍵合成、以及「合成能量」系統(被動累積時間、合成時一次消費)。

概念源自 **Terraria 的 Magic Storage**(無限容量、配方書操作);NeoForge 的儲存網路 / grid UI / 物品處理等**技術模式參考 Refined Storage 2** 原始碼(見 `docs/notes.md`)。

## Key Concepts / Domain

- **Storage Core** — 網路中樞,所有計算(容量、合成、能量)都在 Core;Unit 只提供空間。
- **Storage Unit** — 六個 Tier 的儲存方塊,獨立、不可逆、可並聯不限數量。
- **Crafting Energy** — 多個能量池(`smelting_energy`、`blasting_energy`…、`furnace_fuel`、`blaze_fuel`…),統一 `Map<EnergyType, Long>` 模型,差異只在來源。`RecipeType → EnergyCost` 決定消耗。
- **Terminal(三階)** — `storage_terminal`(T1 取存)、`crafting_terminal`(T2 配方+一鍵合成)、`remote_terminal`(T3 物品,遠端存取)。
- **Network** — Core 掃描/維護 Core ↔ Unit ↔ Terminal/Bus 連通;放置時可增量成長,破壞/不確定拓樸時 full rebuild;Import/Export Bus 做自動化。

## External Resources

- 技術參考(實作模式):Refined Storage 2 — https://github.com/refinedmods/refinedstorage2
- 概念來源:Terraria Magic Storage。
- 完整設計書:`PLAN.md`(同目錄)。
