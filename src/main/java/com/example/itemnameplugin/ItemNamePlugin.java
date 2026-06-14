package com.example.itemnameplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ItemNamePlugin extends JavaPlugin implements Listener {

    private final Map<UUID, UUID> itemToNameTag = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> nameTagToItem = new ConcurrentHashMap<>();
    private BukkitRunnable trackerTask;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        startTrackerTask();
        getLogger().info("ItemNamePlugin enabled.");
    }

    @Override
    public void onDisable() {
        if (trackerTask != null) {
            trackerTask.cancel();
        }
        for (UUID nameTagId : nameTagToItem.keySet()) {
            Entity entity = Bukkit.getEntity(nameTagId);
            if (entity != null) {
                entity.remove();
            }
        }
        itemToNameTag.clear();
        nameTagToItem.clear();
        getLogger().info("ItemNamePlugin disabled.");
    }

    private void startTrackerTask() {
        trackerTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<UUID, UUID> entry : itemToNameTag.entrySet()) {
                    Item item = (Item) Bukkit.getEntity(entry.getKey());
                    ArmorStand stand = (ArmorStand) Bukkit.getEntity(entry.getValue());

                    if (item == null || !item.isValid()) {
                        if (stand != null) {
                            stand.remove();
                            nameTagToItem.remove(stand.getUniqueId());
                        }
                        continue;
                    }

                    if (stand == null || !stand.isValid()) {
                        itemToNameTag.remove(item.getUniqueId());
                        continue;
                    }

                    Location target = item.getLocation().clone().add(0, 0.75, 0);
                    if (stand.getWorld() != target.getWorld()) {
                        stand.teleport(target);
                    } else {
                        Location current = stand.getLocation();
                        if (current.distanceSquared(target) > 0.01) {
                            stand.teleport(target);
                        }
                    }

                    // Refresh name in case stacks merged and the amount changed
                    stand.customName(getDisplayName(item));
                }
            }
        };
        trackerTask.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (itemToNameTag.containsKey(item.getUniqueId())) {
            return;
        }

        Component displayName = getDisplayName(item);
        if (PlainTextComponentSerializer.plainText().serialize(displayName).isBlank()) {
            return;
        }

        Location loc = item.getLocation().clone().add(0, 0.75, 0);
        ArmorStand stand = (ArmorStand) item.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setGravity(false);
        stand.setCanPickupItems(false);
        stand.customName(displayName);
        stand.setCustomNameVisible(true);
        stand.setInvulnerable(true);
        stand.setCollidable(false);
        stand.setPersistent(false);

        itemToNameTag.put(item.getUniqueId(), stand.getUniqueId());
        nameTagToItem.put(stand.getUniqueId(), item.getUniqueId());
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        removeNameTag(event.getItem().getUniqueId());
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        removeNameTag(event.getEntity().getUniqueId());
    }

    private void removeNameTag(UUID itemId) {
        UUID standId = itemToNameTag.remove(itemId);
        if (standId != null) {
            nameTagToItem.remove(standId);
            Entity entity = Bukkit.getEntity(standId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private Component getDisplayName(Item item) {
        int amount = item.getItemStack().getAmount();
        int max = item.getItemStack().getMaxStackSize();
        double ratio = max > 0 ? (double) amount / max : 0;

        NamedTextColor amountColor;
        if (max == 1) {
            amountColor = NamedTextColor.WHITE;
        } else if (ratio <= 0.25) {
            amountColor = NamedTextColor.RED;
        } else if (ratio <= 0.5) {
            amountColor = NamedTextColor.YELLOW;
        } else if (ratio <= 0.75) {
            amountColor = NamedTextColor.GREEN;
        } else {
            amountColor = NamedTextColor.AQUA;
        }

        return item.getItemStack().displayName()
                .append(Component.text(" " + amount, amountColor));
    }
}
