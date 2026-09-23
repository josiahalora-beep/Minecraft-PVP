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
    private Location utilityEnderChest;
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

        // Kraken itself is the architecture. These are four isolated utility
        // blocks placed into existing open floor space—never booths, pads,
        // pillars or a generated crate court.
        voteCrate=findOpenKrakenUtility(w,plugin.getConfig().getInt("spawn.crates.vote-x",-12),y,z);
        donorCrate=findOpenKrakenUtility(w,plugin.getConfig().getInt("spawn.crates.donor-x",-4),y,z);
        kothCrate=findOpenKrakenUtility(w,plugin.getConfig().getInt("spawn.crates.koth-x",4),y,z);
        utilityEnderChest=findOpenKrakenUtility(w,plugin.getConfig().getInt("spawn.crates.ender-x",12),y,z);

        if(voteCrate==null || donorCrate==null || kothCrate==null || utilityEnderChest==null ||
           !placeFunctionalCrate(voteCrate,Material.CHEST,"vote") ||
           !placeFunctionalCrate(donorCrate,Material.CHEST,"donor") ||
           !placeFunctionalCrate(kothCrate,Material.CHEST,"koth") ||
           !placeFunctionalCrate(utilityEnderChest,Material.ENDER_CHEST,"ender")) {
            plugin.getLogger().severe("Kraken utility placement refused: no safe open schematic floor was found. No fallback structure was built.");
            return;
        }

        rememberCrate("vote",voteCrate);
        rememberCrate("donor",donorCrate);
        rememberCrate("koth",kothCrate);
        plugin.getLogger().info("Kraken utility blocks ready: vote="+locText(voteCrate)+
            " donor="+locText(donorCrate)+" koth="+locText(kothCrate)+
            " ender="+locText(utilityEnderChest));
    }

    private Location findOpenKrakenUtility(World w,int preferredX,int preferredY,int preferredZ) {
        for(int radius=0;radius<=8;radius++) {
            for(int dx=-radius;dx<=radius;dx++) {
                for(int dz=-radius;dz<=radius;dz++) {
                    if(radius>0 && Math.abs(dx)!=radius && Math.abs(dz)!=radius) continue;
                    Location l=new Location(w,preferredX+dx,preferredY,preferredZ+dz);
                    Block feet=l.getBlock();
                    Block head=l.clone().add(0,1,0).getBlock();
                    Block floor=l.clone().subtract(0,1,0).getBlock();
                    if(feet.getType()!=Material.AIR || head.getType()!=Material.AIR) continue;
                    if(!floor.getType().isSolid()) continue;
                    return l.getBlock().getLocation();
                }
            }
        }
        return null;
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
        long hours=Math.max(1,plugin.getConfig().getLong("rewards.donor-key-hours",24L));
        long cooldown=hours*3600000L;
        if(now-last<cooldown) return;

        // Rank value is convenience/volume, not a higher PvP ceiling:
        // Basic 1, Silver 2, Gold 3, Platinum 4 keys per claim period.
        int amount=Math.max(1,Math.min(4,donor));
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

    boolean commandRedeem(Player p,String[] args) {
        String wanted=args.length==0?"all":args[0].toLowerCase(Locale.ENGLISH);
        if(!Arrays.asList("all","vote","donor","koth").contains(wanted)) {
            p.sendMessage(EraCore.colorText("&cUsage: /redeem [all|vote|donor|koth]"));
            return true;
        }

        int redeemed=0;
        int safety=2304;
        while(safety-->0) {
            String type=nextRedeemableType(p,wanted);
            if(type.isEmpty()) break;

            int donorTier="donor".equals(type)?highestDonorKeyTier(p):0;
            if(!consumeKey(p,type)) break;
            if(plugin.isBotIdentity(p.getName())) plugin.consumeSimPendingKey(p.getName(),type,1);
            giveReward(p,type,donorTier);
            redeemed++;
        }

        if(redeemed==0)
            p.sendMessage(EraCore.colorText("&cNo matching crate keys found."));
        else
            p.sendMessage(EraCore.colorText("&aRedeemed &f"+redeemed+" &acrate key"+(redeemed==1?"":"s")+
                " &7without leaving your base."));
        return true;
    }

    private String nextRedeemableType(Player p,String wanted) {
        if(!"all".equals(wanted)) return hasKey(p,wanted)?wanted:"";
        // Event keys first, then donor, then vote. This mirrors perceived value
        // and makes /redeem all deterministic.
        if(hasKey(p,"koth")) return "koth";
        if(hasKey(p,"donor")) return "donor";
        if(hasKey(p,"vote")) return "vote";
        return "";
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
        p.sendMessage(EraCore.colorText("&bDonor Chest &7- Basic / Silver / Gold / Platinum keys"));
        p.sendMessage(EraCore.colorText("&5Ender Chest &7- personal storage utility"));
        p.sendMessage(EraCore.colorText("&7Kraken remains the spawn build; these are isolated utility blocks only."));
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
            giveNamedPvpSet(p,1,1,0,"&eVote");
            finishReward(p,"Vote P1/S1 Set",true);
        } else {
            p.getInventory().addItem(keyItem("donor",1,1));
            finishReward(p,"1 Basic Donor Crate Key",true);
        }
    }

    private void giveDonorReward(Player p,int r,int tier) {
        tier=Math.max(1,Math.min(4,tier));
        int pearls=8+tier*4;
        int obsidian=12+tier*6;
        int heals=6+tier*3;
        int gp=12+tier*6;
        int glow=8+tier*4;
        int wart=16+tier*8;
        double cash=250.0+tier*250.0;

        if(r<1200) {
            money(p,cash,"$"+((int)cash));
        } else if(r<2500) {
            item(p,new ItemStack(Material.ENDER_PEARL,pearls),pearls+" Ender Pearls");
        } else if(r<3700) {
            item(p,new ItemStack(Material.OBSIDIAN,obsidian),obsidian+" Obsidian");
        } else if(r<4900) {
            for(int i=0;i<heals;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,heals+" Splash Health II",false);
        } else if(r<6200) {
            p.getInventory().addItem(new ItemStack(Material.NETHER_STALK,wart));
            p.getInventory().addItem(new ItemStack(Material.GLOWSTONE_DUST,glow));
            p.getInventory().addItem(new ItemStack(Material.SULPHUR,gp));
            p.getInventory().addItem(new ItemStack(Material.SPECKLED_MELON,8+tier*4));
            finishReward(p,donorTierName(tier)+" Brewing Bundle",false);
        } else if(r<7400) {
            item(p,new ItemStack(Material.DIAMOND,4+tier*2),(4+tier*2)+" Diamonds");
        } else if(r<8500) {
            p.getInventory().addItem(new ItemStack(Material.IRON_INGOT,16+tier*8));
            p.getInventory().addItem(new ItemStack(Material.HOPPER,Math.max(1,tier)));
            p.getInventory().addItem(new ItemStack(Material.CHEST,8+tier*2));
            finishReward(p,donorTierName(tier)+" Base Supply Bundle",false);
        } else if(r<9600) {
            giveNamedPvpSet(p,1,1,0,donorColor(tier)+donorTierName(tier));
            finishReward(p,donorTierName(tier)+" P1/S1 Supply Set",true);
        } else {
            // A very rare event key is valuable without directly selling P2 gear.
            p.getInventory().addItem(keyItem("koth",1,0));
            finishReward(p,"1 KOTH Crate Key",true);
        }
    }

    private void giveKothReward(Player p,int r) {
        if(r<1300) {
            item(p,new ItemStack(Material.ENDER_PEARL,16),"16 Ender Pearls");
        } else if(r<2500) {
            for(int i=0;i<12;i++) p.getInventory().addItem(new ItemStack(Material.POTION,1,(short)16421));
            finishReward(p,"12 Splash Health II",false);
        } else if(r<3600) {
            item(p,new ItemStack(Material.GLOWSTONE_DUST,24),"24 Glowstone Dust");
        } else if(r<4700) {
            item(p,new ItemStack(Material.SULPHUR,32),"32 Gunpowder");
        } else if(r<5700) {
            item(p,new ItemStack(Material.OBSIDIAN,32),"32 Obsidian");
        } else if(r<6500) {
            item(p,new ItemStack(Material.DIAMOND,12),"12 Diamonds");
        } else if(r<7350) {
            ItemStack looting=namedTool(Material.DIAMOND_SWORD,"&6KOTH Looting Blade");
            looting.addUnsafeEnchantment(Enchantment.LOOT_BONUS_MOBS,4);
            looting.addUnsafeEnchantment(Enchantment.DURABILITY,3);
            item(p,looting,"KOTH Looting IV Sword");
        } else if(r<8200) {
            ItemStack fortune=namedTool(Material.DIAMOND_PICKAXE,"&6KOTH Fortune Pick");
            fortune.addUnsafeEnchantment(Enchantment.LOOT_BONUS_BLOCKS,4);
            fortune.addUnsafeEnchantment(Enchantment.DURABILITY,3);
            item(p,fortune,"KOTH Fortune IV Pickaxe");
        } else if(r<9300) {
            giveNamedPvpSet(p,2,2,0,"&6KOTH");
            finishReward(p,"KOTH P2/S2 Set",true);
        } else {
            ItemStack sword=namedTool(Material.DIAMOND_SWORD,"&cKOTH Fire Blade");
            sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,2);
            sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT,1);
            sword.addUnsafeEnchantment(Enchantment.DURABILITY,3);
            item(p,sword,"Sharpness II / Fire Aspect I KOTH Blade");
        }
    }

    private void giveNamedPvpSet(Player p,int prot,int sharp,int fire,String prefix) {
        p.getInventory().addItem(namedArmor(Material.DIAMOND_HELMET,prot,prefix+" Helmet"));
        p.getInventory().addItem(namedArmor(Material.DIAMOND_CHESTPLATE,prot,prefix+" Chestplate"));
        p.getInventory().addItem(namedArmor(Material.DIAMOND_LEGGINGS,prot,prefix+" Leggings"));
        p.getInventory().addItem(namedArmor(Material.DIAMOND_BOOTS,prot,prefix+" Boots"));
        p.getInventory().addItem(namedSword(sharp,fire,prefix+" Sword"));
        p.updateInventory();
    }

    private ItemStack namedArmor(Material type,int prot,String name) {
        ItemStack item=new ItemStack(type);
        item.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL,prot);
        item.addUnsafeEnchantment(Enchantment.DURABILITY,2);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(EraCore.colorText(name));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack namedSword(int sharp,int fire,String name) {
        ItemStack item=new ItemStack(Material.DIAMOND_SWORD);
        item.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,sharp);
        if(fire>0) item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT,fire);
        item.addUnsafeEnchantment(Enchantment.DURABILITY,2);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(EraCore.colorText(name));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack namedTool(Material type,String name) {
        ItemStack item=new ItemStack(type);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(EraCore.colorText(name));
        item.setItemMeta(meta);
        return item;
    }

    private String donorColor(int tier) {
        if(tier>=4) return "&b";
        if(tier==3) return "&6";
        if(tier==2) return "&f";
        return "&a";
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
            p.sendMessage(EraCore.colorText("&fPearls, heals, glowstone, gunpowder, obsidian and diamonds."));
            p.sendMessage(EraCore.colorText("&eRare: &fLooting IV / Fortune IV event tools and a full named P2/S2 set."));
            p.sendMessage(EraCore.colorText("&cPrestige: &fSharp II / Fire Aspect I KOTH Blade."));
        } else if("donor".equalsIgnoreCase(type)) {
            p.sendMessage(EraCore.colorText("&6--- Donor Keys ---"));
            p.sendMessage(EraCore.colorText("&aBasic &7< &fSilver &7< &6Gold &7< &bPlatinum"));
            p.sendMessage(EraCore.colorText("&7Ranks grant more keys; higher key tiers scale quantities, not the PvP enchant ceiling."));
            p.sendMessage(EraCore.colorText("&fRewards: &7brew/base bundles, pearls, heals, valuables, named P1/S1 sets; very rare KOTH key."));
        } else {
            p.sendMessage(EraCore.colorText("&e--- Vote Chest ---"));
            p.sendMessage(EraCore.colorText("&7Economy/resources, pearls, heals and a rare named P1/S1 set."));
            p.sendMessage(EraCore.colorText("&7Very rare: one Basic Donor Crate Key. No free donor-rank upgrade."));
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
        lore.add(EraCore.colorText("&7Right-click the spawn crate or use &f/redeem&7."));
        lore.add(EraCore.colorText("&8Redeemable from your faction base"));
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
        if("donor".equalsIgnoreCase(type)) return "Donor Chest";
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
        voteCrate=new Location(w,spawn.getBlockX()-12,y,z);
        donorCrate=new Location(w,spawn.getBlockX()-4,y,z);
        kothCrate=new Location(w,spawn.getBlockX()+4,y,z);
        utilityEnderChest=new Location(w,spawn.getBlockX()+12,y,z);
        voteCrate.getBlock().setType(Material.CHEST);
        donorCrate.getBlock().setType(Material.CHEST);
        kothCrate.getBlock().setType(Material.CHEST);
        utilityEnderChest.getBlock().setType(Material.ENDER_CHEST);
    }

    private void save() {
        try { data.save(file); }
        catch(IOException e) { plugin.getLogger().warning("Could not save rewards.yml: "+e.getMessage()); }
    }
}
