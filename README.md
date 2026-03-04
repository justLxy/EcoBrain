# EcoBrain: 会自己呼吸的经济大脑 2.0

传统服务器的经济系统像是一个僵硬的自动售货机：服主写死苹果 5 块钱一个，玩家一旦找到刷金币或刷苹果的漏洞，这个售货机就会被彻底掏空，最后导致服务器物价崩溃。

EcoBrain 是为了打破这种僵局而生的。可以把它想象成一个“不仅知道根据库存自动改价，还能根据全服玩家消费习惯不断自我反省和学习的超级商人”。

在 2.0 版本中，我们抛弃了容易被玩家套利的死板机制，全面升级了经济防线。EcoBrain 2.0 的三大核心引擎是：**vAMM 动态定价与动态印花税**、**零信任 IPO 冷启动** 以及 **PPO 连续强化学习大脑 (ONNX)**。

---

## 1. 零信任 IPO (Zero-Trust IPO) 与初始价值发现

过去，玩家把未知的新物品（哪怕是泥土）第一次卖给系统时，系统会给出一个较高的初始盲猜价，这导致极易产生首发套利漏洞。
在 2.0 中，系统对任何未知物品秉持**零信任**原则。
任何未在系统登记过的物品，其首次卖给系统的价格永远是 **0.01 金币**。

**价值由买单决定，而非卖单**：只有当其他玩家愿意花真金白银从系统里**买走**这个物品时，AI 才会认为该物品有真实的“需求价值”，从而真正激活调价引擎将其价格拉升。

> 注意：**0.01 只属于“新物品/首次登记”的 IPO 冷启动阶段**。进入成熟交易期（Mature）后，物品价格会在区间内波动并由市场持续修正；离线模拟器在 reset 时也会混合采样 IPO 与 Mature（见 3.1）。

---

## 2. AMM 自动做市商与防套利引擎 (The AMM Engine)

### 2.1 动态定价公式
系统是一个永远开门的商人，他对每件物品都有一个“理想库存量”（Target Inventory）。这个理想库存并非死板固定的，系统会根据全服真实的物理流通量，通过 EMA（指数移动平均）算法**自动且平滑地调整**该物品的理想库存，从而免去服主手动配置成百上千种物品库存的痛苦。
*   如果你疯狂把苹果卖给他，导致仓库爆满，他会恐慌并**疯狂压低收购价**。
*   反之，如果全服都来找他买苹果，苹果快绝版了，他会**疯狂抬高售卖价**。

背后的数学公式：
> **当前价格 = 基准价 × (理想库存 ÷ 当前实际库存) ^ K系数**

### 2.2 滑点计算 (Slippage)
假设苹果现在是 10 块钱一个。玩家想一次性卖给系统 64 个苹果。系统**绝对不会**直接给他 `10 × 64 = 640 块钱`！
因为只要玩家卖出第 1 个苹果，系统的库存就增加了，根据上面的公式，第 2 个苹果的收购价就已经跌到了 9.9 块；第 3 个跌到了 9.8 块……第 64 个可能只值 5 块钱了。系统会在后台把这 64 个苹果不断下跌的价格**逐个积分相加**，这就是滑点，从数学底层堵死了大户砸盘刷钱。

### 2.3 动态印花税与反倾销熔断税 (Dynamic Spread & Dumping Tax)
为了杜绝投机倒把的“羊毛党”和“杀猪盘”，2.0 引入了**时间加权平均价 (TWAP) 与防倾销惩罚**机制。
正常情况下，系统对**卖给系统（SELL）**收取 5% 的基础印花税（买入侧不额外收取该税）。

**1. 防恶意套现熔断税：** 针对“开局只卖 1 个建仓，左右手互倒把价格炒到 1 万块，最后一次性倾销 1000 个套现”的恶意操控，系统加入了物理库存校验。如果玩家单次抛售量超过当前全服已知物理库存的 3 倍，开始征收超额抛售税（每超出 1 倍加收 10%）。

> **DynamicSpread = BaseSpread(5%) + DumpingTax** (最高拦截至 99.9%)

**2. TWAP / Volatility 的用途（观测特征为主）：** 当前实现中，TWAP/Volatility 主要用于 AI 的状态特征（见 3.2），并为“倒爷/套利者”提供均值回归参考；如果你希望把 VolatilitySpread 也纳入税费模型，可在插件端扩展 `calculateDynamicSpread`（模拟器端也可以同步加入）。

