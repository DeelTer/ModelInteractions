package dev.deelter.modelinteractions.mixin;

import dev.deelter.modelinteractions.vanilla.MiRenderState;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements MiRenderState {
	@Unique
	private final double[] mi$cam = new double[3];
	@Unique
	private int mi$id = -1;
	@Unique
	private UUID mi$uuid;
	@Unique
	private boolean mi$player;
	@Unique
	private Model<?> mi$main;

	@Override
	public int mi$entityId() {
		return mi$id;
	}

	@Override
	public UUID mi$uuid() {
		return mi$uuid;
	}

	@Override
	public boolean mi$player() {
		return mi$player;
	}

	@Override
	public void mi$setEntity(int id, UUID uuid, boolean player) {
		mi$id = id;
		mi$uuid = uuid;
		mi$player = player;
	}

	@Override
	public double[] mi$camera() {
		return mi$cam;
	}

	@Override
	public Model<?> mi$mainModel() {
		return mi$main;
	}

	@Override
	public void mi$setSubmit(double camX, double camY, double camZ, Model<?> main) {
		mi$cam[0] = camX;
		mi$cam[1] = camY;
		mi$cam[2] = camZ;
		mi$main = main;
	}
}
