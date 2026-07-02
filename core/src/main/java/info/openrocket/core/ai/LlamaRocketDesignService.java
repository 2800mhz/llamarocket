package info.openrocket.core.ai;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.database.Database;
import info.openrocket.core.database.Databases;
import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.EngineBlock;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.rocketcomponent.position.AxialMethod;

/**
 * Safe, model-independent tool surface for LlamaRocket.
 *
 * The language model never receives a Java object or arbitrary reflection access.  This
 * service discovers readable/editable bean properties and exposes JSON-safe values while
 * keeping mutations constrained to RocketComponent setters with supported scalar types.
 */
public final class LlamaRocketDesignService {

	private static final String COMPONENT_PACKAGE = "info.openrocket.core.rocketcomponent.";
	private static final Set<String> BLOCKED_PROPERTIES = Set.of(
			"class", "id", "parent", "children", "root", "rocket", "mutex",
			"componentname", "childposition", "stage", "stageid", "modid");
	private static final Map<String, String> UNITS = createUnits();

	public JsonObject inspectRocket(Rocket rocket) {
		JsonObject result = new JsonObject();
		result.addProperty("schema_version", 1);
		result.addProperty("units", "SI");
		result.add("root", inspectComponent(rocket));
		return result;
	}

	/** Compact tree used in every model turn; detailed properties are returned only on demand. */
	public JsonObject inspectSummary(RocketComponent component) {
		JsonObject result = new JsonObject();
		result.addProperty("id", component.getID().toString());
		result.addProperty("name", component.getName());
		result.addProperty("type", component.getClass().getSimpleName());
		JsonObject all = discoverProperties(component);
		JsonObject key = new JsonObject();
		for (String property : List.of("length", "outerradius", "baseradius", "foreradius", "aftradius",
				"thickness", "mass", "componentmass", "rootchord", "tipchord", "height", "fincount", "diameter")) {
			if (all.has(property)) key.add(property, all.getAsJsonObject(property).get("value"));
		}
		if (key.size() > 0) result.add("key_properties", key);
		if (component.getAllMaterials() != null && !component.getAllMaterials().isEmpty()) {
			addMaterial(result, component);
		}
		JsonArray children = new JsonArray();
		for (RocketComponent child : component.getChildren()) children.add(inspectSummary(child));
		if (children.size() > 0) result.add("children", children);
		return result;
	}

	public JsonObject inspectComponent(RocketComponent component) {
		JsonObject result = new JsonObject();
		result.addProperty("id", component.getID().toString());
		result.addProperty("name", component.getName());
		result.addProperty("type", component.getClass().getSimpleName());
		result.addProperty("path", componentPath(component));
		result.add("properties", discoverProperties(component));
		addMaterial(result, component);

		JsonArray children = new JsonArray();
		for (RocketComponent child : component.getChildren()) {
			children.add(inspectComponent(child));
		}
		result.add("children", children);
		return result;
	}

