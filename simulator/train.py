import os
import argparse
import re
import torch
import torch.nn as nn
from stable_baselines3 import PPO
from stable_baselines3.common.env_checker import check_env
from stable_baselines3.common.env_util import make_vec_env
from stable_baselines3.common.vec_env import DummyVecEnv, SubprocVecEnv
from stable_baselines3.common.vec_env import VecNormalize
from stable_baselines3.common.callbacks import BaseCallback, CallbackList, EvalCallback, StopTrainingOnNoModelImprovement
from ecobrain_env import EcoBrainEnv

def _select_device(device: str) -> str:
    device = (device or "auto").lower()
    if device == "auto":
        if torch.backends.mps.is_available():
            return "mps"
        if torch.cuda.is_available():
            return "cuda"
        return "cpu"
    if device == "mps":
        if not torch.backends.mps.is_available():
            raise RuntimeError("device=mps requested but torch.backends.mps.is_available() is False")
        return "mps"
    if device == "cuda":
        if not torch.cuda.is_available():
            raise RuntimeError("device=cuda requested but torch.cuda.is_available() is False")
        return "cuda"
    if device == "cpu":
        return "cpu"
    raise ValueError(f"Unknown device: {device!r} (expected: auto/cpu/cuda/mps)")


def _get_cpu_count() -> int:
    try:
        return int(os.cpu_count() or 1)
    except Exception:
        return 1


def _auto_n_envs(device: str, n_envs_cap: int) -> int:
    cpu_count = _get_cpu_count()
    # Leave 1 core for the main process / OS, if possible.
    desired = cpu_count - 1 if cpu_count > 1 else 1
    # Cap to avoid accidental process explosions on very large machines.
    cap = max(1, int(n_envs_cap))
    # On GPU, env stepping is still CPU-bound; we keep the same heuristic.
    return max(1, min(desired, cap))


def _parse_net_arch(net_arch: str | None):
    if not net_arch:
        return None
    parts = [p.strip() for p in net_arch.split(",") if p.strip()]
    if not parts:
        return None
    return [int(p) for p in parts]

def _safe_makedirs(path: str):
    if path and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)

def _find_latest_checkpoint(checkpoint_dir: str, prefix: str) -> str | None:
    """
    Find latest checkpoint path like: {prefix}_checkpoint_123456.zip
    """
    if not checkpoint_dir or not os.path.isdir(checkpoint_dir):
        return None
    pat = re.compile(rf"^{re.escape(prefix)}_checkpoint_(\d+)\.zip$")
    best_step = -1
    best_path = None
    for name in os.listdir(checkpoint_dir):
        m = pat.match(name)
        if not m:
            continue
        step = int(m.group(1))
        if step > best_step:
            best_step = step
            best_path = os.path.join(checkpoint_dir, name)
    return best_path

class SaveModelAndVecNormalizeCallback(BaseCallback):
    """
    Periodically save:
    - model checkpoint (.zip)
    - VecNormalize statistics (.pkl)
    Also keeps a 'latest' snapshot for quick resume.
    """
    def __init__(self, save_freq: int, checkpoint_dir: str, prefix: str, verbose: int = 0):
        super().__init__(verbose=verbose)
        self.save_freq = int(max(1, save_freq))
        self.checkpoint_dir = checkpoint_dir
        self.prefix = prefix
        _safe_makedirs(self.checkpoint_dir)

    def _save(self, step: int):
        model_path = os.path.join(self.checkpoint_dir, f"{self.prefix}_checkpoint_{step}.zip")
        vec_path = os.path.join(self.checkpoint_dir, f"{self.prefix}_checkpoint_{step}_vecnormalize.pkl")
        latest_model = os.path.join(self.checkpoint_dir, f"{self.prefix}_latest.zip")
        latest_vec = os.path.join(self.checkpoint_dir, f"{self.prefix}_latest_vecnormalize.pkl")

        self.model.save(model_path)
        self.model.save(latest_model)

        try:
            env = self.model.get_env()
            if isinstance(env, VecNormalize):
                env.save(vec_path)
                env.save(latest_vec)
        except Exception:
            pass

        if self.verbose > 0:
            print(f"[checkpoint] saved @ {step}: {model_path}")

    def _on_step(self) -> bool:
        # num_timesteps is global env steps seen by the model
        if self.num_timesteps % self.save_freq == 0:
            self._save(self.num_timesteps)
        return True


