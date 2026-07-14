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
Storage Core 透過 Fuel 頁中實際安裝的處理機器累積「工作站能量」，把原本要等待的處理時間預先存起來，合成時一次消費。沒有安裝機器就不會憑空產生能量。玩家另可投入燃料物品補充對應的燃料能量池。

### 能量池：統一模型，差異只在來源

所有能量池結構相同（`Map<EnergyType, Long>`，無上限），差異在**來源**：

| 能量池 | 來源 | 用途 |
|--------|------|------|
| `smelting_energy` | Fuel 頁每台 Furnace +1/tick | 熔爐配方 |
| `blasting_energy` | Fuel 頁每台 Blast Furnace +1/tick | 高爐配方 |
| `smoking_energy` | Fuel 頁每台 Smoker +1/tick | 煙燻爐配方 |
| `campfire_energy` | Fuel 頁每台 Campfire +1/tick | 營火配方 |
| `brew_energy` | Fuel 頁每台 Brewing Stand +1/tick | 未來釀造 consumer 預留（目前不可執行） |
| `furnace_fuel` | 任何 NeoForge/vanilla runtime burn time > 0 的燃料 | 所有爐系配方額外消耗；對玩家顯示 **Fuel** |
| `blaze_fuel` | 塞烈焰粉/烈焰桿 | 未來釀造配方額外消耗；現階段可先儲存 |
| `bottle_fuel` | 塞玻璃瓶/水瓶 | 未來釀造輸出容器；現階段可先儲存 |

工作台 shaped/shapeless、切石機 stonecutting、exact `SmithingTransformRecipe` 與 deterministic vanilla default-state axe strip/scrape/wax-off 都是即時合成，不需要能量，但必須在 Core 安裝 matching Crafting Table/Stonecutter/Smithing Table/axe。Smithing Transform 直接用 recipe 的 template/base/addition predicates 比對實際完整 `ItemStack` components，再由 joint allocation 呼叫 `assemble`，保留 exact base components；Smithing Trim、dynamic/special/context-dependent recipe 與任意 mod block tool hook 仍 fail closed。

### Recipe → 消耗的能量池

爐系成本必須由 server 目前 `RecipeManager` 中的具體 `AbstractCookingRecipe#getCookingTime()` 取得，不再按 `RecipeType` 手寫 200/100/600。Smelting / Blasting / Smoking / Campfire 各自消耗同 tick 數的對應 machine process pool 與 `furnace_fuel`。非正值、stale 或 unsupported recipe 一律 fail closed；preview 與 execute 共用同一 resolver。

工作台 shaped/shapeless、stonecutting、Smithing Transform 與 axe transformation 都不耗能；axe 每 craft 固定耗一點 raw durability。Brewing 尚無 production consumer，不以預留的固定數字冒充已完成支援。

### Fuel Value

`furnace_fuel` 的唯一 authoritative value 是 server commit 當下的 `ItemStack#getBurnTime(null)`；大於 0 就接受並按每件實際 burn ticks 加值。因此原木、datapack/NeoForge data-map 燃料、modded fuel 與 stack-sensitive override 不需要 Magic Storage 白名單。容器 remainder、overflow 與 conflict 仍沿用 simulate-then-commit/fail-closed 契約。Brew/Bottle 是尚未接 production brewing 的 explicit reserved mappings，不可反過來覆蓋有效的 runtime Fuel value。

### 燃料塞入方式

Crafting Terminal（含綁定後的 Remote Terminal）有獨立 **Fuel 頁**：
- Installed Stations 有九格：Furnace / Blast Furnace / Smoker / Campfire / Brewing Stand 會按 stack `N` 每 tick 增加對應池 `N`；Crafting Table / Stonecutter / Smithing Table / axe 只解鎖對應 recipe/tool action，不產能。equipment 由 Core server-side 持久化，不占一般種類容量。
- 先選 `Auto` / `Fuel` / `Brew` / `Bottle`，再把燃料放進專用輸入槽；Fuel 頁 Shift+背包燃料也走同一 server-authoritative 轉換路徑。
- `Auto` 是每次開啟 menu 的預設。候選池依「可供應該池的 FuelTable distinct item 種類越少，優先度越高」排序；再以目前累計量較少、`EnergyType` enum 順序作穩定 tie-break。烈焰桿因此預設補 `blaze_fuel`。
- Fuel 頁由 `MachineEnergyTable.entries()` 與 ordered fuel-target descriptors 產生 tiles；Installed Stations 使用兩個 flow rows，wide machine/reserve panels 鋪滿 inner width，右下 `fuelControlPanel` 與玩家 inventory 同高並容納 target selector/Fuel input。每一頁的可見 descriptors 會平均鋪滿 panel，超過 capacity 時各自滾動分頁。左 rail 在 Fuel 頁只保留 Storage/Craftable/Fuel；單一 shared `TerminalCycleButton` 顯示目前 Auto/Fuel/Brew/Bottle target，left-click/scroll-down 往下一個、right-click/scroll-up 往上一個，server 只接 Auto/exact target IDs。若 target 種類日後大幅增加，同一 selector rectangle 才升級為可捲動選單，不把 targets 塞回 rail。每種 energy 以對應 machine/fuel item 作代表物，Fuel 頁顯示 server-synced stored/max type capacity；`furnace_fuel` 對玩家只顯示 **Fuel**，內部 ID 不遷移。0.1.15 尚未暴露第三方 runtime registration；未來 API 必須 server-owned 並同步固定 descriptor/menu parity。
- Storage 頁不轉換燃料；Shift+燃料和一般物品一樣存入網路。舊的即時 popup 不保留。
- explicit target 不相容、non-fuel、overflow 或 stale request 全部 fail closed；container remainder 回專用槽/原玩家槽，溢出再回背包或顯式掉出。

