# 🚀 LlamaRocket (QwenRocket)

**QwenRocket** (formerly known as LlamaRocket) is an AI-Enhanced fork of the OpenRocket simulator. It features an integrated AI Assistant powered by local LLMs (Qwen/Llama/Gemma) to help you design, optimize, and simulate multi-stage rockets through natural language prompts.

![Qwen Assistant Interface](qwen-panel.png)

## 🤖 AI Assistant Features
- **Natural Language Design**: Describe your rocket (e.g., "Build a 2-stage rocket with a 250m apogee and 400g payload") and the AI will construct the component tree.
- **Smart Component Management**: Add and manage Transitions, Parachutes, Shock Cords, Nose Cones, Inner Tubes, and more directly through chat.
- **Auto-Optimization**: The AI automatically runs simulations, identifies issues (e.g., missing motors, unstable designs), and adjusts parameters to achieve target goals.
- **Dynamic Motor Injection**: Selects and assigns real motors from your local database based on simulation requirements.
- **Local & Private**: Powered locally via Ollama, ensuring your prompt data and designs never leave your computer.

---

## 📋 Roadmap / To-Do List

Below is the current status of the project and a list of features to be added in the future:

- [x] **Basic AI Integration:** Integration of the Qwen/Llama/Gemma chat panel (Qwen Assistant Panel) into the OpenRocket UI.
- [x] **Component Addition/Removal:** Direct manipulation of the rocket component tree via natural language prompts.
- [x] **Motor Assignment:** AI autonomously selects appropriate motors for rocket stages and runs simulations automatically.
- [x] **Error Catching (Debug & Self-Correction):** The system detects errors such as invalid motor selections or invalid component names, allowing the AI to self-correct within its loop.
- [x] **Multi-Language Support:** By embedding language rules directly into the JSON formatting templates, the assistant perfectly adapts to the user's command language (e.g., fully supporting Turkish or English).
- [x] **Autonomous Testing and Reporting (Anti-Loop):** When numerical targets are unreachable (e.g., exactly 500m apogee), the AI avoids entering infinite motor-testing loops. Instead, it analyzes the situation, provides logical reports, and suggests structural changes.
- [ ] **Advanced Optimization:** AI-assisted autonomous aerodynamic corrections for Center of Gravity (CG) and Center of Pressure (CP) optimization (e.g., adding mass, clipping fins, altering body tube length).
- [ ] **Customized LLM Models:** Integration of specialized, fine-tuned lightweight local models (LoRA) optimized for rocketry and aerodynamics.

### 🌟 Recent Updates (Today's Achievements)
- **Smart Loop Management:** The `Anti-Loop` rule was successfully integrated to overcome the tendency of local AI models to get stuck in infinite optimization loops.
- **Read-Only Mode Support:** When the user issues commands like "analyze" or "examine", the system enters a stable state where it makes zero modifications to the rocket architecture and simply reports the current status in the user's language.
- **Modern Interface & Gemma Integration:** The assistant UI fonts were modernized (Segoe UI), margins were improved, and the code was updated to automatically detect and default to `Gemma` AI models.

---

## 🛠️ Getting Started

1. **Install Ollama:** Install [Ollama](https://ollama.com) on your computer and download `qwen` or `gemma` (`ollama run gemma4:e4b` or `ollama run qwen`).
2. **Build the Project:** 
   ```bash
   ./gradlew build
   ```
3. **Run the Application:**
   ```bash
   ./gradlew swing:run
   ```
4. **Use the AI Assistant:** Open the side panel and start issuing commands about your rocket. (e.g., "Add a C6-5 motor to the first stage and run the simulation.")

## 📜 License
OpenRocket is proudly open-source under the [GNU GPL](https://www.gnu.org/licenses/gpl-3.0.en.html) license.
