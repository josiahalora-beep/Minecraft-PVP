package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Canonical HCF map registry.  This class deliberately distinguishes:
 *  - true PvP Safezones (spawn hubs, End kite exit, Ore Mountain)
 *  - PvP-active build-protected regions (KOTH/Conquest/portal/resource sites)
 *  - the much larger Spawn claim exclusion.
 *
 * Mineflayer uses the matching bots/src/hcf-map-intelligence.js constants.
 */
@SuppressWarnings("deprecation")
final class HcfMapDirector implements Listener {
    static final class Region {
        final String id,name,worldKey,type;
        final double x,z;
        final int radius;
        final boolean safe,buildProtected,claimProtected;
        Region(String id,String name,String worldKey,String type,double x,double z,int radius,
               boolean safe,boolean buildProtected,boolean claimProtected) {
            this.id=id; this.name=name; this.worldKey=worldKey; this.type=type;
            this.x=x; this.z=z; this.radius=radius;
            this.safe=safe; this.buildProtected=buildProtected; this.claimProtected=claimProtected;
        }
        boolean contains(Location l) {
            if(l==null || l.getWorld()==null) return false;
            double dx=l.getX()-x,dz=l.getZ()-z;
            return dx*dx+dz*dz <= (double)radius*(double)radius;
        }
        int distance(Location l) {
            if(l==null) return Integer.MAX_VALUE;
            double dx=l.getX()-x,dz=l.getZ()-z;
            return (int)Math.round(Math.sqrt(dx*dx+dz*dz));
        }
    }

    private final EraCore plugin;
    private final WarpManager warps;
    private final HcfTravelDirector travel;
    private final HcfZoneDisplayDirector zones;
    private final List<Region> regions=new ArrayList<Region>();
    private final Map<String,Boolean> lastAdditionalSafe=new HashMap<String,Boolean>();

    HcfMapDirector(EraCore plugin,WarpManager warps,HcfTravelDirector travel,HcfZoneDisplayDirector zones) {
        this.plugin=plugin; this.warps=warps; this.travel=travel; this.zones=zones;
        rebuildRegistry();
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        ensureOreMountainWorld();
        rebuildRegistry();
        bootstrapWarps();
        if(plugin.getConfig().getBoolean("map-layout.build-visible-borders",true)) {
            new BukkitRunnable() {
                public void run(){ buildVisibleBorders(); }
            }.runTaskLater(plugin,120L);
        }
    }

    void stop() {
        lastAdditionalSafe.clear();
    }

