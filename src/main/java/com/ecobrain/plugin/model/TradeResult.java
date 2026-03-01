package com.ecobrain.plugin.model;

/**
 * 交易计算结果：包含本次应收/应付金额与结算后的库存值。
 */
public class TradeResult {
    private final double totalPrice;
    private final int postInventory;

    public TradeResult(double totalPrice, int postInventory) {
        this.totalPrice = totalPrice;
        this.postInventory = postInventory;
    }

    public double getTotalPrice() {
        return totalPrice;
    }

    public int getPostInventory() {
        return postInventory;
    }
}
