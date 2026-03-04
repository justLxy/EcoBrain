"""
EcoBrain 2.0 模拟器全局配置文件 (Simulator Configuration)
在这里你可以自定义训练时的各类物品参数，而无需修改底层的 Python 环境代码。
修改后再次运行 `python train.py` 即可生效。
"""

# ==========================================
# 动作空间限制 (Action Space Limits) - 对齐插件端 config.yml
# ==========================================
# 控制 AI 单次决策所能带来的最大改变幅度。
ACTION_BASE_PRICE_MAX_PERCENT = 1.00  # 底价单次最大涨跌幅 (1.00 = 100%)
ACTION_K_FACTOR_MAX_DELTA = 1.00      # K 因子单次最大微调幅度

# 说明：
# 2.0 初版曾引入“每周期涨跌停”（PER_CYCLE_MAX_CHANGE_PERCENT）作为二次拦截；
# 当前版本按线上要求移除该二次夹紧，只保留动作映射上限 ACTION_BASE_PRICE_MAX_PERCENT。
MAX_BASE_PRICE = 1_000_000.0          # 对齐 ai.tuning.max-base-price
MIN_BASE_PRICE = 0.01                # AI 可干预的全局最低底价（允许砸穿 IPO）

K_MIN = 0.2                          # 对齐 ai.tuning.k-min
K_MAX = 10.0                         # 对齐 ai.tuning.k-max

# ==========================================
# AI 调度与观测归一 (Align obs with AIScheduler)
# ==========================================
# 一个 step 代表插件一次 AI 微观调控周期（默认 15 分钟）
SCHEDULE_MINUTES = 15                # 对齐 ai.schedule-minutes
AOV_WINDOW_HOURS = 24                # 对齐 ai.aov-window-hours
IPO_BASE_PRICE_FALLBACK = 100.0      # 对齐 economy.ipo.base-price（zero-trust=false 时）

# ==========================================
# Episode 初始化：IPO vs Mature Item 混合
# ==========================================
# 真实服务器里只有“新物品”才会走 100.0 的 zero-trust IPO；
# 大多数存量物品处于 Mature 状态（base_price 已经被市场发现）。
# 训练时每个 episode 相当于抽样一种“物品状态”，避免总是从 100.0 开局导致学不到区间定价。
IPO_RESET_PROB = 0.05  # 每次 reset 抽到 IPO 物品的概率（其余为 Mature）

# ==========================================
# Observation feature toggle
# ==========================================
# The last observation feature was originally `is_ipo_flag` (0/1).
# For learning stable pricing bands, the agent needs to "see" absolute price level.
# If enabled, we replace the last feature with log(current_price).
OBS_USE_LOG_PRICE = True

# ==========================================
# 风控参数（对齐 CircuitBreaker）
# ==========================================
DAILY_LIMIT_PERCENT = 10.0           # 对齐 circuit-breaker.daily-limit-percent（注意：插件侧是“倍数”，非百分数）
CRITICAL_INVENTORY = 1               # 对齐 circuit-breaker.critical-inventory

# ==========================================
# vAMM 税费（对齐 AMMCalculator.calculateDynamicSpread）
# ==========================================
BASE_SPREAD = 0.05                   # 5% 基础印花税
MAX_SPREAD = 0.999                   # 插件端最高可扣到 99.9%
DUMPING_TAX_TRIGGER_MULTIPLIER = 3.0 # 超过 physicalStock * 3 开始征税
DUMPING_TAX_PER_MULTIPLE = 0.10      # 每超过物理库存 1 倍，额外增加 10% 税

# ==========================================
# 目标库存自适应设置 (Adaptive Target)
# ==========================================
ADAPTIVE_TARGET_ENABLED = True
ADAPTIVE_TARGET_SMOOTHING_FACTOR = 0.05

