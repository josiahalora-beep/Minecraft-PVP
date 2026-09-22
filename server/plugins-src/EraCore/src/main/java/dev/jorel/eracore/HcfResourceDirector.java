package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Contested HCF resource mechanics:
 * - Glowstone Mountain blocks regrow after mining.
 * - Ore Mountain is a protected mining warp where only ores can be broken and
 *   each ore regenerates on its own tiered timer.
 * - Blaze/Creeper entities use visible stacking to avoid mob floods.
 * - Natural Endermen are untouched; there is intentionally no Enderman farm.
 */
@SuppressWarnings("deprecation")
final class HcfResourceDirector implements Listener {
    private final EraCore plugin;
    private final HcfMapDirector map;
    private final Random rng=new Random(20150517L);
    private final Set<String> pendingRegen=new HashSet<String>();
    private final Map<UUID,Integer> stacks=new HashMap<UUID,Integer>();

    HcfResourceDirector(EraCore plugin,HcfMapDirector map) {
        this.plugin=plugin; this.map=map;
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(World w:Bukkit.getWorlds()) for(LivingEntity e:w.getLivingEntities()) {
            int n=parseStack(e);
            if(n>1) stacks.put(e.getUniqueId(),n);
        }
        if(plugin.getConfig().getBoolean("resources.bootstrap-spawners",false)) {
            new BukkitRunnable(){public void run(){bootstrapSpawners();}}.runTaskLater(plugin,160L);
        }
    }

    void stop() {
        pendingRegen.clear();
        stacks.clear();
    }

    boolean handleProtectedBreak(final BlockBreakEvent e) {
        if(e==null || e.getBlock()==null) return false;
        final Block b=e.getBlock();
        final Location l=b.getLocation();

        if(map.isOreWorld(l)) {
            if(!isOre(b.getType())) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(EraCore.colorText("&cOre Mountain is protected. &7Only ore blocks may be mined."));
                return true;
            }
            final Material material=b.getType();
            final byte data=b.getData();
            scheduleRestore(b,material,data,oreDelaySeconds(material));
            return true;
        }

        HcfMapDirector.Region r=map.protectedRegion(l);
        if(r==null) return false;

        if("glowstone".equals(r.id) && b.getType()==Material.GLOWSTONE) {
            scheduleRestore(b,Material.GLOWSTONE,(byte)0,
                randomBetween(plugin.getConfig().getInt("resources.glowstone.regen-min-seconds",45),
                              plugin.getConfig().getInt("resources.glowstone.regen-max-seconds",90)));
            return true;
        }

