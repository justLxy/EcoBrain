import gymnasium as gym
from gymnasium import spaces
import numpy as np
import csv
import os
from collections import deque
from .amm import AMM
from .players import Arbitrageur, ReplayPlayer
from .ecosystem import Regime, build_players_from_archetypes, sample_regime
from .ecosystem import sample_from_spec
from .config import (
    TIERS,
    VALUE_MIX,
    ACTION_BASE_PRICE_MAX_PERCENT,
    ACTION_K_FACTOR_MAX_DELTA,
    ADAPTIVE_TARGET_ENABLED,
    ADAPTIVE_TARGET_SMOOTHING_FACTOR,
    MAX_BASE_PRICE,
    MIN_BASE_PRICE,
    K_MIN,
    K_MAX,
    SCHEDULE_MINUTES,
    AOV_WINDOW_HOURS,
    IPO_BASE_PRICE_FALLBACK,
    IPO_RESET_PROB,
    OBS_USE_LOG_PRICE,
    AGE_CYCLES_IPO,
    AGE_CYCLES_MATURE,
    TREASURY_INITIAL_BALANCE,
    DAILY_LIMIT_PERCENT,
    CRITICAL_INVENTORY,
    BASE_SPREAD,
    MAX_SPREAD,
    DUMPING_TAX_TRIGGER_MULTIPLIER,
    DUMPING_TAX_PER_MULTIPLE,
    REWARD_TRADE_VALUE_WEIGHT,
    REWARD_TRADE_QTY_WEIGHT,
    REWARD_TRADE_QTY_WEIGHT_MID,
    REWARD_TRADE_LOG_VALUE_WEIGHT_HIGH,
    REWARD_INFLATION_RATE_WEIGHT,
    REWARD_INVENTORY_IMBALANCE_WEIGHT,
    REWARD_INVENTORY_IMBALANCE_WEIGHT_LOW,
    REWARD_SCALE,
    REWARD_CLIP_ABS,
    REWARD_ACTION_L1_WEIGHT,
    ECOSYSTEM_RANDOMIZATION,
    MARKET_REGIMES,
    SIMULATED_PLAYER_ARCHETYPES,
)

