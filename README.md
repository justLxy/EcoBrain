# EcoBrain 3.0
## Abstract
EcoBrain 是一个面向 Minecraft 服务器的单模型动态经济系统。它把价格发现写成一个受约束的控制问题，而不是一个管理员手工调参问题。系统在线上只做推理，在离线只做训练；线上交易由虚拟自动做市商决定瞬时价格，离线策略只负责较慢尺度上的参数调节。若系统状态记为

$$
x_t=(b_t,k_t,I_t^\star,I_t,S_t,T_t),
$$

其中 $b_t$ 为底价，$k_t$ 为曲线敏感度，$I_t^\star$ 为目标库存，$I_t$ 为虚拟库存，$S_t$ 为真实库存，$T_t$ 为系统金库，则 EcoBrain 的核心任务是学习策略

$$
a_t=\pi_\theta(o_t), \qquad x_{t+1}=f(x_t,a_t,e_t),
$$

使系统在真实交易事件 $e_t$ 驱动下，兼顾交易活跃性、库存稳定性、资金守恒与价格可解释性。与常见“动态商店”相比，EcoBrain 的设计重点不在于“让价格会动”，而在于确保每一次价格变化都由真实库存、真实成交和真实资金能力共同约束。

## 1. Introduction
Minecraft 服务器经济系统通常面临三个长期问题。第一，价格往往由管理员预设，因此“动态”只是对静态价格表做局部扰动，而不是对稀缺性做真实响应。第二，很多系统没有严格的库存或资金约束，导致价格信号被无限供给或无限印钞污染。第三，训练环境与线上环境经常不是同一个问题，模型在离线看见的状态与线上接收的状态不一致，最终出现“模型没坏，但部署题目变了”的情况。

EcoBrain 针对这三个问题给出一套统一回答。

- 它把单品市场建模为受控动态系统，价格由库存曲线和交易事件共同决定，而不是由固定价表决定。
- 它用全局金库守恒约束系统支付能力，使 SELL 不再等同于“插件无限收购”。
- 它用一套固定的 $18$ 维观测和 $2$ 维动作作为 Java 插件与 Python 模拟器的共同契约，减少训练部署错位。
- 它明确区分“快变量”和“慢变量”：逐笔交易价格由 vAMM 处理，AI 只调 $b_t$ 与 $k_t$。

这种分工的动机很直接。若 AI 直接为每一笔交易报价，控制问题会同时承受玩家到达的不确定性、库存离散跳变和作弊压力，难以稳定学习；而把逐单定价交给解析式市场机制，把 AI 退到较慢周期做宏观调节，就能把学习任务约束在一个更稳定、更可解释的空间里。

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

### 2.2 Design Principles
EcoBrain 的几条设计原则可以概括为以下命题。

1. 价格必须来自约束，而不是来自信念。新物品的初始价格不能信任首个卖家，因此首单只能触发建档，不能决定公允价值。
2. 稀缺性与可交付性必须分离。虚拟库存负责价格弹性，真实库存负责是否真的能卖出货物，这两者若混为一谈，曲线灵敏度与库存风控会互相污染。
3. AI 只负责慢变量。若 AI 逐笔定价，系统会变成一个高频对抗问题；若 AI 只调 $b_t$ 与 $k_t$，则市场机制本身已经提供足够强的微观响应。
4. 训练和部署必须共享同一语义契约。单模型、固定观测顺序、固定动作映射是为了降低工程复杂度，而不是为了追求某种“纯粹性”。

## 3. System Overview
EcoBrain 由一个线上 Java 插件和一个离线 Python 模拟器组成。两者共享市场语义，但职责严格区分。

$$
\text{Trade Logs} \rightarrow \text{Replay / Simulation} \rightarrow \text{PPO} \rightarrow \text{ONNX} \rightarrow \text{Online Inference}.
$$

线上插件负责三件事：处理玩家交互、执行 vAMM 交易与资金结算、周期性构造观测并调用 `ecobrain_value.onnx`。离线模拟器负责三件事：重建市场动力学、定义奖励函数、训练并导出 ONNX 模型。这样的拆分有两个好处。

- 它避免在生产服上做训练，降低性能与安全风险。
- 它允许训练端引入更丰富的探索和日志分析，而不污染线上逻辑。