def train_model(
    value_type="low",
    total_timesteps=100000,
    dataset_path=None,
    device: str = "auto",
    n_envs: int = 0,
    vec_env: str = "auto",
    learning_rate: float = 3e-4,
    n_steps: int = 2048,
    batch_size: int = 0,
    n_epochs: int = 10,
    gamma: float = 0.99,
    gae_lambda: float = 0.95,
    clip_range: float = 0.2,
    seed: int | None = None,
    net_arch: str | None = None,
    n_envs_cap: int = 64,
    vecnorm: bool = True,
    norm_obs: bool = True,
    norm_reward: bool = True,
    checkpoint_freq: int = 100_000,
    checkpoint_dir: str = "checkpoints",
    resume: bool = True,
    eval_freq: int = 0,
    eval_episodes: int = 5,
    early_stop_patience: int = 10,
):
    using_real = bool(dataset_path and os.path.exists(dataset_path))
    if using_real:
        print(f"Training PPO for {value_type}-value items using real server data from: {dataset_path}")
    else:
        print(f"Training PPO for {value_type}-value items using simulated data...")

    check_env(EcoBrainEnv(value_type=value_type, dataset_path=dataset_path if using_real else None))

    device = _select_device(device)

    # n_envs=0 means "auto"
    if n_envs is None or int(n_envs) <= 0:
        n_envs = _auto_n_envs(device=device, n_envs_cap=n_envs_cap)
        print(f"Auto n_envs selected: {n_envs} (cpu_count={_get_cpu_count()}, cap={n_envs_cap})")
    else:
        n_envs = max(1, int(n_envs))

    vec_env = (vec_env or "auto").lower()
    if vec_env == "auto":
        vec_env_cls = SubprocVecEnv if n_envs > 1 else DummyVecEnv
    elif vec_env in ("subproc", "subprocess"):
        vec_env_cls = SubprocVecEnv
    elif vec_env in ("dummy", "sync"):
        vec_env_cls = DummyVecEnv
    else:
        raise ValueError(f"Unknown vec_env: {vec_env!r} (expected: auto/dummy/subproc)")

    env = make_vec_env(
        EcoBrainEnv,
        n_envs=n_envs,
        seed=seed,
        vec_env_cls=vec_env_cls,
        env_kwargs={
            "value_type": value_type,
            "dataset_path": dataset_path if using_real else None,
        },
    )

    model_name = f"ecobrain_ppo_{value_type}"
    model_path = f"{model_name}.zip"
    vecnorm_path = f"{model_name}_vecnormalize.pkl"

    # Optional: normalize observations & rewards for stability.
    # IMPORTANT: If norm_obs=True, we bake the normalization into exported ONNX
    # so the Java plugin can keep feeding raw obs without mismatch.
    if vecnorm:
        if os.path.exists(vecnorm_path):
            try:
                env = VecNormalize.load(vecnorm_path, env)
                print(f"Loaded VecNormalize stats from {vecnorm_path}")
            except Exception as e:
                print(f"Warning: failed to load VecNormalize stats ({e}); starting fresh normalization.")
                env = VecNormalize(env, norm_obs=norm_obs, norm_reward=norm_reward, clip_obs=10.0, clip_reward=10.0, gamma=gamma)
        else:
            env = VecNormalize(env, norm_obs=norm_obs, norm_reward=norm_reward, clip_obs=10.0, clip_reward=10.0, gamma=gamma)
        env.training = True
        env.norm_obs = bool(norm_obs)
        env.norm_reward = bool(norm_reward)

    print(f"Using device: {device} (n_envs={n_envs}, vec_env={vec_env_cls.__name__})")

    policy_kwargs = {}
    parsed_arch = _parse_net_arch(net_arch)
    if parsed_arch:
        policy_kwargs["net_arch"] = parsed_arch

    # batch_size=0 means "auto" (choose something that scales with n_envs but stays reasonable)
    if batch_size is None or int(batch_size) <= 0:
        # Prefer larger minibatches on GPU to improve utilization.
        if device in ("cuda", "mps"):
            batch_size = max(256, min(2048, n_envs * 64))
        else:
            batch_size = max(64, min(1024, n_envs * 64))
        # Keep it divisible by 64 for typical MLP performance.
        batch_size = int(batch_size // 64) * 64
        batch_size = max(64, int(batch_size))
        print(f"Auto batch_size selected: {batch_size}")
    else:
        batch_size = int(batch_size)
    
    # Build callbacks (checkpoint + optional eval/early-stop)
    callbacks = []
    if checkpoint_freq and int(checkpoint_freq) > 0:
        callbacks.append(SaveModelAndVecNormalizeCallback(
            save_freq=int(checkpoint_freq),
            checkpoint_dir=checkpoint_dir,
            prefix=model_name,
            verbose=1,
        ))

    if eval_freq and int(eval_freq) > 0:
        # Eval env: same underlying env but without updating VecNormalize stats
        eval_env = make_vec_env(
            EcoBrainEnv,
            n_envs=1,
            seed=seed,
            vec_env_cls=DummyVecEnv,
            env_kwargs={
                "value_type": value_type,
                "dataset_path": dataset_path if using_real else None,
            },
        )
        if vecnorm:
            if os.path.exists(vecnorm_path):
                eval_env = VecNormalize.load(vecnorm_path, eval_env)
            else:
                eval_env = VecNormalize(eval_env, norm_obs=norm_obs, norm_reward=norm_reward, clip_obs=10.0, clip_reward=10.0, gamma=gamma)
            eval_env.training = False
            eval_env.norm_reward = False  # report unnormalized reward for readability

        stop_cb = StopTrainingOnNoModelImprovement(
            max_no_improvement_evals=max(1, int(early_stop_patience)),
            min_evals=3,
            verbose=1,
        )
        callbacks.append(EvalCallback(
            eval_env,
            callback_after_eval=stop_cb,
            eval_freq=int(eval_freq),
            n_eval_episodes=max(1, int(eval_episodes)),
            best_model_save_path=os.path.join(checkpoint_dir, f"{model_name}_best"),
            log_path=os.path.join(checkpoint_dir, f"{model_name}_eval"),
            deterministic=True,
            render=False,
            verbose=1,
        ))

    callback = CallbackList(callbacks) if callbacks else None

    # Resume logic: prefer latest checkpoint if requested
    load_path = None
    if resume:
        if os.path.exists(model_path):
            load_path = model_path
        else:
            latest = _find_latest_checkpoint(checkpoint_dir, model_name)
            if latest:
                load_path = latest

    if load_path:
        print(f"Loading model from {load_path} and resuming training...")
        model = PPO.load(load_path, env=env, device=device)
    else:
        print(f"No existing model found for {value_type}, starting from scratch...")
        model = PPO(
            "MlpPolicy",
            env,
            verbose=1,
            learning_rate=learning_rate,
            n_steps=n_steps,
            batch_size=batch_size,
            n_epochs=n_epochs,
            gamma=gamma,
            gae_lambda=gae_lambda,
            clip_range=clip_range,
            device=device,
            policy_kwargs=policy_kwargs or None,
            seed=seed,
        )
                
    model.learn(total_timesteps=total_timesteps, callback=callback, reset_num_timesteps=False)
    
    # Save the model
    model.save(model_name)
    if vecnorm and isinstance(env, VecNormalize):
        env.save(vecnorm_path)
        print(f"Saved VecNormalize stats to {vecnorm_path}")
    print(f"Saved model to {model_name}.zip")
    return model

class OnnxablePolicy(nn.Module):
    def __init__(self, policy):
        super().__init__()
        self.policy = policy

    def forward(self, observation):
        # Return only the action for inference
        action, _ = self.policy.predict(observation, deterministic=True)
        return action

def export_to_onnx(model, filename):
    # torch>=2.8 uses a newer ONNX exporter that depends on onnxscript
    try:
        import onnxscript  # noqa: F401
    except Exception as e:
        raise RuntimeError(
            "ONNX export requires the 'onnxscript' package. "
            "Install it with: pip install onnxscript "
            f"(original error: {e})"
        )

    # Extract the PyTorch module from SB3 PPO
    pytorch_policy = model.policy

    # Try to obtain VecNormalize statistics from the model's env (if present)
    vecenv = None
    try:
        vecenv = model.get_env()
    except Exception:
        vecenv = None

    obs_mean = None
    obs_var = None
    obs_eps = 1e-8
    clip_obs = 10.0
    use_obs_norm = False
    if isinstance(vecenv, VecNormalize) and getattr(vecenv, "norm_obs", False) and getattr(vecenv, "obs_rms", None) is not None:
        use_obs_norm = True
        obs_mean = vecenv.obs_rms.mean.copy()
        obs_var = vecenv.obs_rms.var.copy()
        obs_eps = float(getattr(vecenv, "epsilon", 1e-8))
        clip_obs = float(getattr(vecenv, "clip_obs", 10.0))
    
    # Create a wrapper module that just outputs the actions
    class PytorchToOnnxWrapper(nn.Module):
        def __init__(self, actor):
            super().__init__()
            self.actor = actor
            self.use_obs_norm = use_obs_norm
            if self.use_obs_norm:
                mean = torch.tensor(obs_mean, dtype=torch.float32).view(1, -1)
                var = torch.tensor(obs_var, dtype=torch.float32).view(1, -1)
                self.register_buffer("obs_mean", mean)
                self.register_buffer("obs_var", var)
                self.obs_eps = float(obs_eps)
                self.clip_obs = float(clip_obs)
            
        def forward(self, obs):
            if getattr(self, "use_obs_norm", False):
                obs = (obs - self.obs_mean) / torch.sqrt(self.obs_var + self.obs_eps)
                obs = torch.clamp(obs, -self.clip_obs, self.clip_obs)
            # Pass observation through the actor network to get features
            features = self.actor.extract_features(obs)
            # Then through the action net
            action = self.actor.action_net(self.actor.mlp_extractor.forward_actor(features))
            return action
            
    onnx_policy = PytorchToOnnxWrapper(pytorch_policy)
    onnx_policy.eval()
    
    # Create dummy input with the right shape
    dummy_input = torch.randn(1, 6) # [saturation, flow, inflation, elasticity, volatility, is_ipo]
    
    # Export to ONNX
    # We must move the model back to CPU before export because 
    # ONNX export tracing for some operators is not fully supported on MPS
    onnx_policy.cpu()
    dummy_input = dummy_input.cpu()
    
    torch.onnx.export(
        onnx_policy,
        dummy_input,
        filename,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=['observation'],
        output_names=['action'],
        dynamic_axes={'observation': {0: 'batch_size'}, 'action': {0: 'batch_size'}}
    )
    print(f"Exported to {filename}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="EcoBrain 2.0 PPO Training & Export")
    parser.add_argument("--dataset", type=str, help="Path to the real server CSV data for online fine-tuning", default=None)
    parser.add_argument("--timesteps", type=int, help="Total timesteps for training", default=100000)
    parser.add_argument("--device", type=str, default="auto", help="auto/cpu/cuda/mps")
    parser.add_argument("--n-envs", type=int, default=0, help="Number of parallel envs; 0 = auto (CPU utilization key)")
    parser.add_argument("--n-envs-cap", type=int, default=64, help="Safety cap for auto n_envs (avoid too many processes)")
    parser.add_argument("--vec-env", type=str, default="auto", help="auto/dummy/subproc")
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--num-threads", type=int, default=0, help="Torch intra-op threads; 0 = don't set")
    parser.add_argument("--num-interop-threads", type=int, default=0, help="Torch inter-op threads; 0 = don't set")

    parser.add_argument("--learning-rate", type=float, default=3e-4)
    parser.add_argument("--n-steps", type=int, default=2048)
    parser.add_argument("--batch-size", type=int, default=0, help="0 = auto")
    parser.add_argument("--n-epochs", type=int, default=10)
    parser.add_argument("--gamma", type=float, default=0.99)
    parser.add_argument("--gae-lambda", type=float, default=0.95)
    parser.add_argument("--clip-range", type=float, default=0.2)
    parser.add_argument("--net-arch", type=str, default="", help="Comma-separated hidden sizes for MlpPolicy, e.g. 256,256,256")
    parser.add_argument("--no-vecnorm", action="store_true", help="Disable VecNormalize (obs/reward normalization)")
    parser.add_argument("--no-norm-obs", action="store_true", help="Disable observation normalization (VecNormalize)")
    parser.add_argument("--no-norm-reward", action="store_true", help="Disable reward normalization (VecNormalize)")
    parser.add_argument("--checkpoint-freq", type=int, default=100000, help="Save checkpoint every N timesteps (0=disable)")
    parser.add_argument("--checkpoint-dir", type=str, default="checkpoints", help="Directory to store checkpoints/eval logs")
    parser.add_argument("--no-resume", action="store_true", help="Do not resume from existing model/checkpoints")
    parser.add_argument("--eval-freq", type=int, default=0, help="Evaluate every N timesteps (0=disable)")
    parser.add_argument("--eval-episodes", type=int, default=5, help="Number of eval episodes per evaluation")
    parser.add_argument("--early-stop-patience", type=int, default=10, help="Stop if no eval improvement for N evals")
    parser.add_argument("--skip-onnx", action="store_true", help="Skip ONNX export (useful for quick smoke runs)")
    args = parser.parse_args()

    print("Starting EcoBrain 2.0 PPO Training & Export")

    if args.num_threads and args.num_threads > 0:
        torch.set_num_threads(int(args.num_threads))
    if args.num_interop_threads and args.num_interop_threads > 0:
        torch.set_num_interop_threads(int(args.num_interop_threads))
    
    model_low = train_model(
        value_type="low",
        total_timesteps=args.timesteps,
        dataset_path=args.dataset,
        device=args.device,
        n_envs=args.n_envs,
        vec_env=args.vec_env,
        learning_rate=args.learning_rate,
        n_steps=args.n_steps,
        batch_size=args.batch_size,
        n_epochs=args.n_epochs,
        gamma=args.gamma,
        gae_lambda=args.gae_lambda,
        clip_range=args.clip_range,
        seed=args.seed,
        net_arch=args.net_arch or None,
        n_envs_cap=args.n_envs_cap,
        vecnorm=not args.no_vecnorm,
        norm_obs=not args.no_norm_obs,
        norm_reward=not args.no_norm_reward,
        checkpoint_freq=args.checkpoint_freq,
        checkpoint_dir=args.checkpoint_dir,
        resume=not args.no_resume,
        eval_freq=args.eval_freq,
        eval_episodes=args.eval_episodes,
        early_stop_patience=args.early_stop_patience,
    )
    if not args.skip_onnx:
        export_to_onnx(model_low, "ecobrain_low_value.onnx")
    
    model_mid = train_model(
        value_type="mid",
        total_timesteps=args.timesteps,
        dataset_path=args.dataset,
        device=args.device,
        n_envs=args.n_envs,
        vec_env=args.vec_env,
        learning_rate=args.learning_rate,
        n_steps=args.n_steps,
        batch_size=args.batch_size,
        n_epochs=args.n_epochs,
        gamma=args.gamma,
        gae_lambda=args.gae_lambda,
        clip_range=args.clip_range,
        seed=args.seed,
        net_arch=args.net_arch or None,
        n_envs_cap=args.n_envs_cap,
        vecnorm=not args.no_vecnorm,
        norm_obs=not args.no_norm_obs,
        norm_reward=not args.no_norm_reward,
        checkpoint_freq=args.checkpoint_freq,
        checkpoint_dir=args.checkpoint_dir,
        resume=not args.no_resume,
        eval_freq=args.eval_freq,
        eval_episodes=args.eval_episodes,
        early_stop_patience=args.early_stop_patience,
    )
    if not args.skip_onnx:
        export_to_onnx(model_mid, "ecobrain_mid_value.onnx")
    
    model_high = train_model(
        value_type="high",
        total_timesteps=args.timesteps,
        dataset_path=args.dataset,
        device=args.device,
        n_envs=args.n_envs,
        vec_env=args.vec_env,
        learning_rate=args.learning_rate,
        n_steps=args.n_steps,
        batch_size=args.batch_size,
        n_epochs=args.n_epochs,
        gamma=args.gamma,
        gae_lambda=args.gae_lambda,
        clip_range=args.clip_range,
        seed=args.seed,
        net_arch=args.net_arch or None,
        n_envs_cap=args.n_envs_cap,
        vecnorm=not args.no_vecnorm,
        norm_obs=not args.no_norm_obs,
        norm_reward=not args.no_norm_reward,
        checkpoint_freq=args.checkpoint_freq,
        checkpoint_dir=args.checkpoint_dir,
        resume=not args.no_resume,
        eval_freq=args.eval_freq,
        eval_episodes=args.eval_episodes,
        early_stop_patience=args.early_stop_patience,
    )
    if not args.skip_onnx:
        export_to_onnx(model_high, "ecobrain_high_value.onnx")
    
    print("All done!")