这意味着，任何人想要趁机抛售存货砸盘套利，都会被极高的印花税直接没收全部收益，一分钱都套不走。只有真实玩家的正常交易才能享受低滑点。

---

## 3. 强化学习与宏观调控引擎 (The ONNX AI Brain)

### 3.0 PPO/ONNX/插件整体架构总览

#### 3.0.1 生产→导出→训练→部署→推理

```text
Minecraft 服务器（Java 插件，线上只做推理，不训练）

  玩家买卖
     |
     v
  ecobrain.db  (真实交易日志 + AI 决策日志)
     |
     |  (按周期统计窗口: schedule / AOV / TWAP)
     v
  构造 6 维观测 obs = [saturation, flow, inflation, elasticity, volatility, log_price]
     |
     v
  ONNX 推理: OnnxModelRunner.predictAction(obs, tier)
     |
     |  (ONNX 输出是 pre-tanh 均值；插件端对每个维度 tanh -> [-1, 1])
     v
  连续动作 action = {base_price multiplier, k_delta}
     |
     v
  AIScheduler.applyActionToItem  (安全夹紧/阈值/写库落盘)


离线训练（Python 模拟器，产出可热更的 ONNX 模型文件）

  Gymnasium 环境: EcoBrainEnv  (simulator/ecobrain_env/env.py)
     |
     v
  Stable-Baselines3 PPO: PPO("MlpPolicy", ...)  (simulator/train.py)
     |
     v
  训练产物: ecobrain_ppo_{tier}.zip + VecNormalize 统计
     |
     v
  导出推理模型: ecobrain_{tier}_value.onnx
     |
     v
  复制到服务器: plugins/EcoBrain/models/  (覆盖即可热更)


闭环数据回流（用真实服情微调）

  /ecobrain admin exportdata  -> 导出训练 CSV
  python train.py --dataset <path/to/csv>  -> ReplayPlayer 回放真实节奏
```

你可以把它理解为两条闭环：
- **线上闭环（实时推理）**：插件按周期统计 → ONNX 推理 → 调参 → 写库落盘（不在线算 reward、不在线训练）
- **离线闭环（训练/微调）**：用模拟器自博弈或回放 CSV → SB3 PPO 训练 → 导出 ONNX → 覆盖模型文件热更

#### 3.0.2 “接口契约”写死，保证模拟器与插件完全对齐

这几个点是对齐的硬约束（任何一处改了都要同步改另一端）：

- **观测（Observation）**
  - **维度**：6 维（`float32`）
  - **shape**：`[1, 6]`
  - **语义**：`[saturation, flow, inflation, elasticity, volatility, log_price]`
  - **生成位置**
    - 插件端：`src/main/java/com/ecobrain/plugin/ai/AIScheduler.java`
    - 模拟器端：`simulator/ecobrain_env/env.py::_get_obs()`（并受 `simulator/ecobrain_env/config.py` 的 `OBS_USE_LOG_PRICE` 控制）

- **ONNX 输入/输出**
  - **输入名**：`observation`（插件端按这个名字喂数据）
  - **输出 shape**：`[1, 2]`
  - **输出语义**：actor 的 **均值动作（pre-tanh / pre-squash）**，不是最终 \([-1, 1]\) 动作

- **动作（Action）后处理与映射（插件端真实执行语义）**
  - 插件端会对两个输出分量都先做 `tanh`，把它们 squashing 回 \([-1, 1]\)（实现：`src/main/java/com/ecobrain/plugin/ai/OnnxModelRunner.java`）。
  - **动作 0（底价倍率）**：`basePriceMultiplier = 1 + tanh(out0) * 1.00`
    - 其中 `1.00` 与模拟器训练侧的 `ACTION_BASE_PRICE_MAX_PERCENT = 1.00` 对齐（`simulator/ecobrain_env/config.py`）
  - **动作 1（K 微调）**：`kDelta = tanh(out1) * kDeltaMax`
    - `kDeltaMax` 为 tier 级别的安全上限（插件端动态传入）

> 重要：如果你启用了 `VecNormalize(norm_obs=True)`，训练导出 ONNX 时会把 obs normalization **烘焙进 ONNX**（见 `simulator/train.py` 的导出 wrapper）。因此插件端应持续喂 **raw obs**，不要再做二次归一化。

#### 3.0.3 PPO 的网络（MLP）架构到底是什么？

