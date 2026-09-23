package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * HCF movement-first terrain normalization.
 *
 * Visual contract:
 * - Kraken + immediate spawn frontage stay clean and readable
 * - roads remain obvious PvP/kiting lanes, but no longer flatten half the map
 * - wilderness has broad + medium rolling relief rather than superflat grass
 * - event pads stay level only where the actual fight/build needs it
 * - regional materials/landmarks make the map readable without biome noise
 * - no ravines, cliffs, floating terrain, accidental holes, or dense forests
 */
@SuppressWarnings("deprecation")
final class HcfTerrainDirector implements Listener {
    private static final class SurfaceSpec {
        final Material material;
        final byte data;
        SurfaceSpec(Material material,int data) {
            this.material=material;
            this.data=(byte)data;
        }
    }

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
        if(Bukkit.getWorlds().isEmpty() || !e.getWorld().equals(Bukkit.getWorlds().get(0))) return;

        final Chunk chunk=e.getChunk();
        boolean production=plugin.getConfig().getBoolean("world-build.active",false);
        boolean terrainDone=plugin.getConfig().getBoolean("world-build.terrain-complete",false);
        boolean structuresDone=plugin.getConfig().getBoolean("map.structures-complete",false);
        long key=chunkKey(chunk.getX(),chunk.getZ());

        if(production && !structuresDone) {
            if(!terrainDone || busy()) {
                enqueue(chunk);
                return;
            }
            if(normalizedThisRun.add(key)) normalize(chunk);
            return;
        }

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
                SurfaceSpec top=surfaceSpec(x,z,target,base);

                int highest=Math.min(w.getMaxHeight()-1,
                    Math.max(target+cleanup,w.getHighestBlockYAt(x,z)+3));
                for(int y=target+1;y<=highest;y++) {
                    Block b=w.getBlockAt(x,y,z);
                    if(b.getType()!=Material.AIR) setNoPhysics(b,Material.AIR);
                }

