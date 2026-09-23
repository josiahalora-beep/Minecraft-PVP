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
import org.bukkit.scheduler.BukkitTask;

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
    private Location kothCrate;
    private BukkitTask readinessTask;

    SpawnRewardsDirector(EraCore plugin,WarpManager warps) {
        this.plugin=plugin;
        this.warps=warps;
        this.file=new File(plugin.getDataFolder(),"rewards.yml");
        this.data=YamlConfiguration.loadConfiguration(file);
    }

    void start() {
        if(!plugin.getConfig().getBoolean("rewards.enabled",true)) return;

        if(plugin.getConfig().getBoolean("spawn.external-schematic",false)) {
            // Kraken is authoritative. Wait for the production build and then
            // place only the three functional interaction blocks—no court,
            // frames, beacon, signs or other geometry.
            if(!plugin.productionWorldReady()) {
                if(readinessTask==null) {
                    readinessTask=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
                        public void run() {
                            if(!plugin.productionWorldReady()) return;
                            readinessTask.cancel();
                            readinessTask=null;
                            configureKrakenCrates();
                        }
                    },20L,20L);
                }
                return;
            }
            configureKrakenCrates();
            return;
        }

        buildSpawnCrates();
    }

    void stop() {
        if(readinessTask!=null) readinessTask.cancel();
        readinessTask=null;
        save();
    }

    private void configureKrakenCrates() {
        World w=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(w==null) return;
        int y=plugin.getConfig().getInt("spawn.crates.y",67);
        int z=plugin.getConfig().getInt("spawn.crates.z",36);
        voteCrate=new Location(w,plugin.getConfig().getInt("spawn.crates.vote-x",-8),y,z);
        donorCrate=new Location(w,plugin.getConfig().getInt("spawn.crates.donor-x",0),y,z);
        kothCrate=new Location(w,plugin.getConfig().getInt("spawn.crates.koth-x",8),y,z);

        voteCrate.getBlock().setType(Material.CHEST);
        donorCrate.getBlock().setType(Material.ENDER_CHEST);
        kothCrate.getBlock().setType(Material.CHEST);

        rememberCrate("vote",voteCrate);
        rememberCrate("donor",donorCrate);
        rememberCrate("koth",kothCrate);
        plugin.getLogger().info("Kraken crate blocks ready: vote="+locText(voteCrate)+
            " donor="+locText(donorCrate)+" koth="+locText(kothCrate));
    }

    private void rememberCrate(String type,Location l) {
        String b="external-crates."+type;
        data.set(b+".world",l.getWorld().getName());
        data.set(b+".x",l.getBlockX());
        data.set(b+".y",l.getBlockY());
        data.set(b+".z",l.getBlockZ());
        save();
    }

    Location crateLocation(String type) {
        if("donor".equalsIgnoreCase(type)) return donorCrate==null?null:donorCrate.clone();
        if("koth".equalsIgnoreCase(type)) return kothCrate==null?null:kothCrate.clone();
        return voteCrate==null?null:voteCrate.clone();
    }

    boolean setExternalCrate(String type,Location location) {
        if(location==null || location.getWorld()==null) return false;
        String t="donor".equalsIgnoreCase(type)?"donor":("koth".equalsIgnoreCase(type)?"koth":"vote");
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
        } else if("koth".equals(t)) {
            kothCrate=blockLoc;
            kothCrate.getBlock().setType(Material.CHEST);
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
        if(plugin.simPendingKeyCount(name,"koth")>0) return "koth";
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
            int tier="donor".equalsIgnoreCase(type)?Math.max(1,Math.min(4,plugin.publicRankLevel(p.getName()))):0;
            ItemStack key=keyItem(type,1,tier);
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
        p.getInventory().addItem(keyItem("donor",amount,donor));
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

        p.getInventory().addItem(keyItem("vote",1,0));
        data.set(k,now);
        save();
        registerVote(p.getName());
        p.sendMessage(EraCore.colorText("&eThanks for voting. &fYou received 1 Vote Crate Key."));
        try { p.sendTitle(EraCore.colorText("&eVOTE RECEIVED"),EraCore.colorText("&f+1 Vote Key")); } catch(Throwable ignored) {}
        return true;
    }

    boolean commandKeys(Player p) {
        int vote=countKeys(p,"vote");
        int koth=countKeys(p,"koth");
        int simVote=plugin.simPendingKeyCount(p.getName(),"vote");
        int simDonor=plugin.simPendingKeyCount(p.getName(),"donor");
        int simKoth=plugin.simPendingKeyCount(p.getName(),"koth");
        p.sendMessage(EraCore.colorText("&6--- Crate Keys ---"));
        p.sendMessage(EraCore.colorText("&eVote: &f"+vote+(simVote>0?" &7("+simVote+" pending)":"")));
        p.sendMessage(EraCore.colorText("&6KOTH: &f"+koth+(simKoth>0?" &7("+simKoth+" pending)":"")));
        p.sendMessage(EraCore.colorText("&aVIP: &f"+countDonorTierKeys(p,1)+"  &fMVP: &f"+countDonorTierKeys(p,2)+
            "  &6Pro: &f"+countDonorTierKeys(p,3)+"  &bPlatinum: &f"+countDonorTierKeys(p,4)+
            (simDonor>0?" &7("+simDonor+" donor pending)":"")));
        p.sendMessage(EraCore.colorText("&7/vote odds &8| &7/crates"));
        return true;
    }

    boolean commandCrates(Player p,String[] args) {
        if(args.length>0 && (args[0].equalsIgnoreCase("vote") ||
                            args[0].equalsIgnoreCase("donor") ||
                            args[0].equalsIgnoreCase("koth"))) {
            showOdds(p,args[0]);
            return true;
        }
        p.sendMessage(EraCore.colorText("&6Spawn Crates"));
        p.sendMessage(EraCore.colorText("&eVote Chest &7- voting and vote parties"));
        p.sendMessage(EraCore.colorText("&6KOTH Chest &7- event capture keys"));
        p.sendMessage(EraCore.colorText("&bDonor Ender Chest &7- VIP / MVP / Pro / Platinum keys"));
        p.sendMessage(EraCore.colorText("&7All three are plain interaction blocks built into Kraken spawn."));
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
                online.getInventory().addItem(keyItem("vote",1,0));
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
        else if(sameBlock(l,kothCrate)) type="koth";
        if(type==null) return;

        e.setCancelled(true);
        Player p=e.getPlayer();
        int donorTier="donor".equals(type)?highestDonorKeyTier(p):0;
        if(!consumeKey(p,type)) {
            p.sendMessage(EraCore.colorText("&cYou need a "+prettyType(type)+" key. &7Use /crates "+type+" to see rewards."));
            return;
        }

        if(plugin.isBotIdentity(p.getName())) plugin.consumeSimPendingKey(p.getName(),type,1);
        giveReward(p,type,donorTier);
    }

    private void giveReward(Player p,String type,int donorTier) {
        int roll=rng.nextInt(10000);
        if("donor".equals(type)) giveDonorReward(p,roll,Math.max(1,donorTier));
        else if("koth".equals(type)) giveKothReward(p,roll);
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

    private void giveDonorReward(Player p,int r,int tier) {
        tier=Math.max(1,Math.min(4,tier));
        int pearls=8+tier*4;
        int diamonds=2+tier*2;
        int obsidian=8+tier*4;
        int heals=4+tier*3;
        int gunpowder=8+tier*4;
        double cash=250.0+tier*250.0;

        if(r<1800) {
            money(p,cash,"$"+((int)cash));
        } else if(r<3400) {
            item(p,new ItemStack(Material.ENDER_PEARL,pearls),pearls+" Ender Pearls");
        } else if(r<4800) {
            item(p,new ItemStack(Material.DIAMOND,diamonds),diamonds+" Diamonds");
        } else if(r<6100) {
            item(p,new ItemStack(Material.OBSIDIAN,obsidian),obsidian+" Obsidian");
        } else if(r<7300) {
            for(int i=0;i<heals;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,heals+" Splash Health II",false);
        } else if(r<8200) {
            item(p,new ItemStack(Material.SULPHUR,gunpowder),gunpowder+" Gunpowder");
        } else if(r<8900) {
            Material piece=tier>=3?Material.DIAMOND_CHESTPLATE:Material.IRON_CHESTPLATE;
            item(p,enchanted(piece,2),"Protection II "+piece.name().replace('_',' '));
        } else if(r<9400) {
            ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);
            sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,2);
            sword.addUnsafeEnchantment(Enchantment.DURABILITY,2);
            item(p,sword,"Sharpness II Diamond Sword");
        } else if(r<9750 && tier>=3) {
            Material[] armor={Material.DIAMOND_HELMET,Material.DIAMOND_CHESTPLATE,Material.DIAMOND_LEGGINGS,Material.DIAMOND_BOOTS};
            ItemStack piece=enchanted(armor[r%armor.length],2);
            item(p,piece,"Protection II "+piece.getType().name().replace('_',' '));
        } else if(r<9925) {
            int next=Math.min(4,tier+1);
            p.getInventory().addItem(keyItem("donor",1,next));
            finishReward(p,donorTierName(next)+" Donor Crate Key",true);
        } else {
            if(plugin.upgradeRankFromReward(p,donorTierName(tier)+" Donor Crate"))
                finishReward(p,"DONOR RANK UPGRADE",true);
            else {
                p.getInventory().addItem(keyItem("koth",1,0));
                finishReward(p,"1 KOTH Key",true);
            }
        }
    }

    private void giveKothReward(Player p,int r) {
        if(r<1700) {
            item(p,new ItemStack(Material.ENDER_PEARL,16),"16 Ender Pearls");
        } else if(r<3300) {
            for(int i=0;i<12;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,"12 Splash Health II",false);
        } else if(r<4700) {
            item(p,new ItemStack(Material.GLOWSTONE_DUST,16),"16 Glowstone Dust");
        } else if(r<6100) {
            item(p,new ItemStack(Material.SULPHUR,24),"24 Gunpowder");
        } else if(r<7200) {
            item(p,new ItemStack(Material.OBSIDIAN,24),"24 Obsidian");
        } else if(r<8100) {
            item(p,new ItemStack(Material.DIAMOND,8),"8 Diamonds");
        } else if(r<8750) {
            ItemStack looting=new ItemStack(Material.DIAMOND_SWORD);
            looting.addUnsafeEnchantment(Enchantment.LOOT_BONUS_MOBS,4);
            looting.addUnsafeEnchantment(Enchantment.DURABILITY,3);
            item(p,looting,"Looting IV Event Sword");
        } else if(r<9300) {
            ItemStack fortune=new ItemStack(Material.DIAMOND_PICKAXE);
            fortune.addUnsafeEnchantment(Enchantment.LOOT_BONUS_BLOCKS,4);
            fortune.addUnsafeEnchantment(Enchantment.DURABILITY,3);
            item(p,fortune,"Fortune IV Event Pickaxe");
        } else if(r<9750) {
            Material[] armor={Material.DIAMOND_HELMET,Material.DIAMOND_CHESTPLATE,Material.DIAMOND_LEGGINGS,Material.DIAMOND_BOOTS};
            ItemStack piece=enchanted(armor[r%armor.length],2);
            item(p,piece,"Protection II "+piece.getType().name().replace('_',' '));
        } else {
            ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);
            sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,2);
            sword.addUnsafeEnchantment(Enchantment.DURABILITY,3);
            item(p,sword,"Sharpness II Event Sword");
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
            p.sendMessage(EraCore.colorText("&f16 Obsidian &712%  &f12 Heals &712%  &f16 Gunpowder &78%"));
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
        return keyItem(type,amount,"donor".equalsIgnoreCase(type)?1:0);
    }

    private ItemStack keyItem(String type,int amount,int donorTier) {
        boolean donor="donor".equalsIgnoreCase(type);
        boolean koth="koth".equalsIgnoreCase(type);
        Material material=donor?Material.BLAZE_ROD:(koth?Material.NETHER_STAR:Material.TRIPWIRE_HOOK);
        ItemStack item=new ItemStack(material,amount);
        ItemMeta meta=item.getItemMeta();

        if(donor) {
            int tier=Math.max(1,Math.min(4,donorTier));
            String color=tier==4?"&b":(tier==3?"&6":(tier==2?"&f":"&a"));
            meta.setDisplayName(EraCore.colorText(color+donorTierName(tier)+" Donor Crate Key"));
        } else if(koth) {
            meta.setDisplayName(EraCore.colorText("&6KOTH Crate Key"));
        } else {
            meta.setDisplayName(EraCore.colorText("&eVote Crate Key"));
        }

        List<String> lore=new ArrayList<String>();
        lore.add(EraCore.colorText("&7Redeem at the spawn "+prettyType(type)+" block."));
        lore.add(EraCore.colorText("&8Daegon HCF reward key"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String donorTierName(int tier) {
        if(tier>=4) return "Platinum";
        if(tier==3) return "Pro";
        if(tier==2) return "MVP";
        return "VIP";
    }

    private Material keyMaterial(String type) {
        if("donor".equalsIgnoreCase(type)) return Material.BLAZE_ROD;
        if("koth".equalsIgnoreCase(type)) return Material.NETHER_STAR;
        return Material.TRIPWIRE_HOOK;
    }

    private boolean hasKey(Player p,String type) {
        Material mat=keyMaterial(type);
        for(ItemStack item:p.getInventory().getContents()) {
            if(item!=null && item.getType()==mat && isKeyItem(item,type)) return true;
        }
        return false;
    }

    private int countKeys(Player p,String type) {
        int total=0;
        Material mat=keyMaterial(type);
        for(ItemStack item:p.getInventory().getContents()) {
            if(item!=null && item.getType()==mat && isKeyItem(item,type)) total+=item.getAmount();
        }
        return total;
    }

    private int countDonorTierKeys(Player p,int tier) {
        int total=0;
        for(ItemStack item:p.getInventory().getContents()) {
            if(item!=null && item.getType()==Material.BLAZE_ROD && donorKeyTier(item)==tier)
                total+=item.getAmount();
        }
        return total;
    }

    private int highestDonorKeyTier(Player p) {
        int best=0;
        for(ItemStack item:p.getInventory().getContents()) {
            if(item==null || item.getType()!=Material.BLAZE_ROD) continue;
            best=Math.max(best,donorKeyTier(item));
        }
        return best;
    }

    private int donorKeyTier(ItemStack item) {
        if(item==null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return 0;
        String name=ChatColor.stripColor(item.getItemMeta().getDisplayName()).toLowerCase(Locale.ENGLISH);
        if(!name.contains("donor crate key")) return 0;
        if(name.contains("platinum")) return 4;
        if(name.contains("pro")) return 3;
        if(name.contains("mvp")) return 2;
        return 1;
    }

    private boolean consumeKey(Player p,String type) {
        Material mat=keyMaterial(type);
        ItemStack[] contents=p.getInventory().getContents();
        int selected=-1;
        int bestTier=-1;
        for(int i=0;i<contents.length;i++) {
            ItemStack item=contents[i];
            if(item==null || item.getType()!=mat || !isKeyItem(item,type)) continue;
            int tier="donor".equalsIgnoreCase(type)?donorKeyTier(item):0;
            if(selected<0 || tier>bestTier) {selected=i;bestTier=tier;}
        }
        if(selected<0) return false;

        ItemStack item=contents[selected];
        if(item.getAmount()<=1) p.getInventory().setItem(selected,null);
        else { item.setAmount(item.getAmount()-1); p.getInventory().setItem(selected,item); }
        p.updateInventory();
        return true;
    }

    private boolean isKeyItem(ItemStack item,String type) {
        if(item==null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return false;
        String name=ChatColor.stripColor(item.getItemMeta().getDisplayName()).toLowerCase(Locale.ENGLISH);
        if("donor".equalsIgnoreCase(type)) return name.contains("donor crate key");
        if("koth".equalsIgnoreCase(type)) return name.contains("koth crate key");
        return name.contains("vote crate key");
    }

    private String prettyType(String type) {
        if("donor".equalsIgnoreCase(type)) return "Donor Ender Chest";
        if("koth".equalsIgnoreCase(type)) return "KOTH Chest";
        return "Vote Chest";
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
