package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps the HCF presentation bright and consistent.
 *
 * This is a rule, not a one-time /time command: weather events are rejected and
 * a lightweight watchdog periodically restores daytime/clear weather after any
 * external plugin or command changes it.
 */
final class HcfAtmosphereDirector implements Listener {
    private final EraCore plugin;
    private BukkitTask task;

    HcfAtmosphereDirector(EraCore plugin) {
        this.plugin=plugin;
    }

    void start() {
        applyAll();
        if(task!=null) return;
        long every=Math.max(40L,
            plugin.getConfig().getLong("atmosphere.enforce-every-ticks",100L));
        task=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){ applyAll(); }
        },every,every);
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
    }

    private boolean isManaged(World world) {
        return world!=null && world.getEnvironment()==World.Environment.NORMAL;
    }

    private void applyAll() {
        for(World world:Bukkit.getWorlds()) apply(world);
    }

    private void apply(World world) {
        if(!isManaged(world)) return;

        if(plugin.getConfig().getBoolean("atmosphere.lock-day",true)) {
            long time=plugin.getConfig().getLong("atmosphere.day-time",6000L);
            time=((time%24000L)+24000L)%24000L;
            if(Math.abs(world.getTime()-time)>20L) world.setTime(time);
        }

        if(plugin.getConfig().getBoolean("atmosphere.clear-weather",true)) {
            if(world.hasStorm()) world.setStorm(false);
            if(world.isThundering()) world.setThundering(false);
            world.setWeatherDuration(Integer.MAX_VALUE);
            world.setThunderDuration(Integer.MAX_VALUE);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if(!isManaged(event.getWorld())) return;
        if(!plugin.getConfig().getBoolean("atmosphere.clear-weather",true)) return;
        if(event.toWeatherState()) event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onThunderChange(ThunderChangeEvent event) {
        if(!isManaged(event.getWorld())) return;
        if(!plugin.getConfig().getBoolean("atmosphere.clear-weather",true)) return;
        if(event.toThunderState()) event.setCancelled(true);
    }
}
