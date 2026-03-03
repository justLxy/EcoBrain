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

# 插件端二次拦截（安全夹紧）
PER_CYCLE_MAX_CHANGE_PERCENT = 1.00   # 对齐 ai.tuning.per-cycle-max-change-percent
MAX_BASE_PRICE = 1_000_000.0          # 对齐 ai.tuning.max-base-price
MIN_BASE_PRICE = 0.01                # 对齐 economy.ipo.zero-trust 初始锚

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
REWARD_INFLATION_RATE_WEIGHT = 25.0   # 惩罚净印钞率（>0 时）
REWARD_INVENTORY_IMBALANCE_WEIGHT = 10.0  # 库存偏离惩罚

# Reward 数值稳定性：
# - 先做线性缩放把回报压到合理量级（避免 value_loss 爆炸）
# - 再做可选裁剪防极端 outlier
REWARD_SCALE = 1e-3
REWARD_CLIP_ABS = 1e4  # 设为 None 可关闭裁剪

# ==========================================
# 各阶级物品参数 (Item Tier Parameters)
# ==========================================
TIERS = {
    # 极品/高价值物品 (如: 下界之星, Boss掉落物)
    "high": {
        "target_inventory": 5,         # 理想库存 (更稀缺，让AI知道这东西很难得)
        "current_inventory": 1,        # 训练开始时的实际库存
        "price_min": 10000.0,          # 判定为高价值的最低底价
        "price_max": float('inf'),     # 最高价无上限
        "price_target": 100000.0,      # (提升目标价) 由于老玩家多且资金多，AI需要敢于把极品卖出天价
        "price_reward_1": 5.0,         # 达到 price_min 的加分
        "price_reward_2": 15.0,        # (提升奖励) 鼓励AI把极品卖贵，吸收服务器溢出的金币
        "empty_stock_penalty": 30.0    # (提升惩罚) 老玩家多，一旦卖便宜瞬间被秒，告诉AI绝对不能贱卖极品
    },
    
    # 中等价值物品 (如: 副本材料, 铁锭, 金锭)
    "mid": {
        "target_inventory": 1280,      # (调高理想库存) 供大于求，放宽库存容忍度
        "current_inventory": 640,      # 初始库存也要高
        "price_min": 1000.0,           
        "price_max": 10000.0,          
        "price_reward": 5.0,           
        "inflation_penalty_threshold": 5000.0, # (收紧通胀容忍度) 老玩家供大于求，如果AI高价回收中等材料会导致发大水
        "inflation_penalty": 20.0      # (提升通胀惩罚) 让AI学会：面对疯狂倒垃圾的老玩家，必须狠心砸盘降价
    },
    
    # 低价值物品 (如: 泥土, 小麦, 圆石)
    "low": {
        "target_inventory": 3200,      # (大幅调高) 老玩家基建狂魔，低价值物品库存会极其恐怖
        "current_inventory": 1000,     
        "price_min": 0.0,              
        "price_max": 1000.0,           
        "price_target": 50.0,          # (压低目标价) 供大于求的废品，必须压到极低的价格
        "price_reward_1": 2.0,         # 达到 price_max 以下的保底加分
        "price_reward_2": 5.0,         # (提升奖励) 奖励AI成功把垃圾压成白菜价
        "inflation_penalty_threshold": 200.0, # (极低通胀容忍) 垃圾物品绝对不能成为提款机
        "inflation_penalty": 50.0      # (最高惩罚) 谁敢高价收垃圾，直接让AI吃满惩罚
    }
}

# ==========================================
# 模拟玩家生态配置 (Simulated Players)
# ==========================================
# 在离线训练时，如果没有提供真实的 CSV 数据，模拟器会使用以下设定的虚拟玩家。
# 调整这里的概率和数量，可以训练出应对不同服务器生态的 AI。
# 
# buy_prob: 每个周期购买物品的概率 (0.0~1.0)
# sell_prob: 每个周期出售物品的概率 (0.0~1.0)
# amount: 每次购买/出售的数量
# balance: 玩家携带的初始资金

