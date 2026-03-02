"""
EcoBrain 2.0 模拟器全局配置文件 (Simulator Configuration)
在这里你可以自定义训练时的各类物品参数，而无需修改底层的 Python 环境代码。
修改后再次运行 `python train.py` 即可生效。
"""

# ==========================================
# 动作空间限制 (Action Space Limits)
# ==========================================
# 控制 AI 单次决策所能带来的最大改变幅度。
ACTION_BASE_PRICE_MAX_PERCENT = 0.20  # 底价单次最大涨跌幅 (0.20 = 20%)
ACTION_K_FACTOR_MAX_DELTA = 0.10      # K 因子单次最大微调幅度

# ==========================================
# 各阶级物品参数 (Item Tier Parameters)
# ==========================================
TIERS = {
    # 极品/高价值物品 (如: 下界之星, Boss掉落物)
    "high": {
        "target_inventory": 10,        # 理想库存 (非常稀缺)
        "current_inventory": 2,       # 训练开始时的实际库存
        "price_min": 10000.0,         # 判定为高价值的最低底价 (和 Java 端的 config.yml 对应)
        "price_max": float('inf'),    # 最高价无上限
        "price_target": 50000.0,     # AI 训练时，把价格拉升到多少以上会获得顶级奖励
        "price_reward_1": 5.0,        # 达到 price_min 的加分
        "price_reward_2": 10.0,       # 达到 price_target 的顶级加分
        "empty_stock_penalty": 20.0   # 卖得太便宜导致库存被神豪买空（归零）的惩罚扣分
    },
    
    # 中等价值物品 (如: 副本材料, 铁锭, 金锭)
    "mid": {
        "target_inventory": 640,       # 理想库存
        "current_inventory": 64,      # 训练开始时的实际库存
        "price_min": 1000.0,          # 判定为中等价值的最低底价
        "price_max": 10000.0,         # 判定为中等价值的最高底价
        "price_reward": 5.0,          # 价格维稳在此区间内的加分
        "inflation_penalty_threshold": 10000.0, # 单次调控周期内，系统流出金币的容忍上限
        "inflation_penalty": 10.0     # 超过容忍上限的通货膨胀扣分
    },
    
    # 低价值物品 (如: 泥土, 小麦, 圆石)
    "low": {
        "target_inventory": 640,      # 理想库存 (极其充裕)
        "current_inventory": 64,      # 训练开始时的实际库存
        "price_min": 0.0,             # 最低价
        "price_max": 1000.0,          # 判断为低价值的最高底价
        "price_target": 100.0,        # AI 训练时，必须把价格死死压制在这个值以下
        "price_reward": 2.0,          # 成功压制价格的加分
        "inflation_penalty_threshold": 500.0, # 极低的通胀容忍度 (低端物品绝不能成为刷钱漏洞)
        "inflation_penalty": 30.0     # 极其严厉的通货膨胀惩罚 (重罚)
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
        # 老玩家：偶尔打到极品材料卖钱，偶尔买一点
        {"type": "VeteranPlayer", "name": "Veteran1", "sell_prob": 0.01, "buy_prob": 0.1, "sell_amount": 1, "buy_amount": 1, "balance": 100000},
        # 新玩家/平民：极少购买极品
        {"type": "NewPlayer", "name": "New1", "buy_prob": 0.01, "sell_prob": 0.0, "amount": 1, "balance": 5000},
        # 倒爷：永远存在，寻找一切低买高卖的机会
        {"type": "Arbitrageur", "name": "Arb1", "balance": 500000}
    ],
    
    "mid": [
        # 老玩家：有中等产出能力，主要卖钱
        {"type": "VeteranPlayer", "name": "Veteran1", "sell_prob": 0.4, "buy_prob": 0.05, "sell_amount": 16, "buy_amount": 5, "balance": 50000},
        # 新玩家：需要消耗中等材料度过前期
        {"type": "NewPlayer", "name": "New1", "buy_prob": 0.1, "sell_prob": 0.05, "amount": 5, "balance": 2000},
        {"type": "Arbitrageur", "name": "Arb1", "balance": 50000}
    ],
    
    "low": [
        # 老玩家：超级农场主，疯狂倾销海量物资
        {"type": "VeteranPlayer", "name": "Veteran_Farmer1", "sell_prob": 0.9, "buy_prob": 0.01, "sell_amount": 128, "buy_amount": 10, "balance": 10000},
        {"type": "VeteranPlayer", "name": "Veteran_Farmer2", "sell_prob": 0.8, "buy_prob": 0.02, "sell_amount": 64, "buy_amount": 10, "balance": 10000},
        # 新玩家：偶尔买卖少量杂物
        {"type": "NewPlayer", "name": "New1", "buy_prob": 0.2, "sell_prob": 0.1, "amount": 16, "balance": 500},
        {"type": "Arbitrageur", "name": "Arb1", "balance": 10000}
    ]
}
