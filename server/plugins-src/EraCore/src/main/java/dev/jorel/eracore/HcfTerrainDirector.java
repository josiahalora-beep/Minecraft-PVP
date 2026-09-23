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
 * - a broad exact-flat Kraken/spawn apron
 * - a long 300 -> 500 block transition into low-relief wilderness
 * - exact-flat road cores running to the +/-1500 border
 * - long shoulders instead of abrupt road cliffs
 * - broad, bounded hills/valleys with no mountains/ravines/floating caps
 * - flat/blended KOTH, Conquest and portal fight pads
 * - sparse high-canopy trees and low rocks away from PvP lanes
 *
 * The production world intentionally prioritizes readable HCF movement over
 * vanilla sightseeing terrain. The surface deck is sealed so players and bots
 * do not fall into random holes; mining progression lives in the dedicated
 * resource systems/Ore Mountain rather than surface ravines.
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
                for(int y=target+1;y<=highest;y++) {
                    Block b=w.getBlockAt(x,y,z);
                    if(b.getType()!=Material.AIR) setNoPhysics(b,Material.AIR);
                }

                // Seal a stable surface deck without physics. Structure
                // composition loads chunks synchronously; allowing 1.8 block
                // physics here can recursively load neighbors and trip the
                // watchdog before the compositor gets a chance to paste.
                setNoPhysics(w.getBlockAt(x,target,z),top);
                Material fill=top==Material.SAND?Material.SAND:
                    (top==Material.STONE?Material.STONE:Material.DIRT);
                for(int y=Math.max(2,target-5);y<target;y++)
                    setNoPhysics(w.getBlockAt(x,y,z),fill);
            }
        }

        if(plugin.getConfig().getBoolean("terrain.custom-decoration",true))
            decorate(chunk);
    }

    private int targetY(int x,int z,int base) {
        // Build Viewer terrain contract: old-HCF readable first, natural second.
        // Long waves are deliberately bounded to a small vertical range so a
        // player can kite in any direction without cliffs, ravines or staircase
        // noise appearing between adjacent chunks.
        double amplitude=Math.max(2.0,Math.min(7.0,
            plugin.getConfig().getDouble("terrain.wilderness-amplitude",5.0)));
        double relief=
            Math.sin(x*0.0042)*amplitude*0.44+
            Math.cos(z*0.0037)*amplitude*0.34+
            Math.sin((x+z)*0.0021)*amplitude*0.24+
            Math.cos((x-z)*0.0017)*amplitude*0.18;

        // Broad regional identity without creating mountain biomes.
        if(x<-650 && z>160) relief+=Math.sin(z*0.0022)*0.9; // rocky southwest rise
        if(x>610 && z>260) relief-=0.8;                     // southeast dry basin
        if(x>540 && z<-430) relief+=Math.cos(x*0.0020)*0.7;// northeast wooded rise
        relief=Math.max(-amplitude,Math.min(amplitude,relief));

        double natural=base+relief;
        double y=natural;

        // Kraken + immediate PvP frontage stays completely flat. The 300-500
        // transition is long enough that the spawn/warzone border never feels
        // like a giant artificial plateau edge.
        double spawnDist=Math.sqrt((double)x*x+(double)z*z);
        double flatRadius=Math.max(150.0,
            plugin.getConfig().getDouble("terrain.spawn-flat-radius",300.0));
        double transitionRadius=Math.max(flatRadius+80.0,
            plugin.getConfig().getDouble("terrain.spawn-transition-radius",500.0));
        y=blend(base,y,smoothstep(flatRadius,transitionRadius,spawnDist));

        // Four Kraken roads remain exact-flat in the center, with very broad
        // shoulders. This is the main HCF chase/kiting network.
        double axis=Math.min(Math.abs((double)x),Math.abs((double)z));
        double roadBlend=smoothstep(
            plugin.getConfig().getDouble("terrain.road-flat-half-width",22.0),
            plugin.getConfig().getDouble("terrain.road-shoulder-half-width",92.0),
            axis);
        y=blend(base,y,roadBlend);

        // Event/portal pads are level where players actually fight, then merge
        // back into the low-relief wilderness instead of sitting on square slabs.
        int ko=plugin.getConfig().getInt("map-layout.koth-offset",500);
        y=flattenPad(y,base,x,z, ko,-ko,175,250);
        y=flattenPad(y,base,x,z,-ko,-ko,175,250);
        y=flattenPad(y,base,x,z, ko, ko,175,250);
        y=flattenPad(y,base,x,z,-ko, ko,175,250);

        int po=plugin.getConfig().getInt("map-layout.portal-offset",1000);
        y=flattenPad(y,base,x,z, po,-po,130,205);
        y=flattenPad(y,base,x,z,-po,-po,130,205);
        y=flattenPad(y,base,x,z, po, po,130,205);
        y=flattenPad(y,base,x,z,-po, po,130,205);

        int cx=plugin.getConfig().getInt("map-layout.conquest-x",0);
        int cz=plugin.getConfig().getInt("map-layout.conquest-z",1125);
        y=flattenPad(y,base,x,z,cx,cz,185,265);

        int low=base-(int)Math.ceil(amplitude);
        int high=base+(int)Math.ceil(amplitude);
        return Math.max(low,Math.min(high,(int)Math.round(y)));
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

        // Sparse high-canopy trees: enough landmarks to read terrain, never
        // enough density to turn a road fight into leaf-block pathfinding.
        int treeChance=Math.max(0,Math.min(70,plugin.getConfig().getInt("terrain.tree-chance-percent",34)));
        if(r.nextInt(100)<treeChance) {
            int x=(chunk.getX()<<4)+2+r.nextInt(12);
            int z=(chunk.getZ()<<4)+2+r.nextInt(12);
            if(canDecorate(x,z,8)) {
                int y=w.getHighestBlockYAt(x,z);
                Block ground=w.getBlockAt(x,Math.max(1,y-1),z);
                if(ground.getType()==Material.GRASS) buildHighCanopyTree(w,x,y,z,r);
            }
        }

        // Very sparse 1–2 block rocks, kept out of roads and fight pads.
        int rockChance=Math.max(0,Math.min(40,plugin.getConfig().getInt("terrain.rock-chance-percent",14)));
        if(r.nextInt(100)<rockChance) {
            int x=(chunk.getX()<<4)+2+r.nextInt(12);
            int z=(chunk.getZ()<<4)+2+r.nextInt(12);
            if(canDecorate(x,z,5)) {
                int y=w.getHighestBlockYAt(x,z);
                if(w.getBlockAt(x,Math.max(1,y-1),z).getType()==Material.GRASS) {
                    setNoPhysics(w.getBlockAt(x,y,z),r.nextBoolean()?Material.COBBLESTONE:Material.MOSSY_COBBLESTONE);
                    if(r.nextInt(4)==0) setNoPhysics(w.getBlockAt(x,y+1,z),Material.COBBLESTONE);
                }
            }
        }
    }

    private void setNoPhysics(Block block,Material material) {
        if(block==null || material==null) return;
        block.setTypeIdAndData(material.getId(),(byte)0,false);
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
        for(int i=0;i<trunk;i++) setNoPhysics(w.getBlockAt(x,y+i,z),Material.LOG);

        int cy=y+trunk-1;
        for(int dy=-1;dy<=2;dy++) {
            int radius=dy==2?1:2;
            for(int dx=-radius;dx<=radius;dx++) {
                for(int dz=-radius;dz<=radius;dz++) {
                    if(dx==0 && dz==0 && dy<=0) continue;
                    if(Math.abs(dx)==radius && Math.abs(dz)==radius && r.nextBoolean()) continue;
                    Block b=w.getBlockAt(x+dx,cy+dy,z+dz);
                    if(b.getType()==Material.AIR) setNoPhysics(b,Material.LEAVES);
                }
            }
        }
    }
}
