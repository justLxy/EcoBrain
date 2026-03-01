package com.ecobrain.plugin.service;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vault 经济桥接服务，负责发钱/扣钱并统一错误处理。
 */
public class EconomyService {
    private final JavaPlugin plugin;
    private Economy economy;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return false;
        }
        this.economy = registration.getProvider();
        return this.economy != null;
    }

    public boolean isReady() {
        return economy != null;
    }

    public double getBalance(Player player) {
        return economy.getBalance(player);
    }

    public boolean withdraw(Player player, double amount) {
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Withdraw failed: " + response.errorMessage);
        }
        return response.transactionSuccess();
    }

    public boolean deposit(Player player, double amount) {
        EconomyResponse response = economy.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Deposit failed: " + response.errorMessage);
        }
        return response.transactionSuccess();
    }
}
