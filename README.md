# EcoBrain 3.0
## Abstract
EcoBrain 是一个面向 Minecraft 服务器的自适应系统市场插件。玩家可以把任意物品卖给系统市场获得金钱；物品被系统接收后会形成真实库存，随后其他玩家可以再从系统市场购买这些物品。系统因此不再依赖管理员为每种物品手工配置价格，而是把真实买卖行为本身作为价格发现信号。

EcoBrain 的定价机制由两层组成。第一层是动态 AMM：每个物品都沿着库存曲线定价，买入与卖出都会引起滑点，且 SELL 侧带有波动税与防倾销税。第二层是基于 PPO 的连续动作强化学习：策略网络不直接逐笔报价，而是在较慢时间尺度上调节每个物品的 `base_price` 与曲线敏感度 `k`。因此，瞬时价格由 AMM 负责，长期价格中枢与弹性由 PPO 负责。

从形式上看，EcoBrain 仍然可以被写成一个受约束的控制问题。系统在线上只做推理，在离线只做训练；线上交易由动态 AMM 决定瞬时价格，离线策略只负责较慢尺度上的参数调节。若系统状态记为

$$
x_t=(b_t,k_t,I_t^\star,I_t,S_t,T_t),
$$

其中 $b_t$ 为底价，$k_t$ 为曲线敏感度，$I_t^\star$ 为目标库存，$I_t$ 为虚拟库存，$S_t$ 为真实库存，$T_t$ 为系统金库，则 EcoBrain 的核心任务是学习策略

$$
a_t=\pi_\theta(o_t), \qquad x_{t+1}=f(x_t,a_t,e_t),
$$

其目标是在真实交易事件 $e_t$ 驱动下，同时维持交易活跃性、库存稳定性、资金守恒与价格可解释性。本文重点讨论其两条核心算法主线：动态 AMM 与 PPO 连续强化学习。

## 1. Introduction
EcoBrain 要解决的问题并不复杂：在一个允许玩家自由把任意物品卖给系统、再由其他玩家从系统购买的市场中，如何让价格无需人工维护却仍然能随着真实供需自动调整。传统动态商店往往依赖手工初始价、硬编码浮动区间和简单销量修正，这会把价格发现退化成配置管理问题。EcoBrain 则把问题分解为两部分：用动态 AMM 处理逐笔交易定价，用 PPO 连续动作策略处理较慢时间尺度上的参数调节。

这种分层设计的原因是工程上的，也是算法上的。若把“逐笔成交”与“长期调价”交给同一个学习器，状态转移会同时受到库存离散跳变、玩家到达不确定性和风控约束影响，问题过于嘈杂；而把逐笔价格交给解析式市场机制，再让策略网络只调节 $b_t$ 和 $k_t$，就能把学习任务限制在一个更稳定、更可解释的连续控制空间内。

## 2. Background
### 2.1 Problem Formulation
EcoBrain 处理的是“单品受控、全局同场”的问题。每个 episode 或每个线上调控周期只直接控制一个物品的 $b_t$ 与 $k_t$，但该物品并不处在真空中；全服其他物品的交易仍会进入全局背景统计，从而影响通胀、客单价和市场活跃度。

为便于后文表达，定义如下记号。

| 符号 | 含义 |
| --- | --- |
| $b_t$ | 当前调控周期的 `base_price` |
| $k_t$ | 当前调控周期的曲线敏感度 `k_factor` |
| $I_t^\star$ | 目标库存 `target_inventory` |
| $I_t$ | 虚拟库存 `current_inventory`，仅用于定价 |
| $S_t$ | 真实库存 `physical_stock`，仅用于交付与风控 |
| $T_t$ | 全局金库余额 `treasury` |
| $P_t$ | 由 vAMM 计算出的当前成交价 |
| $\mathrm{TWAP}_t$ | 近窗口时间加权平均价的近似量 |
| $o_t$ | $18$ 维连续观测 |
| $a_t$ | $2$ 维连续动作 |
| $e_t$ | 周期 $t$ 内实际发生的交易事件流 |

