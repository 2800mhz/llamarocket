# LlamaRocket

LlamaRocket is a local-first engineering agent built into OpenRocket. It can inspect a rocket at property level, edit the design through validated tools, select materials and motors, run simulations, and use the results in an iterative design loop.

The default model is `gemma4:e4b` when installed. The model selector discovers every model available through the configured Ollama server, so Gemma, Llama, Qwen and other compatible local models can all be used without changing the application. The **Pull Model** button can download any Ollama model by name and select it immediately.

Cloud inference is available from **Settings** through OpenRouter, NVIDIA NIM/Nemotron, MiniMax, or any OpenAI-compatible `/v1/chat/completions` endpoint. Type a provider model ID in the editable model box. API keys remain in process memory and are never written to the rocket document or session logs. The application also reads `OPENROUTER_API_KEY`, `NVIDIA_API_KEY`, and `MINIMAX_API_KEY` when present.

## Current agent architecture

- **Design inspector:** exposes component IDs, hierarchy, paths, materials, and editable scalar/enum properties.
- **SI property schema:** values include units where known and enum choices where applicable.
- **ID-based mutations:** agent changes target stable component UUIDs rather than ambiguous display names.
- **Safe property batches:** every value is validated before application; a failed multi-property operation rolls back changes already applied by that batch.
- **OpenRocket-native compatibility:** component creation delegates structural compatibility checks to OpenRocket.
- **Material tools:** bulk, surface and line material databases are queryable and assignable by exact name.
- **Simulation feedback:** apogee, velocity, timing, warnings and flight events return to the agent after changes.
- **Deterministic goal controller:** target apogee and payload are parsed from the request; compatible motors are sampled from the OpenRocket database and simulated, and the closest result is selected before the language model tunes geometry.
- **Convergence guard:** the run stops automatically when simulated apogee is within 2 m or 1% of the requested target.
- **Legacy compatibility:** early QwenRocket actions and saved chat history remain readable while new conversations use the LlamaRocket tool protocol.

## Run

Requirements:

- Java 17
- Ollama
- At least one installed Ollama model

When the configured URL is local, LlamaRocket checks the Ollama API during startup and automatically launches `ollama serve` in the background if necessary. On Windows it detects standard Ollama installation locations; `OLLAMA_EXE` can point to a custom executable. Remote/custom servers are never started automatically.

```powershell
ollama pull gemma4:e4b
.\gradlew.bat swing:run
```

Other examples:

```powershell
ollama pull llama3.2
ollama pull qwen3.5:4b
```

Models pulled from inside LlamaRocket appear in the selector automatically.

## Agent tools

New agent conversations prefer these operations:

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

The OpenRocket simulation remains an engineering estimate. LlamaRocket output is not a substitute for physical testing, range safety review, or applicable launch rules.

## Development

```powershell
.\gradlew.bat core:compileJava swing:compileJava
.\gradlew.bat core:test swing:test
```

The project is derived from OpenRocket and distributed under the GNU General Public License. See [LICENSE.TXT](LICENSE.TXT).
