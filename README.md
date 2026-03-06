# EcoBrain 3.0

EcoBrain 是一个面向 Minecraft 服务器的动态经济插件。它的目标不是“把价格写死”，而是让系统商店根据真实库存、真实交易和真实服务器节奏持续自我修正。

如果把市面上常见的服务器经济系统拆开看，问题往往不是“没有 AI”，而是更底层的约束从一开始就没立住。

当前版本的关键词只有四个：

- **单模型**：全服统一使用一个 ONNX 模型 `ecobrain_value.onnx`
- **零信任 IPO**：未知物品首次卖给系统时一律从 `100.0` 金币起步
- **vAMM 动态定价**：价格跟随库存、滑点和动态印花税变化
- **全局金库守恒**：玩家 BUY 给系统进钱，玩家 SELL 从系统出钱；金库不够就拒绝收购

---

## 0. 市面方案的不足与 EcoBrain 的回答

### 0.1 常见经济插件的几个结构性问题

很多“动态商店”看起来会涨会跌，但本质上仍然有下面这些问题：

- **价格不是被市场发现出来的，而是被管理员写出来的**  
  常见做法是手工配置一个初始价、一个上下浮动区间，再按销量做加减法。这种方法能动，但它并不真正理解“库存稀缺性”和“成交节奏”。

- **新物品上架时没有可靠的价格发现机制**  
  如果系统一开始就允许首个卖家把价格锚在高位，玩家很容易靠首单或小号互倒把系统带偏，后面所有价格都会在错误起点上继续演化。

- **把交易次数当成市场强度，却忽略真实库存和成交金额**  
  有些系统只看“买了几次”“卖了几次”，但不看一笔交易到底有多大，也不看系统手里到底有没有货。结果就是：账面上像在做市场，实际上定价和供给约束已经脱节。

- **无限发钱或者弱资金约束，会让价格信号失真**  
  如果系统可以无限收购、无限付款，那么很多价格并不是市场给出来的，而是插件自己印钱印出来的。长期看，这会把交易行为和真实价值判断搅在一起。

- **训练和线上推理常常不是同一个问题**  
  很多项目离线训练看起来很漂亮，但训练时看到的状态、线上推理时读取的状态、动作的执行方式并不完全一致。最后模型不是学坏了，而是“上线后题目变了”。

### 0.2 EcoBrain 的核心创新点

EcoBrain 的重点不是“给商店套一个 AI”，而是把**价格发现、库存约束、资金守恒、训练闭环**放进同一个系统里。

- **零信任 IPO**  
  新物品第一次进入系统时，不相信任何人的报价，统一从 `100.0` 金币起步。这样做不是为了保守，而是为了切断“首单定价权”。

- **虚拟库存和真实库存分离**  
  `current_inventory` 用来沿着 AMM 曲线定价，`physical_stock` 用来决定能不能真实交付，以及 SELL 风控要不要触发。  
  这让“价格弹性”和“真实有货”不再混成一团。

- **单模型 + 连续 observation，而不是线上 tier 路由**  
  线上不是先给物品贴一个 `low/mid/high` 标签，再切模型或切规则；而是统一交给一个模型，输入同一套 18 维连续状态。  
  这样做的好处是：线上逻辑更简单，训练与部署的契约也更稳定。

- **交易日志驱动的事件回放训练**  
  `exportdata` 导出的不是“旧模型当时想了什么”，而是“服务器真实发生了什么”。  
  离线训练时，PPO 继续在这些真实交易节奏下试错，而不是单纯模仿历史动作。

- **全局金库守恒 + 无活动弱调控**  
  价格系统不仅要会涨跌，还要知道什么时候该闭嘴。  
  如果没有真实交易活动，AI 会把动作按衰减系数缩小，而不是满强度乱调；如果系统金库不足，SELL 会被拒绝。这两条约束一起保证了价格调整必须建立在真实市场证据和真实资金能力上。