### 3.1 Single-Brain Policy
EcoBrain 在线上只使用一个模型，而不是为 `low`、`mid`、`high` 物品分别路由不同模型。离线训练中的 `low / mid / high` 只是潜在世界 $z$ 的采样标签，用于组织环境参数与奖励带，而不是线上推理时的真实输入：

$$
z \in \{\text{low},\text{mid},\text{high}\}, \qquad z \notin o_t.
$$

这样做的动机不是“模型越少越高级”，而是因为线上路由会引入另一个分类错误源。若先分类、再定价，那么错误可能来自价格模型，也可能来自分类模型；而单模型把问题统一为“在连续状态空间上做控制”，减少了系统边界上的误差传播。

代价也存在：单模型必须同时处理低价、高价和极端库存场景，因此观测必须提供足够稳定的锚点，例如 $\log P_t$、$\log \mathrm{TWAP}_t$ 与 $\log T_t$。这也是 EcoBrain 强调观测设计而不是仅强调网络结构的原因。

### 3.2 Default Runtime Regime
仓库当前附带的核心运行参数如下，这些值构成本文讨论的默认制度环境。

| 参数 | 当前值 | 作用 |
| --- | --- | --- |
| `economy.ipo.zero-trust` | `true` | 开启零信任 IPO |
| `economy.treasury.initial-balance` | `500000` | 冷启动金库规模 |
| `ai.schedule-minutes` | `15` | AI 调控周期 |
| `ai.aov-window-hours` | `24` | 动态客单价窗口 |
| `ai.tuning.base-price-max-percent` | `0.12` | 单周期底价最大相对变化 |
| `ai.tuning.k-delta` | `0.10` | 单周期 $k$ 最大改变量 |
| `ai.tuning.inactivity-action-decay` | `0.35` | 无活动时动作衰减 |
| `ai.tuning.k-min` / `k-max` | `0.2` / `6.0` | $k$ 的硬边界 |
| `ai.tuning.max-base-price` | `100000.0` | 底价硬上界 |
| `circuit-breaker.critical-inventory` | `1` | 买入库存保护线 |
| `ai.garbage-collection-days` | `3` | 垃圾清理阈值 |

这些数值不是“最佳参数”的宣称，而是一个工程折中。它们一方面限制策略动作幅度，防止单周期暴冲；另一方面保留足够大可调空间，使模型还能表达不同服务器节奏下的策略差异。

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
EcoBrain 同时维护 $I_t$ 与 $S_t$，这是整个系统最关键的设计之一。

- $I_t$ 只负责定价。它是曲线上的虚拟流动性，不要求与仓库数量相等。
- $S_t$ 只负责交付与风控。它决定玩家能否真的买到货，也决定大额 SELL 是否触发额外税。

若把二者合并，系统会陷入两难。库存太小时，曲线要变得敏感；但真实仓库太小时，系统又需要保护交付能力。把这两种需求写在同一个变量上，会导致价格曲线为了风控而变形，或风控为了价格连续性而失效。分离之后，价格弹性和履约能力各自拥有清晰语义。

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

这个设计同时解决两类问题。其一，当当前价显著偏离 $\mathrm{TWAP}_t$ 时，系统认为市场处于高波动或已偏离真实成交锚点，于是提高 SELL 税来抑制短期套现。其二，当玩家试图在极小真实库存下做超大规模抛售时，系统把这解释为异常供给冲击，而不是正常市场行为。直接拒绝所有大单会损失连续性，因此 EcoBrain 选择“允许但极重税”的方案，在保持流动性的同时提高操纵成本。

### 4.5 Treasury Conservation
EcoBrain 的支付能力由单一全局金库 $T_t$ 控制，而不是默认无限收购。若周期内 BUY 交易收入为 $B_t$，SELL 支出为 $S_t^{\text{pay}}$，则金库演化满足

$$
T_{t+1}=T_t + B_t - S_t^{\text{pay}}.
$$

当某笔 SELL 所需支付超过当前金库余额时，交易会被拒绝。这个约束的意义并不只是“更真实”，而是把价格信号与货币信号重新绑定起来。若系统能无限付款，则玩家对系统 SELL 的意愿不再体现稀缺性判断，只体现“能否从插件稳定取钱”；此时市场会变成印钞机。金库守恒把这种虚假需求从机制层面切断。

