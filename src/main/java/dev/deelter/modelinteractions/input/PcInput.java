package dev.deelter.modelinteractions.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.interaction.*;
import dev.deelter.modelinteractions.model.Bone;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Mouse and keyboard. Hold right mouse (the use key) on a part and move the camera to pull
 * it; press Shift while holding to keep the new shape when you let go. Sneak + right mouse pets. Optional
 * dedicated keys do the same without taking over the use key.
 */
public final class PcInput {
	public static final int HAND = 0;
	private static final float AIM_MAX_YAW = 45f, AIM_MAX_PITCH = 35f;
	private static KeyMapping grab, pet, hug, reset, pinSelf, freeMode, keep;
	private static boolean grabWasDown, shiftWasDown;
	private static long shiftDownAt;
	private static boolean shiftTapValid;
	/**
	 * The current grab / pet was started with the use key and lasts while it is held.
	 */
	private static boolean useGrabbing, usePetting;
	private static float holdDistance;
	/**
	 * Where the wheel wants the held point; {@link #holdDistance} follows it as fast as the weight lets it.
	 */
	private static float holdTarget;
	/**
	 * While holding with the mouse the camera turns slower than the mouse, so your view doesn't
	 * swing far off; the grabbed point follows the mouse at full speed, up to an angle away
	 * from the middle of the screen. Offsets from the camera, degrees.
	 */
	private static float aimYaw, aimPitch;
	private static boolean aiming;
	private static long lastKeepToggle;
	private static long lastFrameNanos;

	private PcInput() {
	}

