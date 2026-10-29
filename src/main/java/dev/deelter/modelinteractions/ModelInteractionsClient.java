package dev.deelter.modelinteractions;

import dev.deelter.modelinteractions.config.ConfigListeners;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.input.PcInput;
import dev.deelter.modelinteractions.interaction.CarryClient;
import dev.deelter.modelinteractions.net.NetRouter;
import dev.deelter.modelinteractions.net.RelayNet;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.debug.DebugCommands;
import dev.deelter.modelinteractions.debug.DummyManager;
import dev.deelter.modelinteractions.debug.PartVisualizer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public class ModelInteractionsClient implements ClientModInitializer {
	private static Runnable vrFrame = () -> {};
	private static long lastFrame;
	private int publishRetry;

	@Override
	public void onInitializeClient() {
		MiConfig.load();
		PcInput.register();

		if (FabricLoader.getInstance().isModLoaded("vivecraft")) {
			try {
				vrFrame = dev.deelter.modelinteractions.vr.VivecraftCompat.init();
			} catch (Throwable t) {
				ModelInteractions.LOG.error("Vivecraft integration failed to load", t);
			}
		}

		InteractionManager mgr = InteractionManager.get();
		mgr.setNet(NetRouter.get());
		RelayNet.register();
		CarryClient.register();
		ConfigListeners.add(mgr::onConfigChanged);
		DebugCommands.register();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			publishRetry = 0;
			RelayNet.reset();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			DummyManager.reset();
			RelayNet.reset();
			dev.deelter.modelinteractions.toy.ToyBridge.stop();
			CarryClient.reset();
			mgr.clear();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) return;
			DummyManager.tick(mc);
			CarryClient.tick(mc);
			RelayNet.tick();
			dev.deelter.modelinteractions.toy.ToyBridge.tick();
			PcInput.tick(mc);
			mgr.tick();
			PartVisualizer.tick(mc);
			// the CPM handshake / server hello come a bit after join; retry until a transport accepts
			if (!mgr.isPermissionsPublished() && publishRetry++ % 40 == 0) mgr.publishOwnPermissions();
		});
	}

	/** Called at the start of every rendered frame. */
	public static void onFrame(Minecraft mc, float partialTick) {
		if (mc.player == null) return;
		PcInput.frame(mc, partialTick);
		vrFrame.run();
		CarryClient.frameCarry(mc);
		long now = System.nanoTime();
		CarryClient.frame(mc, lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9f, 0.1f));
		lastFrame = now;
		InteractionManager.get().onFrame(mc, partialTick);
	}
}
