package com.ecobrain.plugin.ai;

import java.io.Serial;
import java.io.Serializable;
import java.util.Random;

/**
 * 纯 Java 手写 MLP：
 * Input(3) -> Hidden1(16, ReLU) -> Hidden2(8, ReLU) -> Output(3)
 */
public class NeuralNet implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final double[][] w1;
    private final double[] b1;
    private final double[][] w2;
    private final double[] b2;
    private final double[][] w3;
    private final double[] b3;

    public record State(
        double[][] w1, double[] b1,
        double[][] w2, double[] b2,
        double[][] w3, double[] b3
    ) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public NeuralNet(int inputSize, int hidden1, int hidden2, int outputSize, long seed) {
        Random random = new Random(seed);
        this.w1 = randomMatrix(inputSize, hidden1, random);
        this.b1 = new double[hidden1];
        this.w2 = randomMatrix(hidden1, hidden2, random);
        this.b2 = new double[hidden2];
        this.w3 = randomMatrix(hidden2, outputSize, random);
        this.b3 = new double[outputSize];
    }

    private NeuralNet(double[][] w1, double[] b1,
                      double[][] w2, double[] b2,
                      double[][] w3, double[] b3) {
        this.w1 = deepCopy2d(w1);
        this.b1 = deepCopy1d(b1);
        this.w2 = deepCopy2d(w2);
        this.b2 = deepCopy1d(b2);
        this.w3 = deepCopy2d(w3);
        this.b3 = deepCopy1d(b3);
    }

    public State exportState() {
        return new State(
            deepCopy2d(w1), deepCopy1d(b1),
            deepCopy2d(w2), deepCopy1d(b2),
            deepCopy2d(w3), deepCopy1d(b3)
        );
    }

    public static NeuralNet fromState(State state) {
        if (state == null) {
            throw new IllegalArgumentException("state is null");
        }
        return new NeuralNet(state.w1(), state.b1(), state.w2(), state.b2(), state.w3(), state.b3());
    }

    public double[] predict(double[] input) {
        ForwardCache cache = forward(input);
        return cache.output;
    }

    /**
     * 单样本梯度下降更新（MSE 损失）。
     * 这里直接对输出层目标 Q 值进行回归，避免引入复杂自动求导框架。
     */
    public void trainSingle(double[] input, double[] targetQ, double learningRate) {
        ForwardCache cache = forward(input);
        double[] output = cache.output;

        double[] dOut = new double[output.length];
        for (int i = 0; i < output.length; i++) {
            dOut[i] = 2.0D * (output[i] - targetQ[i]);
        }

        double[] dHidden2 = new double[cache.hidden2.length];
        for (int i = 0; i < cache.hidden2.length; i++) {
            double sum = 0.0D;
            for (int j = 0; j < dOut.length; j++) {
                sum += w3[i][j] * dOut[j];
                w3[i][j] -= learningRate * cache.hidden2[i] * dOut[j];
            }
            dHidden2[i] = sum * reluDerivative(cache.hidden2Pre[i]);
        }
        for (int j = 0; j < b3.length; j++) {
            b3[j] -= learningRate * dOut[j];
        }

        double[] dHidden1 = new double[cache.hidden1.length];
        for (int i = 0; i < cache.hidden1.length; i++) {
            double sum = 0.0D;
            for (int j = 0; j < dHidden2.length; j++) {
                sum += w2[i][j] * dHidden2[j];
                w2[i][j] -= learningRate * cache.hidden1[i] * dHidden2[j];
            }
            dHidden1[i] = sum * reluDerivative(cache.hidden1Pre[i]);
        }
        for (int j = 0; j < b2.length; j++) {
            b2[j] -= learningRate * dHidden2[j];
        }

        for (int i = 0; i < input.length; i++) {
            for (int j = 0; j < dHidden1.length; j++) {
                w1[i][j] -= learningRate * input[i] * dHidden1[j];
            }
        }
        for (int j = 0; j < b1.length; j++) {
            b1[j] -= learningRate * dHidden1[j];
        }
    }

    private ForwardCache forward(double[] input) {
        double[] hidden1Pre = affine(input, w1, b1);
        double[] hidden1 = relu(hidden1Pre);
        double[] hidden2Pre = affine(hidden1, w2, b2);
        double[] hidden2 = relu(hidden2Pre);
        double[] output = affine(hidden2, w3, b3);
        return new ForwardCache(hidden1Pre, hidden1, hidden2Pre, hidden2, output);
    }

    private double[] affine(double[] input, double[][] weight, double[] bias) {
        int outputSize = bias.length;
        double[] out = new double[outputSize];
        for (int j = 0; j < outputSize; j++) {
            double sum = bias[j];
            for (int i = 0; i < input.length; i++) {
                sum += input[i] * weight[i][j];
            }
            out[j] = sum;
        }
        return out;
    }

    private double[] relu(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = Math.max(0.0D, x[i]);
        }
        return out;
    }

    private double reluDerivative(double x) {
        return x > 0 ? 1.0D : 0.0D;
    }

    private double[][] randomMatrix(int rows, int cols, Random random) {
        double[][] matrix = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                matrix[i][j] = (random.nextDouble() - 0.5D) * 0.1D;
            }
        }
        return matrix;
    }

    private static double[] deepCopy1d(double[] src) {
        if (src == null) {
            return new double[0];
        }
        return src.clone();
    }

    private static double[][] deepCopy2d(double[][] src) {
        if (src == null) {
            return new double[0][0];
        }
        double[][] out = new double[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] == null ? new double[0] : src[i].clone();
        }
        return out;
    }

    private record ForwardCache(double[] hidden1Pre, double[] hidden1, double[] hidden2Pre, double[] hidden2,
                                double[] output) {}
}