# ==========================================
# Reward 权重（离线训练用；插件端不在线计算 reward）
# ==========================================
# 说明：为了减少“训练时用绝对金额、线上用归一化通胀率”造成的尺度断裂，这里用 inflation_rate（=netEmission/AOV）
REWARD_TRADE_VALUE_WEIGHT = 0.001     # 交易额越大越好（轻权重避免数值爆炸）
# For low-value items, rewarding "money volume" tends to incentivize higher prices.
# Use quantity-based trade signal instead.
REWARD_TRADE_QTY_WEIGHT = 0.05
# For mid-value items, also prefer quantity-based trade signal to avoid learning
# "raise price to get more volume" as a shortcut.
REWARD_TRADE_QTY_WEIGHT_MID = 0.05
# For high-value items, keep a money-based signal but compress it to avoid runaway
# incentives at extreme prices.
REWARD_TRADE_LOG_VALUE_WEIGHT_HIGH = 5.0
REWARD_INFLATION_RATE_WEIGHT = 2500.0   # 惩罚净印钞率（>0 时）
REWARD_INVENTORY_IMBALANCE_WEIGHT = 10.0  # 库存偏离惩罚
# Low-value items often have strong exogenous sell pressure in the simulator.
# Inventory imbalance can become largely uncontrollable (players decide to sell/buy mostly independent of price),
# so keep this weight modest to avoid making the task unsolvable.
REWARD_INVENTORY_IMBALANCE_WEIGHT_LOW = 30.0

# Reward 数值稳定性：
# - 先做线性缩放把回报压到合理量级（避免 value_loss 爆炸）
# - 再做可选裁剪防极端 outlier
REWARD_SCALE = 1e-3
REWARD_CLIP_ABS = 1e4  # 设为 None 可关闭裁剪

# Penalize overly aggressive control actions (helps avoid pushing base_price/k to extremes early).
REWARD_ACTION_L1_WEIGHT = 200.0

# ==========================================
# 各阶级物品参数 (Item Tier Parameters)
# ==========================================
TIERS = {
    # 极品/高价值物品 (如: 下界之星, Boss掉落物)
    "high": {
        "target_inventory": 32,        # 理想库存（你服情：高价值物品也会有一定存量）
        # 注：在模拟器里 current_inventory 用作初始真实库存 physical_stock（虚拟库存池会在 reset 时初始化为 target_inventory）
        # 对齐线上常态：初始真实库存尽量贴近 target，减少“开局缺货”噪声
        "current_inventory": 32,
        # Mature 物品初始底价（reset 时非 IPO 用）：按 hard 区间采样
        "initial_base_price": {"dist": "loguniform", "low": 10000.0, "high": 1_000_000.0},
        # 限制“走捷径”：高价值允许更灵活的曲线，但仍限制单步 k 变化与 k 上界
        "action_k_max_delta": 0.30,  # 每个 step 最大 k 微调幅度（建议 < 1.0，避免靠 k 把价格打穿/打飞）
        "k_min": 0.2,
        "k_max": 6.0,
        # 目标：底线物理放开至 0.01，但长期希望回到 15000~1e8 的价值带
        "price_min": 0.01,
        "price_max": 100_000_000.0,
        "penalty_out_of_range": 2000.0,     # 越界惩罚（让步于通胀惩罚）
        # 奖励带：15000~1e8 奖励；允许跌破，轻微惩罚
        "reward_band_min": 15000.0,
        "reward_band_max": 100_000_000.0,
        "reward_in_band": 3000.0,
        "penalty_out_of_band": 1000.0,
        "empty_stock_penalty": 5000.0,      # 真实库存枯竭惩罚（防被买空）
    },
    
    # 中等价值物品 (如: 副本材料, 铁锭, 金锭)
    "mid": {
        "target_inventory": 640,      # (调高理想库存) 供大于求，放宽库存容忍度
        "current_inventory": 640,
        # Mature 物品初始底价（reset 时非 IPO 用）：按 hard 区间采样
        "initial_base_price": {"dist": "loguniform", "low": 1000.0, "high": 10000.0},
        # 限制“走捷径”：中价值以维稳为主，k 变化要更保守
        "action_k_max_delta": 0.20,
        "k_min": 0.2,
        "k_max": 4.0,
        # 硬约束范围彻底打开到底，下限为 0.01（越界轻微惩罚，兼容砸穿 IPO）
        "price_min": 0.01,
        "price_max": 10000.0,
        "penalty_out_of_range": 2000.0,     # 极大削弱越界惩罚（让步于通胀惩罚）
        # 奖励带：维持在 500~9000 奖励。带外极轻罚，确保 AI 面对通胀敢于一路砸盘
        "reward_band_min": 500.0,
        "reward_band_max": 9000.0,
        "reward_in_band": 3000.0,
        "penalty_out_of_band": 800.0,
    },
    
    # 低价值物品 (如: 泥土, 小麦, 圆石)
    "low": {
        "target_inventory": 1280,      # (大幅调高) 老玩家基建狂魔，低价值物品库存会极其恐怖
        "current_inventory": 1280,
        # Mature 物品初始底价（reset 时非 IPO 用）：按 hard 区间采样
        "initial_base_price": {"dist": "loguniform", "low": 1.0, "high": 1000.0},
        # 限制“走捷径”：低价值最容易靠 k 把价格压穿到接近 0，因此强收紧 k
        "action_k_max_delta": 0.10,
        "k_min": 0.2,
        "k_max": 3.0,
        # 目标：低价值物品底层完全放开至 0.01，长期保持在 1000 以下
        "price_min": 0.01,
        "price_max": 1000.0,
        # 奖惩带：10-500 之间奖励；超出该带则面临轻微惩罚
        "reward_band_min": 10.0,
        "reward_band_max": 500.0,
        "reward_in_band": 6000.0,
        # 核心改动：极大削弱带外惩罚，让 AI 面对通胀时敢于无视该惩罚一路砸盘
        "penalty_out_of_band": 500.0,
        # 软约束：极大削弱硬区间越界惩罚（允许 AI 为了遏制通胀砸穿所有底线）
        "penalty_out_of_range": 1500.0,
    }
}

