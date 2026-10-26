# RaindanceMC/claims

- **`claims-module`** — Rain's Flexible Modules — Claims: Land claims: the model, the protection, the commands and the screens. Registers itself as RainsCore's answer to "may this player do that here?", so every other plugin on the server can ask without knowing this module exists.
- **`claims-standalone`** — Rain's Extended Claims: Rain's Extended Claims, as a plugin of its own. Nothing here but a build file and a descriptor: the claims module and the standard wrapper are shaded together, and that is the whole plugin. The name and the data folder are unchanged from the standalone plugin it replaces, so a server upgrading keeps its claims.

Part of [RaindanceMC](https://github.com/RaindanceMC); built on [RainsCore](https://github.com/RaindanceMC/RainsCore) and the shared parent in [framework](https://github.com/RaindanceMC/framework). Split out of `rains-flexible-modules` on 16.10.2026, history kept.

## Build

```
mvn install
```

Needs Java 25 and read access to `packages.tstieh.de` (server ids `packages-releases` / `packages-snapshots` in `~/.m2/settings.xml`).

## Plugins

- `claims-standalone/target/RainsExtendedClaims-2.19.0.jar` — also published as `de.raindancer.modules:claims-standalone:2.19.0`

## Art

Blockbench sources and previews for the custom looks (selection sticks, waystone) are in `claims-module/art/`; the
exported models and textures ship in `claims-module/src/main/resources/de/raindancer/modules/claims/pack/`. A placed
waystone's flame is vanilla's floor fire on a display of its own, so it can flare and turn to soul fire in use.

## Versions

A module's version is a property in this repository's root `pom.xml`, a plugin's is `plugin.version` in its own pom. CI publishes every version that is not in the registry yet on the next push to master.
