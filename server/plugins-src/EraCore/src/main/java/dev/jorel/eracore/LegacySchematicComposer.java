package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Dependency-free MCEdit/WorldEdit Alpha .schematic compositor for 1.8.8.
 *
 * Jobs stream through schematic arrays instead of allocating millions of block
 * operation objects.  WorldEdit offsets are honored exactly:
 * world = pasteAnchor + local + WEOffset.
 *
 * Tile entities are intentionally left to EraCore functional directors (crates,
 * elevators, resource spawners).  Static blocks/data are authoritative.
 */
@SuppressWarnings("deprecation")
final class LegacySchematicComposer {
    static final class Schematic {
        int width,height,length;
        int offX,offY,offZ;
        byte[] blocks,data,add;
        int blockId(int index) {
            int id=blocks[index]&0xFF;
            if(add!=null && (index>>1)<add.length) {
                int nibble=((index&1)==0)?(add[index>>1]&0x0F):((add[index>>1]>>4)&0x0F);
                id|=nibble<<8;
            }
            return id;
        }
        byte blockData(int index){return data==null||index>=data.length?0:data[index];}
        int volume(){return width*height*length;}
    }

    private abstract static class Job {
        final String label;
        long processed=0,changed=0;
        Job(String label){this.label=label;}
        abstract boolean step(int budget);
        abstract int progressPercent();
    }

    private final class PasteJob extends Job {
        final World world;
        final Schematic s;
        final int ax,ay,az;
        final boolean pasteAir;
        int cursor=0;
        int lastLogged=-1;
        PasteJob(String label,World world,Schematic s,int ax,int ay,int az,boolean pasteAir) {
            super(label);this.world=world;this.s=s;this.ax=ax;this.ay=ay;this.az=az;this.pasteAir=pasteAir;
        }
        boolean step(int budget) {
            int volume=s.volume(),writes=0,scanned=0;
            // Schematic volumes are mostly air. Scan many cells per tick, but
            // charge the expensive budget only when the world actually needs a
            // block mutation. This preserves terrain clearing while avoiding
            // millions of redundant setType(AIR) calls on already-empty space.
            int scanCap=Math.max(8192,Math.min(50000,budget*96));
            while(cursor<volume && writes<budget && scanned<scanCap) {
                int i=cursor++;
                scanned++;
                int id=s.blockId(i);
                if(id==0 && !pasteAir){processed++;continue;}

                int layer=s.width*s.length;
                int y=i/layer;
                int rem=i-y*layer;
                int z=rem/s.width;
                int x=rem-z*s.width;
                int wx=ax+x+s.offX, wy=ay+y+s.offY, wz=az+z+s.offZ;
                processed++;
                if(wy<=0 || wy>=world.getMaxHeight()) continue;

                int cx=wx>>4,cz=wz>>4;
                if(!world.isChunkLoaded(cx,cz)) {
                    // Finish the current write batch before forcing another
                    // chunk load; this keeps one chunk-generation spike from
                    // monopolizing the server tick.
                    if(writes>0) { cursor--; processed--; break; }
                    world.loadChunk(cx,cz,true);
                }

                Block b=world.getBlockAt(wx,wy,wz);
                byte data=s.blockData(i);
                if(b.getTypeId()==id && b.getData()==data) continue;

                b.setTypeIdAndData(id,data,false);
                changed++;
                writes++;
            }
            logProgress();
            return cursor>=volume;
        }
        int progressPercent(){return Math.min(100,(int)((cursor*100L)/Math.max(1,s.volume())));}
        private void logProgress() {
            int p=progressPercent();
            if(p/5!=lastLogged/5) {
                lastLogged=p;
                plugin.getLogger().info("[composer] "+label+" "+p+"% writes="+changed+" scanned="+processed);
            }
        }
    }

