package info.openrocket.core.ai;

import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.EngineBlock;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.startup.Application;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

public class QwenAgent {
	public enum Provider {
		OLLAMA("Local Ollama", "http://localhost:11434"),
		OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
		NVIDIA_NIM("NVIDIA NIM / Nemotron", "https://integrate.api.nvidia.com/v1"),
		MINIMAX("MiniMax", "https://api.minimax.io/v1"),
		OPENAI_COMPATIBLE("OpenAI-compatible", "");

		private final String label;
		private final String defaultUrl;
		Provider(String label, String defaultUrl) { this.label = label; this.defaultUrl = defaultUrl; }
		public String getDefaultUrl() { return defaultUrl; }
		@Override public String toString() { return label; }
	}

	private final LlamaRocketDesignService designService = new LlamaRocketDesignService();
	private static final Object OLLAMA_START_LOCK = new Object();
	private static volatile String ollamaStartupError;

    private String loadSystemPrompt() {
        try (InputStream is = QwenAgent.class.getResourceAsStream("/ai/system_prompt.txt")) {
            if (is == null) {
                return "You are LlamaRocket AI. (Fallback prompt: system_prompt.txt not found)";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                
                String prompt = sb.toString();
                // We will dynamically inject motors when building the system message.
                return prompt;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "You are LlamaRocket AI. (Fallback prompt: error loading system_prompt.txt)";
        }
    }


    private String modelName;
    private String ollamaUrl;
	private Provider provider = Provider.OLLAMA;
	private String apiKey = "";
    private final Gson gson;
    private List<JsonObject> messageHistory;
    private final String sessionId;

    public static class AgentStreamResult {
        private final String thinking;
        private final String content;

        public AgentStreamResult(String thinking, String content) {
            this.thinking = thinking != null ? thinking : "";
            this.content = content != null ? content : "";
        }

        public String getThinking() {
            String combinedThinking = thinking;
            int startIdx = content.indexOf("<thinking>");
            int endIdx = content.indexOf("</thinking>");
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                String extracted = content.substring(startIdx + 10, endIdx).trim();
                combinedThinking = combinedThinking.isEmpty() ? extracted : combinedThinking + "\n" + extracted;
            }
            return combinedThinking;
        }

        public String getContent() {
            return content;
        }

        public String getCombined() {
            return thinking + content;
        }

        public String getTextForHistory() {
            return content.isEmpty() ? getCombined().trim() : content;
        }
    }

    public interface StreamCallback {
        void onThinkingChunk(String text);
        void onContentChunk(String text);
    }

