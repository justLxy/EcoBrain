# EcoBrain: 会自己呼吸的经济大脑 2.0

传统服务器的经济系统像是一个僵硬的自动售货机：服主写死苹果 5 块钱一个，玩家一旦找到刷金币或刷苹果的漏洞，这个售货机就会被彻底掏空，最后导致服务器物价崩溃。

EcoBrain 是为了打破这种僵局而生的。可以把它想象成一个“不仅知道根据库存自动改价，还能根据全服玩家消费习惯不断自我反省和学习的超级商人”。

在 2.0 版本中，我们抛弃了容易被玩家套利的死板机制，全面升级了经济防线。EcoBrain 2.0 的三大核心引擎是：**vAMM 动态定价与动态印花税**、**零信任 IPO 冷启动** 以及 **PPO 连续强化学习大脑 (ONNX)**。

---

## 1. 零信任 IPO (Zero-Trust IPO) 与初始价值发现

过去，玩家把未知的新物品（哪怕是泥土）第一次卖给系统时，系统会给出一个较高的初始盲猜价，这导致极易产生首发套利漏洞。
在 2.0 中，系统对任何未知物品秉持**零信任**原则。
任何未在系统登记过的物品，其首次卖给系统的价格永远是 **0.01 金币**。

**价值由买单决定，而非卖单**：只有当其他玩家愿意花真金白银从系统里**买走**这个物品时，AI 才会认为该物品有真实的“需求价值”，从而真正激活调价引擎将其价格拉升。泥土因为没人买，永远只能卖 0.01；极品材料一出现就被买空，价格直接起飞。

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

**1. 价格波动税：** 如果某个大户瞬间清空了某种材料，导致价格剧烈飙升（产生高波动率），系统会自动触发防套利惩罚：
> **Volatility = |CurrentPrice - TWAP| / TWAP**
> **VolatilitySpread = Volatility × 0.5**

**2. 防恶意套现熔断税：** 针对“开局只卖 1 个建仓，左右手互倒把价格炒到 1 万块，最后一次性倾销 1000 个套现”的恶意操控，系统加入了物理库存校验。如果玩家单次抛售量超过当前全服已知物理库存的 3 倍，开始征收超额抛售税（每超出 1 倍加收 10%）。

> **DynamicSpread = BaseSpread(5%) + VolatilitySpread + DumpingTax** (最高拦截至 99.9%)

这意味着，任何人想要趁机抛售存货砸盘套利，都会被极高的印花税直接没收全部收益，一分钱都套不走。只有真实玩家的正常交易才能享受低滑点。

---

## 3. 强化学习与宏观调控引擎 (The ONNX AI Brain)

### 3.1 AI 是如何学习的？(The Training Mechanism)
在 2.0 中，我们全面接入了 PPO (Proximal Policy Optimization) 连续强化学习算法。为了让 AI 学会如何当一个合格的“经济宏观调控局局长”，我们将训练分为两个阶段：**本地高仿真虚拟推演 (Offline Training)** 和 **生产服数据回流微调 (Online Fine-tuning)**。

#### 阶段一：本地高仿真虚拟推演 (Offline Simulator)
在把 AI 放到你的服务器之前，我们用 Python `Gymnasium` 框架为它搭建了一个“高仿真的虚拟世界”（代码位于 `simulator/` 目录）。在这个阶段，我们利用计算机的强大算力**“折叠时间”**：把现实中需要 60 分钟才能收集到的一轮市场数据，在 Python 中压缩到零点几毫秒内推演完毕。这意味着 AI 可以在十几秒内经历服务器上百年的经济兴衰史。

**技术逻辑路径如下：**
1. **环境初始化 (`env.py`)**：构建一个符合 `gymnasium` 标准的强化学习环境。在这里，**我们不是在训练一个具体的物品（比如“钻石剑”），而是在训练一种“商业逻辑”**。环境会虚拟出一个“测试商品”，并给它三组核心状态：
   - **`target_inventory`**：理想库存（对齐插件端的自适应目标库存逻辑）
   - **`current_inventory`（虚拟库存池）**：用于 AMM 曲线定价的库存（离线模拟器会在 IPO 冷启动时将其初始化为 `target_inventory`，使开局饱和度约为 100%）
   - **`physical_stock`（真实库存）**：代表系统真实可卖库存（影响能否买入与防倾销税）
   
   同时强制设定首发 `base_price = 0.01` 金币（零信任 IPO 起步）。
2. **三大平行宇宙（三脑协同）**
   游戏里的物品天然存在阶级分化，如果混在一起训练，AI 会“精神错乱”（不知道该涨还是该跌）。所以我们在 `config.py` 中划分了三个宇宙分别训练三个独立的大脑：
   *   **`ecobrain_low_value.onnx` (低价值宇宙)**：专门处理泥土、小麦等。这里塞满了疯狂向系统倾销物品的“肝帝老玩家”。如果 AI 敢让系统给这些玩家多发钱（引发通胀），环境会直接对其施加**极度严厉的扣分 (`reward -= 30.0`)**。AI 被迫学会极其冷酷地将底价按死在几分钱。
   *   **`ecobrain_mid_value.onnx` (中价值宇宙)**：负责处理铁锭、副本材料等。这里有产出也有消耗，AI 的任务是维稳。它需要在玩家的消耗和产出间寻找平衡点，促成交易。如果它能把价格稳定在合理区间，就能获得加分。
   *   **`ecobrain_high_value.onnx` (高价值宇宙)**：专门处理下界之星等稀有物。这里几乎没有卖家，只有携带巨款偶尔路过的“神豪新玩家”和专门找漏洞的“倒爷”。如果系统库存被神豪低价买空，AI 会受到**毁灭性惩罚 (`reward -= 20.0`)**。它在这里被训练得极其贪婪，一旦有买压，敢于跨维度拉升价格榨干土豪。