EcoBrain 的训练使用 SB3 的 `PPO("MlpPolicy", ...)`（见 `simulator/train.py`）。因此策略网络本质上是 **Actor-Critic**：
- **Actor（策略网络）**：obs → MLP → 输出 2 维连续动作的均值（训练时内部再 squashing）
- **Critic（价值网络）**：obs → MLP → 输出状态价值 \(V(s)\)

在本仓库当前实现中：
- **激活函数**：`Tanh`（因为没有显式传 `activation_fn`，SB3 默认就是 `Tanh`）
- **隐藏层层数与宽度**
  - **默认（不传 `--net-arch`）**：actor/critic **各自 2 层隐藏层**，宽度 **64 → 64**
  - **自定义**：传 `--net-arch 256,256,256` 则 actor/critic **各自 3 层隐藏层**（256→256→256）
- **配置入口**：`simulator/train.py` 会把 `--net-arch` 解析为 `policy_kwargs["net_arch"]` 传给 SB3；不传则走 SB3 默认。

### 3.1 AI 是如何学习的？(The Training Mechanism)
在 2.0 中，我们全面接入了 PPO (Proximal Policy Optimization) 连续强化学习算法。为了让 AI 学会如何当一个合格的“经济宏观调控局局长”，我们将训练分为两个阶段：**本地高仿真虚拟推演 (Offline Training)** 和 **生产服数据回流微调 (Online Fine-tuning)**。

#### 阶段一：本地高仿真虚拟推演 (Offline Simulator)
在把 AI 放到你的服务器之前，我们用 Python `Gymnasium` 框架为它搭建了一个“高仿真的虚拟世界”（代码位于 `simulator/` 目录）。在这个阶段，我们利用计算机的强大算力**“折叠时间”**：把现实中需要 60 分钟才能收集到的一轮市场数据，在 Python 中压缩到零点几毫秒内推演完毕。这意味着 AI 可以在十几秒内经历服务器上百年的经济兴衰史。

**技术逻辑路径如下：**
1. **环境初始化 (`env.py`)**：构建一个符合 `gymnasium` 标准的强化学习环境。在这里，**我们不是在训练一个具体的物品（比如“钻石剑”），而是在训练一种“商业逻辑”**。环境会虚拟出一个“测试商品”，并给它三组核心状态：
   - **`target_inventory`**：理想库存（对齐插件端的自适应目标库存逻辑）
   - **`current_inventory`（虚拟库存池）**：用于 AMM 曲线定价的库存（离线模拟器会在 IPO 冷启动时将其初始化为 `target_inventory`，使开局饱和度约为 100%）
   - **`physical_stock`（真实库存）**：代表系统真实可卖库存（影响能否买入与防倾销税）
   
   **Reset 时的“物品状态混合采样”（更贴近真实服）**：
   - 以 `IPO_RESET_PROB` 的概率采样 **IPO 冷启动**：`base_price = 0.01` 且 `is_ipo = true`
   - 否则采样 **成熟物品（Mature）**：`base_price ~ initial_base_price`（支持分布采样），并做 `[MIN_BASE_PRICE, MAX_BASE_PRICE]` 夹紧
   - 其中 `initial_base_price` 推荐按该 tier 的 hard 区间设置（例如 low 1~1000、mid 1000~10000、high ≥10000）
2. **三大平行宇宙（三脑协同）**
   游戏里的物品天然存在阶级分化，如果混在一起训练，AI 会“精神错乱”（不知道该涨还是该跌）。所以我们在 `config.py` 中划分了三个宇宙分别训练三个独立的大脑：
   *   **`ecobrain_low_value.onnx` (低价值宇宙)**：专门处理泥土、小麦等。此宇宙默认模拟“养老服供大于求”：老玩家持续产出并在库存高于阈值时倾销；AI 的目标是把价格稳定在你配置的区间（例如 hard: 1~1000，reward band: 10~500），并控制通胀与库存偏离。
   *   **`ecobrain_mid_value.onnx` (中价值宇宙)**：负责处理铁锭、副本材料等。此宇宙同时存在产出与消耗，AI 的任务是维稳：在 buy/sell 压力与库存之间找平衡，把价格维持在区间（例如 1000~10000）。
   *   **`ecobrain_high_value.onnx` (高价值宇宙)**：专门处理下界之星等稀有物。此宇宙更强调稀缺与防被买空：买压出现时需要敢于提价，同时避免物理库存枯竭；价格下限通常设置为 ≥10000。