系统真正优化的不是“价格本身”，而是一个多目标平衡：既要让市场形成成交，又要防止库存失衡、价格失锚和系统资金透支。也正因为目标天然多元，EcoBrain 选择强化学习而不是监督学习。监督学习需要一个“正确动作”标签；而这里真正关心的是长期后果，而不是某一时刻的单步模仿。

## 3. System Overview
EcoBrain 由一个线上 Java 插件和一个离线 Python 模拟器组成。两者共享市场语义，但职责严格区分。

$$
\text{Trade Logs} \rightarrow \text{Replay / Simulation} \rightarrow \text{PPO} \rightarrow \text{ONNX} \rightarrow \text{Online Inference}.
$$

线上插件负责处理玩家买卖、执行 AMM 结算、维护库存与金库，并周期性调用 `ecobrain_value.onnx`。离线模拟器负责重建环境动力学、定义奖励函数、训练 PPO 并导出 ONNX。这样的拆分让线上路径尽可能简单，把学习复杂度全部放到离线完成。

### 3.1 Single-Brain Policy
EcoBrain 在线上只使用一个模型，而不是为 `low`、`mid`、`high` 物品分别路由不同模型。离线训练中的 `low / mid / high` 只是潜在世界 $z$ 的采样标签，用于组织环境参数与奖励带，而不是线上推理时的真实输入：

$$
z \in \{\text{low},\text{mid},\text{high}\}, \qquad z \notin o_t.
$$

这样做的动机不是“模型越少越高级”，而是因为线上路由会引入另一个分类错误源。若先分类、再定价，那么错误可能来自价格模型，也可能来自分类模型；而单模型把问题统一为“在连续状态空间上做控制”，减少了系统边界上的误差传播。

代价也存在：单模型必须同时处理低价、高价和极端库存场景，因此观测必须提供足够稳定的锚点，例如 $\log P_t$、$\log \mathrm{TWAP}_t$ 与 $\log T_t$。这也是 EcoBrain 强调观测设计而不是仅强调网络结构的原因。

## 4. Methodology
### 4.1 Zero-Trust IPO
对任意首次进入系统的物品，EcoBrain 不允许首位卖家定义市场锚点，而是采用统一的零信任初始条件

$$
b_0 = 100.0.
$$

若首单卖出数量为 $q_0$，则系统初始化

$$
I_0^\star = \max(16,2q_0), \qquad I_0 = I_0^\star, \qquad S_0 = q_0.
$$

这个设计背后的逻辑是：新物品最稀缺的是“可信价格信息”，而不是“价格本身”。首个 SELL 事件只告诉系统“世界上出现了这个物品”，却无法可靠告诉系统“它值多少钱”。因此 EcoBrain 故意把初始价格压回一个统一常数，让真正的价值发现来自后续 BUY 事件。换句话说，系统承认“有货”这件事，但暂不承认“高价”这件事。

选择 $I_0 = I_0^\star$ 而不是随手给一个很小的虚拟库存，同样出于稳定性考虑。若 IPO 时虚拟库存远小于目标库存，即使真实库存很多，价格也会因为曲线形状而被机械性抬高，形成假稀缺。让初始饱和度为 $1$ 可以避免这种非交易性价格跳跃。

### 4.2 vAMM Pricing
EcoBrain 的瞬时价格并不直接由 AI 给出，而是由解析式库存曲线给出：

$$
P_t=b_t\left(\frac{I_t^\star}{\max(1,I_t)}\right)^{k_t}.
$$

批量买入和卖出采用离散求和，而不是“单价乘数量”。若玩家向系统买入 $N$ 件，则总成本为

$$
C^{\text{buy}}_t(N)=\sum_{i=1}^{N} b_t\left(\frac{I_t^\star}{\max(1,I_t-i)}\right)^{k_t}.
$$

若玩家向系统卖出 $N$ 件，则税前总收入为

$$
R^{\text{sell,raw}}_t(N)=\sum_{i=1}^{N} b_t\left(\frac{I_t^\star}{I_t+i}\right)^{k_t}.
$$

