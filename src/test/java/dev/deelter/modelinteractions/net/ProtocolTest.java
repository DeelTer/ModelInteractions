package dev.deelter.modelinteractions.net;

import dev.deelter.modelinteractions.config.PermissionMode;
import dev.deelter.modelinteractions.interaction.InteractionKind;
import dev.deelter.modelinteractions.interaction.PermissionState;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.wire.Wire;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
	private static final UUID T = UUID.randomUUID(), S = UUID.randomUUID();

	private static void readAct(byte[] m) throws IOException {
		DataInputStream in = Wire.in(m);
		Wire.kind(in);
		Wire.readAct(in);
		Wire.end(in);
	}

	@Test
	void actRoundTripWithServerStampedSender() throws IOException {
		Wire.Act a = Protocol.grab(T, 42, 2, new Vector3f(0.1f, -0.2f, 0.3f), new Vector3f(1, 2, 3));
		byte[] c2s = Wire.encodeAct(a, null);
		assertEquals(Wire.ACT_C2S_SIZE, c2s.length);
		byte[] s2c = Wire.encodeAct(a, S);
		assertEquals(Wire.ACT_S2C_SIZE, s2c.length);

		DataInputStream in = Wire.in(s2c);
		assertEquals(Wire.K_ACT, Wire.kind(in));
		assertEquals(S, Wire.readUuid(in));
		Wire.Act b = Wire.readAct(in);
		Wire.end(in);
		assertEquals(Wire.OP_GRAB, b.op());
		assertEquals(T, b.target());
		assertEquals(42, b.bone());
		assertEquals(2, b.hand());
		assertArrayEquals(new float[]{1, 2, 3}, b.point());
	}

	@Test
	void outOfRangePointsAreClampedBeforeSending() {
		Wire.Act a = Protocol.move(T, 1, 0, new Vector3f(), new Vector3f(1e9f, Float.NaN, -1e9f));
		assertArrayEquals(new float[]{Wire.MAX_COORD, 0, -Wire.MAX_COORD}, a.point());
	}

	@Test
	void malformedActsAreRejected() {
		// bad op
		byte[] m = Wire.encodeAct(Protocol.act(Wire.OP_PET, T), null);
		m[2] = (byte) 99;
		assertThrows(IOException.class, () -> readAct(m));
		// bad hand
		byte[] h = Wire.encodeAct(Protocol.release(T, 1, 0, false), null);
		h[2 + 1 + 16 + 4] = 7;
		assertThrows(IOException.class, () -> readAct(h));
		// NaN coordinate written raw
		byte[] n = Wire.encodeAct(Protocol.move(T, 1, 0, new Vector3f(), new Vector3f()), null);
		int nanBits = Float.floatToIntBits(Float.NaN);
		int off = Wire.ACT_C2S_SIZE - 4;
		n[off] = (byte) (nanBits >>> 24);
		n[off + 1] = (byte) (nanBits >>> 16);
		n[off + 2] = (byte) (nanBits >>> 8);
		n[off + 3] = (byte) nanBits;
		assertThrows(IOException.class, () -> readAct(n));
		// truncated
		byte[] t = new byte[10];
		t[0] = Wire.VERSION;
		t[1] = Wire.K_ACT;
		assertThrows(IOException.class, () -> readAct(t));
	}

	@Test
	void otherVersionsAreIgnored() throws IOException {
		byte[] m = Wire.encodeHello(16, 0);
		m[0] = 1;
		assertEquals(-1, Wire.kind(Wire.in(m)));
	}

	@Test
	void otherVersionsOfHelloAreRecognised() {
		assertTrue(Wire.isOtherVersionHello(new byte[]{(byte) (Wire.VERSION + 1), Wire.K_HELLO}));
		assertEquals(false, Wire.isOtherVersionHello(Wire.encodeClientHello()));
		assertEquals(false, Wire.isOtherVersionHello(Wire.encodeReset()));
	}

	@Test
	void permRoundTrip() throws IOException {
		PermissionState s = new PermissionState(PermissionMode.WHITELIST,
				InteractionKind.GRAB_EARS.bit() | InteractionKind.FREE_MODE.bit(), Set.of("bob", "alice"), Set.of("eve"));
		PermissionState r = Protocol.readPerm(Protocol.perm(s));
		assertEquals(s.mode(), r.mode());
		assertEquals(s.flags(), r.flags());
		assertEquals(s.whitelist(), r.whitelist());
		assertEquals(s.blacklist(), r.blacklist());
		assertTrue(r.freeMode());
		assertTrue(r.allows("bob", InteractionKind.GRAB_EARS));
	}

	@Test
	void permWithControlCharactersOrTooManyNamesIsRejected() throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bo);
		out.writeByte(0);
		out.writeInt(0);
		out.writeByte(1);
		out.writeByte(3);
		out.write(new byte[]{'a', 7, 'b'});
		out.writeByte(0);
		assertThrows(IOException.class, () -> Wire.decodePerm(bo.toByteArray()));

		ByteArrayOutputStream bo2 = new ByteArrayOutputStream();
		DataOutputStream out2 = new DataOutputStream(bo2);
		out2.writeByte(0);
		out2.writeInt(0);
		out2.writeByte(200);
		assertThrows(IOException.class, () -> Wire.decodePerm(bo2.toByteArray()));
	}

	@Test
	void poseRoundTripIncludesTorso() throws IOException {
		Map<Integer, Vector3f> pose = new HashMap<>();
		pose.put(5, new Vector3f(0.1f, -0.25f, 1.5f));
		pose.put(Wire.BODY_BONE, new Vector3f(0, 3.0f, 0));
		Map<Integer, Vector3f> back = Protocol.readPose(Protocol.pose(pose));
		assertEquals(2, back.size());
		assertEquals(-0.25f, back.get(5).y, 1e-3f);
		assertEquals(3.0f, back.get(Wire.BODY_BONE).y, 1e-3f);
	}

	@Test
	void poseAnglesBeyondLimitAreRejected() throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bo);
		out.writeShort(1);
		out.writeInt(1);
		out.writeShort(30000);
		out.writeShort(0);
		out.writeShort(0);
		assertThrows(IOException.class, () -> Wire.decodePose(bo.toByteArray()));
	}

	@Test
	void typesRoundTrip() throws IOException {
		Protocol.OwnerTypes t = Protocol.readTypes(Protocol.types("abc123", Map.of(3, PartType.JELLY, 7, PartType.TAIL)));
		assertEquals("abc123", t.modelKey());
		assertEquals(PartType.JELLY, t.types().get(3));
		assertEquals(PartType.TAIL, t.types().get(7));
	}

	@Test
	void canonicalStateDropsTrailingGarbage() {
		byte[] body = Protocol.types("k", Map.of(1, PartType.EAR));
		byte[] junk = new byte[body.length + 3];
		System.arraycopy(body, 0, junk, 0, body.length);
		assertThrows(IOException.class, () -> Wire.canonicalState(Wire.S_TYPES, junk));
	}

	@Test
	void serverReadsTheSamePermissionBitsAsTheMod() {
		assertEquals(InteractionKind.CARRY.bit(), Wire.FLAG_CARRY);
		assertEquals(InteractionKind.LOOK.bit(), Wire.FLAG_LOOK);
		assertEquals(InteractionKind.DRAG.bit(), Wire.FLAG_DRAG);
	}

	@Test
	void carryAndLookRoundTrip() throws IOException {
		byte[] m = Wire.encodeCarry(new Wire.Carry(Wire.CARRY_THROW, T, 100.5, 64, -2000.25, 0.5f, 0.3f, -0.1f, 1), S);
		DataInputStream in = Wire.in(m);
		assertEquals(Wire.K_CARRY, Wire.kind(in));
		assertEquals(S, Wire.readUuid(in));
		Wire.Carry c = Wire.readCarry(in);
		Wire.end(in);
		assertEquals(Wire.CARRY_THROW, c.sub());
		assertEquals(-2000.25, c.z(), 1e-9);
		assertEquals(Wire.CARRY_C2S_SIZE, Wire.encodeCarry(c, null).length);

		byte[] l = Wire.encodeLook(new Wire.Look(true, T, 1, 2, 3), null);
		assertEquals(Wire.LOOK_C2S_SIZE, l.length);
		DataInputStream li = Wire.in(l);
		Wire.kind(li);
		assertTrue(Wire.readLook(li).active());
	}

	@Test
	void absurdThrowsAndPositionsAreRejected() {
		byte[] fast = Wire.encodeCarry(new Wire.Carry(Wire.CARRY_THROW, T, 0, 0, 0, 50f, 0, 0, 1), null);
		assertThrows(IOException.class, () -> {
			DataInputStream in = Wire.in(fast);
			Wire.kind(in);
			Wire.readCarry(in);
		});
		byte[] far = Wire.encodeLook(new Wire.Look(true, T, Double.NaN, 0, 0), null);
		assertThrows(IOException.class, () -> {
			DataInputStream in = Wire.in(far);
			Wire.kind(in);
			Wire.readLook(in);
		});
	}

	@Test
	void stateEnvelopeRoundTrip() throws IOException {
		byte[] body = Wire.encodePerm(new Wire.Perm(0, 5, List.of(), List.of()));
		byte[] m = Wire.encodeState(Wire.S_PERM, S, body);
		DataInputStream in = Wire.in(m);
		assertEquals(Wire.K_STATE, Wire.kind(in));
		assertEquals(Wire.S_PERM, in.readUnsignedByte());
		assertEquals(S, Wire.readUuid(in));
		assertArrayEquals(body, Wire.readStateBody(in));
	}
}
