package info.openrocket.swing.gui.components;

import info.openrocket.core.ai.QwenAgent;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.MotorMount;
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
import java.util.Locale;
import java.util.Map;

public class QwenAssistantPanel extends JPanel {

    private QwenAgent agent;
    private JTextArea chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private JButton debugMotorsButton;
    private OpenRocketDocument document;
    private JToggleButton thinkingToggle;
    private JTextArea thinkingArea;
    private JScrollPane thinkingScrollPane;
    private JLabel statusLabel;
    private JButton saveButton;
    private BasicFrame basicFrame;
    private boolean isThinking = false;
    
    private String ollamaUrl = "http://localhost:11434";
    private int maxIterations = 10;

    public QwenAssistantPanel(BasicFrame parent, OpenRocketDocument document) {
        super();
        this.basicFrame = parent;
        this.document = document;
        
        // The agent initially has no model, we will set it from the combobox
        this.agent = new QwenAgent("", ollamaUrl);
        this.agent.loadHistory(document.getQwenChatHistory());

        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Model:"));
        JComboBox<String> modelSelector = new JComboBox<>();
        
        JButton settingsButton = new JButton("\u2699 Settings");
        settingsButton.addActionListener(e -> showSettingsDialog());
        
        List<String> availableModels = QwenAgent.getAvailableModels(ollamaUrl);
        if (availableModels.isEmpty()) {
            modelSelector.addItem("gemma4:e4b"); // fallback
        } else {
            for (String m : availableModels) {
                modelSelector.addItem(m);
            }
            // Set a gemma model as default if it exists
            for (String m : availableModels) {
                if (m.toLowerCase().contains("gemma")) {
                    modelSelector.setSelectedItem(m);
                    break;
                }
            }
        }
        
        agent.setModelName((String) modelSelector.getSelectedItem());

        modelSelector.addActionListener(e -> {
            String selectedModel = (String) modelSelector.getSelectedItem();
            if (selectedModel != null) {
                agent.setModelName(selectedModel);
                appendChat("System", "Model changed to: " + selectedModel);
            }
        });
        
        topPanel.add(modelSelector);
        topPanel.add(settingsButton);
        add(topPanel, BorderLayout.NORTH);

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        chatArea.setMargin(new Insets(10, 10, 10, 10));
        
        // Auto-scroll to bottom
        DefaultCaret caret = (DefaultCaret) chatArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(chatArea);
        add(scrollPane, BorderLayout.CENTER);

        thinkingArea = new JTextArea();
        thinkingArea.setEditable(false);
        thinkingArea.setLineWrap(true);
        thinkingArea.setWrapStyleWord(true);
        thinkingArea.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        thinkingArea.setMargin(new Insets(8, 8, 8, 8));
        thinkingArea.setForeground(new Color(120, 120, 120));
        thinkingScrollPane = new JScrollPane(thinkingArea);
        thinkingScrollPane.setPreferredSize(new Dimension(0, 120));
        thinkingScrollPane.setVisible(false);

        thinkingToggle = new JToggleButton("▶ Show thinking");
        thinkingToggle.setHorizontalAlignment(SwingConstants.LEFT);
        thinkingToggle.setFocusPainted(false);
        thinkingToggle.setVisible(false);
        thinkingToggle.addActionListener(e -> {
            boolean show = thinkingToggle.isSelected();
            thinkingScrollPane.setVisible(show);
            updateThinkingToggleLabel();
            revalidate();
            repaint();
        });

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        statusLabel.setForeground(new Color(100, 100, 100));

        JPanel thinkingPanel = new JPanel(new BorderLayout(0, 0));
        thinkingPanel.add(thinkingToggle, BorderLayout.NORTH);
        thinkingPanel.add(thinkingScrollPane, BorderLayout.CENTER);

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

        bottomPanel.add(statusLabel, BorderLayout.NORTH);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(thinkingPanel, BorderLayout.NORTH);
        southPanel.add(bottomPanel, BorderLayout.SOUTH);
        add(southPanel, BorderLayout.SOUTH);
        
        // Render initial history if present, otherwise show welcome message
        if (document.getQwenChatHistory() != null) {
            for (JsonElement el : document.getQwenChatHistory()) {
                JsonObject msg = el.getAsJsonObject();
                if (msg.has("role") && msg.has("content") && !"system".equals(msg.get("role").getAsString())) {
                    appendChat(msg.get("role").getAsString(), msg.get("content").getAsString());
                }
            }
        }
        if (document.getQwenChatHistory() == null || document.getQwenChatHistory().size() <= 1) {
            appendChat("System", "LlamaRocket AI initialized.\nReady for commands! (e.g. 'Increase the apogee to 55m')\n");
        }
    }
    