	public RocketComponent findById(RocketComponent root, String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		final UUID wanted;
		try {
			wanted = UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (wanted.equals(root.getID())) {
			return root;
		}
		for (RocketComponent child : root.getChildren()) {
			RocketComponent found = findById(child, id);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	public void setProperties(Rocket rocket, String componentId, Map<String, Object> changes) throws Exception {
		RocketComponent component = requireComponent(rocket, componentId);
		if (changes == null || changes.isEmpty()) {
			throw new IllegalArgumentException("At least one property change is required.");
		}

		// Validate and convert the complete batch before changing the design.
		List<PendingSetter> pending = new ArrayList<>();
		for (Map.Entry<String, Object> entry : changes.entrySet()) {
			Method setter = findSetter(component, entry.getKey());
			if (setter == null) {
				throw new IllegalArgumentException("Property is not editable: " + entry.getKey());
			}
			Method getter = findGetter(component, entry.getKey(), setter.getParameterTypes()[0]);
			if (getter == null) {
				throw new IllegalArgumentException("Property cannot be transactionally edited: " + entry.getKey());
			}
			pending.add(new PendingSetter(setter, convertValue(entry.getValue(), setter.getParameterTypes()[0]), getter.invoke(component)));
		}
		int applied = 0;
		try {
			for (PendingSetter change : pending) {
				change.method().invoke(component, change.value());
				applied++;
			}
		} catch (Exception failure) {
			for (int i = applied - 1; i >= 0; i--) {
				PendingSetter change = pending.get(i);
				try {
					change.method().invoke(component, change.originalValue());
				} catch (Exception rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
			}
			throw failure;
		}
	}

	public RocketComponent addComponent(Rocket rocket, String parentId, String componentType, String name) throws Exception {
		RocketComponent parent = requireComponent(rocket, parentId);
		if (componentType == null || !componentType.matches("[A-Za-z][A-Za-z0-9]*")) {
			throw new IllegalArgumentException("Invalid component type.");
		}
		Class<?> rawClass = Class.forName(COMPONENT_PACKAGE + componentType);
		if (!RocketComponent.class.isAssignableFrom(rawClass) || Modifier.isAbstract(rawClass.getModifiers())) {
			throw new IllegalArgumentException("Unsupported concrete component type: " + componentType);
		}
		Constructor<?> constructor = rawClass.getDeclaredConstructor();
		if (!Modifier.isPublic(constructor.getModifiers())) {
			throw new IllegalArgumentException("Component type cannot be constructed by the agent: " + componentType);
		}
		RocketComponent component = (RocketComponent) constructor.newInstance();
		if (!parent.isCompatible(component)) {
			throw new IllegalArgumentException(componentType + " is not compatible with parent " + parent.getName());
		}
		if (name != null && !name.isBlank()) {
			component.setName(name.trim());
		}
		parent.addChild(component);
		return component;
	}

	public void deleteComponent(Rocket rocket, String componentId) {
		RocketComponent component = requireComponent(rocket, componentId);
		if (component instanceof Rocket || component instanceof AxialStage || component.getParent() == null) {
			throw new IllegalArgumentException("Rocket and stage containers cannot be deleted by the agent.");
		}
		component.getParent().removeChild(component);
	}

	/** Creates a valid, conservative single-stage starting point for optimization. */
	public JsonObject createBasicRocket(Rocket rocket) {
		return createBasicRocket(rocket, 0.0);
	}

	public JsonObject createBasicRocket(Rocket rocket, double payloadMassKg) {
		AxialStage stage = null;
		for (RocketComponent child : rocket.getChildren()) {
			if (child instanceof AxialStage axialStage) {
				stage = axialStage;
				break;
			}
		}
		if (stage == null) {
			stage = new AxialStage();
			stage.setName("Sustainer");
			rocket.addChild(stage);
		}

		// Blueprint is intentionally allowed only on an empty stage to avoid duplicating a design.
		if (stage.getChildCount() > 0) {
			throw new IllegalStateException("The selected stage is not empty; inspect and edit the existing design instead.");
		}
		NoseCone nose = new NoseCone();
		nose.setName("Nose cone");
		nose.setLength(0.12);
		nose.setBaseRadiusAutomatic(false);
		nose.setBaseRadius(0.02);
		stage.addChild(nose);

		BodyTube body = new BodyTube();
		body.setName("Main airframe");
		body.setLength(0.45);
		body.setOuterRadius(0.02);
		body.setThickness(0.001);
		stage.addChild(body);

		MassComponent payload = new MassComponent();
		payload.setName("Payload");
		payload.setMassComponentType(MassComponent.MassComponentType.PAYLOAD);
		payload.setLength(0.08);
		payload.setRadius(0.015);
		payload.setComponentMass(Math.max(0.0, payloadMassKg));
		body.addChild(payload);

		TrapezoidFinSet fins = new TrapezoidFinSet();
		fins.setName("Main fins");
		fins.setFinCount(3);
		fins.setRootChord(0.09);
		fins.setTipChord(0.04);
		fins.setHeight(0.06);
		fins.setThickness(0.002);
		fins.setAxialMethod(AxialMethod.BOTTOM);
		fins.setAxialOffset(0);
		body.addChild(fins);

		InnerTube motorTube = new InnerTube();
		motorTube.setName("Motor mount");
		motorTube.setLength(0.12);
		motorTube.setOuterRadius(0.0155);
		motorTube.setThickness(0.00075);
		motorTube.setMotorMount(true);
		motorTube.setAxialMethod(AxialMethod.BOTTOM);
		motorTube.setAxialOffset(0);
		body.addChild(motorTube);

		EngineBlock block = new EngineBlock();
		block.setName("Engine block");
		motorTube.addChild(block);

		Parachute parachute = new Parachute();
		parachute.setName("Main parachute");
		parachute.setDiameter(0.3);
		body.addChild(parachute);

		ShockCord cord = new ShockCord();
		cord.setName("Shock cord");
		cord.setCordLength(1.0);
		body.addChild(cord);

		return inspectSummary(rocket);
	}

	public JsonArray listMaterials(String type) {
		Material.Type materialType = parseMaterialType(type);
		Database<Material> database = Databases.getDatabase(materialType);
		JsonArray result = new JsonArray();
		for (Material material : database) {
			JsonObject item = new JsonObject();
			item.addProperty("name", material.getName());
			item.addProperty("type", material.getType().name());
			item.addProperty("density", material.getDensity());
			item.addProperty("density_unit", densityUnit(material.getType()));
			item.addProperty("group", String.valueOf(material.getGroup()));
			result.add(item);
		}
		return result;
	}

	public void setMaterial(Rocket rocket, String componentId, String materialType, String materialName) throws Exception {
		RocketComponent component = requireComponent(rocket, componentId);
		Material material = Databases.findMaterial(parseMaterialType(materialType), materialName);
		if (material == null) {
			throw new IllegalArgumentException("Material not found: " + materialName);
		}
		Method setter = component.getClass().getMethod("setMaterial", Material.class);
		setter.invoke(component, material);
	}

	private JsonObject discoverProperties(RocketComponent component) {
		Map<String, Method> getters = new LinkedHashMap<>();
		for (Method method : component.getClass().getMethods()) {
			if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
				continue;
			}
			String property = getterProperty(method);
			if (property != null && !isBlocked(property) && isScalar(method.getReturnType())) {
				getters.putIfAbsent(property.toLowerCase(Locale.ROOT), method);
			}
		}

		JsonObject result = new JsonObject();
		getters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			try {
				Object value = entry.getValue().invoke(component);
				if (value == null || value instanceof Double d && !Double.isFinite(d)) {
					return;
				}
				JsonObject property = new JsonObject();
				addScalar(property, "value", value);
				Method setter = findSetter(component, entry.getKey());
				property.addProperty("editable", setter != null);
				String unit = unitFor(entry.getKey());
				if (unit != null) {
					property.addProperty("unit", unit);
				}
				if (value instanceof Enum<?> enumValue) {
					JsonArray choices = new JsonArray();
					for (Object choice : enumValue.getDeclaringClass().getEnumConstants()) {
						choices.add(((Enum<?>) choice).name());
					}
					property.add("choices", choices);
				}
				result.add(entry.getKey(), property);
			} catch (ReflectiveOperationException ignored) {
				// A component getter may depend on an unavailable configuration. Skip it safely.
			}
		});
		return result;
	}

	private void addMaterial(JsonObject target, RocketComponent component) {
		try {
			Method getter = component.getClass().getMethod("getMaterial");
			Object value = getter.invoke(component);
			if (value instanceof Material material) {
				JsonObject json = new JsonObject();
				json.addProperty("name", material.getName());
				json.addProperty("type", material.getType().name());
				json.addProperty("density", material.getDensity());
				json.addProperty("density_unit", densityUnit(material.getType()));
				target.add("material", json);
			}
		} catch (ReflectiveOperationException ignored) {
			// Not every component has material.
		}
	}

	private Method findSetter(RocketComponent component, String requestedProperty) {
		String normalized = normalize(requestedProperty);
		if (isBlocked(normalized)) {
			return null;
		}
		return java.util.Arrays.stream(component.getClass().getMethods())
				.filter(m -> m.getName().startsWith("set") && m.getParameterCount() == 1)
				.filter(m -> normalize(m.getName().substring(3)).equals(normalized))
				.filter(m -> isScalar(m.getParameterTypes()[0]))
				.sorted(Comparator.comparing(Method::toString))
				.findFirst().orElse(null);
	}

	private Method findGetter(RocketComponent component, String requestedProperty, Class<?> expectedType) {
		String normalized = normalize(requestedProperty);
		for (Method method : component.getClass().getMethods()) {
			String property = getterProperty(method);
			if (property != null && normalize(property).equals(normalized) &&
					wrap(method.getReturnType()).equals(wrap(expectedType))) return method;
		}
		return null;
	}

	private static Class<?> wrap(Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == boolean.class) return Boolean.class;
		if (type == double.class) return Double.class;
		if (type == float.class) return Float.class;
		if (type == int.class) return Integer.class;
		if (type == long.class) return Long.class;
		return type;
	}