class EcoBrainEnv(gym.Env):
    """
    Custom Environment for EcoBrain 2.0 PPO Training
    """
    metadata = {'render_modes': ['human']}

    def __init__(self, value_type="mixed", dataset_path=None):
        super().__init__()
        
        # Single-brain training uses a mixed regime. We keep value_type for internal sampling,
        # but it is NOT part of observation.
        self.value_type = value_type # "mixed" or fixed ("low"/"mid"/"high") for debugging
        self.dataset_path = dataset_path
        
        # Action space: 
        # [0]: Base price multiplier (-20% to +20%, mapped from [-1, 1])
        # [1]: K factor delta (-0.1 to +0.1, mapped from [-1, 1])
        self.action_space = spaces.Box(low=-1.0, high=1.0, shape=(2,), dtype=np.float32)
        
        # Observation space (plugin-aligned, single-brain, 16-dim):
        # 0..5 legacy core features, 6..15 cold-start / signal / risk / treasury features
        self.observation_space = spaces.Box(low=-np.inf, high=np.inf, shape=(16,), dtype=np.float32)
        
        self.max_steps = 1000
        # If domain randomization is enabled, this can optionally keep the same sampled ecosystem across resets.
        self._static_players = None
        self.reset()
        
    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        
        # Initialize AMM
        # Episode init: mixture of IPO (zero-trust) and Mature items
        
        # === Sample a latent regime (low/mid/high) per episode ===
        sampled_vt = self.value_type
        if sampled_vt == "mixed":
            mix = VALUE_MIX or {"low": 1.0}
            keys = list(mix.keys())
            probs = np.array([float(mix.get(k, 0.0)) for k in keys], dtype=np.float64)
            probs = probs / max(1e-9, probs.sum())
            sampled_vt = str(self.np_random.choice(keys, p=probs))
        if sampled_vt not in TIERS:
            sampled_vt = "low"
        self._latent_value_type = sampled_vt

        tier_cfg = TIERS[sampled_vt]
        target_inv = int(sample_from_spec(tier_cfg["target_inventory"], rng=self.np_random))
        # 对齐插件端 IPO 建档：virtual current_inventory 初始化=target_inventory（饱和度=100%）
        # physical_stock 代表真实库存，使用 tier_cfg["current_inventory"] 作为冷启动物理盘
        target_inv = max(1, int(target_inv))
        current_inv = target_inv
        physical_init = int(sample_from_spec(tier_cfg["current_inventory"], rng=self.np_random))
        physical_init = max(0, int(physical_init))

        is_ipo = bool(float(self.np_random.random()) < float(IPO_RESET_PROB))
        if is_ipo:
            base_price = 100.0
        else:
            base_price_spec = tier_cfg.get("initial_base_price", 100.0)
            base_price = float(sample_from_spec(base_price_spec, rng=self.np_random))
            base_price = max(float(MIN_BASE_PRICE), min(float(MAX_BASE_PRICE), float(base_price)))

        # Cold-start age signal (cycles since listing)
        try:
            age_spec = AGE_CYCLES_IPO if is_ipo else AGE_CYCLES_MATURE
            self._age_cycles = int(sample_from_spec(age_spec, rng=self.np_random))
        except Exception:
            self._age_cycles = 0 if is_ipo else 100
        self._age_cycles = max(0, int(self._age_cycles))
            
        self.amm = AMM(
            base_price=base_price, 
            target_inventory=target_inv,
            current_inventory=current_inv,
            k_factor=1.0,
            physical_stock=physical_init,
            is_ipo=is_ipo,
            treasury_balance=float(TREASURY_INITIAL_BALANCE),
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
            # Always keep at least one arbitrageur to punish bad AI
            self.players.append(Arbitrageur("Arb1", balance=50000, rng=self.np_random))
        else:
            rand_cfg = ECOSYSTEM_RANDOMIZATION or {}
            rand_enabled = bool(rand_cfg.get("enabled", False))
            if rand_enabled:
                resample_each_reset = bool(rand_cfg.get("resample_each_reset", True))
                if (not resample_each_reset) and (self._static_players is not None):
                    self.players = self._static_players
                else:
                    regime = sample_regime(MARKET_REGIMES, rng=self.np_random)
                    vt = getattr(self, "_latent_value_type", "low")
                    archetypes = SIMULATED_PLAYER_ARCHETYPES.get(vt, [])
                    self.players = build_players_from_archetypes(
                        value_type=vt,
                        archetypes=archetypes,
                        regime=regime,
                        rng=self.np_random,
                    )
                    if not resample_each_reset:
                        self._static_players = self.players
            else:
                # If randomization is disabled, still build from archetypes,
                # but keep a fixed neutral regime (no regime mixing).
                vt = getattr(self, "_latent_value_type", "low")
                archetypes = SIMULATED_PLAYER_ARCHETYPES.get(vt, [])
                self.players = build_players_from_archetypes(
                    value_type=vt,
                    archetypes=archetypes,
                    regime=Regime(name="fixed"),
                    rng=self.np_random,
                )
            
        self.step_count = 0
        self._prev_saturation = float(current_inv) / max(1.0, float(target_inv))

        # === Plugin-aligned macro signals ===
        self.schedule_minutes = max(1, int(SCHEDULE_MINUTES))
        self.aov_window_hours = max(1, int(AOV_WINDOW_HOURS))
        self.aov_cycles = max(1, int(round((self.aov_window_hours * 60.0) / self.schedule_minutes)))
        # store (trade_value_sum, trade_count) per cycle
        self._aov_window = deque(maxlen=self.aov_cycles)

        # Per-item TWAP proxy: keep per-cycle unit average prices (time-weighted by cycle)
        self._unit_price_window = deque(maxlen=self.aov_cycles)
        self._prev_unit_price = 0.0

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
                self.players.append(ReplayPlayer("ReplayData", buy_prob, sell_prob, avg_buy_amt, avg_sell_amt, rng=self.np_random))
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
        Observation 对齐插件端 `AIScheduler`（单模型 16 维）：
        [saturation, recent_flow_per_minute, global_inflation_rate, elasticity, volatility, log_price,
         log_age, has_activity_trade, log_activity, log_target_inventory, log_physical_stock,
         price_change_pct, log_base_price, k_factor, physical_ratio, log_treasury]
        """
        saturation = self.amm.current_inventory / max(1.0, float(self.amm.target_inventory))
        self._prev_saturation = float(saturation)

        net_flow = float(self.recent_buys - self.recent_sells)
        recent_flow_per_minute = net_flow / float(self.schedule_minutes)

        cycle_net_emission = float(self.recent_sell_volume - self.recent_buy_volume)
        dynamic_aov = float(self._compute_dynamic_aov())
        global_inflation_rate = cycle_net_emission / max(1e-9, dynamic_aov)
        cycle_volume = float(self.recent_sell_volume + self.recent_buy_volume)
        emission_ratio = max(0.0, float(cycle_net_emission)) / max(1e-9, float(cycle_volume))
        sell_money = max(0.0, (cycle_volume + cycle_net_emission) * 0.5)
        sell_share = float(sell_money / max(1e-9, cycle_volume))
        treasury_scaled = float(self.amm.treasury_balance) / max(1e-9, float(dynamic_aov))
        log_treasury = float(np.log1p(max(0.0, treasury_scaled)))
        log_treasury = float(np.clip(log_treasury, 0.0, 20.0))

        # 科学特征：对齐插件端“基于成交统计”的启发式
        current_price = float(self.amm.get_current_price())
        twap = float(self._compute_twap_price(fallback=current_price))
        volatility = abs(current_price - twap) / max(1e-9, twap)

        unit_now = float(self._unit_price_window[-1]) if self._unit_price_window else current_price
        unit_prev = float(self._prev_unit_price) if self._prev_unit_price > 0 else twap
        price_change_pct = (unit_now - unit_prev) / max(1e-9, unit_prev)
        denom = max(1e-6, abs(price_change_pct) * 100.0)
        elasticity = recent_flow_per_minute / denom
        elasticity = float(np.clip(elasticity, -1.0e4, 1.0e4))
        log_price = float(np.log(max(1e-9, float(current_price))))
        log_price = float(np.clip(log_price, -20.0, 20.0))

        log_age = float(np.log1p(max(0.0, float(self._age_cycles))))
        log_age = float(np.clip(log_age, 0.0, 20.0))

        # activity window proxy: use AOV window buckets as a stable approximation
        activity_value = float(sum(v for v, _ in self._aov_window)) if self._aov_window else 0.0
        has_activity = 1.0 if activity_value > 0.0 else 0.0
        log_activity = float(np.log1p(max(0.0, activity_value / max(1.0, float(self.schedule_minutes)))))
        log_activity = float(np.clip(log_activity, 0.0, 20.0))

        log_base_price = float(np.log(max(1e-9, float(self.amm.base_price))))
        log_base_price = float(np.clip(log_base_price, -20.0, 20.0))
        k_factor = float(np.clip(float(self.amm.k_factor), float(K_MIN), float(K_MAX)))
        physical_ratio = float(self.amm.physical_stock) / max(1.0, float(self.amm.target_inventory))
        physical_ratio = float(np.clip(physical_ratio, 0.0, 1000.0))
        log_target = float(np.log1p(max(0.0, float(self.amm.target_inventory))))
        log_target = float(np.clip(log_target, 0.0, 20.0))
        log_physical = float(np.log1p(max(0.0, float(self.amm.physical_stock))))
        log_physical = float(np.clip(log_physical, 0.0, 20.0))

        return np.array(
            [
                saturation,
                recent_flow_per_minute,
                global_inflation_rate,
                elasticity,
                volatility,
                log_price,
                log_age,
                has_activity,
                log_activity,
                log_target,
                log_physical,
                float(np.clip(price_change_pct, -10.0, 10.0)),
                log_base_price,
                k_factor,
                physical_ratio,
                log_treasury,
            ],
            dtype=np.float32,
        )

    def _adaptive_target_update_once(self):
        """
        Plugin-aligned adaptive target update (EMA towards physical_stock).
        This is intentionally small and can be called multiple times per cycle
        (e.g., per-trade) to mirror the Java plugin's "update on settle" behavior.
        """
        if not bool(ADAPTIVE_TARGET_ENABLED):
            return
        if self.amm is None:
            return

        old_target = int(self.amm.target_inventory)
        old_current = int(self.amm.current_inventory)
        physical = int(self.amm.physical_stock)

        ema = old_target + (physical - old_target) * float(ADAPTIVE_TARGET_SMOOTHING_FACTOR)
        new_target = int(round(ema))
        if new_target == old_target and physical != old_target:
            new_target += 1 if physical > old_target else -1
        new_target = max(1, int(new_target))

        if new_target == old_target:
            return

        # Keep saturation approximately stable when target changes.
        scaled_current = int(round(old_current * (new_target / max(1.0, float(old_target)))))
        self.amm.target_inventory = new_target
        self.amm.current_inventory = max(1, int(scaled_current))

    def _compute_twap_price(self, fallback: float) -> float:
        if not self._unit_price_window:
            return float(fallback)
        # time-weighted by cycle buckets: arithmetic mean across buckets
        twap = float(sum(self._unit_price_window)) / float(len(self._unit_price_window))
        return float(twap) if twap > 0 else float(fallback)

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
        k_delta_cap = float(ACTION_K_FACTOR_MAX_DELTA)
        raw_k_delta = float(action[1]) * float(k_delta_cap)

        # No per-cycle max-change clamp: keep only ACTION_BASE_PRICE_MAX_PERCENT mapping.
        safe_mult = float(raw_mult)
        safe_k_delta = max(-float(k_delta_cap), min(float(k_delta_cap), raw_k_delta))

        self.amm.apply_ai_action(safe_mult, safe_k_delta)
        # Hard bounds (plugin aligned, single-brain): only global clamps remain.
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

        # Provide a TWAP hint for this cycle (based on past buckets) so that arbitrageurs
        # can react to mean reversion like on the real server.
        try:
            twap_hint = float(self._compute_twap_price(fallback=float(self.amm.get_current_price())))
        except Exception:
            twap_hint = float(self.amm.get_current_price())
        if hasattr(self.amm, "set_twap_hint"):
            self.amm.set_twap_hint(twap_hint)
        else:
            self.amm.twap_hint = twap_hint

        cycle_trade_value_sum = 0.0
        cycle_trade_count = 0
        cycle_trade_qty_sum = 0
        did_trade = False
        
        # Let players act multiple times per AI cycle
        for _ in range(10):
            for p in self.players:
                if hasattr(p, "tick"):
                    try:
                        p.tick()
                    except Exception:
                        pass
                qty, money = p.act(self.amm, self.step_count)

                if qty > 0: # Buy
                    did_trade = True
                    # Mirror plugin: adaptive target reacts on each settled trade.
                    self._adaptive_target_update_once()
                    self.recent_buys += qty
                    self.recent_buy_volume += abs(money)
                    cycle_trade_value_sum += abs(money)
                    cycle_trade_count += 1
                    cycle_trade_qty_sum += int(abs(qty))
                elif qty < 0: # Sell
                    did_trade = True
                    self._adaptive_target_update_once()
                    self.recent_sells += abs(qty)
                    self.recent_sell_volume += abs(money)
                    cycle_trade_value_sum += abs(money)
                    cycle_trade_count += 1
                    cycle_trade_qty_sum += int(abs(qty))

        # Update AOV rolling window
        self._aov_window.append((cycle_trade_value_sum, cycle_trade_count))

        # Update unit price (VWAP per cycle) window for TWAP proxy
        if cycle_trade_qty_sum > 0:
            unit_price = float(cycle_trade_value_sum) / float(cycle_trade_qty_sum)
        else:
            unit_price = float(self.amm.get_current_price())
        if self._unit_price_window:
            self._prev_unit_price = float(self._unit_price_window[-1])
        self._unit_price_window.append(unit_price)
                    
        # 3. Calculate Reward
        # We want: 
        # - Good trade volume (positive)
        # - Low inflation (penalize high system money emission)
        # - Inventory close to target
        # - For high value items: Price should go UP based on buys.
        # - For low value items: Price should stay LOW based on infinite sells.
        
        # If there were no trades in this cycle, still allow a single adaptive update
        # to mirror the Java scheduler's periodic adjustment.
        if not bool(did_trade):
            self._adaptive_target_update_once()

        trade_value = float(self.recent_buy_volume + self.recent_sell_volume)
        trade_qty = float(self.recent_buys + self.recent_sells)
        buy_money = float(self.recent_buy_volume)
        sell_money = float(self.recent_sell_volume)
        cycle_net_emission = float(sell_money - buy_money)
        dynamic_aov = float(self._compute_dynamic_aov())
        inflation_rate = cycle_net_emission / max(1e-9, dynamic_aov)
        # Scale-invariant inflation metric for low/mid:
        # Prevent the policy from "washing" inflation penalties by simply raising prices
        # (which increases AOV and reduces netEmission/AOV without improving real economics).
        total_flow = max(1e-9, sell_money + buy_money)
        inflation_ratio = max(0.0, cycle_net_emission) / total_flow

        inventory_imbalance = abs(self.amm.current_inventory - self.amm.target_inventory) / max(1, self.amm.target_inventory)

        vt_key = getattr(self, "_latent_value_type", "low")
        tier_cfg = TIERS.get(vt_key, TIERS.get("low"))

        # Core reward: use money-volume for mid/high, but quantity for low to avoid
        # inadvertently incentivizing high prices.
        trade_signal = trade_value
        trade_weight = REWARD_TRADE_VALUE_WEIGHT
        if vt_key == "low":
            trade_signal = trade_qty
            trade_weight = REWARD_TRADE_QTY_WEIGHT
        elif vt_key == "mid":
            trade_signal = trade_qty
            trade_weight = REWARD_TRADE_QTY_WEIGHT_MID
        elif vt_key == "high":
            trade_signal = float(np.log1p(max(0.0, float(trade_value))))
            trade_weight = REWARD_TRADE_LOG_VALUE_WEIGHT_HIGH

        # Use scale-invariant inflation metric for ALL regimes to avoid "price washing"
        # and keep mixed-regime training stable.
        inflation_metric = float(inflation_ratio)
        infl_mult = float(tier_cfg.get("inflation_weight_mult", 1.0))
        inflation_penalty = (REWARD_INFLATION_RATE_WEIGHT * infl_mult) * max(0.0, float(inflation_metric))

        inv_weight = REWARD_INVENTORY_IMBALANCE_WEIGHT
        if vt_key == "low":
            inv_weight = REWARD_INVENTORY_IMBALANCE_WEIGHT_LOW

        reward = float(
            (trade_weight * float(trade_signal))
            - float(inflation_penalty)
            - (float(inv_weight) * float(inventory_imbalance))
        )

        # Mild action penalty to discourage saturating controls (tier-scaled).
        try:
            act_mult = float(tier_cfg.get("action_l1_mult", 1.0))
            reward -= (float(REWARD_ACTION_L1_WEIGHT) * act_mult) * (abs(float(action[0])) + abs(float(action[1])))
        except Exception:
            pass
        
        # Specific shaping to help it learn the difference between garbage and gold.
        # IMPORTANT: Use the observable market price (AMM current price) rather than base_price.
        # The policy observes log(current_price) but does NOT observe base_price/k directly; shaping
        # on base_price makes the reward partially unobservable and often leads to boundary solutions.
        price = float(self.amm.get_current_price())
        if vt_key == "high":
            # High-value shaping: smooth penalties inside hard range and scale out-of-range by distance.
            hard_min = float(tier_cfg["price_min"])
            hard_max = float(tier_cfg.get("price_max", float("inf")))
            band_min = float(tier_cfg.get("reward_band_min", hard_min))
            band_max = float(tier_cfg.get("reward_band_max", hard_max))

            if price < hard_min or price > hard_max:
                base_pen = float(tier_cfg.get("penalty_out_of_range", 0.0))
                if price < hard_min:
                    dist = (hard_min - price) / max(1e-9, hard_min)
                else:
                    dist = (price - hard_max) / max(1e-9, hard_max)
                scale = 1.0 + float(np.log1p(max(0.0, float(dist))))
                reward -= base_pen * scale
            else:
                if band_min <= price <= band_max:
                    reward += float(tier_cfg.get("reward_in_band", 0.0))
                else:
                    penalty = float(tier_cfg.get("penalty_out_of_band", 0.0))
                    if price < band_min:
                        denom = max(1e-9, band_min - hard_min)
                        ratio = (band_min - price) / denom
                        # Make "far below band" much more painful to avoid the single-brain collapsing
                        # high-regime prices into mid-range (common mixed-training failure mode).
                        dist_mult = (band_min / max(1e-9, price)) - 1.0
                        scale = 1.0 + float(np.log1p(max(0.0, float(dist_mult))))
                    else:
                        denom = max(1e-9, hard_max - band_max)
                        ratio = (price - band_max) / denom
                        dist_mult = (price / max(1e-9, band_max)) - 1.0
                        scale = 1.0 + float(np.log1p(max(0.0, float(dist_mult))))
                    ratio = float(np.clip(ratio, 0.0, 1.0))
                    reward -= penalty * ratio * scale

            # 真实库存枯竭惩罚（防被买空）
            if int(self.amm.physical_stock) <= 0:
                reward -= float(tier_cfg.get("empty_stock_penalty", 0.0))
                
        elif vt_key == "mid":
            # Mid-value shaping: smooth penalties inside hard range and scale out-of-range by distance.
            hard_min = float(tier_cfg["price_min"])
            hard_max = float(tier_cfg["price_max"])
            band_min = float(tier_cfg.get("reward_band_min", hard_min))
            band_max = float(tier_cfg.get("reward_band_max", hard_max))

            if price < hard_min or price > hard_max:
                base_pen = float(tier_cfg.get("penalty_out_of_range", 0.0))
                if price < hard_min:
                    dist = (hard_min - price) / max(1e-9, hard_min)
                else:
                    dist = (price - hard_max) / max(1e-9, hard_max)
                scale = 1.0 + float(np.log1p(max(0.0, float(dist))))
                reward -= base_pen * scale
            else:
                if band_min <= price <= band_max:
                    reward += float(tier_cfg.get("reward_in_band", 0.0))
                else:
                    penalty = float(tier_cfg.get("penalty_out_of_band", 0.0))
                    if price < band_min:
                        denom = max(1e-9, band_min - hard_min)
                        ratio = (band_min - price) / denom
                    else:
                        denom = max(1e-9, hard_max - band_max)
                        ratio = (price - band_max) / denom
                    ratio = float(np.clip(ratio, 0.0, 1.0))
                    reward -= penalty * ratio
                
        elif vt_key == "low":
            # Low-value shaping:
            # - If out of hard range: apply out_of_range penalty only (avoid double-penalty with band)
            # - If within hard range but outside band: apply a smooth penalty proportional to distance
            hard_min = float(tier_cfg["price_min"])
            hard_max = float(tier_cfg["price_max"])
            band_min = float(tier_cfg["reward_band_min"])
            band_max = float(tier_cfg["reward_band_max"])

            if price < hard_min or price > hard_max:
                base_pen = float(tier_cfg.get("penalty_out_of_range", 0.0))
                # Scale penalty by log distance beyond the hard range so rare extreme
                # blow-ups get punished much harder than mild violations.
                if price < hard_min:
                    # Use multiplicative distance for low-end collapse: 0.1 vs 1.0 should hurt much more than 0.9 vs 1.0.
                    dist = (hard_min / max(1e-9, price)) - 1.0
                else:
                    dist = (price - hard_max) / max(1e-9, hard_max)
                scale = 1.0 + float(np.log1p(max(0.0, float(dist))))
                reward -= base_pen * scale
            else:
                if band_min <= price <= band_max:
                    reward += float(tier_cfg.get("reward_in_band", 0.0))
                else:
                    penalty = float(tier_cfg.get("penalty_out_of_band", 0.0))
                    if price < band_min:
                        denom = max(1e-9, band_min - hard_min)
                        ratio = (band_min - price) / denom
                        dist_mult = (band_min / max(1e-9, price)) - 1.0
                        scale = 1.0 + float(np.log1p(max(0.0, float(dist_mult))))
                    else:
                        denom = max(1e-9, hard_max - band_max)
                        ratio = (price - band_max) / denom
                        dist_mult = (price / max(1e-9, band_max)) - 1.0
                        scale = 1.0 + float(np.log1p(max(0.0, float(dist_mult))))
                    ratio = float(np.clip(ratio, 0.0, 1.0))
                    reward -= penalty * ratio * scale
                
        # 4. Check done
        done = self.step_count >= self.max_steps
        truncated = False
        
        self.last_price = self.amm.get_current_price()
        
        # Reward stabilization: scale + optional clip (training only; plugin doesn't use reward)
        reward = float(reward) * float(REWARD_SCALE)
        if REWARD_CLIP_ABS is not None:
            reward = float(np.clip(reward, -float(REWARD_CLIP_ABS), float(REWARD_CLIP_ABS)))

        info = {
            "price": float(price),
            "current_price": float(self.amm.get_current_price()),
            "base_price": float(self.amm.base_price),
            "k_factor": float(self.amm.k_factor),
            "current_inventory": int(self.amm.current_inventory),
            "target_inventory": int(self.amm.target_inventory),
            "physical_stock": int(self.amm.physical_stock),
            "frozen": bool(self._frozen),
            "trade_value": float(trade_value),
            "trade_qty": float(trade_qty),
            "inflation_rate": float(inflation_rate),
            "inflation_ratio": float(inflation_ratio),
            "inflation_metric": float(inflation_metric),
            "inventory_imbalance": float(inventory_imbalance),
            # Single-brain diagnostics: latent regime & treasury constraint
            "latent_value_type": str(getattr(self, "_latent_value_type", "low")),
            "treasury_balance": float(getattr(self.amm, "treasury_balance", 0.0)),
        }

        return self._get_obs(), float(reward), done, truncated, info
