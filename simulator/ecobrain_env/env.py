import gymnasium as gym
from gymnasium import spaces
import numpy as np
import csv
import os
from collections import deque
from .amm import AMM
from .players import NewPlayer, VeteranPlayer, Arbitrageur, ReplayPlayer
from .config import (
    TIERS,
    ACTION_BASE_PRICE_MAX_PERCENT,
    ACTION_K_FACTOR_MAX_DELTA,
    PLAYERS,
    ADAPTIVE_TARGET_ENABLED,
    ADAPTIVE_TARGET_SMOOTHING_FACTOR,
    PER_CYCLE_MAX_CHANGE_PERCENT,
    MAX_BASE_PRICE,
    MIN_BASE_PRICE,
    K_MIN,
    K_MAX,
    SCHEDULE_MINUTES,
    AOV_WINDOW_HOURS,
    IPO_BASE_PRICE_FALLBACK,
    DAILY_LIMIT_PERCENT,
    CRITICAL_INVENTORY,
    BASE_SPREAD,
    MAX_SPREAD,
    DUMPING_TAX_TRIGGER_MULTIPLIER,
    DUMPING_TAX_PER_MULTIPLE,
    REWARD_TRADE_VALUE_WEIGHT,
    REWARD_INFLATION_RATE_WEIGHT,
    REWARD_INVENTORY_IMBALANCE_WEIGHT,
)

