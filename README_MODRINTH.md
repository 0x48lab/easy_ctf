# EasyCTF - Minecraft Capture The Flag Plugin

EasyCTF is a comprehensive Capture The Flag plugin for Minecraft Paper servers, designed to provide an engaging team-based PvP experience with building and combat phases.

## Features

- **Multiple Concurrent Games**: Run unlimited CTF games simultaneously on the same server
- **Match System**: Tournament-style gameplay with configurable rounds
- **Two-Phase Gameplay**:
  - **Build Phase**: Teams construct defenses and paths using team-colored blocks
  - **Combat Phase**: Teams battle to capture the enemy flag
- **Shop System**: Team-shared currency system with balanced economy
- **Spectator Mode**: Watch ongoing games without participating
- **Multi-language Support**: Available in Japanese and English
- **Temporary World System**: Each game runs in its own temporary world
- **Initial Equipment**: Players start with efficiency-enchanted diamond tools

## Requirements

- Paper Server 1.21.5+
- Java 21+

## Quick Start

1. Download the plugin JAR file
2. Place it in your server's `plugins` folder
3. Restart the server
4. Create a game with `/ctf create <game_name>`
5. Start the game with `/ctf start <game_name>`

## Commands

- `/ctf create <name>` - Create a new CTF game
- `/ctf start <name> [match] [rounds]` - Start a game or match
- `/ctf join <name>` - Join a game
- `/ctf leave` - Leave current game
- `/ctf list` - List all games
- `/ctf help` - Show help

## Configuration

The plugin can be configured through:
- `config.yml` - Global settings
- `lang_ja.yml` / `lang_en.yml` - Language files
- Individual game settings saved in `plugins/EasyCTF/games/`

## Support

- [GitHub Issues](https://github.com/Hack-Lab-Manabu/easy_ctf/issues)
- [Documentation](https://github.com/Hack-Lab-Manabu/easy_ctf/wiki)

## License

This plugin is provided as-is for educational and entertainment purposes.