如果用一句话概括 EcoBrain 的设计思路，就是：

> 不是让插件“看起来像会调价”，而是让它在真实交易、真实库存、真实资金和统一训练契约下，持续做出可解释的价格修正。

---

## 1. 核心机制

### 1.1 零信任 IPO

任何未被系统登记过的物品，第一次卖给系统时都不会“盲猜高价”，而是固定按 `100.0` 金币起步。

这意味着：

- 新物品不会因为首单盲猜而被套利
- 价值主要由后续真实 **BUY** 行为发现，而不是靠首个 **SELL** 行为“抬出来”

### 1.2 vAMM 动态定价

系统对每个物品维护 3 个关键量：

- `target_inventory`：理想库存
- `current_inventory`：虚拟库存池，用于 AMM 曲线定价
- `physical_stock`：真实库存，用于判断能不能卖给玩家，以及 SELL 风控

当前价公式：

```text
P_current = base_price * (target_inventory / max(1, current_inventory)) ^ k
```

直觉上可以理解为：

- 库存越接近枯竭，价格越高
- 库存越堆积，价格越低
- `k` 越大，价格对库存变化越敏感

### 1.3 滑点与动态印花税

批量买卖不是简单的“单价 × 数量”。系统会按逐个成交的方式做离散积分，因此天然存在滑点。

SELL 侧还会额外收取动态印花税：

```text
DynamicSpread = BaseSpread(5%) + VolatilitySpread + DumpingTax
```

其中：

- `BaseSpread`：基础 5% 税
- `VolatilitySpread`：当前价偏离 TWAP 越多，SELL 税越高
- `DumpingTax`：单次抛售量远超当前真实库存时，额外征收防倾销税

### 1.4 全局金库

EcoBrain 3.0 不是无限发钱系统，而是金库守恒系统：

- 玩家 **BUY**：钱进入系统金库
- 玩家 **SELL**：钱从系统金库支出
- 金库余额不足时，系统会拒绝收购

### 1.5 AI 的职责边界

AI 不负责逐笔交易定价。逐笔交易的即时价格变化主要来自 vAMM 的库存滑点。

AI 的职责是：

- 每个调控周期读取市场状态
- 调整 `base_price`
- 调整 `k`

也就是说，AI 做的是**慢变量宏观调控**，不是逐单撮合。

---

## 2. 线上插件如何工作

Java 插件在线上**只做推理，不做训练**。

一个标准周期大致如下：

1. 插件从数据库读取每个物品的 `base_price / k / target_inventory / current_inventory / physical_stock`
2. 再聚合最近一段时间的交易数据，构造 observation
3. 把 observation 喂给 `ecobrain_value.onnx`
4. ONNX 输出两个连续动作分量，插件先 `clip -> [-1, 1]`
5. 如果该物品在活动窗口内**没有真实成交**，则把动作按 `ai.tuning.inactivity-action-decay` 缩小
6. 再映射为：
   - `basePriceMultiplier`
   - `kDelta`
7. 最后仅保留硬性 clamp，把新的 `base_price / k` 写回数据库

线上插件还会持续记录：

- 真实交易日志
- AI 调参审计事件

但**当前 `exportdata` 导出的训练 CSV 只包含真实交易日志**，不会导出完整 observation 或完整 action。

---

## 3. 离线训练如何工作

离线训练由 `simulator/` 下的 Python 环境完成，使用 `Gymnasium + Stable-Baselines3 PPO`。

训练产物：

- `ecobrain_ppo_value.zip`
- `ecobrain_ppo_value_vecnormalize.pkl`（若启用）
- `ecobrain_value.onnx`

导出的 `ecobrain_value.onnx` 会被复制回服务器，由 Java 插件做推理。

### 3.1 模拟器里的市场

模拟器一次只控制**一个被训练的单品市场**，但它仍然维护和线上同语义的核心状态：

- `target_inventory`
- `current_inventory`
- `physical_stock`
- `base_price`
- `k`
- `treasury`

