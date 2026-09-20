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

        if ("hcf_courtyard".equalsIgnoreCase(preset)) buildCourtyard(world,cx,y,cz);
        else if ("hcf_brewer_base".equalsIgnoreCase(preset)) buildGlassBox(world,cx,y,cz,true);
        else if ("hcf_trap_base".equalsIgnoreCase(preset)) buildGlassBox(world,cx,y,cz,false);
        else buildGlassBox(world,cx,y,cz,false);

        if ("fall_trap".equalsIgnoreCase(trapPreset)) buildFallTrap(world,cx,y,cz);
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
