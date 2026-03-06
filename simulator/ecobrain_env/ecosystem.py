from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from .players import NewPlayer, VeteranPlayer, Arbitrageur


def _clamp(x: float, lo: Optional[float], hi: Optional[float]) -> float:
    if lo is not None:
        x = max(lo, x)
    if hi is not None:
        x = min(hi, x)
    return x


def sample_from_spec(spec: Any, rng: np.random.Generator) -> Any:
    """
    Sampling helper for domain randomization.

    Supported forms:
    - scalar (int/float/str/bool): returned as-is
    - {"dist": "uniform", "low": x, "high": y, "min": a?, "max": b?}
    - {"dist": "loguniform", "low": x, "high": y, "integer": bool?}
    - {"dist": "int_uniform", "low": i, "high": j}  # inclusive
    - {"dist": "choice", "values": [...], "p": [...]?}
    - {"dist": "beta", "a": A, "b": B, "min": a?, "max": b?}
    - {"dist": "normal", "mean": m, "std": s, "min": a?, "max": b?}
    """
    if not isinstance(spec, dict) or "dist" not in spec:
        return spec

    dist = str(spec.get("dist", "")).lower()
    lo = spec.get("min", None)
    hi = spec.get("max", None)

    if dist == "uniform":
        x = float(rng.uniform(float(spec["low"]), float(spec["high"])))
        return _clamp(x, lo, hi)

    if dist == "loguniform":
        low = float(spec["low"])
        high = float(spec["high"])
        if low <= 0 or high <= 0:
            raise ValueError("loguniform requires low/high > 0")
        x = float(np.exp(rng.uniform(np.log(low), np.log(high))))
        x = _clamp(x, lo, hi)
        if bool(spec.get("integer", False)):
            return int(round(x))
        return x

    if dist == "int_uniform":
        low = int(spec["low"])
        high = int(spec["high"])
        if high < low:
            low, high = high, low
        return int(rng.integers(low, high + 1))

    if dist == "choice":
        values = list(spec["values"])
        p = spec.get("p", None)
        return rng.choice(values, p=p)

    if dist == "beta":
        a = float(spec["a"])
        b = float(spec["b"])
        x = float(rng.beta(a, b))
        return _clamp(x, lo, hi)

    if dist == "normal":
        mean = float(spec["mean"])
        std = float(spec["std"])
        x = float(rng.normal(mean, std))
        return _clamp(x, lo, hi)

    raise ValueError(f"Unknown dist spec: {dist!r}")


@dataclass(frozen=True)
class Regime:
    name: str
    buy_prob_mult: float = 1.0
    sell_prob_mult: float = 1.0
    buy_amount_mult: float = 1.0
    sell_amount_mult: float = 1.0


def _apply_regime_prob(p: float, mult: float) -> float:
    # Keep within [0, 0.99] to avoid degenerate always-trade loops.
    return float(_clamp(p * mult, 0.0, 0.99))


def _apply_regime_amount(n: int, mult: float) -> int:
    return max(1, int(round(float(n) * float(mult))))


def sample_regime(regimes: Dict[str, Dict[str, Any]], rng: np.random.Generator) -> Regime:
    names = []
    weights = []
    for name, cfg in regimes.items():
        names.append(name)
        weights.append(float(cfg.get("weight", 1.0)))
    total = sum(weights)
    if total <= 0:
        weights = [1.0 for _ in weights]
        total = float(len(weights))
    p = [w / total for w in weights]
    chosen = str(rng.choice(names, p=p))
    cfg = regimes.get(chosen, {})
    return Regime(
        name=chosen,
        buy_prob_mult=float(cfg.get("buy_prob_mult", 1.0)),
        sell_prob_mult=float(cfg.get("sell_prob_mult", 1.0)),
        buy_amount_mult=float(cfg.get("buy_amount_mult", 1.0)),
        sell_amount_mult=float(cfg.get("sell_amount_mult", 1.0)),
    )


