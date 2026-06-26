import os
import jpype
import jpype.imports
import requests
import json

class QwenRocketBridge:
    def __init__(self, core_jar_path=None):
        if core_jar_path is None:
            # Try to guess the path based on typical gradle build output
            base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            core_jar_path = os.path.join(base_dir, "build", "libs", "*")
            
        if not jpype.isJVMStarted():
            print(f"Starting JVM with classpath: {core_jar_path}")
            # Ensure it picks up Java 17 we just installed
            java_home = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
            os.environ["JAVA_HOME"] = java_home
            jvm_path = os.path.join(java_home, "bin", "server", "jvm.dll")
            jpype.startJVM(jvm_path, classpath=[core_jar_path], convertStrings=True)
            
        # We need to import the java packages to use them
        self.java_io = jpype.JPackage('java.io')
        self.java_lang = jpype.JPackage('java.lang')
        
        # OpenRocket core packages
        self.info = jpype.JPackage('info')
        self.core = self.info.openrocket.core
        self.aerodynamics = self.core.aerodynamics
        self.file = self.core.file
        
        # Initialize Guice dependency injection (required by OpenRocket)
        if self.core.startup.Application.getInjector() is None:
            print("Initializing Guice Injector...")
            guice = jpype.JPackage('com.google.inject').Guice
            gui_module = jpype.JPackage('info.openrocket.swing.startup').GuiModule()
            plugin_module = self.core.plugin.PluginModule()
            injector = guice.createInjector(gui_module, plugin_module)
            self.core.startup.Application.setInjector(injector)
        
    def load_ork(self, filepath):
        """Loads an OpenRocket document from a file."""
        file = self.java_io.File(filepath)
        loader = self.file.GeneralRocketLoader(file)
        document = loader.load()
        return document

    def run_simulation(self, document):
        """Runs the simulation and returns key results."""
        sim = document.getSimulations().get(0)
        
        # Run simulation
        sim.simulate()
        
        # Get flight data
        flight_data = sim.getSimulatedData()
        
        if flight_data is None:
            raise Exception("Simulation returned no data.")
        
        # Extract results
        apogee = flight_data.getMaxAltitude()
        max_velocity = flight_data.getMaxVelocity()
        
        return {
            "apogee_meters": apogee,
            "max_velocity": max_velocity
        }
        
    def get_component_tree(self, rocket):
        """Extracts a tree of components and their editable parameters."""
        tree = []
        for child in rocket.getChildren():
            tree.append(self._extract_component_node(child))
        return tree
        
    def _extract_component_node(self, component):
        """Recursively extracts a component and its parameters."""
        node = {
            "id": str(component.getID()),
            "name": str(component.getName()),
            "type": str(component.getClass().getSimpleName()),
            "parameters": self._extract_parameters(component),
            "children": []
        }
        
        for child in component.getChildren():
            node["children"].append(self._extract_component_node(child))
            
        return node
        
    def _extract_parameters(self, component):
        """Extracts known editable parameters based on component type."""
        params = {}
        comp_type = str(component.getClass().getSimpleName())
        
        # Helper to safely extract double values
        def try_get(name, method_name):
            if hasattr(component, method_name):
                try:
                    params[name] = float(getattr(component, method_name)())
                except Exception:
                    pass
                    
        if comp_type in ["NoseCone", "BodyTube", "InnerTube", "Transition"]:
            try_get("length", "getLength")
            try_get("thickness", "getThickness")
            
        if comp_type in ["NoseCone", "Transition"]:
            try_get("aft_radius", "getAftRadius")
            
        if comp_type in ["Transition"]:
            try_get("fore_radius", "getForeRadius")
            
        if comp_type in ["BodyTube", "InnerTube"]:
            try_get("outer_radius", "getOuterRadius")
            try_get("inner_radius", "getInnerRadius")
            
        if comp_type in ["TrapezoidFinSet", "FreeformFinSet", "EllipticalFinSet"]:
            try_get("thickness", "getThickness")
            try_get("root_chord", "getRootChord")
            try_get("tip_chord", "getTipChord")
            try_get("height", "getHeight")
            try_get("sweep_angle", "getSweepAngle")
            
        return params
        
    def get_rocket(self, document):
        return document.getRocket()

    def modify_component(self, rocket, component_name, parameter, new_value):
        """Finds a component by name and modifies its parameter."""
        component = self._find_component_by_name(rocket, component_name)
        if not component:
            raise ValueError(f"Component '{component_name}' not found.")
            
        # Convert snake_case parameter to camelCase setter
        # e.g., 'length' -> 'setLength', 'outer_radius' -> 'setOuterRadius'
        parts = parameter.split('_')
        camel_name = parts[0].capitalize() + ''.join(p.capitalize() for p in parts[1:])
        setter_name = f"set{camel_name}"
        
        if not hasattr(component, setter_name):
            raise ValueError(f"Component '{component_name}' does not have parameter '{parameter}'.")
            
        try:
            # Set the value (assume float for all geometry dimensions)
            getattr(component, setter_name)(float(new_value))
        except Exception as e:
            raise ValueError(f"Failed to set '{parameter}' to '{new_value}': {e}")
            
    def _find_component_by_name(self, root, name):
        if str(root.getName()) == name:
            return root
        for child in root.getChildren():
            found = self._find_component_by_name(child, name)
            if found:
                return found
        return None
        
    def send_to_qwen(self, prompt, simulation_results=None):
        """
        POSTs to http://localhost:11434/api/chat with model 'qwen3:4b' 
        and returns the response text.
        """
        url = "http://localhost:11434/api/chat"
        
        # Format the context to include simulation results
        context = f"Simulation Results: {json.dumps(simulation_results)}\n\n"
        full_prompt = context + prompt
        
        payload = {
            "model": "qwen3:4b",
            "messages": [
                {
                    "role": "user",
                    "content": full_prompt
                }
            ],
            "stream": False
        }
        
        try:
            response = requests.post(url, json=payload, timeout=30)
            response.raise_for_status()
            data = response.json()
            return data.get("message", {}).get("content", "")
        except requests.exceptions.RequestException as e:
            print(f"Error connecting to Ollama: {e}")
            return None
