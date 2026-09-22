package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Physical spawn voting/donor crate loop.
 *
 * Exact historical Kohi vote/donor odds are not claimed. The tables are
 * explicit and configurable so the economy can be tuned from measured SOTW
 * outcomes without pretending unsupported historical precision.
 */
@SuppressWarnings("deprecation")
final class SpawnRewardsDirector implements Listener {
    private final EraCore plugin;
    private final WarpManager warps;
    private final Random rng=new Random(20150418L);
    private final File file;
    private final YamlConfiguration data;

    private Location voteCrate;
    private Location donorCrate;

    SpawnRewardsDirector(EraCore plugin,WarpManager warps) {
        this.plugin=plugin;
        this.warps=warps;
        this.file=new File(plugin.getDataFolder(),"rewards.yml");
        this.data=YamlConfiguration.loadConfiguration(file);
    }

    void start() {
        if(!plugin.getConfig().getBoolean("rewards.enabled",true)) return;

        if(plugin.getConfig().getBoolean("spawn.external-schematic",false)) {
            // Never carve a crate court into a purchased/pasted schematic.
            // The owner marks the two real blocks once after paste.
            voteCrate=readExternalCrate("vote");
            donorCrate=readExternalCrate("donor");
            plugin.getLogger().info("External spawn mode: crate marks vote="+locText(voteCrate)+
                " donor="+locText(donorCrate));
            return;
        }

        buildSpawnCrates();

        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run(){ buildSpawnCrates(); }
        },420L);
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run(){ buildSpawnCrates(); }
        },760L);
    }

    void stop() {
        save();
    }

    Location crateLocation(String type) {
        if("donor".equalsIgnoreCase(type)) return donorCrate==null?null:donorCrate.clone();
        return voteCrate==null?null:voteCrate.clone();
    }

    boolean setExternalCrate(String type,Location location) {
        if(location==null || location.getWorld()==null) return false;
        String t="donor".equalsIgnoreCase(type)?"donor":"vote";
        Location blockLoc=location.getBlock().getLocation();
        String b="external-crates."+t;
        data.set(b+".world",blockLoc.getWorld().getName());
        data.set(b+".x",blockLoc.getBlockX());
        data.set(b+".y",blockLoc.getBlockY());
        data.set(b+".z",blockLoc.getBlockZ());
        save();

        if("donor".equals(t)) {
            donorCrate=blockLoc;
            donorCrate.getBlock().setType(Material.ENDER_CHEST);
        } else {
            voteCrate=blockLoc;
            voteCrate.getBlock().setType(Material.CHEST);
        }
        return true;
    }

    private Location readExternalCrate(String type) {
        String b="external-crates."+type.toLowerCase(Locale.ENGLISH);
        if(!data.contains(b+".world")) return null;
        World w=Bukkit.getWorld(data.getString(b+".world","world"));
        if(w==null) return null;
        return new Location(w,data.getInt(b+".x"),data.getInt(b+".y"),data.getInt(b+".z"));
    }

    private String locText(Location l) {
        return l==null?"unset":l.getWorld().getName()+":"+l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ();
    }

    String keyTypeForPending(String name) {
        if(plugin.simPendingKeyCount(name,"donor")>0) return "donor";
        if(plugin.simPendingKeyCount(name,"vote")>0) return "vote";
        return "";
    }

    void ensurePhysicalPendingKey(Player p,String type) {
        if(p==null || type==null || type.isEmpty()) return;
        if(plugin.simPendingKeyCount(p.getName(),type)<=0) return;

        org.bukkit.inventory.PlayerInventory inv=p.getInventory();
        int keySlot=findKeySlot(p,type);
        if(keySlot<0) {
            ItemStack key=keyItem(type,1);
            int hotbar=-1;
            for(int i=0;i<9;i++) {
                ItemStack item=inv.getItem(i);
                if(item==null || item.getType()==Material.AIR) { hotbar=i; break; }
            }
            if(hotbar<0) {
                int empty=-1;
                for(int i=9;i<36;i++) {
                    ItemStack item=inv.getItem(i);
                    if(item==null || item.getType()==Material.AIR) { empty=i; break; }
                }
                hotbar=8;
                if(empty>=0) inv.setItem(empty,inv.getItem(hotbar));
            }
            inv.setItem(hotbar,key);
            keySlot=hotbar;
        }

        // Put the genuine NBT/display-name key in-hand. This matters for Donor
        // Keys because Bard players may also legitimately own ordinary blaze rods.
        if(keySlot>=9) {
            int hotbar=-1;
            for(int i=0;i<9;i++) {
                ItemStack item=inv.getItem(i);
                if(item==null || item.getType()==Material.AIR) { hotbar=i; break; }
            }
            if(hotbar<0) hotbar=8;
            ItemStack swap=inv.getItem(hotbar);
            inv.setItem(hotbar,inv.getItem(keySlot));
            inv.setItem(keySlot,swap);
            keySlot=hotbar;
        }
        inv.setHeldItemSlot(Math.max(0,Math.min(8,keySlot)));
        p.updateInventory();
    }

    private int findKeySlot(Player p,String type) {
        org.bukkit.inventory.PlayerInventory inv=p.getInventory();
        for(int i=0;i<36;i++) {
            ItemStack item=inv.getItem(i);
            if(item!=null && isKeyItem(item,type)) return i;
        }
        return -1;
    }

    void onHumanJoin(Player p) {
        if(p==null || plugin.isBotIdentity(p.getName())) return;
        int donor=plugin.publicRankLevel(p.getName());
        if(donor<=0) return;

        long now=System.currentTimeMillis();
        String k="human."+p.getUniqueId().toString()+".last-donor-key";
        long last=data.getLong(k,0L);
        long hours=donor>=4 ? plugin.getConfig().getLong("rewards.platinum-key-hours",8L) :
            (donor==3 ? plugin.getConfig().getLong("rewards.gold-key-hours",12L) :
            (donor==2 ? plugin.getConfig().getLong("rewards.silver-key-hours",18L) :
                        plugin.getConfig().getLong("rewards.basic-key-hours",24L)));
        long cooldown=Math.max(1,hours)*3600000L;
        if(now-last<cooldown) return;

        int amount=donor>=4?3:(donor==3?2:1);
        p.getInventory().addItem(keyItem("donor",amount));
        data.set(k,now);
        save();
        p.sendMessage(EraCore.colorText("&6Donor perk: &f+"+amount+" Donor Crate Key"+(amount==1?"":"s")+"&7. Visit the crates at spawn."));
        try { p.sendTitle(EraCore.colorText("&6DONOR KEY"),EraCore.colorText("&fRedeem it at spawn")); } catch(Throwable ignored) {}
    }

    boolean commandVote(Player p,String[] args) {
        if(args.length>0 && args[0].equalsIgnoreCase("odds")) {
            showOdds(p,"vote");
            return true;
        }

        long now=System.currentTimeMillis();
        String k="human."+p.getUniqueId().toString()+".last-vote";
        long last=data.getLong(k,0L);
        long cooldown=Math.max(1,plugin.getConfig().getLong("rewards.vote-cooldown-hours",12L))*3600000L;
        if(now-last<cooldown && !plugin.isOwnerPlayer(p)) {
            long mins=Math.max(1,(cooldown-(now-last))/60000L);
            p.sendMessage(EraCore.colorText("&cYou can vote again in ~"+mins+" minutes. &7Use /vote odds."));
            return true;
        }

        p.getInventory().addItem(keyItem("vote",1));
        data.set(k,now);
        save();
        registerVote(p.getName());
        p.sendMessage(EraCore.colorText("&eThanks for voting. &fYou received 1 Vote Crate Key."));
        try { p.sendTitle(EraCore.colorText("&eVOTE RECEIVED"),EraCore.colorText("&f+1 Vote Key")); } catch(Throwable ignored) {}
        return true;
    }

    boolean commandKeys(Player p) {
        int vote=countKeys(p,"vote");
        int donor=countKeys(p,"donor");
        int simVote=plugin.simPendingKeyCount(p.getName(),"vote");
        int simDonor=plugin.simPendingKeyCount(p.getName(),"donor");
        p.sendMessage(EraCore.colorText("&6--- Crate Keys ---"));
        p.sendMessage(EraCore.colorText("&eVote: &f"+vote+(simVote>0?" &7("+simVote+" pending)":"")));
        p.sendMessage(EraCore.colorText("&6Donor: &f"+donor+(simDonor>0?" &7("+simDonor+" pending)":"")));
        p.sendMessage(EraCore.colorText("&7/vote odds &8| &7/crates"));
        return true;
    }

    boolean commandCrates(Player p,String[] args) {
        if(args.length>0 && (args[0].equalsIgnoreCase("vote") || args[0].equalsIgnoreCase("donor"))) {
            showOdds(p,args[0]);
            return true;
        }
        p.sendMessage(EraCore.colorText("&6Spawn Crates"));
        p.sendMessage(EraCore.colorText("&eVote Crate &7- voting, vote parties"));
        p.sendMessage(EraCore.colorText("&6Donor Crate &7- donor perk keys"));
        p.sendMessage(EraCore.colorText("&7Use &f/crates vote &7or &f/crates donor &7to view odds."));
        return true;
    }

    void registerSimVote(String name) {
        plugin.addSimPendingKey(name,"vote",1);
        registerVote(name);
    }

    void registerSimDonorKey(String name) {
        plugin.addSimPendingKey(name,"donor",1);
    }

    private void registerVote(String name) {
        int progress=data.getInt("vote-party.progress",0)+1;
        int target=Math.max(5,plugin.getConfig().getInt("rewards.vote-party-target",20));
        if(progress>=target) {
            progress=0;
            plugin.broadcastCommunityEvent("&dVote party! &r&f"+target+" votes reached. &eOnline players received a Vote Key.");
            plugin.rewardVoteParty();
            for(Player online:Bukkit.getOnlinePlayers()) {
                if(plugin.isBotIdentity(online.getName())) continue;
                online.getInventory().addItem(keyItem("vote",1));
                try { online.sendTitle(EraCore.colorText("&dVOTE PARTY"),EraCore.colorText("&e+1 Vote Key")); } catch(Throwable ignored) {}
            }
        } else {
            plugin.broadcastCommunityEvent("&e[Vote] &f"+name+" &7voted. &dVote Party &f"+progress+"&7/&f"+target);
        }
        data.set("vote-party.progress",progress);
        save();
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onInteract(PlayerInteractEvent e) {
        if(e.getClickedBlock()==null) return;
        Location l=e.getClickedBlock().getLocation();

        String type=null;
        if(sameBlock(l,voteCrate)) type="vote";
        else if(sameBlock(l,donorCrate)) type="donor";
        if(type==null) return;

        e.setCancelled(true);
        Player p=e.getPlayer();
        if(!consumeKey(p,type)) {
            p.sendMessage(EraCore.colorText("&cYou need a "+prettyType(type)+" Crate Key. &7Use /crates "+type+" to see rewards."));
            return;
        }

        if(plugin.isBotIdentity(p.getName())) plugin.consumeSimPendingKey(p.getName(),type,1);
        giveReward(p,type);
    }

    private void giveReward(Player p,String type) {
        int roll=rng.nextInt(10000);
        if("donor".equals(type)) giveDonorReward(p,roll);
        else giveVoteReward(p,roll);
    }

    private void giveVoteReward(Player p,int r) {
        if(r<2400) {
            money(p,250,"$250");
        } else if(r<4200) {
            item(p,new ItemStack(Material.ENDER_PEARL,8),"8 Ender Pearls");
        } else if(r<5700) {
            item(p,new ItemStack(Material.IRON_INGOT,16),"16 Iron");
        } else if(r<6900) {
            item(p,new ItemStack(Material.OBSIDIAN,12),"12 Obsidian");
        } else if(r<7900) {
            item(p,new ItemStack(Material.DIAMOND,4),"4 Diamonds");
        } else if(r<8700) {
            for(int i=0;i<6;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,"6 Splash Health II",false);
        } else if(r<9200) {
            item(p,new ItemStack(Material.NETHER_STALK,16),"16 Nether Wart");
        } else if(r<9600) {
            item(p,new ItemStack(Material.GLOWSTONE_DUST,8),"8 Glowstone Dust");
        } else if(r<9850) {
            ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);
            sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,1);
            item(p,sword,"Sharpness I Diamond Sword");
        } else if(r<9970) {
            p.getInventory().addItem(keyItem("donor",1));
            finishReward(p,"1 Donor Crate Key",true);
        } else {
            if(plugin.upgradeRankFromReward(p,"Vote Crate")) finishReward(p,"DONOR RANK UPGRADE",true);
            else {
                p.getInventory().addItem(keyItem("donor",2));
                finishReward(p,"2 Donor Crate Keys",true);
            }
        }
    }

    private void giveDonorReward(Player p,int r) {
        if(r<2000) {
            money(p,750,"$750");
        } else if(r<3800) {
            item(p,new ItemStack(Material.ENDER_PEARL,16),"16 Ender Pearls");
        } else if(r<5300) {
            item(p,new ItemStack(Material.DIAMOND,8),"8 Diamonds");
        } else if(r<6500) {
            item(p,new ItemStack(Material.OBSIDIAN,16),"16 Obsidian");
        } else if(r<7700) {
            for(int i=0;i<12;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,"12 Splash Health II",false);
        } else if(r<8500) {
            for(int i=0;i<4;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)8226));
            finishReward(p,"4 Speed II",false);
        } else if(r<9100) {
            ItemStack[] set={
                enchanted(Material.IRON_HELMET,2), enchanted(Material.IRON_CHESTPLATE,2),
                enchanted(Material.IRON_LEGGINGS,2), enchanted(Material.IRON_BOOTS,2)
            };
            p.getInventory().addItem(set);
            finishReward(p,"Protection II Iron Set",true);
        } else if(r<9500) {
            ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);
            sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,2);
            item(p,sword,"Sharpness II Diamond Sword");
        } else if(r<9800) {
            p.getInventory().addItem(keyItem("vote",2));
            finishReward(p,"2 Vote Crate Keys",true);
        } else if(r<9900) {
            ItemStack chest=enchanted(Material.DIAMOND_CHESTPLATE,2);
            item(p,chest,"Protection II Diamond Chestplate");
        } else if(r<9950) {
            p.getInventory().addItem(keyItem("donor",1));
            finishReward(p,"BONUS Donor Crate Key",true);
        } else {
            if(plugin.upgradeRankFromReward(p,"Donor Crate")) finishReward(p,"DONOR RANK UPGRADE",true);
            else {
                p.getInventory().addItem(keyItem("donor",2));
                finishReward(p,"2 Donor Crate Keys",true);
            }
        }
    }

    private ItemStack enchanted(Material m,int prot) {
        ItemStack item=new ItemStack(m);
        item.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL,prot);
        item.addUnsafeEnchantment(Enchantment.DURABILITY,2);
        return item;
    }

    private void money(Player p,double amount,String label) {
        plugin.creditEconomy(p.getName(),amount);
        finishReward(p,label,false);
    }

    private void item(Player p,ItemStack item,String label) {
        p.getInventory().addItem(item);
        finishReward(p,label,false);
    }

    private void finishReward(Player p,String label,boolean rare) {
        p.updateInventory();
        p.sendMessage(EraCore.colorText("&6Crates &8» &fYou won &e"+label+"&f."));
        try {
            p.sendTitle(EraCore.colorText(rare?"&6Rare reward":"&eCrate reward"),EraCore.colorText("&f"+label));
        } catch(Throwable ignored) {}
        p.playSound(p.getLocation(),rare?Sound.LEVEL_UP:Sound.ORB_PICKUP,1f,rare?0.8f:1.25f);
        if(rare) plugin.broadcastCommunityEvent("&6[Crates] &f"+p.getName()+" &7won &e"+label+"&7.");
    }

    private void showOdds(Player p,String type) {
        if("donor".equalsIgnoreCase(type)) {
            p.sendMessage(EraCore.colorText("&6--- Donor Crate Odds ---"));
            p.sendMessage(EraCore.colorText("&f$750 &720%  &f16 Pearls &718%  &f8 Diamonds &715%"));
            p.sendMessage(EraCore.colorText("&f16 Obsidian &712%  &f12 Heals &712%  &f4 Speed II &78%"));
            p.sendMessage(EraCore.colorText("&fP2 Iron Set &76%  &fSharp II Diamond &74%  &f2 Vote Keys &73%"));
            p.sendMessage(EraCore.colorText("&fP2 Diamond Chest &71%  &fBonus Donor Key &70.5%  &dRank Upgrade &70.5%"));
        } else {
            p.sendMessage(EraCore.colorText("&e--- Vote Crate Odds ---"));
            p.sendMessage(EraCore.colorText("&f$250 &724%  &f8 Pearls &718%  &f16 Iron &715%  &f12 Obsidian &712%"));
            p.sendMessage(EraCore.colorText("&f4 Diamonds &710%  &f6 Heals &78%  &f16 Wart &75%  &f8 Glowstone &74%"));
            p.sendMessage(EraCore.colorText("&fSharp I Diamond &72.5%  &fDonor Key &71.2%  &dRank Upgrade &70.3%"));
        }
    }

    private ItemStack keyItem(String type,int amount) {
        boolean donor="donor".equalsIgnoreCase(type);
        ItemStack item=new ItemStack(donor?Material.BLAZE_ROD:Material.TRIPWIRE_HOOK,amount);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(EraCore.colorText(donor?"&6Donor Crate Key":"&eVote Crate Key"));
        List<String> lore=new ArrayList<String>();
        lore.add(EraCore.colorText("&7Redeem at the spawn "+(donor?"Donor":"Vote")+" Crate."));
        lore.add(EraCore.colorText("&8Era HCF reward key"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasKey(Player p,String type) {
        Material mat="donor".equalsIgnoreCase(type)?Material.BLAZE_ROD:Material.TRIPWIRE_HOOK;
        for(ItemStack item:p.getInventory().getContents()) {
            if(item!=null && item.getType()==mat && isKeyItem(item,type)) return true;
        }
        return false;
    }

    private int countKeys(Player p,String type) {
        int total=0;
        Material mat="donor".equalsIgnoreCase(type)?Material.BLAZE_ROD:Material.TRIPWIRE_HOOK;
        for(ItemStack item:p.getInventory().getContents()) {
            if(item!=null && item.getType()==mat && isKeyItem(item,type)) total+=item.getAmount();
        }
        return total;
    }

    private boolean consumeKey(Player p,String type) {
        Material mat="donor".equalsIgnoreCase(type)?Material.BLAZE_ROD:Material.TRIPWIRE_HOOK;
        ItemStack[] contents=p.getInventory().getContents();
        for(int i=0;i<contents.length;i++) {
            ItemStack item=contents[i];
            if(item==null || item.getType()!=mat || !isKeyItem(item,type)) continue;
            if(item.getAmount()<=1) p.getInventory().setItem(i,null);
            else { item.setAmount(item.getAmount()-1); p.getInventory().setItem(i,item); }
            p.updateInventory();
            return true;
        }
        return false;
    }

    private boolean isKeyItem(ItemStack item,String type) {
        if(item==null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return false;
        String name=ChatColor.stripColor(item.getItemMeta().getDisplayName()).toLowerCase(Locale.ENGLISH);
        return "donor".equalsIgnoreCase(type)?name.contains("donor crate key"):name.contains("vote crate key");
    }

    private String prettyType(String type) {
        return "donor".equalsIgnoreCase(type)?"Donor":"Vote";
    }

    private boolean sameBlock(Location a,Location b) {
        if(a==null || b==null || a.getWorld()!=b.getWorld()) return false;
        return a.getBlockX()==b.getBlockX() && a.getBlockY()==b.getBlockY() && a.getBlockZ()==b.getBlockZ();
    }

    private void buildSpawnCrates() {
        Location spawn=warps.getSpawn();
        if(spawn==null || spawn.getWorld()==null) return;
        World w=spawn.getWorld();
        int y=plugin.getConfig().getInt("map.surface-y",63)+1;
        int cx=spawn.getBlockX();
        int cz=spawn.getBlockZ()+16;

        // Large, unmistakable crate court on the reserved north functional pad.
        // The actual reward logic remains event-driven: these are not decorative
        // chests and bots receive their exact coordinates through WorkerTask.
        for(int x=cx-13;x<=cx+13;x++) for(int z=cz-5;z<=cz+5;z++) {
            w.getBlockAt(x,y-1,z).setType(((Math.abs(x-cx)+Math.abs(z-cz))%5)==0
                ? Material.QUARTZ_BLOCK : Material.SMOOTH_BRICK);
            for(int yy=y;yy<=y+5;yy++) {
                Material m=w.getBlockAt(x,yy,z).getType();
                if(m!=Material.CHEST && m!=Material.ENDER_CHEST && m!=Material.SIGN_POST)
                    w.getBlockAt(x,yy,z).setType(Material.AIR);
            }
        }

        // Two framed stations with enough empty approach space for multiple
        // players/bots. Their block types intentionally differ for simple
        // Mineflayer discovery as a fallback to semantic coordinates.
        int[] stations={-6,6};
        for(int sx:stations) {
            int px=cx+sx;
            for(int dx=-2;dx<=2;dx++) {
                w.getBlockAt(px+dx,y-1,cz).setType(Material.QUARTZ_BLOCK);
                w.getBlockAt(px+dx,y+4,cz).setType(Material.QUARTZ_BLOCK);
            }
            for(int yy=y;yy<=y+4;yy++) {
                w.getBlockAt(px-2,yy,cz).setType(Material.QUARTZ_BLOCK);
                w.getBlockAt(px+2,yy,cz).setType(Material.QUARTZ_BLOCK);
            }
            w.getBlockAt(px,y+4,cz).setType(Material.GLOWSTONE);
        }

        voteCrate=new Location(w,cx-6,y,cz);
        donorCrate=new Location(w,cx+6,y,cz);
        voteCrate.getBlock().setType(Material.CHEST);
        donorCrate.getBlock().setType(Material.ENDER_CHEST);

        placeLabel(w,cx-6,y+1,cz-2,"VOTE KEYS","Right Click");
        placeLabel(w,cx+6,y+1,cz-2,"DONOR KEYS","Right Click");

        // A center beacon makes the court visible from the spawn point while
        // leaving the actual two interaction lanes unobstructed.
        w.getBlockAt(cx,y,cz+3).setType(Material.BEACON);
        w.getBlockAt(cx,y-1,cz+3).setType(Material.IRON_BLOCK);
        w.getBlockAt(cx,y+1,cz+3).setType(Material.GLOWSTONE);
    }

    private void placeLabel(World w,int x,int y,int z,String line0,String line1) {
        Block b=w.getBlockAt(x,y,z);
        b.setType(Material.SIGN_POST);
        if(b.getState() instanceof Sign) {
            Sign s=(Sign)b.getState();
            s.setLine(0,EraCore.colorText(""+line0));
            s.setLine(1,EraCore.colorText("&7"+line1));
            s.update(true);
        }
    }

    private void save() {
        try { data.save(file); }
        catch(IOException e) { plugin.getLogger().warning("Could not save rewards.yml: "+e.getMessage()); }
    }
}
