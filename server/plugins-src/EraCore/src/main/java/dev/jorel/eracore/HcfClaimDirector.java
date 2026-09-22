package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.util.*;

/**
 * Classic HCF rectangular claims.
 *
 * Historical HCF cores used two-corner claim-wand selection, not a collection
 * of independently claimed chunks.  Claims protect the full vertical column,
 * while their X/Z rectangle stays exact and easy to reason about.
 */
final class HcfClaimDirector {
    static final class RectClaim {
        String faction;
        String world;
        int minX,maxX,minZ,maxZ;

        boolean contains(Location l) {
            return l!=null && l.getWorld()!=null && world.equalsIgnoreCase(l.getWorld().getName()) &&
                l.getBlockX()>=minX && l.getBlockX()<=maxX &&
                l.getBlockZ()>=minZ && l.getBlockZ()<=maxZ;
        }

        boolean overlaps(String otherWorld,int ox1,int ox2,int oz1,int oz2) {
            if(!world.equalsIgnoreCase(otherWorld)) return false;
            return minX<=ox2 && maxX>=ox1 && minZ<=oz2 && maxZ>=oz1;
        }

        int width(){return maxX-minX+1;}
        int length(){return maxZ-minZ+1;}
        int area(){return width()*length();}
        int centerX(){return (minX+maxX)/2;}
        int centerZ(){return (minZ+maxZ)/2;}
    }

    private static final class Selection {
        Location a,b;
    }

    private final EraCore plugin;
    private final HcfMapDirector map;
    private final File file;
    private final YamlConfiguration data;
    private final Map<String,RectClaim> claims=new LinkedHashMap<String,RectClaim>();
    private final Map<UUID,Selection> selections=new HashMap<UUID,Selection>();
    private final Map<UUID,String> lastTerritory=new HashMap<UUID,String>();

    HcfClaimDirector(EraCore plugin,HcfMapDirector map) {
        this.plugin=plugin;
        this.map=map;
        this.file=new File(plugin.getDataFolder(),"claims-v2.yml");
        this.data=YamlConfiguration.loadConfiguration(file);
        load();
    }

    void stop() {
        save();
        selections.clear();
        lastTerritory.clear();
    }

    RectClaim claim(String faction) {
        if(faction==null) return null;
        return claims.get(key(faction));
    }

    RectClaim ownerClaimAt(Location l) {
        if(l==null || l.getWorld()==null) return null;
        for(RectClaim c:claims.values()) if(c.contains(l)) return c;
        return null;
    }

    String ownerAt(Location l) {
        RectClaim c=ownerClaimAt(l);
        return c==null?"":c.faction;
    }

    boolean factionOwns(String faction,Location l) {
        RectClaim c=claim(faction);
        return c!=null && c.contains(l);
    }

    String describe(String faction) {
        RectClaim c=claim(faction);
        if(c==null) return "none";
        return c.world+" ["+c.minX+","+c.minZ+" -> "+c.maxX+","+c.maxZ+"] "+
            c.width()+"x"+c.length();
    }

    boolean clearFactionClaim(String faction) {
        RectClaim removed=claims.remove(key(faction));
        if(removed!=null) save();
        return removed!=null;
    }

    boolean setFactionClaim(String faction,World world,int x1,int x2,int z1,int z2) {
        if(faction==null || faction.trim().isEmpty() || world==null) return false;
        int minX=Math.min(x1,x2),maxX=Math.max(x1,x2);
        int minZ=Math.min(z1,z2),maxZ=Math.max(z1,z2);

        for(RectClaim other:claims.values()) {
            if(other.faction.equalsIgnoreCase(faction)) continue;
            if(other.overlaps(world.getName(),minX,maxX,minZ,maxZ)) return false;
        }
        if(map!=null && !map.canClaimRect(world,minX,maxX,minZ,maxZ)) return false;

        RectClaim c=new RectClaim();
        c.faction=faction;
        c.world=world.getName();
        c.minX=minX;c.maxX=maxX;c.minZ=minZ;c.maxZ=maxZ;
        claims.put(key(faction),c);
        save();
        return true;
    }