    private final class RoadJob extends Job {
        final World world;
        final Schematic s;
        final int ax,ay,az;
        final int x1,x2,z1,z2;
        final int shiftX,shiftZ,repeats;
        final int tileWidth,tileLength,tileVolume;
        int cursor=0;
        RoadJob(String label,World world,Schematic s,int ax,int ay,int az,
                int x1,int x2,int z1,int z2,int shiftX,int shiftZ,int repeats) {
            super(label);this.world=world;this.s=s;this.ax=ax;this.ay=ay;this.az=az;
            this.x1=x1;this.x2=x2;this.z1=z1;this.z2=z2;
            this.shiftX=shiftX;this.shiftZ=shiftZ;this.repeats=Math.max(0,repeats);
            this.tileWidth=x2-x1+1;this.tileLength=z2-z1+1;
            this.tileVolume=tileWidth*tileLength*s.height;
        }
        boolean step(int budget) {
            int total=tileVolume*repeats,done=0,scanned=0;
            // Road source slices are mostly air because they inherit Kraken's
            // full vertical schematic height. Air does not need to be written
            // into the fresh flat SOTW world. Scan it cheaply while preserving
            // the same bounded number of real block writes per tick.
            int scanCap=Math.max(4096,Math.min(20000,budget*32));
            while(cursor<total && done<budget && scanned<scanCap) {
                int global=cursor++;
                scanned++;
                int repeat=global/tileVolume;
                int local=global-repeat*tileVolume;
                int plane=tileWidth*tileLength;
                int y=local/plane;
                int rem=local-y*plane;
                int zz=rem/tileWidth;
                int xx=rem-zz*tileWidth;
                int sx=x1+xx,sz=z1+zz;
                int source=y*s.width*s.length+sz*s.width+sx;
                int id=s.blockId(source);
                processed++;
                if(id==0) continue;

                int wx=ax+sx+s.offX+shiftX*(repeat+1);
                int wy=ay+y+s.offY;
                int wz=az+sz+s.offZ+shiftZ*(repeat+1);
                if(wy>0 && wy<world.getMaxHeight()) {
                    int cx=wx>>4,cz=wz>>4;
                    if(!world.isChunkLoaded(cx,cz)) {
                        if(done>0) { cursor--; processed--; break; }
                        world.loadChunk(cx,cz,true);
                    }
                    world.getBlockAt(wx,wy,wz).setTypeIdAndData(id,s.blockData(source),false);
                    changed++;
                    done++;
                }
            }
            return cursor>=total;
        }
        int progressPercent() {
            int total=Math.max(1,tileVolume*repeats);
            return Math.min(100,(int)((cursor*100L)/total));
        }
    }

    /**
     * Extends the actual surface pattern from the uploaded 101x101 spawn roads.
     *
     * This is intentionally NOT a generated gravel road. Every road block/data
     * value comes from the spawn schematic's terminal road rows/columns.
     * The pattern is projected onto the authored Stylez terrain so only the
     * narrow copied road footprint changes.
     */
    private final class SpawnRoadSurfaceJob extends Job {
        final World world;
        final Schematic s;
        final int ax,az;
        final int direction; // 0=N,1=S,2=W,3=E
        final int border,totalSteps,perpendicularSize;
        int cursor=0;

        SpawnRoadSurfaceJob(String label,World world,Schematic s,int ax,int az,int direction,int border) {
            super(label);
            this.world=world;this.s=s;this.ax=ax;this.az=az;this.direction=direction;
            this.border=Math.max(64,border);
            this.perpendicularSize=(direction<=1)?s.width:s.length;
            int boundary=(direction==0||direction==2)?
                Math.abs((direction==0?s.offZ:s.offX)):
                Math.abs((direction==1?s.offZ+s.length-1:s.offX+s.width-1));
            this.totalSteps=Math.max(0,this.border-boundary);
        }

