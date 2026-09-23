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
            int volume=s.volume(),done=0;
            while(cursor<volume && done<budget) {
                int i=cursor++;
                int id=s.blockId(i);
                if(id==0 && !pasteAir){processed++;continue;}
                int layer=s.width*s.length;
                int y=i/layer;
                int rem=i-y*layer;
                int z=rem/s.width;
                int x=rem-z*s.width;
                int wx=ax+x+s.offX, wy=ay+y+s.offY, wz=az+z+s.offZ;
                if(wy>0 && wy<world.getMaxHeight()) {
                    int cx=wx>>4,cz=wz>>4;
                    if(!world.isChunkLoaded(cx,cz)) {
                        if(done>0) { cursor--; break; }
                        world.loadChunk(cx,cz,true);
                    }
                    Block b=world.getBlockAt(wx,wy,wz);
                    b.setTypeIdAndData(id,s.blockData(i),false);
                    changed++;
                }
                processed++;done++;
            }
            logProgress();
            return cursor>=volume;
        }
        int progressPercent(){return Math.min(100,(int)((cursor*100L)/Math.max(1,s.volume())));}
        private void logProgress() {
            int p=progressPercent();
            if(p/10!=lastLogged/10) {
                lastLogged=p;
                plugin.getLogger().info("[composer] "+label+" "+p+"% blocks="+changed);
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
            int total=tileVolume*repeats,done=0;
            while(cursor<total && done<budget) {
                int global=cursor++;
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
                int wx=ax+sx+s.offX+shiftX*(repeat+1);
                int wy=ay+y+s.offY;
                int wz=az+sz+s.offZ+shiftZ*(repeat+1);
                if(wy>0 && wy<world.getMaxHeight()) {
                    int cx=wx>>4,cz=wz>>4;
                    if(!world.isChunkLoaded(cx,cz)) {
                        if(done>0) { cursor--; break; }
                        world.loadChunk(cx,cz,true);
                    }
                    world.getBlockAt(wx,wy,wz).setTypeIdAndData(id,s.blockData(source),false);
                    if(id!=0) changed++;
                }
                processed++;done++;
            }
            return cursor>=total;
        }
        int progressPercent() {
            int total=Math.max(1,tileVolume*repeats);
            return Math.min(100,(int)((cursor*100L)/total));
        }
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
            plugin.getConfig().getString("world-composer.assets.spawn","krakenhcf.schematic"),
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
        String name=asset("spawn","krakenhcf.schematic");
        if(!hasAsset(name)) {
            plugin.getLogger().warning("Cannot compose Kraken spawn; missing asset: "+name);
            return false;
        }
        try {
            World over=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
            if(over==null) return false;
            Schematic spawn=load(name);
            jobs.add(new PasteJob("Kraken Spawn",over,spawn,0,
                plugin.getConfig().getInt("world-composer.spawn-anchor-y",66),0,true));
            productionRun=false;
            ensureRunner();
            plugin.getLogger().info("[composer] queued spawn-only reset asset="+name+
                " volume="+spawn.volume());
            return true;
        } catch(Exception e) {
            jobs.clear();
            plugin.getLogger().severe("Could not queue Kraken spawn reset: "+e.getMessage());
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
            World nether=firstWorld(World.Environment.NETHER);
            World end=firstWorld(World.Environment.THE_END);
            if(over==null || nether==null || end==null) {
                plugin.getLogger().warning("Cannot compose HCF map until Overworld, Nether and End are loaded.");
                return false;
            }

            Schematic spawn=load(asset("spawn","krakenhcf.schematic"));
            jobs.add(new PasteJob("Kraken Spawn",over,spawn,0,
                plugin.getConfig().getInt("world-composer.spawn-anchor-y",66),0,true));

            int ko=plugin.getConfig().getInt("map-layout.koth-offset",500);
            int ky=plugin.getConfig().getInt("map.surface-y",63);
            jobs.add(new PasteJob("Classic KOTH",over,load(asset("koth-classic","KOTH2-production-1.8.schematic")), ko,ky,-ko,true));
            jobs.add(new PasteJob("EndStyle KOTH",over,load(asset("koth-endstyle","EndStyleKOTH-production-1.8.schematic")),-ko,ky,-ko,true));
            jobs.add(new PasteJob("Egypt KOTH",over,load(asset("koth-egypt","EgyptKOTH-production-1.8.schematic")), ko,ky,ko,true));
            jobs.add(new PasteJob("Frost KOTH",over,load(asset("koth-frost","KOTH-Forty-1.8-converted.schematic")),-ko,ky,ko,true));

            jobs.add(new PasteJob("Conquest",over,load(asset("conquest","conquest.schematic")),
                plugin.getConfig().getInt("map-layout.conquest-x",0),
                plugin.getConfig().getInt("world-composer.conquest-anchor-y",63),
                plugin.getConfig().getInt("map-layout.conquest-z",1125),true));

            jobs.add(new PasteJob("Nether Spawn",nether,load(asset("nether-spawn","NetherSpawnWillzaTeam.schematic")),
                0,plugin.getConfig().getInt("world-composer.nether-anchor-y",70),0,true));
            jobs.add(new PasteJob("Magic End",end,load(asset("end","magical-hcf-end-xayden-bt.schematic")),
                0,plugin.getConfig().getInt("world-composer.end-anchor-y",68),0,true));

            int border=plugin.getConfig().getInt("map.world-border",3000)/2;
            int roadEdge=126;
            int repeats=Math.max(0,(border-roadEdge)/25);
            jobs.add(new RoadJob("North Kraken road",over,spawn,0,
                plugin.getConfig().getInt("world-composer.spawn-anchor-y",66),0,
                114,138,0,24,0,-25,repeats));
            jobs.add(new RoadJob("South Kraken road",over,spawn,0,
                plugin.getConfig().getInt("world-composer.spawn-anchor-y",66),0,
                114,138,228,252,0,25,repeats));
            jobs.add(new RoadJob("West Kraken road",over,spawn,0,
                plugin.getConfig().getInt("world-composer.spawn-anchor-y",66),0,
                0,24,114,138,-25,0,repeats));
            jobs.add(new RoadJob("East Kraken road",over,spawn,0,
                plugin.getConfig().getInt("world-composer.spawn-anchor-y",66),0,
                228,252,114,138,25,0,repeats));

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
                int configured=Math.max(20,Math.min(500,plugin.getConfig().getInt("world-composer.blocks-per-tick",120)));
                int budget=configured;
                double p95=plugin.currentP95Mspt();
                if(p95>=45.0) budget=0;
                else if(p95>=32.0) budget=Math.min(budget,10);
                else if(p95>=26.0) budget=Math.min(budget,20);
                else if(p95>=22.0) budget=Math.min(budget,40);
                else if(p95>=18.0) budget=Math.min(budget,70);
                if(budget<=0) return;

                while(budget>0 && !jobs.isEmpty()) {
                    Job j=jobs.peekFirst();
                    long before=j.processed;
                    boolean done=j.step(budget);
                    int used=(int)Math.max(1,j.processed-before);
                    budget-=used;
                    if(done) {
                        jobs.removeFirst();
                        plugin.getLogger().info("[composer] completed "+j.label+" changed="+j.changed);
                    }
                }
                if(jobs.isEmpty()) {
                    if(productionRun) {
                        plugin.getConfig().set("map.structures-complete",true);
                        plugin.getConfig().set("map.production-layout-version",2);
                        plugin.saveConfig();
                        plugin.getLogger().info("[composer] production HCF structures complete; resource stage may begin.");
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
