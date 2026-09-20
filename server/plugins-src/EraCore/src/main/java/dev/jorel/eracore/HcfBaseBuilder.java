package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
        Op(World world, int x, int y, int z, Material material) {
            this(world,x,y,z,material,(byte)0);
        }
        Op(World world, int x, int y, int z, Material material, byte data) {
            this.world=world; this.x=x; this.y=y; this.z=z; this.material=material; this.data=data;
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

        if ("fall_trap".equalsIgnoreCase(trapPreset)) buildFallTrap(world,cx,y,cz);
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
        if ("fall_trap".equalsIgnoreCase(trapPreset)) r=Math.max(r,20);
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
                    Block b = op.world.getBlockAt(op.x,op.y,op.z);
                    b.setType(op.material);
                    if (op.data != 0) b.setData(op.data);
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

        // Front entrance.
        for (int yy=y+1;yy<=y+3;yy++) {
            for (int x=cx-1;x<=cx+1;x++) queue.add(new Op(w,x,yy,cz-half,Material.AIR));
        }

        // Inner secure room / panic room.
        for (int x=cx-4;x<=cx+4;x++) {
            for (int z=cz-4;z<=cz+4;z++) {
                for (int yy=y+1;yy<=y+5;yy++) {
                    boolean wall=x==cx-4||x==cx+4||z==cz-4||z==cz+4||yy==y+5;
                    if (wall) queue.add(new Op(w,x,yy,z,Material.SMOOTH_BRICK));
                }
            }
        }
        for (int yy=y+1;yy<=y+2;yy++) queue.add(new Op(w,cx,yy,cz-4,Material.AIR));

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
        for(int yy=y+1;yy<=y+2;yy++) queue.add(new Op(w,cx,yy,cz-inner,Material.AIR));
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
        for(int yy=y+1;yy<=y+3;yy++) for(int x=cx-1;x<=cx+1;x++)
            queue.add(new Op(w,x,yy,frontZ,Material.AIR));
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
