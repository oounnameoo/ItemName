package com.example.itemname;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ItemName extends JavaPlugin implements Listener {

    /** Items within this radius (blocks) of a group member are merged into one label. */
    private static final double GROUP_RADIUS = 2.5;
    private static final double GROUP_RADIUS_SQUARED = GROUP_RADIUS * GROUP_RADIUS;

    /** Labels are hidden when no player is within this distance of any item in the group. */
    private static final double VIEW_DISTANCE = 32.0;
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;

    /** Vertical offset above the item for the text label. */
    private static final double LABEL_Y_OFFSET = 0.5;

    /** A group of matching dropped items that share one TextDisplay label. */
    private static final class Group {
        final UUID displayId;
        final Set<UUID> itemIds = new HashSet<>();

        Group(UUID displayId) {
            this.displayId = displayId;
        }
    }

    /** item UUID -> group UUID */
    private final Map<UUID, UUID> itemToGroup = new ConcurrentHashMap<>();
    /** group UUID -> Group */
    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();
    /** display UUID -> group UUID */
    private final Map<UUID, UUID> displayToGroup = new ConcurrentHashMap<>();

    private BukkitRunnable trackerTask;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        startTrackerTask();
        getLogger().info("ItemName enabled.");
    }

    @Override
    public void onDisable() {
        if (trackerTask != null) {
            trackerTask.cancel();
        }
        for (Group group : groups.values()) {
            Entity entity = Bukkit.getEntity(group.displayId);
            if (entity != null) {
                entity.remove();
            }
        }
        itemToGroup.clear();
        groups.clear();
        displayToGroup.clear();
        getLogger().info("ItemName disabled.");
    }

    private void startTrackerTask() {
        trackerTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Group group : groups.values()) {
                    TextDisplay display = (TextDisplay) Bukkit.getEntity(group.displayId);
                    if (display == null || !display.isValid()) {
                        disbandGroup(group);
                        continue;
                    }

                    // Recompute active items in this group
                    Set<Item> activeItems = new HashSet<>();
                    int totalAmount = 0;
                    for (UUID itemId : new HashSet<>(group.itemIds)) {
                        Item item = (Item) Bukkit.getEntity(itemId);
                        if (item == null || !item.isValid()) {
                            group.itemIds.remove(itemId);
                            itemToGroup.remove(itemId);
                            continue;
                        }
                        activeItems.add(item);
                        totalAmount += item.getItemStack().getAmount();
                    }

                    if (activeItems.isEmpty()) {
                        display.remove();
                        UUID groupId = displayToGroup.remove(group.displayId);
                        if (groupId != null) {
                            groups.remove(groupId);
                        }
                        continue;
                    }

                    // Move label to weighted center of the group
                    Location center = calculateCenter(activeItems);
                    Location target = center.clone().add(0, LABEL_Y_OFFSET, 0);
                    if (display.getWorld() != target.getWorld()) {
                        display.teleport(target);
                    } else if (display.getLocation().distanceSquared(target) > 0.01) {
                        display.teleport(target);
                    }

                    // Update combined name and visibility
                    Item representative = activeItems.iterator().next();
                    boolean nearPlayer = isGroupNearAnyPlayer(activeItems);
                    display.text(nearPlayer ? getGroupName(representative, totalAmount) : Component.empty());
                }
            }
        };
        trackerTask.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();

        // Ignore empty/blank names
        Component baseName = getBaseName(stack);
        if (PlainTextComponentSerializer.plainText().serialize(baseName).isBlank()) {
            return;
        }

        // Try to join an existing nearby group with the same item
        Group group = findNearbyGroup(item);
        if (group != null) {
            group.itemIds.add(item.getUniqueId());
            itemToGroup.put(item.getUniqueId(), displayToGroup.get(group.displayId));
            return;
        }

        // Create a new group with a TextDisplay label — no flicker, no body, no ArmorStand.
        Location loc = item.getLocation().clone().add(0, LABEL_Y_OFFSET, 0);
        boolean nearPlayer = isNearAnyPlayer(item);
        TextDisplay display = (TextDisplay) item.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
        display.text(nearPlayer ? getGroupName(stack, stack.getAmount()) : Component.empty());
        display.setBillboard(Display.Billboard.CENTER);   // always faces the player
        display.setDefaultBackground(false);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(false);

        UUID groupId = UUID.randomUUID();
        Group newGroup = new Group(display.getUniqueId());
        newGroup.itemIds.add(item.getUniqueId());

        groups.put(groupId, newGroup);
        itemToGroup.put(item.getUniqueId(), groupId);
        displayToGroup.put(display.getUniqueId(), groupId);
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        removeItemFromGroup(event.getItem().getUniqueId());
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        removeItemFromGroup(event.getEntity().getUniqueId());
    }

    // ── Group helpers ─────────────────────────────────────────────────────────

    /** Find a group within GROUP_RADIUS that holds the same kind of item. */
    private Group findNearbyGroup(Item item) {
        Location loc = item.getLocation();
        ItemStack stack = item.getItemStack();
        for (Map.Entry<UUID, Group> entry : groups.entrySet()) {
            Group group = entry.getValue();
            if (group.itemIds.isEmpty()) {
                continue;
            }
            if (!groupHasSameItem(group, stack)) {
                continue;
            }
            for (UUID memberId : group.itemIds) {
                Item member = (Item) Bukkit.getEntity(memberId);
                if (member == null || !member.isValid()) {
                    continue;
                }
                if (member.getWorld() != loc.getWorld()) {
                    continue;
                }
                if (member.getLocation().distanceSquared(loc) <= GROUP_RADIUS_SQUARED) {
                    return group;
                }
            }
        }
        return null;
    }

    private boolean groupHasSameItem(Group group, ItemStack stack) {
        for (UUID memberId : group.itemIds) {
            Item member = (Item) Bukkit.getEntity(memberId);
            if (member == null || !member.isValid()) {
                continue;
            }
            if (isSameItem(stack, member.getItemStack())) {
                return true;
            }
        }
        return false;
    }

    private void removeItemFromGroup(UUID itemId) {
        UUID groupId = itemToGroup.remove(itemId);
        if (groupId == null) {
            return;
        }
        Group group = groups.get(groupId);
        if (group == null) {
            return;
        }
        group.itemIds.remove(itemId);
        if (group.itemIds.isEmpty()) {
            Entity display = Bukkit.getEntity(group.displayId);
            if (display != null) {
                display.remove();
            }
            displayToGroup.remove(group.displayId);
            groups.remove(groupId);
        }
    }

    private void disbandGroup(Group group) {
        UUID groupId = displayToGroup.remove(group.displayId);
        if (groupId != null) {
            groups.remove(groupId);
        }
        for (UUID itemId : group.itemIds) {
            itemToGroup.remove(itemId);
        }
    }

    /** Two stacks are "the same" for grouping if type, durability and meta match. */
    private boolean isSameItem(ItemStack a, ItemStack b) {
        return a.isSimilar(b);
    }

    private Location calculateCenter(Set<Item> items) {
        double x = 0, y = 0, z = 0;
        World world = null;
        int totalWeight = 0;
        for (Item item : items) {
            Location loc = item.getLocation();
            int weight = item.getItemStack().getAmount();
            x += loc.getX() * weight;
            y += loc.getY() * weight;
            z += loc.getZ() * weight;
            totalWeight += weight;
            world = loc.getWorld();
        }
        if (totalWeight == 0) {
            return items.iterator().next().getLocation();
        }
        return new Location(world, x / totalWeight, y / totalWeight, z / totalWeight);
    }

    private boolean isGroupNearAnyPlayer(Set<Item> items) {
        for (Item item : items) {
            if (isNearAnyPlayer(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNearAnyPlayer(Item item) {
        Location itemLoc = item.getLocation();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != itemLoc.getWorld()) {
                continue;
            }
            if (player.getLocation().distanceSquared(itemLoc) <= VIEW_DISTANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    // ── Name helpers ──────────────────────────────────────────────────────────

    private Component getGroupName(Item representative, int totalAmount) {
        return getGroupName(representative.getItemStack(), totalAmount);
    }

    private Component getGroupName(ItemStack stack, int totalAmount) {
        Component name = getBaseName(stack);
        int max = stack.getMaxStackSize();
        NamedTextColor amountColor = getAmountColor(totalAmount, max);
        return name.append(Component.text(" x" + totalAmount, amountColor));
    }

    private Component getBaseName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta.hasDisplayName()) {
            return meta.displayName();
        }
        return Component.translatable(stack.getType().translationKey());
    }

    private NamedTextColor getAmountColor(int amount, int max) {
        if (max == 1) {
            return NamedTextColor.WHITE;
        }
        if (amount > max) {
            return NamedTextColor.GOLD;
        }
        double ratio = (double) amount / max;
        if (ratio <= 0.25) {
            return NamedTextColor.RED;
        } else if (ratio <= 0.5) {
            return NamedTextColor.YELLOW;
        } else if (ratio <= 0.75) {
            return NamedTextColor.GREEN;
        } else {
            return NamedTextColor.AQUA;
        }
    }
}
