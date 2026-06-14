# ItemNamePlugin

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A lightweight Paper plugin that shows an item's **display name and stack amount** as a floating label above dropped items on the ground.

---

## Features

| Feature | Description |
|---|---|
| **Floating item labels** | Dropped items with a custom display name show a visible name tag above them. |
| **Stack amount** | The label also shows the current stack size with an `x` separator (e.g., `Diamond x64`). Updates automatically if stacks merge. |
| **Colored amounts** | Amount is color-coded by how full the stack is: red → yellow → green → aqua. |
| **Distance culling** | Name tags are hidden when no player is within 32 blocks, reducing clutter and improving performance. |
| **Armor-stand based** | Uses an invisible, marker armor stand so the label is smooth and collision-free. |
| **Automatic cleanup** | Labels are removed when the item is picked up, despawns, or the plugin disables. |
| **No configuration needed** | Works out of the box — just drop an item with a name. |

---

## How It Works

When a named item entity spawns in the world, the plugin spawns an invisible armor stand slightly above it and sets the stand's custom name to the item's display name plus its stack amount. A tracker task runs every tick to keep the label positioned above the item and refreshes the amount if stacks merge.

---

## Usage

1. Rename any item (e.g., with an anvil).
2. Drop it on the ground.
3. The item's name appears as a floating label above it.

---

## Requirements

| Requirement | Version |
|---|---|
| Server software | [Paper](https://papermc.io/downloads/paper) |
| Minecraft / Paper API | 26.1 (Minecraft 1.21.x) |
| Java | 25 |

---

## Installation

1. Download the latest `itemnameplugin-*.jar` from the [Releases](../../releases) page.
2. Place the JAR in your server's `plugins/` directory.
3. Restart your server.

---

## Building from Source

```bash
git clone <repo-url>
cd ItemNamePlugin
mvn package
```

The compiled JAR will be at `target/ItemNamePlugin-1.0.0.jar`.

---

## License

This project is released under the [MIT License](LICENSE).