3. **高强度的微观博弈 (The Micro-ticks)**
   在一百万步（timesteps）的训练周期中，每一个 `step()` 回合都经历着经典的 **深度强化学习（Deep Reinforcement Learning）循环**：
   *   **前向传播 (Observation -> Action)**：AI 神经网络接收当前的 6 维经济状态张量（库存饱和度、净流量、全局通胀率、价格弹性、波动率、**log(当前价)**）。尤其是**“价格弹性”**，它告诉 AI 涨价后玩家是不买了（廉价品）还是照买不误（必需品/极品）。随后，AI 吐出两个动作：调整底价的百分比和微调 K 系数的幅度。
   *   **黑盒推演 (Interaction)**：将 AI 决定的价格应用到 AMM 中。接着，在这个价格下，虚拟的“老玩家”、“新玩家”和“倒爷”会通过 micro-ticks 进入市场进行买卖。
       - **玩家卖出需要自己真的有物品库存**（不会出现“无限倾销凭空造物品”的假象）
       - 每个 micro-tick 会先进行一次 **产出/消耗 tick**（用 Poisson 过程模拟自动化产出与消耗），再决定是否买/卖
   *   **结算打分 (Reward Calculation)**：周期结束，环境统算。Reward 只用于离线训练（插件端不在线算 reward）。核心目标是：交易活跃但不过度通胀、库存围绕目标、价格长期稳定在每个 tier 的 hard range / reward band 内（band 外采用连续惩罚，避免“常数罚”导致学不到方向）。
   *   **反向传播 (Backpropagation)**：`Stable-Baselines3` 框架收集到这批 `<状态, 动作, 得分>` 的经验池后，利用梯度下降计算损失，**反向传播去更新神经网络内部的权重**。让 AI 逐渐形成“肌肉记忆”。

#### 3.1.1 离线模拟器机制速览（通俗版）

模拟器只“虚拟一种商品”，但它的经济机制与插件端保持同一套核心语义（vAMM 定价 + 双库存 + 动态印花税 + 风控约束）。每个 `step()` 约等于线上一次 AI 调控周期（默认 15 分钟）。

- **step 内发生顺序**：
  - **AI 调参**：输出两个连续动作：`base_price` 的倍率、`k_factor` 的增量（并会做安全夹紧/上下限）。
  - **玩家交易**：同一个 step 里会跑多轮 micro-ticks（默认 10 轮），让“买卖”更像连续发生。
  - **更新状态/奖励**：计算 6 维观测（见下文）与 reward（reward 仅用于离线训练，插件端不在线计算 reward）。

- **双库存**：
  - **`physical_stock`**：真实库存（决定是否能买、影响 dumpingTax）。
  - **`current_inventory`**：虚拟库存池（决定 vAMM 曲线与价格敏感度）。

- **动态印花税（SELL 侧）**：基础 5% + volatilitySpread + dumpingTax（最高可到 99.9%）。


#### 3.1.2 更鲁棒的“玩家生态”随机化（Domain Randomization）

如果你不提供真实 CSV 数据，模拟器默认不会死记一套固定概率，而是**每个 episode 抽样一套生态**（更通用、更抗服情变化）：

- **Market Regime（市场状态）**：如 `quiet / normal / dumping / event_buying`，用乘子整体放大或缩小买卖概率与单量，模拟“养老服低频”“清仓倾销”“活动周末买压”等。
- **玩家 Archetype 分布**：老玩家/新玩家/倒爷的数量、buy/sell 概率、单量、资金均从分布采样（`beta/loguniform/choice` 等）。
- **供需来源也随机化**：每类玩家都可以配置 `initial_item_inventory / produce_lambda / consume_lambda`，让“供大于求/活动买压/清仓倾销”等服情来自可控的产出与消耗，而不是无上限卖出。

配置入口都在 `simulator/ecobrain_env/config.py`：

- `ECOSYSTEM_RANDOMIZATION.enabled`: 是否启用生态随机化
- `MARKET_REGIMES`: 各 regime 的权重与乘子
- `SIMULATED_PLAYER_ARCHETYPES`: 各类玩家的分布化参数

> 如果你提供了真实 CSV（`--dataset`），模拟器会优先用 `ReplayPlayer` 回放你服的交易节奏（更贴服情），并保留少量“倒爷”作为压力测试。

#### 3.1.3 Reward 设计要点（开发者，防遗忘）
Reward 只存在于模拟器，用来让 PPO 学到“宏观调控”的偏好。实现位置：`simulator/ecobrain_env/env.py`，权重在 `simulator/ecobrain_env/config.py`。

