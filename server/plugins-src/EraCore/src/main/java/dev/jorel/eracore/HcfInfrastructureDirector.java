package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Builds/repairs physical HCF infrastructure that must exist independently of
 * the legacy map bootstrap:
 * - dedicated PotPvP duel arena
 * - Nether Safezone/warp hub
 * - End Safezone/warp hub
 *
 * The first few spawn columns are repaired synchronously so nobody is ever
 * teleported into a block. The larger decorative structures are queued.
 */
@SuppressWarnings("deprecation")
final class HcfInfrastructureDirector {
    private static final int VERSION=2;

    private static final class Op {
        final World world;
        final int x,y,z;
        final Material material;
        final byte data;
        Op(World world,int x,int y,int z,Material material){this(world,x,y,z,material,(byte)0);}
        Op(World world,int x,int y,int z,Material material,byte data){
            this.world=world;this.x=x;this.y=y;this.z=z;this.material=material;this.data=data;
        }
    }

    private final EraCore plugin;
    private final WarpManager warps;
    private final HcfZoneDisplayDirector zones;
    private final File file;
    private final YamlConfiguration data;
    private final ArrayDeque<Op> queue=new ArrayDeque<Op>();
    private BukkitTask task;
    private boolean duelReady;

    private Location duelCenter;
    private Location duelHuman;
    private Location duelSim;

    HcfInfrastructureDirector(EraCore plugin,WarpManager warps,HcfZoneDisplayDirector zones) {
        this.plugin=plugin;
        this.warps=warps;
        this.zones=zones;
        this.file=new File(plugin.getDataFolder(),"infrastructure.yml");
        this.data=YamlConfiguration.loadConfiguration(file);
    }

    void start() {
        World overworld=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(overworld==null) return;

        int dcx=plugin.getConfig().getInt("infrastructure.duel-center-x",0);
        int dcz=plugin.getConfig().getInt("infrastructure.duel-center-z",-600);
        int dfloor=plugin.getConfig().getInt("infrastructure.duel-floor-y",70);
        duelCenter=new Location(overworld,dcx+0.5,dfloor+1,dcz+0.5);
        duelHuman=new Location(overworld,dcx-18+0.5,dfloor+1,dcz+0.5,-90f,0f);
        duelSim=new Location(overworld,dcx+18+0.5,dfloor+1,dcz+0.5,90f,0f);

        // These exact columns are repaired immediately before anything can duel.
        ensureSafePad(duelHuman,3,Material.SMOOTH_BRICK);
        ensureSafePad(duelSim,3,Material.SMOOTH_BRICK);
        duelReady=true;

        World nether=firstWorld(World.Environment.NETHER);
        World end=firstWorld(World.Environment.THE_END);

        Location netherHub=nether==null?null:hubCenter(nether,
            plugin.getConfig().getInt("infrastructure.nether-y",70));
        Location endHub=end==null?null:hubCenter(end,
            plugin.getConfig().getInt("infrastructure.end-y",68));

        if(netherHub!=null) {
            ensureSafePad(netherHub,4,Material.NETHER_BRICK);
            warps.setWarp("nether",netherHub);
            nether.setSpawnLocation(netherHub.getBlockX(),netherHub.getBlockY(),netherHub.getBlockZ());
            zones.syncDimensionZone(netherHub);
        }
        if(endHub!=null) {
            ensureSafePad(endHub,4,Material.ENDER_STONE);
            warps.setWarp("end",endHub);
            end.setSpawnLocation(endHub.getBlockX(),endHub.getBlockY(),endHub.getBlockZ());
            zones.syncDimensionZone(endHub);
        }

        Location duelLobby=new Location(overworld,dcx+0.5,dfloor+2,dcz-27+0.5,0f,0f);
        ensureSafePad(duelLobby,2,Material.QUARTZ_BLOCK);
        warps.setWarp("duels",duelLobby);

        if(data.getInt("version",0)<VERSION) {
            queueSpawnFoundationRepair(overworld);
            queueDuelArena(overworld,dcx,dfloor,dcz);
            if(netherHub!=null) queueDimensionHub(netherHub,Material.NETHER_BRICK,Material.NETHER_FENCE,Material.GLOWSTONE);
            if(endHub!=null) queueDimensionHub(endHub,Material.ENDER_STONE,Material.IRON_FENCE,Material.GLOWSTONE);
            runQueue();
        } else {
            // Version already built: still repair critical spawn columns and warps.
            duelReady=validateSpawn(duelHuman) && validateSpawn(duelSim);
        }
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        save();
    }

