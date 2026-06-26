package info.openrocket.swing.gui.components;

import info.openrocket.core.ai.QwenAgent;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.SimulationEngine;
import info.openrocket.core.simulation.BasicEventSimulationEngine;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.swing.gui.main.BasicFrame;
import info.openrocket.core.startup.Application;
import info.openrocket.core.motor.Motor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.Desktop;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class QwenAssistantPanel extends JPanel {

    private JTextArea chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private JButton saveButton;
    private JButton debugMotorsButton;
    private BasicFrame basicFrame;
    private OpenRocketDocument document;
    private QwenAgent agent;
    private boolean isThinking = false;

    public QwenAssistantPanel(BasicFrame parent, OpenRocketDocument document) {
        super();
        this.basicFrame = parent;
        this.document = document;
        
        // Use a generic model name and URL, can be made configurable later
        this.agent = new QwenAgent("qwen3.5:4b", "http://localhost:11434");
        this.agent.loadHistory(document.getQwenChatHistory());

        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout());

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        
        // Auto-scroll to bottom
        DefaultCaret caret = (DefaultCaret) chatArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(chatArea);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputField.addActionListener(e -> sendMessage());
        
        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendMessage());

        JPanel buttonPanel = new JPanel(new FlowLayout());
        
        debugMotorsButton = new JButton("Debug: List Motors");
        debugMotorsButton.addActionListener(e -> {
            try {
                List<? extends Motor> allMotors = Application.getMotorSetDatabase().findMotors(null, null, null, null, Double.NaN, Double.NaN);
                StringBuilder sb = new StringBuilder("All Motor Designations (" + allMotors.size() + " total):\n");
                for (Motor m : allMotors) {
                    sb.append(m.getDesignation()).append(", ");
                }
                appendChat("System", sb.toString());
            } catch (Exception ex) {
                appendChat("Error", "Could not fetch motors: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        saveButton = new JButton("Export Session");
        saveButton.addActionListener(e -> {
            try {
                File dir = Paths.get(System.getProperty("user.home"), "qwenrocket-sessions").toFile();
                if (!dir.exists()) dir.mkdirs();
                
                File exportFile = new File(dir, "exported_session_" + System.currentTimeMillis() + ".jsonl");
                try (PrintWriter out = new PrintWriter(new FileWriter(exportFile))) {
                    JsonArray history = document.getQwenChatHistory();
                    if (history != null) {
                        for (JsonElement el : history) {
                            out.println(el.getAsJsonObject().toString());
                        }
                    }
                }
                JOptionPane.showMessageDialog(this, "Session exported to: " + exportFile.getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error exporting session: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        buttonPanel.add(debugMotorsButton);
        buttonPanel.add(saveButton);
        buttonPanel.add(sendButton);

        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
        
        // Render initial history if present, otherwise show welcome message
        if (document.getQwenChatHistory() != null && document.getQwenChatHistory().size() > 1) {
            for (JsonElement el : document.getQwenChatHistory()) {
                JsonObject msg = el.getAsJsonObject();
                if (msg.has("role") && msg.has("content") && !"system".equals(msg.get("role").getAsString())) {
                    appendChat(msg.get("role").getAsString(), msg.get("content").getAsString());
                }
            }
        } else {
            appendChat("System", "Qwen AI Assistant initialized.\nModel: qwen3.5:4b\nReady for commands! (e.g. 'Increase the apogee to 55m')\n");
        }
    }

    private void appendChat(String role, String text) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append("[" + role + "] " + text + "\n");
        });
    }
    
    private void appendStream(String text) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(text);
        });
    }

    private void sendMessage() {
        if (isThinking) return;
        String userGoal = inputField.getText().trim();
        if (userGoal.isEmpty()) return;

        inputField.setText("");
        appendChat("User", userGoal);
        
        isThinking = true;
        sendButton.setEnabled(false);
        
        // Run AI loop in background thread
        new Thread(() -> {
            try {
                runAiLoop(userGoal);
            } catch (Exception e) {
                appendChat("Error", e.getMessage());
                e.printStackTrace();
            } finally {
                // Sync the session history into the OpenRocketDocument so it gets saved
                document.setQwenChatHistory(agent.getHistoryAsJsonArray());
                
                SwingUtilities.invokeLater(() -> {
                    isThinking = false;
                    sendButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void runAiLoop(String userGoal) throws Exception {
        Rocket rocket = document.getRocket();
        
        int maxIterations = 25;
        String retryPrefix = "";
        
        for (int i = 0; i < maxIterations; i++) {
            appendChat("System", "--- Iteration " + (i + 1) + "/" + maxIterations + " ---");
            
            // 1. Get current component tree
            JsonObject tree = agent.getComponentTree(rocket);
            
            // 2. Run simulation to get current state
            JsonObject results = runSimulation();
            
            // 3. Build state message
            String stateMsg = retryPrefix + 
                              "USER GOAL: " + userGoal + "\n\n" +
                              "CURRENT ROCKET COMPONENT TREE:\n" + tree.toString() + "\n\n" +
                              "LATEST SIMULATION RESULTS:\n" + results.toString() + "\n\n" +
                              "What is your next action?";
            
            // Reset retry prefix after using it
            retryPrefix = "";
                              
            agent.addUserMessage(stateMsg);
            
            appendChat("Qwen", ""); // Start of stream
            
            // 4. Send to Ollama
            String response = agent.sendPromptStreaming(chunk -> appendStream(chunk));
            appendStream("\n\n");
            
            if (response == null || response.isEmpty()) {
                appendChat("Error", "Failed to get response from Qwen.");
                break;
            }
            
            agent.addAssistantMessage(response);
            
            // 5. Parse action
            JsonObject action = agent.parseAction(response);
            if (action == null) {
                appendChat("System", "Could not parse action. Asking Qwen to try again.");
                // Remove the failed assistant response AND the user prompt we just added
                // This prevents the context from exploding with repeated failed attempts!
                agent.removeLastAssistantMessage();
                agent.removeLastUserMessage();
                
                retryPrefix = "CRITICAL ERROR: Your last response was invalid or cut off. You MUST output ONLY a valid JSON block starting with { and ending with }.\n\n";
                continue;
            }
            
            String reasoning = action.has("reasoning") ? action.get("reasoning").getAsString() : "No reasoning provided.";
            
            // Handle nested action
            JsonElement cmdElem = action.get("action");
            if (cmdElem != null && cmdElem.isJsonObject()) {
                JsonObject nested = cmdElem.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : nested.entrySet()) {
                    if (!"action".equals(entry.getKey())) {
                        action.add(entry.getKey(), entry.getValue());
                    }
                }
                
                if (nested.has("type")) {
                    action.addProperty("action", nested.get("type").getAsString());
                } else if (nested.has("action")) {
                    action.addProperty("action", nested.get("action").getAsString());
                } else if (nested.has("command")) {
                    action.addProperty("action", nested.get("command").getAsString());
                } else {
                    action.remove("action");
                }
            }
            
            String cmd = action.has("action") && !action.get("action").isJsonNull() ? action.get("action").getAsString() : "";
            
            JsonElement orkChanges = null;
            
            if ("modify_components".equals(cmd)) {
                JsonArray modifications = action.getAsJsonArray("modifications");
                if (modifications != null) {
                    orkChanges = modifications;
                    for (JsonElement modElem : modifications) {
                        JsonObject mod = modElem.getAsJsonObject();
                        String comp = mod.get("component_name").getAsString();
                        String param = mod.get("parameter").getAsString();
                        double val = mod.get("new_value").getAsDouble();
                        
                        appendChat("System", "Executing: modify_component(" + comp + ", " + param + ", " + val + ")");
                        
                        // Apply modification to GUI
                        SwingUtilities.invokeAndWait(() -> {
                            try {
                                agent.modifyComponent(rocket, comp, param, val);
                                // The GUI listens to component change events automatically
                            } catch (Exception e) {
                                appendChat("Error", "Modify failed: " + e.getMessage());
                            }
                        });
                    }
                    
                    // Run simulation again after modifications to log the new apogee
                    JsonObject newResults = runSimulation();
                    appendChat("System", "Resulting Apogee: " + String.format("%.2f", newResults.get("apogee_meters").getAsDouble()) + " m");
                    
                    agent.logSession(userGoal, reasoning, orkChanges, newResults);
                }
            } else if ("add_components".equals(cmd)) {
                if (action.has("components")) {
                    JsonArray components = action.getAsJsonArray("components");
                    orkChanges = components;
                    for (JsonElement compElement : components) {
                        JsonObject comp = compElement.getAsJsonObject();
                        String type = comp.get("type").getAsString();
                        String parent = comp.get("parent").getAsString();
                        String name = comp.has("name") ? comp.get("name").getAsString() : null;
                        
                        appendChat("System", "Executing: addComponent(" + parent + ", " + type + ", " + (name != null ? name : "") + ")");
                        SwingUtilities.invokeAndWait(() -> {
                            try {
                                agent.addComponent(rocket, parent, type, name);
                            } catch (Exception e) {
                                appendChat("Error", "Add failed: " + e.getMessage());
                            }
                        });
                    }
                    
                    // Add undo position and simulate
                    SwingUtilities.invokeAndWait(() -> document.addUndoPosition("AI Assistant added components"));
                    JsonObject newResults = runSimulation();
                    
                    double apogee = newResults.get("apogee_meters").getAsDouble();
                    String apogeeText = String.format("%.2f m", apogee);
                    appendChat("System", "Resulting Apogee: " + apogeeText);
                    
                    // Feed result back
                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("Simulation complete. New apogee: " + apogeeText + ". Component tree: " + agent.getComponentTree(rocket).toString() + ". What is your next action?");
                    
                    agent.logSession(userGoal, reasoning, orkChanges, newResults);
                }
            } else if ("assign_motor".equals(cmd)) {
                String compName = action.get("component_name").getAsString();
                String motorName = action.get("motor").getAsString();
                
                appendChat("System", "Executing: assignMotor(" + compName + ", " + motorName + ")");
                
                final java.util.concurrent.atomic.AtomicReference<String> errorRef = new java.util.concurrent.atomic.AtomicReference<>(null);
                
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        agent.assignMotor(rocket, compName, motorName);
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        appendChat("Error", "Assign failed: " + msg);
                        errorRef.set(msg);
                    }
                });
                
                if (errorRef.get() != null) {
                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("ACTION FAILED: " + errorRef.get() + ". Fix the error and try a different approach.");
                } else {
                    SwingUtilities.invokeAndWait(() -> document.addUndoPosition("AI Assistant assigned motor"));
                    JsonObject newResults = runSimulation();
                    
                    double apogee = newResults.get("apogee_meters").getAsDouble();
                    String apogeeText = String.format("%.2f m", apogee);
                    appendChat("System", "Resulting Apogee: " + apogeeText);
                    
                    // Feed result back
                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("Simulation complete. New apogee: " + apogeeText + ". Component tree: " + agent.getComponentTree(rocket).toString() + ". What is your next action?");
                    
                    JsonObject motorChange = new JsonObject();
                    motorChange.addProperty("type", "motor_assignment");
                    motorChange.addProperty("component", compName);
                    motorChange.addProperty("motor", motorName);
                    
                    agent.logSession(userGoal, reasoning, motorChange, newResults);
                }
            } else if ("plan_and_continue".equals(cmd)) {
                String msg = action.has("message") ? action.get("message").getAsString() : "No plan provided.";
                appendChat("System", "Qwen Plan: " + msg);
                agent.logSession(userGoal, reasoning, null, results);
                
                agent.addAssistantMessage(action.toString());
                agent.addUserMessage("Plan acknowledged. Please execute the FIRST step of your plan now.");
                continue;
            } else if ("report".equals(cmd)) {
                String msg = action.has("message") ? action.get("message").getAsString() : "No message provided.";
                appendChat("System", "Qwen reported: " + msg);
                agent.logSession(userGoal, reasoning, null, results);
                break;
            } else if ("finish".equals(cmd)) {
                String reason = action.has("reason") ? action.get("reason").getAsString() : "";
                appendChat("System", "Goal achieved! Reason: " + reason);
                agent.logSession(userGoal, reasoning, null, results);
                break;
            } else {
                appendChat("System", "Unknown action: " + cmd);
                agent.addUserMessage("Unknown action: " + cmd + ". Available actions: modify_components, add_components, assign_motor, plan_and_continue, report, finish.");
                continue;
            }
        }
    }
    
    private JsonObject runSimulation() {
        JsonObject res = new JsonObject();
        try {
            if (document.getSimulations().isEmpty()) {
                Simulation sim = new Simulation(document.getRocket());
                sim.setName("Qwen Simulation");
                document.addSimulation(sim);
            }
            
            Simulation sim = document.getSimulations().get(0);
            
            // CRITICAL: Ensure the simulation uses the default configuration where the motor was assigned!
            sim.setFlightConfigurationId(
                info.openrocket.core.rocketcomponent.FlightConfigurationId.DEFAULT_VALUE_FCID
            );
            
            sim.simulate();
            FlightData data = sim.getSimulatedData();
            
            double apogee = data.getMaxAltitude();
            double velocity = data.getMaxVelocity();
            double timeToApogee = data.getTimeToApogee();
            
            res.addProperty("apogee_meters", apogee);
            res.addProperty("max_velocity_ms", velocity);
            res.addProperty("time_to_apogee_s", timeToApogee);
            
            // Collect warnings to diagnose 0.0m apogee issues
            if (data.getWarningSet() != null && !data.getWarningSet().isEmpty()) {
                JsonArray warnings = new JsonArray();
                for (info.openrocket.core.logging.Warning w : data.getWarningSet()) {
                    warnings.add(w.toString());
                }
                res.add("warnings", warnings);
            }
            
            // Also collect events
            JsonArray events = new JsonArray();
            if (data.getBranchCount() > 0) {
                for (info.openrocket.core.simulation.FlightEvent event : data.getBranch(0).getEvents()) {
                    events.add(event.getType().toString() + " at " + String.format("%.2f", event.getTime()) + "s");
                }
            }
            res.add("events", events);
            
        } catch (SimulationException e) {
            res.addProperty("error", e.getMessage());
        }
        return res;
    }
}
