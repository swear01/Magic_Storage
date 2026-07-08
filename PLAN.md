# Magic Storage Mod — 設計計劃書

> 靈感來源：Terraria Magic Storage  
> 目標平台：Minecraft（NeoForge）  
> 狀態：核心已實作（即時進度見 `docs/plan.md`；本文件為設計參考）

---

## 一、核心設計理念

### 設計哲學：讓玩家開心，不做人工障礙
- **All-in-One 工作站**：一個終端完成所有合成
- **無限容量**：儲存不限制數量，只限制物品種類數
- **合成能量無上限**：先累積後享用，不卡玩家
- **配方書式操作**：點選物品 → 下方面板顯示配方 → 點按鈕瞬間合成

---

## 二、合成能量系統（Crafting Energy）

### 核心概念
Storage Core 被動累積時間，把「本來要等的時間」預先存起來，合成時一次消費。
玩家也可塞燃料物品到特定能量池加速累積。

### 能量池：統一模型，差異只在來源

所有能量池結構相同（`Map<EnergyType, Long>`，無上限），差異在**來源**：

| 能量池 | 來源 | 用途 |
|--------|------|------|
| `smelting_energy` | 自動 +1/tick | 熔爐配方 |
| `blasting_energy` | 自動 +1/tick | 高爐配方 |
| `smoking_energy` | 自動 +1/tick | 煙燻爐配方 |
| `campfire_energy` | 自動 +1/tick | 營火配方 |
| `brew_energy` | 自動 +1/tick | 釀造台配方 |
| `furnace_fuel` | 塞煤炭/木炭/岩漿桶 | 所有爐系配方額外消耗 |
| `blaze_fuel` | 塞烈焰粉/烈焰桿 | 釀造配方額外消耗 |
| `bottle_fuel` | 塞玻璃瓶/水瓶 | 釀造輸出容器 |

工作台 shaped/shapeless、切石機 stonecutting、鍛造台 smithing 不需要能量（即時合成）。

### RecipeType → 消耗的能量池

```java
record EnergyCost(long processEnergy, long fuelEnergy) {}

Map<RecipeType, EnergyCost> RECIPE_ENERGY = Map.of(
    SMELTING,   new EnergyCost(200, 200),   // smelting_energy + furnace_fuel
    BLASTING,   new EnergyCost(100, 100),   // blasting_energy + furnace_fuel
    SMOKING,    new EnergyCost(100, 100),   // smoking_energy  + furnace_fuel
    CAMPFIRE,   new EnergyCost(600, 600),   // campfire_energy + furnace_fuel
    BREWING,    new EnergyCost(400, 20)     // brew_energy + blaze_fuel (+ optional bottle_fuel)
);
```

### Fuel Value 對照表

| 燃料物品 | 提供給 | 每個價值 |
|---------|--------|---------|
| 煤炭/木炭 | `furnace_fuel` | 1,600 |
| 煤炭方塊 | `furnace_fuel` | 16,000 |
| 岩漿桶 | `furnace_fuel` | 20,000 |
| 烈焰桿 | `furnace_fuel` 或 `blaze_fuel`（手動選） | 2,400 / 1,200 |
| 烈焰粉 | `blaze_fuel` | 600 |

### 燃料塞入方式

在 Storage Terminal 中，Shift+背包格物品時：
- 若物品是燃料 → 跳出選擇畫面：**「補充哪個能量池？」**
- 玩家點選目標池 → 物品消耗，對應能量增加
- 同一物品可對應多個池（如烈焰桿）→ 選擇畫面列出所有可補充的池

### 能量不足顯示

Crafting Terminal 配方詳情區：
- 足夠：`smelt 12,450 / 200 ✅`
- 不足：`smelt 50 / 200 ❌`（紅色）
- 批量：`×64 需要 12,800 smelt，目前 3,200 ❌`

### 批量合成
- **線性消耗**：合成 ×N 就消耗 ×N CE
- 能量不足時顯示「可合成 X 個（XX 能量不足）」

