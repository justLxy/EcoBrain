import csv
import os
import tempfile
import unittest

import numpy as np

try:
    from simulator.ecobrain_env.env import EcoBrainEnv

    HAS_ENV = True
except Exception:
    EcoBrainEnv = None
    HAS_ENV = False


@unittest.skipUnless(HAS_ENV, "gymnasium/numpy training environment is not available")
class EnvContractTest(unittest.TestCase):
    def test_no_activity_gate_holds_policy_action(self):
        env = EcoBrainEnv(value_type="low")
        env.players = []
        env._item_activity_window.clear()

        base_before = float(env.amm.base_price)
        k_before = float(env.amm.k_factor)

        env.step(np.array([1.0, 1.0], dtype=np.float32))

        self.assertAlmostEqual(base_before, float(env.amm.base_price), places=9)
        self.assertAlmostEqual(k_before, float(env.amm.k_factor), places=9)

    def test_dataset_replay_selects_hash_from_matching_value_tier(self):
        cycle_ms = 15 * 60 * 1000
        temp = tempfile.NamedTemporaryFile("w", newline="", suffix=".csv", delete=False)
        try:
            writer = csv.writer(temp)
            writer.writerow(["item_hash", "trade_type", "quantity", "total_price", "created_at"])
            writer.writerow(["low-hash", "BUY", 1, 10.0, 0])
            writer.writerow(["low-hash", "SELL", 1, 12.0, cycle_ms])
            writer.writerow(["high-hash", "BUY", 1, 20000.0, 0])
            writer.writerow(["high-hash", "SELL", 1, 18000.0, cycle_ms])
            temp.close()

            env = EcoBrainEnv(value_type="low", dataset_path=temp.name)
            self.assertEqual("low-hash", env._dataset_selected_hash)
            self.assertIn(0, env._dataset_item_cycles)
            self.assertIn(1, env._dataset_item_cycles)
        finally:
            try:
                os.unlink(temp.name)
            except FileNotFoundError:
                pass


if __name__ == "__main__":
    unittest.main()