一个 `step()` 约等于线上一次 AI 调控周期。

模拟器里会发生两类市场驱动：

- **自博弈模式**：随机化的老玩家 / 新玩家 / 倒爷生态
- **数据回放模式**：按线上导出的 CSV 时间桶回放真实交易节奏

### 3.2 训练里的 low / mid / high 是什么

这点很重要：

**`low / mid / high` 是离线训练里的“环境 bucket / reward shaping bucket”，不是线上插件的真实物品标签。**

线上插件并不会给物品打一个“真实 tier”再按 tier 路由模型。当前版本线上只有：

- 一个模型
- 一套连续 observation
- 一套统一推理逻辑

离线训练里的 `low / mid / high` 主要用于：

- 采样不同价位区间的训练环境
- 使用不同的 reward band / shaping
- 在回放真实 CSV 时，按价格区间选一个更合适的代表物品

所以：

- **tier 不是线上硬标签**
- **tier 也不是模型在线推理时必须知道的真值**
- 它只是离线训练时帮助模型见到不同价位世界的一种组织方式

### 3.3 线上导出的 CSV 有什么用

当前 `exportdata` 导出的核心字段是：

- `item_hash`
- `trade_type`
- `quantity`
- `total_price`
- `created_at`

这些字段的价值分别是：

- `item_hash`：把同一种物品的交易串起来
- `trade_type`：区分真实买压和卖压
- `quantity`：重建流速、单量和库存压力
- `total_price`：重建单位成交价、AOV、金库收支尺度
- `created_at`：重建真实时间节奏

### 3.4 CSV 在训练里是怎么被使用的

CSV 不是被当成“监督学习标签”直接喂给模型，而是被当成**真实服务器节奏的证据**，放进模拟器里让 PPO 继续试错。

当前流程是：

1. 把整份 CSV 按 `created_at` 切成与 `schedule-minutes` 对齐的时间桶
2. 所有物品的交易一起用于构造**全局宏观统计**
   - AOV
   - 全局收支压力
   - 全局市场节奏
3. 同时按 `item_hash` 拆分单物品交易
4. 每个 episode 选择一个代表性 `item_hash` 作为“当前被控制的单品市场”
5. PPO 仍然自己输出 `base_price` 和 `k` 的动作
6. 模拟器在这个动作下，回放该时间桶里的真实交易节奏
7. 根据结果计算 reward，继续更新模型

这里的“代表性 `item_hash`”不是人工指定，也不是固定名单，而是每个 episode 都按下面的规则重新抽样：

1. 先对整份 CSV 里每个 `item_hash` 统计：
   - 总成交额 `value_sum`
   - 总成交量 `qty_sum`
   - 事件数 `event_count`
   - 最早/最晚出现在哪个时间桶
2. 计算该物品的平均成交单价：

```text
avg_unit_price = value_sum / max(1, qty_sum)
```

3. 再按 `avg_unit_price` 把它归进离线训练里的 `low / mid / high` bucket
4. 当前 episode 会先抽到一个 latent value type；候选物品优先从同 bucket 里选
5. 如果这个 bucket 在 CSV 里一个候选都没有，就退化为“全量物品都可选”
6. 候选之间不是平均随机，而是按 `event_count` 加权抽样  
   也就是说，交易更活跃、样本更多的物品更容易被选中，但不会变成永远只训练某一个 hash
7. 选中后还会再随机一个 `cycle_offset`，但这个随机不是无边界的，而是会被 episode 长度约束：

```text
max_start = max(0, dataset_max_cycle - max_steps + 1)
preferred_start     = min(selected_min_cycle, max_start)
latest_useful_start = min(selected_max_cycle, max_start)

cycle_offset ~ Uniform(preferred_start, latest_useful_start)
```

