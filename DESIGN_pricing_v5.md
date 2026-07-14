# EcoBrain 定价引擎重构设计 (v5)

## 目标

用两个业界成熟的原语替换现有"半吊子贝叶斯 + 规则引擎 + 僵尸 AMM"：

1. **鲁棒卡尔曼滤波**（Student-t 观测似然）估计对数公允价值 —— 替换假贝叶斯 + 分阶段规则 + 整个反操纵子系统。
2. **Avellaneda-Stoikov 做市报价**（闭式解）—— 替换手调 spread，且把库存与金库风险统一进报价。

核心诉求：**少特征工程、多原理**。config 参数从 ~40 个降到 ~10 个；magic constant 从 ~30 降到个位数；反操纵从"15 条手写惩罚"变成"似然函数的一个自然性质"。

---

## 一、算法设计

### Layer 1 — 鲁棒卡尔曼滤波（潜在价值估计）

状态：`x` = 对数公允价值，`P` = 其方差。

**时间更新（两次成交之间）**：
```
Δt = now - lastUpdate
P ← P + q · Δt / diversityFactor
```
- `q` = 单位时间过程噪声（config: `process-noise-per-hour`）。没人交易时 P 自然变大 → 不确定性上升。**取消 sigma 下限 clamp**，让它真实呼吸。
- `diversityFactor` = 有效交易者多样性（见下），交易者越单一，越不信任、P 涨得越快。

**测量更新（每笔成交）**：
```
z = log(unitPrice)                      // 观测
R = r0 / max(1, volume)                 // 量越大越可信，噪声越小
ν = z - x                               // 创新(innovation)
S = P + R                               // 创新方差
w = (dof + 1) / (dof + ν²/S)            // Student-t IRLS 权重 ∈ (0,1]
R' = R / w                              // 异常成交 → w→0 → R'→∞ → 基本不更新
K = P / (P + R')                        // 卡尔曼增益
x ← x + K · ν
P ← (1 - K) · P
```

**这一步同时干掉了**：
- 现有 `ingestTradeEvidence` 的假精度加权
- `sigmaLogPrice` 的 `clamp(0.18, 3.0)`
- 整个 `manipulationScore` / `reversalRate` / `top2FlowShare` / `distinctBuyers` 规则体系 —— 洗价/对倒因为创新巨大，`w→0` 被**自动降权**，无需任何一条反操纵规则。
- SELL 在 UNKNOWN 的 `min(mu)` 特判 —— 由似然自然处理。

**诚实边界**：P2S 市场系统是唯一对手方，数据源是系统自己的报价，**循环论证是结构性的**。缓解手段是 `diversityFactor`（有效交易者数的 EWMA）——单人反复刷价时 diversity 低，P 不收敛、报价不敢跟，但这是缓解不是根治。文档里明确写清，不假装"科学发现价值"。

### Layer 2 — Avellaneda-Stoikov 报价

```
σ² = P + volEwma          // 慢状态不确定性 + 快速已实现波动
q_inv  = (physicalStock - targetFloat) / targetFloat      // 库存失衡, 正=超卖压力
q_cash = treasuryShortfall(...)                            // 金库紧张度 ∈ [0,1)
skew   = γ · (invWeight·q_inv - cashWeight·q_cash) · σ²
r      = exp(x - skew)                                     // reservation price
δ      = baseFee + 0.5·γ·σ²                                // half-spread (A-S 简化)
bid    = r · exp(-δ)
ask    = r · exp(+δ)
```
多单滑点：沿库存变化对边际价积分（复用现有 `marginalPrice` 结构，改用新 σ/depth）。

**关键改进**：`q_cash` 让金库吃紧时 **bid 自动走低**（做市商用价格管理风险），而不是现在的"价格挺好却硬拒收"。硬熔断只保留极端兜底（金库为 0 时才拒付）。

---

## 二、数据结构变更

### `DiscoveryState` (record) 瘦身
删除：`stage`, `manipulationScore`, `trustedBuyQty7d`, `trustedSellQty7d`, `distinctBuyers7d`, `distinctSellers7d`, `top2FlowShare24h`, `reversalRate24h`, `trustedFloat`, `liquidityDepth`
改名/新增：
```
itemHash, xLogValue(=mu), pVar(=方差,不再叫sigma), volEwma, diversityEwma, lastTradeAtMillis, updatedAtMillis
```
`stage`/`trustedFloat`/`liquidityDepth` 改为**读时派生**（stage 纯 UI 标签，由 `pVar` 大小映射 UNKNOWN/DISCOVERY/MATURE）。

### `ecobrain_discovery_state` 表
对应增删列（SQLite 3.35+ 支持 `DROP COLUMN`，xerial 3.50 满足）。

### `ecobrain_items` 表
删除列：`base_price`, `k_factor`, `current_inventory`, `target_inventory`。
保留：`item_hash`, `item_base64`, `physical_stock`, `created_at`。

### `ItemMarketRecord` (model)
删除 `basePrice`/`kFactor`/`currentInventory`/`targetInventory` 字段、`withTuning`、`withInventories` 的虚拟库存参数（改为只带 `physicalStock`）。

### `MarketSnapshot` (record)
**保持字段名不变**（GUI lore 模板、CircuitBreaker 依赖），语义重映射：
- `sigmaLogPrice` ← `sqrt(pVar)`
- `manipulationScore` ← 最近窗口的平均鲁棒降权 `1 - w̄`（"异常率"，仍是 0~1，GUI 文案不用改）
- `trustedFloat`/`liquidityDepth` ← 报价时算出的深度
- `stage` ← 派生标签
- `distinctBuyers7d` 等整数字段 → 保留但填 0 或从轻量查询取（GUI 若不显示可后续删）

