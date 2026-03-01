package com.ecobrain.plugin.ai;

import com.ecobrain.plugin.ai.ReplayBuffer.Transition;

import java.util.List;
import java.util.Random;

/**
 * 简化版 DQN 训练器：
 * - 动作空间 2 维（下调/上调）
 * - epsilon-greedy 探索
 * - 使用经验回放稳定训练
 */
public class DqnTrainer {
    private final NeuralNet onlineNet;
    private final ReplayBuffer replayBuffer;
    private final Random random = new Random();

    private double epsilon = 0.15D;
    private final double gamma = 0.95D;
    private final double learningRate = 0.0008D;

    public DqnTrainer(NeuralNet onlineNet, ReplayBuffer replayBuffer) {
        this.onlineNet = onlineNet;
        this.replayBuffer = replayBuffer;
    }

    public int chooseAction(double[] state) {
        if (random.nextDouble() < epsilon) {
            return random.nextInt(2);
        }
        double[] q = onlineNet.predict(state);
        return q[0] >= q[1] ? 0 : 1;
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
            double maxNextQ = Math.max(nextQ[0], nextQ[1]);

            double[] target = new double[] {currentQ[0], currentQ[1]};
            target[transition.action()] = transition.reward() + gamma * maxNextQ;
            onlineNet.trainSingle(transition.state(), target, learningRate);
        }
    }
}
