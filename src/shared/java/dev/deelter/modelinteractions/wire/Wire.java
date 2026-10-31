package dev.deelter.modelinteractions.wire;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Wire format shared by the mod and the Paper plugin. Plain Java only, so the plugin can
 * compile it without Minecraft client classes.
 * <p>
 * Every message starts with {@code [version u8][kind u8]}. Client to server messages never
 * name their sender: the server knows who sent them and stamps that into what it relays.
 *
 * <pre>
 * ACT   c2s: op u8, target uuid, bone i32, hand u8, flags u8, model i32, anchor 3*f32, point 3*f32
 *       s2c: sender uuid, then the same
 * STATE c2s: state u8, len u16, body
 *       s2c: state u8, owner uuid, len u16, body
 * REQ   c2s: target uuid                       ask for the states of one player
 * HELLO c2s: (nothing)                        "I have the mod"; repeated until the server answers
 *       s2c: range u16, flags u8               relay present
 * CARRY c2s: sub u8, target uuid, pos 3*f64, vel 3*f32, hands u8   holding / letting go of something;
 *            pos is where the held thing's feet go so that every grabbed point stays in its hand
 *       s2c: carrier uuid, then the same     only to the carried player
 * LOOK  c2s: active u8, target uuid, pos 3*f64            turn someone's head towards pos
 *       s2c: sender uuid, then the same
 * </pre>
 * <p>
 * Bodies of states are parsed and re-encoded by the server, so a client only ever receives
 * well formed, size-bounded data.
 */
public final class Wire {
	public static final int VERSION = 3;
	public static final String CHANNEL = "modelinteractions:main";

	public static final int K_ACT = 1, K_STATE = 2, K_REQ = 3, K_HELLO = 4, K_CARRY = 5, K_LOOK = 6, K_RESET = 7;
	/**
	 * TETHER (s2c): held on the ground; walk towards pos, can't walk away from it.
	 */
	public static final int CARRY_START = 0, CARRY_MOVE = 1, CARRY_THROW = 2, CARRY_DROP = 3, CARRY_TETHER = 4, CARRY_COUNT = 5;
	public static final int HELLO_CARRY = 2;
	/**
	 * The server is for adults and allows external devices driven by touches (each player still opts in).
	 */
	public static final int HELLO_ADULT = 4;
	/**
	 * Permission flag bits the server reads (same as the mod's InteractionKind bits).
	 */
	public static final int FLAG_CARRY = 1 << 12, FLAG_LOOK = 1 << 13, FLAG_DRAG = 1 << 14, FLAG_CATCH = 1 << 15;
	/**
	 * World coordinates beyond the world border are nonsense.
	 */
	public static final double MAX_WORLD = 3.0e7;
	/**
	 * Blocks per tick; nothing is thrown faster than this.
	 */
	public static final float MAX_THROW = 4f;
	public static final int CARRY_C2S_SIZE = 2 + 1 + 16 + 24 + 12 + 1, LOOK_C2S_SIZE = 2 + 1 + 16 + 24;
	public static final int OP_GRAB = 0, OP_MOVE = 1, OP_RELEASE = 2, OP_PET = 3, OP_HUG = 4, OP_RESET = 5,
	/**
	 * A hit: bone = part hit, anchor = contact point, point = velocity, both in the target's model space; keep = a VR slap.
	 */
	OP_PUSH = 6, OP_COUNT = 7;
	public static final int S_PERM = 0, S_POSE = 1, S_TYPES = 2, S_COUNT = 3;
	public static final int HELLO_MOBS = 1;

	/**
	 * Hands: 0 mouse, 1 and 2 VR main/off hand.
	 */
	public static final int MAX_HAND = 2;
	/**
	 * Bone that stands for the whole torso / body turning.
	 */
	public static final int BODY_BONE = -1;
	/**
	 * Model points are in blocks; no model is bigger than this.
	 */
	public static final float MAX_COORD = 16f;
	public static final int ACT_C2S_SIZE = 2 + 1 + 16 + 4 + 1 + 1 + 4 + 12 + 12;
	public static final int ACT_S2C_SIZE = ACT_C2S_SIZE + 16;
	public static final int MAX_STATE_BODY = 4096;
	public static final int MAX_NAMES = 64, MAX_NAME_LEN = 16, MAX_POSE = 256, MAX_TYPES = 512, MAX_KEY_LEN = 64;
	/**
	 * Pose angles are milliradians; a rotation vector longer than this is nonsense.
	 */
	public static final int MAX_POSE_MRAD = 4000;
	public static final int FLAG_KEEP = 1;

	private Wire() {
	}