    /**
     * One-time migration for old chunk claims.  The rectangular envelope
     * preserves the old protected footprint until a faction reclaims normally.
     */
    void importLegacy(String faction,Collection<String> chunks) {
        if(faction==null || chunks==null || chunks.isEmpty() || claim(faction)!=null) return;
        String world=null;
        int minCx=Integer.MAX_VALUE,maxCx=Integer.MIN_VALUE,minCz=Integer.MAX_VALUE,maxCz=Integer.MIN_VALUE;
        for(String raw:chunks) {
            if(raw==null) continue;
            String[] p=raw.split(":");
            if(p.length<3) continue;
            try {
                if(world==null) world=p[0];
                if(!world.equalsIgnoreCase(p[0])) continue;
                int cx=Integer.parseInt(p[1]),cz=Integer.parseInt(p[2]);
                minCx=Math.min(minCx,cx);maxCx=Math.max(maxCx,cx);
                minCz=Math.min(minCz,cz);maxCz=Math.max(maxCz,cz);
            } catch(Exception ignored){}
        }
        World w=world==null?null:Bukkit.getWorld(world);
        if(w==null || minCx==Integer.MAX_VALUE) return;
        setFactionClaim(faction,w,minCx*16,maxCx*16+15,minCz*16,maxCz*16+15);
    }

    ItemStack claimWand() {
        ItemStack wand=new ItemStack(Material.GOLD_HOE,1);
        ItemMeta meta=wand.getItemMeta();
        meta.setDisplayName(EraCore.colorText("&6Claim Wand"));
        List<String> lore=new ArrayList<String>();
        lore.add(EraCore.colorText("&7Left-click: &afirst corner"));
        lore.add(EraCore.colorText("&7Right-click: &asecond corner"));
        lore.add(EraCore.colorText("&7Then: &f/f claim confirm"));
        lore.add(EraCore.colorText("&8Classic two-corner HCF claim"));
        meta.setLore(lore);
        wand.setItemMeta(meta);
        return wand;
    }

    boolean isClaimWand(ItemStack item) {
        if(item==null || item.getType()!=Material.GOLD_HOE || !item.hasItemMeta()) return false;
        ItemMeta meta=item.getItemMeta();
        if(meta==null || !meta.hasDisplayName()) return false;
        String name=ChatColor.stripColor(meta.getDisplayName());
        return name!=null && name.equalsIgnoreCase("Claim Wand");
    }

    boolean handleWand(PlayerInteractEvent e,String faction,boolean allowed) {
        if(e==null || e.getPlayer()==null || !isClaimWand(e.getItem())) return false;
        e.setCancelled(true);
        Player p=e.getPlayer();
        if(!allowed || faction==null || faction.isEmpty()) {
            p.sendMessage(EraCore.colorText("&cOnly the faction leader can edit the claim."));
            return true;
        }

        Action action=e.getAction();
        if(action==Action.RIGHT_CLICK_AIR) {
            selections.remove(p.getUniqueId());
            p.sendMessage(EraCore.colorText("&7Claim selection cleared."));
            return true;
        }
        if(e.getClickedBlock()==null) return true;
        if(action!=Action.LEFT_CLICK_BLOCK && action!=Action.RIGHT_CLICK_BLOCK) return true;

        Location l=e.getClickedBlock().getLocation();
        Selection s=selections.get(p.getUniqueId());
        if(s==null){s=new Selection();selections.put(p.getUniqueId(),s);}
        if(action==Action.LEFT_CLICK_BLOCK) {
            s.a=l;
            p.sendMessage(EraCore.colorText("&aClaim corner 1: &f"+l.getBlockX()+", "+l.getBlockZ()));
        } else {
            s.b=l;
            p.sendMessage(EraCore.colorText("&aClaim corner 2: &f"+l.getBlockX()+", "+l.getBlockZ()));
        }
        if(s.a!=null && s.b!=null) {
            int w=Math.abs(s.a.getBlockX()-s.b.getBlockX())+1;
            int d=Math.abs(s.a.getBlockZ()-s.b.getBlockZ())+1;
            p.sendMessage(EraCore.colorText("&7Selection: &f"+w+"x"+d+" &7("+w*d+
                " blocks). Use &f/f claim confirm&7."));
        }
        return true;
    }

