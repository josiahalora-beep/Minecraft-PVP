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
            new BukkitRunnable(){public void run(){bootstrapPublicSites();}}.runTaskLater(plugin,160L);
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

    private void bootstrapPublicSites() {
        buildOreMountain();
        buildNetherResourceSites();
        buildEndResourceSites();
        bootstrapSpawners();
    }

    private void buildOreMountain() {
        World w=Bukkit.getWorld(plugin.getConfig().getString("map-layout.ore-world","ore_mountain"));
        if(w==null) return;
        Location spawn=w.getSpawnLocation();
        int cx=spawn.getBlockX(),cz=spawn.getBlockZ();
        int base=Math.max(58,w.getHighestBlockYAt(cx,cz));
        Block sentinel=w.getBlockAt(cx,base+18,cz);
        if(sentinel.getType()==Material.EMERALD_BLOCK) return;

        Random local=new Random(20150418L);
        int radius=32;
        for(int dx=-radius;dx<=radius;dx++) {
            for(int dz=-radius;dz<=radius;dz++) {
                double d=Math.sqrt(dx*dx+dz*dz);
                if(d>radius) continue;
                int h=Math.max(1,(int)Math.round(18.0*(1.0-d/radius)));
                for(int dy=0;dy<=h;dy++) {
                    Material m=Material.STONE;
                    int roll=local.nextInt(1000);
                    if(roll<10) m=Material.DIAMOND_ORE;
                    else if(roll<18) m=Material.EMERALD_ORE;
                    else if(roll<48) m=Material.GOLD_ORE;
                    else if(roll<128) m=Material.IRON_ORE;
                    else if(roll<190) m=Material.REDSTONE_ORE;
                    else if(roll<300) m=Material.COAL_ORE;
                    w.getBlockAt(cx+dx,base+dy,cz+dz).setType(m);
                }
            }
        }
        sentinel.setType(Material.EMERALD_BLOCK);
        w.setSpawnLocation(cx,base+2,cz);
        if(map!=null) map.bootstrapWarps();
        plugin.getLogger().info("Bootstrapped protected Ore Mountain at "+cx+","+cz+".");
    }

    private void buildNetherResourceSites() {
        World w=firstWorld(World.Environment.NETHER);
        if(w==null) return;

        HcfMapDirector.Region glow=map.region("glowstone");
        if(glow!=null) {
            int y=plugin.getConfig().getInt("resources.blaze.y",70)-1;
            int cx=(int)glow.x,cz=(int)glow.z;
            if(w.getBlockAt(cx,y+15,cz).getType()!=Material.GLOWSTONE) {
                Random local=new Random(77113L);
                for(int dx=-28;dx<=28;dx++) for(int dz=-28;dz<=28;dz++) {
                    double d=Math.sqrt(dx*dx+dz*dz);
                    if(d>28) continue;
                    int h=Math.max(1,(int)Math.round(14.0*(1.0-d/28.0)));
                    for(int dy=0;dy<=h;dy++) {
                        Material m=(dy==h && local.nextInt(100)<42)?Material.GLOWSTONE:Material.NETHERRACK;
                        w.getBlockAt(cx+dx,y+dy,cz+dz).setType(m);
                    }
                }
                w.getBlockAt(cx,y+15,cz).setType(Material.GLOWSTONE);
            }
        }

        HcfMapDirector.Region wart=map.region("wart");
        if(wart!=null) {
            int y=plugin.getConfig().getInt("resources.blaze.y",70);
            int cx=(int)wart.x,cz=(int)wart.z;
            for(int dx=-22;dx<=22;dx++) for(int dz=-16;dz<=16;dz++)
                w.getBlockAt(cx+dx,y-1,cz+dz).setType(Material.NETHER_BRICK);
            for(int row=-12;row<=12;row+=4) for(int dx=-18;dx<=18;dx++) {
                Block soil=w.getBlockAt(cx+dx,y,cz+row);
                soil.setType(Material.SOUL_SAND);
                Block crop=w.getBlockAt(cx+dx,y+1,cz+row);
                crop.setType(Material.NETHER_WARTS);
                crop.setData((byte)3);
            }
        }

        HcfMapDirector.Region blaze=map.region("blaze");
        if(blaze!=null) {
            int y=plugin.getConfig().getInt("resources.blaze.y",70);
            int cx=(int)blaze.x,cz=(int)blaze.z;
            for(int dx=-22;dx<=22;dx++) for(int dz=-22;dz<=22;dz++) {
                w.getBlockAt(cx+dx,y-1,cz+dz).setType(Material.NETHER_BRICK);
                if(Math.abs(dx)==22 || Math.abs(dz)==22)
                    w.getBlockAt(cx+dx,y,cz+dz).setType(Material.NETHER_FENCE);
            }
        }

        HcfMapDirector.Region nk=map.region("nether-koth");
        if(nk!=null) buildCapturePad(w,(int)nk.x,
            plugin.getConfig().getInt("resources.blaze.y",70),(int)nk.z,Material.NETHER_BRICK,Material.GOLD_BLOCK);
    }

    private void buildEndResourceSites() {
        World w=firstWorld(World.Environment.THE_END);
        if(w==null) return;

        HcfMapDirector.Region creeper=map.region("creeper");
        if(creeper!=null) {
            int y=plugin.getConfig().getInt("resources.creeper.y",69);
            int cx=(int)creeper.x,cz=(int)creeper.z;
            for(int dx=-20;dx<=20;dx++) for(int dz=-20;dz<=20;dz++) {
                w.getBlockAt(cx+dx,y-1,cz+dz).setType(Material.ENDER_STONE);
                if(Math.abs(dx)==20 || Math.abs(dz)==20)
                    w.getBlockAt(cx+dx,y,cz+dz).setType(Material.OBSIDIAN);
            }
        }

        HcfMapDirector.Region ek=map.region("end-koth");
        if(ek!=null) buildCapturePad(w,(int)ek.x,
            plugin.getConfig().getInt("resources.creeper.y",69),(int)ek.z,Material.ENDER_STONE,Material.DIAMOND_BLOCK);

        HcfMapDirector.Region exit=map.region("end-exit");
        if(exit!=null) {
            int y=plugin.getConfig().getInt("resources.creeper.y",69);
            int cx=(int)exit.x,cz=(int)exit.z;
            for(int dx=-8;dx<=8;dx++) for(int dz=-8;dz<=8;dz++)
                w.getBlockAt(cx+dx,y-1,cz+dz).setType(Material.OBSIDIAN);
            // A small physical End portal gives the safe kite destination a real
            // exit. HcfPortalDirector routes it back to the configured Spawn.
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                w.getBlockAt(cx+dx,y,cz+dz).setType(Material.ENDER_PORTAL);
        }
    }

    private void buildCapturePad(World w,int cx,int y,int cz,Material floor,Material center) {
        for(int dx=-18;dx<=18;dx++) for(int dz=-18;dz<=18;dz++)
            w.getBlockAt(cx+dx,y-1,cz+dz).setType(floor);
        for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++)
            w.getBlockAt(cx+dx,y-1,cz+dz).setType(center);
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