### 4.6 Circuit Breaking and Inventory Protection
买入路径受到三类硬约束。

第一类是冻结约束：若某物品被风险表标记为冻结，则直接拒绝买入。第二类是虚拟库存约束：若 $I_t$ 过低，则不允许继续 BUY，防止曲线在接近零库存时进入极端区间。第三类是真实库存保护：买后若 $S_t-N$ 低于临界库存线，则交易被拒绝。

此外，系统还维护日内开盘价 $P_t^{\text{open}}$。若日涨跌幅

$$
\rho_t=\frac{P_t-P_t^{\text{open}}}{P_t^{\text{open}}}
$$

超过阈值 $\lambda_{\text{day}}$，则物品可进入冻结状态。理论上这是为了防止异常行情扩散；工程上，仓库自带配置当前把该阈值设得非常宽松，因此这一机制更像一个预留保险丝，而不是默认高频触发的主规则。

### 4.7 Adaptive Target Inventory
目标库存 $I_t^\star$ 不应被视为不可变常数，因为真实服务器会长期演化。EcoBrain 因此引入数量感知的指数平滑更新。若某次交易后真实库存变为 $S_{t+1}$，单笔交易量为 $q_t$，则先定义

$$
m_t=\min(q_t,q_{\max}), \qquad \alpha_t^{\mathrm{eff}}=1-(1-\alpha)^{m_t},
$$

再更新目标库存

$$
I_{t+1}^\star=\mathrm{round}\left(I_t^\star + \alpha_t^{\mathrm{eff}}(S_{t+1}-I_t^\star)\right).
$$

仅更新 $I_t^\star$ 还不够。若目标库存改变而虚拟库存 $I_t$ 保持不动，价格会因为分母结构而产生非交易性跳变。为保持相对饱和度近似不变，EcoBrain 同时做比例缩放：

$$
I_{t+1}=\max\left(1,\mathrm{round}\left(I_t\frac{I_{t+1}^\star}{I_t^\star}\right)\right).
$$

这个细节看似小，实际上非常关键。它使“目标库存变化”被解释为世界结构的长期漂移，而不是一次瞬时市场冲击。

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

观测设计的总体思想是“给模型足够多的锚点，但不给它多余标签”。例如系统没有把 `low / mid / high` 作为线上输入，因为那会把一部分价格决定权提前交给一个离散分类器；相反，它用 $\log P_t$、$\log \mathrm{TWAP}_t$、$\log I_t^\star$ 等连续特征让模型自己判断当前所处的价格世界。

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

为什么不是“无交易就完全不动”？因为冷启动和长尾物品需要缓慢回归，而不是永久冻结。为什么又不能“无交易也满强度调价”？因为那会让模型在没有市场证据时胡乱试探。于是 EcoBrain 选了折中路线：允许小幅动作，但不允许高强度动作。

## 6. Plugin Architecture
这一章只讨论插件侧结构，即 `src/` 中 Java 代码如何把市场机制、模型推理、持久化与用户交互组合成一个可运行系统。

### 6.1 Module Partition

| 模块 | 代表类或目录 | 主要职责 | 设计理由 |
| --- | --- | --- | --- |
| 插件入口层 | `EcoBrainPlugin` | 装配依赖、注册命令和监听器、启动与重启 `AIScheduler` | 把生命周期管理集中到单点，降低热更新时的状态漂移风险。 |
| 市场服务层 | `MarketService`, `AMMCalculator`, `CircuitBreaker`, `EconomyService` | 处理 IPO、报价、库存结算、风控、Vault 交互 | 把“市场规则”从 GUI 和命令中剥离，避免多入口出现逻辑分叉。 |
| AI 推理层 | `AIScheduler`, `OnnxModelRunner` | 周期提取观测、执行 ONNX 推理、更新 $b_t$ 与 $k_t$、写审计日志 | 让 AI 成为一个可替换、可审计的独立模块，而不是把推理散落在交易路径里。 |
| 并发控制层 | `ItemOperationCoordinator` | 按 `item_hash` 对交易和 AI 调参做串行化 | 同一物品的买卖与调参若并发写库，会出现库存和参数竞争条件。 |
| 持久化层 | `DatabaseManager`, `ItemMarketRepository` | 维护 SQLite schema、交易日志、金库、风险表、奖励表、导出训练数据 | 用仓储层把 SQL 细节与业务决策分离，便于复用统计查询。 |
| 交互层 | `EcoBrainCommand`, `AdminCommand`, `gui/`, `listener/` | 命令入口、GUI、批量出售、市场视图、主手提示 | 把玩家交互与市场规则解耦，让前端入口只负责组织操作流程。 |
| 扩展层 | `rewards/`, `placeholder/` | 奖励、排行榜、PlaceholderAPI 对接 | 保持核心交易闭环独立，同时允许服务器做展示和激励扩展。 |