	public static byte[] encodeAct(Act a, UUID sender) {
		ByteArrayOutputStream bo = new ByteArrayOutputStream(ACT_S2C_SIZE);
		try (DataOutputStream out = new DataOutputStream(bo)) {
			out.writeByte(VERSION);
			out.writeByte(K_ACT);
			if (sender != null) writeUuid(out, sender);
			out.writeByte(a.op());
			writeUuid(out, a.target());
			out.writeInt(a.bone());
			out.writeByte(a.hand());
			out.writeByte(a.keep() ? FLAG_KEEP : 0);
			out.writeInt(a.model());
			for (float f : a.anchor()) out.writeFloat(f);
			for (float f : a.point()) out.writeFloat(f);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bo.toByteArray();
	}

	// ------------------------------------------------------------------ actions

	/**
	 * Reads the body of an ACT after the header (and sender, if any) and validates every field.
	 */
	public static Act readAct(DataInputStream in) throws IOException {
		int op = in.readUnsignedByte();
		if (op >= OP_COUNT) throw new BadMessage("op");
		UUID target = readUuid(in);
		int bone = in.readInt();
		int hand = in.readUnsignedByte();
		if (hand > MAX_HAND) throw new BadMessage("hand");
		int flags = in.readUnsignedByte();
		int model = in.readInt();
		float[] a = readVec(in), p = readVec(in);
		return new Act(op, target, bone, hand, (flags & FLAG_KEEP) != 0, model, a, p);
	}

	private static float[] readVec(DataInputStream in) throws IOException {
		float[] v = new float[3];
		for (int i = 0; i < 3; i++) {
			float f = in.readFloat();
			if (!Float.isFinite(f) || Math.abs(f) > MAX_COORD) throw new BadMessage("coord");
			v[i] = f;
		}
		return v;
	}

	public static byte[] encodeCarry(Carry c, UUID from) {
		return write(out -> {
			out.writeByte(VERSION);
			out.writeByte(K_CARRY);
			if (from != null) writeUuid(out, from);
			out.writeByte(c.sub());
			writeUuid(out, c.target());
			out.writeDouble(c.x());
			out.writeDouble(c.y());
			out.writeDouble(c.z());
			out.writeFloat(c.vx());
			out.writeFloat(c.vy());
			out.writeFloat(c.vz());
			out.writeByte(c.hands());
		});
	}

	public static Carry readCarry(DataInputStream in) throws IOException {
		int sub = in.readUnsignedByte();
		if (sub >= CARRY_COUNT) throw new BadMessage("carry sub");
		UUID target = readUuid(in);
		double x = worldCoord(in), y = worldCoord(in), z = worldCoord(in);
		float vx = speed(in), vy = speed(in), vz = speed(in);
		int hands = in.readUnsignedByte();
		if (hands > MAX_HAND + 1) throw new BadMessage("hands");
		return new Carry(sub, target, x, y, z, vx, vy, vz, hands);
	}

	// ------------------------------------------------------------------ carry / look

	public static byte[] encodeLook(Look l, UUID from) {
		return write(out -> {
			out.writeByte(VERSION);
			out.writeByte(K_LOOK);
			if (from != null) writeUuid(out, from);
			out.writeByte(l.active() ? 1 : 0);
			writeUuid(out, l.target());
			out.writeDouble(l.x());
			out.writeDouble(l.y());
			out.writeDouble(l.z());
		});
	}

	public static Look readLook(DataInputStream in) throws IOException {
		int a = in.readUnsignedByte();
		if (a > 1) throw new BadMessage("look flag");
		UUID target = readUuid(in);
		return new Look(a == 1, target, worldCoord(in), worldCoord(in), worldCoord(in));
	}

	private static double worldCoord(DataInputStream in) throws IOException {
		double d = in.readDouble();
		if (!Double.isFinite(d) || Math.abs(d) > MAX_WORLD) throw new BadMessage("world coord");
		return d;
	}

	private static float speed(DataInputStream in) throws IOException {
		float f = in.readFloat();
		if (!Float.isFinite(f) || Math.abs(f) > MAX_THROW) throw new BadMessage("speed");
		return f;
	}

	public static byte[] encodeState(int state, UUID owner, byte[] body) {
		if (body.length > MAX_STATE_BODY) throw new IllegalArgumentException("state too big");
		ByteArrayOutputStream bo = new ByteArrayOutputStream(body.length + 24);
		try (DataOutputStream out = new DataOutputStream(bo)) {
			out.writeByte(VERSION);
			out.writeByte(K_STATE);
			out.writeByte(state);
			if (owner != null) writeUuid(out, owner);
			out.writeShort(body.length);
			out.write(body);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bo.toByteArray();
	}

	public static byte[] readStateBody(DataInputStream in) throws IOException {
		int len = in.readUnsignedShort();
		if (len > MAX_STATE_BODY) throw new BadMessage("state len");
		byte[] body = new byte[len];
		in.readFully(body);
		return body;
	}

	/**
	 * Parses a state body and writes it again; throws on anything malformed or out of bounds.
	 */
	public static byte[] canonicalState(int state, byte[] body) throws IOException {
		return switch (state) {
			case S_PERM -> encodePerm(decodePerm(body));
			case S_POSE -> encodePose(decodePose(body));
			case S_TYPES -> encodeTypes(decodeTypes(body));
			default -> throw new BadMessage("state kind");
		};
	}

	public static byte[] encodePerm(Perm p) {
		return write(out -> {
			out.writeByte(p.mode());
			out.writeInt(p.flags());
			writeNames(out, p.whitelist());
			writeNames(out, p.blacklist());
		});
	}

	// ------------------------------------------------------------------ states

	public static Perm decodePerm(byte[] body) throws IOException {
		DataInputStream in = in(body);
		int mode = in.readUnsignedByte();
		if (mode > 2) throw new BadMessage("mode");
		int flags = in.readInt();
		Perm p = new Perm(mode, flags, readNames(in), readNames(in));
		end(in);
		return p;
	}

	private static void writeNames(DataOutputStream out, List<String> names) throws IOException {
		int n = Math.min(names.size(), MAX_NAMES);
		out.writeByte(n);
		for (int i = 0; i < n; i++) writeString(out, names.get(i), MAX_NAME_LEN);
	}

	private static List<String> readNames(DataInputStream in) throws IOException {
		int n = in.readUnsignedByte();
		if (n > MAX_NAMES) throw new BadMessage("names");
		List<String> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(readString(in, MAX_NAME_LEN));
		return out;
	}

	// permissions --------------------------------------------------------

	/**
	 * bone id -> rotation vector in milliradians.
	 */
	public static byte[] encodePose(Map<Integer, int[]> pose) {
		return write(out -> {
			int n = Math.min(pose.size(), MAX_POSE);
			out.writeShort(n);
			int i = 0;
			for (Map.Entry<Integer, int[]> e : pose.entrySet()) {
				if (i++ >= n) break;
				out.writeInt(e.getKey());
				for (int c = 0; c < 3; c++) out.writeShort(clampMrad(e.getValue()[c]));
			}
		});
	}

	public static Map<Integer, int[]> decodePose(byte[] body) throws IOException {
		DataInputStream in = in(body);
		int n = in.readUnsignedShort();
		if (n > MAX_POSE) throw new BadMessage("pose size");
		Map<Integer, int[]> out = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			int id = in.readInt();
			int[] v = new int[3];
			for (int c = 0; c < 3; c++) {
				v[c] = in.readShort();
				if (Math.abs(v[c]) > MAX_POSE_MRAD) throw new BadMessage("pose angle");
			}
			out.put(id, v);
		}
		end(in);
		return out;
	}

	private static int clampMrad(int v) {
		return Math.max(-MAX_POSE_MRAD, Math.min(MAX_POSE_MRAD, v));
	}

	public static byte[] encodeTypes(Types t) {
		return write(out -> {
			writeString(out, t.modelKey(), MAX_KEY_LEN);
			int n = Math.min(t.types().size(), MAX_TYPES);
			out.writeShort(n);
			int i = 0;
			for (Map.Entry<Integer, Integer> e : t.types().entrySet()) {
				if (i++ >= n) break;
				out.writeInt(e.getKey());
				out.writeByte(e.getValue());
			}
		});
	}

	public static Types decodeTypes(byte[] body) throws IOException {
		DataInputStream in = in(body);
		String key = readString(in, MAX_KEY_LEN);
		int n = in.readUnsignedShort();
		if (n > MAX_TYPES) throw new BadMessage("types size");
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			int id = in.readInt();
			int type = in.readUnsignedByte();
			if (type >= 32) throw new BadMessage("type");
			out.put(id, type);
		}
		end(in);
		return new Types(key, out);
	}