- **交易激励信号**：
  - `low/mid`：用 `trade_qty`（数量）而不是 `trade_value`（金额），避免策略学出“抬价刷成交额”的捷径
  - `high`：用 `log1p(trade_value)` 压缩金额信号，避免极端高价 runaway
- **通胀惩罚**：
  - `low/mid`：用 `inflation_ratio = max(0, sell-buy)/(sell+buy)`（价格无关）替代 `netEmission/dynamicAOV`（避免通过抬价让 AOV 变大来“洗掉”惩罚）
- **价格 shaping**：
  - 所有 tier：hard range 外按越界距离加重惩罚；hard range 内对 band 外做连续惩罚（而非常数罚），让梯度“有方向”

4. **导出成果 (ONNX Export)**：
   经过亿万次试错和梯度更新，AI 的权重收敛到了最优状态。`train.py` 会将这个饱经风霜的 PyTorch 神经网络，剥离掉不需要的训练代码，**打包成一个只包含纯粹前向传播计算图的 `.onnx` 模型文件**。
   这个文件极其轻量，随后交给 Java 插件在真实的 Minecraft 服务器中**瞬间完成毫秒级的免环境推理（Inference）**，这就是我们在 Java 端不需要再等 60 分钟去训练，也不会卡死主线程的根本原因。

#### 阶段二：闭环数据回流 (Online Fine-tuning)
每个服务器的玩家生态都是不同的。本地模拟器训练出来的模型虽然强大，但可能不是最贴合你服务器“服情”的。

为此，EcoBrain 2.0 提供了**生产数据回流机制**：
1. **数据捕获**：Java 插件在运行过程中，会把每一次真实的玩家交易、AI 每次看到的真实状态（State）以及作出的决策（Action），默默记录在 `ecobrain.db` 数据库中。
2. **导出提炼**：服主可以在游戏中执行 `/ecobrain admin exportdata`，系统会立刻把生产服内**所有的真实玩家交易日志**打包为 CSV 文件导出到 `plugins/EcoBrain/` 目录。
3. **二次进化 (Fine-tuning)**：你可以随时把这些真实的交易日志丢回 Python 模拟器中。模拟器会读取这些真实的购买率和抛售率，替换掉虚拟玩家的概率模型。这就相当于让 AI **在回放你服务器的真实历史**中重新挨打、重新学习。
4. **无缝热更**：训练后生成的新 `.onnx` 模型可以直接覆盖回服务器的 `models/` 目录。不需要占用服务器 CPU，你就能获得一个 100% 懂你服务器玩家的“专武大脑”。

---

### 3.2 状态空间 (Observation Space)
AI 每次调控（默认每 15 分钟，可在 `config.yml` 里调整）会提取 6 个高维经济指标作为输入张量：
1. **Saturation (饱和度)**：当前库存 / 理想库存
2. **Recent Flow (近期流速)**：净流量（BUY 记正、SELL 记负）并按分钟归一，代表真实的买压或卖压强度
3. **Global Inflation (全局通胀率)**：全服近期净印发金币 / 动态客单价，用于感知服务器通货膨胀
4. **Price Elasticity (价格弹性, 启发式)**：用“净流速（买压）/ 价格变化幅度”近似衡量需求对价格的敏感度（会做剪裁以避免极端噪声）
5. **Volatility (波动率)**：当前价格偏离 TWAP 的程度（TWAP 来自最近交易统计的时间桶近似）
6. **Log Price（绝对价位）**：`log(P_current)`（默认启用；用于让策略“看见”绝对价格水平，从而学会稳定在 reward band 内）

> 兼容开关：如果你想退回旧版“IPO Flag”，可在 `simulator/ecobrain_env/config.py` 里把 `OBS_USE_LOG_PRICE = False`。
>
> 开发者提示：**模拟器与插件必须完全一致**。模拟器由 `simulator/ecobrain_env/env.py::_get_obs()` 生成；插件端由 `src/main/java/com/ecobrain/plugin/ai/AIScheduler.java` 生成。

#### 3.2.1 具体公式（按当前实现）

设：
- 调控周期窗口 `W_ms`（毫秒）= `ai.schedule-minutes` × 60 × 1000
- AOV/TWAP 窗口 `H_ms`（毫秒）= `ai.aov-window-hours` × 60 × 60 × 1000
- 当前时刻 `t`，则本周期区间为 `[t-W_ms, t)`，上周期为 `[t-2W_ms, t-W_ms)`

