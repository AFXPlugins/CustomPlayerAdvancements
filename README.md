# CustomPlayerAdvancements
![Image of a customized advancement message.](https://cdn.modrinth.com/data/cached_images/1b94b374acb542b59663a5edbfbb67882336817c.png)
## Info
- **Display custom nicknames instead of usernames in advancement messages.**
- **Fully customize advancement messages and colors.**
## Requirements
- [ProtocolLib](https://modrinth.com/plugin/protocollib) (required - plugin functionality)
- [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) (required - placeholder support)
## Configuration
```yaml
format: "%luckperms_prefix%%essentials_nickname%"

messages:
  task:
    phrase: "&bhas made the advancement"
    advancement-color: "&a"

  goal:
    phrase: "&bhas reached the goal"
    advancement-color: "&e"

  challenge:
    phrase: "&bhas completed the challenge"
    advancement-color: "&d"
```
- `format` — The format used for the name portion of the advancement message that replaces the player's username. Supports PlaceholderAPI placeholders and `&` color codes.
- `messages.*.phrase` — The main message for each advancement type.
- `messages.*.advancement-color` — The color applied to the advancement name.
## Commands
- `/ancustomizer reload` — Reloads the plugin configuration. Requires `ancustomizer.reload` (default: OP).
## Permissions
- `advancementcustomizer.admin` — Allows use of all CustomPlayerAdvancement commands.
