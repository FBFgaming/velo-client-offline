package net.veloclient.launcher.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.instance.BuiltinIcons;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceIcon;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;

/**
 * Detects and imports SKLauncher instances into the Velo launcher's instance system.
 * An SKLauncher instance folder (under APPDATA/.sklauncher/instances/<name>/) is
 * converted into a Velo Instance that:
 * - Uses the instance folder as its game directory (mods/, saves/, config/ live there)
 * - Reads the Minecraft version from mod filenames (e.g. appleskin-fabric-mc1.21.11-...),
 *   falling back to detecting the version from the mod list or the debug-profile.json.
 * - Gets a basic icon from the instance folder name or built-in icons.
 * - Is persisted to ~/.velo-client/instances/ so it shows up in the profile carousel and can
 *   be launched like any other profile.
 *
 * <p>Imported instances are given a negative {@code createdAtEpochMillis} so they sort
 * to the top of the "most played" list, and their {@code extraJvmArgs} is left {@code null}
 * (defaults apply in {@link net.veloclient.launcher.launch.GameLauncher}).
 */
public final class SklauncherInstanceImporter {

	private static final Pattern MC_TAG_PATTERN = Pattern.compile("mc(\\d+\\.\\d+(?:\\.\\d+)?)");
	private static final Pattern ANY_VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

	private SklauncherInstanceImporter() {
	}

	/**
	 * Scan the SKLauncher instances directory and import any instances found.
	 * Only scans APPDATA/.sklauncher/instances/ (Windows) and APPDATA/.minecraft/instances/
	 * (standard vanilla). Instances already present in the Velo InstanceStore are skipped.
	 *
	 * @return the number of newly imported instances
	 */
	public static int importInstances() {
		List<Candidate> candidates = detectCandidates();
		if (candidates.isEmpty()) {
			return 0;
		}
		Map<String, Instance> known = new java.util.LinkedHashMap<>();
		for (Instance instance : InstanceStore.loadAll()) {
			known.put(instance.id(), instance);
		}
		int imported = 0;
		for (Candidate candidate : candidates) {
			Instance existing = known.get(candidate.id());
			if (existing == null) {
				Instance instance = instanceFromFolder(candidate.folder(), candidate.id(), candidate.name());
				if (instance != null) {
					// The imported instance keeps using the original folder as its game dir (mods/saves/config
					// stay in place, zero copies) - see InstancePaths#externalGameDir.
					InstancePaths.setExternalGameDir(candidate.id(), candidate.folder());
					InstanceStore.save(instance);
					imported++;
				}
			} else if (InstancePaths.externalGameDir(candidate.id()) != null && !isKnownVersion(existing.mcVersion())) {
				// Repair a previously imported instance whose version detection had failed - without a
				// playable version (GameVersion) it cannot launch at all.
				String fixed = versionFromFolder(candidate.folder());
				if (fixed != null && !fixed.equals(existing.mcVersion())) {
					InstanceStore.save(new Instance(existing.id(), existing.name(), fixed, existing.icon(),
							existing.createdAtEpochMillis(), existing.ramMinMb(), existing.ramMaxMb(),
							existing.extraJvmArgs()));
					imported++;
				}
			}
		}
		return imported;
	}

	/** @return the Minecraft version string, or null if undetectable. */
	private static String versionFromFolder(Path folder) {
		// First try: debug-profile.json "minecraft:game_version" field
		Path debug = folder.resolve("debug-profile.json");
		if (Files.isRegularFile(debug)) {
			try {
				String json = Files.readString(debug);
				if (json.contains("minecraft:game_version")) {
					int idx = json.indexOf("minecraft:game_version") + "minecraft:game_version".length() + 2;
					int end = json.indexOf(",", idx);
					if (end < 0) {
						end = json.indexOf("}", idx);
					}
					if (end > idx) {
						String ver = json.substring(idx, end).trim().replace("\"", "").replace(" ", "");
						if (ver.matches("\\d+\\.\\d+(?:\\.\\d+)?")) {
							return ver;
						}
					}
				}
			} catch (Exception ignored) {
			}
		}
		// Second try: scan mods for "mc<version>" tags - mods carry their MC version in their
		// filename (e.g. appleskin-fabric-mc1.21.11-3.0.8.jar). Only versions the launcher
		// actually plays (GameVersion) are accepted; otherwise plain version tokens in the
		// filenames are tried the same way (fabric-api-0.141.6+1.21.11 -> 1.21.11).
		Path mods = folder.resolve("mods");
		if (Files.isDirectory(mods)) {
			try (var stream = Files.list(mods)) {
				List<String> names = stream
						.filter(p -> p.getFileName().toString().endsWith(".jar"))
						.map(p -> p.getFileName().toString())
						.toList();
				for (boolean mcTagOnly : new boolean[] {true, false}) {
					Pattern pattern = mcTagOnly ? MC_TAG_PATTERN : ANY_VERSION_PATTERN;
					for (String mod : names) {
						Matcher m = pattern.matcher(mod);
						while (m.find()) {
							String candidate = mcTagOnly ? m.group(1) : m.group();
							if (isKnownVersion(candidate)) {
								return candidate;
							}
						}
					}
				}
			} catch (Exception ignored) {
			}
		}
		// Third try: a known version named in the folder itself ("1.21.11" wins over "1.21.1" - longest match).
		String folderName = folder.getFileName().toString();
		String best = null;
		for (GameVersion v : GameVersion.values()) {
			String id = v.id();
			if (folderName.contains(id) && (best == null || id.length() > best.length())) {
				best = id;
			}
		}
		return best;
	}