### 6.2 Online Data Flow
插件架构的关键不是“模块有多少”，而是数据沿什么方向流动。EcoBrain 的设计目标是让每一条交易路径都有清晰的状态边界。

#### 6.2.1 SELL Path
玩家 SELL 给系统的主路径可写成

$$
\text{ItemStack} \rightarrow \text{hash} \rightarrow \text{IPO check} \rightarrow \text{quote} \rightarrow \text{treasury reserve} \rightarrow \text{payout} \rightarrow \text{settlement} \rightarrow \text{logging}.
$$

顺序上具体包含以下环节。

1. 交互层把物品序列化为 `item_base64`，再计算 `item_hash`，确保同品类映射到同一市场键。
2. `MarketService` 检查物品是否已有市场记录；若没有，则执行零信任 IPO 建档。
3. `AMMCalculator` 基于当前 $b_t,k_t,I_t^\star,I_t$ 计算 SELL 报价，并引入 $\mathrm{TWAP}_t$ 与防倾销税。
4. 仓储层先从金库中原子预留资金；若预留失败，则该 SELL 直接终止。
5. 主线程完成物品扣除与玩家发钱。
6. 结算阶段写入新的虚拟库存、真实库存、交易日志与玩家流水；若启用自适应目标库存，则同步更新 $I_t^\star$。

之所以要先预留金库、再发钱，是因为“发钱失败后回滚物品”比“预留失败后拒绝交易”更复杂且更不可靠。EcoBrain 把最脆弱的约束提前处理，减少跨线程回滚。

#### 6.2.2 BUY Path
玩家 BUY 自系统的主路径可写成

$$
\text{request} \rightarrow \text{risk check} \rightarrow \text{physical reserve} \rightarrow \text{withdraw} \rightarrow \text{delivery} \rightarrow \text{settlement}.
$$

这里的关键步骤与 SELL 路径不同。

1. 系统先验证真实库存 $S_t$ 是否足够，并检查冻结、虚拟库存保护、买后库存保护。
2. 仓储层原子预留真实库存，避免并发超卖。
3. 主线程检查背包空间并扣款，然后发货。
4. 只有发货成功后，系统才把 BUY 记入交易日志，并把对应金额计入金库收入。

这一路径体现了 EcoBrain 对“可交付性”的重视。SELL 面临的稀缺约束是钱，BUY 面临的稀缺约束是货，因此两条路径的预留对象必然不同。

#### 6.2.3 AI Cycle Path
AI 调控周期与交易周期正交。其主路径可写成

$$
\text{load items} \rightarrow \text{query macro stats} \rightarrow \text{build } o_t \rightarrow \text{ONNX} \rightarrow \text{activity gate} \rightarrow \text{write } (b_{t+1},k_{t+1}) \rightarrow \text{audit}.
$$

其中 `AIScheduler` 会周期性读取全量物品、聚合全局 AOV、净印钞和单品流速，再逐个构造观测并调用 `OnnxModelRunner`。这条路径被 `ItemOperationCoordinator` 与交易路径按 `item_hash` 串行化，因此同一个物品不会在“交易写库”和“AI 调参写库”之间产生竞态。

### 6.3 Persistence Model
`DatabaseManager` 当前维护 schema version 3。核心表结构如下。