**1) Saturation**

```text
saturation = current_inventory / max(1, target_inventory)
```

**2) Recent Flow（按分钟归一）**

```text
# trade sign:
#   BUY  -> +1
#   SELL -> -1

netFlow = Σ_{tr in [t-W_ms, t)} sign(tr) * quantity(tr)
recentFlow = netFlow / max(1, scheduleMinutes)
```

**3) Global Inflation**

```text
# money sign:
#   SELL -> +total_price (system emits money)
#   BUY  -> -total_price (system sinks money)

netEmission = Σ_{tr in [t-W_ms, t)} moneySign(tr) * totalPrice(tr)

dynamicAOV = (Σ_{tr in [t-H_ms, t)} totalPrice(tr)) / max(1, count(tr))

globalInflation = netEmission / max(eps, dynamicAOV)
```

**4) Elasticity（启发式）**

先算“本周期单位均价”（窗口 VWAP）：

```text
unitPriceNow  = (Σ totalPrice in [t-W_ms, t))   / max(eps, Σ quantity in [t-W_ms, t))
unitPricePrev = (Σ totalPrice in [t-2W_ms, t-W_ms)) / max(eps, Σ quantity in [t-2W_ms, t-W_ms))

deltaP = (unitPriceNow - unitPricePrev) / max(eps, unitPricePrev)

elasticity = clip(
  recentFlow / max(eps, abs(deltaP) * 100),
  -1e4, 1e4
)
```

**5) Volatility（TWAP 桶均价近似）**

将 `[t-H_ms, t)` 按时间桶大小 `B_ms = W_ms` 切分。对每个非空桶 `b`：

```text
bucketVWAP[b] = (Σ_{tr in bucket b} totalPrice(tr)) / max(eps, Σ_{tr in bucket b} quantity(tr))
TWAP = average(bucketVWAP[b]) over all non-empty buckets

volatility = abs(P_current - TWAP) / max(eps, TWAP)
```

其中 `P_current` 为 AMM 当前价：

```text
P_current = basePrice * (targetInventory / max(1, currentInventory))^k
```

**6) Log Price（第 6 维观测）**

```text
logPrice = clip( log(max(eps, P_current)), -20, 20 )
```

### 3.3 动作空间 (Action Space) 与推理
模型输出的是一个连续张量（在 -1.0 到 1.0 之间），然后我们会将其映射到实际的调控幅度：
*   **Base Price Multiplier (`[-100% ~ +100%]`)**：AI 直接决定基准价上涨或下跌的百分比。最新版本默认全权放权给 AI（单次最大翻倍或跌底，即 100% 变幅），给予 AI 极大的调控权力来应对如老玩家疯狂倾销等极端市场变化。你可以在 `simulator/ecobrain_env/config.py` 中自定义此上限（`ACTION_BASE_PRICE_MAX_PERCENT`）。
*   **K-Factor Delta (`[-1.0 ~ +1.0]`)**：AI 微调 AMM 曲线的陡峭程度。K 系数非常敏感，直接影响滑点深度。配合百分比的放宽，单次最大微调幅度放宽至 1.0，让 AI 能够在面对紧急通缩或爆仓时，瞬间拉起或砸平价格曲线。**此上限同样可在 `config.py` 中修改（`ACTION_K_FACTOR_MAX_DELTA`）**。

为了保证性能与跨平台兼容，Java 插件通过集成 `ONNX Runtime` 实现了**脱离 Python 环境的毫秒级端侧推理**。Java 插件会在运行时根据物品的当前价格和目标库存（服主可在 `config.yml` 中自定义阈值），**动态路由**请求到对应的 `high`, `mid` 或 `low` 的大脑进行推理。

