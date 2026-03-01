# EcoBrain (生态脑) - 核心技术架构与维护手册

> **致未来的维护者（你自己）：**
> EcoBrain 并非传统的“死板商店”，而是一个融合了 **DeFi 去中心化金融 (vAMM)** 与 **深度强化学习 (DRL)** 的自适应虚拟微观经济体。
> 任何对核心交易类、AI 训练类或并发控制的修改，必须严格遵循本文档的约束，否则将导致毁灭性的刷钱漏洞或服务器 TPS 暴跌！

---

## 0. 硬性前置依赖 (Hard Dependencies)
本插件的经济流转必须依赖 **Vault** 及其底层经济核心（如 EssentialsX / CMI 等）。
- **启动检查**：插件 `onEnable()` 阶段会强制检测 Vault。若未检测到，会抛出 `Severe` 级警告并主动 `disablePlugin()`，**严禁带病运行**。

---

## 1. 核心金融模型：vAMM（虚拟自动做市商）与滑点

传统商店是“挂单交易”，EcoBrain 是“资金池交易”。为了防刷且符合数学逻辑，这里使用了极度硬核的 **vAMM (Virtual AMM) 物理与虚拟分离架构**。

### 1.1 联合曲线定价公式
基础的瞬时价格计算依赖反比例恒定乘积变种：
$$ P_{current} = BasePrice \times \left( \frac{TargetInventory}{CurrentInventory} \right)^k $$
- `BasePrice`：基准价（由 AI 或初始 IPO 决定）。
- `TargetInventory`：理想库存平衡点（锚点）。
- `CurrentInventory`：**虚拟数学库存**（参与计算，绝不等于 0）。
- `k`：弹性系数（曲线陡峭度）。

### 1.2 物理库存与虚拟库存的隔离 (The vAMM Architecture)
**【历史惨痛教训】**：曾发生过玩家卖 1 个沙子，系统却因注入了 320 个初始库存导致玩家可以反向买出 320 个沙子的“无中生有”恶性 Bug。因此，必须将数学计算和物理交割分离：
- **`current_inventory` (虚拟数学池)**：专门用于套入上述公式算价格。
- **`physical_stock` (物理实体库)**：记录系统真正持有的玩家卖给它的物品数量。
- **交易法则**：
  - **Sell（卖给系统）**：`current_inventory` += N，且 `physical_stock` += N。
  - **Buy（从系统买）**：**必须优先校验 `physical_stock >= N`**，若物理库存不足直接拒绝交易！若充足，则两者同时 -= N。

### 1.3 离散迭代滑点计算 (Slippage)
绝对禁止使用 `总价 = P_current * 数量`！必须使用迭代累加（或积分），模拟每成交 1 个物品导致的库存变化连环跌价/涨价。
- **批量出售逻辑**： `Sum(i=1..N) [ BasePrice * (Target / (Current + i))^k ]`

---

## 2. 物品唯一身份识别 (Identity & NBT)

EcoBrain **抛弃了 `Material` 枚举**，实现了对全 NBT/MythicMobs 自定义物品的完美支持。

- **序列化机制**：利用 `BukkitObjectOutputStream` 将玩家手中的 `ItemStack` 完全序列化为 `Base64` 字符串。
- **哈希索引 (极速检索)**：Base64 字符串过长，直接做 SQLite 主键会导致查询极慢。系统会对 Base64 进行 **SHA-256 哈希计算**，生成唯一的 64 位字符串 `item_hash`，作为所有数据库表的核心主键 (Primary Key)。

---

## 3. IPO 智能冷启动与初始流动性

本插件**无 `items.yml`**，所有物品档案均由玩家首次交易触发建档。

**【初始流动性陷阱 (Initial Liquidity Trap) 防御】**：
如果未知物品首次建档时虚拟库存为 1，会导致价格暴涨数百倍（触发 16000 金币买泥土漏洞）。
- **IPO 正确流转**：当检测到未知的 `item_hash` 时：
  1. 强行设定 `BasePrice` = config 的拓荒价。
  2. **注入虚拟初始流动性**：初始化 `current_inventory = target_inventory`（让比例为 1:1，维持原始基准价）。
  3. 真实物理库存初始化：`physical_stock = 0`。
  4. 走正常的滑点 Sell 流程，把玩家卖的 N 个物品累加进去。