        boolean step(int budget) {
            int total=Math.max(1,totalSteps*perpendicularSize);
            int writes=0,scanned=0;
            int scanCap=Math.max(4096,Math.min(24000,budget*48));
            while(cursor<total && writes<budget && scanned<scanCap) {
                int global=cursor++;
                scanned++;
                int longitudinal=global/perpendicularSize;
                int p=global-longitudinal*perpendicularSize;

                int sx,sz,wx,wz;
                if(direction==0) { // north: exact terminal rows 1..25
                    sz=1+(longitudinal%25); sx=p;
                    wx=ax+sx+s.offX;
                    wz=az+s.offZ-longitudinal;
                } else if(direction==1) { // south: exact terminal rows 99..90
                    sz=99-(longitudinal%10); sx=p;
                    wx=ax+sx+s.offX;
                    wz=az+s.offZ+s.length-1+longitudinal;
                } else if(direction==2) { // west: exact terminal columns 1..6
                    sx=1+(longitudinal%6); sz=p;
                    wx=ax+s.offX-longitudinal;
                    wz=az+sz+s.offZ;
                } else { // east: exact terminal columns 99..95
                    sx=99-(longitudinal%5); sz=p;
                    wx=ax+s.offX+s.width-1+longitudinal;
                    wz=az+sz+s.offZ;
                }

                if(sx<0||sx>=s.width||sz<0||sz>=s.length) continue;
                int source=sz*s.width+sx; // approved road surface lives at schematic Y=0
                int id=s.blockId(source);
                if(!isSpawnRoadSurface(id)) continue;

                int cx=wx>>4,cz=wz>>4;
                if(!world.isChunkLoaded(cx,cz)) {
                    if(writes>0) { cursor--; break; }
                    world.loadChunk(cx,cz,true);
                }

                int y=roadGroundY(world,wx,wz);
                for(int yy=y+1;yy<=Math.min(world.getMaxHeight()-1,y+10);yy++) {
                    Block above=world.getBlockAt(wx,yy,wz);
                    if(!isRoadVegetation(above.getType())) break;
                    if(above.getType()!=Material.AIR) {
                        above.setTypeIdAndData(Material.AIR.getId(),(byte)0,false);
                        changed++;writes++;
                    }
                }

                Block dst=world.getBlockAt(wx,y,wz);
                byte data=s.blockData(source);
                if(dst.getTypeId()!=id || dst.getData()!=data) {
                    dst.setTypeIdAndData(id,data,false);
                    changed++;writes++;
                }
                processed++;
            }
            return cursor>=total;
        }

        int progressPercent() {
            int total=Math.max(1,totalSteps*perpendicularSize);
            return Math.min(100,(int)((cursor*100L)/total));
        }
    }

    private boolean isSpawnRoadSurface(int id) {
        // Exact Y=0 road palette in HCF-Spawn-101-production.schematic.
        return id==1 || id==4 || id==13 || id==35;
    }

    private boolean isRoadVegetation(Material m) {
        return m==Material.AIR || m==Material.LONG_GRASS ||
            m==Material.YELLOW_FLOWER || m==Material.RED_ROSE ||
            m==Material.DOUBLE_PLANT || m==Material.SNOW ||
            m==Material.VINE || m==Material.LEAVES || m==Material.LEAVES_2 ||
            m==Material.LOG || m==Material.LOG_2;
    }

    private int roadGroundY(World world,int x,int z) {
        int y=Math.min(world.getMaxHeight()-1,world.getHighestBlockYAt(x,z));
        if(world.getBlockAt(x,y,z).getType()==Material.AIR) y--;
        while(y>2 && isRoadVegetation(world.getBlockAt(x,y,z).getType())) y--;
        return Math.max(2,y);
    }

    private int countRoadSurfaceOnRow(Schematic spawn,int z) {
        int count=0;
        for(int x=0;x<spawn.width;x++) {
            int i=z*spawn.width+x; // approved road surface lives at schematic Y=0
            if(i>=0 && i<spawn.blocks.length && isSpawnRoadSurface(spawn.blockId(i))) count++;
        }
        return count;
    }

    private int countRoadSurfaceOnColumn(Schematic spawn,int x) {
        int count=0;
        for(int z=0;z<spawn.length;z++) {
            int i=z*spawn.width+x; // approved road surface lives at schematic Y=0
            if(i>=0 && i<spawn.blocks.length && isSpawnRoadSurface(spawn.blockId(i))) count++;
        }
        return count;
    }