class EcoBrainEnv(gym.Env):
    """
    Custom Environment for EcoBrain 2.0 PPO Training
    """
    metadata = {'render_modes': ['human']}

    def __init__(self, value_type="low", dataset_path=None):
        super().__init__()
        
        self.value_type = value_type # "low", "mid", "high"
        self.dataset_path = dataset_path
        
        # Action space: 
        # [0]: Base price multiplier (-20% to +20%, mapped from [-1, 1])
        # [1]: K factor delta (-0.1 to +0.1, mapped from [-1, 1])
        self.action_space = spaces.Box(low=-1.0, high=1.0, shape=(2,), dtype=np.float32)
        
        # Observation space:
        # [saturation, recent_flow, inflation, price_elasticity, volatility, is_ipo_active]
        self.observation_space = spaces.Box(low=-np.inf, high=np.inf, shape=(6,), dtype=np.float32)
        
        self.max_steps = 1000
        self.reset()
        
    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        
        # Initialize AMM
        # Zero-trust IPO: start with 0.01 base price
        
        tier_cfg = TIERS[self.value_type]
        target_inv = tier_cfg["target_inventory"]
        # 对齐插件端 IPO 建档：virtual current_inventory 初始化=target_inventory（饱和度=100%）
        # physical_stock 代表真实库存，使用 tier_cfg["current_inventory"] 作为冷启动物理盘
        current_inv = target_inv
        physical_init = tier_cfg["current_inventory"]
            
        self.amm = AMM(
            base_price=0.01, 
            target_inventory=target_inv,
            current_inventory=current_inv,
            k_factor=1.0,
            physical_stock=physical_init,
            is_ipo=True,
            base_spread=BASE_SPREAD,
            max_spread=MAX_SPREAD,
            dumping_trigger_multiplier=DUMPING_TAX_TRIGGER_MULTIPLIER,
            dumping_tax_per_multiple=DUMPING_TAX_PER_MULTIPLE,
            critical_inventory=CRITICAL_INVENTORY,
        )
        
        # Initialize players
        self.players = []
        
        if self.dataset_path and os.path.exists(self.dataset_path):
            # Load real online data to create ReplayPlayers
            self._load_dataset_players()
            self.players.append(Arbitrageur("Arb1", balance=50000)) # Always keep the arbitrageur to punish bad AI
        else:
            player_configs = PLAYERS.get(self.value_type, [])
            for p_cfg in player_configs:
                if p_cfg["type"] == "VeteranPlayer":
                    self.players.append(VeteranPlayer(
                        p_cfg["name"], 
                        balance=p_cfg.get("balance", 100000),
                        buy_probability=p_cfg.get("buy_prob", 0.05),
                        sell_probability=p_cfg.get("sell_prob", 0.5),
                        buy_amount=p_cfg.get("buy_amount", 1),
                        sell_amount=p_cfg.get("sell_amount", 64)
                    ))
                elif p_cfg["type"] == "NewPlayer":
                    self.players.append(NewPlayer(
                        p_cfg["name"],
                        balance=p_cfg.get("balance", 1000),
                        buy_probability=p_cfg.get("buy_prob", 0.1),
                        sell_probability=p_cfg.get("sell_prob", 0.1),
                        amount=p_cfg.get("amount", 5)
                    ))
                elif p_cfg["type"] == "Arbitrageur":
                    self.players.append(Arbitrageur(
                        p_cfg["name"],
                        balance=p_cfg.get("balance", 10000)
                    ))
            
        self.step_count = 0

        # === Plugin-aligned macro signals ===
        self.schedule_minutes = max(1, int(SCHEDULE_MINUTES))
        self.aov_window_hours = max(1, int(AOV_WINDOW_HOURS))
        self.aov_cycles = max(1, int(round((self.aov_window_hours * 60.0) / self.schedule_minutes)))
        # store (trade_value_sum, trade_count) per cycle
        self._aov_window = deque(maxlen=self.aov_cycles)

        # Circuit breaker (simplified, aligned semantics)
        self.daily_limit = float(DAILY_LIMIT_PERCENT)
        self.critical_inventory = int(CRITICAL_INVENTORY)
        self._frozen = False
        self._day_index = 0
        self._day_open_price = self.amm.get_current_price()
        
        # Stats for state calculation
        self.recent_buys = 0
        self.recent_sells = 0
        self.recent_buy_volume = 0
        self.recent_sell_volume = 0
        self.last_price = self.amm.get_current_price()
        
        # Reset all player balances
        for player in self.players:
            if hasattr(player, 'reset'):
                player.reset()
        
        # State: [saturation, flow, inflation, elasticity, volatility, is_ipo]
        return self._get_obs(), {}
        
    def _load_dataset_players(self):
        # A simple replay logic: extract average buy/sell frequency and volume from CSV
        # and create statistical players to mimic the real server's behavior for this value_type.
        # This makes the training automatic and data-driven without manual coding.
        try:
            total_buys = 0
            total_sells = 0
            buy_vol = 0
            sell_vol = 0
            record_count = 0
            
            with open(self.dataset_path, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    record_count += 1
                    qty = int(row['quantity'])
                    # Simple heuristic to guess if row belongs to this value type based on total_price/qty
                    if qty > 0:
                        price_per_item = float(row['total_price']) / qty
                        tier_cfg = TIERS[self.value_type]
                        if not (tier_cfg["price_min"] <= price_per_item < tier_cfg["price_max"]):
                           continue # Skip records that don't match this tier
                           
                    if row['trade_type'] == 'BUY':
                        total_buys += 1
                        buy_vol += qty
                    elif row['trade_type'] == 'SELL':
                        total_sells += 1
                        sell_vol += qty
                        
            if record_count > 0:
                # Convert to probabilities assuming the CSV covers ~1000 AI cycles
                buy_prob = min(0.9, total_buys / 1000.0)
                sell_prob = min(0.9, total_sells / 1000.0)
                avg_buy_amt = max(1, int(buy_vol / max(1, total_buys)))
                avg_sell_amt = max(1, int(sell_vol / max(1, total_sells)))
                
                print(f"[{self.value_type}] Loaded from CSV: BuyProb={buy_prob:.2f}(amt:{avg_buy_amt}), SellProb={sell_prob:.2f}(amt:{avg_sell_amt})")
                self.players.append(ReplayPlayer("ReplayData", buy_prob, sell_prob, avg_buy_amt, avg_sell_amt))
            else:
                print(f"Warning: Dataset {self.dataset_path} is empty. Falling back to default players.")
                self.dataset_path = None
                self.reset() # Re-init without dataset
                
        except Exception as e:
            print(f"Error loading dataset: {e}. Falling back to default players.")
            self.dataset_path = None
            self.reset()
            
    def _get_obs(self):
        """
        Observation 对齐插件端 `AIScheduler`：
        [saturation, recent_flow_per_minute, global_inflation_rate, elasticity(0), volatility(~0), is_ipo_flag]
        """
        saturation = self.amm.current_inventory / max(1.0, float(self.amm.target_inventory))
        net_flow = float(self.recent_buys - self.recent_sells)
        recent_flow_per_minute = net_flow / float(self.schedule_minutes)

        cycle_net_emission = float(self.recent_sell_volume - self.recent_buy_volume)
        dynamic_aov = float(self._compute_dynamic_aov())
        global_inflation_rate = cycle_net_emission / max(1e-9, dynamic_aov)

        elasticity = 0.0  # 插件端当前实现为 0
        volatility = 0.0  # 插件端 TWAP 近似=当前价
        is_ipo_flag = 1.0 if self.amm.base_price <= 0.011 else 0.0

        return np.array(
            [
                saturation,
                recent_flow_per_minute,
                global_inflation_rate,
                elasticity,
                volatility,
                is_ipo_flag,
            ],
            dtype=np.float32,
        )

    def _compute_dynamic_aov(self) -> float:
        total_value = 0.0
        total_count = 0
        for v, c in self._aov_window:
            total_value += float(v)
            total_count += int(c)
        if total_count > 0 and total_value > 0:
            return total_value / total_count
        # 插件端 fallback：若无交易则使用 IPO base price * 20
        return float(IPO_BASE_PRICE_FALLBACK) * 20.0

    def _refresh_day_if_needed(self):
        # 以 schedule_minutes 为粒度，把一“天”离散成若干个周期
        cycles_per_day = max(1, int(round((24 * 60) / self.schedule_minutes)))
        day_index = int(self.step_count // cycles_per_day)
        if day_index != self._day_index:
            self._day_index = day_index
            self._frozen = False
            self._day_open_price = self.amm.get_current_price()

    def _check_and_update_daily_limit(self):
        # 对齐插件端：超过日内涨跌阈值则冻结买入（卖出仍允许）
        self._refresh_day_if_needed()
        if self._frozen:
            return
        day_open = float(self._day_open_price)
        if day_open <= 0:
            return
        current_price = float(self.amm.get_current_price())
        pct = (current_price - day_open) / day_open
        if abs(pct) > float(self.daily_limit):
            self._frozen = True

    def _allow_buy(self, amount: int) -> bool:
        if amount <= 0:
            return False
        # 必须有真实库存
        if self.amm.physical_stock < amount:
            return False

        self._check_and_update_daily_limit()
        if self._frozen:
            return False

        # 虚拟库存过低
        if int(self.amm.current_inventory) <= int(self.critical_inventory):
            return False

        # 买完后的真实库存保护（允许精准停靠在熔断线）
        post_physical = int(self.amm.physical_stock) - int(amount)
        if post_physical < int(self.critical_inventory):
            return False

        return True

    def step(self, action):
        self.step_count += 1
        
        # 1. Apply AI action
        raw_mult = 1.0 + (float(action[0]) * float(ACTION_BASE_PRICE_MAX_PERCENT))
        raw_k_delta = float(action[1]) * float(ACTION_K_FACTOR_MAX_DELTA)

        # Align with plugin: clamp multiplier and kDelta per-cycle
        limit = max(0.0, float(PER_CYCLE_MAX_CHANGE_PERCENT))
        safe_mult = max(1.0 - limit, min(1.0 + limit, raw_mult))
        safe_k_delta = max(-float(ACTION_K_FACTOR_MAX_DELTA), min(float(ACTION_K_FACTOR_MAX_DELTA), raw_k_delta))

        self.amm.apply_ai_action(safe_mult, safe_k_delta)
        # Hard bounds (plugin aligned)
        self.amm.base_price = max(float(MIN_BASE_PRICE), min(float(MAX_BASE_PRICE), float(self.amm.base_price)))
        self.amm.k_factor = max(float(K_MIN), min(float(K_MAX), float(self.amm.k_factor)))

        # Update circuit breaker flag for this cycle (affects AMM.execute_buy)
        self._check_and_update_daily_limit()
        self.amm.set_frozen(self._frozen)
        
        # 2. Simulate player interactions for this cycle (e.g., representing 30 mins)
        self.recent_buys = 0
        self.recent_sells = 0
        self.recent_buy_volume = 0
        self.recent_sell_volume = 0

        cycle_trade_value_sum = 0.0
        cycle_trade_count = 0
        
        # Let players act multiple times per AI cycle
        for _ in range(10):
            for p in self.players:
                qty, money = p.act(self.amm, self.step_count)

                if qty > 0: # Buy
                    self.recent_buys += qty
                    self.recent_buy_volume += abs(money)
                    cycle_trade_value_sum += abs(money)
                    cycle_trade_count += 1
                elif qty < 0: # Sell
                    self.recent_sells += abs(qty)
                    self.recent_sell_volume += abs(money)
                    cycle_trade_value_sum += abs(money)
                    cycle_trade_count += 1

        # Update AOV rolling window
        self._aov_window.append((cycle_trade_value_sum, cycle_trade_count))
                    
        # 3. Calculate Reward
        # We want: 
        # - Good trade volume (positive)
        # - Low inflation (penalize high system money emission)
        # - Inventory close to target
        # - For high value items: Price should go UP based on buys.
        # - For low value items: Price should stay LOW based on infinite sells.
        
        # 对齐插件端自适应目标库存：target 逼近 physicalStock，而非 virtual currentInventory
        if ADAPTIVE_TARGET_ENABLED:
            old_target = int(self.amm.target_inventory)
            old_current = int(self.amm.current_inventory)
            ema = old_target + (int(self.amm.physical_stock) - old_target) * ADAPTIVE_TARGET_SMOOTHING_FACTOR
            new_target = int(round(ema))
            if new_target == old_target and int(self.amm.physical_stock) != old_target:
                new_target += 1 if int(self.amm.physical_stock) > old_target else -1
            new_target = max(1, int(new_target))

            if new_target != old_target:
                # 对齐插件端：target 变动时按比例缩放 currentInventory，维持饱和度近似不变
                scaled_current = int(round(old_current * (new_target / max(1.0, float(old_target)))))
                self.amm.target_inventory = new_target
                self.amm.current_inventory = max(1, scaled_current)

        trade_value = float(self.recent_buy_volume + self.recent_sell_volume)
        cycle_net_emission = float(self.recent_sell_volume - self.recent_buy_volume)
        dynamic_aov = float(self._compute_dynamic_aov())
        inflation_rate = cycle_net_emission / max(1e-9, dynamic_aov)

        inventory_imbalance = abs(self.amm.current_inventory - self.amm.target_inventory) / max(1, self.amm.target_inventory)

        reward = float(
            (REWARD_TRADE_VALUE_WEIGHT * trade_value)
            - (REWARD_INFLATION_RATE_WEIGHT * max(0.0, inflation_rate))
            - (REWARD_INVENTORY_IMBALANCE_WEIGHT * inventory_imbalance)
        )
        
        # Specific shaping to help it learn the difference between garbage and gold
        tier_cfg = TIERS[self.value_type]
        if self.value_type == "high":
            # Boss mats: encourage scaling to hundreds of thousands or millions
            if self.amm.get_current_price() > tier_cfg["price_target"]:
                reward += tier_cfg["price_reward_2"]
            elif self.amm.get_current_price() > tier_cfg["price_min"]:
                reward += tier_cfg["price_reward_1"]
                
            # 对齐插件端语义：真实库存枯竭更关键
            if int(self.amm.physical_stock) <= 0:
                reward -= tier_cfg["empty_stock_penalty"]
                
        elif self.value_type == "mid":
            # MythicMobs mats: scale to thousands or tens of thousands
            if tier_cfg["price_min"] <= self.amm.get_current_price() <= tier_cfg["price_max"]:
                reward += tier_cfg["price_reward"]
            if cycle_net_emission > tier_cfg["inflation_penalty_threshold"]:
                reward -= tier_cfg["inflation_penalty"]
                
        elif self.value_type == "low":
            # Vanilla mats: scale to tens of coins, extremely strict on inflation
            if self.amm.get_current_price() < tier_cfg["price_target"]:
                reward += tier_cfg.get("price_reward_2", tier_cfg.get("price_reward", 5.0))
            elif self.amm.get_current_price() < tier_cfg["price_max"]:
                reward += tier_cfg.get("price_reward_1", 2.0)
            if cycle_net_emission > tier_cfg["inflation_penalty_threshold"]:
                reward -= tier_cfg["inflation_penalty"]
                
        # 4. Check done
        done = self.step_count >= self.max_steps
        truncated = False
        
        self.last_price = self.amm.get_current_price()
        
        return self._get_obs(), float(reward), done, truncated, {}
