package dev.deelter.modelinteractions.mixin.cpm;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.RenderedCube;

@Mixin(value = ModelDefinition.class, remap = false)
public interface ModelDefinitionAccessor {
	@Accessor("cubes")
	List<RenderedCube> mi$getCubes();
}
