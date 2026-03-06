package com.ecobrain.plugin.ai;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.logging.Logger;

class OnnxModelRunnerTest {

    @Test
    void shouldReturnNeutralActionWhenModelIsMissing() throws Exception {
        var tempDir = Files.createTempDirectory("ecobrain-onnx-test-");
        OnnxModelRunner runner = new OnnxModelRunner(tempDir.toFile(), Logger.getLogger("ecobrain-test"));
        try {
            double[] action = runner.predictAction(new float[]{1.0f, 2.0f, 3.0f}, 0.12D, 1.0D);
            Assertions.assertArrayEquals(new double[]{1.0D, 0.0D}, action, 1.0e-9);
        } finally {
            runner.close();
        }
    }
}