这种离散积分的动机是把滑点做成机制内生属性，而不是事后再加一个“手续费倍率”。当库存接近枯竭时，边际价格会自动提高；当库存堆积时，边际价格会自动下降。于是“越买越贵、越卖越便宜”不再是业务规则，而是曲线本身的结果。

### 4.3 Virtual Inventory and Physical Inventory
EcoBrain 同时维护虚拟库存 $I_t$ 与真实库存 $S_t$。前者只参与定价，后者只参与交付与风控。这样做是为了把“价格弹性”与“履约能力”分离：AMM 需要连续可控的库存状态，交易系统则需要真实可交付的库存约束。

### 4.4 Dynamic Spread
玩家 SELL 时并不是简单获得 $R^{\text{sell,raw}}_t(N)$，而是获得扣税后的收入

$$
R^{\text{sell}}_t(N)=R^{\text{sell,raw}}_t(N)\cdot(1-s_t),
$$

其中动态印花税 $s_t$ 定义为

$$
s_t=\min\left(0.999,\;0.05 + \frac{1}{2}\frac{\lvert P_t-\mathrm{TWAP}_t\rvert}{\max(\varepsilon,\mathrm{TWAP}_t)} + \tau_t^{\text{dump}}\right).
$$

防倾销项 $\tau_t^{\text{dump}}$ 是分段函数。若单次抛售量 $N$ 没有超过真实库存的三倍，则

$$
\tau_t^{\text{dump}}=0.
$$

若 $N>3\max(1,S_t)$，则

$$
\tau_t^{\text{dump}}=\left(\frac{N}{\max(1,S_t)}-3\right)\cdot 0.10.
$$

动态税率主要做两件事：当现价偏离 $\mathrm{TWAP}_t$ 时抑制短期套利；当抛售规模远超真实库存时抑制异常供给冲击。它本质上是 AMM 外的一层稳定器，用于约束 SELL 路径中的极端行为。

### 4.5 Treasury Conservation
EcoBrain 的支付能力由单一全局金库 $T_t$ 控制，而不是默认无限收购。若周期内 BUY 交易收入为 $B_t$，SELL 支出为 $S_t^{\text{pay}}$，则金库演化满足

$$
T_{t+1}=T_t + B_t - S_t^{\text{pay}}.
$$

当某笔 SELL 所需支付超过当前金库余额时，交易会被拒绝。这个约束的意义并不只是“更真实”，而是把价格信号与货币信号重新绑定起来。若系统能无限付款，则玩家对系统 SELL 的意愿不再体现稀缺性判断，只体现“能否从插件稳定取钱”；此时市场会变成印钞机。金库守恒把这种虚假需求从机制层面切断。

### 4.6 Circuit Breaking and Inventory Protection
买入路径受到三类硬约束：风险冻结、虚拟库存下界、真实库存保护。它们的目的都是避免 AMM 在接近零库存时进入极端价格区，并保证系统仍然能够履约。

此外，系统还维护日内开盘价 $P_t^{\text{open}}$。若日涨跌幅

$$
\rho_t=\frac{P_t-P_t^{\text{open}}}{P_t^{\text{open}}}
$$

超过阈值 $\lambda_{\text{day}}$ 时，物品可进入冻结状态。它不是主调控器，而是异常行情下的最后一道保险丝。

### 4.7 Adaptive Target Inventory
目标库存 $I_t^\star$ 不应被视为常数，因为真实服务器的供需会长期漂移。EcoBrain 用数量感知的指数平滑更新目标库存。若某次交易后真实库存变为 $S_{t+1}$，单笔交易量为 $q_t$，则先定义

$$
m_t=\min(q_t,q_{\max}), \qquad \alpha_t^{\mathrm{eff}}=1-(1-\alpha)^{m_t},
$$

再更新目标库存

$$
I_{t+1}^\star=\mathrm{round}\left(I_t^\star + \alpha_t^{\mathrm{eff}}(S_{t+1}-I_t^\star)\right).
$$