Crafting menu 兩端固定 137 slots（81 display + 36 player + 1 Fuel input + 9 equipment + 10 metadata）；Storage base data 固定 11，Crafting/Fuel 再加 83，總計 94。欄位同步七個 recipe/page/view values、八個 energy `long`、九個 ingredient/tool available `long`，以及 process/Fuel required `long`；representative/required count 走 hidden slot stack sync。目前 shared terminal/EMI/station/Axe revision 見 `docs/superpowers/specs/2026-07-14-terminal-platform-emi-recipe-axe-design.md` 與同名 implementation plan。

### 能量不足顯示

Crafting Terminal 配方詳情區採 input → operation → output + `Available / Required`：
- 每個 item/process/Fuel/tool resource 顯示目前 available 與單次 required。
- 整體狀態顯示 `Ready ×N` 或 Missing，固定數量按鈕不足時變暗。
- `Max` 每次由 server fresh preview 算目前合法最大值，不信任 client 顯示數；若資源上限無法完整交付，會再找出當下可完整交付的最大值。binary search 與 Core extraction/output 都使用 long-safe arithmetic/chunking，不會在 `Integer.MAX_VALUE` 邊界溢位或把大額 reservation 當成單一 `ItemStack`。

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

Crafting Terminal 由左側 rail 的第一群組切換 **Storage / Craftable / Fuel** 三頁，並用明顯間隔和 item-page 排序、搜尋 controls 分開。Storage 只列已儲存物，Craftable 只列目前可合成且不可抽取的 synthetic outputs；兩者都以 server-owned `TerminalDisplayStack` exact `long` metadata 顯示目前 Core 庫存，Craftable 零庫存 icon 仍存在但不顯示假數量。Name/Quantity/Mod/ID 走同一 deterministic comparator，display marker 不得進入 `ItemKey` 或真實 output。Fuel rail 只顯示三個 page tabs，machine/reserve tiles 依每頁可見 descriptor counts 平均鋪滿 bounds 並處理 overflow。Fuel target 由 Energy Reserves header 的 single current-value shared cycle control 選擇，沒有語意混雜的 previous/next action buttons。Fuel/Brew/Bottle reserve 分別用 Coal/Blaze Rod/Glass Bottle，Fuel 頁顯示 stored/max types。不同 component 的物品永遠維持完整 `ItemKey` 身分。`TerminalProfile` 組合 Storage reduced/Crafting full capabilities，所有 screens 只走 `TerminalLayout.forProfile(...)`；`StorageTerminalScreen` shared shell 是 common controls 唯一 owner，Crafting 不得 hide-and-recreate。Rail controls 全部是 18×18 hitbox/16×16 item-or-atlas icons，cycle input 一律 left-next/right-previous/wheel-down-next/wheel-up-previous。Grid amount 採 compact units，畫面只計算一個由最寬允許字串與 16px bound 得出的 fixed scale，每個值都用同一 scale 留在自己的 cell 內 right-align。所有動態 slots 委派 constructor 時保存的 original semantic slot，repeated `init()` 不會堆 wrapper。Fuel 常駐畫面不顯示 rate 公式，精確資料由 hover tooltip 發現，status text 無 shadow。合成列為 live-disabled `×1 / ×8 / ×64 / Max`，Max 由 server 重新計算當下合法最大值且不受 9,999 preview sync cap 截斷。

