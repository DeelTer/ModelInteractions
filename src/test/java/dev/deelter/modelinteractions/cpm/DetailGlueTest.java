package dev.deelter.modelinteractions.cpm;

import com.tom.cpl.math.Rotation;
import com.tom.cpl.math.Vec3f;
import dev.deelter.modelinteractions.model.BodyRegion;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.model.PartClassifier;
import dev.deelter.modelinteractions.physics.PartType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DetailGlueTest {
	private static Bone bone(int id, Bone parent, PartType type, float px, float size) {
		Bone b = new Bone(id, parent, BodyRegion.HEAD, parent == null);
		b.setBox(new Vector3f(px, 0, 0).div(16f), new Vector3f(px + size, size, size).div(16f));
		CpmHooks.setRest(b, new Vec3f(0, 0, 0), new Rotation(), null);
		b.setType(type);
		return b;
	}

	@Test
	void detailFollowsSiblingItSitsOn() {
		Bone root = bone(1, null, PartType.HEAD, -100, 8);
		Bone ear = bone(2, root, PartType.EAR, 0, 6);
		Bone stud = bone(3, root, PartType.ACCESSORY, 6, 1);
		PartClassifier.glueDetails(List.of(root, ear, stud), List.of(ear, stud));
		assertEquals(PartType.STATIC, stud.getType());
		assertEquals(ear, stud.getHost());
		assertEquals(ear, stud.movable());
		assertEquals(PartType.EAR, ear.getType());
	}

	@Test
	void looseDetailFarFromItsPivotStaysRigid() {
		Bone root = bone(1, null, PartType.HEAD, -100, 8);
		Bone far = bone(2, root, PartType.ACCESSORY, 20, 1);
		PartClassifier.glueDetails(List.of(root, far), List.of(far));
		assertEquals(PartType.STATIC, far.getType());
		assertNull(far.getHost());
	}

	@Test
	void touchingBoxesOfAnEmptyGroupMoveAsOne() {
		Bone root = bone(1, null, PartType.HEAD, -100, 8);
		Bone group = bone(2, root, PartType.ACCESSORY, 0, 0);
		Bone a = bone(3, group, PartType.HAIR, 0, 6);
		Bone b = bone(4, group, PartType.HAIR, 6, 6);
		List<Bone> all = List.of(root, group, a, b);
		PartClassifier.glueDetails(all, List.of(group, a, b));
		assertEquals(PartType.HAIR, group.getType());
		assertEquals(PartType.STATIC, a.getType());
		assertEquals(group, b.movable());
	}

	@Test
	void groupOfApartBoxesStaysAsItIs() {
		Bone root = bone(1, null, PartType.HEAD, -100, 8);
		Bone group = bone(2, root, PartType.ACCESSORY, 0, 0);
		Bone a = bone(3, group, PartType.HAIR, 0, 6);
		Bone b = bone(4, group, PartType.HAIR, 30, 6);
		PartClassifier.glueDetails(List.of(root, group, a, b), List.of(group, a, b));
		assertEquals(PartType.HAIR, a.getType());
		assertEquals(a, a.movable());
	}

	@Test
	void pickedTypeIsLeftAlone() {
		Bone root = bone(1, null, PartType.HEAD, -100, 8);
		Bone ear = bone(2, root, PartType.EAR, 0, 6);
		Bone stud = bone(3, root, PartType.EAR, 6, 1);
		PartClassifier.glueDetails(List.of(root, ear, stud), List.of(ear));
		assertEquals(PartType.EAR, stud.getType());
		assertNull(stud.getHost());
	}
}
