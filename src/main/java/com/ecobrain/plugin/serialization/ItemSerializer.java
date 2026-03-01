package com.ecobrain.plugin.serialization;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 负责 ItemStack 的完整序列化/反序列化与哈希生成。
 * 通过 BukkitObjectOutputStream 可以保留 NBT、自定义元数据等复杂信息，
 * 从而确保 MythicMobs / RPGItems 等自定义物品不会与普通物品混淆。
 */
public class ItemSerializer {

    /**
     * 将任意 ItemStack 完整序列化为 Base64 字符串。
     *
     * @param itemStack 待序列化的物品对象
     * @return Base64 文本
     */
    public String serializeToBase64(ItemStack itemStack) {
        if (itemStack == null) {
            throw new IllegalArgumentException("itemStack cannot be null");
        }
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream objectOutputStream = new BukkitObjectOutputStream(byteArrayOutputStream)) {
            objectOutputStream.writeObject(itemStack);
            objectOutputStream.flush();
            return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize item stack", e);
        }
    }

    /**
     * 从 Base64 文本恢复 ItemStack。
     *
     * @param base64 通过 serializeToBase64 生成的文本
     * @return 还原后的 ItemStack
     */
    public ItemStack deserializeFromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) {
            throw new IllegalArgumentException("base64 cannot be null or empty");
        }
        byte[] data = Base64.getDecoder().decode(base64);
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
             BukkitObjectInputStream objectInputStream = new BukkitObjectInputStream(byteArrayInputStream)) {
            Object object = objectInputStream.readObject();
            if (!(object instanceof ItemStack itemStack)) {
                throw new IllegalStateException("Decoded object is not ItemStack");
            }
            return itemStack;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to deserialize item stack", e);
        }
    }

    /**
     * 对序列化后的 Base64 文本做 SHA-256 哈希，得到固定长度的 item_hash。
     * 固定 64 字符哈希可显著降低 SQLite 索引成本，避免超长文本索引导致性能问题。
     *
     * @param base64 ItemStack 的 Base64 序列化文本
     * @return 64 位十六进制哈希字符串
     */
    public String sha256(String base64) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(base64.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte hashByte : hashBytes) {
                builder.append(String.format("%02x", hashByte));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
