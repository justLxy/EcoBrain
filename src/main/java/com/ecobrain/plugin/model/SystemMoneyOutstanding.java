package com.ecobrain.plugin.model;

import java.util.UUID;

/**
 * 玩家“系统资金净额”（系统给出 - 系统收回 - 已执行的回收）。
 */
public record SystemMoneyOutstanding(UUID playerUuid, String playerName, double outstandingMoney) {}

