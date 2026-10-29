package dev.deelter.modelinteractions.config;

import java.util.ArrayList;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Optional Cloth Config screen; the JSON file works without it. */
public final class ClothConfigScreen {
	private ClothConfigScreen() {}

	private static Component t(String key) {
		return Component.translatable("modelinteractions.config." + key);
	}

	public static Screen create(Screen parent) {
		MiConfig c = MiConfig.get();
		MiConfig d = new MiConfig();
		ConfigBuilder b = ConfigBuilder.create().setParentScreen(parent).setTitle(t("title")).setSavingRunnable(MiConfig::save);
		ConfigEntryBuilder e = b.entryBuilder();

		ConfigCategory general = b.getOrCreateCategory(t("general"));
		general.addEntry(e.startBooleanToggle(t("enabled"), c.isEnabled()).setDefaultValue(d.isEnabled()).setSaveConsumer(c::setEnabled).build());
		general.addEntry(e.startBooleanToggle(t("particles"), c.isParticles()).setDefaultValue(d.isParticles()).setSaveConsumer(c::setParticles).build());
		general.addEntry(e.startBooleanToggle(t("mobs"), c.isMobs()).setDefaultValue(d.isMobs()).setSaveConsumer(c::setMobs).build());
		general.addEntry(e.startBooleanToggle(t("localWhenDenied"), c.isLocalWhenDenied()).setDefaultValue(d.isLocalWhenDenied()).setTooltip(t("localWhenDenied.tooltip")).setSaveConsumer(c::setLocalWhenDenied).build());
		general.addEntry(e.startBooleanToggle(t("deformLayers"), c.isDeformLayers()).setDefaultValue(d.isDeformLayers()).setSaveConsumer(c::setDeformLayers).build());
		general.addEntry(e.startBooleanToggle(t("hints"), c.isHints()).setDefaultValue(d.isHints()).setSaveConsumer(c::setHints).build());
		general.addEntry(e.startBooleanToggle(t("sounds"), c.isSounds()).setDefaultValue(d.isSounds()).setSaveConsumer(c::setSounds).build());
		general.addEntry(e.startBooleanToggle(t("vrGestures"), c.isVrGestures()).setDefaultValue(d.isVrGestures()).setTooltip(t("vrGestures.tooltip")).setSaveConsumer(c::setVrGestures).build());
		general.addEntry(e.startBooleanToggle(t("carryHints"), c.isCarryHints()).setDefaultValue(d.isCarryHints()).setSaveConsumer(c::setCarryHints).build());
		general.addEntry(e.startBooleanToggle(t("hoverHaptics"), c.isHoverHaptics()).setDefaultValue(d.isHoverHaptics()).setSaveConsumer(c::setHoverHaptics).build());
		general.addEntry(e.startBooleanToggle(t("haptics"), c.isHaptics()).setDefaultValue(d.isHaptics()).setSaveConsumer(c::setHaptics).build());
		general.addEntry(e.startBooleanToggle(t("interactWithoutMod"), c.isInteractWithoutMod()).setDefaultValue(d.isInteractWithoutMod())
				.setTooltip(t("interactWithoutMod.tooltip")).setSaveConsumer(c::setInteractWithoutMod).build());
		general.addEntry(e.startBooleanToggle(t("deformInGui"), c.isDeformInGui()).setDefaultValue(d.isDeformInGui()).setSaveConsumer(c::setDeformInGui).build());
		general.addEntry(e.startBooleanToggle(t("debugOverlay"), c.isDebugOverlay()).setDefaultValue(d.isDebugOverlay())
				.setTooltip(t("debugOverlay.tooltip")).setSaveConsumer(c::setDebugOverlay).build());

		MiConfig.Physics p = c.getPhysics(), dp = d.getPhysics();
		ConfigCategory phys = b.getOrCreateCategory(t("physics"));
		phys.addEntry(e.startFloatField(t("stiffness"), p.getStiffness()).setDefaultValue(dp.getStiffness()).setMin(0.1f).setMax(5f).setSaveConsumer(p::setStiffness).build());
		phys.addEntry(e.startFloatField(t("damping"), p.getDamping()).setDefaultValue(dp.getDamping()).setMin(0.1f).setMax(5f).setSaveConsumer(p::setDamping).build());
		phys.addEntry(e.startBooleanToggle(t("bodyMotion"), p.isBodyMotion()).setDefaultValue(dp.isBodyMotion()).setSaveConsumer(p::setBodyMotion).build());
		phys.addEntry(e.startBooleanToggle(t("touch"), p.isTouch()).setDefaultValue(dp.isTouch()).setSaveConsumer(p::setTouch).build());
		phys.addEntry(e.startFloatField(t("touchStrength"), p.getTouchStrength()).setDefaultValue(dp.getTouchStrength()).setMin(0f).setMax(5f).setSaveConsumer(p::setTouchStrength).build());
		phys.addEntry(e.startFloatField(t("returnSmoothing"), p.getReturnSmoothing()).setDefaultValue(dp.getReturnSmoothing()).setMin(0f).setMax(1f).setTooltip(t("returnSmoothing.tooltip")).setSaveConsumer(p::setReturnSmoothing).build());
		phys.addEntry(e.startFloatField(t("idleSway"), p.getIdleSway()).setDefaultValue(dp.getIdleSway()).setMin(0f).setMax(0.2f).setTooltip(t("idleSway.tooltip")).setSaveConsumer(p::setIdleSway).build());

		MiConfig.Input in = c.getInput(), din = d.getInput();
		ConfigCategory input = b.getOrCreateCategory(t("input"));
		input.addEntry(e.startFloatField(t("pcReach"), in.getPcReach()).setDefaultValue(din.getPcReach()).setMin(1f).setMax(6f).setSaveConsumer(in::setPcReach).build());
		input.addEntry(e.startFloatField(t("vrGrabRadius"), in.getVrGrabRadius()).setDefaultValue(din.getVrGrabRadius()).setMin(0.02f).setMax(0.4f).setSaveConsumer(in::setVrGrabRadius).build());
		input.addEntry(e.startBooleanToggle(t("useKeyGrab"), in.isUseKeyGrab()).setDefaultValue(din.isUseKeyGrab()).setTooltip(t("useKeyGrab.tooltip")).setSaveConsumer(in::setUseKeyGrab).build());
		input.addEntry(e.startIntSlider(t("syncHz"), in.getSyncHz(), 2, 30).setDefaultValue(din.getSyncHz()).setSaveConsumer(in::setSyncHz).build());

		MiConfig.Permissions pr = c.getPermissions(), dpr = d.getPermissions();
		ConfigCategory perm = b.getOrCreateCategory(t("permissions"));
		perm.addEntry(e.startEnumSelector(t("mode"), PermissionMode.class, pr.getMode()).setDefaultValue(dpr.getMode())
				.setEnumNameProvider(m -> t("mode." + m.name().toLowerCase())).setSaveConsumer(pr::setMode).build());
		perm.addEntry(e.startStrList(t("whitelist"), pr.getWhitelist()).setDefaultValue(new ArrayList<>()).setSaveConsumer(pr::setWhitelist).build());
		perm.addEntry(e.startStrList(t("blacklist"), pr.getBlacklist()).setDefaultValue(new ArrayList<>()).setSaveConsumer(pr::setBlacklist).build());
		perm.addEntry(e.startBooleanToggle(t("allow.ears"), pr.isEars()).setDefaultValue(dpr.isEars()).setSaveConsumer(pr::setEars).build());
		perm.addEntry(e.startBooleanToggle(t("allow.tail"), pr.isTail()).setDefaultValue(dpr.isTail()).setSaveConsumer(pr::setTail).build());
		perm.addEntry(e.startBooleanToggle(t("allow.hair"), pr.isHair()).setDefaultValue(dpr.isHair()).setSaveConsumer(pr::setHair).build());
		perm.addEntry(e.startBooleanToggle(t("allow.limbs"), pr.isLimbs()).setDefaultValue(dpr.isLimbs()).setSaveConsumer(pr::setLimbs).build());
		perm.addEntry(e.startBooleanToggle(t("allow.head"), pr.isHead()).setDefaultValue(dpr.isHead()).setSaveConsumer(pr::setHead).build());
		perm.addEntry(e.startBooleanToggle(t("allow.other"), pr.isOther()).setDefaultValue(dpr.isOther()).setSaveConsumer(pr::setOther).build());
		perm.addEntry(e.startBooleanToggle(t("allow.pet"), pr.isPet()).setDefaultValue(dpr.isPet()).setSaveConsumer(pr::setPet).build());
		perm.addEntry(e.startBooleanToggle(t("allow.hug"), pr.isHug()).setDefaultValue(dpr.isHug()).setSaveConsumer(pr::setHug).build());
		perm.addEntry(e.startBooleanToggle(t("freeMode"), pr.isFreeMode()).setDefaultValue(dpr.isFreeMode()).setTooltip(t("freeMode.tooltip")).setSaveConsumer(pr::setFreeMode).build());
		perm.addEntry(e.startBooleanToggle(t("allow.carryMe"), pr.isCarryMe()).setDefaultValue(dpr.isCarryMe()).setTooltip(t("allow.carryMe.tooltip")).setSaveConsumer(pr::setCarryMe).build());
		perm.addEntry(e.startBooleanToggle(t("allow.dragMe"), pr.isDragMe()).setDefaultValue(dpr.isDragMe()).setTooltip(t("allow.dragMe.tooltip")).setSaveConsumer(pr::setDragMe).build());
		perm.addEntry(e.startBooleanToggle(t("allow.carryMeInVr"), pr.isCarryMeInVr()).setDefaultValue(dpr.isCarryMeInVr()).setTooltip(t("allow.carryMeInVr.tooltip")).setSaveConsumer(pr::setCarryMeInVr).build());
		perm.addEntry(e.startEnumSelector(t("allow.lookMode"), dev.deelter.modelinteractions.config.LookMode.class, pr.getLookMode()).setDefaultValue(dpr.getLookMode())
				.setEnumNameProvider(m -> t("lookMode." + m.name().toLowerCase(java.util.Locale.ROOT))).setTooltip(t("allow.lookMode.tooltip")).setSaveConsumer(pr::setLookMode).build());
		perm.addEntry(e.startBooleanToggle(t("allow.turnBody"), pr.isTurnBody()).setDefaultValue(dpr.isTurnBody()).setSaveConsumer(pr::setTurnBody).build());
		perm.addEntry(e.startBooleanToggle(t("allow.body"), pr.isBody()).setDefaultValue(dpr.isBody()).setTooltip(t("allow.body.tooltip")).setSaveConsumer(pr::setBody).build());
		perm.addEntry(e.startBooleanToggle(t("allow.pin"), pr.isPin()).setDefaultValue(dpr.isPin())
				.setTooltip(t("allow.pin.tooltip")).setSaveConsumer(pr::setPin).build());

		MiConfig.Reactions r = c.getReactions(), dr = d.getReactions();
		ConfigCategory react = b.getOrCreateCategory(t("reactions"));
		react.addEntry(e.startStrField(t("petAnimation"), r.getPetAnimation()).setDefaultValue(dr.getPetAnimation())
				.setTooltip(t("animation.tooltip")).setSaveConsumer(r::setPetAnimation).build());
		react.addEntry(e.startStrField(t("hugAnimation"), r.getHugAnimation()).setDefaultValue(dr.getHugAnimation()).setSaveConsumer(r::setHugAnimation).build());
		react.addEntry(e.startStrField(t("grabbedAnimation"), r.getGrabbedAnimation()).setDefaultValue(dr.getGrabbedAnimation()).setSaveConsumer(r::setGrabbedAnimation).build());
		react.addEntry(e.startFloatField(t("animationSeconds"), r.getAnimationSeconds()).setDefaultValue(dr.getAnimationSeconds()).setMin(0.5f).setMax(20f)
				.setSaveConsumer(r::setAnimationSeconds).build());
		return b.build();
	}
}
