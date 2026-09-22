package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Treats touching fence gates as one logical HCF gate wall.
 *
 * Base templates deliberately use connected 3x3 gate grids. One click opens the
 * whole doorway and every open group is closed automatically after passage so a
 * faction base is not left exposed by a worker, human, or interrupted action.
 */
@SuppressWarnings("deprecation")
final class HcfGateDirector implements Listener {
    private final EraCore plugin;

    HcfGateDirector(EraCore plugin) {
        this.plugin=plugin;
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=false)
    public void onInteract(PlayerInteractEvent e) {
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null) return;
        Block clicked=e.getClickedBlock();
        if(clicked.getType()!=Material.FENCE_GATE) return;

        List<Block> group=connectedGateGroup(clicked);
        if(group.isEmpty()) return;

        boolean opening=!isOpen(clicked);
        e.setCancelled(true);
        setGroupOpen(group,opening);

        if(opening) scheduleClose(group);
    }

    private List<Block> connectedGateGroup(Block start) {
        List<Block> out=new ArrayList<Block>();
        ArrayDeque<Block> q=new ArrayDeque<Block>();
        Set<String> seen=new HashSet<String>();
        q.add(start);

        final int[][] dirs={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while(!q.isEmpty() && out.size()<36) {
            Block b=q.removeFirst();
            String k=b.getX()+":"+b.getY()+":"+b.getZ();
            if(!seen.add(k) || b.getType()!=Material.FENCE_GATE) continue;
            out.add(b);

            for(int[] d:dirs) {
                Block n=b.getRelative(d[0],d[1],d[2]);
                if(n.getType()==Material.FENCE_GATE) q.addLast(n);
            }
        }
        return out;
    }

    private boolean isOpen(Block b) {
        return (b.getData() & 0x4) != 0;
    }

    private void setGroupOpen(List<Block> group,boolean open) {
        for(Block b:group) {
            if(b.getType()!=Material.FENCE_GATE) continue;
            byte data=b.getData();
            data=open ? (byte)(data | 0x4) : (byte)(data & ~0x4);
            b.setData(data,true);
        }
    }

    private void scheduleClose(List<Block> group) {
        if(group.isEmpty()) return;
        final World w=group.get(0).getWorld();
        final int[][] coords=new int[group.size()][3];
        for(int i=0;i<group.size();i++) {
            Block b=group.get(i);
            coords[i][0]=b.getX();
            coords[i][1]=b.getY();
            coords[i][2]=b.getZ();
        }

        long delay=Math.max(12L,plugin.getConfig().getLong("base-builder.gate-auto-close-ticks",30L));
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run() {
                List<Block> current=new ArrayList<Block>();
                for(int[] c:coords) {
                    Block b=w.getBlockAt(c[0],c[1],c[2]);
                    if(b.getType()==Material.FENCE_GATE) current.add(b);
                }
                setGroupOpen(current,false);
            }
        },delay);
    }
}
