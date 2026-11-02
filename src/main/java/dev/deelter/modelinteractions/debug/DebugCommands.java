package dev.deelter.modelinteractions.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.input.PcInput;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.interaction.PartHit;
import dev.deelter.modelinteractions.interaction.PlayerRig;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.toy.ToyBridge;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Client commands:
 * <pre>
 * /mi dummy spawn &lt;model&gt; [name]   fake player wearing a builtin or config/modelinteractions/dummies model
 * /mi dummy motion &lt;still|walk|spin|jump|float|follow&gt;
 * /mi dummy follow                 the aimed dummy follows you while you hold its hand (toggle)
 * /mi dummy mob &lt;type&gt;              client-only mob
 * /mi dummy pose on|off            dummies in "pose me" mode
 * /mi dummy reset                  drop dummies' poses
 * /mi dummy clear
 * /mi debug parts                   toggle part outlines
 * /mi debug rigs                    list rigs and detected part counts
 * /mi part set &lt;type&gt;              type of the part under the crosshair (own model: shared with everyone)
 * /mi part clear                   back to the automatic guess
 * /mi freemode                     toggle posing mode
 * </pre>
 */
public final class DebugCommands {
	private static final EquipmentSlot[] DUMMY_SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private DebugCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> dummy = literal("dummy")
					.then(literal("spawn")
							.then(argument("model", StringArgumentType.word())
									.suggests((c, b) -> {
										DummyManager.availableModels().forEach(b::suggest);
										return b.buildFuture();
									})
									.executes(c -> spawn(c, StringArgumentType.getString(c, "model"), null))
									.then(argument("name", StringArgumentType.word())
											.executes(c -> spawn(c, StringArgumentType.getString(c, "model"),
													StringArgumentType.getString(c, "name"))))))
					.then(literal("mob")
							.then(argument("type", StringArgumentType.word())
									.suggests((c, b) -> {
										DummyManager.mobTypes().forEach(b::suggest);
										return b.buildFuture();
									})
									.executes(c -> {
										String type = StringArgumentType.getString(c, "type");
										try {
											DummyManager.spawnMob(type);
											c.getSource().sendFeedback(Component.literal("Spawned a client-only " + type));
											return 1;
										} catch (Exception e) {
											c.getSource().sendError(Component.literal("Can't spawn '" + type + "': " + e.getMessage()));
											return 0;
										}
									})))
					.then(literal("pose")
							.then(literal("on").executes(c -> dummyPose(c, true)))
							.then(literal("off").executes(c -> dummyPose(c, false))))
					.then(literal("reset").executes(c -> {
						int n = DummyManager.resetPoses();
						c.getSource().sendFeedback(Component.literal("Reset " + n + " dummies"));
						return n;
					}))
					.then(literal("clear").executes(c -> {
						int n = DummyManager.clear();
						c.getSource().sendFeedback(Component.literal("Removed " + n + " dummies"));
						return n;
					}));
			var motion = literal("motion");
			for (DummyManager.Motion m : DummyManager.Motion.values()) {
				motion.then(literal(m.name().toLowerCase(Locale.ROOT)).executes(c -> {
					if (!DummyManager.setMotion(m)) {
						c.getSource().sendError(Component.literal("No dummies"));
						return 0;
					}
					return 1;
				}));
			}
			dummy.then(motion);
			dummy.then(literal("follow").executes(c -> {
				UUID target = DummyManager.aimed(Minecraft.getInstance());
				if (target == null) {
					c.getSource().sendError(Component.literal("No dummies"));
					return 0;
				}
				boolean on = DummyManager.toggleFollow(target);
				c.getSource().sendFeedback(Component.literal(on
						? "The dummy follows you while you hold its hand (grab an arm and walk)"
						: "The dummy stays where it is"));
				return 1;
			}));
			var equip = literal("equip");
			var take = literal("take").executes(c -> take(c, null));
			for (EquipmentSlot slot : DUMMY_SLOTS) {
				equip.then(literal(slot.getName())
						.executes(c -> equip(c, slot, Minecraft.getInstance().player.getMainHandItem()))
						.then(argument("item", ItemArgument.item(ctx))
								.executes(c -> equip(c, slot, ItemArgument.getItem(c, "item").createItemStack(1)))));
				take.then(literal(slot.getName()).executes(c -> take(c, slot)));
			}
			dummy.then(equip).then(take);

