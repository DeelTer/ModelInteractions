package dev.deelter.modelinteractions.cpm;

import com.tom.cpl.gui.UI;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.editor.TestIngameManager;
import com.tom.cpm.shared.editor.util.GetFreeSkinSlots;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletionException;

/**
 * Turns a CPM editor project into model file bytes with CPM's own editor classes, without
 * opening the editor. Only loaded when CPM is installed. These are CPM internals, not its API.
 */
public final class CpmProjects {
	private CpmProjects() {
	}

	public static byte[] export(Path project) throws IOException {
		try {
			String[] problem = new String[1];
			Editor editor = new Editor();
			editor.setUI(silentUi(problem));
			editor.load(project.toFile()).join();
			return export(editor, problem);
		} catch (CompletionException e) {
			throw new IOException("Project can't be read: " + e.getCause(), e.getCause());
		} catch (LinkageError e) {
			throw new IOException("This CPM version can't convert projects, export a .cpmmodel in its editor", e);
		}
	}

	static byte[] export(Editor editor, String[] problem) throws IOException {
		if (editor.animEnc == null) editor.animEnc = GetFreeSkinSlots.getDefault(editor);
		// the only export that handles a big model without asking the user; it writes to CPM's own
		// test model, which may be in use, so that one is put back
		Path out = new File(new File(MinecraftClientAccess.get().getGameDir(), "player_models"), TestIngameManager.TEST_MODEL_NAME).toPath();
		byte[] before = Files.isRegularFile(out) ? Files.readAllBytes(out) : null;
		try {
			if (!Exporter.exportTempModel(editor, editor.ui))
				throw new IOException(problem[0] != null ? problem[0] : "CPM couldn't export the project");
			return Files.readAllBytes(out);
		} finally {
			if (before != null) Files.write(out, before);
			else Files.deleteIfExists(out);
		}
	}

	/**
	 * Stands in for the editor's screen: runs tasks at once and keeps the last message for the error.
	 */
	static UI silentUi(String[] problem) {
		return (UI) Proxy.newProxyInstance(UI.class.getClassLoader(), new Class<?>[]{UI.class}, (self, m, args) -> {
			switch (m.getName()) {
				case "executeLater" -> ((Runnable) args[0]).run();
				case "i18nFormat" -> {
					return args[0] + (args.length > 1 ? " " + Arrays.deepToString((Object[]) args[1]) : "");
				}
				case "displayMessagePopup" -> problem[0] = String.valueOf(args[1]);
				case "onGuiException" -> problem[0] = args[0] + ": " + args[1];
				case "toString" -> {
					return "SilentUI";
				}
				case "hashCode" -> {
					return System.identityHashCode(self);
				}
				case "equals" -> {
					return self == args[0];
				}
				default -> {
				}
			}
			Class<?> r = m.getReturnType();
			if (r == boolean.class) return false;
			if (r == int.class) return 0;
			if (r == long.class) return 0L;
			if (r == float.class) return 0f;
			if (r == double.class) return 0d;
			return null;
		});
	}
}
