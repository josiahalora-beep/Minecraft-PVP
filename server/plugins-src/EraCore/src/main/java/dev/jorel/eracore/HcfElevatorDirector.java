package dev.jorel.eracore;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Classic HCF sign elevator.  Generated bases use a dropdown to go down and an
 * elevator sign to return up.  HOT Mineflayer bodies can use the exact same
 * sign by activating the block.
 */
final class HcfElevatorDirector implements Listener {
    private final EraCore plugin;

    HcfElevatorDirector(EraCore plugin) {
        this.plugin=plugin;
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onInteract(PlayerInteractEvent e) {
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null) return;
        Block b=e.getClickedBlock();
        if(b.getType()!=Material.SIGN_POST && b.getType()!=Material.WALL_SIGN) return;
        if(!(b.getState() instanceof Sign)) return;

        Sign sign=(Sign)b.getState();
        String first=plain(sign.getLine(0));
        if(!"[elevator]".equalsIgnoreCase(first) && !"elevator".equalsIgnoreCase(first)) return;

        String direction=plain(sign.getLine(1));
        boolean down="down".equalsIgnoreCase(direction);
        boolean up=!down; // generated signs default to Up

        Location target=findSafe(b.getLocation(),up);
        if(target==null) {
            e.getPlayer().sendMessage(EraCore.colorText("&cNo safe elevator destination was found."));
            return;
        }

        Player p=e.getPlayer();
        p.leaveVehicle();
        p.setFallDistance(0f);
        p.teleport(target);
    }

    private Location findSafe(Location from,boolean up) {
        int x=from.getBlockX();
        int z=from.getBlockZ();
        int start=from.getBlockY();
        int max=from.getWorld().getMaxHeight()-3;

        if(up) {
            for(int y=start+2;y<=Math.min(max,start+96);y++) {
                Location found=safeNear(from,x,y,z);
                if(found!=null) return found;
            }
        } else {
            for(int y=start-2;y>=Math.max(2,start-96);y--) {
                Location found=safeNear(from,x,y,z);
                if(found!=null) return found;
            }
        }
        return null;
    }

    private Location safeNear(Location origin,int x,int y,int z) {
        int[][] offsets={{0,0},{1,0},{-1,0},{0,1},{0,-1}};
        for(int[] o:offsets) {
            Block feet=origin.getWorld().getBlockAt(x+o[0],y,z+o[1]);
            Block head=origin.getWorld().getBlockAt(x+o[0],y+1,z+o[1]);
            Block floor=origin.getWorld().getBlockAt(x+o[0],y-1,z+o[1]);
            if(!feet.getType().isSolid() && !head.getType().isSolid() && floor.getType().isSolid()) {
                return new Location(origin.getWorld(),x+o[0]+0.5,y,z+o[1]+0.5,origin.getYaw(),0f);
            }
        }
        return null;
    }

    private String plain(String s) {
        String out=ChatColor.stripColor(s==null?"":s);
        return out==null?"":out.trim();
    }
}
