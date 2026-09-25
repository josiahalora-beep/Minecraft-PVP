package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Materializes deterministic HCF base presets in small batches.
 * The strategic simulation decides when/where a base is allowed to exist.
 */
final class HcfBaseBuilder {
    static final class Op {
        final World world;
        final int x,y,z;
        final Material material;
        final byte data;
        final String label;
        long deferUntil;
        Op(World world, int x, int y, int z, Material material) {
            this(world,x,y,z,material,(byte)0,null);
        }
        Op(World world, int x, int y, int z, Material material, byte data) {
            this(world,x,y,z,material,data,null);
        }
        Op(World world, int x, int y, int z, Material material, byte data, String label) {
            this.world=world; this.x=x; this.y=y; this.z=z; this.material=material; this.data=data; this.label=label;
        }
    }

    private final EraCore plugin;
    private final ArrayDeque<Op> queue = new ArrayDeque<Op>();
    private final Set<String> completed = new HashSet<String>();
    private final Set<String> auditedPlans = new HashSet<String>();
    private BukkitRunnable runner;
    private boolean maintenanceRebuild;

    HcfBaseBuilder(EraCore plugin) {
        this.plugin = plugin;
    }

    void forceRebuild(String faction,String preset,String trapPreset,int cx,int y,int cz,
                      int storageTier,boolean brewer,boolean netherPortal,boolean endPortal) {
        if(faction==null || faction.trim().isEmpty()) return;
        String k=faction.toLowerCase(java.util.Locale.ENGLISH);

        // Explicit operator/migration rebuilds must not be suppressed by the
        // normal once-per-runtime dedupe set.
        completed.remove("base:"+k);
        completed.remove("surface:"+k);
        completed.remove("farm:"+k);
        completed.remove("brewer:"+k);
        completed.remove("storage:"+k+":1");
        completed.remove("storage:"+k+":2");
        completed.remove("storage:"+k+":3");
        completed.remove("portal:"+k+":nether");
        completed.remove("portal:"+k+":end");
        completed.remove("foundation:"+k);
        completed.remove("terrain:"+k);

        World world=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(world==null) return;

        HcfBasePlan plan=planFor(faction,cx,y,cz);
        maintenanceRebuild=true;
        clearBrokenBaseVolumes(world,plan);

        // Operator/QA rebuilds must reproduce the same blended terrain contract
        // as normal SOTW construction. The old foundation-only path could leave
        // a finished base sitting on a square shelf and made visual QA misleading.
        prepareTerrainPad(world,plan);
        auditPlan(plan);
        buildSurfaceShell(world,plan,true);
        buildUndergroundCore(world,plan);
        sealCriticalEnvelope(world,plan,true);

        int tier=Math.max(1,Math.min(3,storageTier));
        if(tier>1) buildStorageTier(world,plan,tier);
        if(brewer) {
            buildUndergroundBrewer(world,plan);
            plugin.registerAutoBrewerSite(faction,preset,cx,y,cz);
        }
        if(netherPortal) buildFactionPortal(world,plan,"nether");
        if(endPortal) buildFactionPortal(world,plan,"end");

        if ("fall_trap".equalsIgnoreCase(trapPreset)) buildFallTrap(world,cx,y,cz);
        else if ("fence_gate_bow".equalsIgnoreCase(trapPreset)) buildFenceGateBowTrap(world,cx,y,cz);
        else if ("drop_chute".equalsIgnoreCase(trapPreset)) buildDropChute(world,cx,y,cz);

        completed.add("base:"+k);
        completed.add("surface:"+k);
        completed.add("farm:"+k);
        completed.add("storage:"+k+":"+tier);
        if(brewer) completed.add("brewer:"+k);
        if(netherPortal) completed.add("portal:"+k+":nether");
        if(endPortal) completed.add("portal:"+k+":end");

        ensureRunner();
    }

