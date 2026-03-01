package com.ecobrain.plugin.ai;

import com.ecobrain.plugin.ai.ReplayBuffer.Transition;

import java.util.List;
import java.util.Random;

/**
 * 简化版 DQN 训练器：
 * - 动作空间可配置（例如 下调/上调/保持）
 * - epsilon-greedy 探索
 * - 使用经验回放稳定训练
 */
public class DqnTrainer {
    private final NeuralNet onlineNet;
    private final ReplayBuffer replayBuffer;
    private final int actionSize;
    private final Random random = new Random();

    // ================== Q-Learning 核心超参数 ==================
    // 初始探索率：允许 15% 的概率随机瞎蒙
    // 正式服上线前几天，如果是全新档可以保持 0.15 甚至 0.2
    private double epsilon = 0.15D;
    
    // 折扣因子：看重长期回报
    private final double gamma = 0.95D;
    
    // 学习率。想让 AI 在开服初期学得“极度暴力”，可以把它提高到 0.005D
    // 目前保持一个比较折中的稍快学习率 (其实这不需要改，因为DQN是自适应收敛的)
    private final double learningRate = 0.002D;

    public DqnTrainer(NeuralNet onlineNet, ReplayBuffer replayBuffer, int actionSize) {
        this.onlineNet = onlineNet;
        this.replayBuffer = replayBuffer;
        this.actionSize = Math.max(2, actionSize);
    }

    public int chooseAction(double[] state) {
        if (random.nextDouble() < epsilon) {
            return random.nextInt(actionSize);
        }
        double[] q = onlineNet.predict(state);
        int best = 0;
        double bestQ = q[0];
        for (int i = 1; i < Math.min(q.length, actionSize); i++) {
            if (q[i] > bestQ) {
                bestQ = q[i];
                best = i;
            }
        }
        return best;
    }

    public void observe(double[] state, int action, double reward, double[] nextState) {
        replayBuffer.add(new Transition(state, action, reward, nextState));
        epsilon = Math.max(0.02D, epsilon * 0.9995D);
    }

    /**
     * 在异步线程中执行批量训练。
     */
    public void trainBatch(int batchSize) {
        if (replayBuffer.size() < 10) {
            return;
        }
        List<Transition> batch = replayBuffer.sample(batchSize);
        for (Transition transition : batch) {
            double[] currentQ = onlineNet.predict(transition.state());
            double[] nextQ = onlineNet.predict(transition.nextState());
            double maxNextQ = nextQ[0];
            for (int i = 1; i < Math.min(nextQ.length, actionSize); i++) {
                maxNextQ = Math.max(maxNextQ, nextQ[i]);
            }

            double[] target = currentQ.clone();
            target[transition.action()] = transition.reward() + gamma * maxNextQ;
            onlineNet.trainSingle(transition.state(), target, learningRate);
        }
    }
}
