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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

public class QwenAgent {

    private static final String SYSTEM_PROMPT = 
        "You are QwenRocket, an expert AI rocket design agent.\n" +
        "You are pair programming with a user to optimize a model rocket in OpenRocket.\n" +
        "You have the ability to modify the rocket's dimensions and run simulations to see the results.\n\n" +
        "At each step, you will be given the CURRENT ROCKET COMPONENT TREE (with parameters) and the LATEST SIMULATION RESULTS.\n" +
        "You must output a single JSON block wrapped in ```json ... ``` with your next action.\n\n" +
        "Available actions:\n" +
        "1. modify_components\n" +
        "   Required keys: \"action\": \"modify_components\", \"modifications\": [{\"component_name\": \"<name>\", \"parameter\": \"<param>\", \"new_value\": <float>}, ...]\n" +
        "   Use this to change one or more dimensions AT THE SAME TIME (values must be in meters). The simulation will be run automatically after your modifications to show you the new results.\n\n" +
        "2. add_components\n" +
        "   Required keys: \"action\": \"add_components\", \"components\": [{\"type\": \"<type>\", \"parent\": \"<parent_name>\", \"name\": \"<new_name>\"}, ...]\n" +
        "   Use this to add new physical parts to the rocket.\n" +
        "   ALLOWED TYPES & PARENTS:\n" +
        "   - NoseCone, BodyTube -> Can be added to a Stage (like \"Devam Et\")\n" +
        "   - TrapezoidFinSet, InnerTube, EngineBlock, Parachute -> MUST be added to a BodyTube (NOT a Stage!)\n" +
        "   CRITICAL RULES FOR add_components:\n" +
        "   - NEVER use a parent name that doesn't exist in the CURRENT ROCKET COMPONENT TREE, unless you JUST created it in the same array.\n" +
        "   - Do NOT invent parent names like \"Stage2 Tube\" if you didn't explicitly create them.\n" +
        "   EXAMPLE TO BUILD A BASIC ROCKET IN ONE GO:\n" +
        "   {\"action\": \"add_components\", \"components\": [\n" +
        "     {\"type\": \"NoseCone\", \"parent\": \"Devam Et\", \"name\": \"Nose\"},\n" +
        "     {\"type\": \"BodyTube\", \"parent\": \"Devam Et\", \"name\": \"Body\"},\n" +
        "     {\"type\": \"TrapezoidFinSet\", \"parent\": \"Body\", \"name\": \"Fins\"},\n" +
        "     {\"type\": \"Parachute\", \"parent\": \"Body\", \"name\": \"Chute\"}\n" +
        "   ]}\n\n" +
        "3. assign_motor\n" +
        "   Required keys: \"action\": \"assign_motor\", \"component_name\": \"<name_of_bodytube_or_innertube>\", \"motor\": \"<motor_name>\"\n" +
        "   CRITICAL: Motors MUST be assigned to a BodyTube or InnerTube. NEVER assign a motor to an EngineBlock (an EngineBlock is just a thrust ring, not a motor mount).\n" +
        "   Default recommendations:\n" +
        "   - Total length < 0.5m -> A8-3\n" +
        "   - Total length 0.5-1.0m -> B6-4 or C6-5\n" +
        "   - Total length > 1.0m -> C6-5 or D12-5\n\n" +
        "4. plan_and_continue\n" +
        "   Required keys: \"action\": \"plan_and_continue\", \"message\": \"<your step-by-step plan>\"\n" +
        "   Use this BEFORE building a complex multi-stage rocket. Output your plan. The system will prompt you to execute the first step, allowing you to build the rocket gradually across multiple iterations.\n\n" +
        "5. report\n" +
        "   Required keys: \"action\": \"report\", \"message\": \"<summary of current state or answer to user's question>\"\n" +
        "   Use this when the user asks a conversational question, asks for the current apogee, or wants a status update without modifying the rocket.\n\n" +
        "6. finish\n" +
        "   Required keys: \"action\": \"finish\", \"reason\": \"<explanation>\"\n" +
        "   Use this when the user's goal has been accomplished.\n\n" +
        "IMPORTANT:\n" +
        "- COMPLEX ROCKETS: Do NOT try to add all components of a 2-stage rocket in a single add_components action. First use plan_and_continue, then build it step-by-step (e.g. Stage 1 first, assign motor, then Stage 2).\n" +
        "- CRITICAL: After add_components, ALWAYS assign_motor to a BodyTube/InnerTube before running simulation. A rocket without a motor will always return 0m apogee.\n" +
        "- ERROR RULE: If you hit an error, do NOT repeat the same mistake. Read the CURRENT ROCKET COMPONENT TREE, verify the exact names of the existing components, and use ONLY those exact names.\n" +
        "- LANGUAGE RULE: You must respond, write your reasoning, and output your report message in the EXACT SAME LANGUAGE as the user's prompt (If the user speaks Turkish, you MUST think and reply in Turkish: 'Kullanıcı Türkçe konuşuyor, Türkçe yanıtlamalıyım').\n" +
        "- CRITICAL REASONING RULE: You are STRICTLY FORBIDDEN from writing long essays in your thinking process. Your reasoning MUST be extremely short (1-3 sentences maximum). DO NOT analyze rules step by step. DO NOT debate with yourself. Decide quickly and output the JSON immediately. If your thinking process exceeds 30 words, you will be penalized.\n" +
        "- ALWAYS output exactly ONE JSON action block. It must start with { and end with }.\n" +
        "- DO NOT use LaTeX formatting, markdown math (like $), or unescaped backslashes in the JSON. Keep the text simple.\n" +
        "- DO NOT output unnecessary text or reasoning outside the JSON block. Act strictly as a JSON API endpoint. Output ONLY the JSON block.\n" +
        "- /no_think : Do not write long essays. Be concise.\n\n" +
        "Example output:\n" +
        "```json\n" +
        "{\n" +
        "  \"reasoning\": \"The apogee is low because the rocket is too heavy. I will reduce the body tube length.\",\n" +
        "  \"action\": \"modify_components\",\n" +
        "  \"modifications\": [{\"component_name\": \"Body\", \"parameter\": \"length\", \"new_value\": 0.5}]\n" +
        "}\n" +
        "```";

