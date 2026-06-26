import os
import json
import time

class SessionLogger:
    def __init__(self, log_dir="sessions"):
        self.log_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), log_dir)
        os.makedirs(self.log_dir, exist_ok=True)
        self.session_id = str(int(time.time()))
        self.log_file = os.path.join(self.log_dir, f"session_{self.session_id}.jsonl")
        
    def log_interaction(self, user_input, agent_reasoning, ork_changes, simulation_result):
        """Logs a single step of the agent's interaction."""
        entry = {
            "timestamp": time.time(),
            "user_input": user_input,
            "agent_reasoning": agent_reasoning,
            "ork_changes": ork_changes,
            "simulation_result": simulation_result
        }
        
        with open(self.log_file, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")
            
    def get_log_file(self):
        return self.log_file