    void rebuildRegistry() {
        regions.clear();
        int kothOffset=plugin.getConfig().getInt("map-layout.koth-offset",650);
        int kothRadius=plugin.getConfig().getInt("map-layout.koth-radius",165);
        int portalOffset=plugin.getConfig().getInt("map-layout.portal-offset",1000);
        int portalRadius=plugin.getConfig().getInt("map-layout.portal-radius",125);

        regions.add(new Region("spawn-build","Spawn protection","overworld","protected",0,0,
            plugin.getConfig().getInt("map-layout.spawn-build-radius",190),false,true,true));
        regions.add(new Region("spawn-claim","Spawn claim exclusion","overworld","claim",0,0,
            plugin.getConfig().getInt("map-layout.spawn-claim-radius",500),false,false,true));

        regions.add(new Region("classic","Classic KOTH","overworld","koth", kothOffset,-kothOffset,kothRadius,false,true,true));
        regions.add(new Region("endstyle","EndStyle KOTH","overworld","koth",-kothOffset,-kothOffset,kothRadius,false,true,true));
        regions.add(new Region("egypt","Egypt KOTH","overworld","koth", kothOffset, kothOffset,kothRadius,false,true,true));
        regions.add(new Region("frost","Frost KOTH","overworld","koth",-kothOffset, kothOffset,kothRadius,false,true,true));

        regions.add(new Region("ne-end","NE End Portal","overworld","portal", portalOffset,-portalOffset,portalRadius,false,true,true));
        regions.add(new Region("nw-end","NW End Portal","overworld","portal",-portalOffset,-portalOffset,portalRadius,false,true,true));
        regions.add(new Region("se-end","SE End Portal","overworld","portal", portalOffset, portalOffset,portalRadius,false,true,true));
        regions.add(new Region("sw-end","SW End Portal","overworld","portal",-portalOffset, portalOffset,portalRadius,false,true,true));

        regions.add(new Region("conquest","Conquest","overworld","conquest",
            plugin.getConfig().getInt("map-layout.conquest-x",0),
            plugin.getConfig().getInt("map-layout.conquest-z",1125),
            plugin.getConfig().getInt("map-layout.conquest-radius",175),false,true,true));

        regions.add(new Region("nether-build","Nether Spawn protection","nether","protected",0,0,
            plugin.getConfig().getInt("map-layout.nether-build-radius",120),false,true,true));
        regions.add(new Region("glowstone","Glowstone Mountain","nether","resource",
            plugin.getConfig().getInt("resources.glowstone.x",0),
            plugin.getConfig().getInt("resources.glowstone.z",-320),
            plugin.getConfig().getInt("resources.glowstone.radius",105),false,true,true));
        regions.add(new Region("blaze","Blaze Yard","nether","resource",
            plugin.getConfig().getInt("resources.blaze.x",300),
            plugin.getConfig().getInt("resources.blaze.z",190),
            plugin.getConfig().getInt("resources.blaze.radius",90),false,true,true));
        regions.add(new Region("wart","Wart Valley","nether","resource",
            plugin.getConfig().getInt("resources.wart.x",-285),
            plugin.getConfig().getInt("resources.wart.z",205),
            plugin.getConfig().getInt("resources.wart.radius",90),false,true,true));
        regions.add(new Region("nether-koth","Nether KOTH","nether","koth",
            plugin.getConfig().getInt("resources.nether-koth.x",-300),
            plugin.getConfig().getInt("resources.nether-koth.z",-220),
            plugin.getConfig().getInt("resources.nether-koth.radius",125),false,true,true));

        regions.add(new Region("end-build","End Hub protection","end","protected",0,0,
            plugin.getConfig().getInt("map-layout.end-build-radius",145),false,true,true));
        regions.add(new Region("end-koth","End KOTH","end","koth",
            plugin.getConfig().getInt("resources.end-koth.x",-300),
            plugin.getConfig().getInt("resources.end-koth.z",-120),
            plugin.getConfig().getInt("resources.end-koth.radius",125),false,true,true));
        regions.add(new Region("creeper","Creeper Stack","end","resource",
            plugin.getConfig().getInt("resources.creeper.x",160),
            plugin.getConfig().getInt("resources.creeper.z",235),
            plugin.getConfig().getInt("resources.creeper.radius",60),false,true,true));
        regions.add(new Region("end-exit","End Kite Exit","end","safezone",
            plugin.getConfig().getInt("resources.end-exit.x",330),
            plugin.getConfig().getInt("resources.end-exit.z",0),
            plugin.getConfig().getInt("resources.end-exit.safe-radius",34),true,true,true));
    }

    Region region(String id) {
        for(Region r:regions) if(r.id.equalsIgnoreCase(id)) return r;
        return null;
    }

    List<Region> regions() { return Collections.unmodifiableList(regions); }

    private String worldKey(World w) {
        if(w==null) return "";
        String ore=plugin.getConfig().getString("map-layout.ore-world","ore_mountain");
        if(w.getName().equalsIgnoreCase(ore)) return "ore";
        if(w.getEnvironment()==World.Environment.NETHER) return "nether";
        if(w.getEnvironment()==World.Environment.THE_END) return "end";
        return "overworld";
    }

    boolean isOreWorld(Location l) {
        return l!=null && l.getWorld()!=null && "ore".equals(worldKey(l.getWorld()));
    }

    Region protectedRegion(Location l) {
        if(l==null || l.getWorld()==null) return null;
        if(isOreWorld(l)) return new Region("ore-mountain","Ore Mountain","ore","resource-safe",0,0,Integer.MAX_VALUE,true,true,true);
        String wk=worldKey(l.getWorld());
        for(Region r:regions) if(r.worldKey.equals(wk) && r.buildProtected && r.contains(l)) return r;
        return null;
    }

    boolean protectsBuild(Location l) {
        return protectedRegion(l)!=null;
    }

    String buildReason(Location l) {
        Region r=protectedRegion(l);
        return r==null?"":r.name+" is protected.";
    }

    boolean canClaimRect(World world,int minX,int maxX,int minZ,int maxZ) {
        return claimReasonRect(world,minX,maxX,minZ,maxZ).isEmpty();
    }

    String claimReasonRect(World world,int minX,int maxX,int minZ,int maxZ) {
        if(world==null) return "Invalid claim world.";
        if(!"overworld".equals(worldKey(world))) return "Faction claims are only allowed in the Overworld.";

        int x1=Math.min(minX,maxX),x2=Math.max(minX,maxX);
        int z1=Math.min(minZ,maxZ),z2=Math.max(minZ,maxZ);
        for(Region r:regions) {
            if(!"overworld".equals(r.worldKey) || !r.claimProtected) continue;
            double cx=Math.max(x1,Math.min(r.x,x2));
            double cz=Math.max(z1,Math.min(r.z,z2));
            double dx=cx-r.x,dz=cz-r.z;
            if(dx*dx+dz*dz <= (double)r.radius*(double)r.radius)
                return r.name+" has a no-claim radius.";
        }
        return "";
    }