# ==========================================
# 更鲁棒的通用生态：Domain Randomization（推荐开启）
# ==========================================
# 目标：让 AI 在训练中见到“不同服情的分布族”，而不是死记一套固定概率，从而显著提升泛化能力。
#
# 机制：
# - 每个 episode（reset）会先采样一个 market regime（活动/爆仓/平稳等）
# - 再对各类玩家 archetype 的参数做随机采样（beta/normal/loguniform/choice 等）
# - 最终生成一批玩家实例参与该 episode 的所有 step
#
# 注意：当你传入真实 CSV 数据时（ReplayPlayer），该随机化会自动关闭，优先回放真实服情。

ECOSYSTEM_RANDOMIZATION = {
    "enabled": True,
    # 每次 reset 都重新采样生态（推荐 True；如果你想更稳定，可以改 False）
    "resample_each_reset": True,
}

# 市场“状态/活动”分段：用乘子模拟版本更新、活动周、工作日/周末等节奏变化
MARKET_REGIMES = {
    # 养老服：总体交易节奏慢，且供大于求
    # 为了贴近 10-20 在线的小服，我们加入 "quiet"（更低频的常态），并降低整体强度。
    "quiet": {
        "weight": 0.25,
        "buy_prob_mult": 0.70,
        "sell_prob_mult": 0.80,
        "buy_amount_mult": 0.75,
        "sell_amount_mult": 0.80,
    },
    "normal": {
        "weight": 0.35,
        "buy_prob_mult": 0.85,
        "sell_prob_mult": 1.10,
        "buy_amount_mult": 0.95,
        "sell_amount_mult": 1.10,
    },
    # 偶发活动/周末：短期买压更强（给模型见识“少量需求爆发”）
    "event_buying": {
        "weight": 0.10,
        "buy_prob_mult": 1.35,
        "sell_prob_mult": 0.95,
        "buy_amount_mult": 1.20,
        "sell_amount_mult": 0.95,
    },
    # 倾销/清仓期：卖压更强（更多供给/打金/仓库清理）
    "dumping": {
        "weight": 0.30,
        "buy_prob_mult": 0.80,
        "sell_prob_mult": 1.45,
        "buy_amount_mult": 0.90,
        "sell_amount_mult": 1.60,
    },
}

# Archetype 分布化配置：
# - count: 本局该类玩家数量（含随机）
# - buy_prob/sell_prob: 建议用 beta（天然落在 0~1 且可控）
# - amount/balance: 建议用 loguniform（长尾更贴近真实）
#
# dist spec 支持：
# - {"dist":"beta","a":...,"b":...,"min":0,"max":0.99}
# - {"dist":"normal","mean":...,"std":...,"min":...,"max":...}
# - {"dist":"uniform","low":...,"high":...}
# - {"dist":"loguniform","low":...,"high":...,"integer":true}
# - {"dist":"int_uniform","low":...,"high":...}  # inclusive
# - {"dist":"choice","values":[...],"p":[...]}

