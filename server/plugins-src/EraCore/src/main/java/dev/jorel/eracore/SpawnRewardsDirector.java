package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
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

        if(!placeFunctionalCrate(voteCrate,Material.CHEST,"vote") ||
           !placeFunctionalCrate(donorCrate,Material.ENDER_CHEST,"donor") ||
           !placeFunctionalCrate(kothCrate,Material.CHEST,"koth")) {
            plugin.getLogger().severe("Kraken crate placement refused because a configured crate position contains schematic decoration. No replacement structure was built.");
            return;
        }

        rememberCrate("vote",voteCrate);
        rememberCrate("donor",donorCrate);
        rememberCrate("koth",kothCrate);
        plugin.getLogger().info("Kraken crate blocks ready: vote="+locText(voteCrate)+
            " donor="+locText(donorCrate)+" koth="+locText(kothCrate));
    }

    private boolean placeFunctionalCrate(Location location,Material material,String label) {
        if(location==null || location.getWorld()==null) return false;
        Block block=location.getBlock();
        Material current=block.getType();
        if(current!=Material.AIR && current!=Material.CHEST && current!=Material.ENDER_CHEST) {
            plugin.getLogger().warning("Refusing to overwrite Kraken block for "+label+" crate at "+locText(location)+
                " current="+current.name());
            return false;
        }
        Block floor=location.clone().subtract(0,1,0).getBlock();
        if(!floor.getType().isSolid()) {
            plugin.getLogger().warning("Refusing Kraken "+label+" crate: no solid schematic floor at "+locText(location));
            return false;
        }
        block.setType(material);
        return true;
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
        p.sendMessage(EraCore.colorText("&aBasic: &f"+countDonorTierKeys(p,1)+"  &7Silver: &f"+countDonorTierKeys(p,2)+
            "  &6Gold: &f"+countDonorTierKeys(p,3)+"  &bPlatinum: &f"+countDonorTierKeys(p,4)+
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
        p.sendMessage(EraCore.colorText("&bDonor Ender Chest &7- Basic / Silver / Gold / Platinum keys"));
        p.sendMessage(EraCore.colorText("&7All three are plain interaction blocks built into Kraken spawn."));
        return true;
    }

    void grantKey(Player p,String type,int amount,int donorTier) {
        if(p==null || amount<=0) return;
        p.getInventory().addItem(keyItem(type,amount,donorTier));
        p.updateInventory();
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
        // Vote keys should always move early-map progression forward. Premium
        // combat gear exists here only as a lottery-level exception so voting
        // cannot become the main source of P2 / Sharp II.
        if(r<1600) {
            money(p,300,"$300");
        } else if(r<3100) {
            item(p,new ItemStack(Material.ENDER_PEARL,12),"12 Ender Pearls");
        } else if(r<4300) {
            item(p,new ItemStack(Material.OBSIDIAN,16),"16 Obsidian");
        } else if(r<5300) {
            item(p,new ItemStack(Material.DIAMOND,8),"8 Diamonds");
        } else if(r<6500) {
            for(int i=0;i<10;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,"10 Splash Health II",false);
        } else if(r<7300) {
            item(p,new ItemStack(Material.NETHER_STALK,16),"16 Nether Wart");
        } else if(r<8100) {
            item(p,new ItemStack(Material.GLOWSTONE_DUST,12),"12 Glowstone Dust");
        } else if(r<8900) {
            item(p,new ItemStack(Material.SULPHUR,16),"16 Gunpowder");
        } else if(r<9550) {
            ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);
            sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,1);
            item(p,sword,"Sharpness I Diamond Sword");
        } else if(r<9790) {
            p.getInventory().addItem(keyItem("donor",1));
            finishReward(p,"1 Basic Donor Crate Key",true);
        } else if(r<9910) {
            ItemStack piece=randomProt2Piece(r);
            item(p,piece,"Protection II "+piece.getType().name().replace('_',' '));
            plugin.noteSimPremiumReward(p,"vote P2 piece");
        } else if(r<9980) {
            ItemStack sword=rareSword();
            item(p,sword,"Sharpness II / Fire I Diamond Sword");
            plugin.noteSimPremiumReward(p,"vote S2F1 sword");
        } else {
            giveFullRareCombatSet(p,"Vote");
        }
    }

    private void giveDonorReward(Player p,int r,int tier) {
        tier=Math.max(1,Math.min(4,tier));
        int pearls=12+tier*4;
        int diamonds=4+tier*2;
        int obsidian=12+tier*4;
        int heals=7+tier*3;
        int gunpowder=12+tier*4;
        double cash=350.0+tier*250.0;

        // Premium odds rise by donor tier, but the inherited-rank economy is
        // deliberately capped: even Platinum keys are mostly progression loot.
        int fullSetChance = tier==4?70:(tier==3?40:(tier==2?25:15));      // 0.70..0.15%
        int fireSwordChance = tier==4?250:(tier==3?180:(tier==2?120:80)); // 2.50..0.80%
        int p2PieceChance = tier==4?600:(tier==3?450:(tier==2?300:200));  // 6.00..2.00%
        int premiumStart=10000-fullSetChance-fireSwordChance-p2PieceChance;

        if(r>=10000-fullSetChance) {
            giveFullRareCombatSet(p,donorTierName(tier)+" Donor");
            return;
        }
        if(r>=10000-fullSetChance-fireSwordChance) {
            item(p,rareSword(),"Sharpness II / Fire I Diamond Sword");
            plugin.noteSimPremiumReward(p,donorTierName(tier)+" donor S2F1 sword");
            return;
        }
        if(r>=premiumStart) {
            ItemStack piece=randomProt2Piece(r+tier);
            item(p,piece,"Protection II "+piece.getType().name().replace('_',' '));
            plugin.noteSimPremiumReward(p,donorTierName(tier)+" donor P2 piece");
            return;
        }

        // Normalize the common roll into the non-premium portion so the common
        // table stays useful at every donor tier without silently changing odds.
        int common=(int)Math.floor((r/(double)Math.max(1,premiumStart))*10000.0);
        if(common<1700) {
            money(p,cash,"$"+((int)cash));
        } else if(common<3300) {
            item(p,new ItemStack(Material.ENDER_PEARL,pearls),pearls+" Ender Pearls");
        } else if(common<4700) {
            item(p,new ItemStack(Material.DIAMOND,diamonds),diamonds+" Diamonds");
        } else if(common<6100) {
            item(p,new ItemStack(Material.OBSIDIAN,obsidian),obsidian+" Obsidian");
        } else if(common<7500) {
            for(int i=0;i<heals;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,heals+" Splash Health II",false);
        } else if(common<8600) {
            item(p,new ItemStack(Material.SULPHUR,gunpowder),gunpowder+" Gunpowder");
        } else if(common<9300) {
            item(p,new ItemStack(Material.GLOWSTONE_DUST,8+tier*4),(8+tier*4)+" Glowstone Dust");
        } else if(common<9750) {
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
        // KOTH is the primary renewable premium-gear source. Most rolls still
        // pay progression resources; winning events is what makes P2/S2 gear
        // meaningfully cluster on successful factions.
        if(r<1300) {
            item(p,new ItemStack(Material.ENDER_PEARL,16),"16 Ender Pearls");
        } else if(r<2600) {
            for(int i=0;i<12;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,"12 Splash Health II",false);
        } else if(r<3800) {
            item(p,new ItemStack(Material.GLOWSTONE_DUST,20),"20 Glowstone Dust");
        } else if(r<5000) {
            item(p,new ItemStack(Material.SULPHUR,28),"28 Gunpowder");
        } else if(r<6000) {
            item(p,new ItemStack(Material.OBSIDIAN,32),"32 Obsidian");
        } else if(r<6800) {
            item(p,new ItemStack(Material.DIAMOND,12),"12 Diamonds");
        } else if(r<7400) {
            ItemStack looting=new ItemStack(Material.DIAMOND_SWORD);
            looting.addUnsafeEnchantment(Enchantment.LOOT_BONUS_MOBS,4);
            looting.addUnsafeEnchantment(Enchantment.DURABILITY,3);
            item(p,looting,"Looting IV Event Sword");
        } else if(r<7900) {
            ItemStack fortune=new ItemStack(Material.DIAMOND_PICKAXE);
            fortune.addUnsafeEnchantment(Enchantment.LOOT_BONUS_BLOCKS,4);
            fortune.addUnsafeEnchantment(Enchantment.DURABILITY,3);
            item(p,fortune,"Fortune IV Event Pickaxe");
        } else if(r<9300) {
            ItemStack piece=randomProt2Piece(r);
            item(p,piece,"Protection II "+piece.getType().name().replace('_',' '));
            plugin.noteSimPremiumReward(p,"KOTH P2 piece");
        } else if(r<9850) {
            item(p,rareSword(),"Sharpness II / Fire I KOTH Sword");
            plugin.noteSimPremiumReward(p,"KOTH S2F1 sword");
        } else {
            giveFullRareCombatSet(p,"KOTH");
        }
    }

    private ItemStack randomProt2Piece(int seed) {
        Material[] armor={Material.DIAMOND_HELMET,Material.DIAMOND_CHESTPLATE,Material.DIAMOND_LEGGINGS,Material.DIAMOND_BOOTS};
        return enchanted(armor[Math.abs(seed)%armor.length],2);
    }

    private ItemStack rareSword() {
        ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,2);
        sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT,1);
        sword.addUnsafeEnchantment(Enchantment.DURABILITY,3);
        return sword;
    }

    private void giveFullRareCombatSet(Player p,String source) {
        p.getInventory().addItem(enchanted(Material.DIAMOND_HELMET,2));
        p.getInventory().addItem(enchanted(Material.DIAMOND_CHESTPLATE,2));
        p.getInventory().addItem(enchanted(Material.DIAMOND_LEGGINGS,2));
        p.getInventory().addItem(enchanted(Material.DIAMOND_BOOTS,2));
        p.getInventory().addItem(rareSword());
        finishReward(p,"FULL "+source+" P2 SET + S2/FIRE I SWORD",true);
        plugin.noteSimPremiumReward(p,source+" full P2/S2F1 set");
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
        if("koth".equalsIgnoreCase(type)) {
            p.sendMessage(EraCore.colorText("&6--- KOTH Crate ---"));
            p.sendMessage(EraCore.colorText("&fPearls, Healing II, glowstone, gunpowder, obsidian and diamonds."));
            p.sendMessage(EraCore.colorText("&eRare: &fP2 diamond pieces and Sharp II / Fire I swords."));
            p.sendMessage(EraCore.colorText("&6Jackpot: &fa full P2 KOTH set + S2/Fire I sword."));
            p.sendMessage(EraCore.colorText("&7Baseline PvP remains Protection I / Sharpness I."));
        } else if("donor".equalsIgnoreCase(type)) {
            p.sendMessage(EraCore.colorText("&6--- Donor Ender Chest ---"));
            p.sendMessage(EraCore.colorText("&aBasic &7< &fSilver &7< &6Gold &7< &bPlatinum"));
            p.sendMessage(EraCore.colorText("&7Higher tiers improve progression quantities and carefully raise P2/S2F1 jackpot odds."));
            p.sendMessage(EraCore.colorText("&7No Speed II or Fire Resistance bottles; Speed II is permanent."));
        } else {
            p.sendMessage(EraCore.colorText("&e--- Vote Chest ---"));
            p.sendMessage(EraCore.colorText("&f$250 &724%  &f8 Pearls &718%  &f16 Iron &715%  &f12 Obsidian &712%"));
            p.sendMessage(EraCore.colorText("&f4 Diamonds &710%  &f6 Heals &78%  &f16 Wart &75%  &f8 Glowstone &74%"));
            p.sendMessage(EraCore.colorText("&7Useful progression every roll; P2/S2F1 and a full set are extremely rare jackpots."));
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
        if(tier==3) return "Gold";
        if(tier==2) return "Silver";
        return "Basic";
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
        if(name.contains("gold")) return 3;
        if(name.contains("silver")) return 2;
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
        int z=spawn.getBlockZ()+36;

        // Minimal fallback only. Even outside production Kraken mode EraCore
        // never constructs a crate court, beacon, frame, pad or sign.
        voteCrate=new Location(w,spawn.getBlockX()-8,y,z);
        donorCrate=new Location(w,spawn.getBlockX(),y,z);
        kothCrate=new Location(w,spawn.getBlockX()+8,y,z);
        voteCrate.getBlock().setType(Material.CHEST);
        donorCrate.getBlock().setType(Material.ENDER_CHEST);
        kothCrate.getBlock().setType(Material.CHEST);
    }

    private void save() {
        try { data.save(file); }
        catch(IOException e) { plugin.getLogger().warning("Could not save rewards.yml: "+e.getMessage()); }
    }
}