    boolean canClaim(Location l) {
        if(l==null || l.getWorld()==null) return false;
        return canClaimRect(l.getWorld(),l.getBlockX(),l.getBlockX(),l.getBlockZ(),l.getBlockZ());
    }

    String claimReason(Location l) {
        if(l==null || l.getWorld()==null) return "Invalid claim location.";
        if(!"overworld".equals(worldKey(l.getWorld()))) return "Faction claims are only allowed in the Overworld.";
        for(Region r:regions) if("overworld".equals(r.worldKey) && r.claimProtected && r.contains(l))
            return r.name+" has a no-claim radius.";
        return "";
    }

    boolean isAdditionalSafe(Location l) {
        if(l==null || l.getWorld()==null) return false;
        if(isOreWorld(l)) return true;
        String wk=worldKey(l.getWorld());
        for(Region r:regions) if(r.safe && r.worldKey.equals(wk) && r.contains(l)) return true;
        return false;
    }

    Region nearest(Location l,String type) {
        if(l==null || l.getWorld()==null) return null;
        String wk=worldKey(l.getWorld());
        Region best=null; int dist=Integer.MAX_VALUE;
        for(Region r:regions) {
            if(!r.worldKey.equals(wk)) continue;
            if(type!=null && !type.isEmpty() && !r.type.equalsIgnoreCase(type)) continue;
            int d=r.distance(l);
            if(d<dist) {dist=d;best=r;}
        }
        return best;
    }

    boolean commandMapInfo(Player p,String[] args) {
        String sub=args.length==0?"nearest":args[0].toLowerCase(Locale.ENGLISH);
        if("nearest".equals(sub)) {
            Region r=nearest(p.getLocation(),"");
            if(r==null) {
                p.sendMessage(EraCore.colorText("&cNo known map landmark in this dimension."));
                return true;
            }
            p.sendMessage(EraCore.colorText("&6Map &8» &fNearest: &e"+r.name+" &7"+r.distance(p.getLocation())+
                "m &8("+((int)r.x)+", "+((int)r.z)+") &7type="+r.type));
            return true;
        }
        if("routes".equals(sub)) {
            p.sendMessage(EraCore.colorText("&6Roads &8» &fKraken roads run N/S/E/W from 0,0 to the +/-1500 border."));
            p.sendMessage(EraCore.colorText("&7KOTH quadrants are centered at &f+/-"+
                plugin.getConfig().getInt("map-layout.koth-offset",650)+"&7. End portal plazas are at &f+/-"+
                plugin.getConfig().getInt("map-layout.portal-offset",1000)+"&7."));
            return true;
        }
        if("resources".equals(sub)) {
            p.sendMessage(EraCore.colorText("&6Resources &8» &f/oremountain &7(safe mining), &fGlowstone Mountain, Blaze Yard, Wart Valley &7(Nether), &fCreeper Stack &7(End)."));
            return true;
        }
        if("zones".equals(sub)) {
            p.sendMessage(EraCore.colorText("&bCyan &7= Safezone, &cRed &7= KOTH/event protection, &5Purple &7= portal protection, &eGold &7= claim exclusion."));
            p.sendMessage(EraCore.colorText("&7KOTH/resource/portal protection blocks building but &cdoes not disable PvP&7."));
            return true;
        }
        p.sendMessage("/mapinfo <nearest|routes|resources|zones>");
        return true;
    }

    boolean commandOreMountain(Player p) {
        if(zones!=null && zones.isTagged(p) && !plugin.isOwnerPlayer(p)) {
            p.sendMessage(EraCore.colorText("&cYou cannot warp while combat tagged. &7"+zones.tagSeconds(p)+"s remaining."));
            return true;
        }
        Location target=warps.getWarp("oremountain");
        if(target==null) {
            World w=Bukkit.getWorld(plugin.getConfig().getString("map-layout.ore-world","ore_mountain"));
            if(w!=null) {
                Location s=w.getSpawnLocation().clone().add(0.5,1.0,0.5);
                warps.setWarp("oremountain",s);
                target=s;
            }
        }
        if(target==null) {
            p.sendMessage(EraCore.colorText("&cOre Mountain is not materialized yet. &7Install the Ore Mountain world asset before the next SOTW reset."));
            return true;
        }
        travel.request(p,target,"Ore Mountain");
        return true;
    }

