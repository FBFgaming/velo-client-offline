package net.veloclient.launcher.launch;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reuses files other launchers already downloaded instead of fetching them
 * from Mojang again. Before any download, candidates are looked up in each
 * detected launcher root (SKLauncher's {@code .sklauncher}, the vanilla
 * {@code .minecraft}), and a copy that matches the expected sha1/size is
 * hardlinked into the shared cache - same volume, so a hardlink costs no
 * extra disk space; anything else falls back to a copy, and any failure here
 * silently falls through to the normal download path.
 *
 * <p>Imported files are verified exactly like downloads (sha1 when the
 * manifest provides one, size otherwise), then marked in
 * {@link VerifiedFileIndex} so later launches skip re-hashing them.</p>
 */
public final class ExternalInstalls {

	/** Other launchers' data roots whose layout mirrors Mojang's standard one. */
	private static final List<Path> ROOTS = detectRoots();

	private ExternalInstalls() {
	}

	private static List<Path> detectRoots() {
		List<Path> roots = new ArrayList<>();
		String appData = System.getenv("APPDATA");
		if (appData != null && !appData.isBlank()) {
			// SKLauncher's data dir: same versions/ libraries/ assets/ layout as .minecraft
			roots.add(Path.of(appData).resolve(".sklauncher"));
			roots.add(Path.of(appData).resolve(".minecraft"));
		}
		return roots.stream().filter(Files::isDirectory).toList();
	}

	/** @return true when {@code dest} was created from an existing local copy. */
	public static boolean importIfAvailable(URI url, Path dest, String expectedSha1, long expectedSize) {
		if (ROOTS.isEmpty()) {
			return false;
		}
		try {
			for (Path root : ROOTS) {
				for (Path candidate : candidates(root, url)) {
					if (importCandidate(candidate, dest, expectedSha1, expectedSize)) {
						return true;
					}
				}
			}
		} catch (Exception e) {
			// Import is strictly an optimization - never block a launch on it.
		}
		return false;
	}

	/**
	 * The vanilla client jar is keyed by version id, not by a CDN path, so it
	 * gets its own lookup: {@code <root>/versions/<id>/<id>.jar} - the naming
	 * every launcher following the vanilla layout uses. The sha1 from Mojang's
	 * manifest decides, so a modded/renamed jar is rejected and downloaded
	 * normally instead.
	 */
	public static boolean importClientJar(String mcVersion, Path dest, String expectedSha1, long expectedSize) {
		if (ROOTS.isEmpty() || expectedSha1 == null) {
			return false;
		}
		try {
			for (Path root : ROOTS) {
				Path candidate = root.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar");
				if (importCandidate(candidate, dest, expectedSha1, expectedSize)) {
					return true;
				}
			}
		} catch (Exception e) {
			// Fall through to the download path.
		}
		return false;
	}

	private static boolean importCandidate(Path candidate, Path dest, String expectedSha1, long expectedSize) throws IOException {
		if (!Files.isRegularFile(candidate) || candidate.equals(dest)) {
			return false;
		}
		long size = Files.size(candidate);
		if (expectedSize >= 0 && size != expectedSize) {
			return false;
		}
		if (expectedSha1 != null && !Downloader.sha1Of(candidate).equalsIgnoreCase(expectedSha1)) {
			return false;
		}
		Files.createDirectories(dest.getParent());
		if (Files.exists(dest)) {
			return matches(dest, expectedSha1, expectedSize);
		}
		try {
			// Same volume (both under C:\Users) - a hardlink shares the SKLauncher
			// file's bytes without duplicating them.
			Files.createLink(dest, candidate);
		} catch (IOException unsupported) {
			Files.copy(candidate, dest, StandardCopyOption.REPLACE_EXISTING);
		}
		if (expectedSha1 != null) {
			VerifiedFileIndex.markVerified(dest, expectedSha1);
		}
		return true;
	}

	private static boolean matches(Path file, String expectedSha1, long expectedSize) throws IOException {
		if (expectedSize >= 0 && Files.size(file) != expectedSize) {
			return false;
		}
		return expectedSha1 == null || Downloader.sha1Of(file).equalsIgnoreCase(expectedSha1);
	}

	private static List<Path> candidates(Path root, URI url) {
		List<Path> out = new ArrayList<>();
		String raw = url.getPath();
		if (raw == null || raw.isEmpty()) {
			return out;
		}
		String path = raw.startsWith("/") ? raw.substring(1) : raw;
		String[] segments = path.split("/");
		// Asset object: resources.download.minecraft.net/<2 hex>/<40 hex sha1>
		// (SKLauncher/.minecraft cache exactly that under assets/objects).
		if (segments.length == 2 && segments[0].length() == 2 && isSha1(segments[1])) {
			out.add(root.resolve("assets").resolve("objects").resolve(segments[0]).resolve(segments[1]));
		}
		// Asset index: piston-meta .../v1/packages/<sha1>/<id>.json -
		// launchers cache it under assets/indexes/<id>.json.
		if (segments.length >= 3 && segments[segments.length - 1].endsWith(".json") && path.contains("/v1/packages/")) {
			out.add(root.resolve("assets").resolve("indexes").resolve(segments[segments.length - 1]));
		}
		// Library (Mojang's and Fabric's maven hosts share the standard layout).
		out.add(root.resolve("libraries").resolve(path));
		return out;
	}

	private static boolean isSha1(String s) {
		if (s.length() != 40) {
			return false;
		}
		for (char c : s.toCharArray()) {
			if (Character.digit(c, 16) == -1) {
				return false;
			}
		}
		return true;
	}
}
