package com.ecobrain.plugin.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 经验回放池，用于 DQN 训练时打散时间相关性。
 */
public class ReplayBuffer {
    public record Transition(double[] state, int action, double reward, double[] nextState) {}

    private final List<Transition> buffer = new ArrayList<>();
    private final int capacity;
    private final Random random = new Random();

    public ReplayBuffer(int capacity) {
        this.capacity = capacity;
    }

    public void add(Transition transition) {
        if (buffer.size() >= capacity) {
            buffer.remove(0);
        }
        buffer.add(transition);
    }

    public int size() {
        return buffer.size();
    }

    public List<Transition> sample(int batchSize) {
        List<Transition> result = new ArrayList<>();
        if (buffer.isEmpty()) {
            return result;
        }
        int n = Math.min(batchSize, buffer.size());
        for (int i = 0; i < n; i++) {
            result.add(buffer.get(random.nextInt(buffer.size())));
        }
        return result;
    }
}