仅更新 $I_t^\star$ 还不够。若目标库存改变而虚拟库存 $I_t$ 保持不动，价格会产生非交易性跳变。为保持相对饱和度近似不变，EcoBrain 同时做比例缩放：

$$
I_{t+1}=\max\left(1,\mathrm{round}\left(I_t\frac{I_{t+1}^\star}{I_t^\star}\right)\right).
$$

这一步保证目标库存更新只改变长期中枢，而不制造额外的瞬时价格冲击。

## 5. Observation-Action Contract
Java 插件与 Python 模拟器之间最重要的共同接口，是固定的 $18$ 维观测和 $2$ 维动作。若这一契约漂移，模型即使还能运行，其行为也不再可解释。

### 5.1 Observation Space
观测张量类型为 `float32`，形状为 `[1,18]`，输入名为 `observation`。为避免在公式中写过长的工程字段名，记动态客单价为 $A_t$，近窗口时间加权均价近似量为 $W_t$。则

$$
A_t=\frac{V_t}{\max(1,N_t)},
$$

$$
W_t=\frac{1}{|\mathcal{B}_t|}\sum_{b\in\mathcal{B}_t} u_b,
$$

其中 $V_t$ 表示 AOV 窗口内全局成交总额，$N_t$ 表示该窗口内全局成交笔数，$\mathcal{B}_t$ 表示 TWAP 窗口内的非空时间桶集合，$u_b$ 表示单个时间桶的单位成交均价。若窗口为空，则 $W_t$ 退化为 $P_t$，$A_t$ 退化为 IPO 基础价尺度常数。

#### 5.1.1 Inventory and Capacity Features

| 维度 | 公式 | 设计动机 |
| --- | --- | --- |
| `saturation` | $I_t / \max(1,I_t^\star)$ | 衡量曲线库存紧张度；它直接决定价格相对目标库存的偏离。 |
| `log_target_inventory` | `log(1 + I_t^\star)` 后再裁剪到 `[0, 20]` | 让模型区分“小宗高频物品”和“大宗材料”，避免一个模型只学到单一库存尺度。 |
| `log_physical_stock` | `log(1 + S_t)` 后再裁剪到 `[0, 20]` | 告诉模型系统真实有多少货，帮助它分辨“曲线缺货”和“仓库缺货”。 |
| `k_factor` | $k_t$ 裁剪到 $[k_{\min}, k_{\max}]$ | 显式暴露当前曲线陡峭度，否则策略无法知道自己站在什么曲线上。 |
| `physical_ratio` | $S_t / \max(1, I_t^\star)$ 后再裁剪到 `[0, 1000]` | 与 `saturation` 配合，揭示虚拟库存和真实库存是否发生结构性分离。 |

#### 5.1.2 Flow and Macro Features

| 维度 | 公式 | 设计动机 |
| --- | --- | --- |
| `recent_flow` | `(\sum BUY qty - \sum SELL qty) / schedule_minutes` | 用净流速而不是成交额，刻画当前买压或卖压方向。 |
| `global_inflation` | `(\sum SELL money - \sum BUY money) / max(\varepsilon, AOV_t)` | 近似系统净印钞强度；用 `AOV_t` 归一化是为了跨服务器稳定量级。 |
| `has_activity_trade` | `1[activity volume > 0]` | 这是动作门控信号；无活动时系统只允许小幅修正。 |
| `log_activity` | `log(1 + activity_volume / schedule_minutes)` 后再裁剪到 `[0, 20]` | 区分“完全无交易”和“有交易但很冷清”。 |
| `log_treasury` | `log(1 + T_t / max(\varepsilon, AOV_t))` 后再裁剪到 `[0, 20]` | 让金库规模在不同服务器经济体量下可比较。 |

#### 5.1.3 Price Anchor Features

