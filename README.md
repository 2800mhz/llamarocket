# LlamaRocket

LlamaRocket is an AI-assisted model rocket design environment built on OpenRocket. It gives language models controlled access to the OpenRocket component model, material and motor databases, and flight simulator.

The agent can inspect a design, modify components, select materials and motors, run simulations, and use the results to continue working toward a design goal. OpenRocket remains responsible for component compatibility and flight physics.

## Features

- Natural-language rocket design and analysis
- Stable component ID tracking
- Dynamic discovery of editable component properties
- SI units and enum choices in the agent context
- Component creation, deletion, and property editing
- Bulk, surface, and line material selection
- OpenRocket motor database integration
- Simulation feedback including apogee, velocity, acceleration, Mach, flight time, CG, CP, stability, and recovery data
- Transactional multi-property changes
- Conversation history stored with the rocket document
- Local and cloud model providers

## Model providers

### Local Ollama

Ollama is the default provider. LlamaRocket discovers all models installed on the configured Ollama server. The default model is `gemma4:e4b` when available.

For local URLs, LlamaRocket checks whether the Ollama API is running and attempts to start `ollama serve` automatically. Standard Windows installation paths are detected. A custom executable can be provided through the `OLLAMA_EXE` environment variable.

Models can also be downloaded from the interface with the `Pull Model` button.

### Cloud providers

The following providers are available from the settings dialog:

- OpenRouter
- NVIDIA NIM and Nemotron
- MiniMax
- Generic OpenAI-compatible APIs

Cloud providers use the `/v1/chat/completions` protocol. The model field is editable, so any model supported by the selected provider can be used.

API keys are kept in process memory. They are not written to rocket files or session logs. Keys can optionally be supplied through:

- `OPENROUTER_API_KEY`
- `NVIDIA_API_KEY`
- `MINIMAX_API_KEY`

## Requirements

- Java 17
- Ollama for local inference, or an API key for a cloud provider

## Running locally

Install or download a model:

```powershell
ollama pull gemma4:e4b
```

Start LlamaRocket:

```powershell
.\gradlew.bat swing:run
```

The application attempts to start the local Ollama service when necessary.

## Agent workflow

For each request, the agent receives a compact description of the current rocket, motor configuration, and latest simulation results. It responds with one structured action. LlamaRocket validates and executes that action, runs a new simulation when required, and returns the updated state to the model.

Available operations include:

- `inspect_design`
- `create_basic_rocket`
- `set_properties`
- `add_component`
- `delete_component_by_id`
- `list_materials`
- `set_material`
- `assign_motor`
- `report`
- `finish`

Older QwenRocket actions and saved conversation history remain supported for compatibility.

## Project structure

- `core/`: OpenRocket simulation core and LlamaRocket agent services
- `swing/`: desktop interface and assistant panel
- `docs/`: OpenRocket documentation
- `bridge/`: legacy Python integration

Important agent files:

- `core/src/main/java/info/openrocket/core/ai/LlamaRocketAgent.java`
- `core/src/main/java/info/openrocket/core/ai/LlamaRocketDesignService.java`
- `core/src/main/resources/ai/system_prompt.txt`
- `swing/src/main/java/info/openrocket/swing/gui/components/QwenAssistantPanel.java`

## Development

Compile the application:

```powershell
.\gradlew.bat core:compileJava swing:compileJava
```

Run tests:

```powershell
.\gradlew.bat core:test swing:test
```

Run the focused LlamaRocket tests:

```powershell
.\gradlew.bat core:test --tests info.openrocket.core.ai.LlamaRocketDesignServiceTest
```

## Current limitations

- Agent quality depends on the selected language model.
- Small local models may repeat actions or misunderstand complex design goals.
- Advanced stage separation and recovery configuration tools are still limited.
- Simulation results are engineering estimates and do not replace physical testing or range safety review.
- The Swing assistant class retains its historical `QwenAssistantPanel` name for backward compatibility.

## License

LlamaRocket is derived from OpenRocket and distributed under the GNU General Public License. See [LICENSE.TXT](LICENSE.TXT).