```
┌────────────────────────────────────────────────────────┐
│  [I][F]  [🔍 搜尋__________]                            │
├────────────────────────────────────────────────────────┤
│  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┐                        │
│  │  │  │  │  │  │  │  │  │  │  ← 網路物品 icon grid  │
│  ├──┼──┼──┼──┼──┼──┼──┼──┼──┤                        │
│  │  │  │  │  │  │  │  │  │  │                        │
│  └──┴──┴──┴──┴──┴──┴──┴──┴──┘                        │
├────────────────────────────────────────────────────────┤
│  配方詳情（Ready + Available / Required 資源表）        │
│  🔥 鐵錠（熔煉）                                       │
│  消耗：recipe cookingtime 的 process + Fuel           │
│  材料：✅ 生鐵礦 × 1            配方 1/2    [◀][▶]    │
│  [×1] [×8] [×64] [Max]                               │
├────────────────────────────────────────────────────────┤
│                     玩家背包                            │
└────────────────────────────────────────────────────────┘
```

| 操作 | 行為 |
|------|------|
| 點選網路物品 | 選取該物品，下方面板顯示其配方詳情 |
| `[◀][▶]` | 同一產物的不同配方切換 |
| `[×1] [×8] [×64] [Max]` | 精確按鈕不足量時變暗；Max 由 server 重新 preview 後合成當下最大合法量 |
| 左側 `Storage / Craftable / Fuel` | 明確分頁；Craftable 的 zero-storage synthetic entries 可選 recipe 但不可抽取 |
| 左側玩家背包圖示（完整 tooltip） | 開啟後合成材料也計入玩家可見的 36 格主背包+快捷列（不含 armor/offhand，優先扣網路） |
| 左側 Fuel 頁按鈕 | 切到 Fuel 頁；rail 只保留三頁籤，再用 Energy Reserves header 的 Fuel Target selector 選 Auto/Fuel/Brew/Bottle、安裝機器、放入或 Shift+燃料，並以 panel paging 查看目前所有 energy descriptors |

### 同物品多配方與資源身分

- 每個 `ItemKey` component variant 永遠各自顯示，不會因畫面大小或使用者模式而合併。
- 同一產物的不同配方仍用 `[<][>]` 切換，selection 綁 exact recipe/output identity。
- 右側資源表由 server 同步每個 ingredient predicate 與 process/Fuel 的現有量、單次需求；整體 `Ready ×N` 仍以 joint reservation 為最終真值。

### 5c. 遠端存取（Tier 3 專屬）

`remote_terminal` 是純物品，永遠不放置到世界中。

```
綁定流程：
  手持 remote_terminal，Shift+右鍵點擊 storage_core
  → 物品記錄 Core 的持久 UUID + 座標 + 維度，tooltip 顯示已綁定座標

使用流程：
  手持已綁定的 remote_terminal，右鍵
  → 開啟完整 GUI（與 crafting_terminal 相同）
```

- 只限同維度，且 Core 所在區塊需已載入；選單不會自行載入區塊
- 綁定的是該 Core 的持久 UUID；同座標換成另一顆 Core 後必須重新綁定
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

放置 callback 合併到 next-tick topology pass；單一安全成長走 bounded incremental add，破壞、批次或不確定拓樸才走 bounded full rebuild。
沒有 core 的連通群組不構成網路（所有功能停用）。

Terminal/Import Bus/Export Bus cache access path，但每次操作前驗證路徑連續、每格 chunk 目前已載入且仍是 network block；失效時只尋找 alternate loaded path，不得為驗證連線載入 chunk。

效能：Unit 沒有 BlockEntity，BFS 只做 `getBlockState()` 檢查；所有 BFS/full rebuild 都有深度與方塊數上限。
```

---

## 七、技術規劃

### 7.1 開發環境

| 項目 | 內容 |
|------|------|
| 平台 | NeoForge（Minecraft 1.21.1） |
| 語言 | Java 21 |
| 配方瀏覽 | **EMI**（可選整合，`dev.emi:emi-neoforge:1.1.24+1.21.1:api`；只接受 terminal 支援的 exact backing recipe，送 `{recipeId,amount,destination}`；NONE 選取 preview，CURSOR/INVENTORY 執行單層 server-authoritative magic craft，不做遞迴 pattern tree） |
| 說明書 | **Patchouli**（遊戲內指南書 library，`vazkii.patchouli:Patchouli:1.21.1-93-NEOFORGE`） |
| 能量系統 | 自訂，不用 Forge Energy（合成能量非通用科技電力） |
| UI | 全手刻（`AbstractContainerScreen` + `GuiGraphics`），無現成 UI library |
| 資料結構 | FastUtil（內建於 Minecraft，primitive collection） |
| 網路效能 | 自訂 BFS，純 `getBlockState()` 檢查，無 BlockEntity 開銷 |

### 7.2 資料結構（參考 AE2 儲存效能設計）

#### ItemKey：緊湊、不可變、NBT 感知的物品身分

```java
// 參考 AE2 的不可變 resource key 設計
record ItemKey(Item item, DataComponentMap components) {
    static ItemKey of(ItemStack stack) {
        // 必須 build 新 DataComponentMap；不可保留來源 stack 的 mutable patch view
        return new ItemKey(stack.getItem(), snapshot(stack.getComponents()));
    }

