package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class WarpManager {
    private final EraCore plugin;
    private final File file;
    private final YamlConfiguration data;

    WarpManager(EraCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "warps.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    void bootstrapDefaults() {
        World world = Bukkit.getWorlds().get(0);
        if (!data.contains("spawn.world")) setSpawn(world.getSpawnLocation(), false);

        if (!data.contains("warps.pvp.world")) {
            int y = plugin.getConfig().getInt("map.surface-y", 63) + 1;
            setWarp("pvp", new Location(world, 200.5, y, 0.5, 90f, 0f), false);
        }
        if (!data.contains("warps.koth.world")) {
            int y = plugin.getConfig().getInt("map.surface-y", 63) + 1;
            setWarp("koth", new Location(world, 500.5, y, 500.5, 0f, 0f), false);
        }
        save();
    }

    void applyPlayman2013Preset(World world) {
        Location center = new Location(world, 260.5, 70.0, 180.5, 0f, 0f);
        setSpawn(center, false);
        setWarp("spawn", center, false);

        // The downloaded build contains its own shop and enchanting rooms.
        // Their exact interior spots can be finalized with /setwarp after the paste.
        setWarp("shop", center, false);
        setWarp("enchant", center, false);

        save();
    }

    Location getSpawn() {
        Location l = read("spawn");
        if (l != null) return l;
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    void setSpawn(Location location) {
        setSpawn(location, true);
    }

    private void setSpawn(Location location, boolean save) {
        write("spawn", location);
        location.getWorld().setSpawnLocation(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (save) save();
    }

    Location getWarp(String name) {
        if ("spawn".equalsIgnoreCase(name)) return getSpawn();
        return read("warps." + normalize(name));
    }

    void setWarp(String name, Location location) {
        setWarp(name, location, true);
    }

    private void setWarp(String name, Location location, boolean save) {
        write("warps." + normalize(name), location);
        if (save) save();
    }

    boolean deleteWarp(String name) {
        String key = normalize(name);
        if (!data.contains("warps." + key)) return false;
        data.set("warps." + key, null);
        save();
        return true;
    }

    List<String> names() {
        ConfigurationSection s = data.getConfigurationSection("warps");
        if (s == null) return Collections.emptyList();
        List<String> out = new ArrayList<String>(s.getKeys(false));
        Collections.sort(out);
        return out;
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9_-]", "");
    }

    private void write(String path, Location l) {
        data.set(path + ".world", l.getWorld().getName());
        data.set(path + ".x", l.getX());
        data.set(path + ".y", l.getY());
        data.set(path + ".z", l.getZ());
        data.set(path + ".yaw", (double)l.getYaw());
        data.set(path + ".pitch", (double)l.getPitch());
    }

    private Location read(String path) {
        if (!data.contains(path + ".world")) return null;
        World w = Bukkit.getWorld(data.getString(path + ".world", "world"));
        if (w == null) return null;
        return new Location(
            w,
            data.getDouble(path + ".x"),
            data.getDouble(path + ".y"),
            data.getDouble(path + ".z"),
            (float)data.getDouble(path + ".yaw"),
            (float)data.getDouble(path + ".pitch")
        );
    }

    void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save warps.yml: " + e.getMessage());
        }
    }
}
