package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * HCF movement-first terrain normalization for newly generated Overworld chunks.
 *
 * The surface stays broad and readable like an old HCF map:
 * - exact-flat road cores running to the +/-1500 border
 * - long shoulders instead of abrupt road cliffs
 * - large low-frequency hills, never one-block noise
 * - flat/blended event and portal pads
 * - sparse high-canopy trees and low rocks away from PvP lanes
 *
 * Deep vanilla caves/ores remain intact. The top few layers are sealed so
 * pathing cannot randomly fall into generation holes at the surface.
 */
@SuppressWarnings("deprecation")
final class HcfTerrainDirector implements Listener {
    private final EraCore plugin;
    private final ArrayDeque<Chunk> productionQueue=new ArrayDeque<Chunk>();
    private final Set<Long> queuedChunks=new HashSet<Long>();
    private final Set<Long> normalizedThisRun=new HashSet<Long>();
    private BukkitTask productionTask;

    HcfTerrainDirector(EraCore plugin) {
        this.plugin=plugin;
    }

    boolean busy() {
        return productionTask!=null || !productionQueue.isEmpty();
    }

    boolean queueProductionTerrain() {
        if(busy()) return false;
        World world=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(world==null) return false;

        normalizedThisRun.clear();
        plugin.getConfig().set("world-build.terrain-complete",false);
        plugin.saveConfig();

        // Repair every currently loaded Overworld chunk first. This includes
        // spawn/start-region chunks and any partial composer chunks from an
        // interrupted build. Later schematic-loaded chunks are normalized
        // synchronously by onChunkLoad before the composer writes into them.
        for(Chunk chunk:world.getLoadedChunks()) enqueue(chunk);

        if(productionQueue.isEmpty()) {
            finishProductionTerrain();
            return true;
        }

        productionTask=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run() {
                double p95=plugin.currentP95Mspt();
                int perTick=p95>=32.0?1:(p95>=24.0?2:3);
                for(int i=0;i<perTick && !productionQueue.isEmpty();i++) {
                    Chunk chunk=productionQueue.removeFirst();
                    queuedChunks.remove(chunkKey(chunk.getX(),chunk.getZ()));
                    if(chunk.isLoaded()) normalizeProductionChunk(chunk);
                }
                if(productionQueue.isEmpty()) finishProductionTerrain();
            }
        },1L,1L);
        plugin.getLogger().info("[terrain] queued production terrain repair chunks="+productionQueue.size());
        return true;
    }

    private void enqueue(Chunk chunk) {
        if(chunk==null) return;
        long key=chunkKey(chunk.getX(),chunk.getZ());
        if(queuedChunks.add(key)) productionQueue.addLast(chunk);
    }

    private long chunkKey(int x,int z) {
        return (((long)x)<<32) ^ (z&0xffffffffL);
    }

    private void finishProductionTerrain() {
        if(productionTask!=null) productionTask.cancel();
        productionTask=null;
        productionQueue.clear();
        queuedChunks.clear();
        plugin.getConfig().set("world-build.terrain-complete",true);
        plugin.saveConfig();
        plugin.getLogger().info("[terrain] production terrain stage complete; future new chunks normalize on generation.");
    }

    void stop() {
        if(productionTask!=null) productionTask.cancel();
        productionTask=null;
        productionQueue.clear();
        queuedChunks.clear();
        normalizedThisRun.clear();
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent e) {
        if(!plugin.getConfig().getBoolean("terrain.normalize-new-chunks",true)) return;
        if(e.getWorld().getEnvironment()!=World.Environment.NORMAL) return;
        // Ore Mountain is a separate resource world and keeps its own terrain.
        if(Bukkit.getWorlds().isEmpty() || !e.getWorld().equals(Bukkit.getWorlds().get(0))) return;

        final Chunk chunk=e.getChunk();

        boolean production=plugin.getConfig().getBoolean("world-build.active",false);
        boolean terrainDone=plugin.getConfig().getBoolean("world-build.terrain-complete",false);
        boolean structuresDone=plugin.getConfig().getBoolean("map.structures-complete",false);
        long key=chunkKey(chunk.getX(),chunk.getZ());

        // Production repair also covers EXISTING chunks from an interrupted
        // composer pass. Normalize each chunk once per runtime before the
        // composer writes it; remembering the chunk prevents later reloads
        // during the same structure pass from erasing already-pasted blocks.
        if(production && !structuresDone) {
            if(!terrainDone || busy()) {
                enqueue(chunk);
                return;
            }
            if(normalizedThisRun.add(key)) normalize(chunk);
            return;
        }

        // Outside production, only newly generated terrain is normalized.
        if(!e.isNewChunk()) return;
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run() {
                if(chunk.isLoaded()) normalize(chunk);
            }
        },1L);
    }

    private void normalizeProductionChunk(Chunk chunk) {
        if(chunk==null) return;
        long key=chunkKey(chunk.getX(),chunk.getZ());
        if(!normalizedThisRun.add(key)) return;
        normalize(chunk);
    }

    private void normalize(Chunk chunk) {
        World w=chunk.getWorld();
        int base=plugin.getConfig().getInt("map.surface-y",63);
        int cleanup=Math.max(28,plugin.getConfig().getInt("terrain.cleanup-height",46));

        for(int lx=0;lx<16;lx++) {
            for(int lz=0;lz<16;lz++) {
                int x=(chunk.getX()<<4)+lx;
                int z=(chunk.getZ()<<4)+lz;
                int target=targetY(x,z,base);
                Material top=surfaceMaterial(x,z);

                int highest=Math.min(w.getMaxHeight()-1,
                    Math.max(target+cleanup,w.getHighestBlockYAt(x,z)+3));
                for(int y=target+1;y<=highest;y++)
                    if(w.getBlockAt(x,y,z).getType()!=Material.AIR)
                        w.getBlockAt(x,y,z).setType(Material.AIR);

                // Seal a stable surface deck. This is intentionally stronger
                // than vanilla terrain near the top so walkers never step into
                // exposed cave lips or tiny water holes.
                w.getBlockAt(x,target,z).setType(top);
                Material fill=top==Material.SAND?Material.SAND:
                    (top==Material.STONE?Material.STONE:Material.DIRT);
                for(int y=Math.max(2,target-5);y<target;y++)
                    w.getBlockAt(x,y,z).setType(fill);
            }
        }

        if(plugin.getConfig().getBoolean("terrain.custom-decoration",true))
            decorate(chunk);
    }

    private int targetY(int x,int z,int base) {
        // Long wavelengths: a sprinting player sees slopes, not staircase noise.
        double natural=base+
            Math.sin(x*0.0065)*5.0+
            Math.cos(z*0.0054)*4.3+
            Math.sin((x+z)*0.0034)*2.8+
            Math.cos((x-z)*0.0027)*2.3;

        // Broad biome personalities.
        if(x<-620 && z>120) natural+=Math.sin(z*0.0030)*2.8;       // rocky west/south ridge
        if(x>560 && z>220) natural-=1.6;                           // southeast dry basin
        if(x>520 && z<-420) natural+=Math.cos(x*0.0032)*2.0;       // northeast forest rise

        double y=natural;

        // Spawn schematic/apron.
        double spawnDist=Math.sqrt((double)x*x+(double)z*z);
        y=blend(base,y,smoothstep(135.0,270.0,spawnDist));

        // Four Kraken roads: a genuinely flat middle and a 45-block gentle
        // shoulder. At 100+ blocks from axis the landscape is fully natural.
        double axis=Math.min(Math.abs((double)x),Math.abs((double)z));
        double roadBlend=smoothstep(
            plugin.getConfig().getDouble("terrain.road-flat-half-width",20.0),
            plugin.getConfig().getDouble("terrain.road-shoulder-half-width",72.0),
            axis);
        y=blend(base,y,roadBlend);

        // Event/portal pads are level where players fight, then merge smoothly
        // back into surrounding terrain.
        int ko=plugin.getConfig().getInt("map-layout.koth-offset",500);
        y=flattenPad(y,base,x,z, ko,-ko,175,235);
        y=flattenPad(y,base,x,z,-ko,-ko,175,235);
        y=flattenPad(y,base,x,z, ko, ko,175,235);
        y=flattenPad(y,base,x,z,-ko, ko,175,235);

        int po=plugin.getConfig().getInt("map-layout.portal-offset",1000);
        y=flattenPad(y,base,x,z, po,-po,130,190);
        y=flattenPad(y,base,x,z,-po,-po,130,190);
        y=flattenPad(y,base,x,z, po, po,130,190);
        y=flattenPad(y,base,x,z,-po, po,130,190);

        int cx=plugin.getConfig().getInt("map-layout.conquest-x",0);
        int cz=plugin.getConfig().getInt("map-layout.conquest-z",1125);
        y=flattenPad(y,base,x,z,cx,cz,185,250);

        return Math.max(56,Math.min(72,(int)Math.round(y)));
    }

    // Overload kept explicit to make call-sites readable.
    private double flattenPad(double current,int base,int x,int z,int cx,int cz,double flat,double outer) {
        double dx=x-cx,dz=z-cz;
        double d=Math.sqrt(dx*dx+dz*dz);
        if(d>=outer) return current;
        return blend(base,current,smoothstep(flat,outer,d));
    }

    private double blend(double a,double b,double t) {
        t=Math.max(0.0,Math.min(1.0,t));
        return a+(b-a)*t;
    }

    private double smoothstep(double edge0,double edge1,double x) {
        if(edge1<=edge0) return x>=edge1?1.0:0.0;
        double t=(x-edge0)/(edge1-edge0);
        t=Math.max(0.0,Math.min(1.0,t));
        return t*t*(3.0-2.0*t);
    }

    private Material surfaceMaterial(int x,int z) {
        // One restrained dry sector adds visual identity without producing the
        // noisy patchwork that makes PvP terrain hard to read.
        if(x>610 && z>310) return Material.SAND;
        if(x<-780 && z>250 && ((Math.abs(x*31+z*17)%29)==0)) return Material.STONE;
        return Material.GRASS;
    }

    private void decorate(Chunk chunk) {
        long seed=881994L ^ ((long)chunk.getX()*341873128712L) ^ ((long)chunk.getZ()*132897987541L);
        Random r=new Random(seed);
        World w=chunk.getWorld();

        // One candidate high-canopy tree on roughly half the chunks.
        if(r.nextInt(100)<52) {
            int x=(chunk.getX()<<4)+2+r.nextInt(12);
            int z=(chunk.getZ()<<4)+2+r.nextInt(12);
            if(canDecorate(x,z,8)) {
                int y=w.getHighestBlockYAt(x,z);
                Block ground=w.getBlockAt(x,Math.max(1,y-1),z);
                if(ground.getType()==Material.GRASS) buildHighCanopyTree(w,x,y,z,r);
            }
        }

        // Very sparse 1–2 block rocks, kept out of roads and fight pads.
        if(r.nextInt(100)<22) {
            int x=(chunk.getX()<<4)+2+r.nextInt(12);
            int z=(chunk.getZ()<<4)+2+r.nextInt(12);
            if(canDecorate(x,z,5)) {
                int y=w.getHighestBlockYAt(x,z);
                if(w.getBlockAt(x,Math.max(1,y-1),z).getType()==Material.GRASS) {
                    w.getBlockAt(x,y,z).setType(r.nextBoolean()?Material.COBBLESTONE:Material.MOSSY_COBBLESTONE);
                    if(r.nextInt(4)==0) w.getBlockAt(x,y+1,z).setType(Material.COBBLESTONE);
                }
            }
        }
    }

    private boolean canDecorate(int x,int z,int margin) {
        // Keep roads open for strafing and group movement.
        if(Math.min(Math.abs(x),Math.abs(z))<=95+margin) return false;

        double spawn=Math.sqrt((double)x*x+(double)z*z);
        if(spawn<285+margin) return false;

        int ko=plugin.getConfig().getInt("map-layout.koth-offset",500);
        if(near(x,z, ko,-ko,245+margin)||near(x,z,-ko,-ko,245+margin)||
           near(x,z, ko, ko,245+margin)||near(x,z,-ko, ko,245+margin)) return false;

        int po=plugin.getConfig().getInt("map-layout.portal-offset",1000);
        if(near(x,z, po,-po,200+margin)||near(x,z,-po,-po,200+margin)||
           near(x,z, po, po,200+margin)||near(x,z,-po, po,200+margin)) return false;

        int cx=plugin.getConfig().getInt("map-layout.conquest-x",0);
        int cz=plugin.getConfig().getInt("map-layout.conquest-z",1125);
        return !near(x,z,cx,cz,260+margin);
    }

    private boolean near(int x,int z,int cx,int cz,int radius) {
        long dx=(long)x-cx,dz=(long)z-cz;
        return dx*dx+dz*dz<=(long)radius*radius;
    }

    private void buildHighCanopyTree(World w,int x,int y,int z,Random r) {
        int trunk=5+r.nextInt(3);
        for(int i=0;i<trunk;i++) w.getBlockAt(x,y+i,z).setType(Material.LOG);

        int cy=y+trunk-1;
        for(int dy=-1;dy<=2;dy++) {
            int radius=dy==2?1:2;
            for(int dx=-radius;dx<=radius;dx++) {
                for(int dz=-radius;dz<=radius;dz++) {
                    if(dx==0 && dz==0 && dy<=0) continue;
                    if(Math.abs(dx)==radius && Math.abs(dz)==radius && r.nextBoolean()) continue;
                    Block b=w.getBlockAt(x+dx,cy+dy,z+dz);
                    if(b.getType()==Material.AIR) b.setType(Material.LEAVES);
                }
            }
        }
    }
}