    private void ensureOreMountainWorld() {
        if(!plugin.getConfig().getBoolean("resources.ore-mountain.enabled",true)) return;
        String name=plugin.getConfig().getString("map-layout.ore-world","ore_mountain");
        World w=Bukkit.getWorld(name);
        if(w==null) {
            try {
                WorldCreator creator=new WorldCreator(name);
                creator.environment(World.Environment.NORMAL);
                creator.generateStructures(false);
                w=creator.createWorld();
            } catch(Throwable t) {
                plugin.getLogger().warning("Could not create Ore Mountain world: "+t.getMessage());
            }
        }
        if(w!=null) {
            try {
                double size=Math.max(128,plugin.getConfig().getDouble("resources.ore-mountain.world-border",320));
                w.getWorldBorder().setCenter(w.getSpawnLocation());
                w.getWorldBorder().setSize(size);
            } catch(Throwable ignored){}
        }
    }

    void bootstrapWarps() {
        World ore=Bukkit.getWorld(plugin.getConfig().getString("map-layout.ore-world","ore_mountain"));
        if(ore!=null && warps.getWarp("oremountain")==null)
            warps.setWarp("oremountain",ore.getSpawnLocation().clone().add(0.5,1.0,0.5));
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if(!(e.getEntity() instanceof Player)) return;
        Player victim=(Player)e.getEntity();
        Player attacker=null;
        if(e.getDamager() instanceof Player) attacker=(Player)e.getDamager();
        else if(e.getDamager() instanceof Projectile) {
            Object shooter=((Projectile)e.getDamager()).getShooter();
            if(shooter instanceof Player) attacker=(Player)shooter;
        }
        if(isAdditionalSafe(victim.getLocation()) || (attacker!=null && isAdditionalSafe(attacker.getLocation()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onMove(PlayerMoveEvent e) {
        if(e.getTo()==null || e.getFrom().getWorld()!=e.getTo().getWorld()) return;
        Player p=e.getPlayer();
        boolean from=isAdditionalSafe(e.getFrom());
        boolean to=isAdditionalSafe(e.getTo());
        if(!from && to && zones!=null && zones.isTagged(p) && !plugin.isOwnerPlayer(p)) {
            e.setTo(e.getFrom());
            Vector v=e.getFrom().toVector().subtract(e.getTo().toVector());
            if(v.lengthSquared()<0.01) {
                Region r=protectedRegion(e.getTo());
                if(r!=null) v=p.getLocation().toVector().subtract(new Vector(r.x,p.getLocation().getY(),r.z));
            }
            if(v.lengthSquared()>0.01) p.setVelocity(v.setY(0).normalize().multiply(0.55).setY(0.16));
            p.sendMessage(EraCore.colorText("&cYou are combat tagged for &f"+zones.tagSeconds(p)+"s&c and cannot enter this Safezone."));
            return;
        }
        String key=p.getName().toLowerCase(Locale.ENGLISH);
        Boolean old=lastAdditionalSafe.put(key,to);
        if(old!=null && old.booleanValue()!=to) {
            if(to) p.sendMessage(EraCore.colorText("&aEntering protected Safezone"));
            else if(from) p.sendMessage(EraCore.colorText("&cLeaving protected Safezone"));
        }
    }

    private void buildVisibleBorders() {
        final World overworld=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(overworld==null) return;
        for(final Region r:regions) {
            if(!"overworld".equals(r.worldKey)) continue;
            if("claim".equals(r.type) && !plugin.getConfig().getBoolean("map-layout.show-claim-border",true)) continue;
            final byte color="koth".equals(r.type)||"conquest".equals(r.type)?(byte)14:
                ("portal".equals(r.type)?(byte)10:("claim".equals(r.type)?(byte)4:(byte)9));
            final ArrayDeque<int[]> q=new ArrayDeque<int[]>();
            int samples=Math.max(96,(int)Math.ceil(2*Math.PI*r.radius));
            Set<String> seen=new HashSet<String>();
            for(int i=0;i<samples;i++) {
                double a=(Math.PI*2.0*i)/samples;
                int x=(int)Math.round(r.x+Math.cos(a)*r.radius);
                int z=(int)Math.round(r.z+Math.sin(a)*r.radius);
                String k=x+":"+z;
                if(seen.add(k)) q.add(new int[]{x,z});
            }
            new BukkitRunnable() {
                public void run() {
                    int n=0;
                    while(!q.isEmpty() && n++<128) {
                        int[] pos=q.removeFirst();
                        int y=Math.max(1,overworld.getHighestBlockYAt(pos[0],pos[1]));
                        Block b=overworld.getBlockAt(pos[0],Math.max(1,y-1),pos[1]);
                        if(b.getType()==Material.BEDROCK) continue;
                        b.setType(Material.STAINED_CLAY);
                        b.setData(color);
                    }
                    if(q.isEmpty()) cancel();
                }
            }.runTaskTimer(plugin,1L,1L);
        }
    }
}
