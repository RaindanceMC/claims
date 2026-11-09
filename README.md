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

- `claims-standalone/target/RainsExtendedClaims-2.26.0.jar` — also published as `de.raindancer.modules:claims-standalone:2.26.0`

## Server claims

Land the server itself holds (spawn, a market square): a claim with no owners and `server: true` in its stored
document, read as "Server" wherever an owner is named. Never counted against a limit, no price, upkeep, entry fee or
lapse. Created fully protected — visitors may enter, craft and trade only; PvP, fire spread, explosions, mob griefing
and hostile spawning off (`Claim.SERVER_CLAIM_DENIED_FLAGS`). Overlaps with players' claims are refused unless forced.

`/serverclaim` (also `/claimadmin server …`, permission `rainsclaims.admin.server`, implied by `rec.admin`):
`create <name>` (marking tool, full height) or `create <name> <world> <x1> <z1> <x2> <z2> [force]` from the console
(round: `create <name> <world> circle <x> <z> <radius>` — `ClaimShape.circle`, an outline accurate to the block),
`resize` (same shapes), `rename`, `delete`, `info`, `flag <name> <flag> <allow|deny|default>`, `public <name> <action> <allow|deny>`,
`trust`/`untrust <name> <player>`, `open`. A rank is trusted by `rainsclaims.server.trust.<name>` or
`rainsclaims.server.trust.*` (only when explicitly granted). The admin bypass still builds everywhere.
In the menus: `/claimadmin` → *The server's own claims*.

## Art

Blockbench sources and previews for the custom looks (selection sticks, waystone) are in `claims-module/art/`; the
exported models and textures ship in `claims-module/src/main/resources/de/raindancer/modules/claims/pack/`. A placed
waystone's flame is vanilla's floor fire on a display of its own, so it can flare and turn to soul fire in use.

## Versions

A module's version is a property in this repository's root `pom.xml`, a plugin's is `plugin.version` in its own pom. CI publishes every version that is not in the registry yet on the next push to master.