#### 3.3.1 ONNX 推理对齐要点（开发者勿忘）
- **输入名与 shape**：输入名固定为 `observation`，shape 为 `[1, 6]`（batch 维可变）。
- **输入应为 raw obs**：若训练启用了 `VecNormalize(norm_obs=True)`，导出 ONNX 时会把 obs normalization **烘焙进 ONNX**；因此插件端不要再做二次归一化。
- **动作 squashing**：导出的 ONNX 输出为 actor 的均值（pre-tanh）。插件端需要 `tanh` 才能回到 `[-1, 1]`（见 `OnnxModelRunner`）。
- **Pure RL（生产执行语义）**：插件端不再做“爆仓/稀缺/无供给衰减”等动作覆盖，**只保留硬 clamp 与交易风控**。否则会造成“模型输出的动作 ≠ 实际执行动作”的分布错位，影响收敛与线上效果。
- **Tier-aware base_price cap（重要）**：为防止策略把 `base_price` 推到全局上限导致低/中价值永远回不来区间，线上与离线都对 `base_price` 做 tier 级别的上限夹紧：\n+  - low: `base_price <= low.price_max`（默认 1000）\n+  - mid: `base_price <= mid.price_max`（默认 10000）\n+  - high: `base_price <= max-base-price`（全局上限）\n+  - **实现位置**：模拟器 `simulator/ecobrain_env/env.py`（step 内 clamp），插件 `src/main/java/com/ecobrain/plugin/ai/AIScheduler.java`（applyActionToItem 内 clamp）。

### 3.4 动手实践：如何自己训练 AI (How to Train)
想要在本地复现训练过程，或者使用自己服务器的数据微调模型，请按照以下步骤操作：

**1. 准备 Python 环境**
```bash
cd simulator
pip install -r requirements.txt
```
> 你可以按需使用 venv/conda，但不是必须；关键是安装 `simulator/requirements.txt` 里的依赖。ONNX 导出需要 `onnxscript`。

**2. 自定义物品阈值（可选）**
如果你想调整各阶级物品的目标库存、奖惩门槛、价格判定标准，可以直接打开并修改 `simulator/ecobrain_env/config.py`。所有的参数都已经用中文写好了注释，无需修改底层算法逻辑。

**3. 启动本地高仿真训练 (Offline Training)**
直接运行 `train.py`，程序会依次为你训练低、中、高价值的三个大脑。在百万级的博弈推演后，代码会自动把 PPO 模型导出为 Java 可读的 ONNX 格式。
```bash
python train.py
```
> 如果你本机没装 tensorboard，可以用 `--log-formats stdout,csv`，避免 SB3 logger 报错。
训练完成后，你会在 `simulator` 目录下看到 `ecobrain_low_value.onnx`、`ecobrain_mid_value.onnx` 和 `ecobrain_high_value.onnx` 文件。将它们复制到服务器的 `plugins/EcoBrain/models/` 目录下，并在游戏内输入 `/ecobrain reload` 即可完成大模型的无缝热更！

**4. 使用生产服数据进行微调 (Online Fine-tuning)**
当服务器运行一段时间后：
1. 在游戏内管理员输入命令：`/ecobrain admin exportdata`
2. 插件会在 `plugins/EcoBrain/` 目录下生成一个包含真实玩家经济行为的日志文件（如 `ecobrain_training_data_1700000000.csv`）。
3. 复制该文件的绝对路径，直接用 `--dataset` 参数告诉训练脚本去读取它：
```bash
python train.py --dataset /你的服务器路径/plugins/EcoBrain/ecobrain_training_data_1700000000.csv
```
4. 脚本会自动解析 CSV 文件，计算出你服务器里真实的“玩家购买率、抛售率、平均交易量”等数据，并自动生成对应的 `ReplayPlayer` （回放玩家）来替换掉原来的模拟玩家。
5. 等待训练完成，将生成的新 `.onnx` 模型覆盖回服务器即可！不需要再手动改任何代码了！

> 重要提示：如果你修改了模拟器的核心机制（例如“玩家卖出必须有库存”“产出/消耗模型”“reset 的 IPO/Mature 混合”），这属于**环境分布变化**，建议删除旧的 `ecobrain_ppo_*.zip` / `*_vecnormalize.pkl` 或使用 `--no-resume` 从头训练，否则容易在旧分布策略上继续跑导致效果很差。

> 同样重要：如果你修改了 Observation 的含义（例如第 6 维从 `is_ipo_flag` 改为 `log(price)`），也属于**观测分布变化**，必须从头训练并重新导出 ONNX；插件端构造观测也要保持一致。

#### 3.4.1 训练产物与目录约定（开发者）
- **模型权重**：`ecobrain_ppo_{low,mid,high}.zip`（SB3）与 `ecobrain_ppo_*_vecnormalize.pkl`（归一化统计）
- **导出推理模型**：`ecobrain_{low,mid,high}_value.onnx`
- **日志**：`simulator/runs/`（或 `--log-dir` 指定目录）
- 这些都是生成物，不应提交到仓库（已在 `.gitignore` 忽略）。

---

## 4. Use Case (一个物品的完整一生)