这样做的目的，是既尽量让回放从这个物品“真正开始活跃”的区间进入，又避免 episode 起点总是固定
8. 被选中的那个 `item_hash` 会作为“当前被控制的单品市场”；而同一时间桶里其他物品的交易，仍然继续参与**全局宏观统计**

所以“代表性”的真正含义不是“它是这个 tier 的官方样板”，而是：

- 它落在当前训练想看的价格区间里
- 它有足够的真实交易事件
- 它只是本回合被抽中的一个真实物品，不是永久主角

所以它是：

- **真实交易节奏回放**
- **模拟器内状态演化**
- **PPO 继续试错学习**

而不是：

- 直接拿真实数据做监督学习
- 也不是逐帧完整复刻线上全部内部状态

### 3.5 数据回放模式的边界

当前 CSV 回放很有用，但它不是“100% 服务器状态录像”。

它能提供：

- 哪个时间点有买卖
- 买还是卖
- 量有多大
- 成交总价多少
- 全局市场节奏如何变化

它不能直接提供：

- 当时完整 observation
- 当时完整 action
- 当时的 `base_price / k / target_inventory / current_inventory / physical_stock` 快照

所以现阶段的离线训练更准确地说是：

**交易日志驱动的半回放训练**，不是逐帧状态镜像。

---

## 4. Observation / Action 契约

这是 Java 插件和 Python 模拟器之间最重要的“接口契约”。

### 4.1 Observation

输入维度固定为：

- 类型：`float32`
- shape：`[1, 18]`
- 输入名：`observation`

18 维 observation 顺序固定如下：

1. `saturation`
2. `recent_flow`
3. `global_inflation`
4. `elasticity`
5. `volatility`
6. `log_price`
7. `log_twap`
8. `price_vs_twap`
9. `log_age`
10. `has_activity_trade`
11. `log_activity`
12. `log_target_inventory`
13. `log_physical_stock`
14. `price_change_pct`
15. `log_base_price`
16. `k_factor`
17. `physical_ratio`
18. `log_treasury`

这 18 个量不是随意拼出来的，它们都有固定计算方式。先约定几个公共记号：

```text
windowMinutes = ai.schedule-minutes
windowMs      = windowMinutes * 60 * 1000
aovWindowMs   = ai.aov-window-hours * 60 * 60 * 1000

P_current = base_price * (target_inventory / max(1, current_inventory)) ^ k

TWAP = mean(bucket_unit_price over non-empty buckets in AOV window)
bucket_unit_price = SUM(total_price in bucket) / SUM(quantity in bucket)
if TWAP <= 0:
    TWAP = P_current

unit_price_now  = SUM(total_price in current window) / SUM(quantity in current window)
unit_price_prev = SUM(total_price in previous window) / SUM(quantity in previous window)
if unit_price_now <= 0:
    unit_price_now = P_current
if unit_price_prev <= 0:
    unit_price_prev = TWAP if TWAP > 0 else P_current

dynamicAOV = SUM(global total_price in AOV window) / COUNT(global trades in AOV window)
if dynamicAOV <= 0:
    dynamicAOV = IPO_BASE_PRICE_FALLBACK * 20
```

然后每一维具体如下：

1. `saturation`

```text
saturation = current_inventory / max(1, target_inventory)
```

表示“虚拟库存相对理想库存的饱和度”。  
大于 `1` 说明库存偏多，小于 `1` 说明库存偏紧。

2. `recent_flow`

```text
recent_flow =
(
    SUM(BUY quantity in current window)
  - SUM(SELL quantity in current window)
) / windowMinutes
```

它不是总成交额，而是**按分钟归一化后的净流速**。  
正数偏买压，负数偏卖压。

3. `global_inflation`

```text
cycleNetEmission =
    SUM(SELL total_price in current window)
  - SUM(BUY total_price in current window)

global_inflation = cycleNetEmission / max(1e-9, dynamicAOV)
```

这里的“通胀”不是宏观经济学里的 CPI，而是一个工程化指标：  
当前周期系统净支出了多少金币，再用动态客单价做尺度归一化。