	public static void register() {
		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ModelInteractions.MOD_ID, "main"));
		// right mouse does both by default; H would clash with CPM's gesture menu
		grab = key("grab", InputConstants.UNKNOWN.getValue(), cat);
		pet = key("pet", InputConstants.UNKNOWN.getValue(), cat);
		hug = key("hug", InputConstants.KEY_J, cat);
		reset = key("reset", InputConstants.KEY_K, cat);
		pinSelf = key("pin_self", InputConstants.UNKNOWN.getValue(), cat);
		freeMode = key("free_mode", InputConstants.UNKNOWN.getValue(), cat);
		// bind to the grip / second button in Vivecraft's controller bindings
		keep = key("keep", InputConstants.UNKNOWN.getValue(), cat);
	}

	private static KeyMapping key(String name, int code, KeyMapping.Category cat) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.modelinteractions." + name, code, cat));
	}

	/**
	 * Called from {@code Minecraft.startUseItem}. Returns true to swallow the vanilla use.
	 * <p>
	 * The click that starts a grab still reaches the entity, so server NPCs (FancyNpcs and
	 * the like) and anything else listening for a click on that entity get it. Only the
	 * repeats while the button is held are swallowed, and a click that would land on a block
	 * behind the model (a door, a chest) is swallowed too.
	 */
	public static boolean onUseKey(Minecraft mc) {
		if (useGrabbing || usePetting) return true;
		// VR: while a hand holds a part, the other button of a controller marks "keep this shape"
		if (vrHolding()) {
			toggleKeep(mc);
			return true;
		}
		boolean started = startUse(mc);
		return started && !aimsAtGrabbed(mc);
	}

	/**
	 * The vanilla crosshair is on the very entity we just took hold of.
	 */
	private static boolean aimsAtGrabbed(Minecraft mc) {
		if (!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh)) return false;
		Grab g = InteractionManager.get().localGrab(HAND);
		if (g != null) return eh.getEntity().getUUID().equals(g.getTarget());
		return usePetting;
	}

	private static boolean startUse(Minecraft mc) {
		if (!MiConfig.get().isEnabled() || !MiConfig.get().getInput().isUseKeyGrab() || mc.player == null) return false;
		InteractionManager mgr = InteractionManager.get();
		if (mc.player.isShiftKeyDown()) {
			PartHit hit = pick(mc, 1f);
			if (hit == null || !useKeyMayGrab(mc, hit) || !mgr.pet(hit.rig(), hit)) return false;
			usePetting = true;
			return true;
		}
		PartHit hit = pick(mc, 1f, b -> true);
		if (hit == null || !useKeyMayGrab(mc, hit) || mgr.localGrab(HAND) != null || mgr.startLocalGrab(HAND, hit) == null)
			return false;
		holdDistance = holdTarget = hit.distance();
		useGrabbing = true;
		return true;
	}

	/**
	 * Right mouse only grabs with an empty hand, so eating, placing or using an item while
	 * looking at someone still works, and never on mobs that do something with an empty hand
	 * (see {@link #vanillaHandUseWins}). The grab key works regardless.
	 */
	private static boolean useKeyMayGrab(Minecraft mc, PartHit hit) {
		if (!mc.player.getMainHandItem().isEmpty()) return false;
		return !vanillaHandUseWins(mc, InteractionManager.entityOf(mc.level, hit.rig()));
	}

	/**
	 * Trading, riding, sitting your own pet, handing items: these keep the hand.
	 */
	public static boolean vanillaHandUseWins(Minecraft mc, Entity e) {
		if (e == null || e instanceof Player) return false;
		if (e instanceof Npc || e instanceof AbstractHorse || e instanceof InventoryCarrier) return true;
		return e instanceof OwnableEntity o && o.getOwnerReference() != null && mc.player != null
				&& mc.player.getUUID().equals(o.getOwnerReference().getUUID());
	}

	/**
	 * Called when the attack key starts an attack: an empty hand on a part knocks it. Returns
	 * true when the vanilla attack must not go on: it would hit the block behind the model.
	 * A hit on the entity itself (PvP) is left alone.
	 */
	public static boolean onAttack(Minecraft mc) {
		if (vrHolding()) {
			toggleKeep(mc);
			return true;
		}
		if (!MiConfig.get().isEnabled() || mc.player == null || !mc.player.getMainHandItem().isEmpty()) return false;
		if (InteractionManager.get().localGrab(HAND) != null) return false;
		PartHit hit = pick(mc, 1f);
		if (hit == null) return false;
		Vec3 look = mc.player.getViewVector(1f);
		InteractionManager.get().punch(hit, new Vector3f((float) look.x, (float) look.y, (float) look.z));
		return mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK;
	}

	/**
	 * Holding attack while the crosshair passes a model on its way to a block: don't dig that block.
	 */
	public static boolean modelInFrontOfBlock(Minecraft mc) {
		if (!MiConfig.get().isEnabled() || mc.player == null || !mc.player.getMainHandItem().isEmpty()) return false;
		if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return false;
		return pick(mc, 1f) != null;
	}

	public static void tick(Minecraft mc) {
		InteractionManager mgr = InteractionManager.get();
		boolean useDown = mc.options.keyUse.isDown() && mc.gui.screen() == null;
		// a quick tap of Shift while holding: "keep this shape when I let go", tap again to undo.
		// Holding Shift is just sneaking and changes nothing
		boolean shift = mc.options.keyShift.isDown() && mc.gui.screen() == null;
		long nowMs = System.currentTimeMillis();
		if (shift && !shiftWasDown) {
			shiftDownAt = nowMs;
			shiftTapValid = mgr.localGrab(HAND) != null;
		}
		if (!shift && shiftWasDown && shiftTapValid && nowMs - shiftDownAt <= 300 && mgr.localGrab(HAND) != null)
			toggleKeep(mc);
		if (mgr.localGrab(HAND) == null) shiftTapValid = false;
		shiftWasDown = shift;
		// Shift pressed during the hold and still down when letting go: the tap never got to end, it counts
		boolean keepNow = shift && shiftTapValid;
		if (useGrabbing && (!useDown || mgr.localGrab(HAND) == null)) {
			useGrabbing = false;
			mgr.releaseLocal(HAND, keepNow);
		}
		if (usePetting) {
			PartHit hit = useDown && mc.player.isShiftKeyDown() ? pick(mc, 1f) : null;
			if (hit == null) usePetting = false;
			else mgr.pet(hit.rig(), hit);
		}

		boolean down = grab.isDown() && mc.gui.screen() == null;
		if (down && !grabWasDown && !useGrabbing) {
			PartHit hit = pick(mc, 1f, b -> true);
			if (hit != null && mgr.startLocalGrab(HAND, hit) != null) holdDistance = holdTarget = hit.distance();
		} else if (!down && grabWasDown && !useGrabbing) {
			mgr.releaseLocal(HAND, keepNow);
		}
		grabWasDown = down;

		if (pet.isDown() && mc.gui.screen() == null) {
			PartHit hit = pick(mc, 1f);
			if (hit != null) mgr.pet(hit.rig(), hit);
		}
		while (hug.consumeClick()) {
			PartHit hit = pick(mc, 1f);
			if (hit != null && hit.distance() < 2.2f) mgr.hug(hit.rig().getUuid());
		}
		while (reset.consumeClick()) {
			mgr.resetSelf();
			mc.player.sendOverlayMessage(Component.translatable("modelinteractions.msg.reset"));
		}
		while (freeMode.consumeClick()) toggleFreeMode(mc);
		while (keep.consumeClick()) toggleKeep(mc);
		while (pinSelf.consumeClick()) {
			mgr.pinSelf();
			mc.player.sendOverlayMessage(Component.translatable("modelinteractions.msg.pinned"));
		}

		hint(mc, mgr);

		if (MiConfig.get().isDebugOverlay() && mgr.localGrab(HAND) == null) {
			PartHit hit = pick(mc, 1f);
			if (hit != null) {
				mc.player.sendOverlayMessage(Component.literal("#" + hit.bone().getId() + " " + hit.bone().getType()
						+ " " + hit.bone().getRegion() + " len=" + Math.round(hit.bone().getLengthPx()) + "px model=" + hit.rig().getModelKey()));
			}
		}
	}

	/**
	 * The first time ever the crosshair lands on a model with an empty hand, say what the
	 * mouse does there. Nothing else on screen tells that a model can be grabbed.
	 */
	private static void hint(Minecraft mc, InteractionManager mgr) {
		if (MiConfig.get().isHintSeen() || mgr.localGrab(HAND) != null || MiConfig.get().isDebugOverlay())
			return;
		if (!mc.player.getMainHandItem().isEmpty() || mc.gui.screen() != null) return;
		PartHit hit = pick(mc, 1f);
		if (hit == null || !mgr.canGrab(hit) || !useKeyMayGrab(mc, hit)) return;
		MiConfig.get().setHintSeen(true);
		MiConfig.save();
		mc.player.sendOverlayMessage(Component.translatable("modelinteractions.msg.hint"));
	}

	/**
	 * Mouse wheel while holding a part: wheel down pulls it towards you, wheel up pushes it
	 * away. Returns true to keep the wheel from switching hotbar slots meanwhile.
	 */
	public static boolean onScroll(double amount) {
		Grab g = InteractionManager.get().localGrab(HAND);
		if (g == null) return false;
		float max = MiConfig.get().getInput().getPcReach() + 1.5f;
		// a notch moves a light part 15cm, a heavy body much less
		float c = InteractionManager.get().complianceOf(g);
		holdTarget = Math.clamp(holdTarget + (float) amount * 0.15f * (0.3f + 0.7f * c), 0.3f, max);
		return true;
	}

	private static boolean vrHolding() {
		InteractionManager m = InteractionManager.get();
		return m.localGrab(1) != null || m.localGrab(2) != null;
	}

	/**
	 * Flips "keep this shape when I let go" on everything we hold.
	 */
	public static void toggleKeep(Minecraft mc) {
		// one button press can reach us twice in VR (the grab module and the vanilla key)
		long now = System.nanoTime();
		if (now - lastKeepToggle < 250_000_000L) return;
		lastKeepToggle = now;
		InteractionManager m = InteractionManager.get();
		Boolean to = null;
		for (int h = 0; h <= 2; h++) {
			Grab g = m.localGrab(h);
			if (g == null) continue;
			if (to == null) to = !g.isKeepOnRelease();
			g.setKeepOnRelease(to);
		}
		if (to != null && mc.player != null) {
			mc.player.sendOverlayMessage(Component.translatable(to ? "modelinteractions.msg.keep_on" : "modelinteractions.msg.keep_off"));
		}
	}

	public static boolean toggleFreeMode(Minecraft mc) {
		MiConfig.Permissions p = MiConfig.get().getPermissions();
		p.setFreeMode(!p.isFreeMode());
		MiConfig.save(); // republishes permissions, so everyone around learns it
		if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable(
				p.isFreeMode() ? "modelinteractions.msg.free_on" : "modelinteractions.msg.free_off"));
		return p.isFreeMode();
	}

	/**
	 * Camera share of a mouse turn while holding: light parts 80%, heavy bodies 45%.
	 */
	private static double cameraShare() {
		Grab g = InteractionManager.get().localGrab(HAND);
		return g == null ? 1 : 0.45 + 0.35 * InteractionManager.get().complianceOf(g);
	}

	/**
	 * Called with each mouse turn of the local player (degrees / 0.15, as vanilla passes them).
	 * Returns what the camera itself turns; the rest goes into the aim of the held point.
	 */
	public static double onTurn(double delta, boolean yaw) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || InteractionManager.get().localGrab(HAND) == null || vrHolding()) return delta;
		aiming = true;
		double share = cameraShare();
		// the camera takes its share; the rest moves the held point away from the middle of the screen
		float rest = (float) (delta * 0.15 * (1 - share));
		if (yaw) aimYaw = net.minecraft.util.Mth.clamp(aimYaw + rest, -AIM_MAX_YAW, AIM_MAX_YAW);
		else aimPitch = net.minecraft.util.Mth.clamp(aimPitch + rest, -AIM_MAX_PITCH, AIM_MAX_PITCH);
		return delta * share;
	}

	/**
	 * Per-frame: drag the held point with the camera.
	 */
	public static void frame(Minecraft mc, float partial) {
		InteractionManager mgr = InteractionManager.get();
		Grab g = mgr.localGrab(HAND);
		if (g == null) {
			lastFrameNanos = 0;
			aiming = false;
			aimYaw = aimPitch = 0;
			dev.deelter.modelinteractions.interaction.CarryClient.clearPcHand();
			return;
		}
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0 : Math.min((now - lastFrameNanos) / 1e9f, 0.1f);
		lastFrameNanos = now;
		// heavy things come along slowly: up to 4 blocks/s for light parts, ~1 for a body
		float speed = 0.6f + 3.4f * mgr.complianceOf(g);
		float step = speed * dt;
		holdDistance += Math.clamp(holdTarget - holdDistance, -step, step);
		Vec3 eye = mc.player.getEyePosition(partial);
		Vec3 look = aiming
				? Vec3.directionFromRotation(net.minecraft.util.Mth.clamp(mc.player.getViewXRot(partial) + aimPitch, -90f, 90f), mc.player.getViewYRot(partial) + aimYaw)
				: mc.player.getViewVector(partial);
		Vector3f point = new Vector3f(
				(float) (eye.x + look.x * holdDistance),
				(float) (eye.y + look.y * holdDistance),
				(float) (eye.z + look.z * holdDistance));
		mgr.updateLocalGrab(HAND, point);
		// the mouse grab counts as one hand for lifting together
		dev.deelter.modelinteractions.interaction.CarryClient.setPcHand(point, dt);
	}

	public static PartHit pick(Minecraft mc, float partial) {
		return pick(mc, partial, b -> true);
	}

	public static PartHit pick(Minecraft mc, float partial, Predicate<Bone> filter) {
		Vec3 eye = mc.player.getEyePosition(partial);
		Vec3 look = mc.player.getViewVector(partial);
		Vector3f o = new Vector3f((float) eye.x, (float) eye.y, (float) eye.z);
		Vector3f d = new Vector3f((float) look.x, (float) look.y, (float) look.z);
		float reach = MiConfig.get().getInput().getPcReach();
		// nothing through walls: stop at the block the crosshair is on
		if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			reach = Math.min(reach, (float) bh.getLocation().distanceTo(eye));
		}
		UUID self = mc.player.getUUID();
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		PartHit best = null;
		float near = (reach + 6) * (reach + 6);
		for (PlayerRig rig : InteractionManager.get().rigs()) {
			if (firstPerson && rig.getUuid().equals(self)) continue;
			if (!rig.isWorldValid() || rig.getBaseModelToWorld().getTranslation(new Vector3f()).distanceSquared(o) > near)
				continue;
			PartHit h = HitTester.raycast(rig, o, d, reach, filter);
			if (h != null && (best == null || h.distance() < best.distance())) best = h;
		}
		return best;
	}
}