---

## 三、物品儲存系統

### 架構原則：所有計算在 Core，Unit 只是空間

- **Storage Unit 沒有自己的庫存**，它只是一個「容量貢獻方塊」
- Core 掃描到 Unit 後，把它的種類上限加進自己的總額度
- 所有物品資料、庫存索引、能量池都存在 Core 的 BlockEntity 裡
- NBT 不同 = 不同種（帶附魔的劍 ≠ 普通劍）
- 每種物品數量無限，並聯 Unit 數量不限

### Storage Unit 各 Tier

| 方塊 ID | 貢獻種類槽 | 說明 |
|---------|-----------|------|
| `storage_unit` | +10 種 | 基礎款 |
| `storage_unit_t1` | +25 種 | Tier 1 |
| `storage_unit_t2` | +50 種 | Tier 2 |
| `storage_unit_t3` | +100 種 | Tier 3 |
| `storage_unit_t4` | +200 種 | Tier 4 |
| `storage_unit_t5` | +400 種 | Tier 5 |

- 各 Tier 是獨立方塊，合成需要上一 Tier 作為材料（強制線性升級）
- **不可逆**：破壞只掉回基礎 `storage_unit`，Core 重新掃描後自動調整總額度
- 總種類槽 = 所有連接 Unit 的貢獻加總

```
範例：
  storage_core 連接了：
    storage_unit    × 3  →  +30 種
    storage_unit_t2 × 1  →  +50 種
  ─────────────────────────────
  總可用種類槽：80 種（全部由 Core 統一管理）
```

### 超額處理
若破壞 Unit 導致總額度低於現有種類數，Core 標記為「滿槽」，現有物品仍可存取，但無法存入新種類，直到額度恢復。

---

## 四、方塊設計

> **連接規則：實體相鄰（六面），不需管道**  
> **每個網路只有一個 `storage_core`，作為系統偵測的唯一切入點**

### 核心方塊

| 方塊 | 功能 |
|------|------|
| `storage_core` | 網路唯一中樞；持有庫存、能量池、種類額度；BFS 掃描起點 |
| `storage_unit` ~ `storage_unit_t5` | 容量擴充方塊（貢獻 +10/+25/+50/+100/+200/+400 種類槽給 Core）；無自身庫存；並聯不限；升級不可逆 |
| `storage_terminal` | Tier 1：網路物品格（可取可存），無配方面板 |
| `crafting_terminal` | Tier 2：網路物品格 + 配方面板，可合成 |
| `remote_terminal` | Tier 3：**物品**（非方塊）；功能同 T2；Shift+右鍵點 Core 綁定；右鍵開 GUI |

### 擴充方塊（第二階段）

| 方塊 | 功能 |
|------|------|
| `import_bus` | 自動吸入相鄰容器物品到網路 |
| `export_bus` | 自動從網路輸出物品到相鄰容器 |

---

## 五、Terminal 三個等級

三個等級是**不同方塊/物品**（參考 RS：Grid / Crafting Grid 是不同方塊），沒有頁籤切換，T2/T3 的配方面板永久顯示。

| 等級 | 方塊 | 功能 |
|------|------|------|
| Tier 1 | `storage_terminal` | 網路物品格（可取可存） |
| Tier 2 | `crafting_terminal` | + 配方面板（點物品顯示配方、一鍵合成） |
| Tier 3 | `remote_terminal` | 功能同 T2，但是手持物品，遠端開 GUI |

合成配方：`storage_terminal`（方塊）→ `crafting_terminal`（方塊）→ `remote_terminal`（物品）

---

### 5a. Storage Terminal GUI（Tier 1）

