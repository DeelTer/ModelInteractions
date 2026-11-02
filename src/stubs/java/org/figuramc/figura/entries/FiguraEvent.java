package org.figuramc.figura.entries;

import java.util.Collection;

/**
 * Compile-time stand-in for Figura's entrypoint (never packaged; the real one comes from Figura).
 * Real signature: {@code Collection<Pair<String, LuaEvent>> getEvents()}, same erasure.
 */
public interface FiguraEvent {
	String getID();

	Collection<?> getEvents();
}
