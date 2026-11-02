package dev.deelter.modelinteractions.figura;

import com.mojang.datafixers.util.Pair;
import org.figuramc.figura.entries.FiguraEvent;
import org.figuramc.figura.lua.api.event.LuaEvent;

import java.util.Collection;
import java.util.List;

/**
 * Figura {@code figura_event} entrypoint: adds {@code events["modelinteractions.touch"]} to every
 * avatar. Only Figura loads this class. Figura asks once per avatar, so each gets its own event.
 */
public class FiguraTouchEvents implements FiguraEvent {
	@Override
	public String getID() {
		return "modelinteractions";
	}

	@Override
	public Collection<?> getEvents() {
		try {
			return List.of(Pair.of("touch", new LuaEvent()));
		} catch (LinkageError e) {
			// another Figura than we know: no event, but the avatar still loads
			return List.of();
		}
	}
}
