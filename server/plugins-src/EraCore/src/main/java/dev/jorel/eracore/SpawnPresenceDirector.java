package dev.jorel.eracore;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.PacketPlayOutAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityHeadRotation;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityTeleport;
import net.minecraft.server.v1_8_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R3.PlayerInteractManager;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Extremely cheap spawn ambience.
 *
 * These are packet-only fake players. They have no network session, AI,
 * physics, pathfinder, inventory, chunk subscriptions or world simulation.
 * They only exist visually for real viewers around spawn.
 */
final class SpawnPresenceDirector {
    private static final class Presence {
        EntityPlayer entity;
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
        if (!plugin.getConfig().getBoolean("spawn-presence.enabled", true)) return;
        if (!presences.isEmpty()) return;

        Location spawn = warps.getSpawn();
        World world = spawn.getWorld();
        if (world == null) return;

        int count = Math.max(1, Math.min(8, plugin.getConfig().getInt("spawn-presence.count", 4)));
        List<String> names = new ArrayList<String>(plugin.getConfig().getStringList("spawn-presence.names"));
        if (names.isEmpty()) {
            names.addAll(Arrays.asList("xRico","PurpleDino","BreezyMC","MasonHD","NightPvP","Vexing","CaneKing","MinerMatt"));
        }

        for (int i = 0; i < count; i++) {
            String name = names.get(i % names.size());
            Location loc = findNearbyFloor(spawn, 3 + i * 2);
            Presence p = create(name, loc);
            if (p != null) presences.add(p);
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!plugin.isBotIdentity(viewer.getName())) showTo(viewer);
        }

        long period = Math.max(10L, plugin.getConfig().getLong("spawn-presence.update-ticks", 20L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { tick(); }
        }, period, period);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Player viewer : Bukkit.getOnlinePlayers()) hideFrom(viewer);
        presences.clear();
    }

    void showTo(Player viewer) {
        if (plugin.isBotIdentity(viewer.getName())) return;
        for (Presence p : presences) spawnPacket(viewer, p.entity);
    }

    void hideFrom(Player viewer) {
        if (!(viewer instanceof CraftPlayer)) return;
        for (Presence p : presences) {
            ((CraftPlayer)viewer).getHandle().playerConnection.sendPacket(new PacketPlayOutEntityDestroy(p.entity.getId()));
        }
    }

    private Presence create(String name, Location loc) {
        try {
            MinecraftServer server = ((CraftServer)Bukkit.getServer()).getServer();
            WorldServer world = ((CraftWorld)loc.getWorld()).getHandle();
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(uuid, trimName(name));

            EntityPlayer entity = new EntityPlayer(server, world, profile, new PlayerInteractManager(world));
            entity.setLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
            entity.getDataWatcher().watch(10, (byte)127);

            Presence p = new Presence();
            p.entity = entity;
            p.anchor = loc.clone();
            p.target = loc.clone();
            p.idleUntil = System.currentTimeMillis() + 4000L + rng.nextInt(11000);
            return p;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not create spawn presence NPC: " + t.getMessage());
            return null;
        }
    }

    private String trimName(String name) {
        if (name.length() <= 16) return name;
        return name.substring(0, 16);
    }

    private void spawnPacket(final Player viewer, final EntityPlayer npc) {
        if (!(viewer instanceof CraftPlayer)) return;
        CraftPlayer cp = (CraftPlayer)viewer;
        cp.getHandle().playerConnection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, npc));
        cp.getHandle().playerConnection.sendPacket(new PacketPlayOutNamedEntitySpawn(npc));
        cp.getHandle().playerConnection.sendPacket(new PacketPlayOutEntityHeadRotation(npc, (byte)((npc.yaw * 256.0F) / 360.0F)));

        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (!viewer.isOnline()) return;
                ((CraftPlayer)viewer).getHandle().playerConnection.sendPacket(
                    new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, npc)
                );
            }
        }, 40L);
    }

    private void tick() {
        if (presences.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (Presence p : presences) {
            Location current = new Location(
                p.anchor.getWorld(),
                p.entity.locX,
                p.entity.locY,
                p.entity.locZ,
                p.entity.yaw,
                p.entity.pitch
            );

            if (!p.walking) {
                if (now < p.idleUntil) {
                    if (rng.nextInt(5) == 0) {
                        p.entity.yaw += (float)((rng.nextDouble() - 0.5) * 50.0);
                        broadcast(new PacketPlayOutEntityHeadRotation(p.entity, (byte)((p.entity.yaw * 256.0F) / 360.0F)));
                        if (rng.nextInt(4) == 0) broadcast(new PacketPlayOutAnimation(p.entity, 0));
                    }
                    continue;
                }

                if (rng.nextInt(100) < 58) {
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

            double step = Math.min(0.55, dist);
            double nx = current.getX() + (dx / dist) * step;
            double nz = current.getZ() + (dz / dist) * step;
            double ny = p.target.getY();
            float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));

            p.entity.setLocation(nx, ny, nz, yaw, 0f);
            broadcast(new PacketPlayOutEntityTeleport(p.entity));
            broadcast(new PacketPlayOutEntityHeadRotation(p.entity, (byte)((yaw * 256.0F) / 360.0F)));
        }
    }

    private void broadcast(Object packet) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (plugin.isBotIdentity(viewer.getName())) continue;
            try {
                CraftPlayer cp = (CraftPlayer)viewer;
                if (packet instanceof PacketPlayOutEntityTeleport) cp.getHandle().playerConnection.sendPacket((PacketPlayOutEntityTeleport)packet);
                else if (packet instanceof PacketPlayOutEntityHeadRotation) cp.getHandle().playerConnection.sendPacket((PacketPlayOutEntityHeadRotation)packet);
                else if (packet instanceof PacketPlayOutAnimation) cp.getHandle().playerConnection.sendPacket((PacketPlayOutAnimation)packet);
            } catch (Throwable ignored) {}
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

        return center.clone().add((rng.nextDouble() - 0.5) * 5.0, 0, (rng.nextDouble() - 0.5) * 5.0);
    }
}