4. `elasticity`

```text
price_change_pct_raw =
    (unit_price_now - unit_price_prev) / max(1e-9, unit_price_prev)

elasticity_raw =
    recent_flow / max(1e-6, abs(price_change_pct_raw) * 100)

elasticity = clip(elasticity_raw, -1e4, 1e4)
```

这是一个启发式“价格弹性”特征。  
直觉上，它在问：价格只变了这么一点，需求流速却动了多少。

5. `volatility`

```text
volatility = abs(P_current - TWAP) / max(1e-9, TWAP)
```

表示当前价偏离时间加权均价的程度。  
它既进入 observation，也会影响 SELL 侧的动态印花税。

6. `log_price`

```text
log_price = clip(log(max(1e-9, P_current)), -20, 20)
```

因为服务器里物品价格跨度可能非常大，所以这里用对数压缩量级。

7. `log_twap`

```text
log_twap = clip(log(max(1e-9, TWAP)), -20, 20)
```

这是单模型的“稳定市场锚点”之一。  
`P_current` 可能已经被 AI 自己调偏，但 `TWAP` 更接近最近真实成交给出的市场公允价。

8. `price_vs_twap`

```text
price_vs_twap = clip(log(max(1e-9, P_current) / max(1e-9, TWAP)), -10, 10)
```

这是第二个关键锚点，而且带方向。  
正值表示“当前价高于近期公允价”，负值表示“当前价低于近期公允价”。

9. `log_age`

```text
age_cycles = listing_age_ms / max(1, windowMs)
log_age    = clip(log1p(max(0, age_cycles)), 0, 20)
```

它表示“这个物品进入系统已经经历了多少个调控周期”。  
训练端内部直接维护等价的 `age_cycles` 语义。

10. `has_activity_trade`

```text
activityVolume = SUM(item total_price in activity window)
activityWindow = max(windowMs, aovWindowMs)

has_activity_trade = 1 if activityVolume > 0 else 0
```

这是最关键的门控信号之一。  
如果这一维为 `0`，当前版本不会满强度调控，而是把 ONNX 动作按 `ai.tuning.inactivity-action-decay` 缩小后再执行。

11. `log_activity`

```text
log_activity = clip(log1p(max(0, activityVolume / windowMinutes)), 0, 20)
```

它反映的不是“有没有交易”，而是“有多活跃”。  
同样也做了对数压缩，避免大额热门物品把量级拉爆。

12. `log_target_inventory`

```text
log_target_inventory = clip(log1p(max(0, target_inventory)), 0, 20)
```

把理想库存规模压成稳定范围，方便一个模型同时看小宗商品和大宗材料。

13. `log_physical_stock`

```text
log_physical_stock = clip(log1p(max(0, physical_stock)), 0, 20)
```

表示系统手里真实有多少货，而不是 AMM 曲线上的虚拟库存。

14. `price_change_pct`

```text
price_change_pct =
    clip((unit_price_now - unit_price_prev) / max(1e-9, unit_price_prev), -10, 10)
```

这里看的是**成交均价的环比变化**，不是 `base_price` 的变化。  
这样更接近玩家实际感受到的成交价格变化。

15. `log_base_price`

```text
log_base_price = clip(log(max(1e-9, base_price)), -20, 20)
```

`base_price` 是 AI 真正直接调的慢变量之一，所以要显式告诉模型它现在站在什么底价上。

16. `k_factor`

```text
k_factor = clip(k, ai.tuning.k-min, ai.tuning.k-max)
```

`k` 决定价格曲线对库存变化的敏感度。  
它是另一个被 AI 直接调控的核心参数。

17. `physical_ratio`

```text
physical_ratio = clip(physical_stock / max(1, target_inventory), 0, 1000)
```

它和 `saturation` 不一样：  
`saturation` 看的是虚拟库存池，`physical_ratio` 看的是现实仓库。