---

## 4. 线程模型与原子性交易 (Concurrency & Atomicity)

**【极度危险警告】**：任何交易流转如果顺序错乱，必出刷钱/吞物 Bug。
原则：**重计算与 IO 走异步，Bukkit 背包与 Vault 交互走主线程同步。**

一次完整的交易必须严格遵守以下 **5 步原子性顺序**：
1. **[Async]** 数学计算：查询 SQLite 获取当前状态，并根据 AMM 计算出精准的总价/滑点。
2. **[Sync]** 验证前置：返回主线程（Server Thread），检查玩家背包空间/物品是否还在，或 Vault 余额是否足够。
3. **[Sync]** 实际扣除：扣除玩家物品 / 扣除 Vault 余额（**必须获取扣除成功的 boolean 返回值，否则终止抛错**）。
4. **[Sync]** 实际给予：打款到 Vault / 发放 ItemStack 到背包。
5. **[Async]** 落库固化：异步将更新后的 `current_inventory` 与 `physical_stock` 写入 SQLite。

---

## 5. DRL 嵌入式人工智能引擎 (The AI Core)

为了实现单文件交付并避免阻塞服务器，AI 模型被硬编码为纯 Java 轻量级实现。

- **状态空间 (State)**：特征包含特定物品库存偏离度 `(Target/Current)`、流通率（交易频次）、全服 M0 货币增发量（防通胀）。特征均归一化到 `[-1, 1]`。
- **动作空间 (Action)**：多层感知机 (MLP) 的输出，用于调整对应物品的 `base_price`（例如 ±3%）和 `k_factor`。
- **奖励函数 (Reward)**：`R = (w1 * 成交量) - (w2 * 通胀率差值) - (w3 * 库存严重失衡惩罚)`。
- **【极其重要的持久化机制】**：AI 训练的权重矩阵（Weights & Biases）**必须序列化保存在本地**（如 `plugins/EcoBrain/ai_weights.json` 或专属表中）。每次启动必须 `Load`，每次训练结束必须立刻 `Save`。一旦丢失，AI 将被“洗脑”重置！

---

## 6. GUI 防刷机制 (Anti-Exploit)

54 格批量出售舱 (`BulkSellGUI`) 的安全设计底线：
1. **隔离区划**：`0~44` 为操作区，`45~53` 包含确认/取消按键与占位玻璃板。禁止任何拖拽 (`InventoryDragEvent`) 和点击 (`InventoryClickEvent`) 将物品放入底栏。
2. **退回机制**：监听 `InventoryCloseEvent`。如果玩家强行按 ESC 关掉界面，必须将 `0~44` 的物品完整安全退回玩家背包，满包则掉落脚下。
3. **会话幂等 (Idempotency)**：点击【确认出售】时，立刻给该玩家生成一个 `UUID Token` 或上锁，阻断网速卡顿导致的连点重复结算。

---

## 7. 数据库架构 (SQLite)

核心库位于 `plugins/EcoBrain/ecobrain.db`。

**表：`ecobrain_items`**
- `item_hash` (PK, String) - SHA-256 唯一标志
- `item_base64` (TEXT) - 用于发货时反序列化
- `base_price` (DOUBLE) - 基准价
- `k_factor` (DOUBLE) - 陡峭度
- `target_inventory` (INT) - 虚拟库存锚点
- `current_inventory` (INT) - 虚拟池库存 (用于算价)
- `physical_stock` (INT) - **物理实体库存 (用于发货)**

---

## 8. 指令与管理员风控抓手

主命令：`/ecobrain`（别名 `/eb`）

- `sell <数量>` / `buy <数量>` ：玩家常规指令。

> **关于自动熔断 (Circuit Breaker)**：系统熔断通常只拦截【买入】（因为怕库存抽干），默认放行【卖出】以允许玩家补库存恢复市场流动性。

---

## 9. 维护与重构指北

1. **热更新的边界**：`/eb reload` 会重载参数并重启 AI 调度器任务，但 AI 经验回放池（Replay Buffer）容量和 SQLite 连接池变动需整服重启。
2. **切勿瞎改 K 值**：如果物品价格极度失真，请手动调整 `k_factor` 趋近 `0.6 ~ 1.0`，并检查 `physical_stock` 是否严重枯竭。