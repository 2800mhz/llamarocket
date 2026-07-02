package info.openrocket.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.google.gson.JsonObject;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.startup.OpenRocketCore;

class LlamaRocketDesignServiceTest {

	private final LlamaRocketDesignService service = new LlamaRocketDesignService();

	@BeforeAll
	static void initializeOpenRocket() {
		OpenRocketCore.initialize();
	}

	@Test
	void inspectionIncludesStableIdsEditablePropertiesAndUnits() {
		Rocket rocket = rocketWithBody();
		BodyTube body = (BodyTube) rocket.getChild(0).getChild(0);

		JsonObject json = service.inspectComponent(body);

		assertEquals(body.getID().toString(), json.get("id").getAsString());
		assertEquals("BodyTube", json.get("type").getAsString());
		JsonObject length = json.getAsJsonObject("properties").getAsJsonObject("length");
		assertTrue(length.get("editable").getAsBoolean());
		assertEquals("m", length.get("unit").getAsString());
	}

	@Test
	void propertiesAreSetByIdAndInvalidBatchDoesNotPartiallyApply() throws Exception {
		Rocket rocket = rocketWithBody();
		BodyTube body = (BodyTube) rocket.getChild(0).getChild(0);
		double original = body.getLength();

		Map<String, Object> invalidBatch = new LinkedHashMap<>();
		invalidBatch.put("length", 0.75);
		invalidBatch.put("does_not_exist", 1.0);
		assertThrows(IllegalArgumentException.class,
				() -> service.setProperties(rocket, body.getID().toString(), invalidBatch));
		assertEquals(original, body.getLength());

		service.setProperties(rocket, body.getID().toString(), Map.of("length", 0.75));
		assertEquals(0.75, body.getLength());
	}

	@Test
	void componentCreationUsesParentIdAndOpenRocketCompatibility() throws Exception {
		Rocket rocket = new Rocket();
		AxialStage stage = new AxialStage();
		rocket.addChild(stage);

		RocketComponent added = service.addComponent(rocket, stage.getID().toString(), "BodyTube", "Airframe");

		assertNotNull(added);
		assertEquals("Airframe", added.getName());
		assertEquals(stage, added.getParent());
		assertThrows(IllegalArgumentException.class,
				() -> service.addComponent(rocket, added.getID().toString(), "AxialStage", "Invalid stage"));
	}

	@Test
	void malformedOrUnknownIdsNeverFallBackToNames() {
		Rocket rocket = rocketWithBody();
		assertEquals(null, service.findById(rocket, "Body tube"));
		assertEquals(null, service.findById(rocket, "not-a-uuid"));
	}

	@Test
	void basicRocketBlueprintCreatesValidHierarchyAndProtectsStage() {
		Rocket rocket = new Rocket();
		AxialStage stage = new AxialStage();
		stage.setName("Sustainer");
		rocket.addChild(stage);

		JsonObject summary = service.createBasicRocket(rocket, 0.350);

		assertNotNull(summary);
		assertTrue(stage.getChildCount() >= 2);
		assertTrue(stage.getChild(1) instanceof BodyTube);
		NoseCone nose = (NoseCone) stage.getChild(0);
		BodyTube body = (BodyTube) stage.getChild(1);
		assertEquals(body.getOuterRadius(), nose.getBaseRadius());
		MassComponent payload = (MassComponent) body.getChild(0);
		assertEquals(0.350, payload.getComponentMass());
		rocket.getSelectedConfiguration().update();
		assertTrue(rocket.getSelectedConfiguration().hasRecoveryDevice());
		assertThrows(IllegalArgumentException.class,
				() -> service.deleteComponent(rocket, stage.getID().toString()));
	}

	private static Rocket rocketWithBody() {
		Rocket rocket = new Rocket();
		AxialStage stage = new AxialStage();
		stage.setName("Sustainer");
		rocket.addChild(stage);
		BodyTube body = new BodyTube();
		body.setName("Airframe");
		stage.addChild(body);
		return rocket;
	}
}