    private void showSettingsDialog() {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "LlamaRocket Settings", true);
        dialog.setLayout(new BorderLayout());
        
        JPanel formPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        JTextField urlField = new JTextField(this.ollamaUrl);
        JTextField iterField = new JTextField(String.valueOf(this.maxIterations));
        
        formPanel.add(new JLabel("Ollama URL:"));
        formPanel.add(urlField);
        formPanel.add(new JLabel("Max Iterations:"));
        formPanel.add(iterField);
        
        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> {
            this.ollamaUrl = urlField.getText();
            try {
                this.maxIterations = Integer.parseInt(iterField.getText());
            } catch (NumberFormatException ignored) {}
            
            // Re-fetch models
            JComboBox<String> selector = (JComboBox<String>) ((JPanel) getComponent(0)).getComponent(1); // very brittle, better to reload
            dialog.dispose();
        });
        
        dialog.add(formPanel, BorderLayout.CENTER);
        dialog.add(saveBtn, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void appendChat(String role, String text) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append("[" + role + "] " + text + "\n");
        });
    }

    private String jsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r", " ").replace("\n", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    private double jsonDouble(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return Double.NaN;
        }
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private String buildDirectReportMessage(JsonObject results) {
        if (results == null) {
            return "Mevcut roket için simülasyon sonucu alınamadı.";
        }

        String error = jsonString(results, "error");
        if (error != null && !error.isEmpty()) {
            return "Mevcut roket için simülasyon tamamlanamadı: " + error;
        }

        double apogee = jsonDouble(results, "apogee_meters");
        double timeToApogee = jsonDouble(results, "time_to_apogee_s");
        double totalFlightTime = jsonDouble(results, "total_flight_time_s");
        double maxVelocity = jsonDouble(results, "max_velocity_ms");

        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(totalFlightTime) && totalFlightTime > 0) {
            sb.append("Mevcut simülasyona göre toplam uçuş süresi yaklaşık ")
              .append(String.format("%.2f", totalFlightTime))
              .append(" sn.");
        } else if (!Double.isNaN(timeToApogee) && timeToApogee > 0) {
            sb.append("Mevcut simülasyonda apojeye ulaşma süresi yaklaşık ")
              .append(String.format("%.2f", timeToApogee))
              .append(" sn.");
        } else {
            sb.append("Mevcut roket için anlamlı bir uçuş süresi üretilemedi.");
        }

        if (!Double.isNaN(apogee)) {
            sb.append(" Apogee: ").append(String.format("%.2f", apogee)).append(" m.");
        }
        if (!Double.isNaN(timeToApogee) && timeToApogee > 0) {
            sb.append(" Apojeye varış: ").append(String.format("%.2f", timeToApogee)).append(" sn.");
        }
        if (!Double.isNaN(maxVelocity) && maxVelocity > 0) {
            sb.append(" Maksimum hız: ").append(String.format("%.2f", maxVelocity)).append(" m/s.");
        }
        if (results.has("warnings") && results.get("warnings").isJsonArray() && results.getAsJsonArray("warnings").size() > 0) {
            sb.append(" Uyarı: ").append(results.getAsJsonArray("warnings").get(0).getAsString());
        }
        return sb.toString();
    }

    private void appendMotorStatus(RocketComponent component, StringBuilder sb, int depth) {
        if (component instanceof MotorMount) {
            MotorMount mount = (MotorMount) component;
            String motorText = "none";
            try {
                info.openrocket.core.rocketcomponent.FlightConfigurationId fcid =
                        info.openrocket.core.rocketcomponent.FlightConfigurationId.DEFAULT_VALUE_FCID;
                info.openrocket.core.motor.MotorConfiguration config = mount.getMotorConfig(fcid);
                if (config != null && config.getMotor() != null) {
                    motorText = config.getMotor().getDesignation();
                }
            } catch (Exception e) {
                motorText = "unknown";
            }
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append("- ").append(component.getName())
              .append(" [").append(component.getClass().getSimpleName()).append("] motor=")
              .append(motorText).append("\n");
        }
        for (RocketComponent child : component.getChildren()) {
            appendMotorStatus(child, sb, depth + 1);
        }
    }

    private String getMotorStatus(Rocket rocket) {
        StringBuilder sb = new StringBuilder();
        appendMotorStatus(rocket, sb, 0);
        String result = sb.toString().trim();
        return result.isEmpty() ? "- no motor mounts found" : result;
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text != null ? text : " "));
    }

    private void resetThinkingPanel() {
        SwingUtilities.invokeLater(() -> {
            thinkingArea.setText("");
            thinkingToggle.setSelected(false);
            thinkingScrollPane.setVisible(false);
            thinkingToggle.setVisible(false);
            thinkingToggle.setText("▶ Show thinking");
        });
    }

    private void appendThinkingStream(String text) {
        SwingUtilities.invokeLater(() -> {
            if (thinkingToggle.isSelected()) {
                thinkingArea.append(text);
            }
        });
    }

    private void setThinkingContent(String thinking) {
        SwingUtilities.invokeLater(() -> {
            if (thinking == null || thinking.trim().isEmpty()) {
                thinkingToggle.setVisible(false);
                thinkingScrollPane.setVisible(false);
                return;
            }
            thinkingArea.setText(thinking.trim());
            thinkingToggle.setVisible(true);
            thinkingToggle.setSelected(false);
            thinkingScrollPane.setVisible(false);
            updateThinkingToggleLabel();
        });
    }

    private void updateThinkingToggleLabel() {
        int words = countWords(thinkingArea.getText());
        if (thinkingToggle.isSelected()) {
            thinkingToggle.setText("▼ Hide thinking (" + words + " words)");
        } else {
            thinkingToggle.setText("▶ Show thinking (" + words + " words)");
        }
    }

    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private JsonObject normalizeAction(JsonObject action) {
        if (action == null) {
            return null;
        }
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
        return action;
    }

    private String formatActionSummary(JsonObject action) {
        if (action == null) {
            return "No action parsed.";
        }
        String cmd = jsonString(action, "action");
        if (cmd == null) {
            cmd = "unknown";
        }
        StringBuilder sb = new StringBuilder(cmd);

        String reasoning = jsonString(action, "reasoning");
        if (reasoning != null && !reasoning.isEmpty()) {
            sb.append(" — ").append(reasoning);
        }

        switch (cmd) {
            case "add_components":
                if (action.has("components") && action.get("components").isJsonArray()) {
                    for (JsonElement el : action.getAsJsonArray("components")) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject c = el.getAsJsonObject();
                        String type = jsonString(c, "type");
                        String parent = jsonString(c, "parent");
                        String name = jsonString(c, "name");
                        sb.append("\n  + ").append(type != null ? type : "?")
                          .append(" → ").append(parent != null ? parent : "?");
                        if (name != null) {
                            sb.append(" (").append(name).append(")");
                        }
                    }
                }
                break;
            case "modify_components":
                if (action.has("modifications") && action.get("modifications").isJsonArray()) {
                    for (JsonElement el : action.getAsJsonArray("modifications")) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject m = el.getAsJsonObject();
                        sb.append("\n  ~ ").append(jsonString(m, "component_name"))
                          .append(".").append(jsonString(m, "parameter"))
                          .append(" = ").append(m.has("new_value") && !m.get("new_value").isJsonNull()
                                  ? m.get("new_value").getAsString() : "?");
                    }
                }
                break;
            case "delete_component":
                sb.append(" → ").append(jsonString(action, "component_name"));
                break;
            case "assign_motor":
                sb.append(" → ").append(jsonString(action, "motor"))
                  .append(" on ").append(jsonString(action, "component_name"));
                break;
            case "plan_and_continue":
            case "report":
                String msg = jsonString(action, "message");
                if (msg != null) {
                    sb.append("\n  ").append(msg.replace("\n", "\n  "));
                }
                break;
            case "finish":
                String reason = jsonString(action, "reason");
                if (reason != null) {
                    sb.append(" — ").append(reason);
                }
                break;
            default:
                break;
        }
        return sb.toString();
    }

    private void sendMessage() {
        if (isThinking) return;
        String userGoal = inputField.getText().trim();
        if (userGoal.isEmpty()) return;

        inputField.setText("");
        appendChat("User", userGoal);
        
        isThinking = true;
        
        new Thread(() -> {
            try {
                // Ensure UI is updated before doing heavy work
                SwingUtilities.invokeLater(() -> {
                    sendButton.setEnabled(false);
                    inputField.setEnabled(false);
                });
                
                Rocket rocket = document.getRocket();
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
                              "EXACT COMPONENT NAMES (use ONLY these — never invent Stage1/Stage2):\n" +
                              agent.getComponentNameList(rocket) + "\n\n" +
                              "CURRENT MOTOR STATUS:\n" +
                              getMotorStatus(rocket) + "\n\n" +
                              "CURRENT ROCKET COMPONENT TREE:\n" + tree.toString() + "\n\n" +
                              "LATEST SIMULATION RESULTS:\n" + results.toString() + "\n\n" +
                              "What is your next action?";
            
            // Reset retry prefix after using it
            retryPrefix = "";
                              
            agent.addUserMessage(stateMsg);
            
            resetThinkingPanel();
            setStatus("LlamaRocket is thinking...");
            QwenAgent.AgentStreamResult streamResult = agent.sendPromptStreaming(new QwenAgent.StreamCallback() {
                @Override
                public void onThinkingChunk(String text) {
                    appendThinkingStream(text);
                }

                @Override
                public void onContentChunk(String text) {
                    // Content is shown as a clean summary after parsing.
                }
            });

            setStatus("");

            if (streamResult.getCombined().trim().isEmpty()) {
                appendChat("Error", "Failed to get response from Qwen.");
                break;
            }

            setThinkingContent(streamResult.getThinking());
            // 5. Parse action
            JsonObject action = normalizeAction(agent.parseAction(streamResult));
            if (action == null) {
                appendChat("System", "Could not parse action. Asking Qwen to try again.");
                appendChat("System", "Raw model output: " + abbreviate(streamResult.getCombined(), 240));

                // Remove the user prompt we just added so retries do not bloat the context.
                agent.removeLastUserMessage();
                
                retryPrefix = "CRITICAL ERROR: Your last response was invalid or cut off. Keep your 'reasoning' to 1 short sentence. Output ONLY one valid JSON object with required fields. No prose, no markdown fences.\n\n";
                continue;
            }

            String cmd = jsonString(action, "action");
            if (cmd == null) {
                cmd = "";
            }
            appendChat("Qwen", formatActionSummary(action));

            if (cmd.trim().isEmpty()) {
                appendChat("System", "ACTION FAILED: missing required field \"action\". Raw output: " + abbreviate(streamResult.getCombined(), 300));
                agent.addAssistantMessage(action.toString());
                agent.addUserMessage("ACTION FAILED: missing required field \"action\". Available actions: modify_components, add_components, delete_component, assign_motor, plan_and_continue, report, finish.");
                continue;
            }
            
            String reasoning = jsonString(action, "reasoning");
            if (reasoning == null) {
                reasoning = "No reasoning provided.";
            }
            
            JsonElement orkChanges = null;
            
            try {
            if ("modify_components".equals(cmd)) {
                JsonArray modifications = action.getAsJsonArray("modifications");
                if (modifications != null) {
                    orkChanges = modifications;
                    java.util.List<String> errors = new java.util.ArrayList<>();
                    int applied = 0;

                    for (JsonElement modElem : modifications) {
                        JsonObject mod = modElem.getAsJsonObject();
                        if (!mod.has("component_name") || !mod.has("parameter") || !mod.has("new_value")) {
                            errors.add("Invalid modification entry (needs component_name, parameter, new_value only). To create parts use add_components, not modify_components.");
                            continue;
                        }
                        if (mod.has("type") || mod.has("parent")) {
                            errors.add("Modification entry contains add_components fields (type/parent). Use action add_components instead.");
                            continue;
                        }

                        String comp = mod.get("component_name").getAsString();
                        String param = mod.get("parameter").getAsString();
                        double val = mod.get("new_value").getAsDouble();

                        appendChat("System", "Executing: modify_component(" + comp + ", " + param + ", " + val + ")");

                        final java.util.concurrent.atomic.AtomicReference<String> errorRef = new java.util.concurrent.atomic.AtomicReference<>(null);
                        SwingUtilities.invokeAndWait(() -> {
                            try {
                                agent.modifyComponent(rocket, comp, param, val);
                            } catch (Exception e) {
                                String msg = e.getMessage();
                                appendChat("Error", "Modify failed: " + msg);
                                errorRef.set(msg);
                            }
                        });

                        if (errorRef.get() != null) {
                            errors.add(comp + ": " + errorRef.get());
                        } else {
                            applied++;
                        }
                    }

                    if (!errors.isEmpty()) {
                        agent.addAssistantMessage(action.toString());
                        agent.addUserMessage("ACTION FAILED: " + String.join(" | ", errors) + ". Fix the error and try a different approach.");
                        continue;
                    }

                    if (applied > 0) {
                        SwingUtilities.invokeAndWait(() -> document.addUndoPosition("AI Assistant modified components"));
                    }

                    JsonObject newResults = runSimulation();
                    appendChat("System", "Resulting Apogee: " + String.format("%.2f", newResults.get("apogee_meters").getAsDouble()) + " m");

                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("Modifications applied. Component tree: " + agent.getComponentTree(rocket).toString() + ". What is your next action?");

                    agent.logSession(userGoal, reasoning, orkChanges, newResults);
                    continue;
                }
            } else if ("add_components".equals(cmd)) {
                if (action.has("components") && action.get("components").isJsonArray()) {
                    JsonArray components = action.getAsJsonArray("components");
                    orkChanges = components;
                    java.util.List<String> errors = new java.util.ArrayList<>();
                    int applied = 0;

                    for (JsonElement compElement : components) {
                        if (!compElement.isJsonObject()) {
                            errors.add("Invalid component entry (must be a JSON object with type and parent).");
                            continue;
                        }
                        JsonObject comp = compElement.getAsJsonObject();
                        String type = jsonString(comp, "type");
                        String parent = jsonString(comp, "parent");
                        String name = jsonString(comp, "name");

                        if (type == null || parent == null) {
                            errors.add("Each component needs \"type\" and \"parent\" fields.");
                            continue;
                        }
                        if (!agent.isSupportedComponentType(type)) {
                            errors.add("Unsupported type \"" + type + "\". Supported: AxialStage, NoseCone, BodyTube, TrapezoidFinSet, InnerTube, EngineBlock, Parachute, ShockCord.");
                            continue;
                        }

                        appendChat("System", "Executing: addComponent(" + parent + ", " + type + ", " + (name != null ? name : "") + ")");

                        final java.util.concurrent.atomic.AtomicReference<String> errorRef = new java.util.concurrent.atomic.AtomicReference<>(null);
                        SwingUtilities.invokeAndWait(() -> {
                            try {
                                agent.addComponent(rocket, parent, type, name);
                            } catch (Exception e) {
                                String msg = e.getMessage();
                                appendChat("Error", "Add failed: " + msg);
                                errorRef.set(msg);
                            }
                        });

                        if (errorRef.get() != null) {
                            errors.add(type + " → " + parent + ": " + errorRef.get());
                        } else {
                            applied++;
                        }
                    }

                    if (!errors.isEmpty()) {
                        agent.addAssistantMessage(action.toString());
                        agent.addUserMessage("ACTION FAILED: " + String.join(" | ", errors) + ". Read EXACT COMPONENT NAMES and parent rules. Fix and retry.");
                        continue;
                    }

                    if (applied > 0) {
                        SwingUtilities.invokeAndWait(() -> document.addUndoPosition("AI Assistant added components"));
                    }
                    JsonObject newResults = runSimulation();
                    
                    double apogee = newResults.get("apogee_meters").getAsDouble();
                    String apogeeText = String.format("%.2f m", apogee);
                    appendChat("System", "Resulting Apogee: " + apogeeText);
                    
                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("Simulation complete. New apogee: " + apogeeText + ". Component tree: " + agent.getComponentTree(rocket).toString() + ". What is your next action?");
                    
                    agent.logSession(userGoal, reasoning, orkChanges, newResults);
                    continue;
                } else {
                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("ACTION FAILED: add_components requires a \"components\" array. Each item needs type, parent, and optional name.");
                    continue;
                }
            } else if ("delete_component".equals(cmd)) {
                String compName = jsonString(action, "component_name");
                if (compName == null) {
                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("ACTION FAILED: delete_component requires \"component_name\".");
                    continue;
                }

                appendChat("System", "Executing: deleteComponent(" + compName + ")");

                final java.util.concurrent.atomic.AtomicReference<String> errorRef = new java.util.concurrent.atomic.AtomicReference<>(null);

                SwingUtilities.invokeAndWait(() -> {
                    try {
                        agent.deleteComponent(rocket, compName);
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        appendChat("Error", "Delete failed: " + msg);
                        errorRef.set(msg);
                    }
                });

                if (errorRef.get() != null) {
                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("ACTION FAILED: " + errorRef.get() + ". Fix the error and try a different approach.");
                } else {
                    SwingUtilities.invokeAndWait(() -> document.addUndoPosition("AI Assistant deleted component"));
                    JsonObject newResults = runSimulation();

                    appendChat("System", "Component deleted. Updated tree: " + agent.getComponentTree(rocket).toString());

                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("Component deleted. Component tree: " + agent.getComponentTree(rocket).toString() + ". What is your next action?");

                    JsonObject deleteChange = new JsonObject();
                    deleteChange.addProperty("type", "delete_component");
                    deleteChange.addProperty("component", compName);

                    agent.logSession(userGoal, reasoning, deleteChange, newResults);
                }
            } else if ("assign_motor".equals(cmd)) {
                String compName = jsonString(action, "component_name");
                String motorName = jsonString(action, "motor");
                if (compName == null || motorName == null) {
                    agent.addAssistantMessage(action.toString());
                    agent.addUserMessage("ACTION FAILED: assign_motor requires \"component_name\" and \"motor\".");
                    continue;
                }
                
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
                String msg = jsonString(action, "message");
                if (msg == null) {
                    msg = "No plan provided.";
                }
                appendChat("System", "Qwen Plan: " + msg);
                agent.logSession(userGoal, reasoning, null, results);
                
                agent.addAssistantMessage(action.toString());
                agent.addUserMessage("Plan acknowledged. Execute the FIRST step now using add_components (NOT modify_components). If the tree has no NoseCone/BodyTube yet, add them to the Stage (e.g. \"Devam Et\").");
                continue;
            } else if ("report".equals(cmd)) {
                String msg = jsonString(action, "message");
                if (msg == null) {
                    msg = "No message provided.";
                }
                appendChat("System", "LlamaRocket reported: " + msg);
                agent.addAssistantMessage(action.toString());
                agent.logSession(userGoal, reasoning, null, results);
                break;
            } else if ("finish".equals(cmd)) {
                String reason = jsonString(action, "reason");
                if (reason == null) {
                    reason = "";
                }
                appendChat("System", "Goal achieved! Reason: " + reason);
                agent.addAssistantMessage(action.toString());
                agent.logSession(userGoal, reasoning, null, results);
                break;
            } else {
                appendChat("System", "Unknown action: " + cmd);
                agent.addAssistantMessage(action.toString());
                agent.addUserMessage("ACTION FAILED: unknown action \"" + cmd + "\". Available actions: modify_components, add_components, delete_component, assign_motor, plan_and_continue, report, finish.");
                continue;
            }
            } catch (Exception e) {
                appendChat("Error", e.getClass().getSimpleName() + ": " + e.getMessage());
                agent.addAssistantMessage(action.toString());
                agent.addUserMessage("SYSTEM ERROR: " + e.getMessage() + ". Output valid JSON with all required fields.");
                continue;
            }
        }
        
            } catch (Exception e) {
                appendChat("Error", e.getMessage());
                e.printStackTrace();
            } finally {
                // Sync the session history into the OpenRocketDocument so it gets saved
                document.setQwenChatHistory(agent.getHistoryAsJsonArray());
                
                SwingUtilities.invokeLater(() -> {
                    isThinking = false;
                    sendButton.setEnabled(true);
                    inputField.setEnabled(true);
                });
            }
        }).start();
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
            
            info.openrocket.core.rocketcomponent.Rocket rocket = document.getRocket();
            
            // CRITICAL: Ensure the simulation uses the default configuration where the motor was assigned!
            sim.setFlightConfigurationId(
                rocket.getSelectedConfiguration().getId()
            );
            
            System.out.println("DEBUG FC ID: " + sim.getFlightConfigurationId());
            System.out.println("DEBUG default motors: " + rocket.getSelectedConfiguration().getActiveMotors().size());
            System.out.println("DEBUG sim config motors: " + rocket.getFlightConfiguration(sim.getFlightConfigurationId()).getActiveMotors().size());
            
            sim.simulate();
            FlightData data = sim.getSimulatedData();
            
            double apogee = data.getMaxAltitude();
            double velocity = data.getMaxVelocity();
            double timeToApogee = data.getTimeToApogee();
            double totalFlightTime = data.getFlightTime();
            
            res.addProperty("apogee_meters", apogee);
            res.addProperty("max_velocity_ms", velocity);
            res.addProperty("time_to_apogee_s", timeToApogee);
            res.addProperty("total_flight_time_s", totalFlightTime);
            
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