| 表 | 作用 |
| --- | --- |
| `ecobrain_items` | 存储单品市场状态 $b_t,k_t,I_t^\star,I_t,S_t$ 及建档时间 |
| `ecobrain_treasury` | 存储全局金库余额 |
| `ecobrain_risk` | 存储日开盘价、冻结状态等风险信息 |
| `ecobrain_trade_stats` | 存储所有真实交易日志，是 TWAP、AOV、回放训练的基础 |
| `ecobrain_player_transactions` | 存储玩家维度的买卖流水，用于排行榜和资金回收 |
| `ecobrain_ai_tuning_events` | 存储 AI 调参审计记录 |
| `ecobrain_reward_claims` | 存储奖励领取信息 |
| `ecobrain_system_money_reclaims` | 存储系统资金回收历史 |

这套持久化设计的意义不只是“把数据存下来”。它同时承担三类职责：线上恢复、离线统计、离线训练。尤其是 `ecobrain_trade_stats`，它既支撑实时 $\mathrm{TWAP}_t$ 和 $\mathrm{AOV}_t$，又支撑数据导出与回放训练，因此被设计成最基础的事实表。

## 7. Offline Training Methodology
### 7.1 Environment Design
模拟器环境 `EcoBrainEnv` 采用 Gymnasium 接口，每个 `step()` 近似对应线上一次 AI 调控周期。环境动作空间为 `[-1,1]^2`，观测空间与线上完全对齐为 $18$ 维，目的是把训练问题定义为“在同一语义下学习”，而不是“先在一个近似世界学会，再祈祷线上还能用”。

每个 episode 会先抽取一个潜在价值世界 $z$，再初始化单品市场状态。若进入 IPO 分支，则 $b_0=100$；若进入成熟物品分支，则从各自价格带采样 $b_0$。这一步的动机，是避免训练集永远从零信任起步而学不到成熟市场控制；但同时保留一定 IPO 概率，使策略不会忘记冷启动。

### 7.2 Domain Randomization and Player Ecology
如果没有真实数据集，模拟器会从若干玩家原型中构造生态。市场体制包含 `quiet`、`normal`、`event_buying`、`dumping` 四类 regime，玩家原型包含 `VeteranPlayer`、`NewPlayer` 和 `Arbitrageur`。这种设计的目标不是精确复刻某一台服务器，而是让模型在一族分布上学习，从而提高泛化能力。

这类随机化背后的理论动机，是把训练问题从“记住固定转移核”改为“在转移核分布上鲁棒”。换句话说，EcoBrain 不假设服务器永远处在同一个生态参数点，而是假设它在一个合理邻域中波动。代价是训练噪声更大；但对于一个希望迁移到不同服务器的价格控制器，这种噪声是有价值的。

### 7.3 Dataset Replay
若提供由插件导出的 CSV，训练将进入半回放模式。原始字段为 `item_hash`, `trade_type`, `quantity`, `total_price`, `created_at`。环境会先按调控周期把时间对齐到离散桶，再构建两类索引：单品时间桶序列和全局时间桶序列。

对每个 `item_hash`，定义平均单位成交价。若该物品累计成交总额为 $V_i$、累计成交数量为 $Q_i$，则

$$
p_i=\frac{V_i}{\max(1,Q_i)}.
$$

这个量只用于决定该物品更接近哪一类潜在价值世界，以及为 mixed 训练提供更均衡的样本抽样。若某物品事件数记为 $n_i$，则候选物品的采样概率按事件数加权：

$$
\Pr(i)=\frac{n_i}{\sum_j n_j}.
$$

被选中的物品再从其活跃区间随机抽取起始时间偏移，使训练不总从同一时间切片开始。

最重要的是：回放模式只把历史交易的方向与规模当作外生事件，不把历史价格当作监督标签。也就是说，若某一时间桶里历史上确实出现过一笔 BUY 数量 $q$，环境会把“出现 BUY, 数量为 $q$”作为事实输入，但这笔交易在当前模拟市场里的实际成交价格，仍由当前 $b_t,k_t,I_t^\star,I_t$ 决定。这样做的原因是，EcoBrain 训练的不是“复刻过去”，而是“在真实节奏下做更好的控制”。

### 7.4 Reward Design
模拟器的奖励不是简单的成交额最大化，而是多项因素的加权和。可抽象写为

$$
r_t=
w_{\mathrm{trade}}R_t^{\mathrm{trade}}
-w_{\mathrm{infl}}R_t^{\mathrm{infl}}
-w_{\mathrm{inv}}R_t^{\mathrm{imbalance}}
+R_t^{\mathrm{band}}
-w_{\mathrm{act}}\|a_t\|_1
-R_t^{\mathrm{stockout}}.
$$

