package com.ecobrain.plugin.model;

/**
 * 市场物品聚合模型，代表数据库中某个 item_hash 的完整经济状态。
 */
public class ItemMarketRecord {
    private final String itemHash;
    private final String itemBase64;
    private final double basePrice;
    private final double kFactor;
    private final int targetInventory;
    private final int currentInventory;
    private final int physicalStock;

    public ItemMarketRecord(String itemHash, String itemBase64, double basePrice, double kFactor,
                            int targetInventory, int currentInventory, int physicalStock) {
        this.itemHash = itemHash;
        this.itemBase64 = itemBase64;
        this.basePrice = basePrice;
        this.kFactor = kFactor;
        this.targetInventory = targetInventory;
        this.currentInventory = currentInventory;
        this.physicalStock = physicalStock;
    }

    public String getItemHash() {
        return itemHash;
    }

    public String getItemBase64() {
        return itemBase64;
    }

    public double getBasePrice() {
        return basePrice;
    }

    public double getKFactor() {
        return kFactor;
    }

    public int getTargetInventory() {
        return targetInventory;
    }

    public int getCurrentInventory() {
        return currentInventory;
    }

    public int getPhysicalStock() {
        return physicalStock;
    }

    public ItemMarketRecord withInventories(int newVirtualInventory, int newPhysicalStock) {
        return new ItemMarketRecord(itemHash, itemBase64, basePrice, kFactor, targetInventory,
            newVirtualInventory, newPhysicalStock);
    }

    public ItemMarketRecord withTuning(double newBasePrice, double newKFactor) {
        return new ItemMarketRecord(itemHash, itemBase64, newBasePrice, newKFactor, targetInventory,
            currentInventory, physicalStock);
    }
}
