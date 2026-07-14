package com.ecobrain.plugin.model;

/**
 * 市场物品聚合模型，代表数据库中某个 item_hash 的身份与真实库存。
 *
 * <p>v5 起，定价完全由 {@link DiscoveryState} 的卡尔曼状态驱动，遗留 vAMM 字段
 * (base_price/k_factor/target_inventory/current_inventory) 已移除。</p>
 */
public class ItemMarketRecord {
    private final String itemHash;
    private final String itemBase64;
    private final int physicalStock;
    private final long createdAtMillis;

    public ItemMarketRecord(String itemHash, String itemBase64, int physicalStock) {
        this(itemHash, itemBase64, physicalStock, System.currentTimeMillis());
    }

    public ItemMarketRecord(String itemHash, String itemBase64, int physicalStock, long createdAtMillis) {
        this.itemHash = itemHash;
        this.itemBase64 = itemBase64;
        this.physicalStock = physicalStock;
        this.createdAtMillis = createdAtMillis;
    }

    public String getItemHash() {
        return itemHash;
    }

    public String getItemBase64() {
        return itemBase64;
    }

    public int getPhysicalStock() {
        return physicalStock;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public ItemMarketRecord withPhysicalStock(int newPhysicalStock) {
        return new ItemMarketRecord(itemHash, itemBase64, newPhysicalStock, createdAtMillis);
    }
}