```
┌────────────────────────────────────────────────────────┐
│  [🔍 搜尋__________]              [排序 ▼] [檢視 ▼]   │
├────────────────────────────────────────────────────────┤
│  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┐                        │
│  │  │  │  │  │  │  │  │  │  │                        │
│  ├──┼──┼──┼──┼──┼──┼──┼──┼──┤  ← 網路物品 icon grid  │
│  │  │  │  │  │  │  │  │  │  │  （可取可存，可滾動）    │
│  └──┴──┴──┴──┴──┴──┴──┴──┴──┘                        │
├────────────────────────────────────────────────────────┤
│                     玩家背包                            │
└────────────────────────────────────────────────────────┘
```

| 操作 | 行為 |
|------|------|
| 左鍵點網路物品 | 取出一疊到背包 |
| 右鍵點網路物品 | 取出一半到背包 |
| Shift+左鍵點網路物品 | 取出全部到背包 |
| Shift+左鍵點背包物品 | 該格物品存入網路 |
| 滑鼠拖曳背包物品到網路格 | 存入手持物品 |

---

### 5b. Crafting Terminal GUI（Tier 2）

和 T1 佈局完全相同，但物品格下方多了**配方詳情面板**，以及搜尋列多了 `[只顯示可合成 ☐]` 按鈕。

```
┌────────────────────────────────────────────────────────┐
│  [🔍 搜尋__________]  [排序] [過濾] [只顯示可合成 ☐]  │
├────────────────────────────────────────────────────────┤
│  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┐                        │
│  │  │  │  │  │  │  │  │  │  │  ← 網路物品 icon grid  │
│  ├──┼──┼──┼──┼──┼──┼──┼──┼──┤                        │
│  │  │  │  │  │  │  │  │  │  │                        │
│  └──┴──┴──┴──┴──┴──┴──┴──┴──┘                        │
├────────────────────────────────────────────────────────┤
│  配方詳情                                              │
│  🔥 鐵錠（熔煉）                                       │
│  消耗：200 smelt + 200 fuel    能量池：✅ 足夠         │
│  材料：✅ 生鐵礦 × 1            配方 1/2    [◀][▶]    │
│  [×1] [×8] [×64] [自訂]                              │
├────────────────────────────────────────────────────────┤
│                     玩家背包                            │
└────────────────────────────────────────────────────────┘
```

| 操作 | 行為 |
|------|------|
| 點選網路物品 | 選取該物品，下方面板顯示其配方詳情 |
| `[◀][▶]` | 同一產物的不同配方切換 |
| `[×1] [×8] [×64] [自訂]` | 合成指定數量，消耗材料 + CE |
| `[只顯示可合成 ☐]` | 篩選物品格只顯示有配方的物品 |
| `[背包開關 ☐]` | 開啟後合成材料也計入玩家背包（優先扣網路） |

### 同物品多配方：Compact / Expanded 切換

配方列表頂部有 `[Compact ⇄ Expanded]` 切換：

- **Compact 模式（預設）**：每個產物只顯示一個最優配方（優先 0 CE > 低 CE）
- **Expanded 模式**：每個產物的所有配方分開顯示
- 避免大量配方展開時畫面混亂

### 5c. 遠端存取（Tier 3 專屬）

`remote_terminal` 是純物品，永遠不放置到世界中。

```
綁定流程：
  手持 remote_terminal，Shift+右鍵點擊 storage_core
  → 物品記錄網路 UUID + 維度，tooltip 顯示已綁定的網路名稱

使用流程：
  手持已綁定的 remote_terminal，右鍵
  → 開啟完整 GUI（與 crafting_terminal 相同）
```

- 跨維度可用，但 Core 所在區塊需已載入
- 未綁定時右鍵無反應，tooltip 顯示「未綁定」
- 可以合成多個分別綁定同一網路（多人共用）

---

## 六、網路連接邏輯

```
BFS 從 storage_core 向六個方向掃描：

  [unit][unit][unit]
     ↑
  [core] — [unit]
     ↓
  [terminal]

放置/破壞任何網路方塊時觸發重新掃描。
沒有 core 的連通群組不構成網路（所有功能停用）。

效能：Unit 沒有 BlockEntity，BFS 只做 getBlockState() 檢查，開銷極低。
```

