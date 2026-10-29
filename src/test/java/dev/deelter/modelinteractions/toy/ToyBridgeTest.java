package dev.deelter.modelinteractions.toy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToyBridgeTest {
	@Test
	void onlyTalksToThisComputer() {
		assertTrue(ToyBridge.isLocal("http://127.0.0.1:20010/command"));
		assertTrue(ToyBridge.isLocal("http://localhost:20010/command"));
		assertTrue(ToyBridge.isLocal("https://127-0-0-1.lovense.club:30010/command"));
		assertFalse(ToyBridge.isLocal("http://192.168.1.5:20010/command"));
		assertFalse(ToyBridge.isLocal("https://evil.example/command"));
		assertFalse(ToyBridge.isLocal("https://127-0-0-1.lovense.club.evil.example/command"));
		assertFalse(ToyBridge.isLocal("not a url"));
	}
}
