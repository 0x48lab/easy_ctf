# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.3.0] - 2026-08-31

### Added
- **Build phase territory restriction (zone system)** — the map is split into HOME / NEUTRAL / ENEMY zones by the distance ratio between the two beacons. During the build phase, no block of any type can be placed in enemy territory, so build time goes into constructing routes through the neutral zone instead of griefing the enemy base.
  - `mechanics.build-phase.territory-restriction`, `enemy-territory-ratio`, `enemy-beacon-no-build-radius`
- **Home core safe area** — inside `mechanics.home-safe-radius` of your own beacon/spawn, enemy colored blocks no longer drain shield, preventing the "enemy carpets your own base and turns it into their territory" exploit.
- **Fast removal of enemy blocks in the home core** — enemy-placed blocks inside the home core break instantly and bypass the adjacent-block restriction, fixing the asymmetry between placing (one click) and removing (pickaxe time).
  - `mechanics.fast-remove-in-home`
- **Cleanup refund** — removing an enemy-placed block inside the home core refunds team currency, making griefing a transfer of resources to the defender.
  - `mechanics.home-cleanup-reward`, `home-cleanup-reward-cap`
- Non-team block placements (shop-bought blocks such as obsidian) are now tracked with their placing team, which previously was not recorded at all.
- Zone-based block placement statistics logged at phase end (`[ZoneStats]`) for balance tuning.
- New message key `action-bar.cannot-place-enemy-territory` (ja/en).

### Changed
- Spawn locations used for territory checks are cached in `TerritorySystem`; `GameManager.getGameConfig()` reads YAML from disk on every call and must not be hit from the per-tick shield task.

### Added
- Initial release of EasyCTF plugin
- Multiple concurrent CTF games support
- Match system for tournament-style gameplay
- Shop system with team-shared currency
- Spectator mode support
- Multi-language support (Japanese and English)
- Map creation and saving system with temporary worlds
- Initial equipment system with diamond pickaxe and shovel
- Build phase and combat phase game mechanics
- Team-based block placement system
- Flag capture mechanics with beacons

### Changed
- Improved duplicate initial item prevention system
- Enhanced shop system with dynamic pricing

### Removed
- Removed water and lava buckets from shop for balance
- Removed pickaxes and shovels from shop (provided as initial equipment)