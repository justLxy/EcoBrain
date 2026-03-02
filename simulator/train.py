import os
import torch
import torch.nn as nn
from stable_baselines3 import PPO
from stable_baselines3.common.env_checker import check_env
from ecobrain_env import EcoBrainEnv

# Register envs if needed or just use directly
import gymnasium as gym

def train_model(value_type="low", total_timesteps=100000):
    print(f"Training PPO for {value_type}-value items...")
    
    env = EcoBrainEnv(value_type=value_type)
    check_env(env)
    
    model = PPO("MlpPolicy", env, verbose=1, 
                learning_rate=3e-4, 
                n_steps=2048, 
                batch_size=64, 
                n_epochs=10, 
                gamma=0.99, 
                gae_lambda=0.95, 
                clip_range=0.2)
                
    model.learn(total_timesteps=total_timesteps)
    
    # Save the model
    model_name = f"ecobrain_ppo_{value_type}"
    model.save(model_name)
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
    # Extract the PyTorch module from SB3 PPO
    pytorch_policy = model.policy
    
    # Create a wrapper module that just outputs the actions
    class PytorchToOnnxWrapper(nn.Module):
        def __init__(self, actor):
            super().__init__()
            self.actor = actor
            
        def forward(self, obs):
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
    # In a real scenario we'd train for millions of steps. Here we do a short run to prove the concept.
    print("Starting EcoBrain 2.0 PPO Training & Export")
    
    model_low = train_model(value_type="low", total_timesteps=1000000)
    export_to_onnx(model_low, "ecobrain_low_value.onnx")
    
    model_mid = train_model(value_type="mid", total_timesteps=1000000)
    export_to_onnx(model_mid, "ecobrain_mid_value.onnx")
    
    model_high = train_model(value_type="high", total_timesteps=1000000)
    export_to_onnx(model_high, "ecobrain_high_value.onnx")
    
    print("All done!")
