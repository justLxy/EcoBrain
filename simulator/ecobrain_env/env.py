import gymnasium as gym
from gymnasium import spaces
import numpy as np
from .amm import AMM
from .players import Farmer, Whale, Arbitrageur

class EcoBrainEnv(gym.Env):
    """
    Custom Environment for EcoBrain 2.0 PPO Training
    """
    metadata = {'render_modes': ['human']}

    def __init__(self, value_type="low"):
        super().__init__()
        
        self.value_type = value_type # "low", "mid", "high"
        
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
        
        target_inv = 1000
        current_inv = 500
        if self.value_type == "high":
            # Boss materials: extremely low inventory, very high value
            target_inv = 5
            current_inv = 2
        elif self.value_type == "mid":
            # MythicMobs drop materials: low/mid inventory, high value
            target_inv = 50
            current_inv = 25
            
        self.amm = AMM(
            base_price=0.01, 
            target_inventory=target_inv,
            current_inventory=current_inv,
            k_factor=1.0,
            is_ipo=True
        )
        
        # Initialize players
        self.players = []
        if self.value_type == "high":
            self.players.append(Whale("Whale1", buy_probability=0.2, buy_amount=2))
            self.players.append(Arbitrageur("Arb1", balance=50000))
        elif self.value_type == "mid":
            self.players.append(Farmer("Farmer1", production_rate=32))
            self.players.append(Whale("Whale1", buy_probability=0.1, buy_amount=5))
            self.players.append(Arbitrageur("Arb1", balance=5000))
        else: # low
            self.players.append(Farmer("Farmer1", production_rate=128))
            self.players.append(Farmer("Farmer2", production_rate=64))
            self.players.append(Arbitrageur("Arb1", balance=1000))
            self.players.append(Whale("Whale_Rare", buy_probability=0.01, buy_amount=10)) # Occasional buy
            
        self.step_count = 0
        
        # Stats for state calculation
        self.recent_buys = 0
        self.recent_sells = 0
        self.recent_buy_volume = 0
        self.recent_sell_volume = 0
        self.last_price = self.amm.get_current_price()
        
        # State: [saturation, flow, inflation, elasticity, volatility, is_ipo]
        return self._get_obs(), {}
        
    def _get_obs(self):
        saturation = self.amm.current_inventory / max(1, self.amm.target_inventory)
        flow = self.recent_buys - self.recent_sells
        inflation = self.recent_sell_volume - self.recent_buy_volume
        
        # Elasticity approximation: (Delta Q / Q) / (Delta P / P)
        # Simplified: recent flow change relative to price change
        current_price = self.amm.get_current_price()
        price_change = (current_price - self.last_price) / max(1e-6, self.last_price)
        elasticity = flow / max(1e-6, abs(price_change) * 100) # heuristic scaling
        
        volatility = abs(current_price - self.amm.get_twap()) / max(1e-6, self.amm.get_twap())
        
        return np.array([
            saturation,
            flow,
            inflation,
            elasticity,
            volatility,
            1.0 if self.amm.is_ipo else 0.0
        ], dtype=np.float32)

    def step(self, action):
        self.step_count += 1
        
        # 1. Apply AI action
        base_price_mult = 1.0 + (action[0] * 0.20) # -20% to +20%
        k_delta = action[1] * 0.1 # -0.1 to +0.1
        
        self.amm.apply_ai_action(base_price_mult, k_delta)
        
        # 2. Simulate player interactions for this cycle (e.g., representing 30 mins)
        self.recent_buys = 0
        self.recent_sells = 0
        self.recent_buy_volume = 0
        self.recent_sell_volume = 0
        
        # Let players act multiple times per AI cycle
        for _ in range(10):
            for p in self.players:
                qty, money = p.act(self.amm, self.step_count)
                if qty > 0: # Buy
                    self.recent_buys += qty
                    self.recent_buy_volume += abs(money)
                elif qty < 0: # Sell
                    self.recent_sells += abs(qty)
                    self.recent_sell_volume += abs(money)
                    
        # 3. Calculate Reward
        # We want: 
        # - Good trade volume (positive)
        # - Low inflation (penalize high system money emission)
        # - Inventory close to target
        # - For high value items: Price should go UP based on buys.
        # - For low value items: Price should stay LOW based on infinite sells.
        
        trade_volume = self.recent_buy_volume + self.recent_sell_volume
        net_emission = self.recent_sell_volume - self.recent_buy_volume
        inventory_imbalance = abs(self.amm.current_inventory - self.amm.target_inventory) / self.amm.target_inventory
        
        reward = float((0.1 * trade_volume) - (0.5 * max(0, net_emission)) - (10.0 * inventory_imbalance))
        
        # Specific shaping to help it learn the difference between garbage and gold
        if self.value_type == "high":
            # Boss mats: encourage scaling to hundreds of thousands or millions
            if self.amm.get_current_price() > 500000.0:
                reward += 10.0 # Huge bonus for finding the true boss value
            elif self.amm.get_current_price() > 50000.0:
                reward += 5.0
                
            if self.amm.current_inventory <= 0:
                reward -= 20.0 # Extremely strict about zero inventory for boss mats
                
        elif self.value_type == "mid":
            # MythicMobs mats: scale to thousands or tens of thousands
            if 1000.0 <= self.amm.get_current_price() <= 50000.0:
                reward += 5.0 
            if net_emission > 10000:
                reward -= 10.0
                
        else: # low
            # Vanilla mats: scale to tens of coins, extremely strict on inflation
            if self.amm.get_current_price() < 50.0:
                reward += 2.0 
            if net_emission > 500:
                reward -= 30.0 # Unforgiving penalty for letting farmers print money
                
        # 4. Check done
        done = self.step_count >= self.max_steps
        truncated = False
        
        self.last_price = self.amm.get_current_price()
        
        return self._get_obs(), float(reward), done, truncated, {}