---

## 七、技術規劃

### 7.1 開發環境

| 項目 | 內容 |
|------|------|
| 平台 | NeoForge（Minecraft 1.21.1） |
| 語言 | Java 21 |
| 配方瀏覽 | **EMI**（可選整合，`dev.emi:emi-neoforge:1.1.24+1.21.1:api`；提供 `StandardRecipeHandler` 配方轉移 + `Comparison` NBT 比對） |
| 說明書 | **Patchouli**（遊戲內指南書 library，`vazkii.patchouli:Patchouli:1.21.1-93-NEOFORGE`） |
| 能量系統 | 自訂，不用 Forge Energy（合成能量非通用科技電力） |
| UI | 全手刻（`AbstractContainerScreen` + `GuiGraphics`），無現成 UI library |
| 資料結構 | FastUtil（內建於 Minecraft，primitive collection） |
| 網路效能 | 自訂 BFS，純 `getBlockState()` 檢查，無 BlockEntity 開銷 |

### 7.2 資料結構（參考 AE2 儲存效能設計）

#### ItemKey：緊湊、不可變、NBT 感知的物品身分

```java
// 參考 AE2 的 AEItemKey 設計
final class ItemKey {
    private final ItemStack stack;  // 防禦性拷貝，永不變更
    private final int hashCode;     // 建構時預先計算，永不重算

    private ItemKey(ItemStack stack) {
        this.stack = stack.copy();  // 防禦性拷貝
        this.hashCode = ItemStack.hashItemAndComponents(stack);
    }

    static ItemKey of(ItemStack stack);  // 工廠方法，含快取

    Item getPrimaryKey();       // 回傳 stack.getItem()，用於 Tier-1 索引
    ItemStack toStack(long count); // 轉回真實 ItemStack

    @Override
    public boolean equals(Object o) {
        // 先比 hashCode（fail-fast），再比 ItemStack.isSameItemSameComponents()
    }
}
```

#### 兩層索引（Two-Tier Indexing）

參考 AE2 `KeyCounter` 設計，解決大數量物品的 O(1) 查詢：

```java
// Tier 1: 以 Item 為鍵（忽略 NBT），identity hash map
Map<Item, VariantStore> primaryIndex;

// Tier 2: 以完整 ItemKey 為鍵（含 NBT），記錄數量
class VariantStore {
    ItemKey2LongMap map;  // FastUtil Object2LongOpenHashMap<ItemKey>
}
```

- **非耐久物品**（方塊、錠、材料等佔大多數）：Tier 1 直接 O(1) 查詢，Tier 2 只有一個 entry
- **耐久物品**（劍、工具）：多 variant 共用同一 primary key，各自有不同 ItemKey
- **總種類數檢查**：`primaryIndex.size()` = 已使用的種類槽數

#### 儲存核心資料

```java
// 能量類型：統一結構，差異在來源（自動累積 vs 燃料補充）
enum EnergyType {
    SMELTING_ENERGY(true, 1),    // autoFill, tickRate
    BLASTING_ENERGY(true, 1),
    SMOKING_ENERGY(true, 1),
    CAMPFIRE_ENERGY(true, 1),
    BREW_ENERGY(true, 1),
    FURNACE_FUEL(false, 0),      // 燃料補充
    BLAZE_FUEL(false, 0),
    BOTTLE_FUEL(false, 0);

    final boolean autoFill;
    final int tickRate;
}

// RecipeType → 能量消耗
record EnergyCost(EnergyType process, long processAmount,
                  EnergyType fuel, long fuelAmount) {}

class StorageCoreBlockEntity extends BlockEntity {
    // 雙層庫存索引
    Map<Item, VariantStore> primaryIndex;  // Tier 1: Item → 所有 variant
    int totalTypeSlots;                    // 種類上限（BFS 加總）

    // 能量池
    Map<EnergyType, Long> energy;          // 統一能量池，無上限

    // Fuel value 查表
    static Map<Item, List<FuelEntry>> FUEL_TABLE;  // Item → 哪些池 + 每個給多少

    // 快取層（參考 AE2 InventoryCache）
    Map<ItemKey, Long> cachedDisplay;       // GUI 顯示用快取，異動時重建

    @Override
    void serverTick() {
        for (EnergyType type : EnergyType.values()) {
            if (type.autoFill) energy.merge(type, (long)type.tickRate, Long::sum);
        }
    }

    long insert(ItemStack stack, boolean simulate) { ... }
    long extract(ItemKey key, long amount, boolean simulate) { ... }
    boolean consumeEnergy(EnergyCost cost, long multiplier) { ... }
    void rebuildNetwork(Level level) { ... }
    void addFuel(ItemStack stack, EnergyType targetPool) { ... }
}

class VariantStore {
    Object2LongOpenHashMap<ItemKey> items = new Object2LongOpenHashMap<>();
    long get(ItemKey key) { return items.getLong(key); }
    void add(ItemKey key, long amount) { items.addTo(key, amount); }
}
```