    private void validateSpawnRoadContract(Schematic spawn)throws IOException {
        if(spawn.width!=101 || spawn.height!=37 || spawn.length!=101 ||
           spawn.offX!=-50 || spawn.offY!=-1 || spawn.offZ!=-50)
            throw new IOException("Approved HCF spawn must be 101x37x101 with WE offset -50,-1,-50.");

        // Validate the actual terminal road signatures instead of one brittle
        // coordinate per side. Decorative/grass cells legitimately sit between
        // road pixels at the schematic boundary.
        int north=countRoadSurfaceOnRow(spawn,1);
        int south=countRoadSurfaceOnRow(spawn,99);
        int west=countRoadSurfaceOnColumn(spawn,1);
        int east=countRoadSurfaceOnColumn(spawn,99);
        if(north<4 || south<4 || west<4 || east<4)
            throw new IOException("Approved HCF spawn road exits are incomplete: N="+north+
                " S="+south+" W="+west+" E="+east);
    }

    private final EraCore plugin;
    private final File assetDir;
    private final ArrayDeque<Job> jobs=new ArrayDeque<Job>();
    private BukkitTask runner;
    private boolean productionRun=false;

    LegacySchematicComposer(EraCore plugin) {
        this.plugin=plugin;
        this.assetDir=new File(plugin.getDataFolder().getParentFile().getParentFile(),"map-assets");
    }

    boolean busy(){return runner!=null || !jobs.isEmpty();}
    int queuedJobs(){return jobs.size();}
    String currentJobLabel(){Job j=jobs.peekFirst();return j==null?"":j.label;}
    int currentJobProgress(){Job j=jobs.peekFirst();return j==null?100:j.progressPercent();}

    void stop() {
        if(runner!=null) runner.cancel();
        runner=null;jobs.clear();productionRun=false;
    }

    boolean hasAsset(String name) {
        return new File(assetDir,name).isFile();
    }

    List<String> missingProductionAssets() {
        List<String> out=new ArrayList<String>();
        for(String name:productionAssetNames()) if(!hasAsset(name)) out.add(name);
        return out;
    }

    private List<String> productionAssetNames() {
        return Arrays.asList(
            plugin.getConfig().getString("world-composer.assets.spawn","HCF-Spawn-101-production.schematic"),
            plugin.getConfig().getString("world-composer.assets.koth-classic","KOTH2-production-1.8.schematic"),
            plugin.getConfig().getString("world-composer.assets.koth-endstyle","EndStyleKOTH-production-1.8.schematic"),
            plugin.getConfig().getString("world-composer.assets.koth-egypt","EgyptKOTH-production-1.8.schematic"),
            plugin.getConfig().getString("world-composer.assets.koth-frost","KOTH-Forty-1.8-converted.schematic"),
            plugin.getConfig().getString("world-composer.assets.conquest","conquest.schematic"),
            plugin.getConfig().getString("world-composer.assets.nether-spawn","NetherSpawnWillzaTeam.schematic"),
            plugin.getConfig().getString("world-composer.assets.end","magical-hcf-end-xayden-bt.schematic")
        );
    }