    public QwenAgent(String modelName, String ollamaUrl) {
        this.modelName = modelName;
        this.ollamaUrl = ollamaUrl;
        this.gson = new GsonBuilder().create();
        this.messageHistory = new ArrayList<>();
        this.sessionId = java.util.UUID.randomUUID().toString();
        
        // Dynamically get 4-5 motor designations to inject into the prompt
        String motorExamples = "A8-3, B6-4, C6-5, D12-5"; // Default fallback
        try {
            List<? extends Motor> allMotors = Application.getMotorSetDatabase().findMotors(null, null, null, null, Double.NaN, Double.NaN);
            if (allMotors != null && allMotors.size() >= 4) {
                motorExamples = allMotors.get(0).getDesignation() + ", " + 
                                allMotors.get(allMotors.size()/4).getDesignation() + ", " + 
                                allMotors.get(allMotors.size()/2).getDesignation() + ", " + 
                                allMotors.get(allMotors.size()-1).getDesignation();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        String finalPrompt = loadSystemPrompt().replace("%MOTORS%", motorExamples);
        systemMessage.addProperty("content", finalPrompt);
        this.messageHistory.add(systemMessage);
    }

    public void addUserMessage(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", text);
        this.messageHistory.add(msg);
    }

    public void addAssistantMessage(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", text);
        this.messageHistory.add(msg);
    }

    public void loadHistory(com.google.gson.JsonArray history) {
        if (history != null && history.size() > 0) {
            this.messageHistory.clear();
            for (JsonElement el : history) {
                this.messageHistory.add(el.getAsJsonObject());
            }
        }
    }

    public com.google.gson.JsonArray getHistoryAsJsonArray() {
        JsonArray arr = new JsonArray();
        for (JsonObject msg : this.messageHistory) {
            arr.add(msg);
        }
        return arr;
    }
    
    public List<JsonObject> getMessageHistory() {
        return this.messageHistory;
    }

    public void removeLastAssistantMessage() {
        if (!messageHistory.isEmpty()) {
            JsonObject last = messageHistory.get(messageHistory.size() - 1);
            if (last.has("role") && "assistant".equals(last.get("role").getAsString())) {
                messageHistory.remove(messageHistory.size() - 1);
            }
        }
    }

    public void removeLastUserMessage() {
        if (!messageHistory.isEmpty()) {
            JsonObject last = messageHistory.get(messageHistory.size() - 1);
            if (last.has("role") && "user".equals(last.get("role").getAsString())) {
                messageHistory.remove(messageHistory.size() - 1);
            }
        }
    }

    private void pruneHistory() {
        // Keep System Prompt (index 0) and the last 6 messages (3 full turns).
        int maxHistorySize = 7;
        while (messageHistory.size() > maxHistorySize) {
            // Remove the oldest message after the system prompt
            messageHistory.remove(1);
        }
    }

    public AgentStreamResult sendPromptStreaming(StreamCallback callback) throws Exception {
        pruneHistory();
		if (provider != Provider.OLLAMA) return sendOpenAiCompatible(callback);
		if (isLocalOllamaUrl(this.ollamaUrl) && !ensureLocalOllamaRunning(this.ollamaUrl)) {
			throw new IOException(ollamaStartupError != null ? ollamaStartupError :
					"Ollama is not available at " + this.ollamaUrl);
		}
        URL url = new URL(this.ollamaUrl + "/api/chat");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setConnectTimeout(10_000);
		con.setReadTimeout(180_000);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; utf-8");
        con.setRequestProperty("Accept", "application/json");
        con.setDoOutput(true);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", this.modelName);
        
        JsonArray messagesArr = new JsonArray();
        for (JsonObject msg : this.messageHistory) {
            messagesArr.add(msg);
        }
        payload.add("messages", messagesArr);
        payload.addProperty("stream", true);
        payload.addProperty("think", false);
        
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", 8192);
        options.addProperty("num_predict", 768);
        payload.add("options", options);

        try(OutputStream os = con.getOutputStream()) {
            byte[] input = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int status = con.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("Ollama API Error: HTTP " + status);
        }

        StringBuilder thinking = new StringBuilder();
        StringBuilder content = new StringBuilder();
        try(BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                if (responseLine.trim().isEmpty()) continue;
                JsonObject data = JsonParser.parseString(responseLine).getAsJsonObject();
                if (data.has("message")) {
                    JsonObject msgObj = data.getAsJsonObject("message");
                    if (msgObj.has("thinking")) {
                        String chunk = msgObj.get("thinking").getAsString();
                        if (!chunk.isEmpty()) {
                            thinking.append(chunk);
                            if (callback != null) {
                                callback.onThinkingChunk(chunk);
                            }
                        }
                    }
                    if (msgObj.has("content")) {
                        String chunk = msgObj.get("content").getAsString();
                        if (!chunk.isEmpty()) {
                            content.append(chunk);
                            if (callback != null) {
                                callback.onContentChunk(chunk);
                            }
                        }
                    }
                }
            }
        }
        return new AgentStreamResult(thinking.toString(), content.toString());
    }

	private AgentStreamResult sendOpenAiCompatible(StreamCallback callback) throws Exception {
		if (apiKey == null || apiKey.isBlank()) throw new IOException("Cloud provider API key is missing.");
		URL url = new URL(this.ollamaUrl.replaceAll("/+$", "") + "/chat/completions");
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json; utf-8");
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty("Authorization", "Bearer " + apiKey);
		if (provider == Provider.OPENROUTER) connection.setRequestProperty("X-OpenRouter-Title", "LlamaRocket");
		connection.setConnectTimeout(15_000);
		connection.setReadTimeout(180_000);
		connection.setDoOutput(true);

		JsonObject payload = new JsonObject();
		payload.addProperty("model", modelName);
		JsonArray messages = new JsonArray();
		for (JsonObject message : messageHistory) messages.add(message);
		payload.add("messages", messages);
		payload.addProperty("stream", false);
		payload.addProperty("max_tokens", 768);
		payload.addProperty("temperature", 0.2);
		try (OutputStream output = connection.getOutputStream()) {
			output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
		}
		int status = connection.getResponseCode();
		InputStream responseStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
		String response = responseStream == null ? "" : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
		if (status < 200 || status >= 300) throw new IOException(provider + " API error (HTTP " + status + "): " + response);
		JsonObject root = JsonParser.parseString(response).getAsJsonObject();
		JsonArray choices = root.getAsJsonArray("choices");
		if (choices == null || choices.isEmpty()) throw new IOException(provider + " returned no choices.");
		JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
		String content = message != null && message.has("content") && !message.get("content").isJsonNull()
				? message.get("content").getAsString() : "";
		String thinking = message != null && message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()
				? message.get("reasoning_content").getAsString() : "";
		if (callback != null) {
			if (!thinking.isEmpty()) callback.onThinkingChunk(thinking);
			if (!content.isEmpty()) callback.onContentChunk(content);
		}
		return new AgentStreamResult(thinking, content);
	}

    public JsonObject parseAction(QwenAgent.AgentStreamResult result) {
        if (result == null) {
            return null;
        }
        JsonObject action = parseAction(result.getContent());
        if (action != null) {
            return action;
        }
        return parseAction(result.getCombined());
    }

    public JsonObject parseAction(String responseText) {
        String jsonStr = responseText;
        
        // 1. Try to extract from <tool_call> block
        int startIdx = jsonStr.indexOf("<tool_call>");
        int endIdx = jsonStr.indexOf("</tool_call>");
        
        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            jsonStr = jsonStr.substring(startIdx + 11, endIdx).trim();
        } else {
            // Fallback: look for ```json block if <tool_call> is missing
            Pattern pattern = Pattern.compile("```json\\s*(.*?)\\s*```", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(jsonStr);
            if (matcher.find()) {
                jsonStr = matcher.group(1).trim();
            } else {
                // Absolute fallback: extract first matching curly braces { ... }
                int firstBrace = jsonStr.indexOf('{');
                int lastBrace = jsonStr.lastIndexOf('}');
                if (firstBrace != -1 && lastBrace != -1 && lastBrace >= firstBrace) {
                    jsonStr = jsonStr.substring(firstBrace, lastBrace + 1);
                }
            }
        }
        
        // Clean backslashes that might break parsing
        jsonStr = jsonStr.replaceAll("\\\\(?![/\"\\\\bfnrtu])", "\\\\\\\\");

        try {
            return JsonParser.parseString(jsonStr).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("JSON Parse Error. Raw string was:\n" + jsonStr);
            return null;
        }
    }

    public void setModelName(String modelName) {
		if (modelName == null || modelName.isBlank()) {
			throw new IllegalArgumentException("Model name cannot be empty.");
		}
        this.modelName = modelName;
    }

	public void setOllamaUrl(String ollamaUrl) {
		if (ollamaUrl == null || ollamaUrl.isBlank()) {
			throw new IllegalArgumentException("Ollama URL cannot be empty.");
		}
		this.ollamaUrl = ollamaUrl.replaceAll("/+$", "");
	}

	public void configureProvider(Provider provider, String baseUrl, String apiKey) {
		this.provider = provider != null ? provider : Provider.OLLAMA;
		String resolvedUrl = baseUrl == null || baseUrl.isBlank() ? this.provider.getDefaultUrl() : baseUrl.trim();
		setOllamaUrl(resolvedUrl);
		this.apiKey = apiKey != null ? apiKey.trim() : "";
	}

	public Provider getProvider() { return provider; }

    public static List<String> getAvailableModels(String ollamaUrl) {
        List<String> models = new ArrayList<>();
        try {
			ensureLocalOllamaRunning(ollamaUrl);
            URL url = new URL(ollamaUrl + "/api/tags");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int status = conn.getResponseCode();
            if (status == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine = null;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                    JsonArray modelsArray = json.getAsJsonArray("models");
                    if (modelsArray != null) {
                        for (JsonElement el : modelsArray) {
                            models.add(el.getAsJsonObject().get("name").getAsString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return models;
    }

	/** Starts a locally installed Ollama server when localhost is configured and it is not running. */
	public static boolean ensureLocalOllamaRunning(String ollamaUrl) {
		if (!isLocalOllamaUrl(ollamaUrl)) return false;
		if (isOllamaHealthy(ollamaUrl)) {
			ollamaStartupError = null;
			return true;
		}
		synchronized (OLLAMA_START_LOCK) {
			if (isOllamaHealthy(ollamaUrl)) return true;
			try {
				String executable = findOllamaExecutable();
				if (executable == null) {
					ollamaStartupError = "Ollama is not installed or could not be found.";
					return false;
				}
				Path log = Paths.get(System.getProperty("java.io.tmpdir"), "llamarocket-ollama.log");
				new ProcessBuilder(executable, "serve")
						.redirectErrorStream(true)
						.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
						.start();
				for (int i = 0; i < 30; i++) {
					if (isOllamaHealthy(ollamaUrl)) {
						ollamaStartupError = null;
						return true;
					}
					Thread.sleep(250);
				}
				ollamaStartupError = "Ollama was started but its API did not become ready. Log: " + log;
			} catch (Exception e) {
				ollamaStartupError = "Could not start Ollama: " + e.getMessage();
			}
		}
		return false;
	}

	public static String getOllamaStartupError() {
		return ollamaStartupError;
	}

	private static boolean isLocalOllamaUrl(String value) {
		try {
			String host = URI.create(value).getHost();
			return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
		} catch (Exception e) {
			return false;
		}
	}

	private static boolean isOllamaHealthy(String baseUrl) {
		try {
			HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl.replaceAll("/+$", "") + "/api/tags").openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(500);
			connection.setReadTimeout(1_000);
			return connection.getResponseCode() == 200;
		} catch (Exception ignored) {
			return false;
		}
	}

	private static String findOllamaExecutable() {
		String override = System.getenv("OLLAMA_EXE");
		if (override != null && Files.isRegularFile(Paths.get(override))) return override;
		List<Path> candidates = new ArrayList<>();
		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData != null) {
			candidates.add(Paths.get(localAppData, "Programs", "Ollama", "ollama.exe"));
			candidates.add(Paths.get(localAppData, "Ollama", "ollama.exe"));
		}
		String programFiles = System.getenv("ProgramFiles");
		if (programFiles != null) candidates.add(Paths.get(programFiles, "Ollama", "ollama.exe"));
		for (Path candidate : candidates) if (Files.isRegularFile(candidate)) return candidate.toString();
		// On macOS/Linux and Windows installations already present on PATH.
		return isWindows() ? null : "ollama";
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
	}

	public static void pullModel(String ollamaUrl, String modelName) throws IOException {
		if (modelName == null || modelName.isBlank()) {
			throw new IllegalArgumentException("Model name cannot be empty.");
		}
		URL url = new URL(ollamaUrl.replaceAll("/+$", "") + "/api/pull");
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json; utf-8");
		connection.setConnectTimeout(10_000);
		connection.setReadTimeout(30 * 60_000);
		connection.setDoOutput(true);
		JsonObject payload = new JsonObject();
		payload.addProperty("model", modelName.trim());
		payload.addProperty("stream", false);
		try (OutputStream output = connection.getOutputStream()) {
			output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
		}
		int status = connection.getResponseCode();
		if (status != 200) {
			String message;
			try (InputStream error = connection.getErrorStream()) {
				message = error == null ? "HTTP " + status : new String(error.readAllBytes(), StandardCharsets.UTF_8);
			}
			throw new IOException("Ollama model pull failed: " + message);
		}
		try (InputStream input = connection.getInputStream()) {
			input.readAllBytes();
		}
	}

    public JsonObject getComponentTree(RocketComponent root) {
		return designService.inspectComponent(root);
    }

	public RocketComponent findComponentById(RocketComponent root, String id) {
		return designService.findById(root, id);
	}

	public JsonObject getDesignSummary(RocketComponent root) {
		return designService.inspectSummary(root);
	}

	public JsonObject createBasicRocket(Rocket rocket) {
		return designService.createBasicRocket(rocket);
	}

	public JsonObject createBasicRocket(Rocket rocket, double payloadMassKg) {
		return designService.createBasicRocket(rocket, payloadMassKg);
	}

	public void setProperties(Rocket rocket, String componentId, java.util.Map<String, Object> changes) throws Exception {
		designService.setProperties(rocket, componentId, changes);
	}

	public RocketComponent addComponentById(Rocket rocket, String parentId, String type, String name) throws Exception {
		return designService.addComponent(rocket, parentId, type, name);
	}

	public void deleteComponentById(Rocket rocket, String componentId) {
		designService.deleteComponent(rocket, componentId);
	}

	public JsonArray listMaterials(String type) {
		return designService.listMaterials(type);
	}

	public void setMaterial(Rocket rocket, String componentId, String type, String name) throws Exception {
		designService.setMaterial(rocket, componentId, type, name);
	}
    
    public RocketComponent findComponentByName(RocketComponent current, String name) {
        if (current.getName().equalsIgnoreCase(name)) {
            return current;
        }
        for (RocketComponent child : current.getChildren()) {
            RocketComponent found = findComponentByName(child, name);
            if (found != null) return found;
        }
        return null;
    }

    public String getComponentNameList(RocketComponent root) {
        StringBuilder sb = new StringBuilder();
        appendComponentNameList(root, sb, 0);
        return sb.toString().trim();
    }

    private void appendComponentNameList(RocketComponent component, StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append("- name: \"").append(component.getName())
          .append("\" (Type: ").append(component.getClass().getSimpleName()).append(")\n");
        for (RocketComponent child : component.getChildren()) {
            appendComponentNameList(child, sb, depth + 1);
        }
    }

    public boolean isSupportedComponentType(String type) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case "AxialStage":
            case "NoseCone":
            case "BodyTube":
            case "TrapezoidFinSet":
            case "InnerTube":
            case "EngineBlock":
            case "Parachute":
            case "ShockCord":
            case "Transition":
            case "MassComponent":
                return true;
            default:
                return false;
        }
    }

    public void modifyComponent(Rocket rocket, String componentName, String parameter, double newValue) throws Exception {
        RocketComponent comp = findComponentByName(rocket, componentName);
        if (comp == null) throw new Exception("Component not found: " + componentName);
        if (comp instanceof Rocket || comp instanceof info.openrocket.core.rocketcomponent.AxialStage) {
            throw new Exception("Cannot modify a Stage or Rocket container. Use add_components to add parts to \"" + componentName + "\".");
        }
        
        parameter = parameter.replace("_", "");
        if (comp instanceof info.openrocket.core.rocketcomponent.NoseCone && parameter.equalsIgnoreCase("outerradius")) {
            ((info.openrocket.core.rocketcomponent.NoseCone) comp).setBaseRadius(newValue);
            return;
        }

        String setterName = "set" + parameter.substring(0, 1).toUpperCase() + parameter.substring(1).toLowerCase();
        Method setter = null;
        
        for (Method m : comp.getClass().getMethods()) {
            if (m.getName().equalsIgnoreCase(setterName) && m.getParameterCount() == 1) {
                if (m.getParameterTypes()[0] == double.class) {
                    setter = m;
                    break;
                }
            }
        }
        
        if (setter == null) throw new Exception("Parameter not found or not modifiable: " + parameter);
        setter.invoke(comp, newValue);
    }

    public void addComponent(Rocket rocket, String parentName, String type, String name) throws Exception {
        RocketComponent parent = findComponentByName(rocket, parentName);
        if (parent == null) throw new Exception("Parent component not found: " + parentName + ". Use EXACT names from the tree.");

        if ("NoseCone".equals(type) && !(parent instanceof AxialStage)) {
            throw new Exception("NoseCone can only attach to an AxialStage.");
        }

        RocketComponent newComp = null;
        switch (type) {
            case "AxialStage": newComp = new AxialStage(); break;
            case "NoseCone": newComp = new NoseCone(); break;
            case "BodyTube": newComp = new BodyTube(); break;
            case "TrapezoidFinSet": newComp = new TrapezoidFinSet(); break;
            case "InnerTube": newComp = new InnerTube(); break;
            case "EngineBlock": newComp = new EngineBlock(); break;
            case "Parachute": newComp = new Parachute(); break;
            case "ShockCord": newComp = new ShockCord(); break;
            case "Transition": newComp = new Transition(); break;
            case "MassComponent": newComp = new MassComponent(); break;
            default: throw new Exception("Unsupported component type: " + type + ". Supported: AxialStage, NoseCone, BodyTube, TrapezoidFinSet, InnerTube, EngineBlock, Parachute, ShockCord, Transition, MassComponent.");
        }

        if (name != null && !name.isEmpty()) {
            if (findComponentByName(rocket, name) != null) {
                throw new Exception("A component with the name '" + name + "' already exists! You MUST provide a UNIQUE name.");
            }
            newComp.setName(name);
        } else {
            throw new Exception("You MUST provide a 'name' for the new component.");
        }

        if (!parent.isCompatible(newComp)) {
            throw new Exception(describeAddCompatibilityError(type, parentName, parent));
        }
        
        parent.addChild(newComp);
        if (newComp instanceof info.openrocket.core.rocketcomponent.NoseCone) {
            parent.moveChild(newComp, 0);
        }
        
        if (newComp instanceof info.openrocket.core.rocketcomponent.FinSet) {
            newComp.setAxialMethod(info.openrocket.core.rocketcomponent.position.AxialMethod.BOTTOM);
            newComp.setAxialOffset(0.0);
        }
    }

    private boolean hasExistingNoseCone(RocketComponent current) {
        if (current instanceof NoseCone) {
            return true;
        }
        for (RocketComponent child : current.getChildren()) {
            if (hasExistingNoseCone(child)) {
                return true;
            }
        }
        return false;
    }

    private String describeAddCompatibilityError(String type, String parentName, RocketComponent parent) {
        String parentKind = parent.getClass().getSimpleName();
        if ("NoseCone".equals(type) || "BodyTube".equals(type)) {
            return type + " cannot attach to \"" + parentName + "\" (" + parentKind + "). Parent must be an AxialStage — never a BodyTube.";
        }
        if ("AxialStage".equals(type)) {
            return "AxialStage must attach to the Rocket root, not \"" + parentName + "\" (" + parentKind + ").";
        }
        return type + " cannot attach to \"" + parentName + "\" (" + parentKind + "). Parent must be a BodyTube.";
    }
    
    public void deleteComponent(Rocket rocket, String componentName) throws Exception {
        RocketComponent comp = findComponentByName(rocket, componentName);
        if (comp == null) throw new Exception("Not found: " + componentName);
        if (comp instanceof Rocket) throw new Exception("Cannot delete the rocket root: " + componentName);
        if (comp.getParent() == null) throw new Exception("Cannot delete component with no parent: " + componentName);
        comp.getParent().removeChild(comp);
    }
    
    public void assignMotor(Rocket rocket, String componentName, String motorDesignation) throws Exception {
        RocketComponent comp = designService.findById(rocket, componentName);
        if (comp == null) {
            comp = findComponentByName(rocket, componentName); // legacy saved conversations
        }
        if (comp == null) throw new Exception("Component not found: " + componentName);
        if (!(comp instanceof MotorMount)) {
            throw new Exception("Component is not a motor mount. Use a BodyTube or InnerTube.");
        }
        
        String baseDesignation = motorDesignation.trim();
        double delay = 0.0;
        if (motorDesignation.contains("-")) {
            String[] parts = motorDesignation.split("-");
            baseDesignation = parts[0].trim();
            try {
                delay = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        List<? extends Motor> motors = Application.getMotorSetDatabase().findMotors(null, null, null, motorDesignation.trim(), Double.NaN, Double.NaN);
        if (motors.isEmpty()) {
            motors = Application.getMotorSetDatabase().findMotors(null, null, null, baseDesignation, Double.NaN, Double.NaN);
        }
        if (motors.isEmpty()) {
            throw new Exception("Motor not found: " + motorDesignation + ". Use A8-3, B6-4, C6-5, or D12-5.");
        }
        
        Motor motor = motors.get(0);
        MotorMount mount = (MotorMount) comp;
        mount.setMotorMount(true);
        info.openrocket.core.rocketcomponent.FlightConfigurationId fcid = rocket.getSelectedConfiguration().getId();
        
        if (fcid.equals(info.openrocket.core.rocketcomponent.FlightConfigurationId.DEFAULT_VALUE_FCID)) {
            fcid = new info.openrocket.core.rocketcomponent.FlightConfigurationId();
            rocket.createFlightConfiguration(fcid);
            rocket.getFlightConfiguration(fcid).setName("[" + motorDesignation + "]");
            rocket.setSelectedConfiguration(fcid);
        }

        MotorConfiguration targetConfig = new MotorConfiguration(mount, fcid, mount.getDefaultMotorConfig());
        mount.setMotorConfig(targetConfig, fcid);
        
        targetConfig.setMotor(motor);
        if (delay > 0) {
            targetConfig.setEjectionDelay(delay);
        }
        
        rocket.getFlightConfiguration(fcid).addMotor(targetConfig);

        info.openrocket.core.rocketcomponent.FlightConfiguration config = rocket.getFlightConfiguration(fcid);
        config.update(); // Re-evaluates active motors
        
        // Notify the UI that motors have changed for this configuration
        rocket.fireComponentChangeEvent(info.openrocket.core.rocketcomponent.ComponentChangeEvent.MOTOR_CHANGE, fcid);
    }
    
    public void logSession(String userGoal, String reasoning, JsonElement orkChanges, JsonObject simResult) {
        String homeDir = System.getProperty("user.home");
        Path sessionDir = Paths.get(homeDir, "llamarocket-sessions");
        try {
            Files.createDirectories(sessionDir);
            File logFile = sessionDir.resolve(sessionId + ".jsonl").toFile();
            
            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", Instant.now().toString());
            entry.addProperty("user_goal", userGoal);
            entry.addProperty("agent_reasoning", reasoning);
            if (orkChanges != null) {
                entry.add("ork_changes", orkChanges);
            }
            if (simResult != null) {
                entry.add("simulation_result", simResult);
            }
            
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(gson.toJson(entry) + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
