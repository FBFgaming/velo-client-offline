package net.veloclient.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A fully-authenticated Minecraft session: what's needed to show account
 * info in the launcher and to launch the game with
 * {@code --accessToken}/{@code --uuid}/{@code --username}.
 *
 * <p>Two kinds of sessions share this shape: Microsoft accounts (the real
 * auth chain, refreshable) and offline accounts (a username plus Minecraft's
 * own offline-mode UUID, {@link #offline}) - the latter can never refresh,
 * so everything that touches {@code microsoftRefreshToken} checks
 * {@link #isOffline()} first.
 */
public record MinecraftSession(
		String minecraftAccessToken,
		long accessTokenExpiresAtEpochMillis,
		String microsoftRefreshToken,
		String uuid,
		String username,
		boolean offline) {

	/** The pre-offline-support signature, and the shape every saved account.json predating offline accounts loads through Gson. */
	public MinecraftSession(String minecraftAccessToken, long accessTokenExpiresAtEpochMillis, String microsoftRefreshToken,
			String uuid, String username) {
		this(minecraftAccessToken, accessTokenExpiresAtEpochMillis, microsoftRefreshToken, uuid, username, false);
	}

	public boolean isAccessTokenExpired() {
		return System.currentTimeMillis() >= accessTokenExpiresAtEpochMillis - 30_000;
	}

	/** A username-only offline account: nothing to refresh, no Microsoft token behind it. */
	public boolean isOffline() {
		return offline;
	}

	/**
	 * An offline account: a username without a Microsoft sign-in. The UUID is Minecraft's own
	 * offline-mode derivation, {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)} - the same
	 * value a vanilla server running with online-mode off assigns that username, so offline-mode
	 * servers recognize the player exactly like every other launcher's offline accounts.
	 * Singleplayer, LAN and offline-mode servers work with it; Velo Network (friends, parties,
	 * cosmetics sync) and premium-only servers need a real Microsoft account.
	 */
	public static MinecraftSession offline(String username) {
		String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
		// Five years out: never expires in practice, so no refresh path ever touches the empty Microsoft token.
		long never = System.currentTimeMillis() + 5L * 365L * 24L * 60L * 60L * 1000L;
		return new MinecraftSession("0", never, "", uuid, username, true);
	}
}
