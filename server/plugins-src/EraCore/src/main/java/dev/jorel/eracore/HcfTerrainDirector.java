package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Normalizes newly generated Overworld chunks into HCF-friendly terrain.
 *
 * Underground generation remains intact. The surface is rebuilt into flat spawn
 * terrain and very low rolling wilderness, eliminating mountains, floating
 * terrain, exposed surface ravines and tree/terrain overhangs before bases are
 * ever placed.
 */
@SuppressWarnings("deprecation")
final class HcfTerrainDirector implements Listener {
    private final EraCore plugin;

    HcfTerrainDirector(EraCore plugin) {
        this.plugin=plugin;
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent e) {
        if(!e.isNewChunk()) return;
        if(!plugin.getConfig().getBoolean("terrain.normalize-new-chunks",true)) return;
        if(e.getWorld().getEnvironment()!=World.Environment.NORMAL) return;

        final Chunk chunk=e.getChunk();
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run() {
                if(chunk.isLoaded()) normalize(chunk);
            }
        },1L);
    }

    private void normalize(Chunk chunk) {
        World w=chunk.getWorld();
        int base=plugin.getConfig().getInt("map.surface-y",63);
        int flatRadius=Math.max(180,plugin.getConfig().getInt("terrain.flat-radius",520));
        int amp=Math.max(0,Math.min(4,plugin.getConfig().getInt("terrain.low-relief-amplitude",2)));
        double scale=Math.max(0.001,plugin.getConfig().getDouble("terrain.relief-scale",0.012));
        int cleanup=Math.max(18,plugin.getConfig().getInt("terrain.cleanup-height",40));

        int sx=w.getSpawnLocation().getBlockX();
        int sz=w.getSpawnLocation().getBlockZ();

        for(int lx=0;lx<16;lx++) {
            for(int lz=0;lz<16;lz++) {
                int x=(chunk.getX()<<4)+lx;
                int z=(chunk.getZ()<<4)+lz;
                long dx=(long)x-sx,dz=(long)z-sz;
                boolean flat=dx*dx+dz*dz <= (long)flatRadius*flatRadius;

                int relief=0;
                if(!flat && amp>0) {
                    double a=Math.sin(x*scale)*0.58 + Math.cos(z*scale*0.83)*0.42;
                    relief=(int)Math.round(a*amp);
                }
                int target=Math.max(54,Math.min(76,base+relief));

                int highest=Math.min(w.getMaxHeight()-1,Math.max(target+cleanup,w.getHighestBlockYAt(x,z)+2));
                for(int y=target+1;y<=highest;y++) {
                    if(w.getBlockAt(x,y,z).getType()!=Material.AIR)
                        w.getBlockAt(x,y,z).setType(Material.AIR);
                }

                w.getBlockAt(x,target,z).setType(Material.GRASS);
                for(int y=target-3;y<target;y++) {
                    if(y>1) w.getBlockAt(x,y,z).setType(Material.DIRT);
                }

                for(int y=Math.max(2,target-8);y<=target-4;y++) {
                    Material m=w.getBlockAt(x,y,z).getType();
                    if(m==Material.AIR || isLiquid(m))
                        w.getBlockAt(x,y,z).setType(Material.STONE);
                }
            }
        }
    }

    private boolean isLiquid(Material m) {
        return m==Material.WATER || m==Material.STATIONARY_WATER ||
            m==Material.LAVA || m==Material.STATIONARY_LAVA;
    }
}