PLAYERS = {
    "high": [
        # 高端局：老玩家打到极品，但由于老玩家多，极品其实也有一定产出
        {"type": "VeteranPlayer", "name": "Veteran1", "sell_prob": 0.2, "buy_prob": 0.3, "sell_amount": 1, "buy_amount": 1, "balance": 500000},
        {"type": "VeteranPlayer", "name": "Veteran2", "sell_prob": 0.2, "buy_prob": 0.3, "sell_amount": 1, "buy_amount": 1, "balance": 500000},
        {"type": "VeteranPlayer", "name": "Veteran3", "sell_prob": 0.1, "buy_prob": 0.5, "sell_amount": 1, "buy_amount": 2, "balance": 1000000}, # 神豪老玩家
        # 新玩家：买不起极品，只会做梦
        {"type": "NewPlayer", "name": "New1", "buy_prob": 0.01, "sell_prob": 0.0, "amount": 1, "balance": 5000},
        # 倒爷：敏锐嗅觉
        {"type": "Arbitrageur", "name": "Arb1", "balance": 2000000}
    ],
    
    "mid": [
        # 中端局：老玩家疯狂产出中等材料 (供大于求的核心)
        {"type": "VeteranPlayer", "name": "Veteran1", "sell_prob": 0.8, "buy_prob": 0.05, "sell_amount": 128, "buy_amount": 16, "balance": 200000},
        {"type": "VeteranPlayer", "name": "Veteran2", "sell_prob": 0.8, "buy_prob": 0.05, "sell_amount": 256, "buy_amount": 32, "balance": 200000},
        {"type": "VeteranPlayer", "name": "Veteran3", "sell_prob": 0.7, "buy_prob": 0.1, "sell_amount": 128, "buy_amount": 64, "balance": 200000},
        {"type": "VeteranPlayer", "name": "Veteran4", "sell_prob": 0.7, "buy_prob": 0.0, "sell_amount": 384, "buy_amount": 0, "balance": 200000}, # 纯打金老玩家
        # 新玩家：艰难求生，买点中等材料做装备
        {"type": "NewPlayer", "name": "New1", "buy_prob": 0.3, "sell_prob": 0.1, "amount": 32, "balance": 5000},
        {"type": "NewPlayer", "name": "New2", "buy_prob": 0.4, "sell_prob": 0.1, "amount": 16, "balance": 5000},
        {"type": "Arbitrageur", "name": "Arb1", "balance": 500000}
    ],
    
    "low": [
        # 低端局：老玩家自动化农场全开，海量倾销垃圾
        {"type": "VeteranPlayer", "name": "Veteran_Farmer1", "sell_prob": 0.95, "buy_prob": 0.01, "sell_amount": 1024, "buy_amount": 64, "balance": 100000},
        {"type": "VeteranPlayer", "name": "Veteran_Farmer2", "sell_prob": 0.95, "buy_prob": 0.01, "sell_amount": 2048, "buy_amount": 64, "balance": 100000}, # 超级大农场
        {"type": "VeteranPlayer", "name": "Veteran_Farmer3", "sell_prob": 0.9, "buy_prob": 0.05, "sell_amount": 512, "buy_amount": 128, "balance": 100000},
        {"type": "VeteranPlayer", "name": "Veteran_Farmer4", "sell_prob": 0.9, "buy_prob": 0.0, "sell_amount": 1024, "buy_amount": 0, "balance": 100000},
        # 新玩家：稍微卖点捡来的垃圾，买点吃的
        {"type": "NewPlayer", "name": "New1", "buy_prob": 0.3, "sell_prob": 0.4, "amount": 64, "balance": 1000},
        {"type": "NewPlayer", "name": "New2", "buy_prob": 0.2, "sell_prob": 0.5, "amount": 64, "balance": 1000},
        {"type": "Arbitrageur", "name": "Arb1", "balance": 50000}
    ]
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
            "balance": {"dist": "loguniform", "low": 250_000, "high": 6_000_000},
            # 极品仍会买，但频率更低（慢节奏）
            "buy_prob": {"dist": "beta", "a": 1.5, "b": 7.0, "min": 0.00, "max": 0.60},
            # 产出/抛售事件相对稀疏，但一旦出现仍可能卖（由 dumping regime 放大）
            "sell_prob": {"dist": "beta", "a": 1.2, "b": 10.0, "min": 0.00, "max": 0.45},
            "buy_amount": {"dist": "choice", "values": [1, 1, 2, 3]},
            "sell_amount": {"dist": "choice", "values": [1, 1, 1, 2]},
        },
        # 新玩家：买得起的不多
        {
            "type": "NewPlayer",
            # 养老服：新人更少
            "count": {"dist": "int_uniform", "low": 0, "high": 1},
            "balance": {"dist": "loguniform", "low": 2_000, "high": 30_000},
            "buy_prob": {"dist": "beta", "a": 1.0, "b": 25.0, "min": 0.00, "max": 0.15},
            "sell_prob": {"dist": "beta", "a": 1.0, "b": 60.0, "min": 0.00, "max": 0.05},
            "amount": {"dist": "choice", "values": [1, 1, 1, 2]},
        },
        # 倒爷：每局可能出现 0~1 个（不要太多，否则训练会变得“只学防倒爷”）
        {
            "type": "Arbitrageur",
            "count": {"dist": "int_uniform", "low": 0, "high": 1},
            "balance": {"dist": "loguniform", "low": 150_000, "high": 6_000_000},
        },
    ],
    "mid": [
        {
            "type": "VeteranPlayer",
            # 养老服中价值：典型“供大于求”，老玩家数量更多、卖压更稳定
            "count": {"dist": "int_uniform", "low": 4, "high": 8},
            "balance": {"dist": "loguniform", "low": 60_000, "high": 700_000},
            "buy_prob": {"dist": "beta", "a": 1.0, "b": 22.0, "min": 0.00, "max": 0.15},
            "sell_prob": {"dist": "beta", "a": 10.0, "b": 1.7, "min": 0.35, "max": 0.99},
            "buy_amount": {"dist": "loguniform", "low": 1, "high": 48, "integer": True},
            # 10-20 在线：中价值倾销一般不会太离谱，但仍保留长尾
            "sell_amount": {"dist": "loguniform", "low": 32, "high": 1024, "integer": True},
        },
        {
            "type": "NewPlayer",
            # 养老服：新人少，需求端更稀薄
            "count": {"dist": "int_uniform", "low": 0, "high": 1},
            "balance": {"dist": "loguniform", "low": 800, "high": 20_000},
            "buy_prob": {"dist": "beta", "a": 2.0, "b": 10.0, "min": 0.01, "max": 0.70},
            "sell_prob": {"dist": "beta", "a": 2.5, "b": 9.0, "min": 0.00, "max": 0.55},
            "amount": {"dist": "loguniform", "low": 1, "high": 128, "integer": True},
        },
        {
            "type": "Arbitrageur",
            "count": {"dist": "int_uniform", "low": 0, "high": 1},
            "balance": {"dist": "loguniform", "low": 60_000, "high": 1_200_000},
        },
    ],
    "low": [
        # 低价值：大概率存在“自动化倾销”老玩家，但强度每局不同（关键：让 AI 学会适配不同倾销力度）
        {
            "type": "VeteranPlayer",
            # 养老服低价值：老玩家多、倾销更强
            "count": {"dist": "int_uniform", "low": 5, "high": 10},
            "balance": {"dist": "loguniform", "low": 20_000, "high": 450_000},
            "buy_prob": {"dist": "beta", "a": 1.0, "b": 40.0, "min": 0.00, "max": 0.12},
            "sell_prob": {"dist": "beta", "a": 14.0, "b": 1.3, "min": 0.55, "max": 0.99},
            "buy_amount": {"dist": "loguniform", "low": 1, "high": 96, "integer": True},
            # 低价值倾销长尾仍然保留（自动化农场/清仓），但让常态更温和
            "sell_amount": {"dist": "loguniform", "low": 128, "high": 8192, "integer": True},
        },
        {
            "type": "NewPlayer",
            "count": {"dist": "int_uniform", "low": 0, "high": 2},
            "balance": {"dist": "loguniform", "low": 200, "high": 8_000},
            "buy_prob": {"dist": "beta", "a": 3.0, "b": 7.0, "min": 0.02, "max": 0.90},
            "sell_prob": {"dist": "beta", "a": 4.0, "b": 6.0, "min": 0.02, "max": 0.90},
            "amount": {"dist": "loguniform", "low": 1, "high": 256, "integer": True},
        },
        {
            "type": "Arbitrageur",
            "count": {"dist": "int_uniform", "low": 0, "high": 2},
            "balance": {"dist": "loguniform", "low": 10_000, "high": 500_000},
        },
    ],
}