    boolean duelReady() {
        if(duelHuman==null || duelSim==null) return false;
        ensureSafePad(duelHuman,2,Material.SMOOTH_BRICK);
        ensureSafePad(duelSim,2,Material.SMOOTH_BRICK);
        duelReady=validateSpawn(duelHuman)&&validateSpawn(duelSim);
        return duelReady;
    }

    Location safeSpawnLocation() {
        Location spawn=warps.getSpawn();
        if(spawn==null || spawn.getWorld()==null) return null;
        World w=spawn.getWorld();
        int preferredY=plugin.getConfig().getInt("map.surface-y",63)+1;
        int sx=spawn.getBlockX(),sz=spawn.getBlockZ();

        // Prefer an existing valid tile so /stuck never edits a finished spawn.
        for(int radius=0;radius<=10;radius++) {
            for(int dx=-radius;dx<=radius;dx++) {
                for(int dz=-radius;dz<=radius;dz++) {
                    if(radius>0 && Math.abs(dx)!=radius && Math.abs(dz)!=radius) continue;
                    for(int dy=-2;dy<=3;dy++) {
                        int y=preferredY+dy;
                        if(y<2 || y>=w.getMaxHeight()-2) continue;
                        Block floor=w.getBlockAt(sx+dx,y-1,sz+dz);
                        Block feet=w.getBlockAt(sx+dx,y,sz+dz);
                        Block head=w.getBlockAt(sx+dx,y+1,sz+dz);
                        if(floor.getType().isSolid() && !isLiquid(floor.getType()) &&
                           !feet.getType().isSolid() && !isLiquid(feet.getType()) &&
                           !head.getType().isSolid() && !isLiquid(head.getType())) {
                            return new Location(w,sx+dx+0.5,y,sz+dz+0.5,spawn.getYaw(),spawn.getPitch());
                        }
                    }
                }
            }
        }

        // Last-resort emergency tile. Radius 1 is deliberately tiny.
        Location safe=new Location(w,sx+0.5,preferredY,sz+0.5,spawn.getYaw(),spawn.getPitch());
        ensureSafePad(safe,1,Material.QUARTZ_BLOCK);
        return safe;
    }

    Location duelCenter(){return duelCenter==null?null:duelCenter.clone();}
    Location duelHumanSpawn(){return duelHuman==null?null:duelHuman.clone();}
    Location duelSimSpawn(){return duelSim==null?null:duelSim.clone();}

    private World firstWorld(World.Environment env) {
        for(World w:Bukkit.getWorlds()) if(w.getEnvironment()==env) return w;
        return null;
    }

    private Location hubCenter(World world,int y) {
        Location s=world.getSpawnLocation();
        int x=s.getBlockX();
        int z=s.getBlockZ();
        return new Location(world,x+0.5,Math.max(8,Math.min(world.getMaxHeight()-12,y))+1,z+0.5,0f,0f);
    }

    private void ensureSafePad(Location spawn,int radius,Material floor) {
        if(spawn==null || spawn.getWorld()==null) return;
        World w=spawn.getWorld();
        int sx=spawn.getBlockX(), sy=spawn.getBlockY(), sz=spawn.getBlockZ();
        int fy=sy-1;

        for(int x=sx-radius;x<=sx+radius;x++) {
            for(int z=sz-radius;z<=sz+radius;z++) {
                w.getBlockAt(x,fy,z).setType(floor);
                for(int y=sy;y<=sy+3;y++) w.getBlockAt(x,y,z).setType(Material.AIR);
            }
        }
    }

    private boolean validateSpawn(Location l) {
        if(l==null || l.getWorld()==null) return false;
        Block floor=l.getWorld().getBlockAt(l.getBlockX(),l.getBlockY()-1,l.getBlockZ());
        Block feet=l.getWorld().getBlockAt(l.getBlockX(),l.getBlockY(),l.getBlockZ());
        Block head=l.getWorld().getBlockAt(l.getBlockX(),l.getBlockY()+1,l.getBlockZ());
        return floor.getType()!=Material.AIR && feet.getType()==Material.AIR && head.getType()==Material.AIR;
    }

