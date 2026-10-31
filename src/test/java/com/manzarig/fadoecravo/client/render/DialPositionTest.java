package com.manzarig.fadoecravo.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DialPositionTest {
	@Test
	void theNeedleRunsFromLeftToRightAcrossTheBand() {
		assertEquals(DialPosition.X_AT_MIN, DialPosition.needleX(RadioBlockEntity.MIN_KHZ), 1e-6f);
		assertEquals(DialPosition.X_AT_MAX, DialPosition.needleX(RadioBlockEntity.MAX_KHZ), 1e-6f);
		assertEquals(0.0f, DialPosition.needleOffset(DialPosition.REST_KHZ), 1e-6f);
		// Outside the band the needle stops at the ends.
		assertEquals(DialPosition.X_AT_MIN, DialPosition.needleX(100), 1e-6f);
		assertEquals(DialPosition.X_AT_MAX, DialPosition.needleX(5000), 1e-6f);
		// Block x falls as the frequency rises (530 is the viewer's left, high x); GeckoLib mirrors x.
		assertTrue(DialPosition.needleOffset(1600) > 0.0f);
		assertTrue(DialPosition.needleOffset(530) < 0.0f);
	}

	@Test
	void theModelHasItsNeedleWhereTheCodeExpectsIt() throws Exception {
		JsonObject model;
		try (var in = DialPositionTest.class.getResourceAsStream("/assets/fadoecravo/models/block/radio.json")) {
			model = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		for (JsonElement element : model.getAsJsonArray("elements")) {
			JsonObject box = element.getAsJsonObject();
			if (box.get("name").getAsString().equals("needle")) {
				JsonArray from = box.getAsJsonArray("from");
				JsonArray to = box.getAsJsonArray("to");
				float centre = (from.get(0).getAsFloat() + to.get(0).getAsFloat()) / 2;
				assertEquals(DialPosition.needleX(DialPosition.REST_KHZ), centre, 1e-3f);
				return;
			}
		}
		throw new AssertionError("no needle in block/radio.json");
	}

	@Test
	void theKnobTurnsOneAndAHalfTimesOverTheBand() {
		assertEquals(0.0f, DialPosition.knobAngle(RadioBlockEntity.MIN_KHZ), 1e-6f);
		assertEquals(-3.0 * Math.PI, DialPosition.knobAngle(RadioBlockEntity.MAX_KHZ), 1e-5);
	}

	@Test
	void theNeedleGlidesAndArrives() {
		assertEquals(1035.0f, DialPosition.ease(Float.NaN, 1035), "the first frame jumps");
		float shown = 530.0f;
		int frames = 0;
		while (shown != 1600.0f && frames < 200) {
			float next = DialPosition.ease(shown, 1600);
			assertTrue(next > shown, "always moves towards the target");
			assertTrue(next <= 1600.0f, "never overshoots");
			shown = next;
			frames++;
		}
		assertEquals(1600.0f, shown);
		assertTrue(frames > 10 && frames < 100, "a glide of " + frames + " frames");
	}
}