| 维度 | 公式 | 设计动机 |
| --- | --- | --- |
| `volatility` | `|P_t - TWAP_t| / max(\varepsilon, TWAP_t)` | 衡量当前价偏离真实成交锚点的程度，也与 SELL 税耦合。 |
| `log_price` | `log(max(\varepsilon, P_t))` 后再裁剪到 `[-20, 20]` | 价格跨度大时，对数尺度比原始价格更稳定。 |
| `log_twap` | `log(max(\varepsilon, TWAP_t))` 后再裁剪到 `[-20, 20]` | 提供较慢的市场公允价锚点。 |
| `price_vs_twap` | `log(max(\varepsilon, P_t) / max(\varepsilon, TWAP_t))` 后再裁剪到 `[-10, 10]` | 不只告诉模型“价格多大”，还告诉模型“现在是偏贵还是偏便宜”。 |
| `price_change_pct` | `(u_t - u_{t-1}) / max(\varepsilon, u_{t-1})` 后再裁剪到 `[-10, 10]` | 这里的 $u_t$ 是周期成交均价；它更接近玩家真实感知，而不是 AI 内部参数变化。 |
| `log_base_price` | `log(max(\varepsilon, b_t))` 后再裁剪到 `[-20, 20]` | 让策略知道自己正在控制的底价基线。 |
| `elasticity` | `recent_flow / max(\varepsilon, 100 * |price_change_pct|)` 后再裁剪到 `[-10^4, 10^4]` | 这是启发式弹性特征，描述需求流速对价格扰动的敏感程度。 |

#### 5.1.4 Temporal Feature

| 维度 | 公式 | 设计动机 |
| --- | --- | --- |
| `log_age` | `log(1 + listing_age_in_cycles)` 后再裁剪到 `[0, 20]` | 新物品和成熟物品面对同样交易信号时，最优调节方向可能不同，因此年龄必须显式编码。 |

观测设计的原则是只提供控制所需的连续锚点，不引入额外的离散标签。模型看到的是价格、库存、波动、流量和金库状态，而不是人为指定的物品层级。

### 5.2 Action Space
ONNX 输出固定为 `[1,2]`，两维分别对应底价倍率和 $k$ 增量。设网络原始输出为 $\hat a_t^{(1)}, \hat a_t^{(2)}$，则先裁剪到 `[-1, 1]`，再映射为

$$
m_t = 1 + \mathrm{clip}(\hat a_t^{(1)},-1,1)\,\delta_b,
$$

$$
\Delta k_t = \mathrm{clip}(\hat a_t^{(2)},-1,1)\,\delta_k.
$$

在默认配置下，$\delta_b=0.12$，$\delta_k=0.10$。若当前活动窗口内没有真实成交，则动作被进一步衰减为

$$
m_t' = 1 + (m_t-1)\gamma, \qquad \Delta k_t'=\gamma \Delta k_t,
$$

其中 $\gamma=0.35$。最终写库时执行硬边界约束

