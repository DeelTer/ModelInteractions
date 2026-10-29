package dev.deelter.modelinteractions.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.decoration.Mannequin;

@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Invoker("getImmovable")
	boolean mi$immovable();
}