其中各项含义如下。

- $R_t^{\text{trade}}$ 奖励有效交易信号，但不同价值世界采用不同尺度。低价和中价世界更偏向数量信号，高价世界保留压缩后的金额信号。
- $R_t^{\text{infl}}$ 惩罚净印钞率。这样可以阻止策略通过简单抬价来“洗高成交额”。
- $R_t^{\text{imbalance}}$ 惩罚库存偏离目标过大，推动系统回到合理饱和度附近。
- $R_t^{\text{band}}$ 约束价格长期停留在各自世界的可接受区间。
- $|a_t|_1$ 惩罚过于激进的控制动作，防止底价和 $k$ 被策略快速推向边界。
- $R_t^{\text{stockout}}$ 在高价值世界额外惩罚被买空，反映“稀缺物品不能长期断供”的需求。

这个奖励结构透露出 EcoBrain 的立场：一个“成交很多但持续印钱、库存失衡、价格脱锚”的经济系统不是好系统。换句话说，交易活跃性只是目标之一，而不是唯一目标。

### 7.5 Curriculum and Export Contract
当训练 `mixed` 单模型时，`train.py` 默认使用课程式顺序

$$
\text{low} \rightarrow \text{mid} \rightarrow \text{high} \rightarrow \text{mixed},
$$

并按比例 `(0.15, 0.20, 0.20, 0.45)` 分配时长。其动机是先学局部、再学整合，避免模型在一开始同时面对所有世界而无法形成稳定策略。

训练产物包括策略权重 `ecobrain_ppo_value.zip`、归一化统计 `ecobrain_ppo_value_vecnormalize.pkl` 与推理模型 `ecobrain_value.onnx`。若启用 `VecNormalize(norm_obs=True)`，观测归一化会被烘焙到 ONNX 中，因此线上插件继续输入原始 $18$ 维观测即可：

$$
\text{ONNX}(\text{raw obs}) = \text{action}.
$$

这一步看似只是导出细节，实际上是在修复训练部署一致性问题。若线上和线下各自归一化一次，或者一端归一化一端不归一化，模型学到的控制规律就会被输入尺度差异破坏。

## 8. Conclusion
EcoBrain 的核心贡献，不是把 AI 强行塞进商店，而是把一个面向玩家的经济系统整理成了一个可训练、可部署、可审计的控制问题。它用零信任 IPO 切断首单锚定，用虚拟库存和真实库存分离保证价格弹性与履约能力各自独立，用全局金库守恒阻止无限印钞，用固定观测动作契约保证训练部署一致，用交易日志半回放训练让模型在真实服务器节奏下学习，而不是在静态价格标签上拟合。

如果把 EcoBrain 写成一句话，它更像一个“受约束的市场控制器”，而不是一个“会自动涨跌价的商店插件”。这也是它和传统动态经济插件最根本的区别。

## Appendix A. Reproducibility Notes
若希望复现实验或继续训练，有三点最重要。

1. Java 插件与 Python 模拟器必须保持相同的调度周期、动作边界、金库初值、目标库存平滑参数和库存保护线。
2. 若修改观测语义、动作映射、税费机制、自适应目标库存逻辑、数据回放逻辑或玩家生态模型，应重新从头训练，而不是直接沿用旧 checkpoint。
3. 线上导出的训练数据是事实日志，而不是标签集；它适合做事件驱动强化学习，不适合被误解为监督学习标注。

## Appendix B. Extended Plugin Components
虽然本文重点在市场机制与训练闭环，但插件还包含若干外围模块，用于把核心系统接入服务器生态。

- `gui/` 提供批量出售、市场查看和排行榜界面。
- `listener/` 提供批量出售监听、主手市场提示、市场 GUI 交互和 Q 键快速出售入口。
- `rewards/` 提供奖励定义、领取记录与 GUI 展示。
- `placeholder/` 提供排行榜和个人统计的 PlaceholderAPI 接口。
- `AdminCommand` 提供导出训练数据、冻结市场、清榜和系统资金回收等管理功能。

这些模块都没有改变本文给出的经济学核心方程；它们的角色是把核心机制暴露成服务器可以实际使用、观察和维护的工具面板。