    private final String modelName;
    private final String ollamaUrl;
    private final Gson gson;
    private List<JsonObject> messageHistory;
    private final String sessionId;

    public interface StreamCallback {
        void onChunk(String text);
    }

    public QwenAgent(String modelName, String ollamaUrl) {
        this.modelName = modelName;
        this.ollamaUrl = ollamaUrl;
        this.gson = new GsonBuilder().create();
        this.messageHistory = new ArrayList<>();
        this.sessionId = "session_" + Instant.now().getEpochSecond();
        
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", SYSTEM_PROMPT);
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

    public String sendPromptStreaming(StreamCallback callback) throws Exception {
        URL url = new URL(this.ollamaUrl + "/api/chat");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; utf-8");
        con.setRequestProperty("Accept", "application/json");
        con.setDoOutput(true);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", "qwen3.5:4b");
        
        JsonArray messagesArr = new JsonArray();
        for (JsonObject msg : this.messageHistory) {
            messagesArr.add(msg);
        }
        payload.add("messages", messagesArr);
        payload.addProperty("stream", true);
        
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", 32768);
        options.addProperty("num_predict", 8192);
        payload.add("options", options);

        try(OutputStream os = con.getOutputStream()) {
            byte[] input = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int status = con.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("Ollama API Error: HTTP " + status);
        }

        StringBuilder fullContent = new StringBuilder();
        try(BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                if (responseLine.trim().isEmpty()) continue;
                JsonObject data = JsonParser.parseString(responseLine).getAsJsonObject();
                if (data.has("message")) {
                    JsonObject msgObj = data.getAsJsonObject("message");
                    String chunk = "";
                    if (msgObj.has("thinking")) {
                        chunk += msgObj.get("thinking").getAsString();
                    }
                    if (msgObj.has("content")) {
                        chunk += msgObj.get("content").getAsString();
                    }
                    if (!chunk.isEmpty()) {
                        fullContent.append(chunk);
                        if (callback != null) {
                            callback.onChunk(chunk);
                        }
                    }
                }
            }
        }
        return fullContent.toString();
    }