### 仓储层 (`ItemMarketRepository`)
- 删除：`updateTuning`, `updateTargetInventoryWithProportionalCurrentScaling`, `updateVirtualInventoryOnly`, `queryDiscoveryWindowStats`（3 条昂贵查询，含相关子查询，Layer1 不再需要）, `hasOppositePlayerTradeSince`, `queryPlayerActiveDaysForItem`, `recordAiTuningEvent`
- 新增：`recordTradeObservation` 已有 `recordTrade` 可复用；滤波更新读写走瘦身后的 discovery_state
- `upsertIpo` 简化：不再写 base_price/k/target/current

---

## 三、config.yml 变更

删除整节：`ai.*`（保留空壳或删）、`discovery.*`、`evidence.*`、`anti-manipulation.*`、`market-maker.*` 的大部分。

新增 `pricing:` 节（~10 参数）：
```yaml
pricing:
  gamma: 0.15                      # A-S 风险厌恶
  process-noise-per-hour: 0.02     # 卡尔曼过程噪声 q
  observation-noise-base: 1.0      # r0
  student-t-dof: 4                 # 鲁棒似然自由度(越小越抗异常)
  volatility-half-life-hours: 12   # volEwma 半衰期
  base-fee: 0.05                   # 最小 half-spread
  inventory-risk-weight: 1.0
  treasury-risk-weight: 1.0
  trusted-float-baseline: 16
  min-depth: 16
  max-depth: 2048
  anchor-price: 100                # 冷启动中性锚
  initial-variance: 5.0            # 冷启动 P
```
`PluginSettings` 对应删 `AI/Discovery/Evidence/AntiManipulation/MarketMaker` records，新增 `Pricing` record。

---

## 四、受影响文件清单

| 文件 | 改动 |
|---|---|
| `StatisticalPriceDiscoveryService` | 核心重写：卡尔曼+鲁棒似然+A-S；删 computeStage/manipulation/trust 一族方法 |
| `model/DiscoveryState` | 瘦身字段 |
| `model/ItemMarketRecord` | 删 4 个遗留字段 |
| `model/MarketSnapshot` | 字段语义重映射（名字不变） |
| `model/DiscoveryStage` | 保留（纯 UI 标签） |
| `persistence/ItemMarketRepository` | 删 ~8 个方法，改 upsertIpo/discovery 读写 |
| `persistence/DatabaseManager` | SCHEMA_VERSION→5，建表去列，`migrate4To5`（DROP COLUMN 或重建） |
| `config/PluginSettings` | 删 5 个 record，加 `Pricing` |
| `resources/config.yml` | 删 ~40 参数，加 pricing 节 |
| `service/MarketService` | 适配瘦身后的 record/settle |
| `safety/CircuitBreaker` | 适配 snapshot（midPrice 仍在），逻辑基本不变 |
| `command/AdminCommand` | `inspect` 显示新状态；删 base/k/target 显示 |
| `gui/MarketViewGUI` | lore 占位符适配（{virtual_inventory} 等删或改） |
| `simulator/` | 重写或标记废弃（见下） |

---

## 五、模拟器 (simulator/)

现状：`amm.py` + `env.py` + PPO 训练全部建立在已删除的 AMM 上，训练目的（喂 PPO）已消失。

**建议（二选一，作为独立子任务）**：
- **A. 标记废弃**：留档不动，README 注明"v5 起定价不再依赖离线训练"。最省事。
- **B. 改造为不变量验证器**：重写 `amm.py`→`market_maker.py`，用 pytest 契约测试验证新定价的经济不变量（无套利：buy 后立即 sell 必亏 spread；金库守恒；P 随空闲上升、随成交下降）。更有价值但工作量大。

本次重构默认走 A，B 单独排期。

---

## 六、测试策略

- 保留并改写 `StatisticalPriceDiscoveryServiceTest`：验证卡尔曼收敛、鲁棒降权（注入离群成交，断言 x 几乎不动）、A-S 报价对库存/金库的响应、冷启动。
- 保留 `ItemMarketRepositoryTargetScalingTest`→改为新 schema 的读写测试（或删，因为 target scaling 概念已删）。
- 保留 `BulkSellListenerTest`, `LeaderboardClearTest`, `ItemOperationCoordinatorTest`（不受定价影响）。
- 新增：鲁棒似然单元测试（wash-trade 场景）、A-S skew 单调性测试。
- 每步 `mvn test` + `mvn package` 验证。

---

## 七、分阶段落地（每阶段可独立编译+测试）

1. **Schema + model 瘦身**：DatabaseManager v5、DiscoveryState/ItemMarketRecord/MarketSnapshot 改字段、仓储层增删方法。先让项目编译过、老测试改到能跑。
2. **算法核心**：重写 `StatisticalPriceDiscoveryService`（卡尔曼+鲁棒+A-S），配 `Pricing` config。
3. **接线适配**：MarketService/CircuitBreaker/AdminCommand/GUI 适配新 snapshot 与 record。
4. **config + 文档**：config.yml 重写，注释讲清每个参数的经济含义与循环论证边界。
5. **测试补全**：新单元测试 + 全量回归。
6.（可选，独立）模拟器改造为不变量验证器。

---

## 八、风险与决策点

- **数据迁移**：v5 删列。若在线服有存量数据，`migrate4To5` 用 `ALTER TABLE DROP COLUMN` 原地迁移（保留 item_base64/physical_stock/交易历史），不清库。需在实施时确认目标 SQLite 版本 ≥ 3.35。
- **调参**：新模型行为由 `gamma`/`q`/`dof` 主导，上线前需用历史交易或模拟器扫一遍默认值，避免价格过冲/过钝。
- **GUI 文案**：`manipulationScore`→"异常率"语义变了但字段名不变，中文 lore 若写死"操盘分"需微调文案。
