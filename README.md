# ItemName

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A lightweight Paper plugin that shows an item's **display name and combined stack amount** as a floating label above dropped items on the ground. Nearby matching items are grouped into a single label.

---

## Features

| Feature | Description |
|---|---|
| **Floating item labels** | Dropped items show a visible name tag above them. |
| **Nearby grouping** | Matching items within ~2.5 blocks share a single label with their combined amount. |
| **Combined amount** | The label shows the total amount of all grouped items (e.g., `Diamond x256`). |
| **Colored amounts** | Amount is color-coded by how full the stack is: red → yellow → green → aqua; oversized stacks are gold. |
| **Distance culling** | Name tags are hidden when no player is within 32 blocks, reducing clutter and improving performance. |
| **Armor-stand based** | Uses an invisible, marker armor stand so the label is smooth and collision-free. |
| **Automatic cleanup** | Labels are removed when the item is picked up, despawns, or the plugin disables. |
| **No configuration needed** | Works out of the box — just drop an item with a name. |

---

## How It Works

When a dropped item spawns, the plugin looks for an existing group of matching items within ~2.5 blocks. If found, the item joins that group; otherwise a new group is created with its own invisible armor stand. The stand's name shows the item's display name plus the combined amount of every item in the group. A tracker task runs every tick to keep the label centered above the pile and refreshes the total amount as items are added, picked up, or despawn.

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

1. Download the latest `itemname-*.jar` from the [Releases](../../releases) page.
2. Place the JAR in your server's `plugins/` directory.
3. Restart your server.

---

## Building from Source

```bash
git clone <repo-url>
cd ItemNamePlugin
mvn package
```

The compiled JAR will be at `target/ItemName-1.0.0.jar`.

---

## License

This project is released under the [MIT License](LICENSE).