    public JsonObject parseAction(String responseText) {
        Pattern pattern = Pattern.compile("```json\\s*(.*?)\\s*```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(responseText);
        String jsonStr = responseText.trim();
        
        if (matcher.find()) {
            jsonStr = matcher.group(1);
        } else {
            // If no ```json block, find the last occurrence of {"reasoning" or {"action"
            int start = jsonStr.lastIndexOf("{\"reasoning\"");
            if (start == -1) start = jsonStr.lastIndexOf("{\"action\"");
            if (start == -1) start = jsonStr.lastIndexOf("{ \"reasoning\"");
            if (start == -1) start = jsonStr.lastIndexOf("{ \"action\"");
            
            int end = jsonStr.lastIndexOf('}');
            if (start != -1 && end != -1 && start <= end) {
                jsonStr = jsonStr.substring(start, end + 1);
            } else {
                // Absolute fallback, might break if text contains {
                start = jsonStr.indexOf('{');
                if (start != -1 && end != -1 && start <= end) {
                    jsonStr = jsonStr.substring(start, end + 1);
                }
            }
        }
        
        // Clean backslashes that might break parsing
        jsonStr = jsonStr.replaceAll("\\\\(?![/\"\\\\bfnrtu])", "\\\\\\\\");

        try {
            return JsonParser.parseString(jsonStr).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject getComponentTree(RocketComponent root) {
        JsonObject result = new JsonObject();
        JsonObject params = new JsonObject();

        String[] propertiesToExtract = {"Length", "Radius", "OuterRadius", "InnerRadius", "Thickness", "Mass"};
        
        for (String prop : propertiesToExtract) {
            try {
                Method getter = root.getClass().getMethod("get" + prop);
                Object val = getter.invoke(root);
                if (val instanceof Number && !Double.isNaN(((Number) val).doubleValue())) {
                    params.addProperty(prop.toLowerCase(), ((Number) val).doubleValue());
                }
            } catch (Exception e) {
                // Ignore missing properties
            }
        }
        
        if (params.size() > 0) {
            result.add(root.getName(), params);
        } else {
            result.add(root.getName(), new JsonObject()); // empty params
        }

        JsonArray children = new JsonArray();
        for (RocketComponent child : root.getChildren()) {
            children.add(getComponentTree(child));
        }

        if (children.size() > 0) {
            result.add("children", children);
        }

        return result;
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

    public void modifyComponent(Rocket rocket, String componentName, String parameter, double newValue) throws Exception {
        RocketComponent comp = findComponentByName(rocket, componentName);
        if (comp == null) throw new Exception("Component not found: " + componentName);
        
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
        if (parent == null) throw new Exception("Parent component not found: " + parentName);

        RocketComponent newComp = null;
        switch (type) {
            case "NoseCone": newComp = new NoseCone(); break;
            case "BodyTube": newComp = new BodyTube(); break;
            case "TrapezoidFinSet": newComp = new TrapezoidFinSet(); break;
            case "InnerTube": newComp = new InnerTube(); break;
            case "EngineBlock": newComp = new EngineBlock(); break;
            case "Parachute": newComp = new Parachute(); break;
            default: throw new Exception("Unsupported component type: " + type);
        }

        if (name != null && !name.isEmpty()) {
            newComp.setName(name);
        }
        
        parent.addChild(newComp);
        
        if (newComp instanceof info.openrocket.core.rocketcomponent.FinSet) {
            newComp.setAxialMethod(info.openrocket.core.rocketcomponent.position.AxialMethod.BOTTOM);
            newComp.setAxialOffset(0.0);
        }
    }
    
    public void assignMotor(Rocket rocket, String componentName, String motorDesignation) throws Exception {
        RocketComponent comp = findComponentByName(rocket, componentName);
        if (comp == null) throw new Exception("Component not found: " + componentName);
        if (!(comp instanceof MotorMount)) {
            throw new Exception("Component is not a motor mount. Use a BodyTube or InnerTube.");
        }
        
        String baseDesignation = motorDesignation;
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
        
        List<? extends Motor> motors = Application.getMotorSetDatabase().findMotors(null, null, null, baseDesignation, Double.NaN, Double.NaN);
        if (motors.isEmpty()) {
            throw new Exception("Motor not found in database: " + baseDesignation);
        }
        
        Motor motor = motors.get(0); // Pick the first match
        MotorMount mount = (MotorMount) comp;
        mount.setMotorMount(true);
        info.openrocket.core.rocketcomponent.FlightConfigurationId fcid = info.openrocket.core.rocketcomponent.FlightConfigurationId.DEFAULT_VALUE_FCID;
        MotorConfiguration newConfig = new MotorConfiguration(mount, fcid);
        newConfig.setMotor(motor);
        if (delay > 0) {
            newConfig.setEjectionDelay(delay);
        }
        mount.setMotorConfig(newConfig, fcid);
        
        // Let OpenRocket's internal update mechanisms handle the flight configuration
        info.openrocket.core.rocketcomponent.FlightConfiguration config = rocket.getFlightConfiguration(fcid);
        config.update(); // Re-evaluates active motors
        
        // Notify the UI that motors have changed for this configuration
        rocket.fireComponentChangeEvent(info.openrocket.core.rocketcomponent.ComponentChangeEvent.MOTOR_CHANGE, fcid);
    }
    
    public void logSession(String userGoal, String reasoning, JsonElement orkChanges, JsonObject simResult) {
        String homeDir = System.getProperty("user.home");
        Path sessionDir = Paths.get(homeDir, "qwenrocket-sessions");
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