	private Object convertValue(Object raw, Class<?> target) {
		if (raw == null) {
			throw new IllegalArgumentException("Property value cannot be null.");
		}
		if (target == String.class) return String.valueOf(raw);
		if (target == boolean.class || target == Boolean.class) {
			if (raw instanceof Boolean b) return b;
			String value = String.valueOf(raw);
			if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
				throw new IllegalArgumentException("Expected a boolean value, got: " + raw);
			}
			return Boolean.parseBoolean(value);
		}
		if (target.isEnum()) {
			for (Object value : target.getEnumConstants()) {
				if (((Enum<?>) value).name().equalsIgnoreCase(String.valueOf(raw))) return value;
			}
			throw new IllegalArgumentException("Unknown enum value " + raw + " for " + target.getSimpleName());
		}
		if (Number.class.isAssignableFrom(target) || target.isPrimitive()) {
			double value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw));
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Numeric values must be finite.");
			if (target == double.class || target == Double.class) return value;
			if (target == float.class || target == Float.class) return (float) value;
			if (target == int.class || target == Integer.class) return Math.toIntExact(Math.round(value));
			if (target == long.class || target == Long.class) return Math.round(value);
		}
		throw new IllegalArgumentException("Unsupported property type: " + target.getSimpleName());
	}

	private RocketComponent requireComponent(Rocket rocket, String id) {
		RocketComponent component = findById(rocket, id);
		if (component == null) throw new IllegalArgumentException("Component ID not found: " + id);
		return component;
	}

	private static String getterProperty(Method method) {
		String name = method.getName();
		if (name.startsWith("get") && name.length() > 3) return name.substring(3);
		if (name.startsWith("is") && name.length() > 2 &&
				(method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) return name.substring(2);
		return null;
	}

	private static boolean isScalar(Class<?> type) {
		return type == String.class || type == boolean.class || type == Boolean.class || type.isEnum() ||
				type == double.class || type == Double.class || type == float.class || type == Float.class ||
				type == int.class || type == Integer.class || type == long.class || type == Long.class;
	}

	private static boolean isBlocked(String property) {
		return BLOCKED_PROPERTIES.contains(normalize(property));
	}

	private static String normalize(String value) {
		return value == null ? "" : value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
	}

	private static void addScalar(JsonObject object, String key, Object value) {
		if (value instanceof Boolean b) object.addProperty(key, b);
		else if (value instanceof Number n) object.addProperty(key, n);
		else if (value instanceof Enum<?> e) object.addProperty(key, e.name());
		else object.addProperty(key, String.valueOf(value));
	}

	private static String componentPath(RocketComponent component) {
		List<String> path = new ArrayList<>();
		for (RocketComponent current = component; current != null; current = current.getParent()) path.add(0, current.getName());
		return String.join(" / ", path);
	}

	private static Material.Type parseMaterialType(String type) {
		try {
			return Material.Type.valueOf(type.toUpperCase(Locale.ROOT));
		} catch (Exception e) {
			throw new IllegalArgumentException("Material type must be BULK, SURFACE, or LINE.");
		}
	}

	private static String densityUnit(Material.Type type) {
		return switch (type) {
			case BULK, CUSTOM -> "kg/m^3";
			case SURFACE -> "kg/m^2";
			case LINE -> "kg/m";
		};
	}

	private static String unitFor(String property) {
		return UNITS.get(normalize(property));
	}

	private static Map<String, String> createUnits() {
		Map<String, String> units = new LinkedHashMap<>();
		for (String key : List.of("length", "radius", "baseradius", "foreradius", "aftradius", "outerradius",
				"innerradius", "thickness", "height", "rootchord", "tipchord", "sweeplength", "axialoffset",
				"radialoffset", "diameter", "linelength", "cordlength", "packedlength")) units.put(key, "m");
		for (String key : List.of("mass", "overridemass", "componentmass")) units.put(key, "kg");
		for (String key : List.of("angle", "sweepangle", "cantangle", "radialdirection")) units.put(key, "rad");
		return Map.copyOf(units);
	}

	private record PendingSetter(Method method, Object value, Object originalValue) {}
}
