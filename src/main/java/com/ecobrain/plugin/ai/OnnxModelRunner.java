package com.ecobrain.plugin.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.logging.Logger;

public class OnnxModelRunner {

    private final OrtEnvironment env;
    private OrtSession sessionLowValue;
    private OrtSession sessionMidValue;
    private OrtSession sessionHighValue;
    private final Logger logger;

    public OnnxModelRunner(File modelDir, Logger logger) {
        this.logger = logger;
        this.env = OrtEnvironment.getEnvironment();
        try {
            File lowModelFile = new File(modelDir, "ecobrain_low_value.onnx");
            File midModelFile = new File(modelDir, "ecobrain_mid_value.onnx");
            File highModelFile = new File(modelDir, "ecobrain_high_value.onnx");

            if (lowModelFile.exists()) {
                this.sessionLowValue = env.createSession(lowModelFile.getAbsolutePath(), new OrtSession.SessionOptions());
                logger.info("Loaded Low-Value PPO model.");
            } else {
                logger.warning("Low-Value PPO model not found at " + lowModelFile.getAbsolutePath());
            }

            if (midModelFile.exists()) {
                this.sessionMidValue = env.createSession(midModelFile.getAbsolutePath(), new OrtSession.SessionOptions());
                logger.info("Loaded Mid-Value PPO model.");
            } else {
                logger.warning("Mid-Value PPO model not found at " + midModelFile.getAbsolutePath());
            }

            if (highModelFile.exists()) {
                this.sessionHighValue = env.createSession(highModelFile.getAbsolutePath(), new OrtSession.SessionOptions());
                logger.info("Loaded High-Value PPO model.");
            } else {
                logger.warning("High-Value PPO model not found at " + highModelFile.getAbsolutePath());
            }
        } catch (OrtException e) {
            logger.severe("Failed to initialize ONNX Runtime: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (sessionLowValue != null) sessionLowValue.close();
            if (sessionMidValue != null) sessionMidValue.close();
            if (sessionHighValue != null) sessionHighValue.close();
            if (env != null) env.close();
        } catch (OrtException e) {
            logger.severe("Failed to close ONNX Runtime: " + e.getMessage());
        }
    }

    /**
     * @param obs [saturation, flow, inflation, elasticity, volatility, is_ipo]
     * @param valueType "low", "mid", or "high"
     * @param kDeltaMax maximum absolute k delta per cycle (tier-specific)
     * @return double[] {basePriceMultiplier, kDelta}
     */
    public double[] predictAction(float[] obs, String valueType, double kDeltaMax) {
        OrtSession session;
        switch (valueType) {
            case "high":
                session = sessionHighValue;
                break;
            case "mid":
                session = sessionMidValue;
                break;
            default:
                session = sessionLowValue;
                break;
        }

        if (session == null) {
            // Fallback to doing nothing if model isn't loaded
            return new double[]{0.0, 0.0};
        }

        try {
            // PPO obs shape is typically [batch_size, obs_dim] -> [1, 6]
            long[] shape = new long[]{1, obs.length};
            OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(obs), shape);

            // Our exported model's input name is 'observation'
            OrtSession.Result result = session.run(Collections.singletonMap("observation", tensor));

            // Output should be a float array of shape [1, 2] -> action
            float[][] output = (float[][]) result.get(0).getValue();
            
            // Our ONNX exports the actor's mean actions (pre-squash). SB3 PPO uses tanh-squash
            // for continuous actions, so we apply tanh here to recover [-1, 1].
            double actionBaseMultRaw = Math.tanh((double) output[0][0]);
            double actionKDeltaRaw = Math.tanh((double) output[0][1]);

            // Action 0: mapped based on training config.py (1.00 = 100%)
            double basePriceMultiplier = 1.0 + (actionBaseMultRaw * 1.00);
            
            // Action 1: mapped based on tier-specific training cap
            double safeCap = Math.max(0.0D, kDeltaMax);
            double kDelta = actionKDeltaRaw * safeCap;

            tensor.close();
            result.close();

            return new double[]{basePriceMultiplier, kDelta};

        } catch (OrtException e) {
            logger.severe("ONNX inference failed: " + e.getMessage());
            return new double[]{1.0, 0.0}; // Neutral action on failure
        }
    }
}