def build_players_from_archetypes(
    value_type: str,
    archetypes: List[Dict[str, Any]],
    regime: Regime,
    rng: np.random.Generator,
) -> List[Any]:
    players: List[Any] = []
    for idx, arch in enumerate(archetypes):
        p_type = str(arch.get("type", ""))
        count_spec = arch.get("count", 1)
        count = int(sample_from_spec(count_spec, rng))
        count = max(0, count)

        for i in range(count):
            name = str(arch.get("name", f"{p_type}_{idx}_{i}"))
            balance = float(sample_from_spec(arch.get("balance", 1000.0), rng))
            item_inventory = int(sample_from_spec(arch.get("initial_item_inventory", 0), rng))
            produce_lambda = float(sample_from_spec(arch.get("produce_lambda", 0.0), rng))
            consume_lambda = float(sample_from_spec(arch.get("consume_lambda", 0.0), rng))
            price_response_strength = float(sample_from_spec(arch.get("price_response_strength", 1.0), rng))

            if p_type == "VeteranPlayer":
                buy_prob = float(sample_from_spec(arch.get("buy_prob", 0.02), rng))
                sell_prob = float(sample_from_spec(arch.get("sell_prob", 0.8), rng))
                buy_amount = int(sample_from_spec(arch.get("buy_amount", 10), rng))
                sell_amount = int(sample_from_spec(arch.get("sell_amount", 64), rng))
                buy_inventory_target = int(sample_from_spec(arch.get("buy_inventory_target", 0), rng))
                sell_inventory_threshold = int(sample_from_spec(arch.get("sell_inventory_threshold", 0), rng))

                buy_prob = _apply_regime_prob(buy_prob, regime.buy_prob_mult)
                sell_prob = _apply_regime_prob(sell_prob, regime.sell_prob_mult)
                buy_amount = _apply_regime_amount(buy_amount, regime.buy_amount_mult)
                sell_amount = _apply_regime_amount(sell_amount, regime.sell_amount_mult)

                players.append(
                    VeteranPlayer(
                        name,
                        balance=balance,
                        buy_probability=buy_prob,
                        sell_probability=sell_prob,
                        buy_amount=buy_amount,
                        sell_amount=sell_amount,
                        buy_inventory_target=buy_inventory_target,
                        sell_inventory_threshold=sell_inventory_threshold,
                        item_inventory=item_inventory,
                        produce_lambda=produce_lambda,
                        consume_lambda=consume_lambda,
                        price_response_strength=price_response_strength,
                        rng=rng,
                    )
                )
            elif p_type == "NewPlayer":
                buy_prob = float(sample_from_spec(arch.get("buy_prob", 0.05), rng))
                sell_prob = float(sample_from_spec(arch.get("sell_prob", 0.05), rng))
                amount = int(sample_from_spec(arch.get("amount", 5), rng))
                buy_inventory_target = int(sample_from_spec(arch.get("buy_inventory_target", 0), rng))
                sell_inventory_threshold = int(sample_from_spec(arch.get("sell_inventory_threshold", 0), rng))

                buy_prob = _apply_regime_prob(buy_prob, regime.buy_prob_mult)
                sell_prob = _apply_regime_prob(sell_prob, regime.sell_prob_mult)
                amount = _apply_regime_amount(amount, (regime.buy_amount_mult + regime.sell_amount_mult) / 2.0)

                players.append(
                    NewPlayer(
                        name,
                        balance=balance,
                        buy_probability=buy_prob,
                        sell_probability=sell_prob,
                        amount=amount,
                        buy_inventory_target=buy_inventory_target,
                        sell_inventory_threshold=sell_inventory_threshold,
                        item_inventory=item_inventory,
                        produce_lambda=produce_lambda,
                        consume_lambda=consume_lambda,
                        price_response_strength=price_response_strength,
                        rng=rng,
                    )
                )
            elif p_type == "Arbitrageur":
                players.append(Arbitrageur(name, balance=balance, rng=rng))
            else:
                raise ValueError(f"Unknown simulated player type: {p_type!r}")

    # Small shuffle so PPO doesn't see stable ordering artifacts
    rng.shuffle(players)
    return players

