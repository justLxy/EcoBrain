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