18. `log_treasury`

```text
treasury_scaled = treasury_balance / max(1e-9, dynamicAOV)
log_treasury    = clip(log1p(max(0, treasury_scaled)), 0, 20)
```

这里不是直接把金库余额原样塞进模型，而是先用动态客单价归一化。  
这样不同服务器规模下，金库特征的量级更稳定。

开发者注意：

- 第 6 维已经固定为 `log(P_current)`，不再保留旧语义开关
- 如果训练启用了 `VecNormalize(norm_obs=True)`，obs normalization 会被**烘焙进 ONNX**
- 因此 Java 插件应继续喂 **raw obs**，不要再做二次归一化

### 4.2 Action

ONNX 输出 shape 固定为 `[1, 2]`。

两个动作分量的含义是：

- `out0`：底价倍率控制
- `out1`：`k` 的增量控制

插件端会先做：

```text
clip(out, -1, 1)
```

再映射为：

```text
basePriceMultiplier = 1 + clip(out0, -1, 1) * ai.tuning.base-price-max-percent
kDelta              =     clip(out1, -1, 1) * ai.tuning.k-delta

if has_activity_trade == 0:
    basePriceMultiplier = 1 + (basePriceMultiplier - 1) * ai.tuning.inactivity-action-decay
    kDelta              = kDelta * ai.tuning.inactivity-action-decay
```

之后再对真实执行值做硬性 clamp：

- `base_price`：`[MIN_BASE_PRICE, ai.tuning.max-base-price]`
- `k`：`[ai.tuning.k-min, ai.tuning.k-max]`

---

## 5. 自己训练 AI

### 5.1 准备环境

```bash
cd simulator
pip install -r requirements.txt
```

### 5.2 本地从头训练

```bash
python train.py
```

如果你不需要 tensorboard，可以这样跑：

```bash
python train.py --log-formats stdout,csv
```

当前默认行为补充：

- 如果 `--value-type mixed` 且没有手动指定 `--curriculum`，训练会自动使用 `low -> mid -> high -> mixed`
- 这样做是为了先学会各价位世界的基本控制方向，再进入更长的 mixed 融合阶段

### 5.3 用真实服务器数据继续训练

1. 服务器运行一段时间
2. 在游戏内执行：

```text
/ecobrain admin exportdata
```

3. 拿到导出的 CSV 路径后训练：

```bash
python train.py --dataset /你的服务器路径/plugins/EcoBrain/ecobrain_training_data_1700000000.csv
```

### 5.4 训练完成后部署

训练完成后会得到 `ecobrain_value.onnx`。

把它复制到服务器：

```text
plugins/EcoBrain/models/ecobrain_value.onnx
```

然后在游戏内执行：

```text
/ecobrain reload
```

### 5.5 什么时候必须从头训练

如果你改了下面这些内容，请不要继续接旧 checkpoint，直接 `--no-resume` 从头训练：

- observation 语义
- 动作映射语义
- SELL 税费机制
- 自适应 target 更新逻辑
- 数据回放逻辑
- 玩家生态模型

建议命令：

```bash
python train.py --no-resume
```

### 5.6 训练产物

- 模型权重：`ecobrain_ppo_value.zip`
- VecNormalize 统计：`ecobrain_ppo_value_vecnormalize.pkl`
- ONNX 推理模型：`ecobrain_value.onnx`
- Checkpoints：`simulator/checkpoints/`
- 训练日志：`simulator/runs/ecobrain_ppo_value/`

---

## 6. 配置对齐清单

如果你修改了下面这些配置，请记得同步检查 Java 插件和 Python 模拟器两边是否一致：

- `ai.schedule-minutes`
- `ai.aov-window-hours`
- `ai.tuning.max-base-price`
- `ai.tuning.base-price-max-percent`
- `ai.tuning.inactivity-action-decay`
- `ai.tuning.k-delta`
- `ai.tuning.k-min`
- `ai.tuning.k-max`
- `ai.adaptive-target.smoothing-factor`
- `ai.adaptive-target.quantity-cap`
- `economy.treasury.initial-balance`
- `circuit-breaker.critical-inventory`