3. **高强度的微观博弈 (The Micro-ticks)**
   在一百万步（timesteps）的训练周期中，每一个 `step()` 回合都经历着经典的 **深度强化学习（Deep Reinforcement Learning）循环**：
   *   **前向传播 (Observation -> Action)**：AI 神经网络接收当前的 6 维经济状态张量（库存饱和度、净流量、全局通胀率、价格弹性、波动率、IPO 状态）。尤其是**“价格弹性”**，它告诉 AI 涨价后玩家是不买了（廉价品）还是照买不误（必需品/极品）。随后，AI 吐出两个动作：调整底价的百分比和微调 K 系数的幅度。
   *   **黑盒推演 (Interaction)**：将 AI 决定的价格应用到 AMM 中。接着，在这个价格下，虚拟的“老玩家”、“新玩家”和“倒爷”会通过一个 `for _ in range(10):` 的微循环疯狂冲进市场进行买卖。老玩家无情砸盘，新玩家按需买单，倒爷伺机套利。
   *   **结算打分 (Reward Calculation)**：周期结束，环境统算。如果倒爷套利成功、或者系统被老玩家抽干金币，系统会给 AI 打一个负分；如果 AI 成功防住了倒爷的冲击并赚到了神豪的钱，就给正分。
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

配置入口都在 `simulator/ecobrain_env/config.py`：

- `ECOSYSTEM_RANDOMIZATION.enabled`: 是否启用生态随机化
- `MARKET_REGIMES`: 各 regime 的权重与乘子
- `SIMULATED_PLAYER_ARCHETYPES`: 各类玩家的分布化参数

> 如果你提供了真实 CSV（`--dataset`），模拟器会优先用 `ReplayPlayer` 回放你服的交易节奏（更贴服情），并保留少量“倒爷”作为压力测试。

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
6. **IPO Flag (冷启动标识)**：当前物品是否刚上市

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

**4) TWAP（时间桶近似，time-weighted）**

将 `[t-H_ms, t)` 按时间桶大小 `B_ms = W_ms` 切分。对每个非空桶 `b`：

```text
bucketVWAP[b] = (Σ_{tr in bucket b} totalPrice(tr)) / max(eps, Σ_{tr in bucket b} quantity(tr))

TWAP = average(bucketVWAP[b]) over all non-empty buckets
```

**5) Volatility**

```text
volatility = abs(P_current - TWAP) / max(eps, TWAP)
```

其中 `P_current` 为 AMM 当前价：

```text
P_current = basePrice * (targetInventory / max(1, currentInventory))^k
```

**6) Elasticity（启发式）**

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

**7) IPO Flag**

```text
isIpo = 1 if basePrice <= 0.011 else 0
```

### 3.3 动作空间 (Action Space) 与推理
模型输出的是一个连续张量（在 -1.0 到 1.0 之间），然后我们会将其映射到实际的调控幅度：
*   **Base Price Multiplier (`[-100% ~ +100%]`)**：AI 直接决定基准价上涨或下跌的百分比。最新版本默认全权放权给 AI（单次最大翻倍或跌底，即 100% 变幅），给予 AI 极大的调控权力来应对如老玩家疯狂倾销等极端市场变化。**注意：你可以在 `simulator/ecobrain_env/config.py` 中自定义此上限（`ACTION_BASE_PRICE_MAX_PERCENT`）。最终在 Java 端还会受到 `config.yml` 中 `per-cycle-max-change-percent` 的二次拦截**。
*   **K-Factor Delta (`[-1.0 ~ +1.0]`)**：AI 微调 AMM 曲线的陡峭程度。K 系数非常敏感，直接影响滑点深度。配合百分比的放宽，单次最大微调幅度放宽至 1.0，让 AI 能够在面对紧急通缩或爆仓时，瞬间拉起或砸平价格曲线。**此上限同样可在 `config.py` 中修改（`ACTION_K_FACTOR_MAX_DELTA`）**。

为了保证性能与跨平台兼容，Java 插件通过集成 `ONNX Runtime` 实现了**脱离 Python 环境的毫秒级端侧推理**。Java 插件会在运行时根据物品的当前价格和目标库存（服主可在 `config.yml` 中自定义阈值），**动态路由**请求到对应的 `high`, `mid` 或 `low` 的大脑进行推理。

### 3.4 动手实践：如何自己训练 AI (How to Train)
想要在本地复现训练过程，或者使用自己服务器的数据微调模型，请按照以下步骤操作：

**1. 准备 Python 环境**
```bash
cd simulator
python3 -m venv venv
source venv/bin/activate  # Windows 用户使用 venv\Scripts\activate
pip install -r requirements.txt
```

**2. 自定义物品阈值（可选）**
如果你想调整各阶级物品的目标库存、奖惩门槛、价格判定标准，可以直接打开并修改 `simulator/ecobrain_env/config.py`。所有的参数都已经用中文写好了注释，无需修改底层算法逻辑。

**3. 启动本地高仿真训练 (Offline Training)**
直接运行 `train.py`，程序会依次为你训练低、中、高价值的三个大脑。在百万级的博弈推演后，代码会自动把 PPO 模型导出为 Java 可读的 ONNX 格式。
```bash
python train.py
```
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
