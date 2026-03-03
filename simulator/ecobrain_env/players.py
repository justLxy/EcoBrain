import numpy as np

class Player:
    def __init__(self, name, balance=1000, rng=None):
        self.name = name
        self.balance = balance
        self.initial_balance = balance # Store initial balance to reset
        self.rng = rng if rng is not None else np.random.default_rng()
        
    def reset(self):
        self.balance = self.initial_balance # Reset balance
        
    def act(self, amm, step):
        pass

class NewPlayer(Player):
    """
    New player (新玩家): Occasionally buys or sells small amounts. Does not heavily impact the market alone.
    """
    def __init__(self, name, balance=1000, buy_probability=0.05, sell_probability=0.05, amount=5, rng=None):
        super().__init__(name, balance, rng=rng)
        self.buy_probability = buy_probability
        self.sell_probability = sell_probability
        self.amount = amount
        
    def act(self, amm, step):
        action_rand = float(self.rng.random())
        if action_rand < self.buy_probability:
            # Buy
            try:
                cost = amm.simulate_buy(self.amount)
                if self.balance >= cost:
                    actual_cost = amm.execute_buy(self.amount)
                    self.balance -= actual_cost
                    return self.amount, -actual_cost
            except Exception:
                return 0, 0
        elif action_rand < self.buy_probability + self.sell_probability:
            # Sell
            try:
                revenue = amm.execute_sell(self.amount)
                self.balance += revenue
                return -self.amount, revenue
            except Exception:
                return 0, 0
            
        return 0, 0

class VeteranPlayer(Player):
    """
    Veteran player (老玩家): Mostly produces and sells large amounts to the system, rarely buys.
    Generates significant sell pressure.
    """
    def __init__(self, name, balance=100000, buy_probability=0.02, sell_probability=0.8, buy_amount=10, sell_amount=64, rng=None):
        super().__init__(name, balance, rng=rng)
        self.buy_probability = buy_probability
        self.sell_probability = sell_probability
        self.buy_amount = buy_amount
        self.sell_amount = sell_amount
        
    def act(self, amm, step):
        action_rand = float(self.rng.random())
        if action_rand < self.buy_probability:
            # Buy
            try:
                cost = amm.simulate_buy(self.buy_amount)
                if self.balance >= cost:
                    actual_cost = amm.execute_buy(self.buy_amount)
                    self.balance -= actual_cost
                    return self.buy_amount, -actual_cost
            except Exception:
                return 0, 0
        elif action_rand < self.buy_probability + self.sell_probability:
            # Sell
            try:
                revenue = amm.execute_sell(self.sell_amount)
                self.balance += revenue
                return -self.sell_amount, revenue
            except Exception:
                return 0, 0
            
        return 0, 0

class ReplayPlayer(Player):
    """
    Driven by probabilities and amounts extracted from a real server's CSV data.
    """
    def __init__(self, name, buy_prob, sell_prob, avg_buy_amt, avg_sell_amt, rng=None):
        super().__init__(name, 10000000, rng=rng) # Give enough balance
        self.buy_prob = buy_prob
        self.sell_prob = sell_prob
        self.buy_amount = avg_buy_amt
        self.sell_amount = avg_sell_amt
        
    def act(self, amm, step):
        action_rand = float(self.rng.random())
        if action_rand < self.buy_prob:
            try:
                cost = amm.simulate_buy(self.buy_amount)
                if self.balance >= cost:
                    actual_cost = amm.execute_buy(self.buy_amount)
                    self.balance -= actual_cost
                    return self.buy_amount, -actual_cost
            except Exception:
                return 0, 0
        elif action_rand < self.buy_prob + self.sell_prob:
            try:
                revenue = amm.execute_sell(self.sell_amount)
                self.balance += revenue
                return -self.sell_amount, revenue
            except Exception:
                return 0, 0
            
        return 0, 0

class Arbitrageur(Player):
    """
    Looks for price discrepancies. Buys when price is low and TWAP is high, sells when price is high and TWAP is low.
    Or tries to manipulate price.
    """
    def __init__(self, name, balance=10000, rng=None):
        super().__init__(name, balance, rng=rng)
        self.inventory = 0

    def reset(self):
        super().reset()
        self.inventory = 0
        
    def act(self, amm, step):
        current_price = amm.get_current_price()
        twap = amm.get_twap()
        
        # If current price is significantly lower than TWAP, buy
        if current_price < twap * 0.8:
            quantity = 64
            try:
                cost = amm.simulate_buy(quantity)
                if self.balance >= cost:
                    amm.execute_buy(quantity)
                    self.balance -= cost
                    self.inventory += quantity
                    return quantity, -cost
            except Exception:
                return 0, 0
                
        # If current price is significantly higher than TWAP and we have inventory, sell
        elif current_price > twap * 1.2 and self.inventory > 0:
            quantity = min(self.inventory, 64)
            try:
                revenue = amm.simulate_sell(quantity)
                # Only sell if dynamic spread doesn't eat all profits
                if revenue > quantity * twap:
                    amm.execute_sell(quantity)
                    self.balance += revenue
                    self.inventory -= quantity
                    return -quantity, revenue
            except Exception:
                return 0, 0
                
        return 0, 0
