import numpy as np

class AMM:
    def __init__(self, base_price, target_inventory, current_inventory, k_factor, is_ipo=False):
        self.base_price = base_price
        self.target_inventory = target_inventory
        self.current_inventory = current_inventory
        self.k_factor = k_factor
        self.is_ipo = is_ipo
        
        self.twap_window = []
        self.twap_period = 24  # Let's say 24 steps
        self.volatility_spread = 0.05
        self.base_spread = 0.05
        
    def get_current_price(self):
        if self.current_inventory <= 0:
            return self.base_price * (self.target_inventory / 0.1) ** self.k_factor
        return self.base_price * (self.target_inventory / self.current_inventory) ** self.k_factor

    def simulate_buy(self, quantity):
        # User buys from system (system inventory decreases)
        total_price = 0
        if self.is_ipo:
            # First buy unlocks IPO
            self.is_ipo = False
            
        for i in range(quantity):
            inv = max(0.1, self.current_inventory - i)
            price = self.base_price * (self.target_inventory / inv) ** self.k_factor
            total_price += price
            
        # Update TWAP
        self._update_twap(total_price / quantity if quantity > 0 else 0)
        return total_price

    def simulate_sell(self, quantity):
        # User sells to system (system inventory increases)
        total_price = 0
        for i in range(1, quantity + 1):
            inv = self.current_inventory + i
            price = self.base_price * (self.target_inventory / inv) ** self.k_factor
            total_price += price
            
        # Apply spread
        spread = self.calculate_dynamic_spread()
        final_price = total_price * (1.0 - spread)
        
        # Update TWAP
        self._update_twap(total_price / quantity if quantity > 0 else 0)
        return final_price
        
    def execute_buy(self, quantity):
        cost = self.simulate_buy(quantity)
        self.current_inventory -= quantity
        if self.current_inventory < 0:
            self.current_inventory = 0
        return cost
        
    def execute_sell(self, quantity):
        revenue = self.simulate_sell(quantity)
        self.current_inventory += quantity
        return revenue

    def _update_twap(self, current_avg_price):
        if current_avg_price > 0:
            self.twap_window.append(current_avg_price)
            if len(self.twap_window) > self.twap_period:
                self.twap_window.pop(0)
            
    def get_twap(self):
        if not self.twap_window:
            return self.get_current_price()
        return sum(self.twap_window) / len(self.twap_window)
        
    def calculate_dynamic_spread(self):
        if len(self.twap_window) < 2:
            return self.base_spread
            
        current_price = self.get_current_price()
        twap = self.get_twap()
        
        # Volatility = |Current - TWAP| / TWAP
        volatility = abs(current_price - twap) / (twap + 1e-6)
        
        # dynamic spread scales with volatility, cap at 50%
        dynamic_spread = self.base_spread + volatility * 0.5
        return min(0.50, dynamic_spread)

    def apply_ai_action(self, base_price_multiplier, k_delta):
        # PPO outputs continuous actions
        self.base_price *= base_price_multiplier
        self.base_price = max(0.01, min(self.base_price, 1000000.0)) # bounds
        
        self.k_factor += k_delta
        self.k_factor = max(0.1, min(self.k_factor, 5.0))
