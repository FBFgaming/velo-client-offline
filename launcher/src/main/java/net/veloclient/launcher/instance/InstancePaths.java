package net.veloclient.launcher.instance;

import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-instance directory layout, rooted at {@code ~/.velo-client/instances/<id>/}.
 * Each instance is a fully isolated Minecraft game directory (own mods/,
 * saves/, config/, logs/) - only the downloaded libraries/assets/client jars
 * are shared across instances, see {@link net.veloclient.launcher.launch.GameDataPaths}.
 */
public final class InstancePaths {

	private static final String EXTERNAL_MARKER = "external-gamedir.txt";

	private InstancePaths() {
	}

	public static Path root() {
		return VeloPaths.root().resolve("instances");
	}

	public static Path dir(String instanceId) {
		return root().resolve(instanceId);
	}

	public static Path metadataFile(String instanceId) {
		return dir(instanceId).resolve("instance.json");
	}

	public static Path iconFile(String instanceId) {
		return dir(instanceId).resolve("icon.png");
	}

	/**
	 * The instance's Minecraft game directory: mods/saves/config/logs all live directly under this.
	 * Instances imported from other launchers point this at the original folder (marker file
	 * {@code external-gamedir.txt} in the Velo-side instance dir), so the pack's own mods, saves
	 * and config are used in place - nothing is copied or duplicated.
	 */
	public static Path gameDir(String instanceId) {
		Path external = externalGameDir(instanceId);
		return external != null ? external : dir(instanceId);
	}

	/** @return the external game dir this instance was imported from, or {@code null} if it uses its own Velo folder. */
	public static Path externalGameDir(String instanceId) {
		Path marker = dir(instanceId).resolve(EXTERNAL_MARKER);
		if (!Files.isRegularFile(marker)) {
			return null;
		}
		try {
			String raw = Files.readString(marker, StandardCharsets.UTF_8).strip();
			if (raw.isEmpty()) {
				return null;
			}
			Path external = Path.of(raw);
			return Files.isDirectory(external) ? external : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Points this instance's game directory at {@code external} (an existing folder) instead of its own Velo folder. */
	public static void setExternalGameDir(String instanceId, Path external) {
		try {
			Files.createDirectories(dir(instanceId));
			Files.writeString(dir(instanceId).resolve(EXTERNAL_MARKER),
					external.toAbsolutePath().normalize().toString() + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException("Failed to record external game dir for " + instanceId, e);
		}
	}

	public static Path modsDir(String instanceId) {
		return gameDir(instanceId).resolve("mods");
	}

	public static Path resourcePacksDir(String instanceId) {
		return gameDir(instanceId).resolve("resourcepacks");
	}

	public static Path shaderPacksDir(String instanceId) {
		return gameDir(instanceId).resolve("shaderpacks");
	}

	/** Litematica's schematic folder - its own default and not user-configurable per-install, always {@code <gamedir>/schematics/}. */
	public static Path schematicsLitematicaDir(String instanceId) {
		return gameDir(instanceId).resolve("schematics");
	}

	/** WorldEdit (Fabric)'s default {@code saveDir}, relative to its own config folder - {@code <gamedir>/config/worldedit/schematics/}. */
	public static Path schematicsWorldEditDir(String instanceId) {
		return gameDir(instanceId).resolve("config").resolve("worldedit").resolve("schematics");
	}

	public static Path logsDir(String instanceId) {
		return gameDir(instanceId).resolve("logs");
	}

	public static Path savesDir(String instanceId) {
		return gameDir(instanceId).resolve("saves");
	}

	public static void ensureRoot() {
		try {
			Files.createDirectories(root());
		} catch (IOException e) {
			throw new RuntimeException("Failed to create instances directory: " + root(), e);
		}
	}

	public static void ensureDirectories(String instanceId) {
		for (Path dir : new Path[] {root(), dir(instanceId), modsDir(instanceId), resourcePacksDir(instanceId),
				shaderPacksDir(instanceId), schematicsLitematicaDir(instanceId), schematicsWorldEditDir(instanceId), logsDir(instanceId)}) {
			try {
				Files.createDirectories(dir);
			} catch (IOException e) {
				throw new RuntimeException("Failed to create instance directory: " + dir, e);
			}
		}
	}
}
