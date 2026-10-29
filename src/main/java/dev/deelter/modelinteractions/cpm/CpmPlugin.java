package dev.deelter.modelinteractions.cpm;

import java.io.DataInputStream;
import java.util.UUID;
import java.util.function.BiConsumer;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.api.ICPMPlugin;
import com.tom.cpm.api.IClientAPI;
import com.tom.cpm.api.IClientAPI.MessageSender;
import com.tom.cpm.api.ICommonAPI;

import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.net.NetBridge;
import dev.deelter.modelinteractions.net.NetRouter;
import dev.deelter.modelinteractions.wire.Wire;
import net.minecraft.client.Minecraft;

/**
 * CPM API plugin: a fallback transport over CPM plugin messages for servers that run CPM but
 * not the Model Interactions plugin, plus access to the local player's CPM animations.
 * The bytes are the same {@link Wire} messages, wrapped in an NBT byte array.
 */
public class CpmPlugin implements ICPMPlugin, NetBridge {
	private static final String[] STATE_CHANNELS = { "perm", "pose", "types" };

	private IClientAPI api;
	private MessageSender act;
	private final MessageSender[] states = new MessageSender[Wire.S_COUNT];

	@Override
	public void initClient(IClientAPI api) {
		this.api = api;
		InteractionManager mgr = InteractionManager.get();
		act = api.registerPluginMessage("act", onMain((sender, data) -> {
			DataInputStream in = Wire.in(data);
			if (Wire.kind(in) != Wire.K_ACT) return;
			mgr.handleAction(sender, Wire.readAct(in));
		}), true);
		for (int i = 0; i < Wire.S_COUNT; i++) {
			int state = i;
			states[i] = api.registerPluginStateMessage(STATE_CHANNELS[i], onMain((owner, data) -> {
				if (data.length > Wire.MAX_STATE_BODY) return;
				mgr.handleState(owner, state, data);
			}));
		}
		mgr.setAnimator(this::playOwnAnimation);
		NetRouter.get().setCpm(this);
		ModelInteractions.LOG.info("Registered CPM plugin messages");
	}

	private interface Handler {
		void accept(UUID sender, byte[] data) throws Exception;
	}

	private static BiConsumer<UUID, NBTTagCompound> onMain(Handler h) {
		return (uuid, tag) -> {
			byte[] data = tag.getByteArray("d");
			Minecraft.getInstance().execute(() -> {
				try {
					if (uuid != null && data != null) h.accept(uuid, data);
				} catch (Exception e) {
					ModelInteractions.LOG.debug("Bad interaction message from {}", uuid, e);
				}
			});
		};
	}

	private static NBTTagCompound wrap(byte[] data) {
		NBTTagCompound t = new NBTTagCompound();
		t.setByteArray("d", data);
		return t;
	}

	@Override
	public void initCommon(ICommonAPI api) {
	}

	@Override
	public String getOwnerModId() {
		return ModelInteractions.MOD_ID;
	}

	@Override
	public boolean sendAction(Wire.Act a) {
		return act != null && act.sendMessage(wrap(Wire.encodeAct(a, null)));
	}

	@Override
	public boolean publishState(int state, byte[] body) {
		MessageSender s = state >= 0 && state < states.length ? states[state] : null;
		return s != null && s.sendMessage(wrap(body));
	}

	@Override
	public boolean requestStates(UUID target) {
		return false; // CPM resends state messages to new watchers by itself
	}

	@Override
	public boolean isAvailable() {
		return act != null;
	}

	public boolean playOwnAnimation(String name, int value) {
		if (api == null || api.getAnimationPlaying(name) < 0) return false;
		return api.playAnimation(name, value);
	}
}
