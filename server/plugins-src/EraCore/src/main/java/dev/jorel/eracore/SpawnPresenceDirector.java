package dev.jorel.eracore;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Ultra-light spawn ambience using server-side stand-ins only.
 *
 * No Mineflayer clients, sockets, pathfinding, chunk subscriptions, combat AI,
 * inventories, or network sessions. Each stand-in is one ArmorStand entity
 * that occasionally teleports a short distance or changes facing.
 */
final class SpawnPresenceDirector {
    private static final class Presence {
        ArmorStand stand;
        Location anchor;
        Location target;
        long idleUntil;
        boolean walking;
    }

    private final EraCore plugin;
    private final WarpManager warps;
    private final Random rng = new Random(20141122L);
    private final List<Presence> presences = new ArrayList<Presence>();
    private BukkitTask task;

    SpawnPresenceDirector(EraCore plugin, WarpManager warps) {
        this.plugin = plugin;
        this.warps = warps;
    }

    void start() {
        stop();

        Location spawn = warps.getSpawn();
        World world = spawn.getWorld();
        if (world == null) return;

        List<String> names = new ArrayList<String>(plugin.getConfig().getStringList("spawn-presence.names"));
        if (names.isEmpty()) {
            names.addAll(Arrays.asList("xRico","PurpleDino","BreezyMC","MasonHD","NightPvP","Vexing"));
        }

        // Always remove legacy stand-ins first. In real-player-only mode no
        // ArmorStand impersonators are created at all.
        cleanupOld(world, spawn, names);
        if (plugin.getConfig().getBoolean("spawn-presence.real-players-only", true) ||
            !plugin.getConfig().getBoolean("spawn-presence.enabled", false)) return;

        int count = Math.max(1, Math.min(8, plugin.getConfig().getInt("spawn-presence.count", 4)));

        for (int i = 0; i < count; i++) {
            String name = names.get(i % names.size());
            Location loc = findNearbyFloor(spawn, 3 + i * 2);
            Presence p = create(name, loc, i);
            if (p != null) presences.add(p);
        }

        long period = Math.max(10L, plugin.getConfig().getLong("spawn-presence.update-ticks", 20L));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { tick(); }
        }, period, period);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Presence p : presences) {
            if (p.stand != null && !p.stand.isDead()) p.stand.remove();
        }
        presences.clear();
    }

    void showTo(org.bukkit.entity.Player ignored) {
        // Bukkit entities are automatically visible to nearby players.
    }

    private Presence create(String name, Location loc, int index) {
        try {
            ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class);
            stand.setCustomName(EraCore.colorText("&7" + name));
            stand.setCustomNameVisible(true);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setSmall(false);

            ItemStack skull = new ItemStack(Material.SKULL_ITEM, 1, (short)3);
            SkullMeta meta = (SkullMeta)skull.getItemMeta();
            meta.setOwner(name);
            skull.setItemMeta(meta);
            stand.setHelmet(skull);

            // Visual variety only; this is not the stand-in's actual stored gear.
            if (index % 4 == 0) {
                stand.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                stand.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
                stand.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
            } else if (index % 4 == 1) {
                stand.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
                stand.setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
                stand.setBoots(new ItemStack(Material.LEATHER_BOOTS));
            } else if (index % 4 == 2) {
                stand.setChestplate(new ItemStack(Material.GOLD_CHESTPLATE));
                stand.setLeggings(new ItemStack(Material.GOLD_LEGGINGS));
                stand.setBoots(new ItemStack(Material.GOLD_BOOTS));
            } else {
                stand.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                stand.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                stand.setBoots(new ItemStack(Material.IRON_BOOTS));
            }

            Presence p = new Presence();
            p.stand = stand;
            p.anchor = loc.clone();
            p.target = loc.clone();
            p.idleUntil = System.currentTimeMillis() + 4000L + rng.nextInt(11000);
            return p;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not create spawn stand-in: " + t.getMessage());
            return null;
        }
    }

    private void cleanupOld(World world, Location spawn, List<String> names) {
        Set<String> display = new HashSet<String>();
        for (String n : names) display.add(EraCore.colorText("&7" + n));

        for (Entity e : world.getNearbyEntities(spawn, 40, 20, 40)) {
            if (!(e instanceof ArmorStand)) continue;
            ArmorStand a = (ArmorStand)e;
            if (a.getCustomName() != null && display.contains(a.getCustomName())) a.remove();
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();

        for (Presence p : presences) {
            if (p.stand == null || p.stand.isDead()) continue;
            Location current = p.stand.getLocation();

            if (!p.walking) {
                if (now < p.idleUntil) {
                    if (rng.nextInt(5) == 0) {
                        Location turned = current.clone();
                        turned.setYaw(current.getYaw() + (float)((rng.nextDouble() - 0.5) * 55.0));
                        p.stand.teleport(turned);
                    }
                    continue;
                }

                if (rng.nextInt(100) < 55) {
                    p.target = findNearbyFloor(p.anchor, 4 + rng.nextInt(7));
                    p.walking = true;
                } else {
                    p.idleUntil = now + 3500L + rng.nextInt(12000);
                }
                continue;
            }

            double dx = p.target.getX() - current.getX();
            double dz = p.target.getZ() - current.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist < 0.45) {
                p.walking = false;
                p.idleUntil = now + 4000L + rng.nextInt(14000);
                continue;
            }

            double step = Math.min(0.45, dist);
            Location next = current.clone();
            next.setX(current.getX() + (dx / dist) * step);
            next.setZ(current.getZ() + (dz / dist) * step);
            next.setY(p.target.getY());
            next.setYaw((float)Math.toDegrees(Math.atan2(-dx, dz)));
            p.stand.teleport(next);
        }
    }

    private Location findNearbyFloor(Location center, int radius) {
        World w = center.getWorld();
        int baseY = center.getBlockY();

        for (int tries = 0; tries < 28; tries++) {
            int x = center.getBlockX() + rng.nextInt(radius * 2 + 1) - radius;
            int z = center.getBlockZ() + rng.nextInt(radius * 2 + 1) - radius;

            for (int dy = -2; dy <= 2; dy++) {
                int y = baseY + dy;
                Material below = w.getBlockAt(x, y - 1, z).getType();
                Material feet = w.getBlockAt(x, y, z).getType();
                Material head = w.getBlockAt(x, y + 1, z).getType();
                if (below.isSolid() && feet == Material.AIR && head == Material.AIR) {
                    return new Location(w, x + 0.5, y, z + 0.5, rng.nextInt(360), 0f);
                }
            }
        }

        return center.clone();
    }
}