        if("wart".equals(r.id) && b.getType()==Material.NETHER_WARTS) {
            scheduleRestore(b,Material.NETHER_WARTS,b.getData(),
                randomBetween(plugin.getConfig().getInt("resources.wart.regen-min-seconds",45),
                              plugin.getConfig().getInt("resources.wart.regen-max-seconds",90)));
            return true;
        }
        return false;
    }

    private void scheduleRestore(final Block original,final Material material,final byte data,int seconds) {
        if(original==null || original.getWorld()==null) return;
        final String key=original.getWorld().getName()+":"+original.getX()+":"+original.getY()+":"+original.getZ();
        if(!pendingRegen.add(key)) return;
        final String worldName=original.getWorld().getName();
        final int x=original.getX(),y=original.getY(),z=original.getZ();
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run() {
                pendingRegen.remove(key);
                World w=Bukkit.getWorld(worldName);
                if(w==null) return;
                Block b=w.getBlockAt(x,y,z);
                if(b.getType()!=Material.AIR) return;
                b.setType(material);
                b.setData(data);
            }
        },Math.max(1,seconds)*20L);
    }

    private boolean isOre(Material m) {
        return m==Material.COAL_ORE || m==Material.IRON_ORE || m==Material.GOLD_ORE ||
            m==Material.REDSTONE_ORE || m==Material.GLOWING_REDSTONE_ORE ||
            m==Material.DIAMOND_ORE || m==Material.EMERALD_ORE;
    }

    private int oreDelaySeconds(Material m) {
        String key=m==Material.COAL_ORE?"coal":
            (m==Material.IRON_ORE?"iron":
            ((m==Material.REDSTONE_ORE||m==Material.GLOWING_REDSTONE_ORE)?"redstone":
            (m==Material.GOLD_ORE?"gold":
            (m==Material.DIAMOND_ORE?"diamond":"emerald"))));
        int min=plugin.getConfig().getInt("resources.ore-mountain."+key+"-regen-min-seconds",30);
        int max=plugin.getConfig().getInt("resources.ore-mountain."+key+"-regen-max-seconds",Math.max(min,60));
        return randomBetween(min,max);
    }

    private int randomBetween(int min,int max) {
        min=Math.max(1,min);max=Math.max(min,max);
        return min+(max==min?0:rng.nextInt(max-min+1));
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        LivingEntity born=e.getEntity();
        String site=stackSite(born);
        if(site.isEmpty()) return;

        int cap="blaze".equals(site)?
            plugin.getConfig().getInt("resources.blaze.stack-cap",20):
            plugin.getConfig().getInt("resources.creeper.stack-cap",25);
        double merge=plugin.getConfig().getDouble("resources.mob-stack-merge-radius",10.0);

        for(Entity near:born.getNearbyEntities(merge,merge,merge)) {
            if(!(near instanceof LivingEntity) || near.getType()!=born.getType()) continue;
            LivingEntity target=(LivingEntity)near;
            int n=stackCount(target);
            if(n>=cap) continue;
            setStack(target,Math.min(cap,n+1));
            e.setCancelled(true);
            return;
        }
        setStack(born,1);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onStackDeath(final EntityDeathEvent e) {
        final LivingEntity dead=e.getEntity();
        int n=stackCount(dead);
        stacks.remove(dead.getUniqueId());
        if(n<=1) return;
        final EntityType type=dead.getType();
        final Location loc=dead.getLocation().clone();
        final int remaining=n-1;
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run() {
                if(loc.getWorld()==null) return;
                Entity entity=loc.getWorld().spawnEntity(loc,type);
                if(entity instanceof LivingEntity) setStack((LivingEntity)entity,remaining);
            }
        },2L);
    }

    private String stackSite(LivingEntity e) {
        if(e==null || e.getWorld()==null) return "";
        Location l=e.getLocation();
        HcfMapDirector.Region r=map.protectedRegion(l);
        if(e.getType()==EntityType.BLAZE && r!=null && "blaze".equals(r.id)) return "blaze";
        if(e.getType()==EntityType.CREEPER && r!=null && "creeper".equals(r.id)) return "creeper";
        return "";
    }

    private int stackCount(LivingEntity e) {
        Integer n=stacks.get(e.getUniqueId());
        if(n!=null) return Math.max(1,n);
        n=parseStack(e);
        if(n>1) stacks.put(e.getUniqueId(),n);
        return Math.max(1,n);
    }

    private int parseStack(LivingEntity e) {
        if(e==null || e.getCustomName()==null) return 1;
        String s=ChatColor.stripColor(e.getCustomName());
        int i=s==null?-1:s.lastIndexOf(" x");
        if(i<0) return 1;
        try{return Math.max(1,Integer.parseInt(s.substring(i+2).trim()));}
        catch(Exception ignored){return 1;}
    }

    private void setStack(LivingEntity e,int count) {
        if(e==null) return;
        count=Math.max(1,count);
        stacks.put(e.getUniqueId(),count);
        String base=e.getType()==EntityType.BLAZE?"Blaze":(e.getType()==EntityType.CREEPER?"Creeper":e.getType().name());
        e.setCustomName(EraCore.colorText("&e"+base+" &7x&f"+count));
        e.setCustomNameVisible(count>1);
    }

    private World firstWorld(World.Environment env) {
        for(World w:Bukkit.getWorlds()) if(w.getEnvironment()==env) return w;
        return null;
    }

    private void bootstrapSpawners() {
        World nether=firstWorld(World.Environment.NETHER);
        World end=firstWorld(World.Environment.THE_END);
        if(nether!=null) {
            HcfMapDirector.Region r=map.region("blaze");
            if(r!=null) {
                int y=plugin.getConfig().getInt("resources.blaze.y",70);
                placeSpawner(nether,(int)r.x-14,y,(int)r.z-14,EntityType.BLAZE);
                placeSpawner(nether,(int)r.x+14,y,(int)r.z-14,EntityType.BLAZE);
                placeSpawner(nether,(int)r.x-14,y,(int)r.z+14,EntityType.BLAZE);
                placeSpawner(nether,(int)r.x+14,y,(int)r.z+14,EntityType.BLAZE);
            }
        }
        if(end!=null) {
            HcfMapDirector.Region r=map.region("creeper");
            if(r!=null) {
                int y=plugin.getConfig().getInt("resources.creeper.y",69);
                placeSpawner(end,(int)r.x-14,y,(int)r.z-14,EntityType.CREEPER);
                placeSpawner(end,(int)r.x+14,y,(int)r.z-14,EntityType.CREEPER);
                placeSpawner(end,(int)r.x-14,y,(int)r.z+14,EntityType.CREEPER);
                placeSpawner(end,(int)r.x+14,y,(int)r.z+14,EntityType.CREEPER);
            }
        }
    }

    private void placeSpawner(World w,int x,int y,int z,EntityType type) {
        Block b=w.getBlockAt(x,y,z);
        if(b.getType()!=Material.AIR && b.getType()!=Material.MOB_SPAWNER) return;
        b.setType(Material.MOB_SPAWNER);
        if(b.getState() instanceof CreatureSpawner) {
            CreatureSpawner sp=(CreatureSpawner)b.getState();
            sp.setSpawnedType(type);
            sp.setDelay(100);
            sp.update(true);
        }
    }
}