                setNoPhysics(w.getBlockAt(x,target,z),top.material,top.data);
                Material fill=(top.material==Material.SAND || top.material==Material.SANDSTONE)
                    ?Material.SAND:(top.material==Material.STONE||top.material==Material.COBBLESTONE
                        ?Material.STONE:Material.DIRT);
                for(int y=Math.max(2,target-5);y<target;y++)
                    setNoPhysics(w.getBlockAt(x,y,z),fill);
            }
        }

        if(plugin.getConfig().getBoolean("terrain.custom-decoration",true))
            decorate(chunk);
    }

    private int targetY(int x,int z,int base) {
        double amp=Math.max(4.0,Math.min(9.0,
            plugin.getConfig().getDouble("terrain.wilderness-amplitude",7.0)));

        // Natural HCF terrain v3: domain-warped value noise instead of visible
        // sine bands. The wavelengths are deliberately large enough that normal
        // sprint paths change by at most about one block at a time.
        double warpX=valueNoise(x,z,340.0,0x1451L)*92.0;
        double warpZ=valueNoise(x,z,340.0,0x9127L)*92.0;
        double wx=x+warpX,wz=z+warpZ;

        double relief=
            valueNoise(wx,wz,360.0,0xA311L)*amp*0.38+
            valueNoise(wx,wz,185.0,0xB827L)*amp*0.24+
            valueNoise(wx,wz,96.0,0xC593L)*amp*0.11;

        // Broad curving ridge spines plus independently warped bowls give the
        // wilderness a readable topographic silhouette without mountains.
        double ridgeField=1.0-Math.abs(valueNoise(wx+71.0,wz-43.0,245.0,0xF117L));
        double ridgeShape=Math.max(0.0,(ridgeField-0.50)/0.50);
        ridgeShape*=ridgeShape;
        double ridgeMask=0.55+0.45*valueNoise(wx,wz,520.0,0xF211L);
        double ridge=ridgeShape*ridgeMask*amp*0.42;

        double bowlField=1.0-Math.abs(valueNoise(wx-123.0,wz+89.0,305.0,0xF331L));
        double bowlShape=Math.max(0.0,(bowlField-0.57)/0.43);
        bowlShape*=bowlShape;
        double bowlMask=0.50+0.50*valueNoise(wx+80.0,wz-130.0,610.0,0xF441L);
        double bowl=bowlShape*bowlMask*amp*0.30;

        relief += ridge - bowl;

        // Regional identity remains subtle; geometry never becomes a mountain.
        if(x<-650 && z>160) relief+=1.0+valueNoise(x,z,210.0,0xD114L)*0.9;
        if(x>610 && z>260) relief-=1.0;
        if(x>520 && z<-420) relief+=0.7+valueNoise(x,z,190.0,0xE615L)*0.8;
        if(x<-520 && z<-480) relief-=0.6;

        relief=Math.max(-amp,Math.min(amp,relief));
        double y=base+relief;

        // Kraken is 253x253. Preserve a clean apron around the actual structure,
        // then begin the wilderness before the old map can read as a giant lawn.
        double spawnDist=Math.sqrt((double)x*x+(double)z*z);
        double flatRadius=Math.max(145.0,
            plugin.getConfig().getDouble("terrain.spawn-flat-radius",175.0));
        double transitionRadius=Math.max(flatRadius+70.0,
            plugin.getConfig().getDouble("terrain.spawn-transition-radius",300.0));
        y=blend(base,y,smoothstep(flatRadius,transitionRadius,spawnDist));

        // Roads need only enough flat width for group fights and kiting.
        double axis=Math.min(Math.abs((double)x),Math.abs((double)z));
        double roadBlend=smoothstep(
            plugin.getConfig().getDouble("terrain.road-flat-half-width",16.0),
            plugin.getConfig().getDouble("terrain.road-shoulder-half-width",50.0),
            axis);
        y=blend(base,y,roadBlend);

        int ko=plugin.getConfig().getInt("map-layout.koth-offset",500);
        // Match the real production schematics instead of applying one enormous
        // plateau to every event: 65x77, 101x101, 51x51, and 200x200.
        y=flattenPad(y,base,x,z, ko,-ko,
            plugin.getConfig().getDouble("terrain.koth-classic-flat-radius",52.0),
            plugin.getConfig().getDouble("terrain.koth-classic-blend-radius",118.0));
        y=flattenPad(y,base,x,z,-ko,-ko,
            plugin.getConfig().getDouble("terrain.koth-endstyle-flat-radius",64.0),
            plugin.getConfig().getDouble("terrain.koth-endstyle-blend-radius",132.0));
        y=flattenPad(y,base,x,z, ko, ko,
            plugin.getConfig().getDouble("terrain.koth-egypt-flat-radius",38.0),
            plugin.getConfig().getDouble("terrain.koth-egypt-blend-radius",105.0));
        y=flattenPad(y,base,x,z,-ko, ko,
            plugin.getConfig().getDouble("terrain.koth-frost-flat-radius",108.0),
            plugin.getConfig().getDouble("terrain.koth-frost-blend-radius",170.0));

        int po=plugin.getConfig().getInt("map-layout.portal-offset",1000);
        double portalFlat=plugin.getConfig().getDouble("terrain.portal-flat-radius",112.0);
        double portalOuter=plugin.getConfig().getDouble("terrain.portal-blend-radius",168.0);
        y=flattenPad(y,base,x,z, po,-po,portalFlat,portalOuter);
        y=flattenPad(y,base,x,z,-po,-po,portalFlat,portalOuter);
        y=flattenPad(y,base,x,z, po, po,portalFlat,portalOuter);
        y=flattenPad(y,base,x,z,-po, po,portalFlat,portalOuter);

        int cx=plugin.getConfig().getInt("map-layout.conquest-x",0);
        int cz=plugin.getConfig().getInt("map-layout.conquest-z",1125);
        y=flattenPad(y,base,x,z,cx,cz,
            plugin.getConfig().getDouble("terrain.conquest-flat-radius",158.0),
            plugin.getConfig().getDouble("terrain.conquest-blend-radius",222.0));

        int low=base-(int)Math.ceil(amp);
        int high=base+(int)Math.ceil(amp);
        return Math.max(low,Math.min(high,(int)Math.round(y)));
    }

    private double valueNoise(double x,double z,double scale,long salt) {
        double gx=x/scale,gz=z/scale;
        int x0=(int)Math.floor(gx),z0=(int)Math.floor(gz);
        int x1=x0+1,z1=z0+1;
        double tx=gx-x0,tz=gz-z0;
        tx=tx*tx*(3.0-2.0*tx);
        tz=tz*tz*(3.0-2.0*tz);

        double a=lattice(x0,z0,salt);
        double b=lattice(x1,z0,salt);
        double c0=lattice(x0,z1,salt);
        double d=lattice(x1,z1,salt);
        return blend(blend(a,b,tx),blend(c0,d,tx),tz);
    }

    private double lattice(int x,int z,long salt) {
        long h=((long)x*341873128712L)^((long)z*132897987541L)^salt;
        h^=(h>>>23); h*=0x2127599bf4325c37L; h^=(h>>>47);
        long bits=h&0x7fffffffL;
        return (bits/(double)0x7fffffffL)*2.0-1.0;
    }

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

    private long terrainHash(int x,int z) {
        long h=((long)x*341873128712L)^((long)z*132897987541L)^0x5DEECE66DL;
        h^=(h>>>21); h*=0x9E3779B97F4A7C15L; h^=(h>>>29);
        return h&Long.MAX_VALUE;
    }

    private SurfaceSpec surfaceSpec(int x,int z,int y,int base) {
        double spawn=Math.sqrt((double)x*x+(double)z*z);
        double axis=Math.min(Math.abs((double)x),Math.abs((double)z));

        // Roads keep a deliberate old-HCF gravel identity, but shoulder
        // materials vary in long coherent stretches instead of pixel noise.
        if(spawn>165 && axis<=4) return new SurfaceSpec(Material.GRAVEL,0);
        if(spawn>170 && axis<=14) {
            double shoulder=valueNoise(x,z,46.0,0x4301L);
            if(shoulder>0.28) return new SurfaceSpec(Material.DIRT,1);
            if(shoulder<-0.42) return new SurfaceSpec(Material.GRAVEL,0);
        }

        // Southeast dry basin: broad connected patches. Never choose a new
        // material independently for every block; that was the source of the
        // confetti look in the v3 QA screenshots.
        if(x>560 && z>260) {
            // Regional identity is expressed as many small connected patches,
            // never one huge brown/desert carpet.
            double dry=valueNoise(x,z,58.0,0x7719L);
            double stone=valueNoise(x,z,31.0,0x773BL);
            if(dry>0.70) {
                if(stone>0.76) return new SurfaceSpec(Material.SANDSTONE,0);
                return new SurfaceSpec(Material.SAND,0);
            }
            if(dry>0.52) return new SurfaceSpec(Material.DIRT,1);
        }

        // Rocky southwest: clustered exposed rock / gravel shelves with grass
        // remaining the dominant material between them.
        if(x<-700 && z>200) {
            double rock=valueNoise(x,z,44.0,0x5521L);
            double breakup=valueNoise(x,z,23.0,0x55A9L);
            int delta=Math.abs(y-base);
            if(delta>=4 && rock>0.72 && breakup>0.16) return new SurfaceSpec(Material.STONE,0);
            if(delta>=3 && rock>0.60 && breakup>-0.02) return new SurfaceSpec(Material.GRAVEL,0);
            if(rock>0.50 && breakup>0.10) return new SurfaceSpec(Material.DIRT,1);
        }

        // General slope accents stay restrained. Grass is the dominant HCF
        // surface; these coherent scars exist to reveal a slope, not recolor a
        // whole hillside.
        if(canSurfaceAccent(x,z)) {
            double accent=valueNoise(x,z,46.0,0x39A7L);
            int delta=Math.abs(y-base);
            if(delta>=5 && accent>0.78) return new SurfaceSpec(Material.GRAVEL,0);
            if(delta>=3 && accent>0.66) return new SurfaceSpec(Material.DIRT,1);
        }

        return new SurfaceSpec(Material.GRASS,0);
    }

    private boolean canSurfaceAccent(int x,int z) {
        if(Math.min(Math.abs(x),Math.abs(z))<42) return false;
        if(Math.sqrt((double)x*x+(double)z*z)<225) return false;
        return !insideEventCore(x,z,8);
    }

    private boolean insideEventCore(int x,int z,int extra) {
        int ko=plugin.getConfig().getInt("map-layout.koth-offset",500);
        if(near(x,z, ko,-ko,(int)Math.ceil(plugin.getConfig().getDouble("terrain.koth-classic-flat-radius",52.0))+extra)) return true;
        if(near(x,z,-ko,-ko,(int)Math.ceil(plugin.getConfig().getDouble("terrain.koth-endstyle-flat-radius",64.0))+extra)) return true;
        if(near(x,z, ko, ko,(int)Math.ceil(plugin.getConfig().getDouble("terrain.koth-egypt-flat-radius",38.0))+extra)) return true;
        if(near(x,z,-ko, ko,(int)Math.ceil(plugin.getConfig().getDouble("terrain.koth-frost-flat-radius",108.0))+extra)) return true;

        int po=plugin.getConfig().getInt("map-layout.portal-offset",1000);
        int pr=(int)Math.ceil(plugin.getConfig().getDouble("terrain.portal-flat-radius",112.0))+extra;
        if(near(x,z,po,-po,pr)||near(x,z,-po,-po,pr)||
           near(x,z,po,po,pr)||near(x,z,-po,po,pr)) return true;

        int cx=plugin.getConfig().getInt("map-layout.conquest-x",0);
        int cz=plugin.getConfig().getInt("map-layout.conquest-z",1125);
        int cr=(int)Math.ceil(plugin.getConfig().getDouble("terrain.conquest-flat-radius",158.0))+extra;
        return near(x,z,cx,cz,cr);
    }

    private void decorate(Chunk chunk) {
        long seed=881994L ^ ((long)chunk.getX()*341873128712L) ^ ((long)chunk.getZ()*132897987541L);
        Random r=new Random(seed);
        World w=chunk.getWorld();

        int treeChance=Math.max(0,Math.min(60,
            plugin.getConfig().getInt("terrain.tree-chance-percent",28)));
        if(r.nextInt(100)<treeChance) {
            int x=(chunk.getX()<<4)+2+r.nextInt(12);
            int z=(chunk.getZ()<<4)+2+r.nextInt(12);
            if(canDecorate(x,z,8)) {
                int y=w.getHighestBlockYAt(x,z);
                Block ground=w.getBlockAt(x,Math.max(1,y-1),z);
                if(ground.getType()==Material.GRASS) {
                    buildHighCanopyTree(w,x,y,z,r);
                    // Northeast gets occasional tiny copses, never forests.
                    if(x>520 && z<-420 && r.nextInt(100)<38) {
                        int x2=x+(r.nextBoolean()?5:-5)+r.nextInt(4);
                        int z2=z+(r.nextBoolean()?5:-5)+r.nextInt(4);
                        int y2=w.getHighestBlockYAt(x2,z2);
                        if(canDecorate(x2,z2,8) &&
                           w.getBlockAt(x2,Math.max(1,y2-1),z2).getType()==Material.GRASS)
                            buildHighCanopyTree(w,x2,y2,z2,r);
                    }
                }
            }
        }

        int rockChance=Math.max(0,Math.min(45,
            plugin.getConfig().getInt("terrain.rock-chance-percent",18)));
        if(r.nextInt(100)<rockChance) {
            int x=(chunk.getX()<<4)+2+r.nextInt(12);
            int z=(chunk.getZ()<<4)+2+r.nextInt(12);
            if(canDecorate(x,z,5)) {
                int y=w.getHighestBlockYAt(x,z);
                if(w.getBlockAt(x,Math.max(1,y-1),z).getType()==Material.GRASS) {
                    setNoPhysics(w.getBlockAt(x,y,z),r.nextBoolean()?Material.COBBLESTONE:Material.MOSSY_COBBLESTONE);
                    if(r.nextInt(100)<35) setNoPhysics(w.getBlockAt(x,y+1,z),Material.COBBLESTONE);
                    if(r.nextInt(100)<28) setNoPhysics(w.getBlockAt(x+1,y,z),Material.STONE);
                }
            }
        }

        // Low, pass-through vegetation gives the landscape depth at eye level.
        int grassChance=Math.max(0,Math.min(90,
            plugin.getConfig().getInt("terrain.ground-detail-chance-percent",48)));
        if(r.nextInt(100)<grassChance) {
            int count=1+r.nextInt(4);
            for(int i=0;i<count;i++) {
                int x=(chunk.getX()<<4)+1+r.nextInt(14);
                int z=(chunk.getZ()<<4)+1+r.nextInt(14);
                if(!canDecorate(x,z,1)) continue;
                int y=w.getHighestBlockYAt(x,z);
                if(w.getBlockAt(x,Math.max(1,y-1),z).getType()!=Material.GRASS) continue;
                int roll=r.nextInt(100);
                if(roll<92) setNoPhysics(w.getBlockAt(x,y,z),Material.LONG_GRASS,(byte)1);
                else if(roll<97) setNoPhysics(w.getBlockAt(x,y,z),Material.YELLOW_FLOWER,(byte)0);
                else setNoPhysics(w.getBlockAt(x,y,z),Material.RED_ROSE,(byte)0);
            }
        }
    }

    private void setNoPhysics(Block block,Material material) {
        setNoPhysics(block,material,(byte)0);
    }

    private void setNoPhysics(Block block,Material material,byte data) {
        if(block==null || material==null) return;
        block.setTypeIdAndData(material.getId(),data,false);
    }

    private boolean canDecorate(int x,int z,int margin) {
        if(Math.min(Math.abs(x),Math.abs(z))<=52+margin) return false;

        double spawn=Math.sqrt((double)x*x+(double)z*z);
        if(spawn<205+margin) return false;

        int ko=plugin.getConfig().getInt("map-layout.koth-offset",500);
        if(near(x,z, ko,-ko,(int)Math.ceil(plugin.getConfig().getDouble("terrain.koth-classic-blend-radius",118.0))+margin)) return false;
        if(near(x,z,-ko,-ko,(int)Math.ceil(plugin.getConfig().getDouble("terrain.koth-endstyle-blend-radius",132.0))+margin)) return false;
        if(near(x,z, ko, ko,(int)Math.ceil(plugin.getConfig().getDouble("terrain.koth-egypt-blend-radius",105.0))+margin)) return false;
        if(near(x,z,-ko, ko,(int)Math.ceil(plugin.getConfig().getDouble("terrain.koth-frost-blend-radius",170.0))+margin)) return false;

        int po=plugin.getConfig().getInt("map-layout.portal-offset",1000);
        int portal=(int)Math.ceil(plugin.getConfig().getDouble("terrain.portal-blend-radius",168.0));
        if(near(x,z,po,-po,portal+margin)||near(x,z,-po,-po,portal+margin)||
           near(x,z,po,po,portal+margin)||near(x,z,-po,po,portal+margin)) return false;

        int cx=plugin.getConfig().getInt("map-layout.conquest-x",0);
        int cz=plugin.getConfig().getInt("map-layout.conquest-z",1125);
        int conquest=(int)Math.ceil(plugin.getConfig().getDouble("terrain.conquest-blend-radius",222.0));
        return !near(x,z,cx,cz,conquest+margin);
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
