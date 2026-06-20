> Archived: 2026-06-20
> Reason: Implemented: StorageTerminalScreen redesign (imageWidth=210, dynamic MIN/MAX_VISIBLE_ROWS, RS2-style buttons, searchBox) shipped in code; spec superseded.
> Replacement: src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java
> Status: historical only; do not use as active truth.

# Magic Storage Terminal UI 修正設計文件

**日期：** 2026-05-19  
**狀態：** 已核准，待實作

---

## 問題描述

StorageTerminalScreen 目前有三個視覺問題：

1. **寬度不一致**：`imageWidth=227` 但 slot 格子只到 x=170，scrollbar 到 x=186，右側有 41px 空白
2. **高度硬編碼**：`VISIBLE_ROWS=6` 固定，不依螢幕高度自動調整
3. **搜尋欄無背景**：搜尋欄區域沒有視覺框線或背景

同時缺少右側功能按鈕（仿 Refined Storage 2 風格）。

---

## 新佈局尺寸

```
imageWidth = 210px
  ├─ 8  (left pad)
  ├─ 162 (9×18 item grid)
  ├─ 4  (gap)
  ├─ 12 (scrollbar, SB_X=174 不變)
  ├─ 2  (gap)
  ├─ 18 (button column, BUTTON_X=188)
  └─ 4  (right pad)

imageHeight = TOP_HEIGHT(19) + BOTTOM_HEIGHT(99) + visibleRows×18  ← 動態
```

---

## 新增 Enum 類別

### `SortMode.java`
```java
public enum SortMode { NAME, QUANTITY, ID }
```

### `SortOrder.java`
```java
public enum SortOrder { ASCENDING, DESCENDING }
```

### `SearchMode.java`
```java
public enum SearchMode { NORMAL, TAG, MOD }
// NORMAL = 預設文字搜尋
// TAG    = 自動加 # prefix → 依 tag 過濾
// MOD    = 自動加 @ prefix → 依 mod id 過濾
```

---

## 新增 Packet

### `TerminalSettingsPacket.java`

```java
public record TerminalSettingsPacket(
    int containerId,
    int sortModeOrdinal,
    int sortOrderOrdinal,
    int searchModeOrdinal,
    int visibleRows
) implements CustomPacketPayload {
    public static final Type<TerminalSettingsPacket> TYPE = ...;
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalSettingsPacket> STREAM_CODEC = ...;
}
```

**流程：** 玩家按按鈕 → Client 傳送封包 → Server 更新設定 → `refreshDisplayItems()` 重新排序/過濾

---

## StorageTerminalMenu.java 修改

**路徑：** `src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalMenu.java`

| 項目 | 修改 |
|---|---|
| `DISPLAY_ROWS` | 改為 `MAX_DISPLAY_ROWS = 9` |
| `DISPLAY_SLOTS` | 改為 `81`（9×9） |
| 新欄位 | `visibleRows`（預設 6）、`sortMode`、`sortOrder`、`searchMode` |
| `setupSlots()` | 建立 81 個 GhostSlot |
| `refreshDisplayItems()` | 只填 `0..visibleRows*9-1`，其餘清空 |
| scroll math | `maxOffset = totalItemTypes - visibleRows * 9` |
| `clickMenuButton()` | buttonId 2-6 處理設定變更 |
| 新方法 | `applySettings(TerminalSettingsPacket)`、`getVisibleRows()` |

---

## StorageCoreBlockEntity.java 修改

**路徑：** `src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java`

新增排序版本：
```java
public List<ItemStack> getDisplayStacks(String filter, SortMode mode, SortOrder order) {
    List<ItemStack> result = getDisplayStacks(filter); // 現有過濾邏輯
    Comparator<ItemStack> cmp = switch (mode) {
        case NAME     -> Comparator.comparing(s -> s.getHoverName().getString());
        case QUANTITY -> Comparator.comparingLong(s -> getItemCount(ItemKey.of(s)));
        case ID       -> Comparator.comparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
    };
    result.sort(order == SortOrder.DESCENDING ? cmp.reversed() : cmp);
    return result;
}
```

舊的 `getDisplayStacks(String filter)` 保留作委派。

---

## StorageTerminalScreen.java 修改

**路徑：** `src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java`

### 動態列數（仿 RS2）

```java
@Override
protected void init() {
    int visibleRows = Math.max(3, (this.height - getTopHeight() - getBottomHeight()) / 18);
    this.imageWidth = 210;
    this.imageHeight = getTopHeight() + getBottomHeight() + visibleRows * 18;
    this.inventoryLabelY = imageHeight - 94;
    super.init();
    // ... 建立 searchBox、按鈕
    sendSettings(); // 通知 server visibleRows
}
```

### 搜尋區背景（程式碼繪製）

```java
// 在 renderBg() 繪製背景圖後加入：
g.fill(leftPos + 79, topPos + 4, leftPos + 171, topPos + 16, 0xFF000000);
g.renderOutline(leftPos + 79, topPos + 4, 92, 12, 0xFF555555);
```

### 右側按鈕（5 個，垂直排列，BUTTON_X=188）

| # | 功能 | Y offset (from gridTop) | 行為 |
|---|---|---|---|
| 0 | 排序方向 | +0  | ASCENDING ↔ DESCENDING |
| 1 | 排序依據 | +20 | NAME → QUANTITY → ID 循環 |
| 2 | 顯示模式 | +40 | GRID ↔ LIST |
| 3 | 僅可合成 | +60 | toggle |
| 4 | 搜尋模式 | +80 | NORMAL → TAG → MOD 循環 |

### Slot 渲染限制

```java
@Override
protected void renderSlot(GuiGraphics g, Slot slot) {
    if (slot.index < StorageTerminalMenu.DISPLAY_SLOTS
            && slot.index >= menu.getVisibleRows() * 9) return;
    super.renderSlot(g, slot);
}
```

`mouseClicked` 和 `isHovering` 同樣加入邊界判斷，避免玩家點擊到隱藏 slot。

---

## MagicStorage.java 修改

**路徑：** `src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java`

1. 註冊 `TerminalSettingsPacket.TYPE` 和 `STREAM_CODEC`
2. 新增 handler：`menu.applySettings(packet)` → `refreshDisplayItems(core)`

---

## CraftingTerminalScreen.java 修改

**路徑：** `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java`

- 移除 `imageWidth` hardcode，繼承父類的 210
- `imageHeight = 256` 暫時保留

---

## 實作順序

1. `SortMode.java`, `SortOrder.java`, `SearchMode.java`（enum）
2. `TerminalSettingsPacket.java`（含 STREAM_CODEC）
3. `StorageCoreBlockEntity.java` → 加入排序版 `getDisplayStacks()`
4. `StorageTerminalMenu.java` → MAX_DISPLAY_ROWS=9、81 slots、visibleRows、applySettings()
5. `MagicStorage.java` → 註冊新封包
6. `StorageTerminalScreen.java` → 動態尺寸、搜尋區背景、右側按鈕、slot 限制
7. `CraftingTerminalScreen.java` → 適配新寬度
8. `./gradlew build` 驗證

---

## 驗證方式

1. `./gradlew build` 編譯無誤
2. 啟動遊戲開啟 Storage Terminal：
   - 介面背景寬度（210px）與物品欄對齊
   - 調整視窗高度，物品格列數自動變化（最少 3 列）
   - 搜尋欄有深色背景框
   - 5 個右側按鈕可點擊，排序/過濾即時生效
3. `./gradlew runGameTestServer` SelfTest 通過
