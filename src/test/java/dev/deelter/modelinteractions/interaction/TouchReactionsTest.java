package dev.deelter.modelinteractions.interaction;

import dev.deelter.modelinteractions.interaction.TouchReactions.Kind;
import dev.deelter.modelinteractions.interaction.TouchReactions.Part;
import dev.deelter.modelinteractions.model.BodyRegion;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.wire.Wire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TouchReactionsTest {
	@Test
	void actionBeforeInteractMostPreciseFirst() {
		Part ear = new Part(17, null, PartType.EAR, BodyRegion.HEAD);
		assertEquals(List.of("mi_pet_part17", "mi_pet_head", "mi_pet",
						"mi_interact_part17", "mi_interact_head", "mi_interact"),
				TouchReactions.animationNames(Kind.PET, ear));
	}

	@Test
	void vanillaPartUsesItsName() {
		Part tail = new Part(3, "Tail-1", PartType.TAIL, BodyRegion.BODY);
		assertEquals(List.of("mi_grab_tail_1", "mi_grab_body", "mi_grab",
						"mi_interact_tail_1", "mi_interact_body", "mi_interact"),
				TouchReactions.animationNames(Kind.GRAB, tail));
	}

	@Test
	void hugIsWholePlayer() {
		Part whole = new Part(Wire.BODY_BONE, null, PartType.STATIC, BodyRegion.BODY);
		assertEquals(List.of("mi_hug", "mi_interact"), TouchReactions.animationNames(Kind.HUG, whole));
	}

	@Test
	void torsoHasNoElementNumber() {
		Part whole = new Part(Wire.BODY_BONE, null, PartType.STATIC, BodyRegion.BODY);
		assertEquals(List.of("mi_hit_body", "mi_hit", "mi_interact_body", "mi_interact"),
				TouchReactions.animationNames(Kind.HIT, whole));
	}
}
