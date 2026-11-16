# Offline account support (this fork)

This fork adds username-only **offline accounts** to the Velo launcher, the same way
vanilla Minecraft's own offline mode works: no Microsoft sign-in, just a username.
Useful without a paid account, or for LAN/singleplayer/test worlds.

## What changed

| File | Change |
| --- | --- |
| `launcher/.../auth/MinecraftSession.java` | Added an `offline` flag + `MinecraftSession.offline(username)` factory. The offline UUID is `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` - the exact value a vanilla server in offline mode assigns that username, so offline servers match you like every other launcher. Token is a dummy `"0"`, expiry set 5 years out so no refresh path ever touches it. |
| `launcher/.../auth/OfflineAuth.java` | New. Validates Minecraft's own username rules (3-16 chars, `[A-Za-z0-9_]`) and builds the session. |
| `launcher/.../ui/SignInDialog.java` | The sign-in dialog has an offline panel: footer link *"No Microsoft account? Play with an offline username instead"* from the Microsoft flow, and a new `SignInDialog.showOffline(...)` entry point. Backing out of the offline panel restarts the Microsoft device-code flow. |
| `launcher/.../launch/GameLauncher.java` | Offline sessions launch with `user_type=legacy` (what launchers pass for offline accounts) instead of `msa`. |
| `launcher/.../social/LauncherSocial.java` | Offline accounts skip the Velo Network worker entirely (it would retry a handshake the server could never verify, every few seconds). |
| `launcher/.../LauncherApp.java` | Account menu gets **Add offline account**; the "not signed in" prompt now says a Microsoft account *or* an offline username works; switcher rows and settings/profile labels mark offline accounts. |
| `src/client/.../auth/VeloAccountAuth.java` | The in-game "Fix Session" button reports accurately for offline accounts instead of trying a refresh that can't work. |

Old `account.json` files keep working: the flag loads as `false`, so existing Microsoft
accounts behave exactly as before.

## How to use

1. Open the launcher. Top-right account pill → **Add offline account** (or just press Play while signed out, then pick the offline link in the sign-in dialog).
2. Type a username (3-16 characters, letters/numbers/underscore), press **Play offline**.
3. Play. Works on singleplayer, LAN and any server with `online-mode=false`.

Multiple accounts still work: mix Microsoft and offline accounts in the account
switcher; each gets its own cosmetics/Locker folder keyed by UUID.

## What offline accounts can't do

- Join `online-mode` (premium-checking) servers.
- Velo Network extras: friends, parties, cosmetics sync, Velo+ - these need a real
  Microsoft account. Everything that doesn't need the network still works, and the
  launcher shows *"Offline account - Velo Network needs a Microsoft sign-in"* instead
  of pretending to connect.
- Real-name collisions: two people picking the same offline username are the same
  account on offline-mode servers. That's inherent to Minecraft's offline mode.

## Building this fork

The launcher targets Java 21 but must be **compiled on JDK 25** (the repo's CI pins
`java-version: "25"`; `java.lang.foreign` is preview in 21 and errors out).

```bat
set JAVA_HOME=C:\path\to\jdk-25
gradle.bat :launcher:compileJava   # fast check, launcher only
gradle.bat :launcher:run           # run the launcher from source
```

`gradlew` wrapper download can time out on slow networks (`networkTimeout=10000` in
`gradle/wrapper/gradle-wrapper.properties`); installing Gradle 9.5 manually and calling
`gradle.bat` directly avoids it. A full installer build (`:launcher:jpackageDist`) also
bundles the per-version client jars and needs the whole monorepo built - much heavier.

## Not included (deliberately)

Nothing here spoofs premium sessions or Mojang's auth servers: an offline account can
only ever do what an offline-mode vanilla server would allow it. Velo Network and
Mojang-verified features stay Microsoft-only.
