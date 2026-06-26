import requests
import json
import re

class QwenAgent:
    def __init__(self, bridge, logger, model_name="qwen3.5:4b", ollama_url="http://localhost:11434"):
        self.bridge = bridge
        self.logger = logger
        self.model_name = model_name
        self.ollama_url = ollama_url
        self.system_prompt = """You are QwenRocket, an expert AI rocket design agent. 
You are pair programming with a user to optimize a model rocket in OpenRocket.
You have the ability to modify the rocket's dimensions and run simulations to see the results.

At each step, you will be given the CURRENT ROCKET COMPONENT TREE (with parameters) and the LATEST SIMULATION RESULTS.
You must output a single JSON block wrapped in ```json ... ``` with your next action.

Available actions:
1. modify_components
   Required keys: "action": "modify_components", "modifications": [{"component_name": "<name>", "parameter": "<param>", "new_value": <float>}, ...]
   Use this to change one or more dimensions AT THE SAME TIME (values must be in meters). The simulation will be run automatically after your modifications to show you the new results.

2. finish
   Required keys: "action": "finish", "reason": "<explanation>"
   Use this when the user's goal has been accomplished.

IMPORTANT:
- You must respond, write your reasoning, and output your report message in the EXACT SAME LANGUAGE as the user's prompt (e.g., if the user speaks Turkish, you must reply in Turkish).
- CRITICAL REASONING RULE: You are STRICTLY FORBIDDEN from writing long essays in your thinking process. Your reasoning MUST be extremely short (1-3 sentences maximum). DO NOT analyze rules step by step. DO NOT debate with yourself. Decide quickly and output the JSON immediately. If your thinking process exceeds 30 words, you will be penalized.
- You can modify MULTIPLE components at once in a single "modify_components" action to save iterations!
- ALWAYS output exactly ONE JSON action block.
- DO NOT invent component names, use the ones provided in the component tree.
- DO NOT use LaTeX formatting, markdown math (like $), or unescaped backslashes in the JSON. Keep the text simple.

Example output:
```json
{
  "reasoning": "The current apogee is 50m, but the goal is 60m. Decreasing NoseCone length by 0.05m will reduce mass by roughly 5g. Simultaneously, decreasing BodyTube thickness by 0.0005m will reduce mass by another 3g. Together, this 8g reduction should improve apogee significantly.",
  "action": "modify_components",
  "modifications": [
    {"component_name": "Nose cone", "parameter": "length", "new_value": 0.050},
    {"component_name": "Body tube", "parameter": "thickness", "new_value": 0.0015}
  ]
}
```
"""

    def send_prompt(self, messages):
        """Sends chat messages to Ollama, streams to console, and returns the full text."""
        url = f"{self.ollama_url}/api/chat"
        payload = {
            "model": self.model_name,
            "messages": messages,
            "stream": True,
            "options": {
                "num_predict": 500
            }
        }
        
        try:
            print("Qwen: ", end="", flush=True)
            response = requests.post(url, json=payload, stream=True)
            response.raise_for_status()
            
            full_content = ""
            for line in response.iter_lines():
                if line:
                    data = json.loads(line)
                    msg = data.get("message", {})
                    
                    # Some models (like DeepSeek R1/Qwen variants) stream reasoning in 'thinking'
                    thinking_chunk = msg.get("thinking", "")
                    content_chunk = msg.get("content", "")
                    
                    chunk = ""
                    if thinking_chunk:
                        chunk += thinking_chunk
                    if content_chunk:
                        chunk += content_chunk
                        
                    full_content += chunk
                    
                    # Print to console, with fallback for Windows encoding issues
                    try:
                        print(chunk, end="", flush=True)
                    except UnicodeEncodeError:
                        print(chunk.encode('ascii', 'replace').decode('ascii'), end="", flush=True)
                        
            print() # Print a final newline when done
            return full_content
        except Exception as e:
            print(f"\nOllama API Error: {e}")
            return None

    def parse_action(self, response_text):
        """Extracts and parses the JSON action from the LLM response."""
        # Find JSON block wrapped in ```json ... ```
        match = re.search(r'```json\s*(.*?)\s*```', response_text, re.DOTALL)
        if match:
            json_str = match.group(1)
        else:
            # Fallback if the LLM just outputs raw JSON
            json_str = response_text.strip()
            
        # Clean up invalid backslashes (e.g., \approx, \text) that break json.loads
        json_str = re.sub(r'\\(?![/"\\bfnrtu])', r'\\\\', json_str)
            
        try:
            action = json.loads(json_str)
            return action
        except json.JSONDecodeError as e:
            print(f"Failed to parse agent JSON: {e}")
            print(f"Raw output: {response_text}")
            return None