$$
b_{t+1}=\mathrm{clip}(b_t m_t', b_{\min}, b_{\max}),
\qquad
k_{t+1}=\mathrm{clip}(k_t+\Delta k_t', k_{\min}, k_{\max}).
$$

这一映射说明 PPO 是在连续参数空间里做低频控制，而不是直接输出逐笔成交价。无交易时只保留衰减后的弱动作，用于防止长尾物品永久冻结。

## 6. Plugin Architecture
这一章只保留与动态 AMM 和 PPO 调控直接相关的插件结构。

### 6.1 Core Modules

| 模块 | 代表类 | 作用 |
| --- | --- | --- |
| 市场执行层 | `MarketService`, `AMMCalculator`, `CircuitBreaker` | 执行 IPO、报价、买卖结算、风险保护 |
| AI 调控层 | `AIScheduler`, `OnnxModelRunner` | 构造 $18$ 维观测、运行 ONNX、写回 $b_t,k_t$ |
| 并发控制层 | `ItemOperationCoordinator` | 按 `item_hash` 串行化交易与 AI 调参 |
| 持久化层 | `ItemMarketRepository`, `DatabaseManager` | 存储市场状态、交易日志、金库与审计记录 |

### 6.2 Online Data Flow
玩家 SELL 给系统的主路径可写成

$$
\text{ItemStack} \rightarrow \text{hash} \rightarrow \text{IPO check} \rightarrow \text{quote} \rightarrow \text{treasury reserve} \rightarrow \text{payout} \rightarrow \text{settlement} \rightarrow \text{logging}.
$$

其中 `AMMCalculator` 负责报价，仓储层负责金库预留和库存写回；只有预留成功后才会实际支付。这样做的目的是先锁定稀缺资源，再执行玩家侧副作用。

#### 6.2.2 BUY Path
玩家 BUY 自系统的主路径可写成

$$
\text{request} \rightarrow \text{risk check} \rightarrow \text{physical reserve} \rightarrow \text{withdraw} \rightarrow \text{delivery} \rightarrow \text{settlement}.
$$

BUY 路径先检查风控，再原子预留真实库存，最后扣款发货。SELL 的稀缺约束是钱，BUY 的稀缺约束是货，因此两条路径分别预留金库和实物库存。

#### 6.2.3 AI Cycle Path
AI 调控周期与交易周期正交。其主路径可写成

$$
\text{load items} \rightarrow \text{query macro stats} \rightarrow \text{build } o_t \rightarrow \text{ONNX} \rightarrow \text{activity gate} \rightarrow \text{write } (b_{t+1},k_{t+1}) \rightarrow \text{audit}.
$$

`AIScheduler` 周期性读取市场状态和统计量，逐个构造 $o_t$ 并调用 `OnnxModelRunner`。这条路径与交易路径共享同一串行化机制，因此同一个物品不会在成交与调参之间出现竞态写入。

### 6.3 Persistence Model
`DatabaseManager` 当前维护 schema version 3。核心表结构如下。

| 表 | 作用 |
| --- | --- |
| `ecobrain_items` | 存储单品市场状态 $b_t,k_t,I_t^\star,I_t,S_t$ 及建档时间 |
| `ecobrain_treasury` | 存储全局金库余额 |
| `ecobrain_risk` | 存储日开盘价、冻结状态等风险信息 |
| `ecobrain_trade_stats` | 存储所有真实交易日志，是 TWAP、AOV、回放训练的基础 |
| `ecobrain_player_transactions` | 存储玩家维度的买卖流水 |
| `ecobrain_ai_tuning_events` | 存储 AI 调参审计记录 |
| `ecobrain_reward_claims` | 存储奖励领取信息 |
| `ecobrain_system_money_reclaims` | 存储系统资金回收历史 |

其中最关键的事实表是 `ecobrain_trade_stats`，因为它同时支撑线上统计与离线回放训练。

## 7. PPO Continuous Reinforcement Learning
### 7.1 MDP Construction
模拟器环境 `EcoBrainEnv` 采用 Gymnasium 接口，每个 `step()` 近似对应线上一次 AI 调控周期。状态为与线上完全一致的 $18$ 维观测，动作空间为

$$
a_t \in [-1,1]^2,
$$

分别控制底价倍率与 $k$ 的增量。若记策略网络输出的连续动作分布为

$$
a_t \sim \mathcal{N}(\mu_\theta(o_t), \mathrm{diag}(\sigma_\theta^2(o_t))),
$$

则 PPO 学到的是一个连续控制器，而不是离散规则选择器。每个 episode 会在 IPO 场景、成熟物品场景以及不同市场 regime 之间随机初始化，以提高策略对不同服务器节奏的适应性。

### 7.2 Replay and Domain Randomization
若提供插件导出的 CSV，训练进入半回放模式。环境只回放交易事件的方向与数量，不回放历史价格标签；成交价格始终由当前 AMM 状态重新计算。这保证训练目标是“在真实交易节奏下做更优控制”，而不是监督式地复刻过去价格。

若没有真实数据，模拟器会通过 `quiet`、`normal`、`event_buying`、`dumping` 等 regime 以及多类玩家原型进行 domain randomization。其目标是让策略对转移核分布鲁棒，而不是记住单一服务器。

### 7.3 PPO Objective
设旧策略为 $\pi_{\theta_{\mathrm{old}}}$，优势函数为 $\hat A_t$，则概率比定义为

$$
r_t(\theta)=\frac{\pi_\theta(a_t\mid o_t)}{\pi_{\theta_{\mathrm{old}}}(a_t\mid o_t)}.
$$

PPO 的 clipped surrogate objective 写为

$$
L^{\mathrm{PPO}}(\theta)=
\mathbb{E}_t\left[
\min\left(
r_t(\theta)\hat A_t,\;
\mathrm{clip}(r_t(\theta),1-\epsilon,1+\epsilon)\hat A_t
\right)
\right].
$$

实际优化时再叠加价值函数损失与熵正则。选择 PPO 的原因很直接：动作是连续的，训练数据带噪且非平稳，而 PPO 在这种低维连续控制问题上通常比更激进的策略梯度方法稳定。

### 7.4 Reward Design
奖励函数不是简单追求成交额，而是显式平衡成交、通胀、库存与动作平滑性：

$$
r_t=
w_{\mathrm{trade}}R_t^{\mathrm{trade}}
-w_{\mathrm{infl}}R_t^{\mathrm{infl}}
-w_{\mathrm{inv}}R_t^{\mathrm{imbalance}}
+R_t^{\mathrm{band}}
-w_{\mathrm{act}}|a_t|_1
-R_t^{\mathrm{stockout}}.
$$

其中 $R_t^{\text{trade}}$ 奖励有效成交，$R_t^{\text{infl}}$ 惩罚净印钞，$R_t^{\text{imbalance}}$ 惩罚库存偏离，$R_t^{\text{band}}$ 约束价格带，$|a_t|_1$ 抑制过激动作，$R_t^{\text{stockout}}$ 惩罚高价值物品长期断供。奖励设计的关键思想是让 PPO 学到“可成交但不过热、可调价但不失锚”的控制策略。

### 7.5 Curriculum and Export
当训练 `mixed` 单模型时，`train.py` 默认使用课程式顺序

$$
\text{low} \rightarrow \text{mid} \rightarrow \text{high} \rightarrow \text{mixed},
$$

并按比例 `(0.15, 0.20, 0.20, 0.45)` 分配时长。课程学习先让策略掌握局部价格世界，再学习跨世界泛化。

训练产物包括策略权重 `ecobrain_ppo_value.zip`、归一化统计 `ecobrain_ppo_value_vecnormalize.pkl` 与推理模型 `ecobrain_value.onnx`。若启用 `VecNormalize(norm_obs=True)`，观测归一化会被烘焙到 ONNX 中，因此线上插件继续输入原始 $18$ 维观测即可：

$$
\text{ONNX}(\text{raw obs}) = \text{action}.
$$

这一步的意义在于保证训练部署一致性，避免线上线下在观测归一化上发生语义漂移。

## 8. Conclusion
EcoBrain 的核心不是“自动涨跌价”，而是把系统市场拆成两层控制。底层由动态 AMM 决定逐笔成交价，上层由 PPO 连续策略缓慢调节 $b_t$ 与 $k_t$。前者负责微观流动性与滑点，后者负责长期价格中枢与曲线弹性。

因此，EcoBrain 更适合被理解为一个受约束的市场控制器：它既不是静态价表，也不是让神经网络直接逐笔报价，而是用解析式市场机制与连续强化学习共同完成价格发现。

## Appendix A. Reproducibility Notes
若希望复现实验或继续训练，有三点最重要。

1. Java 插件与 Python 模拟器必须保持相同的调度周期、动作边界、金库初值、目标库存平滑参数和库存保护线。
2. 若修改观测语义、动作映射、税费机制、自适应目标库存逻辑、数据回放逻辑或玩家生态模型，应重新从头训练，而不是直接沿用旧 checkpoint。
3. 线上导出的训练数据是事实日志，而不是标签集；它适合做事件驱动强化学习，不适合被误解为监督学习标注。