	// pose ---------------------------------------------------------------

	/**
	 * s2c: you are in another world now, forget every grab.
	 */
	public static byte[] encodeReset() {
		return new byte[]{(byte) VERSION, (byte) K_RESET};
	}

	/**
	 * A hello in another version of this protocol: the one message every version can recognise.
	 */
	public static boolean isOtherVersionHello(byte[] data) {
		return data.length >= 2 && data[1] == K_HELLO && data[0] != VERSION;
	}

	/**
	 * Permissions as others see them: no name lists. Whitelist mode becomes "everyone" because
	 * the server already drops actions from players the owner did not list.
	 */
	public static byte[] redacted(Perm p) {
		int mode = p.mode() == 1 ? 0 : p.mode();
		return encodePerm(new Perm(mode, p.flags(), List.of(), List.of()));
	}

	public static byte[] encodeReq(UUID target) {
		return write(out -> {
			out.writeByte(VERSION);
			out.writeByte(K_REQ);
			writeUuid(out, target);
		});
	}

	/**
	 * The client's hello: just the header.
	 */
	public static byte[] encodeClientHello() {
		return new byte[]{(byte) VERSION, (byte) K_HELLO};
	}

	public static byte[] encodeHello(int range, int flags) {
		return write(out -> {
			out.writeByte(VERSION);
			out.writeByte(K_HELLO);
			out.writeShort(range);
			out.writeByte(flags);
		});
	}