    void queueQaFamilyShowcase() {
        if(!plugin.getConfig().getBoolean("base-builder.qa-showcase",false)) return;
        final World world=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(world==null) return;

        // Disposable visual-QA only. These names/coordinates are chosen so the
        // unmodified deterministic HcfBasePlan resolves one primary example of
        // every real production family. No alternate geometry path exists here:
        // forceRebuild() still runs the same planner/compiler used by factions.
        final String[] names={
            "QARedemption2","QABase0","QAModern14","QATunnel21","QACave55"
        };
        final int[][] sites={
            {-900,-900},{-450,-900},{450,-900},{900,-900},{-900,900}
        };
        final String[] expected={
            "REDEMPTION","BASE_HCF","MODERN_HCF","TUNNEL","CAVE"
        };

        // Preload a bounded neighborhood first. Newly generated chunks are
        // normalized by HcfTerrainDirector on the following tick; delaying the
        // actual build lets each showcase site read the real terrain relief.
        for(int[] site:sites) {
            int ccx=site[0]>>4, ccz=site[1]>>4;
            for(int dx=-4;dx<=4;dx++)
                for(int dz=-4;dz<=4;dz++)
                    world.loadChunk(ccx+dx,ccz+dz);
        }

        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run() {
                for(int i=0;i<sites.length;i++) {
                    int x=sites[i][0],z=sites[i][1];
                    int y=evaluateSite(x,z,30)[0];
                    HcfBasePlan p=planFor(names[i],x,y,z);
                    if(!expected[i].equals(p.primaryFamilyName())) {
                        plugin.getLogger().warning("[qa-showcase] family seed drift name="+names[i]+
                            " expected="+expected[i]+" actual="+p.primaryFamilyName());
                    }
                    forceRebuild(names[i],"hcf_glass_box","none",x,y,z,1,false,false,false);
                    plugin.getLogger().info("[qa-showcase] queued "+names[i]+
                        " family="+p.primaryFamilyName()+" secondary="+p.secondaryFamilyName()+
                        " at="+x+","+y+","+z);
                }

                // Spigot may unload remote showcase chunks because the inspector
                // begins at spawn. Keep only these disposable QA neighborhoods
                // hot until the real production build queue reaches zero; this
                // makes screenshots prove COMPLETE geometry rather than a partial
                // prefix of queued operations.
                new BukkitRunnable() {
                    public void run() {
                        if(queue.isEmpty()) {
                            plugin.getLogger().info("[qa-showcase] build queue drained; captures may begin.");
                            cancel();
                            return;
                        }
                        for(int[] site:sites) {
                            int ccx=site[0]>>4, ccz=site[1]>>4;
                            for(int dx=-4;dx<=4;dx++)
                                for(int dz=-4;dz<=4;dz++)
                                    world.loadChunk(ccx+dx,ccz+dz);
                        }
                    }
                }.runTaskTimer(plugin,1L,10L);
            }
        },20L);
    }

    void lazyMaterialize(String faction,String preset,String trapPreset,int cx,int y,int cz,
                         int storageTier,boolean brewer,boolean netherPortal,boolean endPortal) {
        if(faction==null || faction.trim().isEmpty()) return;
        World world=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(world==null) return;

        HcfBasePlan plan=planFor(faction,cx,y,cz);
        if(!footprintLoaded(faction,preset,cx,y,cz)) return;

        String k=faction.toLowerCase(java.util.Locale.ENGLISH);
        completed.remove("base:"+k);
        completed.remove("surface:"+k);
        maintenanceRebuild=true;

        // Lazy/cold materialization must compile the same visible terrain contract
        // as normal SOTW construction and /baserebuild. Reusing the old rectangular
        // flat apron made a base change appearance depending on how it became hot.
        // v10 keeps the work bounded to the deterministic family mask + cradle blend,
        // so Cave/Tunnel/Modern sites cannot regress into square lawns after recovery.
        clearBrokenBaseVolumes(world,plan);
        prepareTerrainPad(world,plan);
        auditPlan(plan);

        buildSurfaceShell(world,plan,true);
        buildUndergroundCore(world,plan);
        sealCriticalEnvelope(world,plan,true);

        int tier=Math.max(1,Math.min(3,storageTier));
        if(tier>1) buildStorageTier(world,plan,tier);
        if(brewer) {
            buildUndergroundBrewer(world,plan);
            plugin.registerAutoBrewerSite(faction,preset,cx,y,cz);
        }
        if(netherPortal) buildFactionPortal(world,plan,"nether");
        if(endPortal) buildFactionPortal(world,plan,"end");

        if ("fall_trap".equalsIgnoreCase(trapPreset)) buildFallTrap(world,cx,y,cz);
        else if ("fence_gate_bow".equalsIgnoreCase(trapPreset)) buildFenceGateBowTrap(world,cx,y,cz);
        else if ("drop_chute".equalsIgnoreCase(trapPreset)) buildDropChute(world,cx,y,cz);

        completed.add("base:"+k);
        completed.add("surface:"+k);
        completed.add("farm:"+k);
        completed.add("storage:"+k+":"+tier);
        if(brewer) completed.add("brewer:"+k);
        if(netherPortal) completed.add("portal:"+k+":nether");
        if(endPortal) completed.add("portal:"+k+":end");

        ensureRunner();
    }

    private void clearBrokenBaseVolumes(World w,HcfBasePlan p) {
        // v11: clear the old visible shell by distance to the REAL family mask,
        // never by a square terrainCradleRadius box. The old maintenance path
        // erased natural hills down to surfaceY across a rectangle before the
        // terrain blender ran; that was the source of the giant dirt cutouts in
        // headless QA. The canonical grade pass below repairs the small organic
        // work halo and also makes repeated rebuilds idempotent.
        int clearX=p.surfaceHalfX+7;
        int clearZ=p.surfaceHalfZ+7;
        int top=Math.min(w.getMaxHeight()-1,p.surfaceY+p.surfaceHeight+10);
        for(int x=p.cx-clearX;x<=p.cx+clearX;x++) for(int z=p.cz-clearZ;z<=p.cz+clearZ;z++) {
            double d=surfaceDistanceFromMaskExact(p,x,z,7);
            if(d>5.5) continue;
            for(int yy=p.surfaceY+1;yy<=top;yy++)
                queue.add(new Op(w,x,yy,z,Material.AIR));
        }

        // Underground: replace the entire generated work volume with stone
        // before carving the corrected connected core/farm/transit layout.
        // This removes old sealed islands and accidental cave/excavation seams.
        int hx=p.coreHalfX+14;
        int hz=p.coreHalfZ+14;
        int low=Math.max(3,p.undergroundY-9);
        int high=Math.min(w.getMaxHeight()-2,p.undergroundY+8);
        for(int x=p.cx-hx;x<=p.cx+hx;x++) for(int z=p.cz-hz;z<=p.cz+hz;z++) {
            for(int yy=low;yy<=high;yy++)
                queue.add(new Op(w,x,yy,z,Material.STONE));
        }

        // Re-open only the lined 5x5 transit column through the untouched
        // natural stone between the surface and underground work volume.
        int[] d=p.anchor("drop");
        for(int x=d[0]-2;x<=d[0]+2;x++) for(int z=d[2]-2;z<=d[2]+2;z++) {
            for(int yy=high+1;yy<=p.surfaceY;yy++)
                queue.add(new Op(w,x,yy,z,Material.STONE));
        }
    }

    void queueBase(String faction, String preset, String trapPreset, int cx, int y, int cz) {
        String key = "base:" + faction.toLowerCase();
        if (!completed.add(key)) return;
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        HcfBasePlan plan=planFor(faction,cx,y,cz);
        String surfaceKey="surface:"+faction.toLowerCase();
        boolean surfaceAlreadyQueued=completed.contains(surfaceKey);

        // If the rushed SOTW shell was already queued, never flatten/erase it
        // again. Its operations are already ahead of these in the same FIFO;
        // append the dropdown/core work and continue downward.
        if(!surfaceAlreadyQueued) {
            prepareTerrainPad(world,plan);
            auditPlan(plan);
            buildSurfaceShell(world,plan,true);
            completed.add(surfaceKey);
        }
        buildUndergroundCore(world,plan);
        sealCriticalEnvelope(world,plan,true);

        if ("fall_trap".equalsIgnoreCase(trapPreset)) buildFallTrap(world,cx,y,cz);
        else if ("fence_gate_bow".equalsIgnoreCase(trapPreset)) buildFenceGateBowTrap(world,cx,y,cz);
        else if ("drop_chute".equalsIgnoreCase(trapPreset)) buildDropChute(world,cx,y,cz);
        ensureRunner();
    }

    void queueSurfaceStarter(String faction,String preset,int cx,int y,int cz) {
        String key="surface:"+faction.toLowerCase();
        if(!completed.add(key)) return;
        World world=Bukkit.getWorlds().get(0);
        if(world==null) return;
        HcfBasePlan plan=planFor(faction,cx,y,cz);
        prepareTerrainPad(world,plan);
        auditPlan(plan);
        buildSurfaceShell(world,plan,false);
        sealSurfaceEnvelope(world,plan,false);
        ensureRunner();
    }

    void queueStorageUpgrade(String faction,String preset,int tier,int cx,int y,int cz) {
        int t=Math.max(1,Math.min(3,tier));
        String key="storage:"+faction.toLowerCase()+":"+t;
        if(!completed.add(key)) return;
        World world=Bukkit.getWorlds().get(0);
        if(world==null) return;
        buildStorageTier(world,planFor(faction,cx,y,cz),t);
        ensureRunner();
    }

    void queuePortal(String faction,String preset,String type,int cx,int y,int cz) {
        String t=type==null?"":type.toLowerCase();
        if(!"nether".equals(t) && !"end".equals(t)) return;
        String key="portal:"+faction.toLowerCase()+":"+t;
        if(!completed.add(key)) return;
        World world=Bukkit.getWorlds().get(0);
        if(world==null) return;
        buildFactionPortal(world,planFor(faction,cx,y,cz),t);
        ensureRunner();
    }

    void queueTrapAddon(String faction, String trapPreset, int cx, int y, int cz) {
        if (trapPreset == null || "none".equalsIgnoreCase(trapPreset)) return;
        String key = "trap-addon:" + faction.toLowerCase();
        if (!completed.add(key)) return;

        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        // Only prepare the trap's approach footprint. Do not reapply or alter
        // the existing live faction base.
        if ("fall_trap".equalsIgnoreCase(trapPreset)) buildFallTrap(world,cx,y,cz);
        else if ("fence_gate_bow".equalsIgnoreCase(trapPreset)) buildFenceGateBowTrap(world,cx,y,cz);
        else if ("drop_chute".equalsIgnoreCase(trapPreset)) buildDropChute(world,cx,y,cz);
        ensureRunner();
    }

    void queueFarm(String faction, String crop, int cx, int y, int cz) {
        String key = "farm:" + faction.toLowerCase();
        if (!completed.add(key)) return;
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        HcfBasePlan plan=planFor(faction,cx,y,cz);
        buildFarmLevel(world,plan,crop==null?"cane":crop.toLowerCase());
        ensureRunner();
    }

    void queueBrewer(String faction, String preset, int cx, int y, int cz) {
        String key = "brewer:" + faction.toLowerCase();
        if (!completed.add(key)) return;
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        HcfBasePlan plan=planFor(faction,cx,y,cz);
        buildUndergroundBrewer(world,plan);
        plugin.registerAutoBrewerSite(faction,preset,cx,y,cz);
        ensureRunner();
    }

    int[] evaluateSite(int cx,int cz,int radius) {
        World world=Bukkit.getWorlds().get(0);
        if(world==null) return new int[]{64,999,999};

        java.util.List<Integer> ys=new java.util.ArrayList<Integer>();
        int min=Integer.MAX_VALUE, max=Integer.MIN_VALUE, liquid=0;
        int step=4;

        for(int x=cx-radius;x<=cx+radius;x+=step) {
            for(int z=cz-radius;z<=cz+radius;z+=step) {
                int sy=solidSurfaceY(world,x,z);
                ys.add(sy);
                min=Math.min(min,sy);
                max=Math.max(max,sy);

                int top=Math.max(1,world.getHighestBlockYAt(x,z));
                Material topMat=world.getBlockAt(x,top,z).getType();
                if(topMat==Material.WATER||topMat==Material.STATIONARY_WATER||
                   topMat==Material.LAVA||topMat==Material.STATIONARY_LAVA) liquid++;
            }
        }

        java.util.Collections.sort(ys);
        int median=ys.isEmpty()?64:ys.get(ys.size()/2);
        int relief=(min==Integer.MAX_VALUE||max==Integer.MIN_VALUE)?999:(max-min);
        return new int[]{median,relief,liquid};
    }

    void queueFoundationRepair(String faction, String preset, String trapPreset, int cx, int y, int cz) {
        String key = "foundation:" + faction.toLowerCase();
        if (!completed.add(key)) return;
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        HcfBasePlan plan=planFor(faction,cx,y,cz);
        fillFoundationOnly(world,plan);
        buildSurfaceShell(world,plan,false);
        buildUndergroundCore(world,plan);
        sealCriticalEnvelope(world,plan,true);
        rescueEmbeddedPlayers(world,cx,y,cz,plan.surfacePadRadius());
        ensureRunner();
    }

    private void fillFoundationOnly(World w,HcfBasePlan p) {
        // Support only the actual shell plus a one-block construction lip.
        // The authored FreeMap outside that tiny envelope is never filled into
        // a platform/terrace just because a faction base exists nearby.
        int rx=p.surfaceHalfX+1,rz=p.surfaceHalfZ+1;
        for(int x=p.cx-rx;x<=p.cx+rx;x++) {
            for(int z=p.cz-rz;z<=p.cz+rz;z++) {
                if(!surfaceInside(p,x,z) && surfaceDistanceFromMaskExact(p,x,z,2)>1.25) continue;
                int surface=solidSurfaceY(w,x,z);
                if(surface>=p.surfaceY-1) continue;

                Material nativeTop=nativeSurfaceMaterial(w,x,z);
                Material fill=nativeFillMaterial(nativeTop);
                int from=Math.max(2,surface+1);
                for(int yy=from;yy<p.surfaceY;yy++)
                    queue.add(new Op(w,x,yy,z,yy>=p.surfaceY-2?fill:Material.STONE));
            }
        }
    }

    void queueTerrainRepair(String faction, String preset, String trapPreset, int cx, int y, int cz) {
        String key = "terrain:" + faction.toLowerCase();
        if (!completed.add(key)) return;
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        HcfBasePlan plan=planFor(faction,cx,y,cz);
        prepareTerrainPad(world,plan);
        auditPlan(plan);
        ensureRunner();
    }

    private int basePadRadius(String preset, String trapPreset) {
        int r=16;
        if ("hcf_courtyard".equalsIgnoreCase(preset)) r=18;
        else if ("hcf_double_layer".equalsIgnoreCase(preset)) r=17;
        else if ("hcf_archer_tower".equalsIgnoreCase(preset)) r=16;
        else if ("hcf_split_level".equalsIgnoreCase(preset)) r=15;
        else if ("hcf_glass_box".equalsIgnoreCase(preset) || "hcf_brewer_base".equalsIgnoreCase(preset) || "hcf_trap_base".equalsIgnoreCase(preset)) r=16;
        if (!"none".equalsIgnoreCase(trapPreset)) r=Math.max(r,22);
        // Every template owns a connected rear storage wing. Terrain preparation
        // must include it or the vault can float off the back of a steep site.
        r=Math.max(r,presetHalf(preset)+8);
        return r;
    }

    /**
     * Build Viewer base-site terraforming.
     *
     * The actual surface shell receives a flat work apron, but the remainder of
     * the claim-sized construction envelope blends back into the pre-existing
     * low-relief terrain. This avoids giant square faction plateaus while still
     * guaranteeing a safe, hole-free PvP frontage and supported underground
     * infrastructure.
     */
    private void prepareTerrainPad(World w,HcfBasePlan p) {
        // Phase 2B: structure first, authored terrain second.
        //
        // Do NOT generate a terrain feature around the base. Real HCF builders
        // mostly cleared the footprint and, depending on skill/intent, flattened
        // a very small PvP apron. The checksum-pinned FreeMap remains authoritative
        // immediately outside that work zone.
        int apron=p.profile.builderQuality>=75?4:(p.profile.builderQuality>=45?3:2);
        int minX=p.cx-p.surfaceHalfX-apron,maxX=p.cx+p.surfaceHalfX+apron;
        int minZ=p.cz-p.surfaceHalfZ-apron,maxZ=p.cz+p.surfaceHalfZ+apron;
        int gateX=p.cx+p.frontGateOffset;
        int frontZ=surfaceFrontZ(p,gateX);

        for(int x=minX;x<=maxX;x++) {
            for(int z=minZ;z<=maxZ;z++) {
                boolean inside=surfaceInside(p,x,z);
                double dist=inside?0.0:surfaceDistanceFromMaskExact(p,x,z,apron+1);
                boolean frontApron=z<=frontZ && z>=frontZ-4 && Math.abs(x-gateX)<=4;
                if(!inside && !frontApron && dist>apron+0.25) continue;

                int natural=plugin.canonicalHcfTerrainY(x,z);
                int current=solidSurfaceY(w,x,z);
                int target;

                if(inside) {
                    target=p.surfaceY;
                } else {
                    // Human-like PvP grading: only columns already within one
                    // vertical block of the base grade are flattened. Larger
                    // authored hills/dips remain natural instead of becoming
                    // concentric terraces.
                    int delta=natural-p.surfaceY;
                    boolean skilledApron=p.profile.builderQuality>=45 && (frontApron || dist<=apron);
                    if(!skilledApron || Math.abs(delta)>1) continue;
                    target=p.surfaceY;
                }

                Material nativeTop=nativeSurfaceMaterial(w,x,z);
                Material fill=nativeFillMaterial(nativeTop);

                // Clear only the build/apron column. Nothing outside this tiny
                // work zone has vegetation, trees, cliffs or biome materials touched.
                int clearTop=Math.min(w.getMaxHeight()-1,
                    Math.max(target+(inside?p.surfaceHeight+8:4),w.getHighestBlockYAt(x,z)+2));
                for(int yy=target+1;yy<=clearTop;yy++) {
                    Material existing=w.getBlockAt(x,yy,z).getType();
                    if(existing!=Material.AIR)
                        queue.add(new Op(w,x,yy,z,Material.AIR));
                }

                if(current<target) {
                    for(int yy=Math.max(2,current+1);yy<target;yy++)
                        queue.add(new Op(w,x,yy,z,yy>=target-2?fill:Material.STONE));
                }

                // The shell floor replaces inside cells later. Apron cells keep
                // their own native surface material, which naturally respects
                // desert/grass/gravel boundaries without synthetic 70/20/10 noise.
                if(!inside)
                    queue.add(new Op(w,x,target,z,nativeTop));
            }
        }
    }

    private Material nativeSurfaceMaterial(World w,int x,int z) {
        int y=solidSurfaceY(w,x,z);
        Material m=w.getBlockAt(x,y,z).getType();
        if(m==Material.GRASS || m==Material.DIRT || m==Material.SAND ||
           m==Material.GRAVEL || m==Material.STONE || m==Material.COBBLESTONE ||
           m==Material.MOSSY_COBBLESTONE || m==Material.SANDSTONE)
            return m;
        Material[] fallback=sampleLocalPaletteAt(w,x,z);
        return fallback[0];
    }

    private Material nativeFillMaterial(Material top) {
        if(top==Material.SAND || top==Material.SANDSTONE) return Material.SAND;
        if(top==Material.STONE || top==Material.COBBLESTONE || top==Material.MOSSY_COBBLESTONE)
            return Material.STONE;
        if(top==Material.GRAVEL) return Material.DIRT;
        return Material.DIRT;
    }

    private Material[] sampleLocalPaletteAt(World w,int cx,int cz) {
        Map<Material,Integer> count=new LinkedHashMap<Material,Integer>();
        for(int x=cx-5;x<=cx+5;x+=2) for(int z=cz-5;z<=cz+5;z+=2) {
            int y=solidSurfaceY(w,x,z);
            Material m=w.getBlockAt(x,y,z).getType();
            if(m==Material.GRASS || m==Material.DIRT || m==Material.SAND ||
               m==Material.GRAVEL || m==Material.STONE || m==Material.COBBLESTONE ||
               m==Material.MOSSY_COBBLESTONE || m==Material.SANDSTONE) {
                Integer n=count.get(m); count.put(m,n==null?1:n+1);
            }
        }
        Material dominant=Material.GRASS; int best=-1;
        for(Map.Entry<Material,Integer> e:count.entrySet())
            if(e.getValue()>best){best=e.getValue();dominant=e.getKey();}
        if(dominant==Material.SAND || dominant==Material.SANDSTONE)
            return new Material[]{dominant,Material.SAND,Material.SANDSTONE};
        if(dominant==Material.STONE || dominant==Material.COBBLESTONE || dominant==Material.MOSSY_COBBLESTONE)
            return new Material[]{dominant,Material.STONE,Material.GRAVEL};
        if(dominant==Material.GRAVEL)
            return new Material[]{Material.GRAVEL,Material.DIRT,Material.STONE};
        return new Material[]{Material.GRASS,Material.DIRT,Material.STONE};
    }

    private int gradedSurfaceY(HcfBasePlan p,int x,int z,int blendReach) {
        double d=warpedMaskDistance(p,x,z,blendReach+2);
        int natural=plugin.canonicalHcfTerrainY(x,z);
        int delta=Math.max(-6,Math.min(6,natural-p.surfaceY));

        // v13: only the shell and a tiny irregular work apron are graded.
        // Wider distance-based interpolation inevitably becomes a visible
        // contour/moat around a Minecraft build once Y is quantized to blocks.
        // Outside roughly two blocks, the untouched canonical wilderness owns
        // the height completely; concealment is added later as broken lobes.
        double bend=(cradleBroadNoise(x,z,p.seed+1601)-0.5)*1.25+
            (cradleNoise(x,z,p.seed+1609)-0.5)*0.40;
        double edge=d+bend;

        if(edge<=1.20) return p.surfaceY;
        if(edge<=2.15) {
            // One-block transition only, sufficient to avoid a hard foundation
            // lip without drawing a faction-sized terrace.
            int kept=Math.max(-1,Math.min(1,delta));
            return p.surfaceY+kept;
        }
        return natural;
    }

    private double warpedMaskDistance(HcfBasePlan p,int x,int z,int limit) {
        double d=surfaceDistanceFromMaskExact(p,x,z,limit);
        if(d<=0.0) return 0.0;

        // Low-frequency deterministic bending only changes contour position; it
        // does not create random one-block height noise.
        double bend=(cradleNoise(x,z,p.seed+601)-0.5)*1.55+
            (cradleNoise(x+23,z-17,p.seed+907)-0.5)*0.75;
        return Math.max(0.0,d+bend);
    }

    /**
     * Terraform first, build second.
     *
     * Every column becomes solid through target Y and clear for 16 blocks above
     * grade. This prevents floating floors, terrain clipping through walls and
     * trees/leaves being trapped inside bases.
     */
    private void prepareTerrainPad(World w,int cx,int y,int cz,int rx,int rz) {
        for(int x=cx-rx;x<=cx+rx;x++) {
            for(int z=cz-rz;z<=cz+rz;z++) {
                int surface=solidSurfaceY(w,x,z);
                int clearTop=Math.min(w.getMaxHeight()-1,
                    Math.max(y+24,w.getHighestBlockYAt(x,z)+8));

                // Clear the complete column above grade. The previous +16 cap
                // cut the bottom out of tall hills and literally created
                // floating mountain fragments over bases.
                for(int yy=y+1;yy<=clearTop;yy++)
                    queue.add(new Op(w,x,yy,z,Material.AIR));

                // Fill every gap up to grade. Use stone deeper down and dirt near top.
                int from=Math.max(2,surface+1);
                if(surface<y) {
                    for(int yy=from;yy<y;yy++) {
                        Material fill=(yy>=y-3)?Material.DIRT:Material.STONE;
                        queue.add(new Op(w,x,yy,z,fill));
                    }
                }

                // Natural flat grade outside the actual structure footprint.
                queue.add(new Op(w,x,y,z,Material.GRASS));
            }
        }
    }

    private int solidSurfaceY(World w,int x,int z) {
        int start=Math.min(w.getMaxHeight()-1,Math.max(1,w.getHighestBlockYAt(x,z)+6));
        for(int y=start;y>=1;y--) {
            Material m=w.getBlockAt(x,y,z).getType();
            if(m==Material.AIR || isVegetationOrLiquid(m)) continue;
            return y;
        }
        return 1;
    }

    private boolean isVegetationOrLiquid(Material m) {
        return m==Material.LEAVES || m==Material.LEAVES_2 || m==Material.LOG || m==Material.LOG_2 ||
               m==Material.LONG_GRASS || m==Material.YELLOW_FLOWER || m==Material.RED_ROSE ||
               m==Material.VINE || m==Material.SNOW || m==Material.SNOW_BLOCK ||
               m==Material.WATER || m==Material.STATIONARY_WATER ||
               m==Material.LAVA || m==Material.STATIONARY_LAVA;
    }

    boolean footprintLoaded(String faction,String preset,int cx,int y,int cz) {
        World w=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(w==null) return false;
        HcfBasePlan p=planFor(faction,cx,y,cz);
        int r=p.surfacePadRadius();
        int[][] pts={{p.cx,p.cz},{p.cx-r,p.cz-r},{p.cx-r,p.cz+r},{p.cx+r,p.cz-r},{p.cx+r,p.cz+r}};
        for(int[] pt:pts) if(!w.isChunkLoaded(pt[0]>>4,pt[1]>>4)) return false;
        return true;
    }

    boolean looksMaterialized(String faction,String preset,int cx,int y,int cz) {
        World w=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(w==null) return false;
        HcfBasePlan p=planFor(faction,cx,y,cz);
        if(!footprintLoaded(faction,preset,cx,y,cz)) return false;

        int frontX=p.cx+p.frontGateOffset;
        int frontZ=surfaceFrontZ(p,frontX);
        int rearZ=surfaceRearZ(p,p.cx);
        int sideX=surfaceSideX(p,p.cz,p.utilitySide);
        int centerRoof=surfaceRoofY(p,p.cx,p.cz)+1;
        int[][] pts={
            {frontX,p.surfaceY+2,frontZ},
            {p.cx,centerRoof,p.cz},
            {p.cx,p.surfaceY+2,rearZ},
            {sideX,p.surfaceY+2,p.cz}
        };
        int present=0;
        for(int[] pt:pts) {
            Material m=w.getBlockAt(pt[0],pt[1],pt[2]).getType();
            if(m!=Material.AIR && !isVegetationOrLiquid(m)) present++;
        }
        return present>=2;
    }

    int queuedOperations() {
        return queue.size();
    }

    boolean maintenanceRebuildActive() {
        return maintenanceRebuild;
    }

    void stop() {
        if (runner != null) runner.cancel();
        runner = null;
        queue.clear();
        auditedPlans.clear();
        maintenanceRebuild=false;
    }

    private void ensureRunner() {
        if (runner != null) return;
        runner = new BukkitRunnable() {
            public void run() {
                int configured = Math.max(8, plugin.getConfig().getInt("base-builder.blocks-per-tick", 80));
                int visible=Math.max(4,plugin.getConfig().getInt("base-builder.visible-blocks-per-tick",12));
                int rebuild=Math.max(8,plugin.getConfig().getInt("base-builder.rebuild-blocks-per-tick",32));

                int normal=plugin.hasHumanOnline()?Math.min(configured,visible):configured;
                boolean qaFast=maintenanceRebuild &&
                    plugin.getConfig().getBoolean("base-builder.qa-fast-rebuild",false);
                int budget;
                if(qaFast) {
                    // Explicit disposable-QA mode only. This lets the headless
                    // visual harness materialize full deterministic bases in
                    // minutes; the production default is false and retains all
                    // combat-safe throttles below.
                    budget=Math.max(40,Math.min(3000,
                        plugin.getConfig().getInt("base-builder.qa-fast-rebuild-blocks-per-run",1800)));
                } else {
                    budget=maintenanceRebuild?Math.min(rebuild,plugin.hasHumanOnline()?24:40):normal;

                    // Construction is cosmetic/physical projection, never allowed to
                    // compete with combat or normal movement. Pause or taper instantly
                    // as p95 tick time rises.
                    double p95=plugin.currentP95Mspt();
                    if(p95>=45.0) budget=0;
                    else if(p95>=32.0) budget=Math.min(budget,2);
                    else if(p95>=26.0) budget=Math.min(budget,4);
                    else if(p95>=22.0) budget=Math.min(budget,8);
                    else if(p95>=18.0) budget=Math.min(budget,12);
                }

                if(budget<=0) return;
                int n = 0;
                while (n < budget && !queue.isEmpty()) {
                    Op op = queue.poll();
                    // Never materialize a solid repair/build block through a live
                    // player's feet or head. Skipping one cosmetic/support block is
                    // preferable to suffocating or trapping a player in a wall.
                    long now=System.currentTimeMillis();
                    if(op.deferUntil>now) {
                        queue.add(op);
                        n++;
                        continue;
                    }

                    // Never force-load/generate an offscreen chunk just to finish
                    // cosmetic projection. The operation waits until a real/HOT
                    // player naturally has this base area loaded.
                    if(!op.world.isChunkLoaded(op.x>>4,op.z>>4)) {
                        op.deferUntil=now+5000L;
                        queue.add(op);
                        n++;
                        continue;
                    }

                    if (op.material != Material.AIR && intersectsPlayer(op)) {
                        // Never turn a temporary player collision into a permanent
                        // hole. Millenaire-style builders wait until the work site
                        // is clear, then finish the same blueprint operation.
                        op.deferUntil=now+1250L;
                        queue.add(op);
                        n++;
                        continue;
                    }
                    Block b = op.world.getBlockAt(op.x,op.y,op.z);
                    // 1.8 tile entities can survive a rapid CHEST -> FURNACE
                    // style replacement long enough for Bukkit to detect a
                    // mismatched TileEntity. Clear changed block types first,
                    // then place the deterministic result without physics.
                    if(b.getType()!=op.material) {
                        b.setTypeIdAndData(Material.AIR.getId(),(byte)0,false);
                        b.setTypeIdAndData(op.material.getId(),op.data,false);
                    } else if(b.getData()!=op.data) {
                        b.setData(op.data,false);
                    }
                    if (op.label != null && b.getState() instanceof Sign) {
                        Sign sign=(Sign)b.getState();
                        String[] lines=op.label.split("\\|",-1);
                        for(int li=0;li<Math.min(4,lines.length);li++) {
                            String line=lines[li]==null?"":lines[li];
                            sign.setLine(li,line.length()>15?line.substring(0,15):line);
                        }
                        sign.update(true);
                    }
                    n++;
                }
                if (queue.isEmpty()) {
                    if(maintenanceRebuild) {
                        maintenanceRebuild=false;
                        plugin.getLogger().info("Base Intelligence: forced base rematerialization queue completed.");
                    }
                    cancel();
                    runner = null;
                }
            }
        };
        long interval=Math.max(1L,plugin.getConfig().getLong("base-builder.interval-ticks",2L));
        runner.runTaskTimer(plugin,1L,interval);
    }

    private int presetHalf(String preset) {
        if ("hcf_courtyard".equalsIgnoreCase(preset)) return 14;
        if ("hcf_compact_2015".equalsIgnoreCase(preset)) return 9;
        if ("hcf_split_level".equalsIgnoreCase(preset)) return 11;
        if ("hcf_archer_tower".equalsIgnoreCase(preset)) return 10;
        if ("hcf_double_layer".equalsIgnoreCase(preset)) return 13;
        return 12;
    }

    int[] anchor(String faction,String preset,String kind,int cx,int y,int cz) {
        return planFor(faction,cx,y,cz).anchor(kind);
    }

    int[] anchor(String preset,String kind,int cx,int y,int cz) {
        // Compatibility for older call sites; new simulation code always supplies
        // the faction so personality/size influence remains stable.
        return HcfBasePlan.of("",cx,y,cz,new HcfBasePlan.Profile()).anchor(kind);
    }

    int[] storageAnchor(String faction,String preset,String category,int cx,int y,int cz) {
        HcfBasePlan plan=planFor(faction,cx,y,cz);
        return plan.storageSlot(storageCategoryIndex(category));
    }

    private void buildOrganizedVault(World w,String faction,String preset,int cx,int y,int cz) {
        int half=presetHalf(preset);
        int rear=cz+half;
        int minX=cx-11,maxX=cx+11;
        int minZ=rear,maxZ=rear+8;
        Material accent=blueprintStyle(faction,preset).frame;

        // The vault is part of the base template, not a freestanding shed. Its
        // front wall overlaps the rear wall of the main preset and shares one
        // canonical synchronized gate group.
        for(int x=minX;x<=maxX;x++) {
            for(int z=minZ;z<=maxZ;z++) {
                int surface=solidSurfaceY(w,x,z);
                if(surface<y) {
                    for(int yy=Math.max(2,surface+1);yy<y;yy++)
                        queue.add(new Op(w,x,yy,z,yy>=y-3?Material.DIRT:Material.STONE));
                }
                queue.add(new Op(w,x,y,z,accent));
                for(int yy=y+1;yy<=y+5;yy++) {
                    boolean wall=x==minX||x==maxX||z==minZ||z==maxZ;
                    queue.add(new Op(w,x,yy,z,wall?accent:Material.AIR));
                }
                queue.add(new Op(w,x,y+6,z,accent));
            }
        }

        // Full-height logical doorway. HcfGateDirector synchronizes all nine
        // fence gates, so the opening is never a three-wide hole above one gate.
        doorway(w,cx,y,rear);

        String[] near={"Pots","Pearls","Valuables","Blocks","Brewing","Farm","Overflow"};
        String[] far={"Helmets","Chestplates","Leggings","Boots","Swords","Bows","Kits"};
        int[] starts={-10,-7,-4,-1,2,5,8};
        int nearZ=rear+3, farZ=rear+6;

        // Fourteen labeled DOUBLE chests. Every pair has one block of lateral
        // separation so Minecraft cannot merge three chests into an invalid row.
        for(int i=0;i<starts.length;i++) {
            int x=cx+starts[i];
            doubleChest(w,x,y+1,nearZ,near[i]);
            doubleChest(w,x,y+1,farZ,far[i]);
        }

        // Lighting is kept off the chest rows and aisle.
        queue.add(new Op(w,cx-10,y+4,rear+1,Material.GLOWSTONE));
        queue.add(new Op(w,cx+10,y+4,rear+1,Material.GLOWSTONE));
        queue.add(new Op(w,cx-10,y+4,rear+7,Material.GLOWSTONE));
        queue.add(new Op(w,cx+10,y+4,rear+7,Material.GLOWSTONE));
    }

    private void doubleChest(World w,int x,int chestY,int z,String label) {
        queue.add(new Op(w,x,chestY,z,Material.CHEST));
        queue.add(new Op(w,x+1,chestY,z,Material.CHEST));
        queue.add(new Op(w,x,chestY+1,z,Material.SIGN_POST,(byte)8,label));
        // Explicit clearance prevents a later template/repair operation from
        // leaving a solid block over either half and making the double unusable.
        queue.add(new Op(w,x,chestY+2,z,Material.AIR));
        queue.add(new Op(w,x+1,chestY+1,z,Material.AIR));
        queue.add(new Op(w,x+1,chestY+2,z,Material.AIR));
    }


    private static final class BlueprintStyle {
        final Material frame,wall,floor,roof;
        final int halfX,height,roofStyle,side;
        BlueprintStyle(Material frame,Material wall,Material floor,Material roof,
                       int halfX,int height,int roofStyle,int side) {
            this.frame=frame;this.wall=wall;this.floor=floor;this.roof=roof;
            this.halfX=halfX;this.height=height;this.roofStyle=roofStyle;this.side=side;
        }
    }

    private int positiveHash(String text) {
        int h=text==null?0:text.toLowerCase(java.util.Locale.ENGLISH).hashCode();
        return h==Integer.MIN_VALUE?0:Math.abs(h);
    }

    private BlueprintStyle blueprintStyle(String faction,String preset) {
        int h=positiveHash((faction==null?"":faction)+"|"+(preset==null?"":preset));
        Material[] frames={Material.SMOOTH_BRICK,Material.COBBLESTONE,Material.NETHER_BRICK,
            Material.BRICK,Material.SANDSTONE,Material.QUARTZ_BLOCK};
        Material[] walls={Material.WOOD,Material.COBBLESTONE,Material.SMOOTH_BRICK,
            Material.NETHER_BRICK,Material.BRICK,Material.SANDSTONE};
        Material frame=frames[h%frames.length];
        Material wall=walls[(h/7)%walls.length];
        if(wall==frame) wall=walls[((h/7)+2)%walls.length];
        Material floor=((h/13)&1)==0?frame:Material.WOOD;
        Material roof=((h/17)&1)==0?frame:wall;
        int halfX=8+(h%6);                 // 17..27 blocks wide
        int height=6+((h/31)%6);           // 6..11 blocks tall
        int roofStyle=(h/67)%3;
        int side=((h/97)&1)==0?-1:1;

        if("hcf_compact_2015".equalsIgnoreCase(preset)){halfX=Math.min(10,halfX);height=Math.min(8,height);}
        if("hcf_archer_tower".equalsIgnoreCase(preset)){halfX=Math.max(10,halfX);height=Math.max(9,height);}
        if("hcf_double_layer".equalsIgnoreCase(preset)){halfX=Math.max(12,halfX);height=Math.max(9,height);}
        if("hcf_courtyard".equalsIgnoreCase(preset)){halfX=Math.max(12,halfX);}
        return new BlueprintStyle(frame,wall,floor,roof,halfX,height,roofStyle,side);
    }

    /**
     * Deterministic per-faction blueprint.
     *
     * The old builder had eight names for mostly the same smooth-brick/glass box.
     * This keeps stable semantic anchors for AI navigation while varying width,
     * height, palette, roof, wings, safe room, frontage and defensive silhouette.
     * The queue order is deliberately construction-like: floor -> frame/walls ->
     * roof -> rooms -> details. HOT builders can visibly work around a structure
     * that grows over time instead of watching one prefab appear at once.
     */
    private void buildFactionBlueprint(World w,String faction,String preset,int cx,int y,int cz) {
        BlueprintStyle s=blueprintStyle(faction,preset);
        int hx=s.halfX;
        int hz=presetHalf(preset);
        int top=y+s.height;

        // Foundation/floor and complete interior clearance.
        for(int x=cx-hx;x<=cx+hx;x++) {
            for(int z=cz-hz;z<=cz+hz;z++) {
                queue.add(new Op(w,x,y,z,s.floor));
                for(int yy=y+1;yy<=top+4;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
            }
        }

        // Exterior shell. Windows are discrete openings, not full glass walls.
        for(int x=cx-hx;x<=cx+hx;x++) {
            for(int z=cz-hz;z<=cz+hz;z++) {
                boolean edge=x==cx-hx||x==cx+hx||z==cz-hz||z==cz+hz;
                if(!edge) continue;
                boolean corner=(x==cx-hx||x==cx+hx)&&(z==cz-hz||z==cz+hz);
                for(int yy=y+1;yy<=top;yy++) {
                    boolean beam=corner || yy==y+1 || yy==top || ((x+z+yy)%7==0);
                    boolean window=!beam && yy>=y+3 && yy<=top-2 && ((Math.abs(x-cx)+Math.abs(z-cz))%4==0);
                    queue.add(new Op(w,x,yy,z,beam?s.frame:(window?Material.STAINED_GLASS:s.wall)));
                }
            }
        }

        // Different roof silhouettes.
        if(s.roofStyle==0) {
            for(int x=cx-hx;x<=cx+hx;x++) for(int z=cz-hz;z<=cz+hz;z++)
                queue.add(new Op(w,x,top+1,z,s.roof));
            for(int x=cx-hx;x<=cx+hx;x+=2) {
                queue.add(new Op(w,x,top+2,cz-hz,s.frame));
                queue.add(new Op(w,x,top+2,cz+hz,s.frame));
            }
        } else if(s.roofStyle==1) {
            // Open parapet roof with central skylight/courtyard feel.
            for(int x=cx-hx;x<=cx+hx;x++) {
                queue.add(new Op(w,x,top+1,cz-hz,s.roof));
                queue.add(new Op(w,x,top+1,cz+hz,s.roof));
            }
            for(int z=cz-hz;z<=cz+hz;z++) {
                queue.add(new Op(w,cx-hx,top+1,z,s.roof));
                queue.add(new Op(w,cx+hx,top+1,z,s.roof));
            }
            for(int x=cx-3;x<=cx+3;x++) for(int z=cz-3;z<=cz+3;z++)
                queue.add(new Op(w,x,top+1,z,Material.GLASS));
        } else {
            // Stepped ridge roof.
            for(int step=0;step<=Math.min(4,hz-2);step++) {
                int z0=cz-hz+step,z1=cz+hz-step;
                int yy=top+1+step;
                for(int x=cx-hx;x<=cx+hx;x++) {
                    queue.add(new Op(w,x,yy,z0,s.roof));
                    queue.add(new Op(w,x,yy,z1,s.roof));
                }
            }
            for(int x=cx-hx;x<=cx+hx;x++)
                queue.add(new Op(w,x,top+5,cz,s.frame));
        }

        // Stable front gate for worker navigation.
        doorway(w,cx,y,cz-hz);

        // Asymmetric side utility wing. Side differs by faction, so neighboring
        // bases do not read as rotated clones.
        int wingCenterX=cx+s.side*(hx+4);
        int wingHalfX=3,wingHalfZ=5;
        for(int x=wingCenterX-wingHalfX;x<=wingCenterX+wingHalfX;x++) {
            for(int z=cz-wingHalfZ;z<=cz+wingHalfZ;z++) {
                queue.add(new Op(w,x,y,z,s.frame));
                for(int yy=y+1;yy<=y+5;yy++) {
                    boolean wall=x==wingCenterX-wingHalfX||x==wingCenterX+wingHalfX||
                                 z==cz-wingHalfZ||z==cz+wingHalfZ||yy==y+5;
                    queue.add(new Op(w,x,yy,z,wall?s.wall:Material.AIR));
                }
            }
        }
        doorwayX(w,wingCenterX-s.side*wingHalfX,y,cz);

        // Offset panic/refill room rather than the same centered cube every time.
        int roomX=cx-s.side*Math.max(2,hx/3);
        int roomZ=cz+Math.max(1,hz/4);
        int rx=4,rz=4;
        for(int x=roomX-rx;x<=roomX+rx;x++) for(int z=roomZ-rz;z<=roomZ+rz;z++) {
            for(int yy=y+1;yy<=y+5;yy++) {
                boolean wall=x==roomX-rx||x==roomX+rx||z==roomZ-rz||z==roomZ+rz||yy==y+5;
                if(wall) queue.add(new Op(w,x,yy,z,s.frame));
            }
        }

        // Preset-specific silhouettes remain meaningful, but are layered over the
        // faction blueprint instead of defining the entire building.
        if("hcf_archer_tower".equalsIgnoreCase(preset)) {
            tower(w,cx-hx+3,y+1,cz-hz+3,3,10);
            tower(w,cx+hx-3,y+1,cz-hz+3,3,12);
        } else if("hcf_double_layer".equalsIgnoreCase(preset)) {
            int inner=Math.max(5,Math.min(hx,hz)-4);
            for(int x=cx-inner;x<=cx+inner;x++) for(int z=cz-inner;z<=cz+inner;z++) {
                boolean edge=x==cx-inner||x==cx+inner||z==cz-inner||z==cz+inner;
                if(!edge) continue;
                for(int yy=y+1;yy<=y+6;yy++) queue.add(new Op(w,x,yy,z,s.frame));
            }
            doorway(w,cx,y,cz-inner);
        } else if("hcf_courtyard".equalsIgnoreCase(preset)) {
            for(int sx:new int[]{-1,1}) for(int sz:new int[]{-1,1}) {
                int px=cx+sx*(hx-2),pz=cz+sz*(hz-2);
                for(int yy=y+1;yy<=top+3;yy++) queue.add(new Op(w,px,yy,pz,s.frame));
                queue.add(new Op(w,px,top+4,pz,Material.GLOWSTONE));
            }
        } else if("hcf_trap_base".equalsIgnoreCase(preset)) {
            for(int x=cx-hx+2;x<=cx+hx-2;x+=2)
                queue.add(new Op(w,x,y+1,cz-hz-2,Material.OBSIDIAN));
        } else if("hcf_brewer_base".equalsIgnoreCase(preset)) {
            int chimneyX=cx-s.side*(hx-2);
            for(int yy=y+1;yy<=top+6;yy++)
                queue.add(new Op(w,chimneyX,yy,cz+hz-2,(yy%3==0)?Material.IRON_FENCE:s.frame));
        } else if("hcf_split_level".equalsIgnoreCase(preset)) {
            for(int x=cx;x<=cx+hx-1;x++) for(int z=cz-hz+2;z<=cz+hz-2;z++)
                queue.add(new Op(w,x,y+4,z,s.frame));
        }

        // Interior utility landmarks.
        queue.add(new Op(w,cx+s.side*(hx-2),y+1,cz+hz-3,Material.ENCHANTMENT_TABLE));
        queue.add(new Op(w,cx+s.side*(hx-3),y+1,cz+hz-3,Material.ANVIL));
        queue.add(new Op(w,cx,y+Math.max(4,s.height-1),cz,Material.GLOWSTONE));
    }


    private void auditPlan(HcfBasePlan p) {
        if(p==null) return;
        String k=p.faction.toLowerCase(java.util.Locale.ENGLISH)+"@"+p.cx+","+p.cz;
        if(!auditedPlans.add(k)) return;

        int pad=p.surfacePadRadius();
        int[][] anchors={
            p.anchor("drop"),p.anchor("elevator"),p.anchor("storage"),
            p.anchor("brewer"),p.anchor("farm"),p.anchor("portal-nether"),
            p.anchor("portal-end"),p.anchor("enchant"),p.anchor("war-room")
        };
        boolean inside=true;
        for(int[] a:anchors) {
            if(Math.abs(a[0]-p.cx)>pad || Math.abs(a[2]-p.cz)>pad) {
                inside=false;break;
            }
        }

        int[] d=p.anchor("drop"),e=p.anchor("elevator");
        int transitSep=Math.abs(d[0]-e[0])+Math.abs(d[2]-e[2]);
        if(!inside || transitSep<5) {
            plugin.getLogger().warning("[base-plan] INVALID faction="+p.faction+
                " family="+p.primaryFamilyName()+" pad="+pad+
                " anchorsInside="+inside+" transitSep="+transitSep);
        } else {
            plugin.getLogger().info("[base-plan] faction="+p.faction+
                " family="+p.primaryFamilyName()+"+"+p.secondaryFamilyName()+
                " surface="+(p.surfaceHalfX*2+1)+"x"+(p.surfaceHalfZ*2+1)+
                " core="+(p.coreHalfX*2+1)+"x"+(p.coreHalfZ*2+1)+
                " depth="+(p.surfaceY-p.undergroundY)+
                " entrances="+p.entrances+" storage="+p.storageVariant+
                " claimWorkRadius="+pad);
        }
    }

    private HcfBasePlan planFor(String faction,int cx,int y,int cz) {
        HcfBasePlan.Profile profile=plugin.simBaseProfile(faction);
        return HcfBasePlan.of(faction,cx,y,cz,profile);
    }

    private void buildSurfaceShell(World w,HcfBasePlan p,boolean openTransit) {
        int minX=p.cx-p.surfaceHalfX,maxX=p.cx+p.surfaceHalfX;
        int minZ=p.cz-p.surfaceHalfZ,maxZ=p.cz+p.surfaceHalfZ;
        int maxTop=surfaceMaxTop(p);

        // v9: every family owns its footprint AND roof profile. The structural
        // shell is still practical HCF construction, but it no longer extrudes
        // one identical-height box over five different masks.
        for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
            if(!surfaceInside(p,x,z)) continue;
            int colTop=surfaceRoofY(p,x,z);
            queue.add(new Op(w,x,p.surfaceY,z,p.surfaceFloor));

            boolean edge=surfaceBoundary(p,x,z);
            for(int yy=p.surfaceY+1;yy<=colTop;yy++) {
                if(!edge) {
                    queue.add(new Op(w,x,yy,z,Material.AIR));
                    continue;
                }
                boolean cornerish=surfaceCornerLike(p,x,z);
                boolean beam=cornerish || yy==p.surfaceY+1 || yy==colTop;
                Material wall=surfaceWallMaterial(p,x,yy,z,colTop,beam);
                queue.add(new Op(w,x,yy,z,wall,surfaceWallData(p,wall)));
            }
            queue.add(new Op(w,x,colTop+1,z,surfaceRoofMaterial(p,x,z,edge)));
        }

        // Gates resolve against the real family boundary. Curved, stepped and
        // tunnel masks no longer receive doors on empty bounding-box air.
        int frontX=p.cx+p.frontGateOffset;
        int frontZ=surfaceFrontZ(p,frontX);
        bufferedGateZ(w,frontX,p.surfaceY,frontZ,+1,frontGateHalfWidth(p),p.surfaceFrame);
        if(p.entrances>=2) {
            int sideX=surfaceSideX(p,p.cz,p.utilitySide);
            bufferedGateX(w,sideX,p.surfaceY,p.cz,p.utilitySide>0?-1:+1,p.surfaceFrame);
        }
        if(p.entrances>=3) {
            int backX=p.cx-p.frontGateOffset;
            int rearZ=surfaceRearZ(p,backX);
            bufferedGateZ(w,backX,p.surfaceY,rearZ,-1,1,p.surfaceFrame);
        }

        buildExteriorGateBanks(w,p);

        int[] d=p.anchor("drop");
        for(int x=d[0]-2;x<=d[0]+2;x++) for(int z=d[2]-2;z<=d[2]+2;z++)
            queue.add(new Op(w,x,p.surfaceY,z,
                (Math.abs(x-d[0])==2||Math.abs(z-d[2])==2)?p.surfaceFrame:p.surfaceFloor));

        buildTerrainCradle(w,p,maxTop);
        decorateSurfaceGrammar(w,p,maxTop);
        if(openTransit) buildVerticalTransit(w,p);
    }

    private void sealSurfaceEnvelope(World w,HcfBasePlan p,boolean dropdownOpen) {
        int[] d=p.anchor("drop");

        // Re-assert the family-shaped shell using the same per-column roof
        // profile as initial construction. Only intentional gates/transit reopen.
        for(int x=p.cx-p.surfaceHalfX;x<=p.cx+p.surfaceHalfX;x++) {
            for(int z=p.cz-p.surfaceHalfZ;z<=p.cz+p.surfaceHalfZ;z++) {
                if(!surfaceInside(p,x,z)) continue;
                int colTop=surfaceRoofY(p,x,z);
                boolean dropCell=dropdownOpen && Math.abs(x-d[0])<=1 && Math.abs(z-d[2])<=1;
                queue.add(new Op(w,x,p.surfaceY,z,dropCell?Material.AIR:p.surfaceFloor));

                boolean edge=surfaceBoundary(p,x,z);
                if(edge) {
                    for(int yy=p.surfaceY+1;yy<=colTop;yy++) {
                        if(isBayJoinOpening(p,x,yy,z)) {
                            queue.add(new Op(w,x,yy,z,Material.AIR));
                            continue;
                        }
                        int gateData=surfaceGateData(p,x,yy,z);
                        if(gateData>=0) {
                            queue.add(new Op(w,x,yy,z,Material.FENCE_GATE,(byte)gateData));
                            continue;
                        }
                        boolean beam=surfaceCornerLike(p,x,z) || yy==p.surfaceY+1 || yy==colTop ||
                            ((Math.abs(x-p.cx)+Math.abs(z-p.cz)+p.seed)%7==0);
                        Material wall=surfaceWallMaterial(p,x,yy,z,colTop,beam);
                        queue.add(new Op(w,x,yy,z,wall,surfaceWallData(p,wall)));
                    }
                }

                queue.add(new Op(w,x,colTop+1,z,surfaceRoofMaterial(p,x,z,edge)));
            }
        }
    }

    private void sealCriticalEnvelope(World w,HcfBasePlan p,boolean dropdownOpen) {
        sealSurfaceEnvelope(w,p,dropdownOpen);

        // Re-assert the underground central box envelope. Internal modules are
        // left untouched; this only prevents cave/excavation seams at the shell.
        int minX=p.cx-p.coreHalfX,maxX=p.cx+p.coreHalfX;
        int minZ=p.cz-p.coreHalfZ,maxZ=p.cz+p.coreHalfZ;
        int ceiling=p.undergroundY+6;
        for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
            boolean boundary=x==minX||x==maxX||z==minZ||z==maxZ;
            if(boundary) {
                Material wall=p.finishTier==0?Material.STONE:Material.SMOOTH_BRICK;
                for(int yy=p.undergroundY+1;yy<ceiling;yy++)
                    queue.add(new Op(w,x,yy,z,wall));
            }
            queue.add(new Op(w,x,ceiling,z,p.finishTier==0?Material.STONE:Material.SMOOTH_BRICK));
        }

        if(dropdownOpen) {
            // The integrity pass runs last; re-open/re-line only the one legal
            // vertical connection so envelope sealing cannot accidentally close it.
            buildVerticalTransit(w,p);
        }
    }

    private boolean nearBay(int v,int width,int... centers) {
        for(int c:centers) if(Math.abs(v-c)<=width) return true;
        return false;
    }

    private boolean surfaceWindowCell(HcfBasePlan p,int x,int yy,int z,int top) {
        int level=yy-p.surfaceY;
        int maxLevel=top-p.surfaceY;
        if(level<3 || level>=maxLevel) return false;

        boolean front=z==surfaceFrontZ(p,x);
        boolean rear=z==surfaceRearZ(p,x);
        boolean sideNeg=x==surfaceSideX(p,z,-1);
        boolean sidePos=x==surfaceSideX(p,z,+1);
        if(!front && !rear && !sideNeg && !sidePos) return false;

        int along=(front||rear)?x-p.cx:z-p.cz;
        boolean frontOrRear=front||rear;

        switch(p.primaryFamily) {
            case 0: // Redemption: four strong two-wide bays across both stories.
                if(frontOrRear) {
                    if(level>=4 && level<=6 && nearBay(along,1,-6,-2,2,6)) return true;
                    if(level>=9 && level<=10 && nearBay(along,1,-5,0,5)) return true;
                }
                return (sideNeg||sidePos) && level>=4 && level<=7 &&
                    nearBay(along,1,-5,0,5);
            case 1: // Base-HCF: stained-glass columns are a defining visual feature.
                if(frontOrRear && level>=4 && level<=8)
                    return nearBay(along,1,-9,-3,3,9);
                if((sideNeg||sidePos) && level>=4 && level<=8)
                    return nearBay(along,1,-7,-2,3,8);
                return level>=10 && level<=11 && nearBay(along,1,-6,0,6);
            case 2: // Modern: large coherent glass panels, not speckles.
                if(frontOrRear && level>=3 && level<=7)
                    return nearBay(along,2,-4,4);
                return (sideNeg||sidePos) && level>=4 && level<=8 &&
                    nearBay(along,1,-3,3);
            case 3: // Tunnel: stacked pairs of windows on a visible tower.
                if(frontOrRear) {
                    if(level>=4 && level<=6 && nearBay(along,1,-3,3)) return true;
                    if(level>=9 && level<=11 && nearBay(along,1,-3,3)) return true;
                }
                return (sideNeg||sidePos) && level>=5 && level<=10 &&
                    nearBay(along,0,-3,0,3);
            case 4: // Cave/Devhorah: fewer but still deliberate glass openings.
                if(front && level>=4 && level<=6)
                    return nearBay(along,1,-4,4);
                return (rear||sideNeg||sidePos) && level>=5 && level<=7 &&
                    nearBay(along,0,-4,0,4);
            default:
                return false;
        }
    }

    private boolean surfaceTrimCell(HcfBasePlan p,int x,int yy,int z,int top) {
        int level=yy-p.surfaceY;
        boolean front=z==surfaceFrontZ(p,x);
        boolean rear=z==surfaceRearZ(p,x);
        boolean side=x==surfaceSideX(p,z,-1)||x==surfaceSideX(p,z,+1);
        int along=(front||rear)?x-p.cx:z-p.cz;
        if(!(front||rear||side)) return false;

        if(p.primaryFamily==0)
            return level==6 || nearBay(along,0,-7,7);
        if(p.primaryFamily==1)
            return level==3 || level==8 || nearBay(along,0,-11,-5,5,11);
        if(p.primaryFamily==2)
            return level==3 || nearBay(along,0,-7,7);
        if(p.primaryFamily==3)
            return level==3 || level==7 || nearBay(along,0,-4,4);
        return nearBay(along,0,-5,5) && level<=7;
    }

    private Material surfaceWallMaterial(HcfBasePlan p,int x,int yy,int z,int top,boolean beam) {
        int level=yy-p.surfaceY;
        int pattern=Math.abs(x*31+z*17+p.seed)%11;

        if(!beam && surfaceWindowCell(p,x,yy,z,top)) {
            // Stained glass was a defining 1.7/1.8 HCF facade material.
            // Color is supplied by surfaceWallData().
            return Material.STAINED_GLASS;
        }

        if(beam) {
            switch(p.primaryFamily) {
                case 0: return level<=2?Material.SMOOTH_BRICK:Material.LOG; // Redemption timber frame
                case 1: return Material.SMOOTH_BRICK;                       // Base-HCF masonry frame
                case 2: return Material.QUARTZ_BLOCK;                       // Modern clean frame
                case 3: return level<=2?Material.SMOOTH_BRICK:Material.LOG; // Tunnel stacked timber/stone
                case 4: return pattern<4?Material.COBBLESTONE:Material.LOG; // Devhorah wood/stone
                default:return p.surfaceFrame;
            }
        }

        if(surfaceTrimCell(p,x,yy,z,top)) {
            switch(p.primaryFamily) {
                case 0: return Material.LOG;
                case 1: return Material.LOG;
                case 2: return Material.QUARTZ_BLOCK;
                case 3: return Material.LOG;
                case 4: return pattern<5?Material.COBBLESTONE:Material.LOG;
                default:return p.surfaceFrame;
            }
        }

        switch(p.primaryFamily) {
            case 0:
                // Redemption reference is wood-dominant with a stone lower course.
                if(level<=2) return Material.SMOOTH_BRICK;
                return Material.WOOD;
            case 1:
                return p.finishTier>=1?Material.SMOOTH_BRICK:Material.COBBLESTONE;
            case 2:
                // Modern uses glass as a major surface; solid cells stay clean/light.
                return p.finishTier>=1?Material.SMOOTH_BRICK:Material.QUARTZ_BLOCK;
            case 3:
                // Tunnel reference is a visible timber tower in a gray frame.
                if(level<=2) return Material.SMOOTH_BRICK;
                return Material.WOOD;
            case 4:
                // Devhorah/Cave reference is visibly wooden with stone supports,
                // not a random mossy-rock capsule.
                if(level<=2) return Material.COBBLESTONE;
                return pattern==0?Material.COBBLESTONE:Material.WOOD;
            default:
                return p.surfaceFrame;
        }
    }

    private byte surfaceWallData(HcfBasePlan p,Material material) {
        if(material!=Material.STAINED_GLASS) return (byte)0;
        switch(p.primaryFamily) {
            case 0: return (byte)9;  // cyan/light-blue feel against timber
            case 1: return (byte)15; // dark/black HCF panes in gray masonry
            case 2: return (byte)9;  // cyan glass-heavy modern tower
            case 3: return (byte)9;  // cyan stacked tunnel windows
            case 4: return (byte)7;  // gray glass in the cave/wood facade
            default:return (byte)0;
        }
    }

    private Material surfaceRoofMaterial(HcfBasePlan p,int x,int z,boolean edge) {
        int dx=x-p.cx,dz=z-p.cz;
        if(!edge) {
            if(p.primaryFamily==2 && Math.abs(dx)<=2 && Math.abs(dz)<=2)
                return Material.STAINED_GLASS;
            if(p.primaryFamily==1 && Math.abs(dx)<=1 && Math.abs(dz)<=3)
                return Material.STAINED_GLASS;
            if(p.primaryFamily==0 && Math.abs(dx)<=1 && dz>=1 && dz<=4)
                return Material.STAINED_GLASS;
            if(p.primaryFamily==3 && Math.abs(dx)<=1 && Math.abs(dz)<=1)
                return Material.STAINED_GLASS;
        }
        if(p.primaryFamily==0 || p.primaryFamily==3 || p.primaryFamily==4)
            return Material.WOOD;
        if(p.primaryFamily==2) return Material.QUARTZ_BLOCK;
        return Material.SMOOTH_BRICK;
    }

    private int frontGateHalfWidth(HcfBasePlan p) {
        return p.primaryFamily==4?1:2;
    }

    private int surfaceGateData(HcfBasePlan p,int x,int yy,int z) {
        int level=yy-p.surfaceY;

        boolean front=z==surfaceFrontZ(p,x);
        boolean rear=z==surfaceRearZ(p,x);
        boolean sideNeg=x==surfaceSideX(p,z,-1);
        boolean sidePos=x==surfaceSideX(p,z,+1);
        int along=(front||rear)?x-p.cx:z-p.cz;

        // Primary functional entrances.
        if(level>=1 && level<=2) {
            int frontX=p.cx+p.frontGateOffset;
            int frontHalf=frontGateHalfWidth(p);
            if(Math.abs(x-frontX)<=frontHalf && front) return 0;

            if(p.entrances>=2 && Math.abs(z-p.cz)<=1) {
                int sideX=surfaceSideX(p,z,p.utilitySide);
                if(x==sideX) return 1;
            }

            if(p.entrances>=3) {
                int backX=p.cx-p.frontGateOffset;
                if(Math.abs(x-backX)<=1 && rear) return 0;
            }
        }

        // Reference-derived HCF facade language: fence-gate ventilation /
        // fighting bands framed into the walls. These are deliberate bays, not
        // random holes, and they survive the final envelope seal.
        if(front||rear) {
            if(p.primaryFamily==0 && (level==3 || level==4) && nearBay(along,1,-6,-2,2,6)) return 0;
            if(p.primaryFamily==1 && (level==2 || level==3) && nearBay(along,1,-9,-3,3,9)) return 0;
            if(p.primaryFamily==2 && level==2 && nearBay(along,1,-5,5)) return 0;
            if(p.primaryFamily==3 && ((level>=2 && level<=3) || level==7) &&
               nearBay(along,0,-3,0,3)) return 0;
            if(p.primaryFamily==4 && (level==2 || level==3) && nearBay(along,0,-4,4)) return 0;
        }
        if(sideNeg||sidePos) {
            if(p.primaryFamily==0 && (level==3 || level==4) && nearBay(along,1,-5,0,5)) return 1;
            if(p.primaryFamily==1 && (level==2 || level==3) && nearBay(along,1,-7,0,7)) return 1;
            if(p.primaryFamily==2 && level==2 && nearBay(along,0,-4,4)) return 1;
            if(p.primaryFamily==3 && (level==2 || level==3) && nearBay(along,0,-3,3)) return 1;
            if(p.primaryFamily==4 && (level==2 || level==3) && along==p.utilitySide*3) return 1;
        }
        return -1;
    }

    private boolean isBayJoinOpening(HcfBasePlan p,int x,int yy,int z) {
        return false;
    }

    private boolean surfaceInside(HcfBasePlan p,int x,int z) {
        int dx=x-p.cx,dz=z-p.cz;
        int ax=Math.abs(dx),az=Math.abs(dz);
        if(ax>p.surfaceHalfX || az>p.surfaceHalfZ) return false;

        // Phase 2B reference geometry: the uploaded HCF bases are dominated by
        // legible rectangular/tower masses. Avoid decorative ellipse/chamfer
        // algorithms that made the generated shell read like a pod or terrain prop.
        switch(p.primaryFamily) {
            case 0: { // Redemption: strong square main house, tiny rear utility bite.
                if(ax<=p.surfaceHalfX && az<=p.surfaceHalfZ) {
                    if(dz>=p.surfaceHalfZ-2 && dx*p.utilitySide<-p.surfaceHalfX+2) return false;
                    return true;
                }
                return false;
            }
            case 1: // Base-HCF: broad practical rectangle.
                return true;
            case 2: { // ModernHCF: clean square main mass + shallow offset rear wing.
                boolean main=ax<=p.surfaceHalfX-1 && az<=p.surfaceHalfZ-1;
                boolean wing=dz>=1 && dx*p.utilitySide>=p.surfaceHalfX-3 &&
                    ax<=p.surfaceHalfX && az<=p.surfaceHalfZ-2;
                return main||wing;
            }
            case 3: // Tunnel reference: compact vertical/tower footprint.
                return ax<=p.surfaceHalfX-1 && az<=p.surfaceHalfZ-1;
            case 4: { // Cave/Devhorah: visible rectangular core with one natural-looking notch.
                if(ax>p.surfaceHalfX-1 || az>p.surfaceHalfZ-1) return false;
                if(dz>p.surfaceHalfZ-4 && dx*p.utilitySide>p.surfaceHalfX-4) return false;
                return true;
            }
            default:
                return true;
        }
    }

    private boolean surfaceBoundary(HcfBasePlan p,int x,int z) {
        if(!surfaceInside(p,x,z)) return false;
        return !surfaceInside(p,x+1,z)||!surfaceInside(p,x-1,z)||
               !surfaceInside(p,x,z+1)||!surfaceInside(p,x,z-1);
    }

    private boolean surfaceCornerLike(HcfBasePlan p,int x,int z) {
        int missing=0;
        if(!surfaceInside(p,x+1,z)) missing++;
        if(!surfaceInside(p,x-1,z)) missing++;
        if(!surfaceInside(p,x,z+1)) missing++;
        if(!surfaceInside(p,x,z-1)) missing++;
        return missing>=2;
    }

    private int surfaceFrontZ(HcfBasePlan p,int x) {
        for(int z=p.cz-p.surfaceHalfZ;z<=p.cz+p.surfaceHalfZ;z++)
            if(surfaceInside(p,x,z)) return z;
        return p.cz-p.surfaceHalfZ;
    }

    private int surfaceRearZ(HcfBasePlan p,int x) {
        for(int z=p.cz+p.surfaceHalfZ;z>=p.cz-p.surfaceHalfZ;z--)
            if(surfaceInside(p,x,z)) return z;
        return p.cz+p.surfaceHalfZ;
    }

    private int surfaceSideX(HcfBasePlan p,int z,int side) {
        if(side>=0) {
            for(int x=p.cx+p.surfaceHalfX;x>=p.cx-p.surfaceHalfX;x--)
                if(surfaceInside(p,x,z)) return x;
            return p.cx+p.surfaceHalfX;
        }
        for(int x=p.cx-p.surfaceHalfX;x<=p.cx+p.surfaceHalfX;x++)
            if(surfaceInside(p,x,z)) return x;
        return p.cx-p.surfaceHalfX;
    }

    private int surfaceRoofY(HcfBasePlan p,int x,int z) {
        int dx=x-p.cx,dz=z-p.cz;
        int base=p.surfaceY+p.surfaceHeight;
        int roof=base;
        switch(p.primaryFamily) {
            case 0: { // Redemption: broad timber house with a shallow stepped crown.
                int ridge=Math.max(0,2-(Math.abs(dx)/4));
                roof+=ridge;
                if(dz>=1 && Math.abs(dx)<=Math.max(3,p.surfaceHalfX/2)) roof++;
                break;
            }
            case 1: { // Base-HCF: broad shallow gable + raised central roof mass.
                int ridge=Math.max(0,3-(Math.abs(dx)/4));
                roof+=ridge;
                if(Math.abs(dx)<=Math.max(3,p.surfaceHalfX/3) &&
                   Math.abs(dz)<=Math.max(3,p.surfaceHalfZ/2)) roof++;
                break;
            }
            case 2: // ModernHCF: clean offset upper slab.
                if(dx*p.utilitySide>0 && dz>-(p.surfaceHalfZ/3)) roof+=1;
                break;
            case 3: { // Tunnel reference: pronounced stacked tower/gable.
                int ridge=Math.max(0,4-(Math.abs(dx)/2));
                roof+=ridge;
                if(Math.abs(dx)<=1 && dz>=0) roof++;
                break;
            }
            case 4: { // Devhorah/Cave: wood gable with asymmetric stone-side rise.
                int ridge=Math.max(0,3-(Math.abs(dx)/2));
                roof+=ridge;
                if(dx*p.utilitySide>1 && dz>=0) roof++;
                break;
            }
            default:
                break;
        }
        return Math.max(p.surfaceY+7,roof);
    }

    private int surfaceMaxTop(HcfBasePlan p) {
        int max=p.surfaceY+3;
        for(int x=p.cx-p.surfaceHalfX;x<=p.cx+p.surfaceHalfX;x++)
            for(int z=p.cz-p.surfaceHalfZ;z<=p.cz+p.surfaceHalfZ;z++)
                if(surfaceInside(p,x,z)) max=Math.max(max,surfaceRoofY(p,x,z));
        return max;
    }

    private double surfaceDistanceFromMaskExact(HcfBasePlan p,int x,int z,int limit) {
        if(surfaceInside(p,x,z)) return 0.0;
        int max=Math.max(1,limit);
        double best=Double.MAX_VALUE;

        int minX=Math.max(p.cx-p.surfaceHalfX,x-max);
        int maxX=Math.min(p.cx+p.surfaceHalfX,x+max);
        int minZ=Math.max(p.cz-p.surfaceHalfZ,z-max);
        int maxZ=Math.min(p.cz+p.surfaceHalfZ,z+max);
        for(int sx=minX;sx<=maxX;sx++) {
            for(int sz=minZ;sz<=maxZ;sz++) {
                if(!surfaceInside(p,sx,sz)) continue;
                double dx=x-sx,dz=z-sz;
                double d2=dx*dx+dz*dz;
                if(d2<best) best=d2;
            }
        }
        return best==Double.MAX_VALUE?max+1.0:Math.sqrt(best);
    }

    private int surfaceDistanceFromMask(HcfBasePlan p,int x,int z,int limit) {
        return (int)Math.ceil(surfaceDistanceFromMaskExact(p,x,z,limit));
    }

    private void buildSurfaceBay(World w,HcfBasePlan p,int bx,int bz,int side,int top) {
        for(int x=bx-3;x<=bx+3;x++) for(int z=bz-4;z<=bz+4;z++) {
            queue.add(new Op(w,x,p.surfaceY,z,p.surfaceFloor));
            for(int yy=p.surfaceY+1;yy<=top;yy++) {
                boolean edge=x==bx-3||x==bx+3||z==bz-4||z==bz+4;
                queue.add(new Op(w,x,yy,z,edge?(((yy-p.surfaceY)%3==0)?p.surfaceFrame:Material.GLASS):Material.AIR));
            }
            queue.add(new Op(w,x,top+1,z,p.surfaceFrame));
        }
        int joinX=p.cx+side*p.surfaceHalfX;
        for(int x=Math.min(joinX,bx);x<=Math.max(joinX,bx);x++)
            for(int z=bz-1;z<=bz+1;z++)
                for(int yy=p.surfaceY+1;yy<=p.surfaceY+3;yy++)
                    queue.add(new Op(w,x,yy,z,Material.AIR));
    }

    private void buildExteriorGateBanks(World w,HcfBasePlan p) {
        // Peak-era HCF facades frequently used fence-gate banks as defensive
        // shutters, trap-ready openings and visual rhythm. Keep them deliberate:
        // aligned with window bays and never random single blocks.
        int frontZ=surfaceFrontZ(p,p.cx);
        int rearZ=surfaceRearZ(p,p.cx);
        int levelA=p.surfaceY+3;
        int levelB=p.surfaceY+4;

        int[] centers;
        if(p.primaryFamily==1) centers=new int[]{-9,-3,3,9};
        else if(p.primaryFamily==0) centers=new int[]{-6,-2,2,6};
        else if(p.primaryFamily==3) centers=new int[]{-3,3};
        else if(p.primaryFamily==2) centers=new int[]{-4,4};
        else centers=new int[]{-4,4};

        for(int off:centers) {
            int x=p.cx+off;
            if(Math.abs(x-p.cx)>p.surfaceHalfX-1) continue;

            // Do not overwrite the true primary doorway.
            if(Math.abs(x-(p.cx+p.frontGateOffset))>frontGateHalfWidth(p)+1) {
                queue.add(new Op(w,x,levelA,surfaceFrontZ(p,x),Material.FENCE_GATE,(byte)0));
                queue.add(new Op(w,x,levelB,surfaceFrontZ(p,x),Material.FENCE_GATE,(byte)0));
            }
            int rz=surfaceRearZ(p,x);
            queue.add(new Op(w,x,levelA,rz,Material.FENCE_GATE,(byte)0));
            queue.add(new Op(w,x,levelB,rz,Material.FENCE_GATE,(byte)0));
        }

        // Side shutter columns make the building read like an HCF base from
        // more than one angle and match the supplied gate-heavy references.
        int[] zOff={-4,0,4};
        for(int off:zOff) {
            int z=p.cz+off;
            if(Math.abs(z-p.cz)>p.surfaceHalfZ-1) continue;
            int lx=surfaceSideX(p,z,-1),rx=surfaceSideX(p,z,+1);
            for(int yy=levelA;yy<=levelB;yy++) {
                queue.add(new Op(w,lx,yy,z,Material.FENCE_GATE,(byte)1));
                queue.add(new Op(w,rx,yy,z,Material.FENCE_GATE,(byte)1));
            }
        }
    }

    private void bufferedGateZ(World w,int cx,int y,int wallZ,int inward,int halfWidth,Material frame) {
        int inner=wallZ+inward*3;
        int lo=Math.min(wallZ-1,inner-1),hi=Math.max(wallZ+1,inner+1);
        for(int z=lo;z<=hi;z++) for(int x=cx-halfWidth;x<=cx+halfWidth;x++)
            for(int yy=y+1;yy<=y+2;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));

        for(int z:new int[]{wallZ,inner}) for(int x=cx-halfWidth;x<=cx+halfWidth;x++) {
            for(int yy=y+1;yy<=y+2;yy++) queue.add(new Op(w,x,yy,z,Material.FENCE_GATE,(byte)0));
            queue.add(new Op(w,x,y+3,z,frame));
        }
        // Deliberate entrance frame posts like the supplied HCF references.
        for(int z:new int[]{wallZ,inner}) {
            queue.add(new Op(w,cx-halfWidth-1,y+1,z,frame));
            queue.add(new Op(w,cx-halfWidth-1,y+2,z,frame));
            queue.add(new Op(w,cx+halfWidth+1,y+1,z,frame));
            queue.add(new Op(w,cx+halfWidth+1,y+2,z,frame));
        }
    }

    private void bufferedGateX(World w,int wallX,int y,int cz,int inward,Material frame) {
        int inner=wallX+inward*3;
        int lo=Math.min(wallX-1,inner-1),hi=Math.max(wallX+1,inner+1);
        for(int x=lo;x<=hi;x++) for(int z=cz-1;z<=cz+1;z++)
            for(int yy=y+1;yy<=y+2;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));

        for(int x:new int[]{wallX,inner}) for(int z=cz-1;z<=cz+1;z++) {
            for(int yy=y+1;yy<=y+2;yy++) queue.add(new Op(w,x,yy,z,Material.FENCE_GATE,(byte)1));
            queue.add(new Op(w,x,y+3,z,frame));
        }
    }

    private void buildUndergroundCore(World w,HcfBasePlan p) {
        int minX=p.cx-p.coreHalfX,maxX=p.cx+p.coreHalfX;
        int minZ=p.cz-p.coreHalfZ,maxZ=p.cz+p.coreHalfZ;
        int floor=p.undergroundY,ceiling=floor+6;

        for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
            queue.add(new Op(w,x,floor,z,p.undergroundFloor));
            for(int yy=floor+1;yy<ceiling;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));

            boolean boundary=x==minX||x==maxX||z==minZ||z==maxZ;
            if(boundary) {
                Material wall=p.finishTier==0?Material.STONE:
                    (((x+z+p.seed)%7==0)?p.undergroundTrim:Material.SMOOTH_BRICK);
                for(int yy=floor+1;yy<ceiling;yy++) queue.add(new Op(w,x,yy,z,wall));
            }

            Material roof=p.finishTier==0?Material.STONE:
                ((((x-p.cx)%7==0)||((z-p.cz)%7==0))?p.undergroundTrim:Material.SMOOTH_BRICK);
            queue.add(new Op(w,x,ceiling,z,roof));
        }

        // Deliberately imperfect finishing for rushed/average builders.
        if(p.finishTier==1) {
            for(int x=minX+2;x<=minX+7;x++)
                queue.add(new Op(w,x,floor+1,minZ,Material.COBBLESTONE));
        }

        buildVerticalTransit(w,p);
        buildStorageTier(w,p,1);
        buildFarmLevel(w,p,"cane");
        buildReferenceGrammar(w,p);
        buildCoreUtilityModules(w,p);

        int[] refill=p.anchor("refill");
        queue.add(new Op(w,refill[0],refill[1],refill[2],Material.ENDER_CHEST));
        queue.add(new Op(w,refill[0]+1,refill[1],refill[2],Material.ANVIL));
        queue.add(new Op(w,refill[0]-1,refill[1],refill[2],Material.WORKBENCH));
    }

    private void buildCoreUtilityModules(World w,HcfBasePlan p) {
        buildEnchantCorner(w,p);
        buildWarRoom(w,p);
        buildUtilityCorner(w,p);
    }

    private void buildEnchantCorner(World w,HcfBasePlan p) {
        int[] a=p.anchor("enchant");
        int y=p.undergroundY;
        int cx=a[0],cz=a[2];

        // Compact period-correct enchanting nook. Keep two approach cells open
        // so the module never blocks circulation around the core.
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
            if(Math.abs(dx)<=1 && dz==2) continue;
            boolean edge=Math.abs(dx)==2||Math.abs(dz)==2;
            if(edge) queue.add(new Op(w,cx+dx,y+1,cz+dz,Material.BOOKSHELF));
        }
        queue.add(new Op(w,cx,y+1,cz,Material.ENCHANTMENT_TABLE));
        queue.add(new Op(w,cx+1,y+1,cz,Material.ANVIL));
        queue.add(new Op(w,cx,y+5,cz,Material.GLOWSTONE));
    }

    private void buildWarRoom(World w,HcfBasePlan p) {
        int[] a=p.anchor("war-room");
        int y=p.undergroundY;
        int cx=a[0],cz=a[2];

        // Small faction meeting/refill table rather than decorative furniture
        // everywhere. This keeps the central room open and gives embodied
        // players a believable place to pause, coordinate and inspect supplies.
        for(int x=cx-2;x<=cx+2;x++)
            queue.add(new Op(w,x,y+1,cz,Material.WOOD));
        queue.add(new Op(w,cx-2,y+2,cz+1,Material.SIGN_POST,(byte)8,"[Faction]|War Room"));
        queue.add(new Op(w,cx+2,y+2,cz+1,Material.SIGN_POST,(byte)8,"[KOTH]|Prep"));
        queue.add(new Op(w,cx,y+5,cz,Material.GLOWSTONE));
    }

    private void buildUtilityCorner(World w,HcfBasePlan p) {
        int[] a=p.anchor("utility");
        int y=p.undergroundY;
        int x=a[0],z=a[2];
        queue.add(new Op(w,x,y+1,z,Material.WORKBENCH));
        queue.add(new Op(w,x+1,y+1,z,Material.FURNACE));
        queue.add(new Op(w,x-1,y+1,z,Material.FURNACE));
        queue.add(new Op(w,x,y+1,z+1,Material.ENDER_CHEST));
    }

    private Material[] sampleLocalPalette(World w,HcfBasePlan p) {
        int r=p.terrainCradleRadius()+5;
        Map<Material,Integer> count=new LinkedHashMap<Material,Integer>();
        for(int x=p.cx-r;x<=p.cx+r;x+=4) {
            for(int z=p.cz-r;z<=p.cz+r;z+=4) {
                // Sample the outer site more heavily than the center that is
                // about to be excavated.
                if(Math.abs(x-p.cx)<p.surfaceHalfX+3 && Math.abs(z-p.cz)<p.surfaceHalfZ+3) continue;
                int y=solidSurfaceY(w,x,z);
                Material m=w.getBlockAt(x,y,z).getType();
                if(m==Material.GRASS || m==Material.DIRT || m==Material.SAND ||
                   m==Material.GRAVEL || m==Material.STONE || m==Material.COBBLESTONE ||
                   m==Material.MOSSY_COBBLESTONE) {
                    Integer n=count.get(m);
                    count.put(m,n==null?1:n+1);
                }
            }
        }

        Material dominant=Material.GRASS;
        int best=-1;
        for(Map.Entry<Material,Integer> e:count.entrySet()) {
            if(e.getValue()>best) { best=e.getValue(); dominant=e.getKey(); }
        }

        if(dominant==Material.SAND)
            return new Material[]{Material.SAND,Material.SAND,Material.SANDSTONE};
        if(dominant==Material.STONE || dominant==Material.COBBLESTONE || dominant==Material.MOSSY_COBBLESTONE)
            return new Material[]{dominant,Material.STONE,Material.GRAVEL};
        if(dominant==Material.GRAVEL)
            return new Material[]{Material.GRASS,Material.DIRT,Material.GRAVEL};
        return new Material[]{Material.GRASS,Material.DIRT,Material.COBBLESTONE};
    }

    private double cradleNoise(int x,int z,int seed) {
        int gx=Math.floorDiv(x,5),gz=Math.floorDiv(z,5);
        double fx=(Math.floorMod(x,5))/5.0,fz=(Math.floorMod(z,5))/5.0;
        fx=fx*fx*(3.0-2.0*fx); fz=fz*fz*(3.0-2.0*fz);
        double a=cradleLattice(gx,gz,seed),b=cradleLattice(gx+1,gz,seed);
        double c0=cradleLattice(gx,gz+1,seed),d=cradleLattice(gx+1,gz+1,seed);
        double ab=a+(b-a)*fx,cd=c0+(d-c0)*fx;
        return ab+(cd-ab)*fz;
    }

    private double cradleBroadNoise(int x,int z,int seed) {
        int cell=13;
        int gx=Math.floorDiv(x,cell),gz=Math.floorDiv(z,cell);
        double fx=(Math.floorMod(x,cell))/(double)cell;
        double fz=(Math.floorMod(z,cell))/(double)cell;
        fx=fx*fx*(3.0-2.0*fx); fz=fz*fz*(3.0-2.0*fz);
        double a=cradleLattice(gx,gz,seed),b=cradleLattice(gx+1,gz,seed);
        double c0=cradleLattice(gx,gz+1,seed),d0=cradleLattice(gx+1,gz+1,seed);
        double ab=a+(b-a)*fx,cd=c0+(d0-c0)*fx;
        return ab+(cd-ab)*fz;
    }

    private double cradleLattice(int x,int z,int seed) {
        long h=((long)x*341873128712L)^((long)z*132897987541L)^((long)seed*31L);
        h^=(h>>>21); h*=0x9E3779B97F4A7C15L; h^=(h>>>29);
        return (h&0xffffL)/65535.0;
    }

    private Material terrainSurfacePatch(Material dominant,Material support,Material accent,int x,int z,int seed) {
        // Five-block value-noise produces coherent clusters instead of per-block
        // confetti: approximately 70% dominant, 20% support, 10% accent.
        double n=cradleNoise(x,z,seed+1511);
        if(n>0.91) return accent;
        if(n>0.70) return support;
        return dominant;
    }

    private void buildTerrainCradle(World w,HcfBasePlan p,int maxTop) {
        // Phase 2B: intentionally no generated concealment landform.
        //
        // The authored FreeMap is the landscape. prepareTerrainPad() has already
        // handled the only human-scale work allowed here: shell support and a
        // tiny optional PvP apron. Do not add rings, soil caps, biome bleed,
        // random texture fields or artificial banks after the building exists.
        //
        // Cave/Tunnel identity now comes from their actual reference-derived
        // architecture and site selection, not from burying a tiny placeholder.
    }

    private void buildBuriedFamilyBanks(World w,HcfBasePlan p,
                                        Material topMat,Material fillMat,Material accent,
                                        int gateX,int frontZ,int extra) {
        if(p.primaryFamily!=3 && p.primaryFamily!=4) return;

        int reach=p.primaryFamily==3?5:6;
        // Banks meet the structural roof line on side/rear faces. This hides
        // the long low wall without making a radial mound; the front fan below
        // remains explicitly excluded.
        int targetBase=p.surfaceY+4;

        for(int x=p.cx-p.surfaceHalfX-reach;x<=p.cx+p.surfaceHalfX+reach;x++) {
            for(int z=p.cz-p.surfaceHalfZ-reach;z<=p.cz+p.surfaceHalfZ+reach;z++) {
                if(surfaceInside(p,x,z)) continue;
                double dist=surfaceDistanceFromMaskExact(p,x,z,reach+1);
                if(dist<=0.0 || dist>reach+0.25) continue;

                // The first impression must remain a mouth/opening. No earth
                // is allowed to close the 7-wide front fan or form a horseshoe.
                int forward=frontZ-z;
                if(z<=frontZ+3 && Math.abs(x-gateX)<=5+Math.max(0,forward/4))
                    continue;

                double uphill=terrainUphillBias(p,x,z);
                double broad=cradleBroadNoise(x,z,p.seed+(p.primaryFamily==3?2027:2089));
                double fine=cradleNoise(x,z,p.seed+2111);
                double field=0.52*broad+0.36*uphill+0.12*fine;

                // Close banks may hug portions of the side/rear wall, but gaps
                // are mandatory so the result cannot become another ellipse/ring.
                double threshold;
                if(dist<=1.65)
                    threshold=p.primaryFamily==3?0.34:0.37;
                else
                    threshold=(p.primaryFamily==3?0.50:0.48)+
                        Math.max(0.0,dist-1.65)*0.085;
                if(field<threshold) continue;

                // Tunnel banks favor long sides/rear. Cave banks are lopsided
                // rock shoulders biased to the real uphill side.
                if(p.primaryFamily==3) {
                    boolean rear=z>=p.cz+p.surfaceHalfZ-3;
                    boolean side=Math.abs(x-p.cx)>=Math.max(2,p.surfaceHalfX-2);
                    if(!rear && !side) continue;
                } else {
                    double sideBias=p.utilitySide*(x-p.cx)/(double)Math.max(1,p.surfaceHalfX);
                    if(dist>2.0 && uphill<0.42 && sideBias<0.15) continue;
                }

                int ground=gradedSurfaceY(p,x,z,extra+3);
                int bump=(broad>0.72?1:0)+(uphill>0.72?1:0);
                int desired=Math.min(targetBase+bump,p.surfaceY+5);
                if(ground>=desired) continue;

                for(int yy=ground+1;yy<desired;yy++) {
                    Material m=fillMat;
                    if(p.primaryFamily==4 && dist<1.8 && yy>=desired-2)
                        m=(fine>0.58?Material.COBBLESTONE:Material.STONE);
                    else if(p.primaryFamily==3 && dist<1.35 && fine>0.70)
                        m=Material.COBBLESTONE;
                    queue.add(new Op(w,x,yy,z,m));
                }

                Material exposed;
                if(p.primaryFamily==4 && dist<1.7 && broad>0.44)
                    exposed=(fine>0.66?Material.MOSSY_COBBLESTONE:Material.COBBLESTONE);
                else
                    exposed=terrainSurfacePatch(topMat,fillMat,accent,x,z,p.seed+2231);
                queue.add(new Op(w,x,desired,z,exposed));
            }
        }
    }

    private double terrainUphillBias(HcfBasePlan p,int x,int z) {
        int r=8;
        double gx=plugin.canonicalHcfTerrainY(p.cx+r,p.cz)-
            plugin.canonicalHcfTerrainY(p.cx-r,p.cz);
        double gz=plugin.canonicalHcfTerrainY(p.cx,p.cz+r)-
            plugin.canonicalHcfTerrainY(p.cx,p.cz-r);
        double gm=Math.sqrt(gx*gx+gz*gz);
        double dx=x-p.cx,dz=z-p.cz;
        double dm=Math.sqrt(dx*dx+dz*dz);
        if(gm<0.35 || dm<0.5) return 0.50;
        double dot=(dx/dm)*(gx/gm)+(dz/dm)*(gz/gm);
        return Math.max(0.0,Math.min(1.0,0.5+0.5*dot));
    }

    private void decorateSurfaceGrammar(World w,HcfBasePlan p,int top) {
        int gx=p.cx+p.frontGateOffset;
        int frontZ=surfaceFrontZ(p,gx);

        // Architectural detail only. No terrain shoulders, no fake approach
        // mounds and no random exterior texturing: the building is the subject.
        if(p.primaryFamily==0) {
            // Redemption: strong entry brow, timber band and upper parapet.
            for(int x=gx-3;x<=gx+3;x++)
                queue.add(new Op(w,x,p.surfaceY+4,frontZ,p.surfaceFrame));
            for(int x=p.cx-5;x<=p.cx+5;x++)
                queue.add(new Op(w,x,p.surfaceY+7,surfaceFrontZ(p,x),Material.LOG));
            int ry=surfaceMaxTop(p)+1;
            for(int x=p.cx-4;x<=p.cx+4;x+=4)
                queue.add(new Op(w,x,ry,p.cz+2,p.surfaceFrame));
        } else if(p.primaryFamily==1) {
            // Base-HCF: broad framed facade with visible buttresses/parapet.
            int[] zs={Math.max(frontZ+2,p.cz-4),Math.min(surfaceRearZ(p,p.cx)-2,p.cz+4)};
            for(int z:zs) {
                queue.add(new Op(w,surfaceSideX(p,z,-1),p.surfaceY+3,z,p.undergroundTrim));
                queue.add(new Op(w,surfaceSideX(p,z,+1),p.surfaceY+3,z,p.undergroundTrim));
            }
            int ry=surfaceMaxTop(p)+1;
            for(int x=p.cx-p.surfaceHalfX+2;x<=p.cx+p.surfaceHalfX-2;x+=4)
                queue.add(new Op(w,x,ry,p.cz,p.surfaceFrame));
        } else if(p.primaryFamily==2) {
            // Modern: restrained quartz frame around the primary entrance and
            // one clean rear window band.
            Material trim=Material.QUARTZ_BLOCK;
            for(int x=gx-3;x<=gx+3;x++)
                queue.add(new Op(w,x,p.surfaceY+4,frontZ,trim));
            for(int x=p.cx-4;x<=p.cx+4;x++) {
                int rz=surfaceRearZ(p,x);
                queue.add(new Op(w,x,p.surfaceY+6,rz,Material.STAINED_GLASS,(byte)0));
            }
        } else if(p.primaryFamily==3) {
            // Tunnel reference is a visible stacked tower: reinforced entry,
            // mid-level band and short roof prongs.
            for(int x=gx-3;x<=gx+3;x++)
                queue.add(new Op(w,x,p.surfaceY+4,frontZ,p.surfaceFrame));
            for(int x=p.cx-4;x<=p.cx+4;x++)
                queue.add(new Op(w,x,p.surfaceY+7,surfaceFrontZ(p,x),Material.LOG));
            int ry=surfaceMaxTop(p)+1;
            queue.add(new Op(w,p.cx-2,ry,p.cz,p.surfaceFrame));
            queue.add(new Op(w,p.cx+2,ry,p.cz,p.surfaceFrame));
        } else {
            // Cave/Devhorah: clearly architectural timber facade held by rough
            // stone supports, with a peaked center rather than a rock capsule.
            for(int yy=p.surfaceY+1;yy<=p.surfaceY+5;yy++) {
                queue.add(new Op(w,gx-4,yy,frontZ,yy<=2?Material.COBBLESTONE:Material.LOG));
                queue.add(new Op(w,gx+4,yy,frontZ,yy<=2?Material.COBBLESTONE:Material.LOG));
            }
            queue.add(new Op(w,gx-3,p.surfaceY+6,frontZ,Material.LOG));
            queue.add(new Op(w,gx+3,p.surfaceY+6,frontZ,Material.LOG));
            queue.add(new Op(w,gx-2,p.surfaceY+7,frontZ,Material.LOG));
            queue.add(new Op(w,gx+2,p.surfaceY+7,frontZ,Material.LOG));
            queue.add(new Op(w,gx,p.surfaceY+8,frontZ,Material.STAINED_GLASS,
                surfaceWallData(p,Material.STAINED_GLASS)));
            queue.add(new Op(w,gx+p.utilitySide*5,p.surfaceY+1,frontZ+1,Material.COBBLESTONE));
            queue.add(new Op(w,gx+p.utilitySide*5,p.surfaceY+2,frontZ+1,Material.MOSSY_COBBLESTONE));
        }
        // Colored roof glass should read like the period's HCF glass rooms /
        // lookout strips instead of default white panes.
        if(p.primaryFamily<=3) {
            byte data=surfaceWallData(p,Material.STAINED_GLASS);
            if(p.primaryFamily==2) {
                for(int x=p.cx-2;x<=p.cx+2;x++) for(int z=p.cz-2;z<=p.cz+2;z++)
                    queue.add(new Op(w,x,surfaceRoofY(p,x,z)+1,z,Material.STAINED_GLASS,data));
            } else if(p.primaryFamily==1) {
                for(int z=p.cz-3;z<=p.cz+3;z++)
                    queue.add(new Op(w,p.cx,surfaceRoofY(p,p.cx,z)+1,z,Material.STAINED_GLASS,data));
            } else {
                queue.add(new Op(w,p.cx,surfaceMaxTop(p)+1,p.cz,Material.STAINED_GLASS,data));
            }
        }

    }

    private void buildReferenceGrammar(World w,HcfBasePlan p) {
        switch(p.primaryFamily) {
            case 0: buildRedemptionGrammar(w,p); break;
            case 1: buildBaseHcfGrammar(w,p); break;
            case 2: buildModernGrammar(w,p); break;
            case 3: buildTunnelGrammar(w,p); break;
            case 4: buildCaveGrammar(w,p); break;
        }
        buildSecondaryAccent(w,p,p.secondaryFamily);
        buildSubclaimMarkers(w,p);
    }

    private void buildRedemptionGrammar(World w,HcfBasePlan p) {
        int y=p.undergroundY;
        int z=p.cz+p.coreHalfZ-4;
        // Compact mezzanine / vertical organization inspired by Redemption.
        for(int x=p.cx-7;x<=p.cx+7;x++) {
            queue.add(new Op(w,x,y+3,z,p.undergroundTrim));
            if(Math.abs(x-p.cx)>=6) queue.add(new Op(w,x,y+4,z,Material.IRON_FENCE));
        }
        for(int step=0;step<4;step++) {
            int x=p.cx-7+step;
            queue.add(new Op(w,x,y+step,z-2,p.undergroundTrim));
            for(int yy=y+step+1;yy<=y+step+2;yy++) queue.add(new Op(w,x,yy,z-2,Material.AIR));
        }
        queue.add(new Op(w,p.cx,y+5,z,Material.GLOWSTONE));
    }

    private void buildBaseHcfGrammar(World w,HcfBasePlan p) {
        int y=p.undergroundY;
        int r=7;
        // Ring-storage visual language: central circulation remains completely open.
        for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) {
            int edge=Math.max(Math.abs(dx),Math.abs(dz));
            if(edge!=r) continue;
            if(Math.abs(dx+3)<=2 && Math.abs(dz+1)<=2) continue; // dropdown traffic
            Material m=((dx+dz+p.seed)&3)==0?p.undergroundTrim:Material.SMOOTH_BRICK;
            queue.add(new Op(w,p.cx+dx,y,p.cz+dz,m));
        }
        for(int[] d:new int[][]{{-r,-r},{r,-r},{-r,r},{r,r}}) {
            queue.add(new Op(w,p.cx+d[0],y+1,p.cz+d[1],p.undergroundTrim));
            queue.add(new Op(w,p.cx+d[0],y+2,p.cz+d[1],Material.GLOWSTONE));
        }
    }

    private void buildModernGrammar(World w,HcfBasePlan p) {
        int y=p.undergroundY;
        // Clean symmetric ceiling strips and two glass utility separators.
        for(int x=p.cx-p.coreHalfX+3;x<=p.cx+p.coreHalfX-3;x+=5)
            queue.add(new Op(w,x,y+5,p.cz,Material.GLOWSTONE));
        int z1=p.cz-6,z2=p.cz+6;
        for(int x=p.cx-5;x<=p.cx+5;x++) {
            if(Math.abs(x-p.cx)<=1) continue;
            queue.add(new Op(w,x,y+1,z1,Material.STAINED_GLASS,(byte)0));
            queue.add(new Op(w,x,y+2,z1,Material.STAINED_GLASS,(byte)0));
            queue.add(new Op(w,x,y+1,z2,Material.STAINED_GLASS,(byte)0));
            queue.add(new Op(w,x,y+2,z2,Material.STAINED_GLASS,(byte)0));
        }
        queue.add(new Op(w,p.cx-6,y+1,p.cz,Material.QUARTZ_BLOCK));
        queue.add(new Op(w,p.cx+6,y+1,p.cz,Material.QUARTZ_BLOCK));
    }

    private void buildTunnelGrammar(World w,HcfBasePlan p) {
        int dir=p.utilitySide;
        int start=p.cx+dir*(p.coreHalfX-1);
        int end=p.cx+dir*(p.coreHalfX+11);
        int lo=Math.min(start,end),hi=Math.max(start,end);
        int floor=p.undergroundY;
        for(int x=lo;x<=hi;x++) {
            for(int z=p.cz-2;z<=p.cz+2;z++) {
                boolean side=Math.abs(z-p.cz)==2;
                queue.add(new Op(w,x,floor,z,side?p.undergroundTrim:p.undergroundFloor));
                for(int yy=floor+1;yy<=floor+3;yy++)
                    queue.add(new Op(w,x,yy,z,side?p.undergroundTrim:Material.AIR));
                queue.add(new Op(w,x,floor+4,z,p.undergroundTrim));
            }
            if(Math.abs(x-start)%5==0) queue.add(new Op(w,x,floor+3,p.cz,Material.GLOWSTONE));
        }
        // Small utility room at the end.
        int ex=end+dir*3;
        carveUtilityRoom(w,p,ex,p.cz,4,5,p.undergroundTrim);
    }

    private void buildCaveGrammar(World w,HcfBasePlan p) {
        int dir=-p.utilitySide;
        int centerX=p.cx+dir*(p.coreHalfX+5);
        int centerZ=p.cz+((p.seed/71)%7)-3;
        int floor=p.undergroundY;
        int rx=7,rz=6;
        for(int dx=-rx;dx<=rx;dx++) for(int dz=-rz;dz<=rz;dz++) {
            double q=(dx*dx)/(double)(rx*rx)+(dz*dz)/(double)(rz*rz);
            if(q>1.12) continue;
            int x=centerX+dx,z=centerZ+dz;
            boolean shell=q>0.78;
            queue.add(new Op(w,x,floor,z,shell?Material.COBBLESTONE:p.undergroundFloor));
            for(int yy=floor+1;yy<=floor+4;yy++) {
                Material m=shell?(((x+z+yy+p.seed)%5==0)?Material.MOSSY_COBBLESTONE:Material.COBBLESTONE):Material.AIR;
                queue.add(new Op(w,x,yy,z,m));
            }
            queue.add(new Op(w,x,floor+5,z,shell?Material.COBBLESTONE:Material.STONE));
        }
        // Three-wide sealed connector from the core.
        int coreEdge=p.cx+dir*p.coreHalfX;
        int a=Math.min(coreEdge,centerX),b=Math.max(coreEdge,centerX);
        for(int x=a;x<=b;x++) for(int z=centerZ-1;z<=centerZ+1;z++)
            for(int yy=floor+1;yy<=floor+3;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
        queue.add(new Op(w,centerX,floor+4,centerZ,Material.GLOWSTONE));
    }

    private void carveUtilityRoom(World w,HcfBasePlan p,int cx,int cz,int hx,int hz,Material trim) {
        int floor=p.undergroundY;
        for(int x=cx-hx;x<=cx+hx;x++) for(int z=cz-hz;z<=cz+hz;z++) {
            boolean edge=x==cx-hx||x==cx+hx||z==cz-hz||z==cz+hz;
            queue.add(new Op(w,x,floor,z,p.undergroundFloor));
            for(int yy=floor+1;yy<=floor+4;yy++)
                queue.add(new Op(w,x,yy,z,edge?trim:Material.AIR));
            queue.add(new Op(w,x,floor+5,z,trim));
        }
    }

    private void buildSecondaryAccent(World w,HcfBasePlan p,int family) {
        int y=p.undergroundY;
        switch(family) {
            case 0:
                for(int x=p.cx-4;x<=p.cx+4;x+=2)
                    queue.add(new Op(w,x,y,p.cz+4,p.undergroundTrim));
                break;
            case 1:
                for(int z=p.cz-8;z<=p.cz+8;z+=4)
                    queue.add(new Op(w,p.cx,y,z,Material.WOOD));
                break;
            case 2:
                queue.add(new Op(w,p.cx-5,y+5,p.cz-5,Material.GLOWSTONE));
                queue.add(new Op(w,p.cx+5,y+5,p.cz+5,Material.GLOWSTONE));
                break;
            case 3:
                for(int z=p.cz-p.coreHalfZ+3;z<=p.cz+p.coreHalfZ-3;z+=6)
                    queue.add(new Op(w,p.cx,y+4,z,p.undergroundTrim));
                break;
            case 4:
                queue.add(new Op(w,p.cx+p.utilitySide*5,y+1,p.cz+5,Material.MOSSY_COBBLESTONE));
                queue.add(new Op(w,p.cx+p.utilitySide*6,y+1,p.cz+5,Material.COBBLESTONE));
                break;
        }
    }

    private void buildSubclaimMarkers(World w,HcfBasePlan p) {
        if(p.profile.organization<45) return;
        int[] storage=p.anchor("storage");
        int[] refill=p.anchor("refill");
        queue.add(new Op(w,storage[0],p.undergroundY+1,storage[2]-2,Material.SIGN_POST,(byte)8,
            "["+shortFaction(p.faction)+"]|[Subclaim]|Storage"));
        queue.add(new Op(w,refill[0]+2,p.undergroundY+1,refill[2],Material.SIGN_POST,(byte)8,
            "["+shortFaction(p.faction)+"]|[Subclaim]|Pots"));
    }

    private String shortFaction(String name) {
        if(name==null) return "Faction";
        return name.length()>11?name.substring(0,11):name;
    }

    private void buildVerticalTransit(World w,HcfBasePlan p) {
        int[] top=p.anchor("drop");
        int dx=top[0],dz=top[2];

        // A real 3x3 dropdown must cut THROUGH the surface floor. The previous
        // implementation stopped one block too high, leaving a solid floor over
        // a visually-generated shaft and disconnecting the upper/lower base.
        for(int x=dx-1;x<=dx+1;x++) for(int z=dz-1;z<=dz+1;z++) {
            for(int yy=p.undergroundY+1;yy<=p.surfaceY;yy++)
                queue.add(new Op(w,x,yy,z,Material.AIR));
        }

        // Fully line the shaft so intersecting caves/ravines cannot appear as
        // random holes in the faction base. The 5x5 shell surrounds the 3x3 drop.
        for(int x=dx-2;x<=dx+2;x++) for(int z=dz-2;z<=dz+2;z++) {
            boolean shell=x==dx-2||x==dx+2||z==dz-2||z==dz+2;
            if(!shell) continue;
            for(int yy=p.undergroundY;yy<=p.surfaceY;yy++) {
                Material mat=yy==p.surfaceY?p.surfaceFrame:p.undergroundTrim;
                queue.add(new Op(w,x,yy,z,mat));
            }
        }

        // Explicit surface rim: everything outside the intentional 3x3 opening
        // is solid, so the roofed SOTW shell never has accidental floor gaps.
        for(int x=dx-2;x<=dx+2;x++) for(int z=dz-2;z<=dz+2;z++) {
            boolean opening=Math.abs(x-dx)<=1 && Math.abs(z-dz)<=1;
            queue.add(new Op(w,x,p.surfaceY,z,opening?Material.AIR:p.surfaceFrame));
        }

        // Water landing fills the drop footprint. The exit gates are placed in
        // the lined south wall at the SAME feet level as the water.
        for(int x=dx-1;x<=dx+1;x++) for(int z=dz-1;z<=dz+1;z++)
            queue.add(new Op(w,x,p.undergroundY+1,z,Material.STATIONARY_WATER));
        for(int x=dx-1;x<=dx+1;x++) for(int yy=p.undergroundY+1;yy<=p.undergroundY+2;yy++)
            queue.add(new Op(w,x,yy,dz+2,Material.FENCE_GATE,(byte)0));

        // Clear a short dry exit into the central core.
        for(int z=dz+3;z<=dz+4;z++) for(int x=dx-1;x<=dx+1;x++)
            for(int yy=p.undergroundY+1;yy<=p.undergroundY+3;yy++)
                queue.add(new Op(w,x,yy,z,Material.AIR));

        int[] e=p.anchor("elevator");
        queue.add(new Op(w,e[0],p.undergroundY,e[2],p.undergroundTrim));
        queue.add(new Op(w,e[0],e[1],e[2],Material.SIGN_POST,(byte)8,"[Elevator]|Up"));
        for(int yy=e[1]+1;yy<=e[1]+2;yy++) queue.add(new Op(w,e[0],yy,e[2],Material.AIR));
    }

    private void buildStorageTier(World w,HcfBasePlan p,int tier) {
        String[] labels={"Pots","Pearls","Valuables","Blocks","Brewing","Farm","Overflow",
            "Helmets","Chestplates","Leggings","Boots","Swords","Bows","Kits"};
        int count=tier<=1?8:(tier==2?11:14);

        for(int i=0;i<count;i++) {
            int[] a=p.storageSlot(i);
            doubleChest(w,a[0],a[1],a[2],labels[i]);
        }

        // Function is decoration: organized chest banks, signs and lighting make
        // the room look intentional without a fantasy-build shell around it.
        for(int x=p.cx-p.coreHalfX+2;x<=p.cx+p.coreHalfX-2;x+=8)
            queue.add(new Op(w,x,p.undergroundY+5,p.cz,Material.GLOWSTONE));
    }

    private int storageCategoryIndex(String category) {
        String c=category==null?"overflow":category.toLowerCase(java.util.Locale.ENGLISH);
        if("pots".equals(c)) return 0;
        if("pearls".equals(c)) return 1;
        if("valuables".equals(c)) return 2;
        if("blocks".equals(c)) return 3;
        if("brewing".equals(c)) return 4;
        if("farm".equals(c)) return 5;
        if("helmets".equals(c)) return 7;
        if("chestplates".equals(c)) return 8;
        if("leggings".equals(c)) return 9;
        if("boots".equals(c)) return 10;
        if("swords".equals(c)) return 11;
        if("bows".equals(c)) return 12;
        if("kits".equals(c)) return 13;
        return 6;
    }

    private void excavateFarmRoom(World w,int cx,int floor,int cz,int halfX,int halfZ,Material trim) {
        for(int x=cx-halfX;x<=cx+halfX;x++) for(int z=cz-halfZ;z<=cz+halfZ;z++) {
            queue.add(new Op(w,x,floor,z,Material.DIRT));
            for(int yy=floor+1;yy<=floor+4;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
            boolean edge=x==cx-halfX||x==cx+halfX||z==cz-halfZ||z==cz+halfZ;
            if(edge) for(int yy=floor+1;yy<=floor+4;yy++) queue.add(new Op(w,x,yy,z,trim));
            queue.add(new Op(w,x,floor+5,z,trim));
        }
    }

    private void buildFarmLevel(World w,HcfBasePlan p,String preferred) {
        int[] farm=p.anchor("farm");
        int floor=farm[1]-1;
        int hx=Math.max(10,p.coreHalfX-3),hz=Math.max(8,p.coreHalfZ-4);
        excavateFarmRoom(w,farm[0],floor,farm[2],hx,hz,p.finishTier==0?Material.STONE:Material.SMOOTH_BRICK);

        // Sugar cane is the Daegon money engine.  Put it in the largest lanes.
        for(int z=farm[2]-hz+2;z<=farm[2]-1;z+=3) {
            // Reserve the faction-center z line as a dry circulation/stair aisle.
            if(Math.abs(z-p.cz)<=1) continue;
            for(int x=farm[0]-hx+2;x<=farm[0]+hx-2;x++) {
                boolean water=((x-(farm[0]-hx+2))%4)==1;
                queue.add(new Op(w,x,floor,z,water?Material.STATIONARY_WATER:Material.SAND));
                if(!water) {
                    queue.add(new Op(w,x,floor+1,z,Material.SUGAR_CANE_BLOCK));
                    if((x+z+p.seed)%3==0) queue.add(new Op(w,x,floor+2,z,Material.SUGAR_CANE_BLOCK));
                }
            }
        }

        // Wart + melon preserve the period HCF potion pipeline.
        for(int z=farm[2]+2;z<=farm[2]+5;z++) for(int x=farm[0]-hx+2;x<=farm[0]-1;x++) {
            queue.add(new Op(w,x,floor,z,Material.SOUL_SAND));
            queue.add(new Op(w,x,floor+1,z,Material.NETHER_WARTS,(byte)3));
        }
        for(int z=farm[2]+2;z<=farm[2]+5;z++) for(int x=farm[0]+1;x<=farm[0]+hx-2;x++) {
            boolean stem=((x+z)&1)==0;
            queue.add(new Op(w,x,floor,z,Material.SOIL));
            queue.add(new Op(w,x,floor+1,z,stem?Material.MELON_STEM:Material.MELON_BLOCK,stem?(byte)7:(byte)0));
        }

        for(int x=farm[0]-hx+2;x<=farm[0]+hx-2;x+=8)
            queue.add(new Op(w,x,floor+4,farm[2],Material.GLOWSTONE));

        // The farm is a lower underground level, but it must be physically
        // connected. Build a walkable one-block-per-step stair tunnel from the
        // central core instead of leaving a sealed room below it.
        buildFarmAccess(w,p,floor);
    }

    private void buildFarmAccess(World w,HcfBasePlan p,int farmFloor) {
        int dir=-p.utilitySide; // use the quiet half; storage rows reserve z=center
        int z=p.cz;
        int drop=p.undergroundY-farmFloor;
        if(drop<1) return;

        for(int i=1;i<=drop;i++) {
            int x=p.cx+dir*i;
            int stepY=p.undergroundY-i;

            // Full block steps are intentionally simple: Mineflayer and human
            // players can both traverse them reliably in 1.8.8.
            queue.add(new Op(w,x,stepY,z,p.undergroundTrim));
            queue.add(new Op(w,x,stepY+1,z,Material.AIR));
            queue.add(new Op(w,x,stepY+2,z,Material.AIR));

            // Seal the tunnel while it passes through natural stone between the
            // core and farm. The final two steps open directly into the farm room.
            if(i<=Math.max(1,drop-2)) {
                for(int yy=stepY+1;yy<=stepY+2;yy++) {
                    queue.add(new Op(w,x,yy,z-1,p.undergroundTrim));
                    queue.add(new Op(w,x,yy,z+1,p.undergroundTrim));
                }
                queue.add(new Op(w,x,stepY+3,z,p.undergroundTrim));
            }
        }

        // Guarantee a clear landing aisle at farm level.
        int endX=p.cx+dir*drop;
        for(int x=Math.min(p.cx,endX)-1;x<=Math.max(p.cx,endX)+1;x++) {
            for(int yy=farmFloor+1;yy<=farmFloor+2;yy++)
                queue.add(new Op(w,x,yy,z,Material.AIR));
        }
    }

    private void buildUndergroundBrewer(World w,HcfBasePlan p) {
        int[] a=p.anchor("brewer");
        int cx=a[0],floor=a[1],cz=a[2];
        int halfX=5,halfZ=8;
        for(int x=cx-halfX;x<=cx+halfX;x++) for(int z=cz-halfZ;z<=cz+halfZ;z++) {
            queue.add(new Op(w,x,floor,z,p.undergroundFloor));
            for(int yy=floor+1;yy<=floor+5;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
        }

        // Exact lane coordinates intentionally match HcfAutoBrewerDirector:
        // stand=(centerX, floorY+2, centerZ-5+lane*2).
        // Permanent Speed II means every physical lane is useful Heal II capacity.
        String[] labels={"Heal II 1","Heal II 2","Heal II 3","Heal II 4","Heal II 5","Heal II 6"};
        int lanes=6;
        for(int i=0;i<lanes;i++) {
            int z=cz-5+i*2;
            queue.add(new Op(w,cx,floor+2,z,Material.BREWING_STAND));
            queue.add(new Op(w,cx,floor+3,z,Material.HOPPER));
            queue.add(new Op(w,cx,floor+4,z,Material.CHEST));
            queue.add(new Op(w,cx-1,floor+2,z,Material.HOPPER,(byte)5));
            queue.add(new Op(w,cx-1,floor+3,z,Material.CHEST));
            queue.add(new Op(w,cx,floor+1,z,Material.HOPPER,(byte)5));
            queue.add(new Op(w,cx+1,floor+1,z,Material.CHEST));
            queue.add(new Op(w,cx+2,floor+1,z,p.undergroundTrim));
            queue.add(new Op(w,cx+2,floor+2,z,Material.SIGN_POST,(byte)8,labels[i]));
        }

        // Visible compact redstone/control spine.
        for(int z=cz-halfZ+1;z<=cz+halfZ-1;z+=2) {
            queue.add(new Op(w,cx-4,floor+1,z,p.undergroundTrim));
            queue.add(new Op(w,cx-4,floor+2,z,Material.REDSTONE_TORCH_ON));
        }
        queue.add(new Op(w,cx-4,floor+1,cz-halfZ+1,Material.LEVER));
    }

    private void buildFactionPortal(World w,HcfBasePlan p,String type) {
        int[] a=p.anchor("nether".equals(type)?"portal-nether":"portal-end");
        int x=a[0],y=a[1],z=a[2];

        if("nether".equals(type)) {
            // Compact wall-integrated 4x5 frame, matching the utility-first HCF look.
            for(int dx=-1;dx<=2;dx++) for(int dy=0;dy<=4;dy++) {
                boolean edge=dx==-1||dx==2||dy==0||dy==4;
                queue.add(new Op(w,x+dx,y+dy,z,edge?Material.OBSIDIAN:Material.PORTAL));
            }
            return;
        }

        // Server-authoritative End portal tool: expensive to unlock, compact in
        // the underground utility wing, and routed by HcfPortalDirector.
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
            boolean frame=Math.abs(dx)==2||Math.abs(dz)==2;
            if(frame && !(Math.abs(dx)==2&&Math.abs(dz)==2))
                queue.add(new Op(w,x+dx,y,z+dz,Material.ENDER_PORTAL_FRAME));
            else if(Math.abs(dx)<=1 && Math.abs(dz)<=1)
                queue.add(new Op(w,x+dx,y,z+dz,Material.ENDER_PORTAL));
        }
    }

    private void buildGlassBox(World w, int cx, int y, int cz, boolean brewerWing) {
        int half = 12;
        int height = 9;

        // Flatten/foundation.
        for (int x=cx-half;x<=cx+half;x++) {
            for (int z=cz-half;z<=cz+half;z++) {
                queue.add(new Op(w,x,y,z,Material.SMOOTH_BRICK));
                for (int yy=y+1;yy<=y+height;yy++) {
                    if (x==cx-half||x==cx+half||z==cz-half||z==cz+half) {
                        boolean pillar = ((x==cx-half||x==cx+half) && (z==cz-half||z==cz+half));
                        queue.add(new Op(w,x,yy,z,pillar?Material.SMOOTH_BRICK:Material.STAINED_GLASS,(byte)0));
                    } else if (yy <= y+height) {
                        queue.add(new Op(w,x,yy,z,Material.AIR));
                    }
                }
                queue.add(new Op(w,x,y+height+1,z,Material.SMOOTH_BRICK));
            }
        }

        // Front entrance: a real three-wide HCF fence-gate doorway with
        // guaranteed headroom and approach clearance.
        doorway(w,cx,y,cz-half);

        // Inner secure room / panic room.
        for (int x=cx-4;x<=cx+4;x++) {
            for (int z=cz-4;z<=cz+4;z++) {
                for (int yy=y+1;yy<=y+5;yy++) {
                    boolean wall=x==cx-4||x==cx+4||z==cz-4||z==cz+4||yy==y+5;
                    if (wall) queue.add(new Op(w,x,yy,z,Material.SMOOTH_BRICK));
                }
            }
        }
        doorway(w,cx,y,cz-4);

        // Storage room chests.
        for (int x=cx-8;x<=cx-5;x++) {
            queue.add(new Op(w,x,y+1,cz+7,Material.CHEST));
            queue.add(new Op(w,x,y+2,cz+7,Material.CHEST));
        }

        // Enchant/anvil corner.
        queue.add(new Op(w,cx+8,y+1,cz+8,Material.ENCHANTMENT_TABLE));
        queue.add(new Op(w,cx+7,y+1,cz+8,Material.ANVIL));
        for(int dx=-1;dx<=1;dx++) {
            queue.add(new Op(w,cx+8+dx,y+1,cz+6,Material.BOOKSHELF));
        }

        if (brewerWing) buildBrewerRoom(w,"hcf_glass_box",cx,y,cz);
    }

    private void buildCourtyard(World w, int cx, int y, int cz) {
        int half = 14;
        int height = 7;
        for (int x=cx-half;x<=cx+half;x++) {
            for (int z=cz-half;z<=cz+half;z++) {
                queue.add(new Op(w,x,y,z,Material.SMOOTH_BRICK));
                boolean edge=x==cx-half||x==cx+half||z==cz-half||z==cz+half;
                if (edge) {
                    for(int yy=y+1;yy<=y+height;yy++) {
                        Material m=(yy==y+1||yy==y+height)?Material.SMOOTH_BRICK:Material.STAINED_GLASS;
                        queue.add(new Op(w,x,yy,z,m));
                    }
                } else {
                    for(int yy=y+1;yy<=y+height;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
                }
            }
        }

        // central protected core
        for(int x=cx-5;x<=cx+5;x++) for(int z=cz-5;z<=cz+5;z++) {
            for(int yy=y+1;yy<=y+5;yy++) {
                if(x==cx-5||x==cx+5||z==cz-5||z==cz+5||yy==y+5) queue.add(new Op(w,x,yy,z,Material.SMOOTH_BRICK));
            }
        }
        for(int yy=y+1;yy<=y+2;yy++) queue.add(new Op(w,cx,yy,cz-5,Material.AIR));

        doorway(w,cx,y,cz-half);
        doorway(w,cx,y,cz-5);

        // cane strip in courtyard
        for(int x=cx-11;x<=cx-7;x++) {
            queue.add(new Op(w,x,y,cz+9,Material.SAND));
            queue.add(new Op(w,x,y+1,cz+9,Material.SUGAR_CANE_BLOCK));
            queue.add(new Op(w,x,y,cz+10,Material.STATIONARY_WATER));
        }
    }

    private void buildCompact2015(World w, int cx, int y, int cz) {
        int half=9, height=8;
        shell(w,cx,y,cz,half,height,Material.SMOOTH_BRICK,Material.GLASS);

        // Compact period-style core: storage below, enchant/anvil, narrow exits.
        for(int x=cx-6;x<=cx+6;x++) for(int z=cz-6;z<=cz+6;z++) {
            queue.add(new Op(w,x,y-1,z,Material.SMOOTH_BRICK));
            for(int yy=y-5;yy<y-1;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
        }
        for(int x=cx-5;x<=cx-2;x++) {
            queue.add(new Op(w,x,y-4,cz+4,Material.CHEST));
            queue.add(new Op(w,x,y-3,cz+4,Material.CHEST));
        }
        queue.add(new Op(w,cx+5,y+1,cz+5,Material.ENCHANTMENT_TABLE));
        queue.add(new Op(w,cx+4,y+1,cz+5,Material.ANVIL));
        doorway(w,cx,y,cz-half);
    }

    private void buildSplitLevel(World w, int cx, int y, int cz) {
        int half=11, height=10;
        shell(w,cx,y,cz,half,height,Material.SMOOTH_BRICK,Material.STAINED_GLASS);

        // Upper fight/refill room.
        for(int x=cx-6;x<=cx+6;x++) for(int z=cz-6;z<=cz+6;z++)
            queue.add(new Op(w,x,y+5,z,Material.SMOOTH_BRICK));
        for(int x=cx-2;x<=cx+2;x++) for(int z=cz-2;z<=cz+2;z++)
            queue.add(new Op(w,x,y+5,z,Material.AIR));

        // Lower storage level.
        for(int x=cx-8;x<=cx+8;x++) for(int z=cz-8;z<=cz+8;z++) {
            queue.add(new Op(w,x,y-1,z,Material.SMOOTH_BRICK));
            for(int yy=y-5;yy<y-1;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
        }
        for(int z=cz-6;z<=cz+6;z+=3) {
            queue.add(new Op(w,cx-7,y-4,z,Material.CHEST));
            queue.add(new Op(w,cx+7,y-4,z,Material.CHEST));
        }
        doorway(w,cx,y,cz-half);
    }

    private void buildArcherTower(World w, int cx, int y, int cz) {
        int half=10, height=9;
        shell(w,cx,y,cz,half,height,Material.SMOOTH_BRICK,Material.GLASS);

        // Two lightweight archer towers overlooking the approach.
        tower(w,cx-7,y+1,cz-7,4,12);
        tower(w,cx+7,y+1,cz-7,4,12);

        // Interior safe/refill room.
        for(int x=cx-4;x<=cx+4;x++) for(int z=cz+2;z<=cz+8;z++) {
            for(int yy=y+1;yy<=y+5;yy++) {
                boolean wall=x==cx-4||x==cx+4||z==cz+2||z==cz+8||yy==y+5;
                if(wall) queue.add(new Op(w,x,yy,z,Material.SMOOTH_BRICK));
            }
        }
        doorway(w,cx,y,cz-half);
    }

    private void buildDoubleLayer(World w, int cx, int y, int cz) {
        int half=13, height=10;
        shell(w,cx,y,cz,half,height,Material.SMOOTH_BRICK,Material.STAINED_GLASS);

        // Second shell/panic layer leaves a fighting corridor between shells.
        int inner=7;
        for(int x=cx-inner;x<=cx+inner;x++) for(int z=cz-inner;z<=cz+inner;z++) {
            for(int yy=y+1;yy<=y+7;yy++) {
                boolean edge=x==cx-inner||x==cx+inner||z==cz-inner||z==cz+inner||yy==y+7;
                if(edge) queue.add(new Op(w,x,yy,z,Material.SMOOTH_BRICK));
            }
        }
        doorway(w,cx,y,cz-inner);
        for(int x=cx-5;x<=cx-2;x++) {
            queue.add(new Op(w,x,y+1,cz+5,Material.CHEST));
            queue.add(new Op(w,x,y+2,cz+5,Material.CHEST));
        }
        doorway(w,cx,y,cz-half);
    }

    private void buildTrapHouse(World w, int cx, int y, int cz) {
        buildGlassBox(w,cx,y,cz,false);

        // Safe viewing/trigger lane facing the trap approach.
        for(int z=cz-11;z<=cz-6;z++) {
            queue.add(new Op(w,cx+7,y+1,z,Material.IRON_FENCE));
            queue.add(new Op(w,cx+8,y+1,z,Material.SMOOTH_BRICK));
        }
    }

    private void shell(World w,int cx,int y,int cz,int half,int height,Material frame,Material wall) {
        for(int x=cx-half;x<=cx+half;x++) for(int z=cz-half;z<=cz+half;z++) {
            queue.add(new Op(w,x,y,z,frame));
            for(int yy=y+1;yy<=y+height;yy++) {
                boolean edge=x==cx-half||x==cx+half||z==cz-half||z==cz+half;
                if(edge) {
                    boolean corner=(x==cx-half||x==cx+half)&&(z==cz-half||z==cz+half);
                    queue.add(new Op(w,x,yy,z,corner?frame:wall));
                } else {
                    queue.add(new Op(w,x,yy,z,Material.AIR));
                }
            }
            queue.add(new Op(w,x,y+height+1,z,frame));
        }
    }

    private void doorway(World w,int cx,int y,int frontZ) {
        // Clear both sides of the wall so the complete 3x3 gate wall has a
        // guaranteed approach and never opens into a terrain/build artifact.
        for(int z=frontZ-2;z<=frontZ+2;z++) {
            for(int x=cx-1;x<=cx+1;x++) {
                for(int yy=y+1;yy<=y+3;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
            }
        }
        for(int x=cx-1;x<=cx+1;x++) {
            for(int yy=y+1;yy<=y+3;yy++)
                queue.add(new Op(w,x,yy,frontZ,Material.FENCE_GATE,(byte)0));
        }
    }

    private void doorwayX(World w,int frontX,int y,int cz) {
        for(int x=frontX-2;x<=frontX+2;x++) {
            for(int z=cz-1;z<=cz+1;z++) {
                for(int yy=y+1;yy<=y+3;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
            }
        }
        // Data 1 aligns a gate on the east/west wall in legacy 1.8 metadata.
        for(int z=cz-1;z<=cz+1;z++) {
            for(int yy=y+1;yy<=y+3;yy++)
                queue.add(new Op(w,frontX,yy,z,Material.FENCE_GATE,(byte)1));
        }
    }

    private int frontZForPreset(String preset,int cz) {
        int half=12;
        if ("hcf_courtyard".equalsIgnoreCase(preset)) half=14;
        else if ("hcf_compact_2015".equalsIgnoreCase(preset)) half=9;
        else if ("hcf_split_level".equalsIgnoreCase(preset)) half=11;
        else if ("hcf_archer_tower".equalsIgnoreCase(preset)) half=10;
        else if ("hcf_double_layer".equalsIgnoreCase(preset)) half=13;
        return cz-half;
    }

    private void clearHomePocket(World w,int cx,int y,int cz) {
        for(int x=cx-1;x<=cx+1;x++) {
            for(int z=cz-1;z<=cz+1;z++) {
                for(int yy=y+1;yy<=y+3;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
            }
        }
    }

    private boolean intersectsPlayer(Op op) {
        for(org.bukkit.entity.Player p:op.world.getPlayers()) {
            Location l=p.getLocation();
            int px=l.getBlockX();
            int pz=l.getBlockZ();
            int py=l.getBlockY();
            if(px==op.x && pz==op.z && (op.y==py || op.y==py+1)) return true;
        }
        return false;
    }

    private void rescueEmbeddedPlayers(World w,int cx,int y,int cz,int radius) {
        Location safe=new Location(w,cx+0.5,y+1.0,cz+0.5);
        // Make the emergency pocket immediately safe before queued repair ops run.
        w.getBlockAt(cx,y+1,cz).setType(Material.AIR);
        w.getBlockAt(cx,y+2,cz).setType(Material.AIR);
        w.getBlockAt(cx,y+3,cz).setType(Material.AIR);
        for(org.bukkit.entity.Player p:w.getPlayers()) {
            Location l=p.getLocation();
            if(Math.abs(l.getX()-cx)>radius+2 || Math.abs(l.getZ()-cz)>radius+2) continue;
            Material feet=w.getBlockAt(l.getBlockX(),l.getBlockY(),l.getBlockZ()).getType();
            Material head=w.getBlockAt(l.getBlockX(),l.getBlockY()+1,l.getBlockZ()).getType();
            if(feet.isSolid() || head.isSolid()) p.teleport(safe);
        }
    }

    private Material factionAccent(String faction) {
        Material[] palette=new Material[]{
            Material.NETHER_BRICK, Material.BRICK, Material.QUARTZ_BLOCK,
            Material.MOSSY_COBBLESTONE, Material.SANDSTONE, Material.WOOD
        };
        int h=faction==null?0:faction.toLowerCase(java.util.Locale.ENGLISH).hashCode();
        return palette[Math.abs(h % palette.length)];
    }

    private void addDistinctExterior(World w,String faction,String preset,int cx,int y,int cz) {
        Material accent=factionAccent(faction);
        if ("hcf_courtyard".equalsIgnoreCase(preset)) {
            // Open courtyard: four visible corner standards and a low front arcade.
            for(int sx:new int[]{-12,12}) for(int sz:new int[]{-12,12}) {
                for(int yy=y+1;yy<=y+9;yy++) queue.add(new Op(w,cx+sx,yy,cz+sz,accent));
                queue.add(new Op(w,cx+sx,y+10,cz+sz,Material.GLOWSTONE));
            }
            for(int x=cx-9;x<=cx+9;x+=3) {
                queue.add(new Op(w,x,y+1,cz-15,Material.COBBLE_WALL));
                queue.add(new Op(w,x,y+2,cz-15,Material.FENCE));
            }
            return;
        }

        if ("hcf_brewer_base".equalsIgnoreCase(preset)) {
            // Brewer base: industrial side chimney and utility stripe.
            int bx=cx+10,bz=cz+7;
            for(int yy=y+1;yy<=y+13;yy++) {
                Material m=(yy%3==0)?Material.IRON_FENCE:accent;
                queue.add(new Op(w,bx,yy,bz,m));
            }
            for(int z=cz-8;z<=cz+8;z+=2)
                queue.add(new Op(w,cx+12,y+4,z,Material.GLOWSTONE));
            return;
        }

        if ("hcf_trap_base".equalsIgnoreCase(preset)) {
            // Trap house: aggressive front jaw around the gate, intentionally
            // asymmetric so it is recognizable from a chase.
            int front=cz-12;
            for(int x=cx-7;x<=cx+7;x+=2) {
                int h=2+Math.abs(x-cx)%4;
                for(int yy=y+1;yy<=y+h;yy++)
                    queue.add(new Op(w,x,yy,front-2,Material.OBSIDIAN));
            }
            for(int z=front-5;z<=front+1;z++)
                queue.add(new Op(w,cx+8,y+1,z,Material.IRON_FENCE));
            return;
        }

        if ("hcf_compact_2015".equalsIgnoreCase(preset)) {
            // Low bunker silhouette with crenellated roof and chunky corners.
            int half=9,roof=y+10;
            for(int x=cx-half;x<=cx+half;x+=2) {
                queue.add(new Op(w,x,roof,cz-half,accent));
                queue.add(new Op(w,x,roof,cz+half,accent));
            }
            for(int z=cz-half;z<=cz+half;z+=2) {
                queue.add(new Op(w,cx-half,roof,z,accent));
                queue.add(new Op(w,cx+half,roof,z,accent));
            }
            for(int yy=y+1;yy<=y+6;yy++) {
                queue.add(new Op(w,cx-10,yy,cz+6,accent));
                queue.add(new Op(w,cx+10,yy,cz+6,accent));
            }
            return;
        }

        if ("hcf_split_level".equalsIgnoreCase(preset)) {
            // Elevated east-side balcony and offset tower communicate the split floor.
            for(int x=cx+11;x<=cx+15;x++) for(int z=cz-6;z<=cz+6;z++)
                queue.add(new Op(w,x,y+5,z,Material.SMOOTH_BRICK));
            for(int z=cz-6;z<=cz+6;z++)
                queue.add(new Op(w,cx+15,y+6,z,Material.IRON_FENCE));
            for(int yy=y+1;yy<=y+12;yy++)
                queue.add(new Op(w,cx+14,yy,cz+7,yy%3==0?Material.GLASS:accent));
            return;
        }

        if ("hcf_archer_tower".equalsIgnoreCase(preset)) {
            // High parapets and firing rails exaggerate the vertical silhouette.
            for(int sx:new int[]{-7,7}) {
                int tx=cx+sx,tz=cz-7,top=y+14;
                for(int dx=-4;dx<=4;dx++) {
                    queue.add(new Op(w,tx+dx,top,tz-4,Material.COBBLE_WALL));
                    queue.add(new Op(w,tx+dx,top,tz+4,Material.COBBLE_WALL));
                }
                for(int dz=-4;dz<=4;dz++) {
                    queue.add(new Op(w,tx-4,top,tz+dz,Material.COBBLE_WALL));
                    queue.add(new Op(w,tx+4,top,tz+dz,Material.COBBLE_WALL));
                }
            }
            return;
        }

        if ("hcf_double_layer".equalsIgnoreCase(preset)) {
            // Heavy external ribs make the defensive double shell visually obvious.
            for(int x=cx-13;x<=cx+13;x+=6) {
                for(int yy=y+1;yy<=y+11;yy++) {
                    queue.add(new Op(w,x,yy,cz-14,accent));
                    queue.add(new Op(w,x,yy,cz+14,accent));
                }
            }
            for(int z=cz-13;z<=cz+13;z+=6) {
                for(int yy=y+1;yy<=y+11;yy++) {
                    queue.add(new Op(w,cx-14,yy,z,accent));
                    queue.add(new Op(w,cx+14,yy,z,accent));
                }
            }
            return;
        }

        // Default glass box: restrained corner braces + roof beacon instead of
        // sharing another preset's major silhouette.
        for(int yy=y+1;yy<=y+10;yy++) {
            queue.add(new Op(w,cx-13,yy,cz-13,accent));
            queue.add(new Op(w,cx+13,yy,cz-13,accent));
            queue.add(new Op(w,cx-13,yy,cz+13,accent));
            queue.add(new Op(w,cx+13,yy,cz+13,accent));
        }
        queue.add(new Op(w,cx,y+11,cz,Material.GLOWSTONE));
    }

    private void tower(World w,int cx,int y,int cz,int half,int height) {
        for(int x=cx-half;x<=cx+half;x++) for(int z=cz-half;z<=cz+half;z++) {
            for(int yy=y;yy<=y+height;yy++) {
                boolean edge=x==cx-half||x==cx+half||z==cz-half||z==cz+half;
                if(edge) queue.add(new Op(w,x,yy,z,yy%3==0?Material.SMOOTH_BRICK:Material.GLASS));
            }
            queue.add(new Op(w,x,y+height,z,Material.SMOOTH_BRICK));
        }
    }

    private void buildCaneFarm(World w,int cx,int y,int cz) {
        for(int x=cx-6;x<=cx+6;x++) {
            for(int z=cz-5;z<=cz+5;z++) {
                boolean water=((z-(cz-5))%4)==1;
                queue.add(new Op(w,x,y,z,water?Material.STATIONARY_WATER:Material.SAND));
                if(!water) {
                    queue.add(new Op(w,x,y+1,z,Material.SUGAR_CANE_BLOCK));
                    if((x+z)%3==0) queue.add(new Op(w,x,y+2,z,Material.SUGAR_CANE_BLOCK));
                }
            }
        }
    }

    private void buildCactusFarm(World w,int cx,int y,int cz) {
        for(int x=cx-6;x<=cx+6;x+=2) {
            for(int z=cz-5;z<=cz+5;z+=2) {
                queue.add(new Op(w,x,y,z,Material.SAND));
                queue.add(new Op(w,x,y+1,z,Material.CACTUS));
                if((x+z)%4==0) queue.add(new Op(w,x,y+2,z,Material.CACTUS));
            }
        }
    }

    private void buildPumpkinFarm(World w,int cx,int y,int cz) {
        for(int x=cx-6;x<=cx+6;x++) {
            for(int z=cz-5;z<=cz+5;z++) {
                boolean water=(x==cx);
                queue.add(new Op(w,x,y,z,water?Material.STATIONARY_WATER:Material.SOIL));
                if(!water && ((x+z)&1)==0) queue.add(new Op(w,x,y+1,z,Material.PUMPKIN_STEM,(byte)7));
                else if(!water) queue.add(new Op(w,x,y+1,z,Material.PUMPKIN));
            }
        }
    }

    private void buildMelonFarm(World w,int cx,int y,int cz) {
        for(int x=cx-6;x<=cx+6;x++) {
            for(int z=cz-5;z<=cz+5;z++) {
                boolean water=(x==cx);
                queue.add(new Op(w,x,y,z,water?Material.STATIONARY_WATER:Material.SOIL));
                if(!water && ((x+z)&1)==0) queue.add(new Op(w,x,y+1,z,Material.MELON_STEM,(byte)7));
                else if(!water) queue.add(new Op(w,x,y+1,z,Material.MELON_BLOCK));
            }
        }
    }

    private void buildBrewerRoom(World w,String preset,int cx,int y,int cz) {
        int[] core=anchor(preset,"brewer",cx,y,cz);
        int rx=core[0], rz=core[2];
        int halfX=7,halfZ=8;

        // The west wall overlaps the selected base's east wall exactly. The
        // brewery is therefore a real template wing, never a detached shed.
        prepareTerrainPad(w,rx,y,rz,halfX,halfZ);
        for(int x=rx-halfX;x<=rx+halfX;x++) {
            for(int z=rz-halfZ;z<=rz+halfZ;z++) {
                queue.add(new Op(w,x,y,z,Material.SMOOTH_BRICK));
                for(int yy=y+1;yy<=y+7;yy++) {
                    boolean wall=x==rx-halfX||x==rx+halfX||z==rz-halfZ||z==rz+halfZ||yy==y+7;
                    Material m=wall?Material.SMOOTH_BRICK:Material.AIR;
                    if(wall && yy>=y+2 && yy<=y+5 && (z==rz-halfZ||z==rz+halfZ))
                        m=Material.STAINED_GLASS;
                    queue.add(new Op(w,x,yy,z,m));
                }
            }
        }

        doorwayX(w,rx-halfX,y,rz);

        String[] labels={"Heal II A","Heal II B","Heal II C","Heal II D","Heal II E","Heal II F"};
        for(int i=0;i<6;i++) {
            int x=rx;
            int z=rz-5+i*2;

            // Real, readable lane:
            // bottle chest -> side hopper -> raised stand
            // ingredient chest -> top hopper -> stand
            // stand -> bottom hopper -> output chest
            queue.add(new Op(w,x,y+2,z,Material.BREWING_STAND));
            queue.add(new Op(w,x,y+3,z,Material.HOPPER,(byte)0));
            queue.add(new Op(w,x,y+4,z,Material.CHEST));

            queue.add(new Op(w,x-1,y+2,z,Material.HOPPER,(byte)5));
            queue.add(new Op(w,x-1,y+3,z,Material.CHEST));

            queue.add(new Op(w,x,y+1,z,Material.HOPPER,(byte)5));
            queue.add(new Op(w,x+1,y+1,z,Material.CHEST));

            queue.add(new Op(w,x+2,y+1,z,Material.SMOOTH_BRICK));
            queue.add(new Op(w,x+2,y+2,z,Material.SIGN_POST,(byte)8,labels[i]));

            // Lock/control spine, visually matching classic compact auto-brewers.
            queue.add(new Op(w,x-4,y+1,z,Material.SMOOTH_BRICK));
            queue.add(new Op(w,x-4,y+2,z,Material.REDSTONE_TORCH_ON));
            queue.add(new Op(w,x-3,y+1,z,Material.REDSTONE_WIRE));
        }

        doubleChest(w,rx-6,y+1,rz+7,"Ingredients");
        doubleChest(w,rx+4,y+1,rz+7,"Finished Pots");
        queue.add(new Op(w,rx,y+6,rz,Material.GLOWSTONE));
        queue.add(new Op(w,rx-6,y+5,rz,Material.GLOWSTONE));
        queue.add(new Op(w,rx+6,y+5,rz,Material.GLOWSTONE));
    }

    private void buildFenceGateBowTrap(World w,int cx,int y,int cz) {
        int bx=cx;
        int front=cz-18;

        // Narrow chase corridor with repeated fence-gate pinch points.
        for(int z=front-7;z<=front+7;z++) {
            for(int x=bx-2;x<=bx+2;x++) {
                queue.add(new Op(w,x,y,z,Material.SMOOTH_BRICK));
                if(x==bx-2||x==bx+2) {
                    queue.add(new Op(w,x,y+1,z,Material.SMOOTH_BRICK));
                    queue.add(new Op(w,x,y+2,z,Material.GLASS));
                } else {
                    queue.add(new Op(w,x,y+1,z,Material.AIR));
                    queue.add(new Op(w,x,y+2,z,Material.AIR));
                }
            }
        }
        for(int z=front-5;z<=front+5;z+=2) {
            queue.add(new Op(w,bx,y+1,z,Material.FENCE_GATE));
        }

        // Protected bow lane offset from the corridor.
        for(int z=front-6;z<=front+6;z++) {
            queue.add(new Op(w,bx+4,y,z,Material.SMOOTH_BRICK));
            queue.add(new Op(w,bx+4,y+1,z,Material.IRON_FENCE));
            queue.add(new Op(w,bx+5,y+1,z,Material.SMOOTH_BRICK));
        }
    }

    private void buildDropChute(World w,int cx,int y,int cz) {
        int tx=cx-10;
        int tz=cz-17;
        int bottom=Math.max(7,y-32);

        for(int x=tx-1;x<=tx+1;x++) {
            for(int z=tz-1;z<=tz+1;z++) {
                for(int yy=bottom;yy<=y;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
                queue.add(new Op(w,x,bottom-1,z,Material.OBSIDIAN));
            }
        }
        for(int x=tx-2;x<=tx+2;x++) {
            queue.add(new Op(w,x,y,tz-2,Material.SMOOTH_BRICK));
            queue.add(new Op(w,x,y,tz+2,Material.SMOOTH_BRICK));
        }
        for(int z=tz-2;z<=tz+2;z++) {
            queue.add(new Op(w,tx-2,y,z,Material.SMOOTH_BRICK));
            queue.add(new Op(w,tx+2,y,z,Material.SMOOTH_BRICK));
        }
        // Trapdoors make the lip look like a deliberate HCF drop entrance.
        queue.add(new Op(w,tx,y,tz-2,Material.TRAP_DOOR));
        queue.add(new Op(w,tx,y,tz+2,Material.TRAP_DOOR));
    }

    private void buildFallTrap(World w, int cx, int y, int cz) {
        int tx=cx+9;
        int tz=cz-16;
        int bottom=Math.max(6,y-42);

        // 5x5 drop outside the front-side approach. HCF-style trap factions get this more often.
        for(int x=tx-2;x<=tx+2;x++) {
            for(int z=tz-2;z<=tz+2;z++) {
                for(int yy=bottom;yy<=y;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
                queue.add(new Op(w,x,bottom-1,z,Material.OBSIDIAN));
            }
        }

        // Rim makes the trap visibly intentional but still easy to fall into during a chase.
        for(int x=tx-3;x<=tx+3;x++) {
            queue.add(new Op(w,x,y,tz-3,Material.SMOOTH_BRICK));
            queue.add(new Op(w,x,y,tz+3,Material.SMOOTH_BRICK));
        }
        for(int z=tz-3;z<=tz+3;z++) {
            queue.add(new Op(w,tx-3,y,z,Material.SMOOTH_BRICK));
            queue.add(new Op(w,tx+3,y,z,Material.SMOOTH_BRICK));
        }
    }
}
