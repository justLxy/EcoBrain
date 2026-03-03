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
    private final long createdAtMillis;

    public ItemMarketRecord(String itemHash, String itemBase64, double basePrice, double kFactor,
                            int targetInventory, int currentInventory, int physicalStock) {
        this(itemHash, itemBase64, basePrice, kFactor, targetInventory, currentInventory, physicalStock, System.currentTimeMillis());
    }

    public ItemMarketRecord(String itemHash, String itemBase64, double basePrice, double kFactor,
                            int targetInventory, int currentInventory, int physicalStock, long createdAtMillis) {
        this.itemHash = itemHash;
        this.itemBase64 = itemBase64;
        this.basePrice = basePrice;
        this.kFactor = kFactor;
        this.targetInventory = targetInventory;
        this.currentInventory = currentInventory;
        this.physicalStock = physicalStock;
        this.createdAtMillis = createdAtMillis;
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

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public ItemMarketRecord withInventories(int newVirtualInventory, int newPhysicalStock) {
        return new ItemMarketRecord(itemHash, itemBase64, basePrice, kFactor, targetInventory,
            newVirtualInventory, newPhysicalStock, createdAtMillis);
    }

    public ItemMarketRecord withTuning(double newBasePrice, double newKFactor) {
        return new ItemMarketRecord(itemHash, itemBase64, newBasePrice, newKFactor, targetInventory,
            currentInventory, physicalStock, createdAtMillis);
    }
}
