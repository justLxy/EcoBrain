# EcoBrain 3.0

EcoBrain 是一个面向 Minecraft 服务器的动态经济插件。它的目标不是“把价格写死”，而是让系统商店根据真实库存、真实交易和真实服务器节奏持续自我修正。

当前版本的关键词只有四个：

- **单模型**：全服统一使用一个 ONNX 模型 `ecobrain_value.onnx`
- **零信任 IPO**：未知物品首次卖给系统时一律从 `100.0` 金币起步
- **vAMM 动态定价**：价格跟随库存、滑点和动态印花税变化
- **全局金库守恒**：玩家 BUY 给系统进钱，玩家 SELL 从系统出钱；金库不够就拒绝收购

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
2. 再聚合最近一段时间的交易数据，构造 16 维 observation
3. 如果该物品在活动窗口内**没有真实成交**，本周期直接 `HOLD`
4. 如果有成交，则把 observation 喂给 `ecobrain_value.onnx`
5. ONNX 输出两个连续动作分量，插件先 `clip -> [-1, 1]`
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
- shape：`[1, 16]`
- 输入名：`observation`

16 维 observation 顺序固定如下：

1. `saturation`
2. `recent_flow`
3. `global_inflation`
4. `elasticity`
5. `volatility`
6. `log_price`
7. `log_age`
8. `has_activity_trade`
9. `log_activity`
10. `log_target_inventory`
11. `log_physical_stock`
12. `price_change_pct`
13. `log_base_price`
14. `k_factor`
15. `physical_ratio`
16. `log_treasury`

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
basePriceMultiplier = 1 + clip(out0, -1, 1) * ACTION_BASE_PRICE_MAX_PERCENT
kDelta              =     clip(out1, -1, 1) * ai.tuning.k-delta
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
5. AI 在有活动信号的周期里，逐步调 `base_price` 和 `k`
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
