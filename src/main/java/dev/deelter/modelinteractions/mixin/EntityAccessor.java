package dev.deelter.modelinteractions.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Entity;

/** Flags a server-side velocity change for syncing; the field behind it was renamed in 26.3. */
@Mixin(Entity.class)
public interface EntityAccessor {
	@Invoker("markHurt")
	void mi$markVelocityDirty();
}
