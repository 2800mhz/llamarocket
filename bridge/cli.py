import os
import sys
import json
from qwen_bridge import QwenRocketBridge
from session_logger import SessionLogger
from qwen_agent import QwenAgent

def main():
    print("Initializing QwenRocket Bridge...")
    bridge = QwenRocketBridge()
    logger = SessionLogger()
    agent = QwenAgent(bridge, logger)
    
    # Load sample rocket
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sample_ork = os.path.join(base_dir, "core", "src", "main", "resources", "datafiles", "examples", "A simple model rocket.ork")
    
    if not os.path.exists(sample_ork):
        print(f"Sample file not found: {sample_ork}")
        sys.exit(1)
        
    doc = bridge.load_ork(sample_ork)
    rocket = bridge.get_rocket(doc)
    
    print("\n" + "="*50)
    print("🚀 Welcome to QwenRocket Interactive CLI")
    print("="*50)
    print(f"Loaded rocket: {rocket.getName()}")
    print(f"Session Log: {logger.get_log_file()}")
    
    user_goal = input("\nWhat is your design goal? (e.g. 'Increase the apogee to 55m'): ")
    if not user_goal.strip():
        print("No goal provided. Exiting.")
        sys.exit(0)
        
    # Keep user goal separate to prepend to state
    messages = [
        {"role": "system", "content": agent.system_prompt}
    ]
    
    # Do an initial simulation so the agent has a baseline
    print("\nRunning baseline simulation...")
    results = bridge.run_simulation(doc)
    
    max_iterations = 10
    iteration = 0
    
    while iteration < max_iterations:
        iteration += 1
        print(f"\n--- Iteration {iteration}/{max_iterations} ---")
        
        # Build state context
        tree = bridge.get_component_tree(rocket)
        state_msg = (
            f"USER GOAL: {user_goal}\n\n"
            f"CURRENT ROCKET COMPONENT TREE:\n{json.dumps(tree, indent=2)}\n\n"
            f"LATEST SIMULATION RESULTS:\n{json.dumps(results, indent=2)}\n\n"
            "What is your next action?"
        )
        
        messages.append({"role": "user", "content": state_msg})
        
        response = agent.send_prompt(messages)
        if not response:
            print("Failed to get response from Qwen.")
            break
            
        # Add assistant response to history
        messages.append({"role": "assistant", "content": response})
        
        action = agent.parse_action(response)
        if not action:
            print("Could not parse action. Asking Qwen to try again.")
            messages.append({"role": "user", "content": "Your last response was not a valid JSON action. Please follow the format strictly."})
            continue
            
        reasoning = action.get("reasoning", "No reasoning provided.")
        cmd = action.get("action")
        
        # In case the LLM nests the action inside an "action" dict
        if isinstance(cmd, dict):
            action.update(cmd)
            cmd = action.get("action")
            
        ork_changes = None
        
        if cmd == "modify_components":
            modifications = action.get("modifications", [])
            
            if not isinstance(modifications, list):
                print("Error: modifications must be a list.")
                messages.append({"role": "user", "content": "Error: modifications must be a list."})
                continue
                
            print(f"Action: modify_components ({len(modifications)} changes)")
            ork_changes = modifications
            
            try:
                for mod in modifications:
                    comp = mod.get("component_name")
                    param = mod.get("parameter")
                    val = mod.get("new_value")
                    print(f" -> modify_component({comp}, {param}, {val})")
                    bridge.modify_component(rocket, comp, param, val)
                    
                # Auto run simulation after ALL modifications are applied
                results = bridge.run_simulation(doc)
                print(f"Resulting Apogee: {results['apogee_meters']:.2f} m")
                
                logger.log_interaction(
                    user_input=user_goal,
                    agent_reasoning=reasoning,
                    ork_changes=ork_changes,
                    simulation_result=results
                )
                
            except Exception as e:
                print(f"Error modifying components: {e}")
                messages.append({"role": "user", "content": f"Error modifying components: {e}"})
                
        elif cmd == "finish":
            print(f"Action: finish (Reason: {action.get('reason')})")
            print("Goal achieved!")
            break
            
        else:
            print(f"Unknown action: {cmd}")
            messages.append({"role": "user", "content": f"Unknown action: {cmd}. Available actions: modify_components, finish."})

if __name__ == "__main__":
    main()
