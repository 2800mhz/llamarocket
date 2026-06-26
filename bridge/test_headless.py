import os
import sys
from qwen_bridge import QwenRocketBridge

def main():
    bridge = QwenRocketBridge()
    
    # Path to sample ORK file
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sample_ork = os.path.join(base_dir, "core", "src", "main", "resources", "datafiles", "examples", "A simple model rocket.ork")
    
    if not os.path.exists(sample_ork):
        print(f"Sample file not found: {sample_ork}")
        sys.exit(1)
        
    print(f"Loading document: {sample_ork}")
    doc = bridge.load_ork(sample_ork)
    rocket = bridge.get_rocket(doc)
    
    # Run initial simulation
    print("\n--- Initial Simulation ---")
    results1 = bridge.run_simulation(doc)
    print(f"Initial Apogee: {results1['apogee_meters']:.2f} m")
    
    # Find and modify Nose Cone
    print("\n--- Modifying Nose Cone ---")
    nose_cone = None
    # In OpenRocket, the rocket is a tree of components
    try:
        bridge.modify_component(rocket, "Nose cone", "length", 0.05)
        print("Nose cone length set to 0.05m.")
    except Exception as e:
        print(f"Failed to modify component: {e}")

    # Run simulation again to see effect
    print("\n--- Second Simulation ---")
    results2 = bridge.run_simulation(doc)
    print(f"New Apogee: {results2['apogee_meters']:.2f} m")
    
    # Test Ollama if available
    # print("\n--- Asking Qwen Agent ---")
    # prompt = "I just increased the nose cone length of my rocket. Why did the apogee change?"
    # qwen_response = bridge.send_to_qwen(prompt, results2)
    # print(f"Qwen says:\n{qwen_response}")

if __name__ == "__main__":
    main()