    boolean giveWand(Player p) {
        if(p==null) return false;
        p.getInventory().addItem(claimWand());
        p.updateInventory();
        p.sendMessage(EraCore.colorText("&6Claiming &8» &7Left/right-click opposite corners with the Claim Wand."));
        p.sendMessage(EraCore.colorText("&7Your rectangle should cover the complete base plus a small outside buffer."));
        return true;
    }

    boolean confirmSelection(Player p,String faction) {
        Selection s=selections.get(p.getUniqueId());
        if(s==null || s.a==null || s.b==null) {
            p.sendMessage(EraCore.colorText("&cSelect both corners first with /f claim."));
            return false;
        }
        if(s.a.getWorld()!=s.b.getWorld()) {
            p.sendMessage(EraCore.colorText("&cBoth claim corners must be in the same world."));
            return false;
        }

        int minX=Math.min(s.a.getBlockX(),s.b.getBlockX());
        int maxX=Math.max(s.a.getBlockX(),s.b.getBlockX());
        int minZ=Math.min(s.a.getBlockZ(),s.b.getBlockZ());
        int maxZ=Math.max(s.a.getBlockZ(),s.b.getBlockZ());
        int width=maxX-minX+1,length=maxZ-minZ+1;
        int minWidth=Math.max(4,plugin.getConfig().getInt("claims.min-width",12));
        int maxWidth=Math.max(minWidth,plugin.getConfig().getInt("claims.max-width",110));

        if(width<minWidth || length<minWidth) {
            p.sendMessage(EraCore.colorText("&cClaim is too small. Minimum is &f"+minWidth+"x"+minWidth+"&c."));
            return false;
        }
        if(width>maxWidth || length>maxWidth) {
            p.sendMessage(EraCore.colorText("&cClaim is too large. Maximum side length is &f"+maxWidth+"&c."));
            return false;
        }
        if(map!=null && !map.canClaimRect(s.a.getWorld(),minX,maxX,minZ,maxZ)) {
            p.sendMessage(EraCore.colorText("&cThis rectangle intersects protected land. &7"+
                map.claimReasonRect(s.a.getWorld(),minX,maxX,minZ,maxZ)));
            return false;
        }

        for(RectClaim other:claims.values()) {
            if(other.faction.equalsIgnoreCase(faction)) continue;
            if(other.overlaps(s.a.getWorld().getName(),minX,maxX,minZ,maxZ)) {
                p.sendMessage(EraCore.colorText("&cYour selection overlaps &f"+other.faction+"&c."));
                return false;
            }
        }

        if(!setFactionClaim(faction,s.a.getWorld(),minX,maxX,minZ,maxZ)) {
            p.sendMessage(EraCore.colorText("&cCould not create that claim."));
            return false;
        }
        selections.remove(p.getUniqueId());
        p.sendMessage(EraCore.colorText("&aClaim created: &f"+width+"x"+length+
            " &7["+minX+","+minZ+" -> "+maxX+","+maxZ+"]"));
        return true;
    }