---

## 7. 管理员功能

### 7.1 导出训练数据

命令：

```text
/ecobrain admin exportdata
```

作用：

- 导出当前服务器的真实交易日志
- 供离线训练时用 `--dataset` 回放

### 7.2 回收系统资金（QuickTax）

EcoBrain 会把玩家和系统商店的交易累计到 `ecobrain_player_transactions`。

当你想回收系统历史净支出时，可以使用：

- 预览：`/ecobrain admin reclaimmoney preview`
- 执行：`/ecobrain admin reclaimmoney`

回收公式：

\[
\text{outstanding} = \sum(\text{SELL}) - \sum(\text{BUY}) - \sum(\text{已回收})
\]

默认 QuickTax 命令模板：

```text
qt collectname {player} {amount}
```

如果要允许扣到负数，请在 QuickTax 的 `config.yml` 中设置：

```text
debt-mode: 2
```

---

## 8. 一个物品的完整生命周期

1. 玩家第一次把未知物品卖给系统，系统按 `100.0` 金币 IPO 价接收
2. 之后有其他玩家真实 BUY，这个物品开始出现成交信号
3. `current_inventory` 因买卖变化，vAMM 价格随之变化
4. 如果价格偏离 TWAP 或有人恶意大额倾销，SELL 税会明显升高
5. AI 在有活动信号的周期里按正常强度调 `base_price` 和 `k`；无活动时只做衰减后的小幅修正
6. 经过多轮真实交易和调控，价格靠近服务器真实愿意接受的区间

注意：

- 当前版本线上没有“真实 tier 标签”驱动推理
- AI 依赖的是连续 observation，而不是某个固定类别名

---

## 9. 主手市场提示（Title / Subtitle）

EcoBrain 支持在玩家主手切换到“市场里有货的物品”时发送一次标题提示。

特点：

- 只在主手切换时提示
- 有防抖，避免滚轮刷屏
- 默认仅在“至少还能买 1 个”时提示
- 文案支持 `{item}` / `{price}` / `{cmd}`

默认展示的命令文案是：

```text
/ecobrain buy <数量>
```

---

## 10. PlaceholderAPI 占位符

依赖：

- 服务器安装 `PlaceholderAPI`
- 前缀固定为 `ecobrain`

### 10.1 排行榜 TopN

出售榜 = 玩家 **SELL 给系统** 的累计成交额排行  
消费榜 = 玩家 **BUY 自系统** 的累计成交额排行

出售榜：

- `%ecobrain_top_sell_name_<N>%`
- `%ecobrain_top_sell_money_<N>%`
- `%ecobrain_top_sell_money_<N>_raw%`

消费榜：

- `%ecobrain_top_buy_name_<N>%`
- `%ecobrain_top_buy_money_<N>%`
- `%ecobrain_top_buy_money_<N>_raw%`

兼容别名：

- `top_`
- `lb_`
- `leaderboard_`

例如：

- `%ecobrain_top_sell_name_1%`
- `%ecobrain_lb_sell_money_1%`

### 10.2 玩家个人数据

我的出售：

- `%ecobrain_self_sell_money%`
- `%ecobrain_self_sell_money_raw%`
- `%ecobrain_self_sell_qty%`
- `%ecobrain_self_sell_rank%`

我的消费：

- `%ecobrain_self_buy_money%`
- `%ecobrain_self_buy_money_raw%`
- `%ecobrain_self_buy_qty%`
- `%ecobrain_self_buy_rank%`

兼容别名：

- `self_`
- `me_`

例如：

- `%ecobrain_me_sell_rank%`

### 10.3 更新时间

- `%ecobrain_leaderboard_updated_ms%`
- `%ecobrain_self_updated_ms%`
