class AMM:
    """
    与插件端 `AMMCalculator` 对齐的 vAMM：
    - 价格公式：P = basePrice * (targetInventory / max(1, currentInventory))^k
    - 买入/卖出采用离散积分求和（逐个滑点）
    - 卖出带动态印花税：base 5% + dumpingTax（基于 physical_stock）
    注意：插件端目前 TWAP 近似=当前价（volatilitySpread≈0），这里也保持一致。
    """
    def __init__(self, base_price, target_inventory, current_inventory, k_factor, physical_stock, is_ipo=False,
                 base_spread=0.05, max_spread=0.999,
                 dumping_trigger_multiplier=3.0, dumping_tax_per_multiple=0.10,
                 critical_inventory: int = 1):
        self.base_price = base_price
        self.target_inventory = target_inventory
        # Virtual inventory (AMM curve inventory)
        self.current_inventory = current_inventory
        # Physical stock (real server stock)
        self.physical_stock = physical_stock
        self.k_factor = k_factor
        self.is_ipo = is_ipo

        self.base_spread = float(base_spread)
        self.max_spread = float(max_spread)
        self.dumping_trigger_multiplier = float(dumping_trigger_multiplier)
        self.dumping_tax_per_multiple = float(dumping_tax_per_multiple)
        self.critical_inventory = int(critical_inventory)
        self.frozen = False

    def set_frozen(self, frozen: bool):
        self.frozen = bool(frozen)
        
    def get_current_price(self):
        current_inventory = max(1, int(self.current_inventory))
        return self.base_price * (self.target_inventory / current_inventory) ** self.k_factor

    def get_twap(self):
        # 插件端 getTwapPrice() 近似=当前价
        return self.get_current_price()

    def calculate_dynamic_spread(self, sell_amount: int) -> float:
        """
        对齐插件端 `AMMCalculator.calculateDynamicSpread(record, sellAmount)`：
        - volatilitySpread≈0（TWAP=当前价）
        - dumpingTax 基于当前 physical_stock
        """
        if sell_amount <= 0:
            return 0.0

        dumping_tax = 0.0
        current_physical = max(1, int(self.physical_stock))
        if sell_amount > current_physical * self.dumping_trigger_multiplier:
            ratio = sell_amount / current_physical
            dumping_tax = (ratio - self.dumping_trigger_multiplier) * self.dumping_tax_per_multiple

        dynamic_spread = self.base_spread + dumping_tax
        return min(self.max_spread, max(0.0, dynamic_spread))

    def simulate_buy(self, quantity):
        # User buys from system (virtual inventory decreases)
        if quantity <= 0:
            raise ValueError("quantity must be positive")

        initial_inventory = max(1, int(self.current_inventory))
        if quantity >= initial_inventory:
            # 对齐插件端：amount >= initialInventory 直接拦截，避免 currentInventory 掉到 0
            raise ValueError("quantity exceeds available virtual inventory")

        total_cost = 0.0
        # Discrete summation: i=1..N, stepInventory = max(1, initial - i)
        for i in range(1, quantity + 1):
            step_inventory = max(1, initial_inventory - i)
            total_cost += self.base_price * (self.target_inventory / step_inventory) ** self.k_factor
        return max(0.0, total_cost)

    def simulate_sell(self, quantity):
        # User sells to system (virtual inventory increases)
        if quantity <= 0:
            raise ValueError("quantity must be positive")

        total_revenue = 0.0
        initial_inventory = max(1, int(self.current_inventory))
        for i in range(1, quantity + 1):
            step_inventory = initial_inventory + i
            total_revenue += self.base_price * (self.target_inventory / max(1, step_inventory)) ** self.k_factor

        spread = self.calculate_dynamic_spread(sell_amount=int(quantity))
        return max(0.0, total_revenue * (1.0 - spread))
        
    def execute_buy(self, quantity):
        # Plugin-aligned hard constraints
        if self.frozen:
            raise ValueError("buy is frozen by circuit breaker")
        if int(self.current_inventory) <= int(self.critical_inventory):
            raise ValueError("virtual inventory too low to buy")
        if int(self.physical_stock) < int(quantity):
            raise ValueError("insufficient physical stock")
        if (int(self.physical_stock) - int(quantity)) < int(self.critical_inventory):
            raise ValueError("post-buy physical stock protected by critical inventory")

        cost = self.simulate_buy(quantity)
        if self.is_ipo:
            # First buy unlocks IPO
            self.is_ipo = False

        self.current_inventory = max(1, int(self.current_inventory) - int(quantity))
        # physical_stock 的扣减由上层（env）做约束，这里只做同步更新
        self.physical_stock = max(0, int(self.physical_stock) - int(quantity))
        return cost
        
    def execute_sell(self, quantity):
        revenue = self.simulate_sell(quantity)
        self.current_inventory = max(1, int(self.current_inventory) + int(quantity))
        self.physical_stock = max(0, int(self.physical_stock) + int(quantity))
        return revenue

    def apply_ai_action(self, base_price_multiplier, k_delta):
        # PPO outputs continuous actions
        self.base_price *= base_price_multiplier
        # bounds aligned to plugin
        self.base_price = max(0.01, min(self.base_price, 1000000.0))
        
        self.k_factor += k_delta
        # bounds aligned to plugin
        self.k_factor = max(0.2, min(self.k_factor, 10.0))
