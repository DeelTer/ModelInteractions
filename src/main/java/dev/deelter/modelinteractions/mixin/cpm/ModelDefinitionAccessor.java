package dev.deelter.modelinteractions.mixin.cpm;

import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.RenderedCube;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = ModelDefinition.class, remap = false)
public interface ModelDefinitionAccessor {
	@Accessor("cubes")
	List<RenderedCube> mi$getCubes();
}