SIMULATED_PLAYER_ARCHETYPES = {
    "high": [
        # 多个老玩家：既可能产出也可能买单（更真实：极品不是纯卖压/纯买压）
        {
            "type": "VeteranPlayer",
            # 10-20 在线：老玩家占大头，但总人数不会太夸张
            "count": {"dist": "int_uniform", "low": 3, "high": 6},
            # 服情校准：老玩家资产约 100w~500w
            "balance": {"dist": "loguniform", "low": 1_000_000, "high": 5_000_000},
            # 初始持仓 + 产出/消耗（供给来自“产出”，而不是无限卖出）
            "initial_item_inventory": {"dist": "int_uniform", "low": 0, "high": 3},
            "produce_lambda": {"dist": "uniform", "low": 0.00, "high": 0.08},
            "consume_lambda": {"dist": "uniform", "low": 0.00, "high": 0.05},
            # 极品仍会买，但频率更低（慢节奏）
            "buy_prob": {"dist": "beta", "a": 1.5, "b": 7.0, "min": 0.00, "max": 0.60},
            # 产出/抛售事件相对稀疏，但一旦出现仍可能卖（由 dumping regime 放大）
            "sell_prob": {"dist": "beta", "a": 1.2, "b": 10.0, "min": 0.00, "max": 0.45},
            "buy_amount": {"dist": "choice", "values": [1, 1, 2, 3]},
            "sell_amount": {"dist": "choice", "values": [1, 1, 1, 2]},
            # 只有持仓低于目标才会买；持仓高于阈值才会卖（更像真实玩家）
            "buy_inventory_target": {"dist": "int_uniform", "low": 0, "high": 2},
            "sell_inventory_threshold": {"dist": "int_uniform", "low": 0, "high": 4},
        },
        # 新玩家：买得起的不多
        {
            "type": "NewPlayer",
            # 养老服：新人更少
            "count": {"dist": "int_uniform", "low": 0, "high": 2},
            # 服情校准：新人资产约 50w~100w
            "balance": {"dist": "loguniform", "low": 500_000, "high": 1_000_000},
            "initial_item_inventory": {"dist": "int_uniform", "low": 0, "high": 1},
            "produce_lambda": {"dist": "uniform", "low": 0.00, "high": 0.03},
            "consume_lambda": {"dist": "uniform", "low": 0.01, "high": 0.08},
            "buy_prob": {"dist": "beta", "a": 1.0, "b": 25.0, "min": 0.00, "max": 0.15},
            "sell_prob": {"dist": "beta", "a": 1.0, "b": 60.0, "min": 0.00, "max": 0.05},
            "amount": {"dist": "choice", "values": [1, 1, 1, 2]},
            "buy_inventory_target": {"dist": "int_uniform", "low": 1, "high": 4},
            "sell_inventory_threshold": {"dist": "int_uniform", "low": 0, "high": 2},
        },
        # 倒爷：每局可能出现 0~1 个（不要太多，否则训练会变得“只学防倒爷”）
        {
            "type": "Arbitrageur",
            "count": {"dist": "int_uniform", "low": 0, "high": 2},
            # 倒爷通常比普通老玩家更有钱（更像压力测试角色）
            "balance": {"dist": "loguniform", "low": 1_000_000, "high": 10_000_000},
        },
    ],
    "mid": [
        {
            "type": "VeteranPlayer",
            # 养老服中价值：典型“供大于求”，老玩家数量更多、卖压更稳定
            "count": {"dist": "int_uniform", "low": 4, "high": 8},
            "balance": {"dist": "loguniform", "low": 1_000_000, "high": 5_000_000},
            "initial_item_inventory": {"dist": "int_uniform", "low": 16, "high": 128},
            # 供大于求：净产出略大于净消耗（但不再是“无限卖出”）
            "produce_lambda": {"dist": "uniform", "low": 0.20, "high": 0.90},
            "consume_lambda": {"dist": "uniform", "low": 0.05, "high": 0.35},
            "buy_prob": {"dist": "beta", "a": 1.0, "b": 22.0, "min": 0.00, "max": 0.15},
            "sell_prob": {"dist": "beta", "a": 10.0, "b": 1.7, "min": 0.35, "max": 0.99},
            "buy_amount": {"dist": "loguniform", "low": 1, "high": 48, "integer": True},
            # 10-20 在线：中价值倾销一般不会太离谱，但仍保留长尾
            "sell_amount": {"dist": "loguniform", "low": 32, "high": 1024, "integer": True},
            "buy_inventory_target": {"dist": "int_uniform", "low": 8, "high": 64},
            "sell_inventory_threshold": {"dist": "int_uniform", "low": 64, "high": 512},
        },
        {
            "type": "NewPlayer",
            # 养老服：新人少，需求端更稀薄
            "count": {"dist": "int_uniform", "low": 0, "high": 2},
            "balance": {"dist": "loguniform", "low": 500_000, "high": 1_000_000},
            "initial_item_inventory": {"dist": "int_uniform", "low": 0, "high": 64},
            "produce_lambda": {"dist": "uniform", "low": 0.00, "high": 0.25},
            "consume_lambda": {"dist": "uniform", "low": 0.10, "high": 0.60},
            "buy_prob": {"dist": "beta", "a": 2.0, "b": 10.0, "min": 0.01, "max": 0.70},
            "sell_prob": {"dist": "beta", "a": 2.5, "b": 9.0, "min": 0.00, "max": 0.55},
            "amount": {"dist": "loguniform", "low": 1, "high": 128, "integer": True},
            "buy_inventory_target": {"dist": "int_uniform", "low": 16, "high": 128},
            "sell_inventory_threshold": {"dist": "int_uniform", "low": 64, "high": 256},
        },
        {
            "type": "Arbitrageur",
            "count": {"dist": "int_uniform", "low": 0, "high": 2},
            "balance": {"dist": "loguniform", "low": 1_000_000, "high": 10_000_000},
        },
    ],
    "low": [
        # 低价值：大概率存在“自动化倾销”老玩家，但强度每局不同（关键：让 AI 学会适配不同倾销力度）
        {
            "type": "VeteranPlayer",
            # 养老服低价值：老玩家多、倾销更强
            "count": {"dist": "int_uniform", "low": 5, "high": 10},
            "balance": {"dist": "loguniform", "low": 1_000_000, "high": 5_000_000},
            "initial_item_inventory": {"dist": "int_uniform", "low": 256, "high": 4096},
            # 供大于求的核心：持续产出（自动化农场/仓库清理），净产出>净消耗
            # 注意：每个 AI step 内会有 10 次 tick，所以这里的 lambda 是“每 tick 期望”
            # Rebalanced: reduce extreme oversupply so the low-tier price band is learnable.
            "produce_lambda": {"dist": "uniform", "low": 0.6, "high": 3.0},
            "consume_lambda": {"dist": "uniform", "low": 0.4, "high": 2.0},
            "buy_prob": {"dist": "beta", "a": 1.0, "b": 40.0, "min": 0.00, "max": 0.12},
            "sell_prob": {"dist": "beta", "a": 14.0, "b": 1.3, "min": 0.55, "max": 0.99},
            "buy_amount": {"dist": "loguniform", "low": 1, "high": 96, "integer": True},
            # 低价值倾销长尾仍然保留（自动化农场/清仓），但让常态更温和
            "sell_amount": {"dist": "loguniform", "low": 32, "high": 2048, "integer": True},
            # 老玩家通常不会为了“囤低价垃圾”去买；但会在库存非常低时补一点
            "buy_inventory_target": {"dist": "int_uniform", "low": 0, "high": 128},
            # 只有库存显著堆积才会倾销（避免每 tick 都卖、也更贴近“攒一仓库再卖”）
            "sell_inventory_threshold": {"dist": "int_uniform", "low": 512, "high": 8192},
        },
        {
            "type": "NewPlayer",
            "count": {"dist": "int_uniform", "low": 0, "high": 3},
            "balance": {"dist": "loguniform", "low": 500_000, "high": 1_000_000},
            "initial_item_inventory": {"dist": "int_uniform", "low": 0, "high": 128},
            "produce_lambda": {"dist": "uniform", "low": 0.0, "high": 1.5},
            "consume_lambda": {"dist": "uniform", "low": 0.5, "high": 3.0},
            "buy_prob": {"dist": "beta", "a": 3.0, "b": 7.0, "min": 0.02, "max": 0.90},
            "sell_prob": {"dist": "beta", "a": 4.0, "b": 6.0, "min": 0.02, "max": 0.90},
            "amount": {"dist": "loguniform", "low": 1, "high": 256, "integer": True},
            "buy_inventory_target": {"dist": "int_uniform", "low": 32, "high": 256},
            "sell_inventory_threshold": {"dist": "int_uniform", "low": 128, "high": 512},
        },
        {
            "type": "Arbitrageur",
            "count": {"dist": "int_uniform", "low": 0, "high": 3},
            "balance": {"dist": "loguniform", "low": 1_000_000, "high": 10_000_000},
        },
    ],
}
