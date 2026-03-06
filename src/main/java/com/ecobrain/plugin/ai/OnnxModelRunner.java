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
    private OrtSession session;
    private final Logger logger;

    public OnnxModelRunner(File modelDir, Logger logger) {
        this.logger = logger;
        this.env = OrtEnvironment.getEnvironment();
        try {
            File modelFile = new File(modelDir, "ecobrain_value.onnx");
            if (modelFile.exists()) {
                this.session = env.createSession(modelFile.getAbsolutePath(), new OrtSession.SessionOptions());
                logger.info("Loaded EcoBrain PPO model (single-brain).");
            } else {
                logger.warning("EcoBrain PPO model not found at " + modelFile.getAbsolutePath());
            }
        } catch (OrtException e) {
            logger.severe("Failed to initialize ONNX Runtime: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (OrtException e) {
            logger.severe("Failed to close ONNX Runtime: " + e.getMessage());
        }
    }

    /**
     * @param obs observation vector (must match exported ONNX input dim)
     * @param basePriceMaxPercent maximum absolute base price percentage delta per cycle
     * @param kDeltaMax maximum absolute k delta per cycle
     * @return double[] {basePriceMultiplier, kDelta}
     */
    public double[] predictAction(float[] obs, double basePriceMaxPercent, double kDeltaMax) {
        if (session == null) {
            // Fallback to doing nothing if model isn't loaded
            return new double[]{1.0, 0.0};
        }

        try {
            // PPO obs shape is typically [batch_size, obs_dim]
            long[] shape = new long[]{1, obs.length};
            OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(obs), shape);

            // Our exported model's input name is 'observation'
            OrtSession.Result result = session.run(Collections.singletonMap("observation", tensor));

            // Output should be a float array of shape [1, 2] -> action
            float[][] output = (float[][]) result.get(0).getValue();
            
            // Our ONNX exports the actor's mean actions (pre-squash). SB3 PPO uses hard clipping in Python training
            // for continuous actions, so we apply max/min clip here to recover [-1, 1] without distortion.
            double actionBaseMultRaw = Math.max(-1.0, Math.min(1.0, (double) output[0][0]));
            double actionKDeltaRaw = Math.max(-1.0, Math.min(1.0, (double) output[0][1]));

            // Action 0: mapped based on training config.
            double safeBasePriceCap = Math.max(0.0D, basePriceMaxPercent);
            double basePriceMultiplier = 1.0 + (actionBaseMultRaw * safeBasePriceCap);
            
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
