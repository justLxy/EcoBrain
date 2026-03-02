import numpy as np

class Player:
    def __init__(self, name, balance=1000):
        self.name = name
        self.balance = balance
        
    def act(self, amm, step):
        pass

class Farmer(Player):
    """
    Produces large amounts of low-value items and sells them constantly.
    """
    def __init__(self, name, production_rate=64, cost_per_item=0.0):
        super().__init__(name, 0)
        self.production_rate = production_rate
        
    def act(self, amm, step):
        # Always sells produced items
        if amm.is_ipo:
            # Maybe won't sell if it's IPO? or will sell at low price
            pass
        
        # Sells a batch
        revenue = amm.execute_sell(self.production_rate)
        self.balance += revenue
        return -self.production_rate, revenue # - means sell to system

class Whale(Player):
    """
    Buys high-value items regardless of price, up to a certain budget or need.
    """
    def __init__(self, name, buy_probability=0.1, buy_amount=10):
        super().__init__(name, 10000000)
        self.buy_probability = buy_probability
        self.buy_amount = buy_amount
        
    def act(self, amm, step):
        if np.random.random() < self.buy_probability:
            cost = amm.simulate_buy(self.buy_amount)
            if self.balance >= cost:
                actual_cost = amm.execute_buy(self.buy_amount)
                self.balance -= actual_cost
                return self.buy_amount, -actual_cost
        return 0, 0

class Arbitrageur(Player):
    """
    Looks for price discrepancies. Buys when price is low and TWAP is high, sells when price is high and TWAP is low.
    Or tries to manipulate price.
    """
    def __init__(self, name, balance=10000):
        super().__init__(name, balance)
        self.inventory = 0
        
    def act(self, amm, step):
        current_price = amm.get_current_price()
        twap = amm.get_twap()
        
        # If current price is significantly lower than TWAP, buy
        if current_price < twap * 0.8:
            quantity = 64
            cost = amm.simulate_buy(quantity)
            if self.balance >= cost:
                amm.execute_buy(quantity)
                self.balance -= cost
                self.inventory += quantity
                return quantity, -cost
                
        # If current price is significantly higher than TWAP and we have inventory, sell
        elif current_price > twap * 1.2 and self.inventory > 0:
            quantity = min(self.inventory, 64)
            revenue = amm.simulate_sell(quantity)
            # Only sell if dynamic spread doesn't eat all profits
            if revenue > quantity * twap:
                amm.execute_sell(quantity)
                self.balance += revenue
                self.inventory -= quantity
                return -quantity, revenue
                
        return 0, 0
