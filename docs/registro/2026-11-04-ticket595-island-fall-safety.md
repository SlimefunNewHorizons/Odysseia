# #595 — opt-in fall protection for island worlds

The existing `VoidLoopAndSafetyListener` handles `VOID` damage and movement
below the void threshold. It does not cancel `FALL` damage when a player lands
after a dimensional loop or island rescue. Resetting fall distance during a
teleport does not protect a later landing after another fall.

`IslandFallSafetyListener` cancels player `FALL` damage only when
`island-fall-safety.enabled` is true and the current world appears in the exact
`island-fall-safety.worlds` list. Defaults list the overworld, Nether and End of
SkyBlock, OneBlock and OneChunk. The switch defaults to **false**, preserving
existing behaviour. Other damage causes and unlisted worlds are unaffected.
The handler rereads config, so disabling it after a supported config reload
does not require replacing the listener. No teleport or inventory operation is
performed by this guard.

Existing handlers were inventoried before editing: `onVoidDamage` and
`onPlayerMove`, their rescue methods and cooldown map remain intact. The only
change to the main plugin is an additional listener registration.

Validation on `main`: protected JDK 21 `mvn -B -ntp clean package`, 400 tests,
zero failures/errors/skips, including 25 guard regressions. Cases cover all
nine dimensions, unlisted worlds and similar names, non-player entities,
unrelated damage causes, default-off and disabling the switch at runtime.
The jar includes the new listener, class version 65 and Mojang namespace.

This is **not active or runtime-verified**. The server reports offline, its
hosting egg has an open incident, and the local T2 is deliberately stopped.
Do not start either server for this change. Before activation, test an actual
fall and dimensional loop on T2, verify other damage remains normal, publish
the matching guide update and announce the activation. Separately track the
OneChunk border/dimension problem in #594; this guard does not fix it.

Rollback: leave `island-fall-safety.enabled: false`, or restore the previous
artifact using the controlled deployment process. Pre-change source/config
backups are verified in Drive under
`jar-rollback/2026-11-04/ticket595/`. Never place backup files in plugin data
directories. Guide ES/EN text explicitly labels this protection as pending.
