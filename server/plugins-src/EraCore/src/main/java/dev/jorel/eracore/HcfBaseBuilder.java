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
    private BukkitRunnable runner;

    HcfBaseBuilder(EraCore plugin) {
        this.plugin = plugin;
    }

    void queueBase(String faction, String preset, String trapPreset, int cx, int y, int cz) {
        String key = "base:" + faction.toLowerCase();
        if (!completed.add(key)) return;

        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        int radius = basePadRadius(preset, trapPreset);
        prepareTerrainPad(world,cx,y,cz,radius,radius);

        if ("hcf_courtyard".equalsIgnoreCase(preset)) buildCourtyard(world,cx,y,cz);
        else if ("hcf_brewer_base".equalsIgnoreCase(preset)) buildGlassBox(world,cx,y,cz,true);
        else if ("hcf_trap_base".equalsIgnoreCase(preset)) buildTrapHouse(world,cx,y,cz);
        else if ("hcf_compact_2015".equalsIgnoreCase(preset)) buildCompact2015(world,cx,y,cz);
        else if ("hcf_split_level".equalsIgnoreCase(preset)) buildSplitLevel(world,cx,y,cz);
        else if ("hcf_archer_tower".equalsIgnoreCase(preset)) buildArcherTower(world,cx,y,cz);
        else if ("hcf_double_layer".equalsIgnoreCase(preset)) buildDoubleLayer(world,cx,y,cz);
        else buildGlassBox(world,cx,y,cz,false);

        addDistinctExterior(world,faction,preset,cx,y,cz);
        buildOrganizedVault(world,cx,y,cz);

        if ("fall_trap".equalsIgnoreCase(trapPreset)) buildFallTrap(world,cx,y,cz);
        else if ("fence_gate_bow".equalsIgnoreCase(trapPreset)) buildFenceGateBowTrap(world,cx,y,cz);
        else if ("drop_chute".equalsIgnoreCase(trapPreset)) buildDropChute(world,cx,y,cz);
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

        String type = crop == null ? "cane" : crop.toLowerCase();
        int fx=cx-18, fz=cz+14;
        prepareTerrainPad(world,fx,y,fz,7,7);
        if ("cactus".equals(type)) buildCactusFarm(world,fx,y,fz);
        else if ("pumpkin".equals(type)) buildPumpkinFarm(world,fx,y,fz);
        else if ("melon".equals(type)) buildMelonFarm(world,fx,y,fz);
        else buildCaneFarm(world,fx,y,fz);
        ensureRunner();
    }

    void queueBrewer(String faction, int cx, int y, int cz) {
        String key = "brewer:" + faction.toLowerCase();
        if (!completed.add(key)) return;
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;
        buildBrewerRoom(world,cx,y,cz);
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

        int radius = basePadRadius(preset,trapPreset);
        fillFoundationOnly(world,cx,y,cz,radius,radius);

        // Repairs must never rebuild live walls around online players. Only fill
        // missing support below grade, retrofit a guaranteed walkable home pocket,
        // and install/clear the canonical fence-gate entrance.
        clearHomePocket(world,cx,y,cz);
        doorway(world,cx,y,frontZForPreset(preset,cz));
        addDistinctExterior(world,faction,preset,cx,y,cz);
        buildOrganizedVault(world,cx,y,cz);
        rescueEmbeddedPlayers(world,cx,y,cz,radius);
        ensureRunner();
    }

    private void fillFoundationOnly(World w,int cx,int y,int cz,int rx,int rz) {
        for(int x=cx-rx;x<=cx+rx;x++) {
            for(int z=cz-rz;z<=cz+rz;z++) {
                int surface=solidSurfaceY(w,x,z);
                if(surface>=y-1) continue;

                int from=Math.max(2,surface+1);
                for(int yy=from;yy<y;yy++) {
                    Material fill=(yy>=y-3)?Material.DIRT:Material.STONE;
                    queue.add(new Op(w,x,yy,z,fill));
                }
            }
        }
    }

    void queueTerrainRepair(String faction, String preset, String trapPreset, int cx, int y, int cz) {
        String key = "terrain:" + faction.toLowerCase();
        if (!completed.add(key)) return;
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        int radius = basePadRadius(preset,trapPreset);
        prepareTerrainPad(world,cx,y,cz,radius,radius);
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
        return r;
    }

    /**
     * Terraform first, build second.
     *
     * Every column becomes solid through target Y and clear for 16 blocks above
     * grade. This prevents floating floors, terrain clipping through walls and
     * trees/leaves being trapped inside bases.
     */
    private void prepareTerrainPad(World w,int cx,int y,int cz,int rx,int rz) {
        int clearTop=Math.min(w.getMaxHeight()-1,y+16);
        for(int x=cx-rx;x<=cx+rx;x++) {
            for(int z=cz-rz;z<=cz+rz;z++) {
                int surface=solidSurfaceY(w,x,z);

                // Cut hills and vegetation above grade.
                if(surface>y) {
                    for(int yy=y+1;yy<=Math.min(clearTop,surface+6);yy++)
                        queue.add(new Op(w,x,yy,z,Material.AIR));
                } else {
                    // Still clear tree canopies / overhangs above a low surface.
                    for(int yy=y+1;yy<=clearTop;yy++) {
                        Material m=w.getBlockAt(x,yy,z).getType();
                        if(isVegetationOrLiquid(m)) queue.add(new Op(w,x,yy,z,Material.AIR));
                    }
                }

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

    void stop() {
        if (runner != null) runner.cancel();
        runner = null;
        queue.clear();
    }

    private void ensureRunner() {
        if (runner != null) return;
        runner = new BukkitRunnable() {
            public void run() {
                int budget = Math.max(100, plugin.getConfig().getInt("base-builder.blocks-per-tick", 450));
                int n = 0;
                while (n < budget && !queue.isEmpty()) {
                    Op op = queue.poll();
                    // Never materialize a solid repair/build block through a live
                    // player's feet or head. Skipping one cosmetic/support block is
                    // preferable to suffocating or trapping a player in a wall.
                    if (op.material != Material.AIR && intersectsPlayer(op)) {
                        n++;
                        continue;
                    }
                    Block b = op.world.getBlockAt(op.x,op.y,op.z);
                    b.setType(op.material);
                    if (op.data != 0) b.setData(op.data);
                    if (op.label != null && b.getState() instanceof Sign) {
                        Sign sign=(Sign)b.getState();
                        sign.setLine(0, op.label.length()>15 ? op.label.substring(0,15) : op.label);
                        sign.update(true);
                    }
                    n++;
                }
                if (queue.isEmpty()) {
                    cancel();
                    runner = null;
                }
            }
        };
        runner.runTaskTimer(plugin,1L,1L);
    }

    private void buildOrganizedVault(World w,int cx,int y,int cz) {
        // Every faction gets the same readable storage convention even though
        // the surrounding base preset is different. Chests are spaced so they
        // remain singles rather than merging into accidental doubles.
        String[] north={"Pots","Pearls","Valuables","Blocks","Brewing","Farm","Overflow"};
        String[] south={"Helmets","Chestplates","Leggings","Boots","Swords","Bows","Kits"};
        int[] xs={-6,-4,-2,0,2,4,6};

        // Clear a compact rear vault lane; never overwrite a live player because
        // the runner's occupancy guard still applies to every queued block.
        for(int x=cx-7;x<=cx+7;x++) {
            for(int z=cz+5;z<=cz+9;z++) {
                queue.add(new Op(w,x,y,z,Material.SMOOTH_BRICK));
                for(int yy=y+1;yy<=y+3;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
            }
        }

        for(int i=0;i<xs.length;i++) {
            int x=cx+xs[i];

            queue.add(new Op(w,x,y+1,cz+6,Material.CHEST));
            queue.add(new Op(w,x,y+2,cz+6,Material.SIGN_POST,(byte)8,north[i]));

            queue.add(new Op(w,x,y+1,cz+8,Material.CHEST));
            queue.add(new Op(w,x,y+2,cz+8,Material.SIGN_POST,(byte)0,south[i]));
        }

        // Lighting keeps the vault usable without making every base look like
        // the same glass box.
        queue.add(new Op(w,cx-7,y+2,cz+7,Material.GLOWSTONE));
        queue.add(new Op(w,cx+7,y+2,cz+7,Material.GLOWSTONE));
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

        if (brewerWing) buildBrewerRoom(w,cx,y,cz);
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
        // Clear both sides of the wall so a gate can never open into a solid
        // block, hill remnant, chest, or repair artifact.
        for(int z=frontZ-2;z<=frontZ+2;z++) {
            for(int x=cx-1;x<=cx+1;x++) {
                for(int yy=y+1;yy<=y+3;yy++) queue.add(new Op(w,x,yy,z,Material.AIR));
            }
        }
        // Three gates provide a proper HCF entrance rather than an unprotected
        // hole in the shell. Data 0 is a valid north/south gate orientation in 1.8.
        for(int x=cx-1;x<=cx+1;x++) queue.add(new Op(w,x,y+1,frontZ,Material.FENCE_GATE,(byte)0));
        for(int x=cx-1;x<=cx+1;x++) {
            queue.add(new Op(w,x,y+2,frontZ,Material.AIR));
            queue.add(new Op(w,x,y+3,frontZ,Material.AIR));
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

    private void buildBrewerRoom(World w, int cx, int y, int cz) {
        int bx=cx+6, bz=cz-6;

        // 6-station compact HCF-style brewer wall: ingredient/input chest -> hoppers -> stands -> output hoppers/chest.
        queue.add(new Op(w,bx-1,y+1,bz,Material.CHEST));
        queue.add(new Op(w,bx+6,y+1,bz,Material.CHEST));

        for(int i=0;i<6;i++) {
            int x=bx+i;
            queue.add(new Op(w,x,y+1,bz+1,Material.HOPPER));
            queue.add(new Op(w,x,y+2,bz+1,Material.BREWING_STAND));
            queue.add(new Op(w,x,y+3,bz+1,Material.HOPPER));
            queue.add(new Op(w,x,y+4,bz+1,Material.CHEST));
        }

        // Simple clock/visual redstone spine.
        for(int i=0;i<6;i++) {
            queue.add(new Op(w,bx+i,y+1,bz+3,Material.REDSTONE_BLOCK));
            queue.add(new Op(w,bx+i,y+2,bz+3,Material.SMOOTH_BRICK));
        }
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
