import numpy as np

class Player:
    def __init__(
        self,
        name,
        balance=1000,
        item_inventory: int = 0,
        produce_lambda: float = 0.0,
        consume_lambda: float = 0.0,
        rng=None,
    ):
        self.name = name
        self.balance = balance
        self.initial_balance = balance # Store initial balance to reset
        self.item_inventory = int(item_inventory)
        self.initial_item_inventory = int(item_inventory)
        self.produce_lambda = max(0.0, float(produce_lambda))
        self.consume_lambda = max(0.0, float(consume_lambda))
        self.rng = rng if rng is not None else np.random.default_rng()
        self.price_response_strength = 1.0
        
    def reset(self):
        self.balance = self.initial_balance # Reset balance
        self.item_inventory = self.initial_item_inventory

    def tick(self):
        """
        Exogenous supply/demand to mimic real servers:
        - produce_lambda: expected items produced per tick (Poisson)
        - consume_lambda: expected items consumed per tick (Poisson)
        """
        if self.produce_lambda > 0.0:
            self.item_inventory += int(self.rng.poisson(self.produce_lambda))
        if self.consume_lambda > 0.0 and self.item_inventory > 0:
            c = int(self.rng.poisson(self.consume_lambda))
            if c > 0:
                self.item_inventory = max(0, int(self.item_inventory) - int(c))
        
    def act(self, amm, step):
        pass

    def _price_edge(self, amm):
        current_price = float(amm.get_current_price())
        reference_price = float(amm.get_reference_price())
        twap = float(amm.get_twap())

        ref_edge = (reference_price - current_price) / max(1e-9, reference_price)
        twap_edge = (twap - current_price) / max(1e-9, twap)
        blended_edge = (0.65 * ref_edge) + (0.35 * twap_edge)
        return float(np.clip(blended_edge, -2.0, 2.0))

    def _price_multipliers(self, amm):
        edge = self._price_edge(amm)
        strength = max(0.0, float(getattr(self, "price_response_strength", 1.0)))
        buy_mult = float(np.clip(np.exp(strength * edge), 0.10, 6.0))
        sell_mult = float(np.clip(np.exp(-strength * edge), 0.10, 6.0))
        qty_buy_mult = float(np.clip(np.exp(0.5 * strength * edge), 0.50, 3.0))
        qty_sell_mult = float(np.clip(np.exp(-0.5 * strength * edge), 0.50, 3.0))
        return buy_mult, sell_mult, qty_buy_mult, qty_sell_mult

class NewPlayer(Player):
    """
    New player (新玩家): Occasionally buys or sells small amounts. Does not heavily impact the market alone.
    """
    def __init__(
        self,
        name,
        balance=1000,
        buy_probability=0.05,
        sell_probability=0.05,
        amount=5,
        buy_inventory_target: int = 0,
        sell_inventory_threshold: int = 0,
        item_inventory: int = 0,
        produce_lambda: float = 0.0,
        consume_lambda: float = 0.0,
        price_response_strength: float = 1.0,
        rng=None,
    ):
        super().__init__(
            name,
            balance,
            item_inventory=item_inventory,
            produce_lambda=produce_lambda,
            consume_lambda=consume_lambda,
            rng=rng,
        )
        self.buy_probability = buy_probability
        self.sell_probability = sell_probability
        self.amount = amount
        self.buy_inventory_target = int(buy_inventory_target)
        self.sell_inventory_threshold = int(sell_inventory_threshold)
        self.price_response_strength = max(0.0, float(price_response_strength))
        
    def act(self, amm, step):
        buy_mult, sell_mult, qty_buy_mult, qty_sell_mult = self._price_multipliers(amm)
        buy_probability = float(np.clip(float(self.buy_probability) * buy_mult, 0.0, 0.99))
        sell_probability = float(np.clip(float(self.sell_probability) * sell_mult, 0.0, 0.99))
        action_rand = float(self.rng.random())
        if action_rand < buy_probability:
            # Buy
            if int(self.item_inventory) >= int(self.buy_inventory_target) and int(self.buy_inventory_target) > 0:
                return 0, 0
            try:
                qty = max(1, int(round(float(self.amount) * qty_buy_mult)))
                cost = amm.simulate_buy(qty)
                if self.balance >= cost:
                    actual_cost = amm.execute_buy(qty)
                    self.balance -= actual_cost
                    self.item_inventory += int(qty)
                    return qty, -actual_cost
            except Exception:
                return 0, 0
        elif action_rand < buy_probability + sell_probability:
            # Sell
            if int(self.item_inventory) <= int(self.sell_inventory_threshold):
                return 0, 0
            try:
                qty = max(1, int(round(float(self.amount) * qty_sell_mult)))
                qty = min(int(qty), int(self.item_inventory))
                if qty <= 0:
                    return 0, 0
                revenue = amm.execute_sell(qty)
                self.balance += revenue
                self.item_inventory = max(0, int(self.item_inventory) - int(qty))
                return -qty, revenue
            except Exception:
                return 0, 0
            
        return 0, 0

