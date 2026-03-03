"""
EcoBrain 2.0 模拟器全局配置文件 (Simulator Configuration)
在这里你可以自定义训练时的各类物品参数，而无需修改底层的 Python 环境代码。
修改后再次运行 `python train.py` 即可生效。
"""

# ==========================================
# 动作空间限制 (Action Space Limits)
# ==========================================
# 控制 AI 单次决策所能带来的最大改变幅度。
ACTION_BASE_PRICE_MAX_PERCENT = 1.00  # 底价单次最大涨跌幅 (0.20 = 20%)
ACTION_K_FACTOR_MAX_DELTA = 1.00      # K 因子单次最大微调幅度

# ==========================================
# 目标库存自适应设置 (Adaptive Target)
# ==========================================
ADAPTIVE_TARGET_ENABLED = True
ADAPTIVE_TARGET_SMOOTHING_FACTOR = 0.05

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