    ItemStack toStack(int count); // 套回完整 component snapshot
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
// 能量類型：process pools 由已安裝機器產生，fuel pools 由物品轉換
enum EnergyType {
    SMELTING_ENERGY(true), BLASTING_ENERGY(true), SMOKING_ENERGY(true),
    CAMPFIRE_ENERGY(true), BREW_ENERGY(true),
    FURNACE_FUEL(false), BLAZE_FUEL(false), BOTTLE_FUEL(false);

    final boolean machineGenerated;
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
    Container machines;                    // 九格 Core-owned persistent station/tool equipment

    // Fuel value 查表
    static Map<Item, List<FuelEntry>> FUEL_TABLE;  // Item → 哪些池 + 每個給多少

    // 快取層（參考 AE2 InventoryCache）
    Map<ItemKey, Long> cachedDisplay;       // GUI 顯示用快取，異動時重建

    @Override
    void serverTick() {
        if (conflicted) return;
        for (int slot = 0; slot < MachineEnergyTable.size(); slot++) {
            var mapping = MachineEnergyTable.get(slot);
            var stack = machines.getItem(slot);
            if (mapping.generatesEnergy() && mapping.accepts(stack))
                saturatingAdd(mapping.energyType(), stack.getCount());
        }
    }

    long insertItem(ItemStack stack, Action action, Actor actor) { ... }
    long extractItem(ItemKey key, long amount, Action action, Actor actor) { ... }
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
| simulate/execute | RS2/AE2 | `Action.SIMULATE` 不 mutation/event；`Action.EXECUTE` 才 commit + `setChanged()` |

### 7.3 合成流程

```
玩家點「合成 ×N」
  → 依 recipe id 從目前 server RecipeManager（或 exact synthetic axe catalog）重取配方，確認 exact static contract + output identity
  → 重新驗證 matching Crafting Table / cooking machine / Stonecutter / Smithing Table / axe 已安裝
  → 對本次 preview/execute 建立一次 Core + 玩家材料 snapshot，保留每個 Ingredient predicate 身分
  → 建立 joint reservation，計算所需材料總量與可做上限
  → 若 [背包開關] 開啟：材料來源 = 網路庫存 + 玩家可見 36 格（優先扣網路；不含 armor/offhand）
  → 若 [背包開關] 關閉：材料來源 = 網路庫存
  → 檢查材料是否足夠
  → 若為爐系配方：檢查工作站能量池 + fuel_energy 是否足夠
  → 若為即時配方（工作台/切石機/Smithing Transform/axe）：不需要能量；axe 另規劃 raw durability × N
  → 先在扣除 player ingredients 的 36 格 snapshot 放產物/remainders，overflow 規劃回 Core；兩邊無法完整容納就整批 no-op
  → 進入 deferred-listener mutation batch，再重驗並從各 exact source 扣除材料 × N
  → 插入 Core overflow、從能量池扣除 CE/fuelEnergy × N、套用工具耐久與玩家 inventory/cursor
  → 任一步不符即按 exact source rollback；listener callback 只在 batch 結束後送出
  → requested output 永不掉到世界或靜默消失
```

---

## 八、開發里程碑

| 階段 | 內容 |
|------|------|
| M1 | 方塊 & BlockEntity 註冊、模型、材質 |
| M2 | 網路 BFS 掃描、Core ↔ Unit 連通邏輯 |
| M3 | Storage Unit 六個 Tier 方塊（獨立方塊、不可逆、並聯不限） |
| M4 | `storage_terminal`（Tier 1）：物品格 GUI、取出/存入操作 |
| M5 | 能量池系統（EnergyType、Core 持久化機器槽、按安裝數量累積、燃料轉換、RecipeType 映射、NBT） |
| M6 | `crafting_terminal`（Tier 2）：配方面板、一鍵合成 |
| M7 | 即時合成（工作台、切石機、component-preserving Smithing Transform、axe strip/scrape/wax-off）；matching station/tool gates |
| M8 | 熔爐類配方整合（smelting/blasting/smoking/campfire + fuelEnergy 消耗） |
| M9 | 同物品多配方（完整 `ItemKey` variants、exact recipe identity、`[◀][▶]` 配方切換） |
| M10 | `remote_terminal`（Tier 3 物品）：Shift+右鍵綁定、右鍵遠端 GUI |
| M11 | Import/Export Bus 自動化、NBT 物品顯示優化、背包開關 |
| M12 | Patchouli 遊戲內指南書（資源已加入且 content 路徑已修;Prism dev 0.1.3 log 已載入 17 jsons,實機開書可看到分類與說明內容;後續僅剩文案擴充/修整） |
