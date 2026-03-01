package com.ecobrain.plugin;

import com.ecobrain.plugin.service.AMMCalculator;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.TradeResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class EdgeCaseSimulationTest {

    private final AMMCalculator calculator = new AMMCalculator();

    @Test
    void test1_AstronomicalPurchaseWithDepletedInventory() {
        // 场景1：物品底价很贵，理想库存 200。但当前虚拟库存只剩 5 个。
        // 玩家使用指令强行购买 10000 个（指令允许的最大值）。
        // 预期：代码之前其实有 "amount >= initialInventory" 的抛错拦截。这其实是很好的自我保护！
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 50000.0D, 1.5D, 200, 5, 5);
        
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            calculator.calculateBuyTotal(record, 10000);
        }, "Should intercept astronomical purchases that exceed available virtual inventory");
        System.out.println("[Test 1] 10000个极端购买已被底层拦截，无法穿仓。");
    }

    @Test
    void test2_IllegalNegativeOrZeroPurchase() {
        // 场景2：如果指令层的校验被某种方式绕过，AMM 底层必须抛出异常，绝不能发生“买 -10 个反而赚了系统的钱”的情况。
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 100.0D, 1.0D, 100, 100, 100);
        
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            calculator.calculateBuyTotal(record, 0);
        }, "Should throw on 0 purchase");

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            calculator.calculateSellTotal(record, -50);
        }, "Should throw on negative sell");
    }

    @Test
    void test3_MathZeroDivisionProtection() {
        // 场景3：当前库存已经变成 2 了。再往下减就是 1 或 0。
        // 测试公式中的 (Target / Current)^k 会不会因为 Current=0 而抛出除以 0 的异常，或者算出 Infinity。
        // 由于有 amount >= initialInventory 保护，我们最多只能买 1 个。
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 10.0D, 1.0D, 100, 2, 2);
        
        TradeResult result = calculator.calculateBuyTotal(record, 1);
        Assertions.assertEquals(1, result.getPostInventory(), "Inventory rigidly protected at 1");
        
        // 期望价：因为库存跌到底了，买的边际价格是 10 * (100 / 1)^1 = 1000。
        Assertions.assertEquals(1000.0D, result.getTotalPrice(), 0.001);
    }
}