1. **IPO**：玩家把未知的“附魔钻石剑”第一次卖给系统，只能拿到可怜的 0.01 金币。
2. **价值发现**：另一名土豪玩家路过商店，发现这把剑不错，花钱买走了它。
3. **AMM 接管**：系统库存减少，触发 AMM 机制，价格出现基础滑动。
4. **防套利启动**：投机者看到价格被炒高，试图抛售几千把“附魔钻石剑”赚差价，却触发了“防倾销熔断税”，被扣除了 99.9% 的手续费，血本无归。
5. **自适应与 AI 宏观定调**：系统根据该物品长期的真实流通量极少，**自动将其目标库存自适应缩减为个位数**，从而使其被划分为“高价值宇宙”。AI 经理接管后，发现这把剑在稀缺状态下依然有买压，便大胆地将其底价拉升到 50 万金币。
6. **最终**：市场在没有服主人工干预、无需手动设置库存和价格的情况下，依靠 AI 自治与动态安全网，精准且安全地找到了每一件物品最真实的社会价值。

---

## 5. PlaceholderAPI 占位符（系统商店买卖排行榜 / 个人数据）

EcoBrain 支持将“系统商店交易榜单（卖给系统/从系统购买）”与“玩家个人统计”以 PlaceholderAPI 变量形式暴露，便于在计分板、GUI、聊天、公告等任意支持 PAPI 的地方使用。

- **依赖**：需要服务器安装 `PlaceholderAPI`（EcoBrain 为 `softdepend`，不装不会报错，只是不会注册占位符）
- **占位符前缀**：`ecobrain`
- **空值约定**：
  - **榜单 name**：该名次不存在时返回空字符串
  - **金额 money**：该名次/该玩家没有记录时返回 `0.00`（`*_raw` 返回 `0`）
  - **名次 rank**：该玩家没有记录时返回 `0`

### 1) 排行榜 TopN（出售榜/消费榜）

出售榜 = 玩家把物品**卖给系统**（`SELL`）累计成交额排行  
消费榜 = 玩家从系统**买入物品**（`BUY`）累计成交额排行

- **出售榜 TopN**
  - `%ecobrain_top_sell_name_<N>%`：第 N 名玩家名
  - `%ecobrain_top_sell_money_<N>%`：第 N 名出售总额（两位小数）
  - `%ecobrain_top_sell_money_<N>_raw%`：第 N 名出售总额（原始数值）

- **消费榜 TopN**
  - `%ecobrain_top_buy_name_<N>%`：第 N 名玩家名
  - `%ecobrain_top_buy_money_<N>%`：第 N 名消费总额（两位小数）
  - `%ecobrain_top_buy_money_<N>_raw%`：第 N 名消费总额（原始数值）

示例：
- `%ecobrain_top_sell_name_1%`
- `%ecobrain_top_sell_money_1%`
- `%ecobrain_top_buy_name_3%`
- `%ecobrain_top_buy_money_3_raw%`

（兼容前缀别名：`top_` / `lb_` / `leaderboard_` 三者都可用，例如 `%ecobrain_lb_sell_money_1%`）

### 2) 玩家个人数据（我的金额/数量/名次）

这些占位符需要有玩家上下文（例如计分板、聊天格式、对玩家打开的 GUI）。  
个人数据会做缓存：占位符解析时只读缓存，过期后触发异步刷新，避免主线程查库卡顿。

- **我的出售（卖给系统 / SELL）**
  - `%ecobrain_self_sell_money%`：我的出售总额（两位小数）
  - `%ecobrain_self_sell_money_raw%`：我的出售总额（原始数值）
  - `%ecobrain_self_sell_qty%`：我累计卖给系统的物品数量（SUM(quantity)）
  - `%ecobrain_self_sell_rank%`：我的出售榜位（按总额降序；无记录为 0）

- **我的消费（从系统购买 / BUY）**
  - `%ecobrain_self_buy_money%`
  - `%ecobrain_self_buy_money_raw%`
  - `%ecobrain_self_buy_qty%`：我累计从系统买入的物品数量（SUM(quantity)）
  - `%ecobrain_self_buy_rank%`

（兼容前缀别名：`self_` 与 `me_` 等价，例如 `%ecobrain_me_sell_rank%`）

### 3) 更新时间（调试/观测用）

- `%ecobrain_leaderboard_updated_ms%`：排行榜 TopN 缓存更新时间（毫秒时间戳）
- `%ecobrain_self_updated_ms%`：个人缓存更新时间（毫秒时间戳）
