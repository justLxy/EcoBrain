package com.ecobrain.plugin;

import com.ecobrain.plugin.service.AMMCalculator;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.TradeResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PricingSimulationTest {

    private final AMMCalculator calculator = new AMMCalculator();

    @Test
    void test1_SlippageEquality() {
        // 场景1：大单滑点 vs 分批购买滑点（理论上必须一致，或者单次购买更贵/便宜？）
        // 其实因为离散积分 (sum i=1..N)，一次性买 N 个，和分 N 次每次买 1 个，系统计算的库存推演是完全一样的！
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 100.0D, 1.0D, 100, 100, 100);
        
        // 一次性买 10 个
        TradeResult bulkBuy = calculator.calculateBuyTotal(record, 10);
        
        // 分 10 次买，每次买 1 个，手动推进库存
        double stepByStepCost = 0.0D;
        int currentInv = 100;
        for (int i = 0; i < 10; i++) {
            ItemMarketRecord tempRecord = new ItemMarketRecord("hash", "base64", 100.0D, 1.0D, 100, currentInv, currentInv);
            TradeResult stepBuy = calculator.calculateBuyTotal(tempRecord, 1);
            stepByStepCost += stepBuy.getTotalPrice();
            currentInv = stepBuy.getPostInventory();
        }
        
        Assertions.assertEquals(bulkBuy.getTotalPrice(), stepByStepCost, 0.001, "一次性买和分批买的总花费必须绝对一致");
        System.out.println("[Test 1] 滑点公平性验证通过。买10个花费: " + bulkBuy.getTotalPrice());
    }

    @Test
    void test2_The5PercentSpreadLoss() {
        // 场景2：无风险套利阻断测试。玩家花钱买入 10 个物品，然后立刻原封不动卖给系统，查看损耗。
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 100.0D, 1.0D, 100, 100, 100);
        
        // 买入 10 个
        TradeResult buyResult = calculator.calculateBuyTotal(record, 10);
        double cost = buyResult.getTotalPrice();
        
        // 此时系统虚拟库存变为了 90 (100 - 10)
        ItemMarketRecord afterBuyRecord = new ItemMarketRecord("hash", "base64", 100.0D, 1.0D, 100, 90, 90);
        
        // 玩家立刻卖出 10 个
        TradeResult sellResult = calculator.calculateSellTotal(afterBuyRecord, 10);
        double revenue = sellResult.getTotalPrice();
        
        // 验证系统回到了 100 库存
        Assertions.assertEquals(100, sellResult.getPostInventory());
        
        // 验证玩家亏钱了 (因为有 5% 的滑点手续费，收回来的钱必定小于花出去的钱)
        Assertions.assertTrue(revenue < cost, "卖出获得的钱必须小于买入花的钱（防套利）");
        
        // 具体算一下：如果没手续费，由于离散积分的步长偏移（买是从 99 到 90，卖是从 91 到 100），
        // 天然就存在一点微小的滑点差价。加上 5% 手续费后，revenue 会严格低于 cost。
        System.out.println("[Test 2] 买入花费: " + cost + ", 立即卖出获得: " + revenue + " (净亏损: " + (cost - revenue) + ")");
        Assertions.assertTrue(revenue < cost * 0.98, "加上 5% 手续费和滑点天然差价，卖出获得必须显著少于买入花费");
    }

    @Test
    void test3_KFactorElasticity() {
        // 场景3：验证 k 因子对价格飙升的影响 (模拟稀缺神器的价格壁垒)
        // 同样是基础价 1000，目标库存 100，当前只剩 10 个。
        // k = 0.5 (迟钝) vs k = 2.0 (极度敏感)
        ItemMarketRecord dullRecord = new ItemMarketRecord("hash", "base64", 1000.0D, 0.5D, 100, 10, 10);
        ItemMarketRecord sensitiveRecord = new ItemMarketRecord("hash", "base64", 1000.0D, 2.0D, 100, 10, 10);
        
        // 买 1 个
        double dullCost = calculator.calculateBuyTotal(dullRecord, 1).getTotalPrice();
        double sensitiveCost = calculator.calculateBuyTotal(sensitiveRecord, 1).getTotalPrice();
        
        System.out.println("[Test 3] 缺货时买1个 (k=0.5): " + dullCost);
        System.out.println("[Test 3] 缺货时买1个 (k=2.0): " + sensitiveCost);
        
        // k=2.0 的价格飙升应该极其恐怖（(100/9)^2 = 123倍基价）
        Assertions.assertTrue(sensitiveCost > dullCost * 10, "高K值的缺货惩罚必须极其严重");
    }

    @Test
    void test4_GlutDumpDepreciation() {
        // 场景4：大户恶意倾销测试。物品基价 100，理想库存 100。
        // 玩家一口气砸 500 个进来（引发严重爆仓）。
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 100.0D, 1.0D, 100, 100, 100);
        
        TradeResult bulkSellResult = calculator.calculateSellTotal(record, 500);
        double avgPrice = bulkSellResult.getTotalPrice() / 500.0D;
        
        System.out.println("[Test 4] 倾销 500 个总收益: " + bulkSellResult.getTotalPrice() + ", 平均单价: " + avgPrice);
        
        // 尽管基价是 100，但由于他一口气砸了 500 个，越砸越不值钱，最终平均单价一定远低于 100
        Assertions.assertTrue(avgPrice < 40.0D, "大规模倾销必须导致滑点暴跌，均价必须极低");
    }

}