#### 關鍵效能策略

| 策略 | 來源 | 說明 |
|------|------|------|
| 兩層索引 | AE2 KeyCounter | Item 層 identity hash + ItemKey 層 component hash |
| 預先計算 hash | AE2 AEItemKey | ItemKey 建構時 hashCode 就算好 |
| 不可變物件 | AE2 | ItemKey final，內部 stack 防禦性拷貝 |
| 顯示快取 | AE2 InventoryCache | GUI 用的物品列表緩存，只在異動時重建 |
| long 計數 | AE2 MEStorage | 數量用 long 而非 int |
| simulate/modulate | AE2 | insert/extract 先 simulate 再 modulate |

### 7.3 合成流程

```
玩家點「合成 ×N」
  → 查詢配方，確認合成類型
  → 計算所需材料總量
  → 若 [背包開關] 開啟：材料來源 = 網路庫存 + 玩家背包（優先扣網路）
  → 若 [背包開關] 關閉：材料來源 = 網路庫存
  → 檢查材料是否足夠
  → 若為爐系配方：檢查工作站能量池 + fuel_energy 是否足夠
  → 若為即時配方（工作台/切石機/鍛造台）：不需要能量
  → 從各來源扣除材料 × N
  → 從能量池扣除 CE × N（線性）
  → 燃料配方額外扣除 fuelEnergy × N
  → 產物放入網路庫存（若種類槽全滿，退回玩家背包）
```

---

## 八、開發里程碑

| 階段 | 內容 |
|------|------|
| M1 | 方塊 & BlockEntity 註冊、模型、材質 |
| M2 | 網路 BFS 掃描、Core ↔ Unit 連通邏輯 |
| M3 | Storage Unit 六個 Tier 方塊（獨立方塊、不可逆、並聯不限） |
| M4 | `storage_terminal`（Tier 1）：物品格 GUI、取出/存入操作 |
| M5 | 能量池系統（EnergyType 定義、自動累積、燃料轉換、FuelValue 對照表、RecipeType 映射、NBT 持久化） |
| M6 | `crafting_terminal`（Tier 2）：配方面板、一鍵合成 |
| M7 | 即時合成（工作台、切石機、鍛造台） |
| M8 | 熔爐類配方整合（smelting/blasting/smoking/campfire + fuelEnergy 消耗） |
| M9 | 同物品多配方（Compact/Expanded 切換、`[◀][▶]` 配方切換） |
| M10 | `remote_terminal`（Tier 3 物品）：Shift+右鍵綁定、右鍵遠端 GUI |
| M11 | Import/Export Bus 自動化、NBT 物品顯示優化、背包開關 |
| M12 | Patchouli 遊戲內指南書（資源已加入且 content 路徑已修;Prism dev 0.1.3 log 已載入 17 jsons,實機開書可看到分類與說明內容;後續僅剩文案擴充/修整） |
