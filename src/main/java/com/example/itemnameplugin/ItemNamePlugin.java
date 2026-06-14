package com.example.itemnameplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
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

public class ItemNamePlugin extends JavaPlugin implements Listener {

    /** Items within this radius (blocks) of a group center are merged into one label. */
    private static final double GROUP_RADIUS = 2.5;
    private static final double GROUP_RADIUS_SQUARED = GROUP_RADIUS * GROUP_RADIUS;

    /** Labels are hidden when no player is within this distance of any item in the group. */
    private static final double VIEW_DISTANCE = 32.0;
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;

    /** A group of matching dropped items that share one armor-stand label. */
    private static final class Group {
        final UUID standId;
        final Set<UUID> itemIds = new HashSet<>();

        Group(UUID standId) {
            this.standId = standId;
        }
    }

    /** item UUID -> group UUID */
    private final Map<UUID, UUID> itemToGroup = new ConcurrentHashMap<>();
    /** group UUID -> Group */
    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();
    /** stand UUID -> group UUID */
    private final Map<UUID, UUID> standToGroup = new ConcurrentHashMap<>();

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
            Entity entity = Bukkit.getEntity(group.standId);
            if (entity != null) {
                entity.remove();
            }
        }
        itemToGroup.clear();
        groups.clear();
        standToGroup.clear();
        getLogger().info("ItemName disabled.");
    }

    private void startTrackerTask() {
        trackerTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Group group : groups.values()) {
                    ArmorStand stand = (ArmorStand) Bukkit.getEntity(group.standId);
                    if (stand == null || !stand.isValid()) {
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
                        stand.remove();
                        UUID groupId = standToGroup.remove(group.standId);
                        if (groupId != null) {
                            groups.remove(groupId);
                        }
                        continue;
                    }

                    // Move label to weighted center of the group
                    Location center = calculateCenter(activeItems);
                    Location target = center.clone().add(0, 0.75, 0);
                    if (stand.getWorld() != target.getWorld()) {
                        stand.teleport(target);
                    } else if (stand.getLocation().distanceSquared(target) > 0.01) {
                        stand.teleport(target);
                    }

                    // Update combined name and visibility
                    Item representative = activeItems.iterator().next();
                    stand.customName(getGroupName(representative, totalAmount));
                    stand.setCustomNameVisible(isGroupNearAnyPlayer(activeItems));
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
            itemToGroup.put(item.getUniqueId(), standToGroup.get(group.standId));
            return;
        }

        // Create a new group with its own armor stand
        Location loc = item.getLocation().clone().add(0, 0.75, 0);
        ArmorStand stand = (ArmorStand) item.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setGravity(false);
        stand.setCanPickupItems(false);
        stand.customName(getGroupName(stack, stack.getAmount()));
        stand.setCustomNameVisible(isNearAnyPlayer(item));
        stand.setInvulnerable(true);
        stand.setCollidable(false);
        stand.setPersistent(false);

        UUID groupId = UUID.randomUUID();
        Group newGroup = new Group(stand.getUniqueId());
        newGroup.itemIds.add(item.getUniqueId());

        groups.put(groupId, newGroup);
        itemToGroup.put(item.getUniqueId(), groupId);
        standToGroup.put(stand.getUniqueId(), groupId);
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
            Item representative = (Item) Bukkit.getEntity(group.itemIds.iterator().next());
            if (representative == null || !representative.isValid()) {
                continue;
            }
            if (representative.getWorld() != loc.getWorld()) {
                continue;
            }
            if (!isSameItem(stack, representative.getItemStack())) {
                continue;
            }
            if (representative.getLocation().distanceSquared(loc) <= GROUP_RADIUS_SQUARED) {
                return group;
            }
        }
        return null;
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
            Entity stand = Bukkit.getEntity(group.standId);
            if (stand != null) {
                stand.remove();
            }
            standToGroup.remove(group.standId);
            groups.remove(groupId);
        }
    }

    private void disbandGroup(Group group) {
        UUID groupId = standToGroup.remove(group.standId);
        if (groupId != null) {
            groups.remove(groupId);
        }
        for (UUID itemId : group.itemIds) {
            itemToGroup.remove(itemId);
        }
    }

    /** Two stacks are "the same" for grouping if material and custom name match. */
    private boolean isSameItem(ItemStack a, ItemStack b) {
        if (a.getType() != b.getType()) {
            return false;
        }
        Component nameA = getBaseName(a);
        Component nameB = getBaseName(b);
        return PlainTextComponentSerializer.plainText().serialize(nameA)
                .equals(PlainTextComponentSerializer.plainText().serialize(nameB));
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
