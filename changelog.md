- REQUIRES [CraterLib](https://www.curseforge.com/minecraft/mc-mods/craterlib) - [Modrinth](https://modrinth.com/mod/craterlib)
- [Online Config Editor](https://editor.firstdark.dev)
- [Documentation](https://sdlink.fdd-docs.com)
- This single jar works on 1.18.2-26.2

*Requires CraterLib 3.1.3 or newer*

### If you find a bug please report it. If nobody reports issues, they can't be fixed

**Changes**:

- Existing account links and hidden players migrate automatically from JSON to SQLite. Pending verification codes from JSON are invalidated because their issue times are unknown; affected players must request a new code. Existing configuration files need no changes.
- Added official config toggle to ignore canceled chat events (fixes compat with mods like FTB Teams, etc). Requires CraterLib update - HypherionSA
- Add config toggle to relay commands used by hidden players. Disabled by default - HypherionSA

**Bug Fixes**:

- FTB Ranks that do not include conditionally assigned ranks in rank sync - John Clardy
- Topic updates should update thread channel parent topic - HypherionSA