			var debug = literal("debug")
					.then(literal("parts").executes(c -> {
						PartVisualizer.setEnabled(!PartVisualizer.isEnabled());
						c.getSource().sendFeedback(Component.literal("Part outlines " + (PartVisualizer.isEnabled() ? "on" : "off")
								+ ": ears pink, tail orange, hair purple, wings cyan, limbs green, head yellow, other white"));
						return 1;
					}))
					.then(literal("rigs").executes(DebugCommands::listRigs))
					.then(literal("touches").executes(c -> {
						dev.deelter.modelinteractions.interaction.TouchReactions.setDebug(!dev.deelter.modelinteractions.interaction.TouchReactions.isDebug());
						c.getSource().sendFeedback(Component.literal("Touch names " + (dev.deelter.modelinteractions.interaction.TouchReactions.isDebug() ? "on: every touch shows the mi_* animation names to make" : "off")));
						return 1;
					}))
					.then(literal("net").executes(c -> {
						c.getSource().sendFeedback(Component.literal("Relay " + (dev.deelter.modelinteractions.net.RelayNet.isActive() ? "active, range " + dev.deelter.modelinteractions.net.RelayNet.getRange()
								: "NOT active after " + dev.deelter.modelinteractions.net.RelayNet.getHellos() + " hellos" + (dev.deelter.modelinteractions.net.RelayNet.isVersionMismatch() ? ", server speaks another protocol" : ""))));
						InteractionManager.get().netStatus().forEach(l -> c.getSource().sendFeedback(Component.literal(l)));
						return 1;
					}));

			var set = literal("set");
			for (PartType t : PartType.VALUES) {
				set.then(literal(t.name()).executes(c -> setType(c, t)));
			}
			var part = literal("part").then(set).then(literal("clear").executes(c -> setType(c, null)));
			var free = literal("freemode").executes(c -> {
				boolean on = PcInput.toggleFreeMode(Minecraft.getInstance());
				c.getSource().sendFeedback(Component.translatable(on ? "modelinteractions.msg.free_on" : "modelinteractions.msg.free_off"));
				return 1;
			});

			var toy = literal("toy")
					.then(literal("on").executes(c -> toyEnabled(c, true)))
					.then(literal("off").executes(c -> toyEnabled(c, false)))
					.then(literal("stop").executes(c -> {
						ToyBridge.stop();
						c.getSource().sendFeedback(Component.translatable("modelinteractions.msg.toy_stopped"));
						return 1;
					}))
					.then(literal("test").executes(c -> {
						if (!ToyBridge.active()) {
							c.getSource().sendError(Component.translatable("modelinteractions.msg.toy_inactive"));
							return 0;
						}
						ToyBridge.touch(0.5f);
						return 1;
					}))
					.then(literal("allow").then(argument("name", StringArgumentType.word()).executes(c -> toyList(c, true))))
					.then(literal("deny").then(argument("name", StringArgumentType.word()).executes(c -> toyList(c, false))))
					.then(literal("zone").executes(DebugCommands::toyZone));

