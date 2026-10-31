package dev.deelter.modelinteractions.cpm;

import com.tom.cpl.math.Rotation;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.io.ModelFile;
import com.tom.cpm.shared.model.Cube;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RenderedCube;
import com.tom.cpm.shared.parts.ModelPartType;
import dev.deelter.modelinteractions.model.BodyRegion;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.model.PartClassifier;
import dev.deelter.modelinteractions.physics.PartType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses the real test models with CPM's own readers and runs the classifier on them,
 * mirroring what {@link CpmHooks} does in game (without vanilla root poses).
 */
class ModelFileClassificationTest {
	private static final float[][] ROOT_POS = {
			{0, 0, 0}, {0, 0, 0}, {5, 2, 0}, {-5, 2, 0}, {1.9f, 12, 0}, {-1.9f, 12, 0}};

	private static List<Cube> readCubes(String res) throws IOException {
		try (InputStream in = ModelFileClassificationTest.class.getResourceAsStream(res)) {
			ModelFile mf = ModelFile.load(in);
			byte[] data = mf.getDataBlock();
			IOHelper din = new IOHelper(data);
			if (din.read() != ModelDefinitionLoader.HEADER) throw new IOException("bad header");
			List<Cube> out = new ArrayList<>();
			while (true) {
				ModelPartType[] type = new ModelPartType[1];
				Object r = din.readObjectBlock(ModelPartType.VALUES, (t, d) -> {
					type[0] = t;
					switch (t) {
						case CUBES -> {
							int n = d.readVarInt();
							for (int i = 0; i < n; i++) {
								Cube c = Cube.loadDefinitionCubeV2(d);
								c.id = i + 10;
								out.add(c);
							}
						}
						case DEFINITION -> readV1(d, out);
						case DEFINITION_LINK -> {
							// linked definition stored next to the file (overflow block)
							IOHelper link = new IOHelper(overflow(mf));
							if (link.read() != ModelDefinitionLoader.HEADER) throw new IOException("bad link header");
							readV1(link, out);
						}
						default -> {
						}
					}
					return t;
				});
				if (r == null && type[0] == null) continue;
				if (type[0] == ModelPartType.END) break;
			}
			return out;
		}
	}

	// Old (V1) definitions have no other reader: CPM's own ModelPartDefinition uses this same deprecated method.
	@SuppressWarnings("deprecation")
	private static void readV1(IOHelper d, List<Cube> out) throws IOException {
		int n = d.readVarInt();
		for (int i = 0; i < n; i++) {
			Cube c = Cube.loadDefinitionCube(d);
			c.id = i + 10;
			out.add(c);
		}
	}

	private static byte[] overflow(ModelFile mf) throws IOException {
		try {
			var f = ModelFile.class.getDeclaredField("overflowLocal");
			f.setAccessible(true);
			return (byte[]) f.get(mf);
		} catch (ReflectiveOperationException e) {
			throw new IOException(e);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"Rana", "LuciferHelltaker"})
	void classifiesTestModels(String name) throws IOException {
		List<Cube> cubes = readCubes("/models/" + name + ".cpmmodel");
		assertFalse(cubes.isEmpty(), "no cubes parsed");

		Map<Integer, RenderedCube> rc = new HashMap<>();
		for (RenderedCube c : Cube.resolveCubesV2(cubes)) rc.put(c.getId(), c);

		List<Bone> bones = new ArrayList<>();
		for (PlayerModelParts p : PlayerModelParts.VALUES) {
			if (p == PlayerModelParts.CUSTOM_PART) continue;
			Bone root = new Bone(p.ordinal(), null, BodyRegion.fromCpmName(p.getName()), true);
			float[] rp = ROOT_POS[p.ordinal()];
			CpmHooks.setRest(root, new Vec3f(rp[0], rp[1], rp[2]), new Rotation(), null);
			bones.add(root);
			// fake root element holding the top level cubes of this part
			RenderedCube fake = new RenderedCube(new Cube());
			for (Cube c : cubes) if (c.parentId == p.ordinal()) fake.addChild(rc.get(c.id));
			int from = bones.size();
			CpmHooks.walk(fake, root, bones, new StringBuilder());
			for (int i = from; i < bones.size(); i++) {
				Bone b = bones.get(i);
				RenderedCube c = rc.get(b.getId());
				CpmHooks.setRest(b, c.pos, c.rotation, null);
			}
		}

		EnumMap<PartType, Integer> counts = new EnumMap<>(PartType.class);
		StringBuilder dump = new StringBuilder(name).append('\n');
		for (Bone b : bones) {
			Bone root = b;
			while (root.getParent() != null) root = root.getParent();
			PartType t = PartClassifier.classify(b, new Matrix4f(root.model()).invert().mul(b.model()));
			b.setType(t);
			counts.merge(t, 1, Integer::sum);
			if (!b.isRoot() && t != PartType.STATIC) {
				Vector3f c = b.model().transformPosition(b.center(new Vector3f())).mul(16);
				dump.append(String.format("  #%d %-9s %-9s len=%.1fpx center=(%.1f, %.1f, %.1f) size=(%.1f, %.1f, %.1f) parent=%d%n",
						b.getId(), t, b.getRegion(), b.getLengthPx(), c.x, c.y, c.z, (b.getBoxMax().x - b.getBoxMin().x) * 16, (b.getBoxMax().y - b.getBoxMin().y) * 16, (b.getBoxMax().z - b.getBoxMin().z) * 16, b.getParent().getId()));
			}
		}
		dump.append(counts);
		System.out.println(dump);
		assertTrue(bones.size() > 6, "only roots found");
		PartClassifier.glueDetails(bones, bones);
		System.out.println(name + " details: follow=" + bones.stream().filter(b -> b.getHost() != null).count()
				+ " movable before=" + (bones.size() - counts.getOrDefault(PartType.STATIC, 0))
				+ " after=" + bones.stream().filter(b -> b.getType().isGrabbable()).count());
		int ears = counts.getOrDefault(PartType.EAR, 0), tails = counts.getOrDefault(PartType.TAIL, 0);
		switch (name) {
			case "Rana" -> {
				assertTrue(ears >= 2, "Rana has ears");
				assertTrue(tails >= 1, "Rana has a tail");
			}
			case "LuciferHelltaker" -> {
				assertTrue(ears == 0, "Lucifer's horns are not ears");
				assertTrue(tails >= 3, "Lucifer has a segmented tail");
				assertTrue(counts.getOrDefault(PartType.HAIR, 0) >= 3, "Lucifer has long hair");
			}
			default -> {
			}
		}
	}
}