    boolean queueSpawnOnly() {
        if(busy()) return false;
        String name=asset("spawn","HCF-Spawn-101-production.schematic");
        if(!hasAsset(name)) {
            plugin.getLogger().warning("Cannot compose HCF spawn; missing asset: "+name);
            return false;
        }
        try {
            World over=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
            if(over==null) return false;
            Schematic spawn=load(name);
            validateSpawnRoadContract(spawn);
            jobs.add(new PasteJob("HCF Spawn",over,spawn,0,
                plugin.getConfig().getInt("world-composer.spawn-anchor-y",66),0,false));
            productionRun=false;
            ensureRunner();
            plugin.getLogger().info("[composer] queued spawn-only reset asset="+name+
                " volume="+spawn.volume());
            return true;
        } catch(Exception e) {
            jobs.clear();
            plugin.getLogger().severe("Could not queue HCF spawn reset: "+e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    boolean queueRoadsOnly() {
        if(busy()) return false;
        String name=asset("spawn","HCF-Spawn-101-production.schematic");
        if(!hasAsset(name)) {
            plugin.getLogger().warning("Cannot compose HCF roads; missing spawn asset: "+name);
            return false;
        }
        try {
            World over=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
            if(over==null) return false;
            Schematic spawn=load(name);
            validateSpawnRoadContract(spawn);
            int border=plugin.getConfig().getInt("map.world-border",2000)/2;
            jobs.add(new SpawnRoadSurfaceJob("North HCF spawn road",over,spawn,0,0,0,border));
            jobs.add(new SpawnRoadSurfaceJob("South HCF spawn road",over,spawn,0,0,1,border));
            jobs.add(new SpawnRoadSurfaceJob("West HCF spawn road",over,spawn,0,0,2,border));
            jobs.add(new SpawnRoadSurfaceJob("East HCF spawn road",over,spawn,0,0,3,border));
            productionRun=false;
            ensureRunner();
            plugin.getLogger().info("[composer] queued roads-only HCF pass border="+border+
                " source="+name);
            return true;
        } catch(Exception e) {
            jobs.clear();
            plugin.getLogger().severe("Could not queue HCF roads-only pass: "+e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    boolean queueProductionMap(HcfMapDirector map) {
        if(busy()) return false;
        List<String> missing=missingProductionAssets();
        if(!missing.isEmpty()) {
            plugin.getLogger().warning("Cannot compose HCF production map; missing assets: "+join(missing,", "));
            return false;
        }
        try {
            World over=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
            final boolean qaOverworldOnly=plugin.getConfig().getBoolean("world-composer.qa-overworld-only",false);
            World nether=qaOverworldOnly?null:firstWorld(World.Environment.NETHER);
            World end=qaOverworldOnly?null:firstWorld(World.Environment.THE_END);
            if(over==null || (!qaOverworldOnly && (nether==null || end==null))) {
                plugin.getLogger().warning("Cannot compose HCF map until required worlds are loaded.");
                return false;
            }

            Schematic spawn=load(asset("spawn","HCF-Spawn-101-production.schematic"));
            validateSpawnRoadContract(spawn);
            // Kraken's 253x253 WorldEdit selection contains large intentionally
            // empty quadrants around the cross-shaped spawn/roads. Those AIR
            // cells are selection padding, not instructions to excavate the
            // fresh terrain. Production Overworld structures paste non-air only.
            jobs.add(new PasteJob("HCF Spawn",over,spawn,0,
                plugin.getConfig().getInt("world-composer.spawn-anchor-y",66),0,false));

            int ko=plugin.getConfig().getInt("map-layout.koth-offset",500);
            // Keep the already-reviewed quadrant coordinates, but anchor each
            // schematic to the authored FreeMap ground at its own center. This
            // replaces the obsolete superflat-Y assumption without grading the
            // surrounding builder terrain.
            int classicY=plugin.canonicalHcfTerrainY( ko,-ko);
            int endstyleY=plugin.canonicalHcfTerrainY(-ko,-ko);
            int egyptY=plugin.canonicalHcfTerrainY( ko, ko);
            int frostY=plugin.canonicalHcfTerrainY(-ko, ko);
            jobs.add(new PasteJob("Classic KOTH",over,load(asset("koth-classic","KOTH2-production-1.8.schematic")), ko,classicY,-ko,false));
            jobs.add(new PasteJob("EndStyle KOTH",over,load(asset("koth-endstyle","EndStyleKOTH-production-1.8.schematic")),-ko,endstyleY,-ko,false));
            jobs.add(new PasteJob("Egypt KOTH",over,load(asset("koth-egypt","EgyptKOTH-production-1.8.schematic")), ko,egyptY,ko,false));
            jobs.add(new PasteJob("Frost KOTH",over,load(asset("koth-frost","KOTH-Forty-1.8-converted.schematic")),-ko,frostY,ko,false));

            int conquestX=plugin.getConfig().getInt("map-layout.conquest-x",0);
            int conquestZ=plugin.getConfig().getInt("map-layout.conquest-z",775);
            int conquestY=plugin.canonicalHcfTerrainY(conquestX,conquestZ);
            jobs.add(new PasteJob("Conquest",over,load(asset("conquest","conquest.schematic")),
                conquestX,conquestY,conquestZ,false));

            if(!qaOverworldOnly) {
                jobs.add(new PasteJob("Nether Spawn",nether,load(asset("nether-spawn","NetherSpawnWillzaTeam.schematic")),
                    0,plugin.getConfig().getInt("world-composer.nether-anchor-y",70),0,true));
                jobs.add(new PasteJob("Magic End",end,load(asset("end","magical-hcf-end-xayden-bt.schematic")),
                    0,plugin.getConfig().getInt("world-composer.end-anchor-y",68),0,true));
            } else {
                plugin.getLogger().info("[composer] QA Overworld-only mode: skipping Nether Spawn and Magic End composition.");
            }

            int border=plugin.getConfig().getInt("map.world-border",2000)/2;
            // Continue the uploaded spawn's own road design to the 2k border.
            // No generated gravel lane/palette is used.
            jobs.add(new SpawnRoadSurfaceJob("North HCF spawn road",over,spawn,0,0,0,border));
            jobs.add(new SpawnRoadSurfaceJob("South HCF spawn road",over,spawn,0,0,1,border));
            jobs.add(new SpawnRoadSurfaceJob("West HCF spawn road",over,spawn,0,0,2,border));
            jobs.add(new SpawnRoadSurfaceJob("East HCF spawn road",over,spawn,0,0,3,border));

            productionRun=true;
            plugin.getConfig().set("map.structures-complete",false);
            plugin.getConfig().set("map.complete",false);
            plugin.getConfig().set("world-build.complete",false);
            plugin.saveConfig();
            ensureRunner();
            plugin.getLogger().info("[composer] queued production HCF map jobs="+jobs.size()+" assets="+assetDir.getAbsolutePath());
            return true;
        } catch(Exception e) {
            jobs.clear();productionRun=false;
            plugin.getLogger().severe("Could not queue production HCF map: "+e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private String asset(String key,String fallback) {
        return plugin.getConfig().getString("world-composer.assets."+key,fallback);
    }

    private void ensureRunner() {
        if(runner!=null) return;
        runner=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run() {
                int configured=Math.max(20,Math.min(500,plugin.getConfig().getInt("world-composer.blocks-per-tick",320)));
                int budget=configured;
                double p95=plugin.currentP95Mspt();
                // Actual world writes are now budgeted separately from cheap
                // schematic scans. Let healthy servers use more of the available
                // headroom while retaining aggressive backoff before 50ms/tick.
                if(p95>=48.0) budget=0;
                else if(p95>=40.0) budget=Math.min(budget,20);
                else if(p95>=34.0) budget=Math.min(budget,50);
                else if(p95>=28.0) budget=Math.min(budget,100);
                else if(p95>=24.0) budget=Math.min(budget,160);
                else if(p95>=20.0) budget=Math.min(budget,240);
                if(budget<=0) return;

                while(budget>0 && !jobs.isEmpty()) {
                    Job j=jobs.peekFirst();
                    long beforeChanged=j.changed;
                    long beforeProcessed=j.processed;
                    boolean done=j.step(budget);
                    int writes=(int)Math.max(0,j.changed-beforeChanged);
                    long scanned=j.processed-beforeProcessed;
                    // A scan-only step still yields after one job pass, but it
                    // does not consume the block-write budget. This lets sparse
                    // schematic air advance quickly without increasing write pressure.
                    if(writes>0) budget-=writes;
                    else if(scanned<=0) budget-=1;
                    if(done) {
                        jobs.removeFirst();
                        plugin.getLogger().info("[composer] completed "+j.label+" changed="+j.changed);
                    }
                }
                if(jobs.isEmpty()) {
                    if(productionRun) {
                        plugin.finalizeProductionSpawn();
                        plugin.getConfig().set("map.structures-complete",true);
                        plugin.getConfig().set("map.production-layout-version",4);
                        plugin.saveConfig();
                        plugin.getLogger().info("[composer] production HCF structures complete; HCF spawn finalized and resource stage may begin.");
                    }
                    productionRun=false;
                    runner.cancel();runner=null;
                }
            }
        },1L,1L);
    }

    private World firstWorld(World.Environment env) {
        for(World w:Bukkit.getWorlds()) if(w.getEnvironment()==env) return w;
        return null;
    }

    private File file(String name){return new File(assetDir,name);}

    private Schematic load(String name)throws IOException {
        File f=file(name);
        if(!f.isFile()) throw new FileNotFoundException(f.getAbsolutePath());
        DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))));
        try {
            int rootType=in.readUnsignedByte();
            if(rootType!=10) throw new IOException("NBT root is not a compound: "+name);
            readString(in); // root name
            Schematic s=new Schematic();
            while(true) {
                int type=in.readUnsignedByte();
                if(type==0) break;
                String tag=readString(in);
                if(type==2 && "Width".equals(tag)) s.width=in.readShort()&0xFFFF;
                else if(type==2 && "Height".equals(tag)) s.height=in.readShort()&0xFFFF;
                else if(type==2 && "Length".equals(tag)) s.length=in.readShort()&0xFFFF;
                else if(type==3 && "WEOffsetX".equals(tag)) s.offX=in.readInt();
                else if(type==3 && "WEOffsetY".equals(tag)) s.offY=in.readInt();
                else if(type==3 && "WEOffsetZ".equals(tag)) s.offZ=in.readInt();
                else if(type==7 && "Blocks".equals(tag)) s.blocks=readByteArray(in);
                else if(type==7 && "Data".equals(tag)) s.data=readByteArray(in);
                else if(type==7 && "AddBlocks".equals(tag)) s.add=readByteArray(in);
                else skipPayload(in,type);
            }
            if(s.width<=0||s.height<=0||s.length<=0||s.blocks==null)
                throw new IOException("Invalid schematic "+name);
            if(s.blocks.length<s.volume())
                throw new IOException("Truncated schematic "+name+" blocks="+s.blocks.length+" volume="+s.volume());
            plugin.getLogger().info("[composer] loaded "+name+" "+s.width+"x"+s.height+"x"+s.length+
                " offset="+s.offX+","+s.offY+","+s.offZ);
            return s;
        } finally {in.close();}
    }

    private static String readString(DataInputStream in)throws IOException {
        int len=in.readUnsignedShort();
        byte[] b=new byte[len];in.readFully(b);
        return new String(b,"UTF-8");
    }

    private static byte[] readByteArray(DataInputStream in)throws IOException {
        int len=in.readInt();
        if(len<0 || len>100000000) throw new IOException("Invalid byte-array length "+len);
        byte[] b=new byte[len];in.readFully(b);return b;
    }

    private static void skipPayload(DataInputStream in,int type)throws IOException {
        switch(type) {
            case 1: in.readByte();break;
            case 2: in.readShort();break;
            case 3: in.readInt();break;
            case 4: in.readLong();break;
            case 5: in.readFloat();break;
            case 6: in.readDouble();break;
            case 7: {int n=in.readInt();skipFully(in,n);break;}
            case 8: readString(in);break;
            case 9: {
                int child=in.readUnsignedByte(),n=in.readInt();
                for(int i=0;i<n;i++) skipPayload(in,child);
                break;
            }
            case 10:
                while(true) {
                    int child=in.readUnsignedByte();
                    if(child==0) break;
                    readString(in);skipPayload(in,child);
                }
                break;
            case 11: {int n=in.readInt();skipFully(in,(long)n*4L);break;}
            case 12: {int n=in.readInt();skipFully(in,(long)n*8L);break;}
            default: throw new IOException("Unknown NBT type "+type);
        }
    }

    private static void skipFully(DataInputStream in,long n)throws IOException {
        while(n>0) {
            int step=(int)Math.min(8192,n);
            int skipped=in.skipBytes(step);
            if(skipped<=0) {in.readByte();skipped=1;}
            n-=skipped;
        }
    }

    private static String join(Collection<String> xs,String sep) {
        StringBuilder b=new StringBuilder();
        for(String x:xs){if(b.length()>0)b.append(sep);b.append(x);}
        return b.toString();
    }
}