			dispatcher.register(literal("mi").then(dummy).then(debug).then(part).then(free).then(toy));
		});
	}

	private static int toyEnabled(CommandContext<FabricClientCommandSource> c, boolean on) {
		MiConfig.get().getToy().setEnabled(on);
		MiConfig.save();
		if (!on) ToyBridge.stop();
		c.getSource().sendFeedback(Component.translatable(on ? (ToyBridge.active() ? "modelinteractions.msg.toy_on" : "modelinteractions.msg.toy_inactive")
				: "modelinteractions.msg.toy_off"));
		return 1;
	}

	private static int toyList(CommandContext<FabricClientCommandSource> c, boolean add) {
		String name = StringArgumentType.getString(c, "name").toLowerCase(java.util.Locale.ROOT);
		var list = MiConfig.get().getToy().getAllowed();
		list.removeIf(n -> n.equalsIgnoreCase(name));
		if (add) list.add(name);
		MiConfig.save();
		c.getSource().sendFeedback(Component.literal((add ? "+ " : "- ") + name + "  " + String.join(", ", list)));
		return 1;
	}

	/**
	 * Marks or unmarks the part of your own model under the crosshair as a zone (F5 to see yourself).
	 */
	private static int toyZone(CommandContext<FabricClientCommandSource> c) {
		Minecraft mc = Minecraft.getInstance();
		PartHit hit = mc.player == null ? null : PcInput.pick(mc, 1f, b -> !b.isRoot());
		if (hit == null || !hit.rig().getUuid().equals(mc.player.getUUID())) {
			c.getSource().sendError(Component.literal("Look at a part of your own model (F5)"));
			return 0;
		}
		var zones = MiConfig.get().getToy().getZones().computeIfAbsent(hit.rig().getModelKey(), k -> new java.util.ArrayList<>());
		Integer id = hit.bone().getId();
		boolean added = !zones.remove(id);
		if (added) zones.add(id);
		if (zones.isEmpty()) MiConfig.get().getToy().getZones().remove(hit.rig().getModelKey());
		MiConfig.save();
		c.getSource().sendFeedback(Component.literal("#" + id + (added ? " is a zone now" : " is no zone any more")));
		return 1;
	}

	private static int dummyPose(CommandContext<FabricClientCommandSource> c, boolean on) {
		if (!DummyManager.setPoseMode(on)) {
			c.getSource().sendError(Component.literal("No dummies"));
			return 0;
		}
		c.getSource().sendFeedback(Component.literal("Dummies " + (on ? "keep every pose you leave them in" : "spring back again")));
		return 1;
	}

	private static int setType(CommandContext<FabricClientCommandSource> c, PartType type) {
		Minecraft mc = Minecraft.getInstance();
		PartHit hit = mc.player == null ? null : PcInput.pick(mc, 1f, b -> !b.isRoot());
		if (hit == null) {
			c.getSource().sendError(Component.literal("Look at a part first (F5 for your own model)"));
			return 0;
		}
		InteractionManager.get().setPartType(hit, type);
		boolean own = hit.rig().getUuid().equals(mc.player.getUUID());
		c.getSource().sendFeedback(Component.literal("#" + hit.bone().getId() + " -> " + (type == null ? "auto" : type.name())
				+ (own ? " (your model, shared with everyone)" : " (only you see this)")));
		return 1;
	}

	private static int equip(CommandContext<FabricClientCommandSource> c, EquipmentSlot slot, ItemStack stack) {
		java.util.UUID uuid = DummyManager.aimed(Minecraft.getInstance());
		if (uuid == null || stack.isEmpty()) {
			c.getSource().sendError(Component.literal(uuid == null ? "No dummies" : "Take the item in your hand or name it"));
			return 0;
		}
		DummyManager.equip(uuid, slot, stack);
		return 1;
	}

	private static int take(CommandContext<FabricClientCommandSource> c, EquipmentSlot slot) {
		java.util.UUID uuid = DummyManager.aimed(Minecraft.getInstance());
		if (uuid == null) {
			c.getSource().sendError(Component.literal("No dummies"));
			return 0;
		}
		for (EquipmentSlot s : DUMMY_SLOTS) if (slot == null || s == slot) DummyManager.equip(uuid, s, ItemStack.EMPTY);
		return 1;
	}

	private static int spawn(CommandContext<FabricClientCommandSource> c, String model, String name) {
		String n = name != null ? name : "Dummy_" + model;
		try {
			DummyManager.spawn(model, n);
			c.getSource().sendFeedback(Component.literal("Spawned " + n + " (" + model + "). Hold G on a part to pull it."));
			return 1;
		} catch (Exception e) {
			ModelInteractions.LOG.warn("Failed to spawn dummy {}", model, e);
			c.getSource().sendError(Component.literal("Can't spawn '" + model + "': " + e.getMessage()
					+ ". Available: " + String.join(", ", DummyManager.availableModels())));
			return 0;
		}
	}

	private static int listRigs(CommandContext<FabricClientCommandSource> c) {
		int n = 0;
		for (PlayerRig rig : InteractionManager.get().rigs()) {
			int[] counts = new int[dev.deelter.modelinteractions.physics.PartType.values().length];
			for (Bone b : rig.bones()) if (!b.isRoot()) counts[b.getType().ordinal()]++;
			StringBuilder sb = new StringBuilder(rig.getUuid().toString().substring(0, 8)).append(" model=").append(rig.getModelKey())
					.append(rig.isWorldValid() ? "" : " (not visible)");
			for (var t : dev.deelter.modelinteractions.physics.PartType.values()) {
				if (counts[t.ordinal()] > 0) sb.append(' ').append(t).append('=').append(counts[t.ordinal()]);
			}
			c.getSource().sendFeedback(Component.literal(sb.toString()));
			n++;
		}
		if (n == 0) c.getSource().sendFeedback(Component.literal("No rigs yet: no CPM model has been rendered"));
		return n;
	}
}