class VeteranPlayer(Player):
    """
    Veteran player (老玩家): Mostly produces and sells large amounts to the system, rarely buys.
    Generates significant sell pressure.
    """
    def __init__(
        self,
        name,
        balance=100000,
        buy_probability=0.02,
        sell_probability=0.8,
        buy_amount=10,
        sell_amount=64,
        buy_inventory_target: int = 0,
        sell_inventory_threshold: int = 0,
        item_inventory: int = 0,
        produce_lambda: float = 0.0,
        consume_lambda: float = 0.0,
        price_response_strength: float = 1.0,
        rng=None,
    ):
        super().__init__(
            name,
            balance,
            item_inventory=item_inventory,
            produce_lambda=produce_lambda,
            consume_lambda=consume_lambda,
            rng=rng,
        )
        self.buy_probability = buy_probability
        self.sell_probability = sell_probability
        self.buy_amount = buy_amount
        self.sell_amount = sell_amount
        self.buy_inventory_target = int(buy_inventory_target)
        self.sell_inventory_threshold = int(sell_inventory_threshold)
        self.price_response_strength = max(0.0, float(price_response_strength))
        
    def act(self, amm, step):
        buy_mult, sell_mult, qty_buy_mult, qty_sell_mult = self._price_multipliers(amm)
        buy_probability = float(np.clip(float(self.buy_probability) * buy_mult, 0.0, 0.99))
        sell_probability = float(np.clip(float(self.sell_probability) * sell_mult, 0.0, 0.99))
        action_rand = float(self.rng.random())
        if action_rand < buy_probability:
            # Buy
            if int(self.item_inventory) >= int(self.buy_inventory_target) and int(self.buy_inventory_target) > 0:
                return 0, 0
            try:
                qty = max(1, int(round(float(self.buy_amount) * qty_buy_mult)))
                cost = amm.simulate_buy(qty)
                if self.balance >= cost:
                    actual_cost = amm.execute_buy(qty)
                    self.balance -= actual_cost
                    self.item_inventory += int(qty)
                    return qty, -actual_cost
            except Exception:
                return 0, 0
        elif action_rand < buy_probability + sell_probability:
            # Sell
            if int(self.item_inventory) <= int(self.sell_inventory_threshold):
                return 0, 0
            try:
                qty = max(1, int(round(float(self.sell_amount) * qty_sell_mult)))
                qty = min(int(qty), int(self.item_inventory))
                if qty <= 0:
                    return 0, 0
                revenue = amm.execute_sell(qty)
                self.balance += revenue
                self.item_inventory = max(0, int(self.item_inventory) - int(qty))
                return -qty, revenue
            except Exception:
                return 0, 0
            
        return 0, 0

class ReplayPlayer(Player):
    """
    Legacy probability-based replay helper.

    The environment now replays dataset CSVs by time bucket directly in
    `EcoBrainEnv`, so this class is kept only for backward compatibility.
    """
    def __init__(
        self,
        name,
        buy_prob,
        sell_prob,
        avg_buy_amt,
        avg_sell_amt,
        item_inventory: int = 0,
        rng=None,
    ):
        super().__init__(name, 10000000, item_inventory=item_inventory, rng=rng) # Give enough balance
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
                    self.item_inventory += int(self.buy_amount)
                    return self.buy_amount, -actual_cost
            except Exception:
                return 0, 0
        elif action_rand < self.buy_prob + self.sell_prob:
            try:
                qty = min(int(self.sell_amount), int(self.item_inventory))
                if qty <= 0:
                    return 0, 0
                revenue = amm.execute_sell(qty)
                self.balance += revenue
                self.item_inventory = max(0, int(self.item_inventory) - int(qty))
                return -qty, revenue
            except Exception:
                return 0, 0
            
        return 0, 0

class Arbitrageur(Player):
    """
    Looks for price discrepancies. Buys when price is low and TWAP is high, sells when price is high and TWAP is low.
    Or tries to manipulate price.
    """
    def __init__(self, name, balance=10000, rng=None):
        super().__init__(name, balance, item_inventory=0, rng=rng)

    def reset(self):
        super().reset()
        
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
                    self.item_inventory += quantity
                    return quantity, -cost
            except Exception:
                return 0, 0
                
        # If current price is significantly higher than TWAP and we have inventory, sell
        elif current_price > twap * 1.2 and self.item_inventory > 0:
            quantity = min(self.item_inventory, 64)
            try:
                revenue = amm.simulate_sell(quantity)
                # Only sell if dynamic spread doesn't eat all profits
                if revenue > quantity * twap:
                    amm.execute_sell(quantity)
                    self.balance += revenue
                    self.item_inventory -= quantity
                    return -quantity, revenue
            except Exception:
                return 0, 0
                
        return 0, 0
