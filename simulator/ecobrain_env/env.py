import gymnasium as gym
from gymnasium import spaces
import numpy as np
import csv
import os
from collections import defaultdict, deque
from .amm import AMM
from .players import Arbitrageur
from .ecosystem import Regime, build_players_from_archetypes, sample_regime
from .ecosystem import sample_from_spec
from .config import (
    TIERS,
    VALUE_MIX,
    ACTION_BASE_PRICE_MAX_PERCENT,
    ACTION_K_FACTOR_MAX_DELTA,
    ACTION_INACTIVITY_DECAY,
    ADAPTIVE_TARGET_ENABLED,
    ADAPTIVE_TARGET_SMOOTHING_FACTOR,
    ADAPTIVE_TARGET_QUANTITY_CAP,
    MAX_BASE_PRICE,
    MIN_BASE_PRICE,
    K_MIN,
    K_MAX,
    SCHEDULE_MINUTES,
    AOV_WINDOW_HOURS,
    IPO_BASE_PRICE_FALLBACK,
    IPO_RESET_PROB,
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
        self._dataset_cache = None
        self._dataset_cache_path = None
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
        band_min = float(tier_cfg.get("reward_band_min", tier_cfg["price_min"]))
        band_max = float(tier_cfg.get("reward_band_max", tier_cfg.get("price_max", band_min)))
        if band_min > 0.0 and band_max > 0.0:
            self.amm.set_reference_price(float(np.sqrt(band_min * band_max)))
        else:
            self.amm.set_reference_price(float(max(base_price, MIN_BASE_PRICE)))
        
        # === Plugin-aligned macro signals ===
        self.schedule_minutes = max(1, int(SCHEDULE_MINUTES))
        self.aov_window_hours = max(1, int(AOV_WINDOW_HOURS))
        self.aov_cycles = max(1, int(round((self.aov_window_hours * 60.0) / self.schedule_minutes)))

        # Per-cycle rolling windows: keep global macro stats separate from the
        # controlled item's own activity window so obs[2]/obs[15] remain global
        # while obs[7]/obs[8] stay item-specific.
        self._global_aov_window = deque(maxlen=self.aov_cycles)
        self._item_activity_window = deque(maxlen=self.aov_cycles)
        self._unit_price_window = deque(maxlen=self.aov_cycles)
        self._prev_unit_price = 0.0

        self._dataset_selected_hash = None
        self._dataset_cycle_offset = 0
        self._dataset_item_cycles = {}
        self._dataset_global_cycles = {}

        # Initialize players / replay sources
        self.players = []
        if self.dataset_path and os.path.exists(self.dataset_path):
            if self._load_dataset_players():
                # Keep a small amount of opportunistic pressure even during replay.
                self.players.append(Arbitrageur("Arb1", balance=50000, rng=self.np_random))
            else:
                self.players = self._build_simulated_players()
        else:
            self.players = self._build_simulated_players()

        self.step_count = 0
        self._prev_saturation = float(current_inv) / max(1.0, float(target_inv))

        # Circuit breaker (simplified, aligned semantics)
        self.daily_limit = float(DAILY_LIMIT_PERCENT)
        self.critical_inventory = int(CRITICAL_INVENTORY)
        self._frozen = False
        self._day_index = 0
        self._day_open_price = self.amm.get_current_price()

        # Stats for state calculation
        self.recent_buys = 0
        self.recent_sells = 0
        self.recent_buy_volume = 0.0
        self.recent_sell_volume = 0.0
        self._global_recent_buy_volume = 0.0
        self._global_recent_sell_volume = 0.0
        self.last_price = self.amm.get_current_price()

        # Single global treasury shared by the controlled item and background market.
        self.amm.treasury_balance = float(TREASURY_INITIAL_BALANCE)

        # Reset all simulated players
        for player in self.players:
            if hasattr(player, "reset"):
                player.reset()

        return self._get_obs(), {}
        
    def _build_simulated_players(self):
        rand_cfg = ECOSYSTEM_RANDOMIZATION or {}
        rand_enabled = bool(rand_cfg.get("enabled", False))
        if rand_enabled:
            resample_each_reset = bool(rand_cfg.get("resample_each_reset", True))
            if (not resample_each_reset) and (self._static_players is not None):
                return self._static_players

            regime = sample_regime(MARKET_REGIMES, rng=self.np_random)
            vt = getattr(self, "_latent_value_type", "low")
            archetypes = SIMULATED_PLAYER_ARCHETYPES.get(vt, [])
            players = build_players_from_archetypes(
                value_type=vt,
                archetypes=archetypes,
                regime=regime,
                rng=self.np_random,
            )
            if not resample_each_reset:
                self._static_players = players
            return players

        vt = getattr(self, "_latent_value_type", "low")
        archetypes = SIMULATED_PLAYER_ARCHETYPES.get(vt, [])
        return build_players_from_archetypes(
            value_type=vt,
            archetypes=archetypes,
            regime=Regime(name="fixed"),
            rng=self.np_random,
        )

    def _classify_dataset_item(self, avg_unit_price: float) -> str:
        if avg_unit_price <= float(TIERS["low"]["price_max"]):
            return "low"
        if avg_unit_price <= float(TIERS["mid"]["price_max"]):
            return "mid"
        return "high"

    def _ensure_dataset_cache(self) -> bool:
        if self._dataset_cache is not None and self._dataset_cache_path == self.dataset_path:
            return True
        if not self.dataset_path or not os.path.exists(self.dataset_path):
            return False

        cycle_ms = int(self.schedule_minutes * 60 * 1000)
        raw_rows = []
        min_created_at = None

        try:
            with open(self.dataset_path, "r", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    try:
                        item_hash = str(row.get("item_hash", "")).strip()
                        trade_type = str(row.get("trade_type", "")).strip().upper()
                        quantity = int(row.get("quantity", 0))
                        total_price = float(row.get("total_price", 0.0))
                        created_at = int(row.get("created_at", 0))
                    except Exception:
                        continue

                    if not item_hash or trade_type not in {"BUY", "SELL"} or quantity <= 0 or created_at < 0:
                        continue

                    raw_rows.append(
                        {
                            "item_hash": item_hash,
                            "trade_type": trade_type,
                            "quantity": int(quantity),
                            "total_price": float(abs(total_price)),
                            "created_at": int(created_at),
                        }
                    )
                    if min_created_at is None or created_at < min_created_at:
                        min_created_at = int(created_at)
        except Exception as e:
            print(f"Error loading dataset {self.dataset_path}: {e}")
            self._dataset_cache = None
            self._dataset_cache_path = None
            return False

        if not raw_rows or min_created_at is None:
            print(f"Warning: Dataset {self.dataset_path} is empty. Falling back to simulated players.")
            self._dataset_cache = None
            self._dataset_cache_path = None
            return False

        global_cycles = defaultdict(list)
        item_cycles = defaultdict(lambda: defaultdict(list))
        item_stats = defaultdict(lambda: {"value_sum": 0.0, "qty_sum": 0, "event_count": 0, "min_cycle": None, "max_cycle": 0})
        max_cycle = 0

        for row in raw_rows:
            cycle_idx = int(max(0, (int(row["created_at"]) - int(min_created_at)) // max(1, cycle_ms)))
            event = {
                "trade_type": str(row["trade_type"]),
                "quantity": int(row["quantity"]),
                "total_price": float(row["total_price"]),
            }
            global_cycles[cycle_idx].append(event)
            item_hash = str(row["item_hash"])
            item_cycles[item_hash][cycle_idx].append(event)

            stats = item_stats[item_hash]
            stats["value_sum"] += float(row["total_price"])
            stats["qty_sum"] += int(row["quantity"])
            stats["event_count"] += 1
            stats["min_cycle"] = cycle_idx if stats["min_cycle"] is None else min(int(stats["min_cycle"]), cycle_idx)
            stats["max_cycle"] = max(int(stats["max_cycle"]), cycle_idx)
            max_cycle = max(max_cycle, cycle_idx)

        dataset_items = {}
        for item_hash, stats in item_stats.items():
            avg_unit_price = float(stats["value_sum"]) / max(1, int(stats["qty_sum"]))
            dataset_items[item_hash] = {
                "avg_unit_price": float(avg_unit_price),
                "tier": self._classify_dataset_item(avg_unit_price),
                "event_count": int(stats["event_count"]),
                "min_cycle": int(stats["min_cycle"] or 0),
                "max_cycle": int(stats["max_cycle"]),
                "cycles": {int(k): list(v) for k, v in item_cycles[item_hash].items()},
            }

        self._dataset_cache = {
            "global_cycles": {int(k): list(v) for k, v in global_cycles.items()},
            "items": dataset_items,
            "max_cycle": int(max_cycle),
        }
        self._dataset_cache_path = self.dataset_path
        return True

    def _load_dataset_players(self):
        if not self._ensure_dataset_cache():
            self._dataset_selected_hash = None
            self._dataset_item_cycles = {}
            self._dataset_global_cycles = {}
            return False

        cache = self._dataset_cache or {}
        items = cache.get("items", {})
        if not items:
            self._dataset_selected_hash = None
            self._dataset_item_cycles = {}
            self._dataset_global_cycles = {}
            return False

        vt = getattr(self, "_latent_value_type", "low")
        candidate_hashes = [h for h, meta in items.items() if meta.get("tier") == vt]
        if not candidate_hashes:
            candidate_hashes = list(items.keys())

        weights = np.array([max(1, int(items[h].get("event_count", 1))) for h in candidate_hashes], dtype=np.float64)
        weights = weights / max(1e-9, weights.sum())
        selected_hash = str(self.np_random.choice(candidate_hashes, p=weights))
        selected_meta = items[selected_hash]

        max_cycle = int(cache.get("max_cycle", 0))
        max_start = max(0, max_cycle - self.max_steps + 1)
        selected_min_cycle = int(selected_meta.get("min_cycle", 0))
        selected_max_cycle = int(selected_meta.get("max_cycle", 0))
        preferred_start = max(0, min(selected_min_cycle, max_start))
        latest_useful_start = max(0, min(selected_max_cycle, max_start))
        if latest_useful_start >= preferred_start:
            self._dataset_cycle_offset = int(self.np_random.integers(preferred_start, latest_useful_start + 1))
        else:
            self._dataset_cycle_offset = 0

        self._dataset_selected_hash = selected_hash
        self._dataset_item_cycles = dict(selected_meta.get("cycles", {}))
        self._dataset_global_cycles = dict(cache.get("global_cycles", {}))

        avg_unit_price = float(selected_meta.get("avg_unit_price", 0.0))
        print(
            f"[{self.value_type}] Loaded dataset replay: "
            f"item={selected_hash[:8]} tier={selected_meta.get('tier')} "
            f"avg_unit_price={avg_unit_price:.2f} start_cycle={self._dataset_cycle_offset}"
        )
        return True
            
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

        cycle_net_emission = float(self._global_recent_sell_volume - self._global_recent_buy_volume)
        dynamic_aov = float(self._compute_dynamic_aov())
        global_inflation_rate = cycle_net_emission / max(1e-9, dynamic_aov)
        cycle_volume = float(self._global_recent_sell_volume + self._global_recent_buy_volume)
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

        activity_value = float(sum(self._item_activity_window)) if self._item_activity_window else 0.0
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

    def _has_activity_trade(self) -> bool:
        return bool(self._item_activity_window and sum(self._item_activity_window) > 0.0)

    def _current_cycle_index(self) -> int:
        return int(self._dataset_cycle_offset + max(0, self.step_count - 1))

    def _compute_dataset_background_cycle_stats(self, cycle_idx: int):
        events = self._dataset_global_cycles.get(int(cycle_idx), [])
        if not events:
            return {
                "value_sum": 0.0,
                "count": 0,
                "buy_money": 0.0,
                "sell_money": 0.0,
            }

        item_events = self._dataset_item_cycles.get(int(cycle_idx), [])
        selected_signature = {}
        for event in item_events:
            key = (
                str(event.get("trade_type", "")),
                int(event.get("quantity", 0)),
                round(float(event.get("total_price", 0.0)), 6),
            )
            selected_signature[key] = selected_signature.get(key, 0) + 1

        stats = {"value_sum": 0.0, "count": 0, "buy_money": 0.0, "sell_money": 0.0}
        for event in events:
            key = (
                str(event.get("trade_type", "")),
                int(event.get("quantity", 0)),
                round(float(event.get("total_price", 0.0)), 6),
            )
            if selected_signature.get(key, 0) > 0:
                selected_signature[key] -= 1
                continue

            total_price = float(event.get("total_price", 0.0))
            trade_type = str(event.get("trade_type", ""))
            stats["value_sum"] += total_price
            stats["count"] += 1
            if trade_type == "BUY":
                stats["buy_money"] += total_price
            elif trade_type == "SELL":
                stats["sell_money"] += total_price
        return stats

    def _apply_dataset_replay_for_cycle(self, cycle_idx: int):
        cycle_trade_value_sum = 0.0
        cycle_trade_count = 0
        cycle_trade_qty_sum = 0
        did_trade = False

        for event in self._dataset_item_cycles.get(int(cycle_idx), []):
            trade_type = str(event.get("trade_type", ""))
            quantity = max(0, int(event.get("quantity", 0)))
            if quantity <= 0:
                continue

            try:
                if trade_type == "BUY":
                    money = float(self.amm.execute_buy(quantity))
                    did_trade = True
                    self._adaptive_target_trade_update(quantity)
                    self.recent_buys += int(quantity)
                    self.recent_buy_volume += abs(money)
                    cycle_trade_value_sum += abs(money)
                    cycle_trade_count += 1
                    cycle_trade_qty_sum += int(quantity)
                elif trade_type == "SELL":
                    money = float(self.amm.execute_sell(quantity))
                    did_trade = True
                    self._adaptive_target_trade_update(quantity)
                    self.recent_sells += int(quantity)
                    self.recent_sell_volume += abs(money)
                    cycle_trade_value_sum += abs(money)
                    cycle_trade_count += 1
                    cycle_trade_qty_sum += int(quantity)
            except Exception:
                continue

        return did_trade, cycle_trade_value_sum, cycle_trade_count, cycle_trade_qty_sum

    def _compute_adaptive_target(self, trade_quantity: int | None = None, quantity_aware: bool = False) -> int:
        old_target = int(self.amm.target_inventory)
        physical = int(self.amm.physical_stock)
        alpha = float(ADAPTIVE_TARGET_SMOOTHING_FACTOR)
        if quantity_aware and trade_quantity is not None:
            cap = max(1, int(ADAPTIVE_TARGET_QUANTITY_CAP))
            m = min(max(1, int(abs(trade_quantity))), cap)
            alpha = 1.0 - float(np.power(max(0.0, 1.0 - alpha), m))

        ema = old_target + (physical - old_target) * alpha
        new_target = int(round(ema))
        if new_target == old_target and physical != old_target:
            new_target += 1 if physical > old_target else -1
        return max(1, int(new_target))

    def _apply_scaled_target(self, new_target: int):
        old_target = int(self.amm.target_inventory)
        if int(new_target) == old_target:
            return

        old_current = int(self.amm.current_inventory)
        scaled_current = int(round(old_current * (float(new_target) / max(1.0, float(old_target)))))
        self.amm.target_inventory = int(new_target)
        self.amm.current_inventory = max(1, int(scaled_current))

    def _adaptive_target_trade_update(self, trade_quantity: int):
        if not bool(ADAPTIVE_TARGET_ENABLED) or self.amm is None:
            return
        self._apply_scaled_target(self._compute_adaptive_target(trade_quantity=trade_quantity, quantity_aware=True))

    def _adaptive_target_scheduler_update(self):
        if not bool(ADAPTIVE_TARGET_ENABLED) or self.amm is None:
            return
        self._apply_scaled_target(self._compute_adaptive_target(quantity_aware=False))

    def _compute_twap_price(self, fallback: float) -> float:
        if not self._unit_price_window:
            return float(fallback)
        # time-weighted by cycle buckets: arithmetic mean across buckets
        twap = float(sum(self._unit_price_window)) / float(len(self._unit_price_window))
        return float(twap) if twap > 0 else float(fallback)

    def _compute_dynamic_aov(self) -> float:
        total_value = 0.0
        total_count = 0
        for v, c in self._global_aov_window:
            total_value += float(v)
            total_count += int(c)
        if total_count > 0 and total_value > 0:
            return total_value / total_count
        # 插件端 fallback：若无交易则使用 IPO base price * 20
        return float(IPO_BASE_PRICE_FALLBACK) * 20.0

    def _compute_trade_signal(self, vt_key: str, trade_value: float, trade_qty: float, dynamic_aov: float) -> tuple[float, float]:
        if vt_key == "high":
            normalized = max(0.0, float(trade_value) / max(1e-9, float(dynamic_aov)))
            return float(np.log1p(normalized)), float(REWARD_TRADE_LOG_VALUE_WEIGHT_HIGH)
        qty_signal = float(np.log1p(max(0.0, float(trade_qty))))
        if vt_key == "mid":
            return qty_signal, float(REWARD_TRADE_QTY_WEIGHT_MID)
        return qty_signal, float(REWARD_TRADE_QTY_WEIGHT)

    def _compute_price_shaping(self, price: float, tier_cfg: dict) -> float:
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
            return -base_pen * (1.0 + float(np.log1p(max(0.0, dist))))

        if band_min <= price <= band_max:
            reward_in_band = float(tier_cfg.get("reward_in_band", 0.0))
            if band_min <= 0.0 or band_max <= 0.0 or band_min == band_max:
                return reward_in_band
            center = float(np.sqrt(band_min * band_max))
            half_width = max(1e-9, 0.5 * abs(np.log(band_max / band_min)))
            closeness = 1.0 - (abs(np.log(price) - np.log(center)) / half_width)
            closeness = float(np.clip(closeness, 0.0, 1.0))
            return reward_in_band * (0.5 + 0.5 * closeness)

        penalty = float(tier_cfg.get("penalty_out_of_band", 0.0))
        if price < band_min:
            denom = max(1e-9, band_min - hard_min)
            ratio = (band_min - price) / denom
        else:
            denom = max(1e-9, hard_max - band_max)
            ratio = (price - band_max) / denom
        ratio = float(np.clip(ratio, 0.0, 1.5))
        return -penalty * ratio

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

        # Mirror the live scheduler: without recent activity the policy output is
        # ignored and the item stays on HOLD until there is real trade evidence.
        activity_gate = 1.0 if self._has_activity_trade() else float(ACTION_INACTIVITY_DECAY)
        effective_action = np.asarray(action, dtype=np.float32) * float(activity_gate)

        # 1. Apply AI action
        raw_mult = 1.0 + (float(effective_action[0]) * float(ACTION_BASE_PRICE_MAX_PERCENT))
        k_delta_cap = float(ACTION_K_FACTOR_MAX_DELTA)
        raw_k_delta = float(effective_action[1]) * float(k_delta_cap)

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
        self.recent_buy_volume = 0.0
        self.recent_sell_volume = 0.0
        self._global_recent_buy_volume = 0.0
        self._global_recent_sell_volume = 0.0

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

        cycle_idx = self._current_cycle_index()
        if self._dataset_selected_hash is not None:
            replay_did_trade, replay_value, replay_count, replay_qty = self._apply_dataset_replay_for_cycle(cycle_idx)
            did_trade = bool(did_trade or replay_did_trade)
            cycle_trade_value_sum += float(replay_value)
            cycle_trade_count += int(replay_count)
            cycle_trade_qty_sum += int(replay_qty)

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
                    self._adaptive_target_trade_update(int(abs(qty)))
                    self.recent_buys += qty
                    self.recent_buy_volume += abs(money)
                    cycle_trade_value_sum += abs(money)
                    cycle_trade_count += 1
                    cycle_trade_qty_sum += int(abs(qty))
                elif qty < 0: # Sell
                    did_trade = True
                    self._adaptive_target_trade_update(int(abs(qty)))
                    self.recent_sells += abs(qty)
                    self.recent_sell_volume += abs(money)
                    cycle_trade_value_sum += abs(money)
                    cycle_trade_count += 1
                    cycle_trade_qty_sum += int(abs(qty))

        background_stats = {
            "value_sum": 0.0,
            "count": 0,
            "buy_money": 0.0,
            "sell_money": 0.0,
        }
        if self._dataset_selected_hash is not None:
            background_stats = self._compute_dataset_background_cycle_stats(cycle_idx)

        self._item_activity_window.append(float(cycle_trade_value_sum))
        self._global_recent_buy_volume = float(self.recent_buy_volume + background_stats["buy_money"])
        self._global_recent_sell_volume = float(self.recent_sell_volume + background_stats["sell_money"])
        self._global_aov_window.append((
            float(cycle_trade_value_sum + background_stats["value_sum"]),
            int(cycle_trade_count + background_stats["count"]),
        ))
        self.amm.treasury_balance = float(max(
            0.0,
            float(self.amm.treasury_balance) + float(background_stats["buy_money"]) - float(background_stats["sell_money"]),
        ))

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
        
        # Scheduler-side EMA runs once every cycle on the live server, on top of
        # any quantity-aware updates triggered by individual trades.
        self._adaptive_target_scheduler_update()
        self._age_cycles = int(self._age_cycles) + 1

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

        trade_signal, trade_weight = self._compute_trade_signal(
            vt_key=vt_key,
            trade_value=trade_value,
            trade_qty=trade_qty,
            dynamic_aov=dynamic_aov,
        )

        # Use scale-invariant inflation metric for ALL regimes to avoid "price washing"
        # and keep mixed-regime training stable.
        inflation_metric = float(inflation_ratio)
        infl_mult = float(tier_cfg.get("inflation_weight_mult", 1.0))
        inflation_penalty = (REWARD_INFLATION_RATE_WEIGHT * infl_mult) * max(0.0, float(inflation_metric))

        inv_weight = REWARD_INVENTORY_IMBALANCE_WEIGHT
        if vt_key == "low":
            inv_weight = REWARD_INVENTORY_IMBALANCE_WEIGHT_LOW

        reward = float(
            (float(trade_weight) * float(trade_signal))
            - float(inflation_penalty)
            - (float(inv_weight) * float(np.clip(inventory_imbalance, 0.0, 3.0)))
        )

        # Mild action penalty to discourage saturating controls (tier-scaled).
        try:
            act_mult = float(tier_cfg.get("action_l1_mult", 1.0))
            reward -= (float(REWARD_ACTION_L1_WEIGHT) * act_mult) * (
                abs(float(effective_action[0])) + abs(float(effective_action[1]))
            )
        except Exception:
            pass
        
        price = float(self.amm.get_current_price())
        reward += float(self._compute_price_shaping(price, tier_cfg))

        if vt_key == "high" and int(self.amm.physical_stock) <= 0:
            reward -= float(tier_cfg.get("empty_stock_penalty", 0.0))
                
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