	// part types ---------------------------------------------------------

	/**
	 * Header check: returns the kind, or -1 for another version or an empty message.
	 */
	public static int kind(DataInputStream in) throws IOException {
		if (in.available() < 2) return -1;
		int v = in.readUnsignedByte();
		int k = in.readUnsignedByte();
		return v == VERSION ? k : -1;
	}

	public static DataInputStream in(byte[] data) {
		return new DataInputStream(new ByteArrayInputStream(data));
	}

	/**
	 * Trailing bytes mean a message we don't understand.
	 */
	public static void end(DataInputStream in) throws IOException {
		if (in.available() != 0) throw new BadMessage("trailing bytes");
	}

	// ------------------------------------------------------------------ small messages

	public static void writeUuid(DataOutputStream out, UUID u) throws IOException {
		out.writeLong(u.getMostSignificantBits());
		out.writeLong(u.getLeastSignificantBits());
	}

	public static UUID readUuid(DataInputStream in) throws IOException {
		return new UUID(in.readLong(), in.readLong());
	}

	private static void writeString(DataOutputStream out, String s, int maxChars) throws IOException {
		if (s.length() > maxChars) s = s.substring(0, maxChars);
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		out.writeByte(b.length);
		out.write(b);
	}

	private static String readString(DataInputStream in, int maxChars) throws IOException {
		int len = in.readUnsignedByte();
		if (len > maxChars * 4) throw new BadMessage("string");
		byte[] b = new byte[len];
		in.readFully(b);
		String s = new String(b, StandardCharsets.UTF_8);
		if (s.length() > maxChars) throw new BadMessage("string");
		for (int i = 0; i < s.length(); i++) {
			if (Character.isISOControl(s.charAt(i))) throw new BadMessage("string chars");
		}
		return s;
	}

	// ------------------------------------------------------------------ helpers

	private static byte[] write(Body b) {
		ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
		try (DataOutputStream out = new DataOutputStream(bo)) {
			b.write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bo.toByteArray();
	}

	private interface Body {
		void write(DataOutputStream out) throws IOException;
	}

	public static final class BadMessage extends IOException {
		public BadMessage(String msg) {
			super(msg);
		}
	}

	public record Act(int op, UUID target, int bone, int hand, boolean keep, int model, float[] anchor, float[] point) {
		public Act(int op, UUID target, int bone, int hand, boolean keep, float[] anchor, float[] point) {
			this(op, target, bone, hand, keep, 0, anchor, point);
		}

		/**
		 * {@code model}: hash of the model key the bone number refers to; 0 when it doesn't matter.
		 */
		public Act withModel(int hash) {
			return new Act(op, target, bone, hand, keep, hash, anchor, point);
		}

		public Act {
			anchor = anchor == null ? new float[3] : anchor;
			point = point == null ? new float[3] : point;
		}

		public static Act simple(int op, UUID target) {
			return new Act(op, target, 0, 0, false, null, null);
		}
	}

	/**
	 * {@code hands}: how many of the sender's hands hold it (after a let-go: how many still do).
	 */
	public record Carry(int sub, UUID target, double x, double y, double z, float vx, float vy, float vz, int hands) {
	}

	public record Look(boolean active, UUID target, double x, double y, double z) {
	}

	/**
	 * mode: 0 everyone, 1 whitelist, 2 nobody. Names lower case.
	 */
	public record Perm(int mode, int flags, List<String> whitelist, List<String> blacklist) {
	}

	/**
	 * Types the owner picked for their own model: bone id -> part type ordinal.
	 */
	public record Types(String modelKey, Map<Integer, Integer> types) {
	}
}