	/** @return whether the launcher can actually play this Minecraft version (GameVersion knows it). */
	private static boolean isKnownVersion(String version) {
		for (GameVersion v : GameVersion.values()) {
			if (v.id().equals(version)) {
				return true;
			}
		}
		return false;
	}

	private static Instance instanceFromFolder(Path folder, String id, String name) {
		String mcVersion = versionFromFolder(folder);
		if (mcVersion == null) {
			LauncherLog.info("Could not detect Minecraft version for SKLauncher instance: " + folder);
			return null;
		}
		InstanceIcon icon = InstanceIcon.builtin(BuiltinIcons.DEFAULT);
		long created = -System.currentTimeMillis(); // sort to top
		return new Instance(id, name, mcVersion, icon, created);
	}

	/**
	 * Imports one arbitrary Minecraft game folder (picked by the user in a folder chooser) the same
	 * way {@link #importInstances()} imports the well-known launcher roots: the folder becomes the
	 * instance's external game dir, version detected from its mods/filenames.
	 *
	 * @return the created instance, or {@code null} if {@code folder} isn't a game dir with a detectable version
	 */
	public static Instance importFolder(Path folder) {
		if (folder == null || !Files.isDirectory(folder)) {
			return null;
		}
		String id = folder.getFileName().toString();
		List<String> taken = InstanceStore.loadAll().stream().map(Instance::id).toList();
		String unique = id;
		int suffix = 2;
		while (taken.contains(unique)) {
			unique = id + "-" + suffix++;
		}
		Instance instance = instanceFromFolder(folder, unique, id);
		if (instance == null) {
			return null;
		}
		InstancePaths.setExternalGameDir(unique, folder);
		InstanceStore.save(instance);
		return instance;
	}

	private record Candidate(String id, String name, Path folder) {
	}

	/**
	 * Every known instance folder worth importing: the children of SKLauncher's
	 * {@code .sklauncher/instances/} (the user's SKLauncher modpacks) and of any
	 * MultiMC-style {@code .minecraft/instances/}, plus the vanilla launcher's
	 * {@code .minecraft} root itself (it is a game dir with its own mods/).
	 * Candidates without a detectable Minecraft version are skipped by
	 * {@link #instanceFromFolder} - that filters shared folders like {@code versions/} out.
	 */
	private static List<Candidate> detectCandidates() {
		List<Candidate> out = new ArrayList<>();
		String appData = System.getenv("APPDATA");
		if (appData == null) {
			return out;
		}
		Path base = Path.of(appData);
		for (Path instancesRoot : new Path[] {base.resolve(".sklauncher").resolve("instances"),
				base.resolve(".minecraft").resolve("instances")}) {
			if (!Files.isDirectory(instancesRoot)) {
				continue;
			}
			try (var stream = Files.list(instancesRoot)) {
				stream.filter(Files::isDirectory).sorted(Comparator.naturalOrder())
						.forEach(p -> out.add(new Candidate(p.getFileName().toString(), p.getFileName().toString(), p)));
			} catch (IOException e) {
				LauncherLog.warn("Failed to scan instance folders at " + instancesRoot, e);
			}
		}
		Path vanilla = base.resolve(".minecraft");
		out.add(new Candidate("vanilla-minecraft", "Minecraft (.minecraft)", vanilla));
		return out;
	}
}