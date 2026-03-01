package com.ecobrain.plugin.ai;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * AI 模型持久化：保证服务器重启后不“失忆”。
 */
public class AiModelStore {
    private static final String FILE_NAME = "ai-model.dat";
    private static final int VERSION = 1;

    public record Snapshot(int version, NeuralNet.State netState, double epsilon, long savedAtMillis) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public Optional<Snapshot> load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            return Optional.empty();
        }
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            Object obj = in.readObject();
            if (!(obj instanceof Snapshot snapshot)) {
                return Optional.empty();
            }
            if (snapshot.version() != VERSION) {
                return Optional.empty();
            }
            if (snapshot.netState() == null) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (Exception e) {
            plugin.getLogger().warning("[EcoBrain-AI] Failed to load AI model: " + e.getMessage());
            return Optional.empty();
        }
    }

    public void save(JavaPlugin plugin, Snapshot snapshot) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeObject(snapshot);
        } catch (Exception e) {
            plugin.getLogger().warning("[EcoBrain-AI] Failed to save AI model: " + e.getMessage());
        }
    }
}

