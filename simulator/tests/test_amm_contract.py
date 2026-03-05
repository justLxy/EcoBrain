import unittest

from simulator.ecobrain_env.amm import AMM


class AmmContractTest(unittest.TestCase):
    def test_dynamic_spread_includes_twap_volatility(self):
        amm = AMM(
            base_price=100.0,
            target_inventory=100,
            current_inventory=50,
            k_factor=1.0,
            physical_stock=100,
        )
        current_price = amm.get_current_price()
        amm.set_twap_hint(100.0)

        spread = amm.calculate_dynamic_spread(10)
        expected = min(0.999, 0.05 + (abs(current_price - 100.0) / 100.0) * 0.5)

        self.assertAlmostEqual(expected, spread, places=9)


if __name__ == "__main__":
    unittest.main()
