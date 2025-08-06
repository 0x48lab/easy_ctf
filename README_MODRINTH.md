# EasyCTF - Minecraft Capture The Flag Plugin

EasyCTF is a comprehensive Capture The Flag plugin for Minecraft Paper servers, designed to provide an engaging team-based PvP experience with building and combat phases.

## Features

- **Multiple Concurrent Games**: Run unlimited CTF games simultaneously on the same server
- **Match System**: Tournament-style gameplay with configurable rounds
- **Three-Phase Gameplay**:
  - **Build Phase**: Teams construct defenses with selectable game modes (ADVENTURE/SURVIVAL/CREATIVE)
  - **Combat Phase**: Teams battle to capture the enemy flag with strategic shop purchases
  - **Strategy Meeting Phase**: Review results, MVP awards, and prepare for next round
- **Advanced Building System**:
  - Block connection management - blocks must connect to beacon or existing team blocks
  - Disconnected blocks automatically neutralize (turn white)
  - Multiple spawn points support with 3x3 protected platforms
- **Shop System**: 
  - Team-shared currency (G) with dynamic pricing
  - Automatic discounts for losing teams (up to 40%)
  - Item death behaviors: KEEP/DROP/DESTROY
- **Spectator Mode**: Watch ongoing games without participating
- **Multi-language Support**: Japanese and English with color code support (&c, &9, etc.)
- **Temporary World System**: Each game runs in isolated temporary world
- **Map Compression**: Efficient GZIP + Base64 map storage
- **Multiple Spawn Points**: Set multiple respawn locations per team (randomly selected)
- **Spawn Protection**: 3-second invincibility after respawn (cancelled on attack or flag pickup)
- **Customizable Respawn Delays**: Base delay + death penalty system with maximum cap

## Requirements

- Paper Server 1.21.5+
- Java 21+

## Quick Start

1. Download the plugin JAR file
2. Place it in your server's `plugins` folder
3. Restart the server
4. Create a game with `/ctf create <game_name>` or use automatic map detection:
   - `/ctf setpos1` - Set map region start (auto-extends to world height)
   - `/ctf setpos2` - Set map region end
   - Place red/blue concrete blocks for spawn points (multiple allowed, min 4 blocks apart)
   - Place beacon + red/blue glass for team flags (exactly 1 per team)
   - `/ctf savemap <game_name>` - Save and compress the map
5. Start the game with `/ctf start <game_name>`

## Commands

### Player Commands
- `/ctf list` - List all games
- `/ctf join <name>` - Join a game
- `/ctf leave` - Leave current game
- `/ctf team [red|blue]` - Check or change team
- `/ctf status [name]` - Check game status
- `/ctf spectator [name]` - Join as spectator

### Admin Commands
- `/ctf create <name>` - Create a new CTF game (interactive)
- `/ctf update <name>` - Update game settings (interactive)
- `/ctf delete <name>` - Delete a game
- `/ctf start <name> [match] [rounds]` - Start a game or match
- `/ctf stop <name>` - Stop a game
- `/ctf setflag <name> <red|blue>` - Manually set flag location
- `/ctf setspawn <name> <red|blue>` - Manually set spawn location
- `/ctf addspawn <name> <red|blue>` - Add spawn point
- `/ctf removespawn <name> <red|blue> <number>` - Remove spawn point
- `/ctf listspawns <name>` - List all spawn points
- `/ctf setpos1 [name]` - Set map region start (temporary if name omitted)
- `/ctf setpos2 [name]` - Set map region end (temporary if name omitted)
- `/ctf savemap <name>` - Save map with auto-detection

## Configuration

The plugin can be configured through:
- `config.yml` - Global settings including:
  - Default phase durations and game modes
  - Currency and shop settings
  - Match system configuration
  - Respawn delay settings
- `lang_ja.yml` / `lang_en.yml` - Fully customizable language files
- Individual game settings saved in `plugins/EasyCTF/games/`
- Compressed maps saved in `plugins/EasyCTF/maps/`

## Support

- [GitHub Issues](https://github.com/0x48lab/easy_ctf/issues)
- [Documentation](https://0x48lab.github.io/easy_ctf/)
- [Wiki](https://github.com/0x48lab/easy_ctf/wiki)

## License

This plugin is provided as-is for educational and entertainment purposes.