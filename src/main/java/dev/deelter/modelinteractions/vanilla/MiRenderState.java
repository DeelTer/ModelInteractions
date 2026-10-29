package dev.deelter.modelinteractions.vanilla;

import java.util.UUID;

import net.minecraft.client.model.Model;

/** Extra fields mixed into EntityRenderState: which entity it is and how it was submitted. */
public interface MiRenderState {
	int mi$entityId();

	UUID mi$uuid();

	boolean mi$player();

	void mi$setEntity(int id, UUID uuid, boolean player);

	/** Camera position of the pass the state was submitted in. */
	double[] mi$camera();

	/** The renderer's own body model; armour and other layers use other models. */
	Model<?> mi$mainModel();

	void mi$setSubmit(double camX, double camY, double camZ, Model<?> main);
}
