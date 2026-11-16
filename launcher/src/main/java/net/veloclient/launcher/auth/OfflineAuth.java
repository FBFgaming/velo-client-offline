package net.veloclient.launcher.auth;

import java.util.regex.Pattern;

/**
 * Offline accounts: a username without a Microsoft account, the way Minecraft's own
 * offline mode (and every launcher's offline mode) works. Validating against
 * Minecraft's username rules up front keeps the derived offline UUID stable and the
 * game's own name checks happy.
 */
public final class OfflineAuth {

	/** Minecraft's own username rules: 3-16 characters of [a-zA-Z0-9_]. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

	/** A validated username, or the error to show. */
	public record Result(String username, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	private OfflineAuth() {
	}

	public static Result validate(String input) {
		String name = input == null ? "" : input.strip();
		if (name.isEmpty()) {
			return new Result(name, "Enter a username first.");
		}
		if (!NAME.matcher(name).matches()) {
			return new Result(name, "3-16 characters - letters, numbers and underscore only.");
		}
		return new Result(name, null);
	}

	/** Builds the session for an already-validated username ({@link #validate} first). */
	public static MinecraftSession createSession(String username) {
		return MinecraftSession.offline(username);
	}
}