    private void queueDuelArena(World w,int cx,int floorY,int cz) {
        int rx=32,rz=22;
        // Clear a full combat volume first so no legacy terrain/base block can
        // intersect a duelist.
        for(int x=cx-rx;x<=cx+rx;x++) {
            for(int z=cz-rz;z<=cz+rz;z++) {
                for(int y=floorY+1;y<=floorY+8;y++) queue.add(new Op(w,x,y,z,Material.AIR));

                Material floor=((Math.abs(x-cx)%8==0)||(Math.abs(z-cz)%8==0))
                    ? Material.SMOOTH_BRICK : Material.STONE;
                queue.add(new Op(w,x,floorY,z,floor));

                boolean edge=x==cx-rx||x==cx+rx||z==cz-rz||z==cz+rz;
                if(edge) {
                    for(int y=floorY+1;y<=floorY+4;y++)
                        queue.add(new Op(w,x,y,z,y==floorY+4?Material.IRON_FENCE:Material.OBSIDIAN));
                }
            }
        }

        // Classic simple PotPvP visual landmarks.
        for(int x:new int[]{cx-24,cx+24}) {
            for(int z:new int[]{cz-14,cz+14}) {
                queue.add(new Op(w,x,floorY+1,z,Material.QUARTZ_BLOCK));
                queue.add(new Op(w,x,floorY+2,z,Material.GLOWSTONE));
            }
        }

        // North spectator/lobby deck, outside the sealed combat rectangle.
        for(int x=cx-12;x<=cx+12;x++) for(int z=cz-30;z<=cz-24;z++)
            queue.add(new Op(w,x,floorY+1,z,Material.QUARTZ_BLOCK));
        for(int x=cx-12;x<=cx+12;x++)
            queue.add(new Op(w,x,floorY+2,cz-24,Material.IRON_FENCE));

        // Re-queue guaranteed duel spawn pads last.
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
            queue.add(new Op(w,cx-18+dx,floorY,cz+dz,Material.QUARTZ_BLOCK));
            queue.add(new Op(w,cx+18+dx,floorY,cz+dz,Material.QUARTZ_BLOCK));
            for(int yy=floorY+1;yy<=floorY+3;yy++) {
                queue.add(new Op(w,cx-18+dx,yy,cz+dz,Material.AIR));
                queue.add(new Op(w,cx+18+dx,yy,cz+dz,Material.AIR));
            }
        }
    }

    private void queueSpawnFoundationRepair(World w) {
        Location spawn=warps.getSpawn();
        if(spawn==null || spawn.getWorld()==null || !spawn.getWorld().equals(w)) spawn=w.getSpawnLocation();
        int cx=spawn.getBlockX(),cz=spawn.getBlockZ();
        int floorY=plugin.getConfig().getInt("map.surface-y",63);
        int radius=Math.max(50,plugin.getConfig().getInt("spawn-build-protection.radius",
            (int)Math.ceil(plugin.getConfig().getDouble("map.safezone-radius",60.0))));

        // Repair the complete square footprint, including all four corners.
        // Existing constructed spawn blocks are preserved; holes, liquids and
        // natural terrain are normalized into a sealed Safezone foundation.
        for(int x=cx-radius;x<=cx+radius;x++) {
            for(int z=cz-radius;z<=cz+radius;z++) {
                for(int yy=Math.max(2,floorY-5);yy<=floorY-3;yy++) {
                    Material m=w.getBlockAt(x,yy,z).getType();
                    if(m==Material.AIR || isNatural(m) || isLiquid(m))
                        queue.add(new Op(w,x,yy,z,Material.STONE));
                }
                Material below=w.getBlockAt(x,floorY-2,z).getType();
                if(below==Material.AIR || isNatural(below) || isLiquid(below))
                    queue.add(new Op(w,x,floorY-2,z,Material.DIRT));
                Material under=w.getBlockAt(x,floorY-1,z).getType();
                if(under==Material.AIR || isNatural(under) || isLiquid(under))
                    queue.add(new Op(w,x,floorY-1,z,Material.DIRT));

                Material surface=w.getBlockAt(x,floorY,z).getType();
                if(surface==Material.AIR || isNatural(surface) || isLiquid(surface))
                    queue.add(new Op(w,x,floorY,z,Material.SMOOTH_BRICK));

                // Remove only natural clutter/liquid above grade. Chests,
                // quartz, glass, signs, redstone and other spawn construction
                // remain untouched.
                for(int yy=floorY+1;yy<=Math.min(w.getMaxHeight()-1,floorY+10);yy++) {
                    Material m=w.getBlockAt(x,yy,z).getType();
                    if(isNatural(m) || isLiquid(m)) queue.add(new Op(w,x,yy,z,Material.AIR));
                }
            }
        }

        // Explicit corner pads ensure NE/NW/SE/SW are never left as raw cuts.
        int corner=Math.max(6,plugin.getConfig().getInt("spawn-build-protection.corner-pad",10));
        int[][] signs={{1,1},{1,-1},{-1,1},{-1,-1}};
        for(int[] s:signs) {
            int ccx=cx+s[0]*(radius-corner/2);
            int ccz=cz+s[1]*(radius-corner/2);
            for(int x=ccx-corner/2;x<=ccx+corner/2;x++)
                for(int z=ccz-corner/2;z<=ccz+corner/2;z++)
                    queue.add(new Op(w,x,floorY,z,Material.SMOOTH_BRICK));
        }
    }

    private boolean isLiquid(Material m) {
        return m==Material.WATER || m==Material.STATIONARY_WATER ||
            m==Material.LAVA || m==Material.STATIONARY_LAVA;
    }

    private boolean isNatural(Material m) {
        return m==Material.GRASS || m==Material.DIRT || m==Material.STONE ||
            m==Material.SAND || m==Material.GRAVEL || m==Material.CLAY ||
            m==Material.LONG_GRASS || m==Material.YELLOW_FLOWER || m==Material.RED_ROSE ||
            m==Material.SNOW || m==Material.SNOW_BLOCK || m==Material.LEAVES ||
            m==Material.LEAVES_2 || m==Material.LOG || m==Material.LOG_2 ||
            m==Material.VINE || m==Material.DEAD_BUSH || m==Material.MYCEL;
    }

    private void queueDimensionHub(Location center,Material floor,Material fence,Material light) {
        World w=center.getWorld();
        int cx=center.getBlockX(), cz=center.getBlockZ(), floorY=center.getBlockY()-1;
        int hub=24;

        // Clear the safezone hub vertically and make it impossible to spawn in
        // lava, netherrack, End stone, or a ceiling.
        for(int x=cx-hub;x<=cx+hub;x++) {
            for(int z=cz-hub;z<=cz+hub;z++) {
                for(int y=floorY+1;y<=floorY+8;y++) queue.add(new Op(w,x,y,z,Material.AIR));
                queue.add(new Op(w,x,floorY,z,floor));

                boolean edge=x==cx-hub||x==cx+hub||z==cz-hub||z==cz+hub;
                boolean northSouthGate=(Math.abs(x-cx)<=4)&&(z==cz-hub||z==cz+hub);
                boolean eastWestGate=(Math.abs(z-cz)<=4)&&(x==cx-hub||x==cx+hub);
                if(edge && !northSouthGate && !eastWestGate) {
                    queue.add(new Op(w,x,floorY+1,z,fence));
                    queue.add(new Op(w,x,floorY+2,z,fence));
                }
            }
        }

        // Four obvious roads lead out of Safezone into the natural warzone.
        for(int d=hub+1;d<=58;d++) {
            for(int width=-3;width<=3;width++) {
                queue.add(new Op(w,cx+width,floorY,cz+d,floor));
                queue.add(new Op(w,cx+width,floorY,cz-d,floor));
                queue.add(new Op(w,cx+d,floorY,cz+width,floor));
                queue.add(new Op(w,cx-d,floorY,cz+width,floor));
                for(int yy=floorY+1;yy<=floorY+4;yy++) {
                    queue.add(new Op(w,cx+width,yy,cz+d,Material.AIR));
                    queue.add(new Op(w,cx+width,yy,cz-d,Material.AIR));
                    queue.add(new Op(w,cx+d,yy,cz+width,Material.AIR));
                    queue.add(new Op(w,cx-d,yy,cz+width,Material.AIR));
                }
            }
        }

        for(int x=cx-18;x<=cx+18;x+=12) {
            for(int z=cz-18;z<=cz+18;z+=12)
                queue.add(new Op(w,x,floorY+1,z,light));
        }

        // Center marker and safe arrival air.
        queue.add(new Op(w,cx,floorY,cz,Material.QUARTZ_BLOCK));
        for(int yy=floorY+1;yy<=floorY+4;yy++) queue.add(new Op(w,cx,yy,cz,Material.AIR));
    }

    private void runQueue() {
        if(task!=null) return;
        final int perTick=Math.max(150,plugin.getConfig().getInt("infrastructure.blocks-per-tick",500));
        task=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run() {
                int n=0;
                while(!queue.isEmpty() && n++<perTick) {
                    Op op=queue.removeFirst();
                    Block b=op.world.getBlockAt(op.x,op.y,op.z);
                    b.setType(op.material);
                    if(op.data!=0) b.setData(op.data);
                }
                if(queue.isEmpty()) {
                    data.set("version",VERSION);
                    save();
                    task.cancel();
                    task=null;
                    duelReady=validateSpawn(duelHuman)&&validateSpawn(duelSim);
                    plugin.getLogger().info("HCF infrastructure build complete: duel arena + Nether/End warp hubs.");
                }
            }
        },1L,1L);
    }

    private void save() {
        try {data.save(file);}
        catch(IOException e){plugin.getLogger().warning("Could not save infrastructure.yml: "+e.getMessage());}
    }
}
