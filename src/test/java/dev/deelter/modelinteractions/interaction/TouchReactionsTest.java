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
	void cpmElementMostPreciseFirst() {
		Part ear = new Part(17, null, PartType.EAR, BodyRegion.HEAD);
		assertEquals(List.of("mi_pet_part17", "mi_pet_head", "mi_pet"),
				TouchReactions.animationNames(Kind.PET, ear));
	}

	@Test
	void vanillaPartUsesItsName() {
		Part tail = new Part(3, "Tail-1", PartType.TAIL, BodyRegion.BODY);
		assertEquals(List.of("mi_grab_tail_1", "mi_grab_body", "mi_grab"),
				TouchReactions.animationNames(Kind.GRAB, tail));
	}

	@Test
	void guessedTypeIsNotUsed() {
		Part arm = new Part(5, null, PartType.STATIC, BodyRegion.LEFT_ARM);
		assertEquals(List.of("mi_touch_part5", "mi_touch_left_arm", "mi_touch"),
				TouchReactions.animationNames(Kind.TOUCH, arm));
	}

	@Test
	void hugIsWholePlayer() {
		Part whole = new Part(Wire.BODY_BONE, null, PartType.STATIC, BodyRegion.BODY);
		assertEquals(List.of("mi_hug"), TouchReactions.animationNames(Kind.HUG, whole));
		assertEquals(List.of("mi_grab_body", "mi_grab"), TouchReactions.animationNames(Kind.GRAB, whole));
	}
}