    void showMap(Player p) {
        if(p==null || p.getWorld()==null) return;
        List<RectClaim> nearby=new ArrayList<RectClaim>();
        for(RectClaim c:claims.values()) {
            if(!c.world.equalsIgnoreCase(p.getWorld().getName())) continue;
            int dx=Math.max(0,Math.max(c.minX-p.getLocation().getBlockX(),p.getLocation().getBlockX()-c.maxX));
            int dz=Math.max(0,Math.max(c.minZ-p.getLocation().getBlockZ(),p.getLocation().getBlockZ()-c.maxZ));
            if(dx*dx+dz*dz<=512*512) nearby.add(c);
        }
        Collections.sort(nearby,new Comparator<RectClaim>() {
            public int compare(RectClaim a,RectClaim b) {
                int ax=a.centerX()-p.getLocation().getBlockX(),az=a.centerZ()-p.getLocation().getBlockZ();
                int bx=b.centerX()-p.getLocation().getBlockX(),bz=b.centerZ()-p.getLocation().getBlockZ();
                return (ax*ax+az*az)-(bx*bx+bz*bz);
            }
        });

        p.sendMessage(EraCore.colorText("&6--- Nearby HCF Claims ---"));
        if(nearby.isEmpty()) {
            p.sendMessage(EraCore.colorText("&7No faction claims within 512 blocks."));
            return;
        }
        String own=plugin.factionNameFor(p.getName());
        for(RectClaim c:nearby) {
            String color=c.faction.equalsIgnoreCase(own)?"&a":(plugin.factionRaidable(c.faction)?"&c":"&e");
            p.sendMessage(EraCore.colorText(color+c.faction+" &7"+c.width()+"x"+c.length()+
                " &8["+c.minX+","+c.minZ+" -> "+c.maxX+","+c.maxZ+"]"));
        }
    }

    void onMove(Player p,Location from,Location to) {
        if(p==null || to==null || from==null || from.getWorld()!=to.getWorld()) return;
        if(from.getBlockX()==to.getBlockX() && from.getBlockZ()==to.getBlockZ()) return;

        RectClaim c=ownerClaimAt(to);
        String next=c==null?"":key(c.faction);
        String prior=lastTerritory.put(p.getUniqueId(),next);
        if(prior==null) {
            RectClaim fromClaim=ownerClaimAt(from);
            prior=fromClaim==null?"":key(fromClaim.faction);
        }
        if(next.equals(prior)) return;

        if(c==null) {
            try {p.sendTitle(EraCore.colorText("&7Wilderness"),EraCore.colorText("&8Build and PvP territory"));} catch(Throwable ignored){}
            return;
        }

        String own=plugin.factionNameFor(p.getName());
        boolean friendly=c.faction.equalsIgnoreCase(own);
        boolean raidable=plugin.factionRaidable(c.faction);
        String title=friendly?"&a"+c.faction:(raidable?"&c"+c.faction:"&e"+c.faction);
        String sub=friendly?"&7Your faction claim":(raidable?"&cRAIDABLE":"&7Faction Territory");
        try {p.sendTitle(EraCore.colorText(title),EraCore.colorText(sub));} catch(Throwable ignored){}
        p.sendMessage(EraCore.colorText("&8[Territory] "+title+" &7- "+sub));
    }

    void onQuit(Player p) {
        if(p==null) return;
        selections.remove(p.getUniqueId());
        lastTerritory.remove(p.getUniqueId());
    }

    private void load() {
        claims.clear();
        if(!data.isConfigurationSection("claims")) return;
        for(String k:data.getConfigurationSection("claims").getKeys(false)) {
            String b="claims."+k;
            RectClaim c=new RectClaim();
            c.faction=data.getString(b+".faction",k);
            c.world=data.getString(b+".world","world");
            c.minX=data.getInt(b+".min-x");
            c.maxX=data.getInt(b+".max-x");
            c.minZ=data.getInt(b+".min-z");
            c.maxZ=data.getInt(b+".max-z");
            if(c.minX>c.maxX){int t=c.minX;c.minX=c.maxX;c.maxX=t;}
            if(c.minZ>c.maxZ){int t=c.minZ;c.minZ=c.maxZ;c.maxZ=t;}
            claims.put(key(c.faction),c);
        }
    }

    private void save() {
        data.set("claims",null);
        for(RectClaim c:claims.values()) {
            String b="claims."+key(c.faction);
            data.set(b+".faction",c.faction);
            data.set(b+".world",c.world);
            data.set(b+".min-x",c.minX);
            data.set(b+".max-x",c.maxX);
            data.set(b+".min-z",c.minZ);
            data.set(b+".max-z",c.maxZ);
        }
        try {data.save(file);}
        catch(IOException e){plugin.getLogger().warning("Could not save claims-v2.yml: "+e.getMessage());}
    }

    private String key(String s){return s==null?"":s.toLowerCase(Locale.ENGLISH);}
}
