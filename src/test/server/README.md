# Disposable Paper 26.2 startup check

Build Odysseia with Java 25 and `-Dpaper26=true`. Set
`ODYSSEIA_TEST_CLASSPATH` to the built plugin and its Maven compile dependencies,
then compile this fixture separately from the gameplay artifact:

```sh
javac --release 25 -cp "$ODYSSEIA_TEST_CLASSPATH" -d "$TEST_CLASSES" src/test/server/OdysseiaStartupSmoke.java
jar --create --file "$TEST_PLUGIN_JAR" -C "$TEST_CLASSES" . -C src/test/server plugin.yml
```

Install both jars in an empty, loopback-only Paper/Purpur 26.2 server with
PlaceholderAPI, Vault and LuckPerms. Use an isolated configuration with external
integrations disabled and test kit contents; do not copy player databases,
credentials, worlds, or claim records. Keep the base-modality setting empty.

The fixture checks plugin startup, services, all five automation event handlers,
optional kit definitions and the existing PDC namespace. Success is
`ODYSSEIA_STARTUP_SMOKE PASS checks=9`; require a completed server startup and
review errors separately. The fixture saves and stops its disposable server.
It must never be installed on a player server.

This check does not deliver kits or certify client travel. The MockBukkit tests
exercise delivery, pending tasks and duplicate prevention; full Java/Bedrock
journeys remain a separate functional requirement.
