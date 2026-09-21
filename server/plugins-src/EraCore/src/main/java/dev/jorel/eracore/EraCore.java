package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.*;

@SuppressWarnings("deprecation")
public final class EraCore extends JavaPlugin implements Listener, CommandExecutor {
    private final DecimalFormat moneyFmt = new DecimalFormat("#,##0.00");
    private File ranksFile, kitsFile, economyFile, factionsFile, statsFile, metricsFile;
    private YamlConfiguration ranksData, kitsData, economyData, factionsData, statsData;
    private final Map<String, Faction> factions = new LinkedHashMap<String, Faction>();
    private final Map<String, String> claimOwners = new HashMap<String, String>();
    private final Set<String> factionChat = new HashSet<String>();
    private final Map<String, Double> power = new HashMap<String, Double>();
    private final Map<String, Long> pearlCooldowns = new HashMap<String, Long>();
    private final Map<String, String> combatPreparedFight = new HashMap<String, String>();
    private ItemStack[] ownerTestContents;
    private ItemStack[] ownerTestArmor;
    private Location ownerTestReturnLocation;
    private double ownerTestHealth = 20.0;
    private int ownerTestFood = 20;
    private String ownerTestName = "";
    private String ownerTestFightId = "";
    private final Map<Material, Double> sellPrices = new LinkedHashMap<Material, Double>();
    private final Map<String, ShopItem> buyItems = new LinkedHashMap<String, ShopItem>();
    private long[] tickTimes;
    private int metricsTask = -1;
    private WarpManager warpManager;
    private SimWorldDirector simWorld;
    private SimChatDirector simChat;
    private SpawnPresenceDirector spawnPresence;
    private HcfClassDirector hcfClasses;
    private HcfBaseBuilder hcfBaseBuilder;
    private HcfZoneDisplayDirector hcfZones;
    private LogicalTabListDirector logicalTab;
    private SpawnRewardsDirector spawnRewards;

    enum Rank {
        MEMBER(0, "&7[Member]", 24),
        BASIC(1, "&a[Basic]", 24),
        SILVER(2, "&f&l[Silver]&r", 18),
        GOLD(3, "&6&l[Gold]&r", 12),
        PLATINUM(4, "&b&l[Platinum]&r", 8),
        OWNER(99, "&4&l[OWNER]&r", 0);
        final int level;
        final String prefix;
        final int cooldownHours;
        Rank(int level, String prefix, int cooldownHours) {
            this.level = level; this.prefix = prefix; this.cooldownHours = cooldownHours;
        }
        static Rank parse(String s) {
            if(s==null) return null;
            String n=s.toUpperCase(Locale.ENGLISH);
            // Migrate the earlier placeholder hierarchy without invalidating
            // existing ranks.yml files.
            if("VIP".equals(n)) n="BASIC";
            else if("ELITE".equals(n)) n="SILVER";
            else if("LEGEND".equals(n)) n="GOLD";
            else if("TITAN".equals(n)) n="PLATINUM";
            try { return Rank.valueOf(n); }
            catch (Exception e) { return null; }
        }
    }

    static final class ShopItem {
        final String key;
        final Material material;
        final short data;
        final double price;
        ShopItem(String key, Material material, short data, double price) {
            this.key = key; this.material = material; this.data = data; this.price = price;
        }
    }

    static final class Faction {
        String name;
        String leader;
        final Set<String> members = new LinkedHashSet<String>();
        final Set<String> invites = new LinkedHashSet<String>();
        final Set<String> claims = new LinkedHashSet<String>();
        Location home;
        double dtr = 1.1;
        long dtrFrozenUntil = 0L;
        boolean wasRaidable = false;
    }

    static final class BlockOp {
        final int x,y,z;
        final Material material;
        final byte data;
        BlockOp(int x, int y, int z, Material material) { this(x,y,z,material,(byte)0); }
        BlockOp(int x, int y, int z, Material material, byte data) {
            this.x=x; this.y=y; this.z=z; this.material=material; this.data=data;
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        initFiles();
        initShops();
        loadFactions();
        warpManager = new WarpManager(this);
        warpManager.bootstrapDefaults();
        configureWorldBorders();
        simWorld = new SimWorldDirector(this);
        simChat = new SimChatDirector(this, simWorld);
        spawnPresence = new SpawnPresenceDirector(this, warpManager);
        hcfClasses = new HcfClassDirector(this);
        hcfBaseBuilder = new HcfBaseBuilder(this);
        hcfZones = new HcfZoneDisplayDirector(this, warpManager);
        logicalTab = new LogicalTabListDirector(this, simWorld);
        spawnRewards = new SpawnRewardsDirector(this, warpManager);
        bindCommands();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(spawnRewards, this);
        hookTickTimes();
        startMetrics();
        startPowerRegen();
        startDtrRegen();
        simWorld.start();
        logicalTab.start();
        new BukkitRunnable() {
            public void run() {
                if (simWorld != null) simWorld.refreshVisibleCombat();
            }
        }.runTaskTimer(this, 80L, 40L);
        simChat.start();
        hcfClasses.start();
        hcfZones.start();
        spawnPresence.start();
        spawnRewards.start();

        if (getConfig().getBoolean("base-builder.repair-existing-on-start", true)) {
            new BukkitRunnable() {
                public void run() {
                    if (simWorld != null) simWorld.repairExistingBaseTerrainAndClaims();
                }
            }.runTaskLater(this, 120L);
        }

        if (getConfig().getBoolean("map.auto-bootstrap", true) && !getConfig().getBoolean("map.complete", false)) {
            new BukkitRunnable() {
                public void run() {
                    World world = Bukkit.getWorlds().get(0);
                    bootstrapMap(world, false);
                }
            }.runTaskLater(this, 80L);
        }
        getLogger().info("EraCore 0.2 enabled: classic warps, paced sim chat, factions, economy and PvP baseline ready.");
    }

    @Override public void onDisable() {
        if (spawnRewards != null) spawnRewards.stop();
        if (spawnPresence != null) spawnPresence.stop();
        if (logicalTab != null) logicalTab.stop();
        if (hcfZones != null) hcfZones.stop();
        if (hcfClasses != null) hcfClasses.stop();
        if (hcfBaseBuilder != null) hcfBaseBuilder.stop();
        if (simChat != null) simChat.stop();
        if (simWorld != null) simWorld.stop();
        saveAll();
        if (metricsTask != -1) Bukkit.getScheduler().cancelTask(metricsTask);
    }

    private void bindCommands() {
        String[] cmds = {"rank","kit","kits","balance","pay","sell","buy","shop","vote","keys","crates","stats","f","spawn","setspawn","warp","warps","setwarp","delwarp","spawnpreset","msg","r","simchat","sotw","simworker","simcombat","safezone","teamfight","bard","archer","miner","rogue","simprobe","simmap","simstate","duelprep"};
        for (String c : cmds) getCommand(c).setExecutor(this);
    }

    private void initFiles() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        ranksFile = new File(getDataFolder(), "ranks.yml");
        kitsFile = new File(getDataFolder(), "kits.yml");
        economyFile = new File(getDataFolder(), "economy.yml");
        factionsFile = new File(getDataFolder(), "factions.yml");
        statsFile = new File(getDataFolder(), "stats.yml");
        metricsFile = new File(getDataFolder(), "metrics.csv");
        ranksData = YamlConfiguration.loadConfiguration(ranksFile);
        kitsData = YamlConfiguration.loadConfiguration(kitsFile);
        economyData = YamlConfiguration.loadConfiguration(economyFile);
        factionsData = YamlConfiguration.loadConfiguration(factionsFile);
        statsData = YamlConfiguration.loadConfiguration(statsFile);
    }

    private void initShops() {
        sellPrices.clear();
        buyItems.clear();

        // Slow-inflation classic economy. Farming is the reliable money source;
        // mining helps bootstrap but cannot instantly finance endless PvP sets.
        sellPrices.put(Material.SUGAR_CANE, 3.0);
        sellPrices.put(Material.CACTUS, 2.25);
        sellPrices.put(Material.PUMPKIN, 5.5);
        sellPrices.put(Material.MELON, 0.75);
        sellPrices.put(Material.WHEAT, 1.25);
        sellPrices.put(Material.CARROT_ITEM, 1.25);
        sellPrices.put(Material.POTATO_ITEM, 1.25);
        sellPrices.put(Material.IRON_INGOT, 8.0);
        sellPrices.put(Material.GOLD_INGOT, 14.0);
        sellPrices.put(Material.DIAMOND, 60.0);

        // Finished PvP consumables are an expensive convenience. Mature factions
        // save heavily by brewing instead of buying finished pots.
        addBuy("healthpot", Material.POTION, (short)16421, 135.0);
        addBuy("speedpot", Material.POTION, (short)8226, 95.0);
        addBuy("fireres", Material.POTION, (short)8259, 110.0);
        addBuy("pearl", Material.ENDER_PEARL, (short)0, 160.0);
        addBuy("obsidian", Material.OBSIDIAN, (short)0, 30.0);
        addBuy("iron", Material.IRON_INGOT, (short)0, 18.0);
        addBuy("steak", Material.COOKED_BEEF, (short)0, 6.0);

        // Farm/bootstrap supplies. A $500 start can establish one modest farm,
        // but not simultaneously buy a PvP loadout.
        addBuy("cane", Material.SUGAR_CANE, (short)0, 9.0);
        addBuy("cactus", Material.CACTUS, (short)0, 7.0);
        addBuy("pumpkinseed", Material.PUMPKIN_SEEDS, (short)0, 8.0);
        addBuy("melonseed", Material.MELON_SEEDS, (short)0, 5.0);
        addBuy("sand", Material.SAND, (short)0, 2.0);
        addBuy("dirt", Material.DIRT, (short)0, 1.0);
        addBuy("waterbucket", Material.WATER_BUCKET, (short)0, 35.0);
        addBuy("chest", Material.CHEST, (short)0, 20.0);
        addBuy("hopper", Material.HOPPER, (short)0, 65.0);
        addBuy("brewingstand", Material.BREWING_STAND_ITEM, (short)0, 140.0);
        addBuy("redstone", Material.REDSTONE, (short)0, 4.0);
        addBuy("netherwart", Material.NETHER_STALK, (short)0, 12.0);
        addBuy("glowstone", Material.GLOWSTONE_DUST, (short)0, 12.0);
        addBuy("gunpowder", Material.SULPHUR, (short)0, 18.0);
        addBuy("glisteringmelon", Material.SPECKLED_MELON, (short)0, 24.0);
        addBuy("sugar", Material.SUGAR, (short)0, 6.0);
        addBuy("magmacream", Material.MAGMA_CREAM, (short)0, 22.0);
        addBuy("glass", Material.GLASS, (short)0, 2.0);
        addBuy("book", Material.BOOK, (short)0, 12.0);
        addBuy("lapis", Material.INK_SACK, (short)4, 5.0);
    }

    double sellUnitPrice(Material material) {
        Double price = sellPrices.get(material);
        return price == null ? 0.0 : price;
    }

    double buyUnitPrice(String key) {
        ShopItem item = buyItems.get(key.toLowerCase(Locale.ENGLISH));
        return item == null ? Double.POSITIVE_INFINITY : item.price;
    }

    private void addBuy(String key, Material m, short data, double price) {
        buyItems.put(key, new ShopItem(key,m,data,price));
    }

    @EventHandler(priority=EventPriority.HIGHEST) public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean bot = isBotIdentity(p.getName());

        maybeClaimOwner(p);
        ensurePlayerData(p);
        Rank r = bot ? simRankFor(p.getName()) : getRank(p.getName());
        applyCreatorTag(p);
        p.setPlayerListName(color(identityPrefix(p.getName(), r) + rankNameColor(r) + p.getName()));
        if (bot && logicalTab != null) logicalTab.onPhysicalJoin(p);
        if (!bot && spawnRewards != null) spawnRewards.onHumanJoin(p);

        // HOT/COLD body promotion is an implementation detail, not a logical
        // login. Suppress those technical join messages and fan reactions.
        if (bot) {
            e.setJoinMessage(null);
        } else {
            e.setJoinMessage(color((r==Rank.OWNER?"&4&l[OWNER ONLINE] &r":"&8[&a+&8] ") + identityPrefix(p.getName(), r) + rankNameColor(r) + p.getName()));
            if (simChat != null) simChat.onJoin(p);
        }

        if (spawnPresence != null) spawnPresence.showTo(p);
        if (logicalTab != null && !bot) logicalTab.showTo(p);
    }

    @EventHandler(priority=EventPriority.HIGHEST) public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (isBotIdentity(p.getName())) {
            if (simWorld != null && simWorld.hasCombatReservation(p.getName())) {
                simWorld.releaseCombatLoadout(p);
                combatPreparedFight.remove(p.getName().toLowerCase(Locale.ENGLISH));
            }
            e.setQuitMessage(null);
            if (logicalTab != null) logicalTab.onPhysicalQuit(p.getName());
            return;
        }
        Rank r = getRank(p.getName());
        e.setQuitMessage(color("&8[&c-&8] " + identityPrefix(p.getName(), r) + rankNameColor(r) + p.getName()));
    }

    void broadcastSimulatedPresence(String name, boolean joining) {
        if (!hasHumanOnline()) return;
        Rank rank = simRankFor(name);
        String marker = joining ? "&8[&a+&8] " : "&8[&c-&8] ";
        Bukkit.broadcastMessage(color(marker + identityPrefix(name,rank) + rankNameColor(rank) + name + factionSuffix(name)));
    }

    @EventHandler(priority=EventPriority.HIGHEST) public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        final String name = p.getName().toLowerCase(Locale.ENGLISH);
        final Faction f = factionOf(p.getName());
        if (factionChat.contains(name) && f != null) {
            e.setCancelled(true);
            final String msg = color("&7[&aF&7] &f" + p.getName() + "&7: &f" + e.getMessage());
            for (String member : f.members) {
                Player target = Bukkit.getPlayerExact(member);
                if (target != null) target.sendMessage(msg);
            }
            String ownerName = getConfig().getString("owner.name", "");
            Player owner = ownerName.isEmpty() ? null : Bukkit.getPlayerExact(ownerName);
            if (owner != null && !f.members.contains(owner.getName())) owner.sendMessage(msg);
            return;
        }
        Rank r = isBotIdentity(p.getName()) ? simRankFor(p.getName()) : getRank(p.getName());
        e.setFormat(color(identityPrefix(p.getName(), r) + rankNameColor(r) + p.getName() + factionSuffix(p.getName()) + "&7: &f") + "%2$s");
        final String chatText = e.getMessage();
        if (simChat != null) {
            Bukkit.getScheduler().runTask(this, new Runnable() {
                public void run() { simChat.onHumanChat(p, chatText); }
            });
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onTaggedTeleportCommand(PlayerCommandPreprocessEvent e) {
        Player p=e.getPlayer();
        if(hcfZones==null || !hcfZones.isTagged(p) || isOwnerPlayer(p)) return;

        String raw=e.getMessage()==null?"":e.getMessage().trim().toLowerCase(Locale.ENGLISH);
        if(raw.startsWith("/")) raw=raw.substring(1);
        String[] parts=raw.split("\\s+");
        if(parts.length==0) return;
        String cmd=parts[0];

        boolean blocked=cmd.equals("spawn") || cmd.equals("home") || cmd.equals("warp") ||
            cmd.equals("tp") || cmd.equals("teleport") || cmd.equals("tpa") ||
            cmd.equals("tpaccept") || cmd.equals("back");
        if((cmd.equals("f") || cmd.equals("faction") || cmd.equals("fac")) &&
            parts.length>1 && parts[1].equals("home")) blocked=true;

        if(blocked) {
            e.setCancelled(true);
            p.sendMessage(color("&cYou cannot teleport while combat tagged. &7"+hcfZones.tagSeconds(p)+"s remaining."));
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void onPearlUse(PlayerInteractEvent e) {
        ItemStack item=e.getItem();
        if(item==null||item.getType()!=Material.ENDER_PEARL) return;
        Action action=e.getAction();
        if(action!=Action.RIGHT_CLICK_AIR&&action!=Action.RIGHT_CLICK_BLOCK) return;

        Player p=e.getPlayer();
        String key=p.getName().toLowerCase(Locale.ENGLISH);
        long now=System.currentTimeMillis();
        Long until=pearlCooldowns.get(key);
        if(until!=null&&until>now) {
            e.setCancelled(true);
            long left=(long)Math.ceil((until-now)/1000.0);
            p.sendMessage(color("&7Ender pearl: &c"+left+"s"));
            p.updateInventory();
            return;
        }

        long cooldown=Math.max(1,getConfig().getInt("pvp.pearl-cooldown-seconds",16))*1000L;
        pearlCooldowns.put(key,now+cooldown);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void onClaimInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (getRank(p.getName()) == Rank.OWNER) return;

        String owner = claimOwners.get(claimKey(e.getClickedBlock().getLocation()));
        if (owner == null) return;

        Faction own = factionOf(p.getName());
        if (own != null && own.name.equalsIgnoreCase(owner)) return;

        Faction target = factions.get(owner.toLowerCase(Locale.ENGLISH));
        if (target != null && isRaidable(target)) return;

        Material type = e.getClickedBlock().getType();
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.FURNACE ||
            type == Material.BURNING_FURNACE || type == Material.HOPPER || type == Material.BREWING_STAND ||
            type == Material.ANVIL || type == Material.ENCHANTMENT_TABLE || type == Material.WOODEN_DOOR ||
            type == Material.IRON_DOOR_BLOCK || type == Material.TRAP_DOOR || type == Material.FENCE_GATE) {
            e.setCancelled(true);
            p.sendMessage(color("&c" + owner + " is not raidable."));
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void onDamage(EntityDamageByEntityEvent e) {
        if(hcfZones!=null && hcfZones.handleDamage(e)) return;
        if (simWorld != null && simWorld.sotwProtectionActive()) e.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void onExplosion(EntityExplodeEvent e) {
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            String owner = claimOwners.get(claimKey(b.getLocation()));
            if (owner == null) continue;
            Faction target = factions.get(owner.toLowerCase(Locale.ENGLISH));
            if (target != null) {
                // HCF raids are opened by DTR, never by TNT/cannoning.
                it.remove();
            }
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void onBreak(BlockBreakEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void onPlace(BlockPlaceEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        String n = e.getEntity().getName().toLowerCase(Locale.ENGLISH);
        String preparedFight=combatPreparedFight.get(n);
        boolean testFight=preparedFight!=null && preparedFight.startsWith("TESTTEAM_");

        if(testFight) {
            combatPreparedFight.remove(n);
            e.getDrops().clear();
            e.setDroppedExp(0);
            if(simWorld!=null) simWorld.onTestFightDeath(e.getEntity().getName(),
                e.getEntity().getKiller()==null?"":e.getEntity().getKiller().getName());
            return;
        }

        if (simWorld != null && simWorld.settleCombatDeath(e.getEntity())) {
            combatPreparedFight.remove(n);
        }
        power.put(n, Math.max(-10.0, getPower(n) - 2.0));
        recordHumanDeathStats(e.getEntity(),e.getEntity().getKiller());

        Faction f = factionOf(e.getEntity().getName());
        if (f != null) {
            f.dtr -= getConfig().getDouble("dtr.loss-per-death", 1.0);
            f.dtrFrozenUntil = System.currentTimeMillis() + getConfig().getLong("dtr.freeze-seconds-after-death", 180L) * 1000L;
            boolean nowRaidable = isRaidable(f);
            if (nowRaidable && !f.wasRaidable) {
                Bukkit.broadcastMessage(color("&c" + f.name + " is now raidable."));
            }
            f.wasRaidable = nowRaidable;
            e.getEntity().sendMessage(color("&7DTR: " + dtrColor(f) + fmtDtr(f.dtr) + "&7/&f" + fmtDtr(maxDtr(f))));
        }

        saveFactions();
        if (simWorld != null) simWorld.onAuthorityDeath(e.getEntity().getName(), f == null ? "" : f.name, f == null ? 0.0 : f.dtr, f != null && isRaidable(f));
        if (simChat != null) simChat.onDeath(e);
    }

    private void maybeClaimOwner(Player p) {
        if (!getConfig().getBoolean("owner.claim-first-human", true)) return;
        if (!getConfig().getString("owner.uuid", "").isEmpty()) {
            if (getConfig().getString("owner.uuid").equals(p.getUniqueId().toString())) {
                setRank(p.getName(), Rank.OWNER);
                if (!p.isOp()) p.setOp(true);
            }
            return;
        }
        if (isBotIdentity(p.getName())) return;
        getConfig().set("owner.uuid", p.getUniqueId().toString());
        getConfig().set("owner.name", p.getName());
        saveConfig();
        setRank(p.getName(), Rank.OWNER);
        p.setOp(true);
        Bukkit.broadcastMessage(color("&7Server owner: &4" + p.getName()));
        p.sendMessage(color("&7You are now the permanent &4[Owner]&7 for this local server."));
    }

    private void ensurePlayerData(Player p) {
        String key = p.getName().toLowerCase(Locale.ENGLISH);
        if (!ranksData.contains(key)) setRank(p.getName(), Rank.MEMBER);
        if (!economyData.contains("balances." + key)) {
            economyData.set("balances." + key, getConfig().getDouble("economy.starting-balance", 500.0));
            saveYaml(economyData, economyFile);
        }
        if (!power.containsKey(key)) power.put(key, 10.0);
    }

    private Rank getRank(String name) {
        String raw = ranksData.getString(name.toLowerCase(Locale.ENGLISH), "MEMBER");
        Rank r = Rank.parse(raw);
        return r == null ? Rank.MEMBER : r;
    }

    private Rank effectiveRank(String name) {
        if (simWorld != null && simWorld.contains(name) && isBotIdentity(name)) return simRankFor(name);
        return getRank(name);
    }

    private void setRank(String name, Rank rank) {
        ranksData.set(name.toLowerCase(Locale.ENGLISH), rank.name());
        saveYaml(ranksData, ranksFile);
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            applyCreatorTag(p);
            p.setPlayerListName(color(identityPrefix(p.getName(), rank) +
                (rank==Rank.OWNER?"&c&l":"&f") + p.getName()));
        }
    }

    boolean isCreatorIdentity(String name) {
        for (String creator : getConfig().getStringList("creator-tag.creators")) {
            if (creator.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private String identityPrefix(String name, Rank rank) {
        String creator = isCreatorIdentity(name) ? getConfig().getString("creator-tag.chat-prefix", "&c&l[YT]&r ") : "";
        String staff="";
        if(simWorld!=null && simWorld.contains(name)) {
            String role=simWorld.simulatedStaffRole(name);
            if("ADMIN".equalsIgnoreCase(role)) staff="&c[Admin] ";
            else if("MOD".equalsIgnoreCase(role)) staff="&2[Mod] ";
        }
        String rankPrefix = rank.prefix;
        return staff + creator + rankPrefix + " ";
    }

    boolean isBotIdentity(String name) {
        if (simWorld != null && simWorld.contains(name)) return true;
        for (String prefix : getConfig().getStringList("owner.ignored-prefixes")) {
            if (name.toLowerCase(Locale.ENGLISH).startsWith(prefix.toLowerCase(Locale.ENGLISH))) return true;
        }
        return false;
    }

    boolean hasHumanOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isBotIdentity(p.getName())) return true;
        }
        return false;
    }

    private Rank simRankFor(String name) {
        int level=simWorld==null?-1:simWorld.simulatedDonorLevelFor(name);
        if(level>=0) {
            if(level>=4) return Rank.PLATINUM;
            if(level==3) return Rank.GOLD;
            if(level==2) return Rank.SILVER;
            if(level==1) return Rank.BASIC;
            return Rank.MEMBER;
        }

        int roll = Math.abs(name.toLowerCase(Locale.ENGLISH).hashCode()) % 100;
        if (roll < 66) return Rank.MEMBER;
        if (roll < 82) return Rank.BASIC;
        if (roll < 92) return Rank.SILVER;
        if (roll < 98) return Rank.GOLD;
        return Rank.PLATINUM;
    }

    private String rankNameColor(Rank rank) {
        if(rank==Rank.OWNER) return "&c&l";
        if(rank==Rank.PLATINUM) return "&b";
        if(rank==Rank.GOLD) return "&6";
        if(rank==Rank.SILVER) return "&f";
        if(rank==Rank.BASIC) return "&a";
        return "&f";
    }

    private String factionSuffix(String name) {
        Faction real = factionOf(name);
        String faction = real == null ? "" : real.name;
        if (faction.isEmpty() && simWorld != null) faction = simWorld.factionOf(name);
        return faction.isEmpty() ? "" : " &8[&7" + faction + "&8]";
    }

    void broadcastSimulatedChat(String name, String message) {
        Rank rank = simRankFor(name);
        Bukkit.broadcastMessage(color(identityPrefix(name, rank) + rankNameColor(rank) + name + factionSuffix(name) + "&7: &f" + message));
    }

    void sendSimulatedPrivate(Player target, String from, String message) {
        Rank rank = simRankFor(from);
        target.sendMessage(color("&8[&7From " + identityPrefix(from, rank) + rankNameColor(rank) + from + factionSuffix(from) + "&8] &f" + message));
    }

    void broadcastCommunityEvent(String message) {
        if (!hasHumanOnline()) return;
        Bukkit.broadcastMessage(color(message));
    }

    void sendSimulatedStaffChat(String from,String message) {
        String ownerName=getConfig().getString("owner.name","");
        Player owner=ownerName.isEmpty()?null:Bukkit.getPlayerExact(ownerName);
        if(owner==null) return;
        owner.sendMessage(color("&8[&bStaff&8] &f"+from+"&7: &f"+message));
    }


    private void applyCreatorTag(Player p) {
        if (!getConfig().getBoolean("creator-tag.enabled", true)) return;
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam("youtube");
        if (team == null) team = board.registerNewTeam("youtube");
        String headPrefix = getConfig().getString("creator-tag.head-prefix", "&c&l[YT]&r &f");
        team.setPrefix(color(headPrefix));
        if (isCreatorIdentity(p.getName())) {
            team.addPlayer(p);
        } else if (team.hasPlayer(p)) {
            team.removePlayer(p);
        }
    }

    boolean sameFactionForClasses(String a, String b) {
        Faction fa = factionOf(a);
        Faction fb = factionOf(b);
        if (fa != null && fb != null && fa.name.equalsIgnoreCase(fb.name)) return true;
        if (simWorld != null) {
            String sa = simWorld.factionOf(a);
            String sb = simWorld.factionOf(b);
            return !sa.isEmpty() && sa.equalsIgnoreCase(sb);
        }
        return false;
    }

    boolean isHcfSafezone(Location location) {
        return hcfZones != null ? hcfZones.isSafe(location) : isSafezone(location);
    }

    boolean simWorldProtectionActive() {
        return simWorld != null && simWorld.sotwProtectionActive();
    }

    boolean isOwnerPlayer(Player p) {
        return p != null && (getRank(p.getName()) == Rank.OWNER || p.hasPermission("eracore.owner"));
    }

    String factionNameFor(String player) {
        Faction real=factionOf(player);
        if(real!=null) return real.name;
        return simWorld==null?"":simWorld.factionOf(player);
    }

    String factionDtrDisplay(String player) {
        String name=factionNameFor(player);
        if(name==null || name.isEmpty()) return "";
        Faction f=factions.get(name.toLowerCase(Locale.ENGLISH));
        if(f==null) return "&7"+name;
        String state=isRaidable(f)?" &c[RAIDABLE]":" "+dtrColor(f)+"["+fmtDtr(f.dtr)+" DTR]";
        return dtrColor(f)+f.name+state;
    }

    int simulatedDonorLevel(String name) {
        return simRankFor(name).level;
    }

    String simulatedPrimaryPrefix(String name) {
        if (isCreatorIdentity(name)) return "&c&l[YT]&r ";
        return simRankFor(name).prefix + " ";
    }

    void creditEconomy(String name,double amount) {
        if(name==null || amount<=0) return;
        if(simWorld!=null && simWorld.contains(name)) {
            simWorld.creditPlayerBalance(name,amount);
            return;
        }
        setBalance(name,balance(name)+amount);
    }

    int simPendingKeyCount(String name,String type) {
        return simWorld==null?0:simWorld.pendingKeyCount(name,type);
    }

    void addSimPendingKey(String name,String type,int amount) {
        if(simWorld!=null) simWorld.addPendingKey(name,type,amount);
    }

    void consumeSimPendingKey(String name,String type,int amount) {
        if(simWorld!=null) simWorld.consumePendingKey(name,type,amount);
    }

    void rewardVoteParty() {
        if(simWorld!=null) simWorld.rewardVoteParty();
    }

    void recordSimulatedVote(String name) {
        if(spawnRewards!=null) spawnRewards.registerSimVote(name);
    }

    void recordSimulatedDonorKey(String name) {
        if(spawnRewards!=null) spawnRewards.registerSimDonorKey(name);
    }

    String pendingCrateType(String name) {
        return spawnRewards==null?"":spawnRewards.keyTypeForPending(name);
    }

    Location crateLocation(String type) {
        return spawnRewards==null?null:spawnRewards.crateLocation(type);
    }

    void ensurePhysicalPendingKey(Player p,String type) {
        if(spawnRewards!=null) spawnRewards.ensurePhysicalPendingKey(p,type);
    }

    int logicalPopulationCount() {
        return simWorld == null ? 0 : simWorld.allIdentityNames().size();
    }

    static String colorText(String s) {
        return color(s);
    }

    private double balance(String name) {
        String key = "balances." + name.toLowerCase(Locale.ENGLISH);
        if (!economyData.contains(key)) economyData.set(key, getConfig().getDouble("economy.starting-balance", 500.0));
        return economyData.getDouble(key);
    }

    private void setBalance(String name, double amount) {
        economyData.set("balances." + name.toLowerCase(Locale.ENGLISH), Math.max(0, amount));
        saveYaml(economyData, economyFile);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String c = command.getName().toLowerCase(Locale.ENGLISH);
        if (c.equals("rank")) return cmdRank(sender,args);
        if (!(sender instanceof Player)) {
            if (c.equals("simprobe")) {
                sender.sendMessage(probeString());
                return true;
            }
            sender.sendMessage("Player-only command.");
            return true;
        }
        Player p = (Player)sender;
        if (c.equals("kit")) return cmdKit(p,args);
        if (c.equals("kits")) return cmdKits(p);
        if (c.equals("balance")) return cmdBalance(p,args);
        if (c.equals("pay")) return cmdPay(p,args);
        if (c.equals("sell")) return cmdSell(p,args);
        if (c.equals("buy")) return cmdBuy(p,args);
        if (c.equals("shop")) return cmdShop(p);
        if (c.equals("vote")) return spawnRewards != null && spawnRewards.commandVote(p,args);
        if (c.equals("keys")) return spawnRewards != null && spawnRewards.commandKeys(p);
        if (c.equals("crates")) return spawnRewards != null && spawnRewards.commandCrates(p,args);
        if (c.equals("stats")) return cmdStats(p,args);
        if (c.equals("f")) return cmdFaction(p,args);
        if (c.equals("spawn")) return cmdSpawn(p);
        if (c.equals("setspawn")) return cmdSetSpawn(p);
        if (c.equals("warp")) return cmdWarp(p,args);
        if (c.equals("warps")) return cmdWarps(p);
        if (c.equals("setwarp")) return cmdSetWarp(p,args);
        if (c.equals("delwarp")) return cmdDelWarp(p,args);
        if (c.equals("spawnpreset")) return cmdSpawnPreset(p,args);
        if (c.equals("msg")) return cmdMessage(p,args);
        if (c.equals("r")) return cmdReply(p,args);
        if (c.equals("simchat")) return cmdSimChat(p,args);
        if (c.equals("sotw")) return cmdSotw(p,args);
        if (c.equals("simworker")) return cmdSimWorker(p,args);
        if (c.equals("simcombat")) return cmdSimCombat(p,args);
        if (c.equals("safezone")) return hcfZones != null && hcfZones.command(p,args);
        if (c.equals("teamfight")) return cmdTeamFight(p,args);
        if (c.equals("bard")) return cmdClassInfo(p,"bard");
        if (c.equals("archer")) return cmdClassInfo(p,"archer");
        if (c.equals("miner")) return cmdClassInfo(p,"miner");
        if (c.equals("rogue")) return cmdClassInfo(p,"rogue");
        if (c.equals("simprobe")) {
            p.sendMessage(color("&e" + probeString()));
            return true;
        }
        if (c.equals("simmap")) return cmdSimMap(p,args);
        if (c.equals("simstate")) return cmdSimState(p,args);
        if (c.equals("duelprep")) return cmdDuelPrep(p);
        return false;
    }

    private boolean ownerOnly(Player p) {
        if (getRank(p.getName()) == Rank.OWNER || p.hasPermission("eracore.owner")) return true;
        p.sendMessage(color("&cOwner only."));
        return false;
    }

    private boolean cmdSpawn(Player p) {
        Location target=warpManager.getSpawn();
        if(hcfZones!=null && hcfZones.isTagged(p) && !isOwnerPlayer(p)) {
            p.sendMessage(color("&cYou cannot /spawn while combat tagged. &7"+hcfZones.tagSeconds(p)+"s remaining."));
            return true;
        }
        p.teleport(target);
        p.sendMessage(color("&7Teleported to spawn."));
        return true;
    }

    private boolean cmdSetSpawn(Player p) {
        if (!ownerOnly(p)) return true;
        warpManager.setSpawn(p.getLocation());
        warpManager.setWarp("spawn", p.getLocation());
        p.getWorld().setSpawnLocation(p.getLocation().getBlockX(),p.getLocation().getBlockY(),p.getLocation().getBlockZ());
        configureWorldBorders();
        if(hcfZones!=null) hcfZones.syncMainSpawn(p.getLocation());
        p.sendMessage(color("&aSpawn set. World borders and Safezone re-centered here."));
        return true;
    }

    private boolean cmdWarp(Player p, String[] a) {
        if (a.length != 1) return cmdWarps(p);
        Location l = warpManager.getWarp(a[0]);
        if (l == null) {
            p.sendMessage(color("&cWarp not found. &7Use /warps."));
            return true;
        }
        if(hcfZones!=null && hcfZones.isTagged(p) && !isOwnerPlayer(p)) {
            p.sendMessage(color("&cYou cannot use warps while combat tagged. &7"+hcfZones.tagSeconds(p)+"s remaining."));
            return true;
        }
        p.teleport(l);
        p.sendMessage(color("&7Warped to &f" + a[0].toLowerCase(Locale.ENGLISH) + "&7."));
        return true;
    }

    private boolean cmdWarps(Player p) {
        List<String> names = warpManager.names();
        p.sendMessage(color("&6Warps: &fspawn" + (names.isEmpty() ? "" : ", " + join(names, ", "))));
        return true;
    }

    private boolean cmdSetWarp(Player p, String[] a) {
        if (!ownerOnly(p)) return true;
        if (a.length != 1) {
            p.sendMessage("/setwarp <name>");
            return true;
        }
        warpManager.setWarp(a[0], p.getLocation());
        p.sendMessage(color("&aWarp set: &f" + a[0].toLowerCase(Locale.ENGLISH)));
        return true;
    }

    private boolean cmdDelWarp(Player p, String[] a) {
        if (!ownerOnly(p)) return true;
        if (a.length != 1) {
            p.sendMessage("/delwarp <name>");
            return true;
        }
        if (!warpManager.deleteWarp(a[0])) p.sendMessage(color("&cWarp not found."));
        else p.sendMessage(color("&aWarp removed: &f" + a[0].toLowerCase(Locale.ENGLISH)));
        return true;
    }

    private boolean cmdSpawnPreset(Player p, String[] a) {
        if (!ownerOnly(p)) return true;
        if (a.length != 1 || !a[0].equalsIgnoreCase("playman2013")) {
            p.sendMessage("/spawnpreset playman2013");
            return true;
        }
        warpManager.applyPlayman2013Preset(p.getWorld());
        p.sendMessage(color("&a2013 DaeGonner-inspired spawn preset applied."));
        p.sendMessage(color("&7Spawn is set to 260.5, 70, 180.5. Finalize the interior shop/enchant points with /setwarp after the schematic is pasted."));
        return true;
    }

    private boolean cmdTeamFight(Player p,String[] a) {
        if(!ownerOnly(p)) return true;
        if(simWorld==null) return true;

        if(a.length==0 || a[0].equalsIgnoreCase("test") || a[0].equalsIgnoreCase("start")) {
            if(!p.getWorld().equals(Bukkit.getWorlds().get(0))) {
                p.sendMessage(color("&cRun the teamfight test in the Overworld."));
                return true;
            }
            if(simWorld.hasVisibleFight()) {
                p.sendMessage(color("&cA visible fight is already active. &7Use /teamfight stop first."));
                return true;
            }

            int size=5;
            if(a.length>=2) {
                try { size=Integer.parseInt(a[1]); }
                catch(Exception ignored) { size=5; }
            }
            size=Math.max(3,Math.min(5,size));

            if(simWorld.startTeamFightTest(p,size)) {
                beginOwnerTeamFight(p);
                int friendlyBots=size-1;
                p.sendMessage(color("&a"+size+"v"+size+" test started. &fYou + "+friendlyBots+" simulated teammates &7vs &f"+size+" enemies&7."));
                p.sendMessage(color("&7Use &f/teamfight test 3&7, &f4&7, or &f5&7 for the calibration ladder. Watch &f/simprobe&7 during combat."));
            } else {
                p.sendMessage(color("&cCould not start the teamfight test. &7Need two sufficiently large simulated factions."));
            }
            return true;
        }

        if(a[0].equalsIgnoreCase("stop")) {
            simWorld.stopVisibleFightTest();
            restoreOwnerTeamFight();
            p.sendMessage(color("&eTeamfight test stopped. &7Your original inventory/location are restored and bots return to normal work."));
            return true;
        }

        if(a[0].equalsIgnoreCase("status")) {
            p.sendMessage(color("&7Visible fight: &f"+simWorld.visibleFightSummary()));
            return true;
        }

        p.sendMessage("/teamfight <test [3|4|5]|stop|status>");
        return true;
    }

    private void beginOwnerTeamFight(Player p) {
        ownerTestName=p.getName();
        ownerTestFightId=simWorld.currentVisibleFightId();
        ownerTestContents=cloneItems(p.getInventory().getContents());
        ownerTestArmor=cloneItems(p.getInventory().getArmorContents());
        ownerTestReturnLocation=p.getLocation().clone();
        ownerTestHealth=p.getHealth();
        ownerTestFood=p.getFoodLevel();

        combatPreparedFight.put(p.getName().toLowerCase(Locale.ENGLISH),ownerTestFightId);
        prepareHcfCombatKit(p,SimWorldDirector.CombatClass.DIAMOND);
        Location spawn=simWorld.ownerTestSpawn();
        if(spawn!=null) p.teleport(spawn);
        p.sendMessage(color("&cTEST FIGHT: &7Deaths do not affect your DTR/economy and your original inventory is restored on /teamfight stop."));
    }

    private ItemStack[] cloneItems(ItemStack[] src) {
        if(src==null) return null;
        ItemStack[] out=new ItemStack[src.length];
        for(int i=0;i<src.length;i++) out[i]=src[i]==null?null:src[i].clone();
        return out;
    }

    private void restoreOwnerTeamFight() {
        if(ownerTestName==null || ownerTestName.isEmpty()) return;
        Player owner=Bukkit.getPlayerExact(ownerTestName);
        combatPreparedFight.remove(ownerTestName.toLowerCase(Locale.ENGLISH));
        if(owner!=null) {
            owner.getInventory().clear();
            owner.getInventory().setArmorContents(new ItemStack[4]);
            if(ownerTestContents!=null) owner.getInventory().setContents(cloneItems(ownerTestContents));
            if(ownerTestArmor!=null) owner.getInventory().setArmorContents(cloneItems(ownerTestArmor));
            owner.setFoodLevel(ownerTestFood);
            owner.setHealth(Math.max(1.0,Math.min(owner.getMaxHealth(),ownerTestHealth)));
            owner.updateInventory();
            if(ownerTestReturnLocation!=null) owner.teleport(ownerTestReturnLocation);
        }
        ownerTestContents=null;
        ownerTestArmor=null;
        ownerTestReturnLocation=null;
        ownerTestName="";
        ownerTestFightId="";
    }

    private boolean cmdSimCombat(Player p, String[] a) {
        if (simWorld == null) return true;

        if (a.length == 0 || a[0].equalsIgnoreCase("sync")) {
            SimWorldDirector.CombatAssignment ca = simWorld.combatAssignmentFor(p.getName());
            if (ca == null) {
                p.sendMessage("SIMCOMBAT none");
                return true;
            }

            String k=p.getName().toLowerCase(Locale.ENGLISH);
            String prepared=combatPreparedFight.get(k);
            if (!ca.fightId.equals(prepared) && !ca.fightId.startsWith("TESTTEAM_")) {
                if (!simWorld.reserveCombatLoadout(p.getName(),ca.combatClass,ca.fightId)) {
                    p.sendMessage("SIMCOMBAT none reason=stock");
                    return true;
                }
            }

            prepareCombatProjection(p,ca);
            p.sendMessage("SIMCOMBAT " + ca.wire());
            return true;
        }

        if (a[0].equalsIgnoreCase("release")) {
            String result=simWorld.releaseCombatLoadout(p);
            combatPreparedFight.remove(p.getName().toLowerCase(Locale.ENGLISH));
            p.sendMessage("SIMCOMBAT released " + result);
            return true;
        }

        if (a[0].equalsIgnoreCase("status")) {
            if (!ownerOnly(p)) return true;
            p.sendMessage(color("&7Visible fight: &f" + simWorld.visibleFightSummary()));
            return true;
        }

        p.sendMessage("/simcombat <sync|release|status>");
        return true;
    }

    private void prepareCombatProjection(Player p, SimWorldDirector.CombatAssignment ca) {
        String k = p.getName().toLowerCase(Locale.ENGLISH);
        String prepared = combatPreparedFight.get(k);

        if (!ca.fightId.equals(prepared)) {
            prepareHcfCombatKit(p,ca.combatClass);
            combatPreparedFight.put(k,ca.fightId);
        }

        World world = Bukkit.getWorld(ca.world);
        if (world == null) world = Bukkit.getWorlds().get(0);
        if (world != null) {
            Location target = new Location(world,ca.x + 0.5,ca.y,ca.z + 0.5);
            if (!p.getWorld().equals(world) || p.getLocation().distanceSquared(target) > 28.0*28.0) {
                p.teleport(target);
            }
        }

        p.setFoodLevel(20);
        if (p.getHealth() < 16.0) p.setHealth(20.0);
    }

    private void prepareHcfCombatKit(Player p, SimWorldDirector.CombatClass type) {
        PlayerInventory inv=p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);

        if (type == SimWorldDirector.CombatClass.BARD) {
            inv.setHelmet(armor(Material.GOLD_HELMET,2));
            inv.setChestplate(armor(Material.GOLD_CHESTPLATE,2));
            inv.setLeggings(armor(Material.GOLD_LEGGINGS,2));
            inv.setBoots(armor(Material.GOLD_BOOTS,2));
            inv.setItem(0,sword(Material.IRON_SWORD,2));
            for(int slot=1;slot<=5;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(6,new ItemStack(Material.POTION,1,(short)8259));
            inv.setItem(7,new ItemStack(Material.POTION,1,(short)8226));
            inv.setItem(8,new ItemStack(Material.ENDER_PEARL,8));
            for(int slot=9;slot<=27;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(28,new ItemStack(Material.BLAZE_ROD,1));
            inv.setItem(29,new ItemStack(Material.GHAST_TEAR,1));
            inv.setItem(30,new ItemStack(Material.FEATHER,1));
            inv.setItem(31,new ItemStack(Material.MAGMA_CREAM,1));
            inv.setItem(32,new ItemStack(Material.BLAZE_POWDER,8));
            inv.setItem(33,new ItemStack(Material.SUGAR,16));
            inv.setItem(34,new ItemStack(Material.POTION,1,(short)8226));
        } else if (type == SimWorldDirector.CombatClass.ARCHER) {
            inv.setHelmet(armor(Material.LEATHER_HELMET,3));
            inv.setChestplate(armor(Material.LEATHER_CHESTPLATE,3));
            inv.setLeggings(armor(Material.LEATHER_LEGGINGS,3));
            inv.setBoots(armor(Material.LEATHER_BOOTS,3));
            inv.setItem(0,sword(Material.DIAMOND_SWORD,2));
            ItemStack bow=new ItemStack(Material.BOW,1);
            bow.addUnsafeEnchantment(Enchantment.ARROW_DAMAGE,4);
            bow.addUnsafeEnchantment(Enchantment.ARROW_FIRE,1);
            bow.addUnsafeEnchantment(Enchantment.ARROW_KNOCKBACK,1);
            inv.setItem(1,bow);
            inv.setItem(2,new ItemStack(Material.ARROW,64));
            for(int slot=3;slot<=5;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(6,new ItemStack(Material.POTION,1,(short)8259));
            inv.setItem(7,new ItemStack(Material.POTION,1,(short)8226));
            inv.setItem(8,new ItemStack(Material.ENDER_PEARL,8));
            for(int slot=9;slot<=29;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(30,new ItemStack(Material.POTION,1,(short)8226));
        } else if (type == SimWorldDirector.CombatClass.ROGUE) {
            inv.setHelmet(armor(Material.CHAINMAIL_HELMET,2));
            inv.setChestplate(armor(Material.CHAINMAIL_CHESTPLATE,2));
            inv.setLeggings(armor(Material.CHAINMAIL_LEGGINGS,2));
            inv.setBoots(armor(Material.CHAINMAIL_BOOTS,2));
            inv.setItem(0,sword(Material.DIAMOND_SWORD,2));
            for(int slot=1;slot<=4;slot++) inv.setItem(slot,new ItemStack(Material.GOLD_SWORD,1));
            inv.setItem(5,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(6,new ItemStack(Material.POTION,1,(short)8259));
            inv.setItem(7,new ItemStack(Material.POTION,1,(short)8226));
            inv.setItem(8,new ItemStack(Material.ENDER_PEARL,8));
            for(int slot=9;slot<=31;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(32,new ItemStack(Material.POTION,1,(short)8226));
        } else if (type == SimWorldDirector.CombatClass.MINER) {
            inv.setHelmet(armor(Material.IRON_HELMET,2));
            inv.setChestplate(armor(Material.IRON_CHESTPLATE,2));
            inv.setLeggings(armor(Material.IRON_LEGGINGS,2));
            inv.setBoots(armor(Material.IRON_BOOTS,2));
            inv.setItem(0,sword(Material.DIAMOND_SWORD,2));
            for(int slot=1;slot<=5;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(6,new ItemStack(Material.POTION,1,(short)8259));
            inv.setItem(7,new ItemStack(Material.POTION,1,(short)8226));
            inv.setItem(8,new ItemStack(Material.ENDER_PEARL,8));
            for(int slot=9;slot<=27;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(28,new ItemStack(Material.POTION,1,(short)8226));
        } else {
            inv.setHelmet(armor(Material.DIAMOND_HELMET,4));
            inv.setChestplate(armor(Material.DIAMOND_CHESTPLATE,4));
            inv.setLeggings(armor(Material.DIAMOND_LEGGINGS,4));
            inv.setBoots(armor(Material.DIAMOND_BOOTS,4));
            inv.setItem(0,pvpSword(Material.DIAMOND_SWORD,4,2));
            for(int slot=1;slot<=5;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(6,new ItemStack(Material.POTION,1,(short)8259));
            inv.setItem(7,new ItemStack(Material.POTION,1,(short)8226));
            inv.setItem(8,new ItemStack(Material.ENDER_PEARL,8));
            for(int slot=9;slot<=27;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(28,new ItemStack(Material.POTION,1,(short)8226));
        }

        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.updateInventory();
    }

    private boolean cmdSimWorker(Player p, String[] a) {
        boolean simIdentity = simWorld != null && simWorld.contains(p.getName());

        if (a.length == 0 || a[0].equalsIgnoreCase("sync")) {
            if (!simIdentity) {
                if (!ownerOnly(p)) return true;
                p.sendMessage(color("&7Physical worker candidates: &f" + simWorld.workerCandidateCount() +
                    " &7hot budget: &f" + adaptiveHotBodyBudget(getConfig().getInt("combat-director.hot-body-budget",8))));
                return true;
            }

            SimWorldDirector.WorkerTask task = simWorld.workerTaskFor(p.getName());
            prepareWorkerProjection(p,task);
            if("crate".equals(task.action) && task.keyType!=null && !task.keyType.isEmpty())
                ensurePhysicalPendingKey(p,task.keyType);
            int humans = humanOnlineCount();
            int workerBudget = adaptiveWorkerBudget(getConfig().getInt("worker-pool.max-bodies",4));
            Rank simRank=effectiveRank(p.getName());
            boolean tagged=hcfZones!=null && hcfZones.isTagged(p);
            p.sendMessage("SIMWORKER " + task.wire() + " humans=" + humans + " budget=" + workerBudget +
                " rank=" + simRank.name() + " tagged=" + (tagged?1:0));
            return true;
        }

        if (a[0].equalsIgnoreCase("deposit")) {
            if (!simIdentity) {
                p.sendMessage(color("&cSimulation identities only."));
                return true;
            }
            p.sendMessage("SIMDEPOSIT " + simWorld.depositEmbodiedWorker(p));
            return true;
        }

        if (a[0].equalsIgnoreCase("stash")) {
            if (!simIdentity) {
                p.sendMessage(color("&cSimulation identities only."));
                return true;
            }
            p.sendMessage("SIMSTASH " + simWorld.stashEmbodiedWorker(p));
            return true;
        }

        if (a[0].equalsIgnoreCase("status")) {
            if (!ownerOnly(p)) return true;
            p.sendMessage(color("&7Worker candidates: &f" + simWorld.workerCandidateCount() +
                " &7adaptive body budget: &f" + adaptiveWorkerBudget(getConfig().getInt("worker-pool.max-bodies",16)) +
                " &7creator bodies: &f" + getConfig().getStringList("worker-pool.creator-bodies").size()));
            return true;
        }

        p.sendMessage("/simworker <sync|deposit|stash|status>");
        return true;
    }

    private int humanOnlineCount() {
        int n = 0;
        for (Player x : Bukkit.getOnlinePlayers()) if (!isBotIdentity(x.getName())) n++;
        return n;
    }

    private int adaptiveWorkerBudget(int configured) {
        configured = Math.max(1, Math.min(16, configured));
        int creatorFloor = Math.max(1, Math.min(configured,
            getConfig().getStringList("worker-pool.creator-bodies").size()));

        double[] s = tickStats();
        if (s == null) return Math.max(creatorFloor, Math.min(10, configured));

        double p95 = s[1];
        if (p95 >= 42.0) return creatorFloor;
        if (p95 >= 32.0) return Math.max(creatorFloor, Math.min(7, configured));
        if (p95 >= 24.0) return Math.max(creatorFloor, Math.min(9, configured));
        if (p95 >= 16.0) return Math.max(creatorFloor, Math.min(12, configured));
        return configured;
    }

    private void prepareWorkerProjection(Player p, SimWorldDirector.WorkerTask task) {
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return;

        int y = Math.max(3, task.y);
        Location target = new Location(world,task.x + 0.5,y,task.z + 0.5);

        // Never bypass HCF combat tag with internal worker projection.
        boolean tagged=hcfZones!=null && hcfZones.isTagged(p);
        if (!tagged && (!p.getWorld().equals(world) || p.getLocation().distanceSquared(target) > 48.0 * 48.0)) {
            p.teleport(target);
        }

        Material tool = Material.WOOD_PICKAXE;
        if ("mine".equals(task.action) || "gather".equals(task.action) || "supply".equals(task.action)) tool = Material.IRON_PICKAXE;
        else if ("build".equals(task.action)) tool = Material.STONE;
        else if ("farm".equals(task.action)) tool = Material.IRON_HOE;
        else if ("brew".equals(task.action)) tool = Material.BREWING_STAND_ITEM;
        else if ("gear".equals(task.action)) tool = Material.BOOK;
        else if ("patrol".equals(task.action)) tool = Material.DIAMOND_SWORD;
        else if ("safe".equals(task.action)) tool = Material.COOKED_BEEF;
        else if ("scout".equals(task.action)) tool = Material.COMPASS;
        else if ("crate".equals(task.action)) tool = "donor".equalsIgnoreCase(task.keyType) ? Material.BLAZE_ROD : Material.TRIPWIRE_HOOK;

        ItemStack hand = p.getInventory().getItem(0);
        if (hand == null || hand.getType() != tool) {
            p.getInventory().setItem(0,new ItemStack(tool,1));
        }
        p.getInventory().setHeldItemSlot(0);
        // Ordinary worker sync must not magically heal/refeed the physical body.
        // Survival is handled by the Mineflayer inventory/food/potion layer.
        // Combat projection still receives its deliberate benchmark loadout.
    }

    private boolean cmdSotw(Player p, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("status")) {
            p.sendMessage(color("&7" + simWorld.sotwStatus()));
            return true;
        }
        if (a[0].equalsIgnoreCase("reset")) {
            if (!ownerOnly(p)) return true;
            simWorld.resetForSotw();
            Bukkit.broadcastMessage(color("&6SOTW started. &7Everyone is factionless and recruiting is open."));
            return true;
        }
        if (a[0].equalsIgnoreCase("end")) {
            if (!ownerOnly(p)) return true;
            simWorld.endSotwProtection();
            Bukkit.broadcastMessage(color("&cSOTW protection has ended."));
            return true;
        }
        p.sendMessage("/sotw <status|reset|end>");
        return true;
    }

    private boolean cmdClassInfo(Player p, String which) {
        if (which.equals("bard")) {
            p.sendMessage(color("&6Bard &7- full gold armor. Support aura class."));
            p.sendMessage(color("&7Hold blaze rod=strength, ghast tear=regen, feather=jump, magma cream=fire resistance."));
            p.sendMessage(color("&7Right-click support items spends Bard energy for stronger short buffs."));
        } else if (which.equals("archer")) {
            p.sendMessage(color("&eArcher &7- full leather armor. Speed III and ranged pressure."));
            p.sendMessage(color("&7Bow damage scales modestly with distance."));
        } else if (which.equals("miner")) {
            p.sendMessage(color("&fMiner &7- full iron armor. Haste II + night vision; invisibility below the configured mining Y."));
        } else if (which.equals("rogue")) {
            p.sendMessage(color("&7Rogue - full chainmail. Speed III + Jump II; gold-sword backstab with cooldown."));
        }
        return true;
    }

    private boolean cmdSimChat(Player p, String[] a) {
        if (!ownerOnly(p)) return true;
        if (a.length == 0 || a[0].equalsIgnoreCase("status")) {
            p.sendMessage(color("&7Sim chat: " + (simChat.enabled() ? "&aenabled" : "&cdisabled")));
            return true;
        }
        if (a[0].equalsIgnoreCase("on")) {
            simChat.setEnabled(true);
            p.sendMessage(color("&aSim chat enabled."));
            return true;
        }
        if (a[0].equalsIgnoreCase("off")) {
            simChat.setEnabled(false);
            p.sendMessage(color("&cSim chat disabled."));
            return true;
        }
        if (a[0].equalsIgnoreCase("pulse")) {
            simChat.pulse();
            return true;
        }
        p.sendMessage("/simchat <on|off|status|pulse>");
        return true;
    }

    private boolean cmdMessage(Player p, String[] a) {
        if (a.length < 2) {
            p.sendMessage("/msg <player> <message>");
            return true;
        }
        String targetName = a[0];
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < a.length; i++) {
            if (b.length() > 0) b.append(' ');
            b.append(a[i]);
        }

        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            online.sendMessage(color("&8[&7From &f" + p.getName() + "&8] &f" + b.toString()));
            p.sendMessage(color("&8[&7To &f" + online.getName() + "&8] &f" + b.toString()));
            return true;
        }

        if (simWorld != null && simWorld.contains(targetName)) {
            p.sendMessage(color("&8[&7To &f" + targetName + factionSuffix(targetName) + "&8] &f" + b.toString()));

            // Semantic casual conversation goes through the optional AI sidecar.
            // Trade/economy messages stay authoritative in the deterministic
            // transaction engine so an LLM can never create items or money.
            if(simWorld.requestPrivateAi(p,targetName,b.toString())) return true;

            final String reply = simWorld.handlePrivate(p, targetName, b.toString());
            if (reply != null) {
                final String from = targetName;
                Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                    public void run() {
                        if (p.isOnline()) sendSimulatedPrivate(p, from, reply);
                    }
                }, 22L + new Random().nextInt(35));
            }
            return true;
        }

        p.sendMessage(color("&cPlayer not found."));
        return true;
    }

    private boolean cmdReply(final Player p, String[] a) {
        if (a.length < 1) {
            p.sendMessage("/r <message>");
            return true;
        }
        StringBuilder b = new StringBuilder();
        for (String s : a) {
            if (b.length() > 0) b.append(' ');
            b.append(s);
        }
        if (simWorld == null) {
            p.sendMessage(color("&cNobody to reply to."));
            return true;
        }
        final String reply = simWorld.handleReply(p, b.toString());
        if (reply == null) {
            p.sendMessage(color("&cNobody to reply to."));
            return true;
        }
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            public void run() {
                if (p.isOnline()) p.sendMessage(color("&8[&7Reply&8] &f" + reply));
            }
        }, 18L + new Random().nextInt(28));
        return true;
    }

    void queueSimBaseBuild(String faction, String preset, String trapPreset, int x, int y, int z) {
        if (hcfBaseBuilder != null) hcfBaseBuilder.queueBase(faction,preset,trapPreset,x,y,z);
    }

    void queueSimTerrainRepair(String faction, String preset, String trapPreset, int x, int y, int z) {
        if (hcfBaseBuilder != null) hcfBaseBuilder.queueTerrainRepair(faction,preset,trapPreset,x,y,z);
    }

    void queueSimFoundationRepair(String faction, String preset, String trapPreset, int x, int y, int z) {
        if (hcfBaseBuilder != null) hcfBaseBuilder.queueFoundationRepair(faction,preset,trapPreset,x,y,z);
    }

    int[] evaluateSimBaseSite(int x, int z, int radius) {
        if (hcfBaseBuilder == null) return new int[]{64,999,999};
        return hcfBaseBuilder.evaluateSite(x,z,radius);
    }

    void queueSimBrewerBuild(String faction, int x, int y, int z) {
        if (hcfBaseBuilder != null) hcfBaseBuilder.queueBrewer(faction,x,y,z);
    }

    void queueSimFarmBuild(String faction, String crop, int x, int y, int z) {
        if (hcfBaseBuilder != null) hcfBaseBuilder.queueFarm(faction,crop,x,y,z);
    }

    void queueSimTrapAddon(String faction, String trapPreset, int x, int y, int z) {
        if (hcfBaseBuilder != null) hcfBaseBuilder.queueTrapAddon(faction,trapPreset,x,y,z);
    }

    double balanceForSimTrade(String name) {
        return balance(name);
    }

    void changeBalanceForSimTrade(String name, double delta) {
        setBalance(name, balance(name) + delta);
    }

    boolean deliverSimTradeItem(Player p, String key, int qty) {
        if (qty <= 0) return false;
        Material m = null;
        short data = 0;
        if ("cane".equals(key)) m = Material.SUGAR_CANE;
        else if ("iron".equals(key)) m = Material.IRON_INGOT;
        else if ("obsidian".equals(key)) m = Material.OBSIDIAN;
        else if ("tnt".equals(key)) m = Material.TNT;
        else if ("pearl".equals(key)) m = Material.ENDER_PEARL;
        else if ("healthpot".equals(key)) { m = Material.POTION; data = (short)16421; }
        else if ("speedpot".equals(key)) { m = Material.POTION; data = (short)8226; }
        else if ("fireres".equals(key)) { m = Material.POTION; data = (short)8259; }
        if (m == null) return false;

        int left = qty;
        int max = Math.max(1, m.getMaxStackSize());
        while (left > 0) {
            int n = Math.min(left, max);
            Map<Integer,ItemStack> rem = p.getInventory().addItem(new ItemStack(m, n, data));
            if (!rem.isEmpty()) {
                for (ItemStack x : rem.values()) p.getWorld().dropItemNaturally(p.getLocation(), x);
            }
            left -= n;
        }
        return true;
    }

    boolean takeSimTradeItem(Player p, String key, int qty) {
        if (qty <= 0) return false;
        Material m = null;
        short data = 0;
        if ("cane".equals(key)) m = Material.SUGAR_CANE;
        else if ("iron".equals(key)) m = Material.IRON_INGOT;
        else if ("obsidian".equals(key)) m = Material.OBSIDIAN;
        else if ("tnt".equals(key)) m = Material.TNT;
        else if ("pearl".equals(key)) m = Material.ENDER_PEARL;
        else if ("healthpot".equals(key)) { m = Material.POTION; data = (short)16421; }
        else if ("speedpot".equals(key)) { m = Material.POTION; data = (short)8226; }
        else if ("fireres".equals(key)) { m = Material.POTION; data = (short)8259; }
        if (m == null) return false;

        int have = 0;
        for (ItemStack i : p.getInventory().getContents()) {
            if (i != null && i.getType() == m && (m != Material.POTION || i.getDurability() == data)) have += i.getAmount();
        }
        if (have < qty) return false;

        int left = qty;
        ItemStack[] inv = p.getInventory().getContents();
        for (int slot = 0; slot < inv.length && left > 0; slot++) {
            ItemStack i = inv[slot];
            if (i == null || i.getType() != m || (m == Material.POTION && i.getDurability() != data)) continue;
            int take = Math.min(left, i.getAmount());
            i.setAmount(i.getAmount() - take);
            left -= take;
            if (i.getAmount() <= 0) p.getInventory().setItem(slot, null);
            else p.getInventory().setItem(slot, i);
        }
        return left == 0;
    }

    private void configureWorldBorders() {
        if (!getConfig().getBoolean("map.manage-world-border", true)) return;
        try {
            List<World> worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) return;

            World overworld = worlds.get(0);
            Location center = (warpManager != null && warpManager.getSpawn() != null)
                ? warpManager.getSpawn()
                : overworld.getSpawnLocation();
            WorldBorder border = overworld.getWorldBorder();
            border.setCenter(center.getX(), center.getZ());
            border.setSize(Math.max(512.0, getConfig().getDouble("map.world-border", 3000.0)));

            World nether = Bukkit.getWorld("world_nether");
            if (nether != null) {
                WorldBorder nb = nether.getWorldBorder();
                nb.setCenter(center.getX() / 8.0, center.getZ() / 8.0);
                nb.setSize(Math.max(256.0, getConfig().getDouble("map.nether-border", 1000.0)));
            }

            getLogger().info("World borders configured around spawn: overworld=" +
                (int)border.getSize() + " nether=" +
                (nether == null ? "n/a" : Integer.toString((int)nether.getWorldBorder().getSize())));
        } catch (Throwable t) {
            getLogger().warning("Could not configure world border: " + t.getMessage());
        }
    }

    private boolean cmdRank(CommandSender s, String[] a) {
        boolean allowed = !(s instanceof Player) || getRank(s.getName()) == Rank.OWNER || s.hasPermission("eracore.owner");
        if (!allowed) {
            s.sendMessage(color("&cOwner only."));
            return true;
        }
        if (a.length != 3 || !a[0].equalsIgnoreCase("set")) {
            s.sendMessage("/rank set <player> <member|basic|silver|gold|platinum|owner>");
            return true;
        }
        Rank r = Rank.parse(a[2]);
        if (r == null) {
            s.sendMessage("Unknown rank.");
            return true;
        }
        setRank(a[1], r);
        s.sendMessage(color("&aSet " + a[1] + " to " + r.prefix));
        return true;
    }

    private boolean cmdKit(Player p, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("starter")) {
            String archetype=a.length>=2?a[1]:"diamond";
            return claimStarterKit(p,archetype);
        }

        if (a.length != 1) {
            p.sendMessage("/kit <starter [diamond|bard|archer|miner|rogue]|member|basic|silver|gold|platinum>");
            return true;
        }
        Rank requested = Rank.parse(a[0]);
        if (requested == null || requested == Rank.OWNER) {
            p.sendMessage(color("&cUnknown kit."));
            return true;
        }
        Rank own = effectiveRank(p.getName());
        if (own != Rank.OWNER && own.level < requested.level) {
            p.sendMessage(color("&cYour rank cannot use that kit."));
            return true;
        }
        String key = p.getName().toLowerCase(Locale.ENGLISH) + "." + requested.name().toLowerCase(Locale.ENGLISH);
        long now = System.currentTimeMillis();
        long next = kitsData.getLong(key, 0L);
        if (own != Rank.OWNER && now < next) {
            long mins = Math.max(1, (next-now)/60000L);
            p.sendMessage(color("&cThat kit is on cooldown for ~" + mins + " minutes."));
            return true;
        }
        grantKit(p, requested);
        if (simWorld != null && simWorld.contains(p.getName())) simWorld.applyDonorKitClaim(p.getName(), requested.level);
        if (own != Rank.OWNER) kitsData.set(key, now + requested.cooldownHours * 3600000L);
        saveYaml(kitsData, kitsFile);
        p.sendMessage(color("&aClaimed " + requested.prefix + " &akit. Higher ranks may also claim every lower donor kit."));
        return true;
    }

    private boolean claimStarterKit(Player p,String archetype) {
        String type=archetype==null?"diamond":archetype.toLowerCase(Locale.ENGLISH);
        if(!Arrays.asList("diamond","bard","archer","miner","rogue").contains(type)) {
            p.sendMessage(color("&cStarter types: &fdiamond, bard, archer, miner, rogue"));
            return true;
        }

        String key=p.getName().toLowerCase(Locale.ENGLISH)+".starter";
        long now=System.currentTimeMillis();
        long next=kitsData.getLong(key,0L);
        Rank own=effectiveRank(p.getName());
        if(own!=Rank.OWNER && now<next) {
            long mins=Math.max(1,(next-now)/60000L);
            p.sendMessage(color("&cStarter kit is on cooldown for ~"+mins+" minutes."));
            return true;
        }

        grantStarterKit(p,type);
        int hours=Math.max(1,getConfig().getInt("kits.starter-cooldown-hours",6));
        if(own!=Rank.OWNER) kitsData.set(key,now+hours*3600000L);
        saveYaml(kitsData,kitsFile);
        p.sendMessage(color("&aClaimed &f"+type+" &astarter kit."));
        return true;
    }

    private boolean cmdKits(Player p) {
        Rank own = effectiveRank(p.getName());
        p.sendMessage(color("&6--- Kit Cooldowns ---"));
        long starterNext=kitsData.getLong(p.getName().toLowerCase(Locale.ENGLISH)+".starter",0L);
        String starterStatus=own==Rank.OWNER || System.currentTimeMillis()>=starterNext ? "&aReady" :
            "&e"+Math.max(1,(starterNext-System.currentTimeMillis())/60000L)+"m";
        p.sendMessage(color("&fStarter &7- "+Math.max(1,getConfig().getInt("kits.starter-cooldown-hours",6))+"h - "+starterStatus));
        for (Rank r : new Rank[]{Rank.MEMBER,Rank.BASIC,Rank.SILVER,Rank.GOLD,Rank.PLATINUM}) {
            boolean eligible = own == Rank.OWNER || own.level >= r.level;
            String key = p.getName().toLowerCase(Locale.ENGLISH) + "." + r.name().toLowerCase(Locale.ENGLISH);
            long next = kitsData.getLong(key,0L);
            String status = !eligible ? "&cLocked" : (System.currentTimeMillis()>=next ? "&aReady" : "&e" + Math.max(1,(next-System.currentTimeMillis())/60000L) + "m");
            p.sendMessage(color(r.prefix + " &7- " + r.cooldownHours + "h - " + status));
        }
        return true;
    }

    private void grantKit(Player p, Rank r) {
        int prot=0,sharp=0,fire=0,pearls=0,pots=0,steak=32;
        boolean diamond=false;
        int speed=0,fireRes=0;

        switch(r) {
            case MEMBER:
                pearls=2;
                break;
            case BASIC:
                // Entry donor: useful convenience, still close to a member.
                prot=1; sharp=1; pearls=4; pots=4; speed=1;
                break;
            case SILVER:
                diamond=true; prot=1; sharp=1; pearls=8; pots=8; speed=2; fireRes=1;
                break;
            case GOLD:
                diamond=true; prot=2; sharp=2; pearls=12; pots=14; speed=2; fireRes=1;
                break;
            case PLATINUM:
                diamond=true; prot=3; sharp=3; fire=1; pearls=16; pots=22; speed=3; fireRes=2; steak=64;
                break;
            default:
                break;
        }

        if(!diamond) {
            add(p,armor(Material.IRON_HELMET,prot));
            add(p,armor(Material.IRON_CHESTPLATE,prot));
            add(p,armor(Material.IRON_LEGGINGS,prot));
            add(p,armor(Material.IRON_BOOTS,prot));
            add(p,pvpSword(r==Rank.MEMBER?Material.IRON_SWORD:Material.DIAMOND_SWORD,sharp,fire));
        } else {
            add(p,armor(Material.DIAMOND_HELMET,prot));
            add(p,armor(Material.DIAMOND_CHESTPLATE,prot));
            add(p,armor(Material.DIAMOND_LEGGINGS,prot));
            add(p,armor(Material.DIAMOND_BOOTS,prot));
            add(p,pvpSword(Material.DIAMOND_SWORD,sharp,fire));
        }

        if(pearls>0) add(p,new ItemStack(Material.ENDER_PEARL,pearls));
        add(p,new ItemStack(Material.COOKED_BEEF,steak));
        for(int i=0;i<pots;i++) add(p,new ItemStack(Material.POTION,1,(short)16421));
        for(int i=0;i<speed;i++) add(p,new ItemStack(Material.POTION,1,(short)8226));
        for(int i=0;i<fireRes;i++) add(p,new ItemStack(Material.POTION,1,(short)8259));

        // Top tiers are valuable to factions even beyond the armor itself.
        if(r==Rank.GOLD) {
            add(p,new ItemStack(Material.NETHER_STALK,8));
            add(p,new ItemStack(Material.GLOWSTONE_DUST,4));
        } else if(r==Rank.PLATINUM) {
            add(p,new ItemStack(Material.NETHER_STALK,16));
            add(p,new ItemStack(Material.GLOWSTONE_DUST,8));
            add(p,new ItemStack(Material.OBSIDIAN,8));
        }
    }

    private void grantStarterKit(Player p,String type) {
        if("bard".equals(type)) {
            add(p,armor(Material.GOLD_HELMET,1));
            add(p,armor(Material.GOLD_CHESTPLATE,1));
            add(p,armor(Material.GOLD_LEGGINGS,1));
            add(p,armor(Material.GOLD_BOOTS,1));
            add(p,sword(Material.IRON_SWORD,1));
            add(p,new ItemStack(Material.BLAZE_ROD,1));
            add(p,new ItemStack(Material.GHAST_TEAR,1));
            add(p,new ItemStack(Material.FEATHER,1));
            add(p,new ItemStack(Material.MAGMA_CREAM,1));
        } else if("archer".equals(type)) {
            add(p,armor(Material.LEATHER_HELMET,2));
            add(p,armor(Material.LEATHER_CHESTPLATE,2));
            add(p,armor(Material.LEATHER_LEGGINGS,2));
            add(p,armor(Material.LEATHER_BOOTS,2));
            ItemStack bow=new ItemStack(Material.BOW);
            bow.addUnsafeEnchantment(Enchantment.ARROW_DAMAGE,2);
            add(p,bow);
            add(p,new ItemStack(Material.ARROW,64));
            add(p,sword(Material.IRON_SWORD,1));
        } else if("miner".equals(type)) {
            add(p,armor(Material.IRON_HELMET,1));
            add(p,armor(Material.IRON_CHESTPLATE,1));
            add(p,armor(Material.IRON_LEGGINGS,1));
            add(p,armor(Material.IRON_BOOTS,1));
            ItemStack pick=new ItemStack(Material.IRON_PICKAXE);
            pick.addUnsafeEnchantment(Enchantment.DIG_SPEED,2);
            add(p,pick);
            add(p,sword(Material.IRON_SWORD,1));
        } else if("rogue".equals(type)) {
            add(p,armor(Material.CHAINMAIL_HELMET,1));
            add(p,armor(Material.CHAINMAIL_CHESTPLATE,1));
            add(p,armor(Material.CHAINMAIL_LEGGINGS,1));
            add(p,armor(Material.CHAINMAIL_BOOTS,1));
            add(p,sword(Material.GOLD_SWORD,2));
        } else {
            add(p,armor(Material.IRON_HELMET,1));
            add(p,armor(Material.IRON_CHESTPLATE,1));
            add(p,armor(Material.IRON_LEGGINGS,1));
            add(p,armor(Material.IRON_BOOTS,1));
            add(p,sword(Material.DIAMOND_SWORD,1));
        }

        add(p,new ItemStack(Material.ENDER_PEARL,"miner".equals(type)?2:4));
        add(p,new ItemStack(Material.COOKED_BEEF,32));
        int heals="miner".equals(type)?6:10;
        for(int i=0;i<heals;i++) add(p,new ItemStack(Material.POTION,1,(short)16421));
        add(p,new ItemStack(Material.POTION,1,(short)8226));
        if(!"miner".equals(type)) add(p,new ItemStack(Material.POTION,1,(short)8226));
        if("rogue".equals(type) || "diamond".equals(type)) add(p,new ItemStack(Material.POTION,1,(short)8259));
    }

    private ItemStack armor(Material m,int prot) {
        ItemStack i=new ItemStack(m);
        if(prot>0)i.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL,prot);
        return i;
    }

    private ItemStack sword(Material m,int sharp) {
        ItemStack i=new ItemStack(m);
        if(sharp>0)i.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,sharp);
        return i;
    }

    private ItemStack pvpSword(Material m,int sharp,int fireAspect) {
        ItemStack i=sword(m,sharp);
        if(fireAspect>0)i.addUnsafeEnchantment(Enchantment.FIRE_ASPECT,fireAspect);
        return i;
    }

    private void add(Player p, ItemStack i) {
        if (i == null || i.getAmount() <= 0) return;
        Map<Integer,ItemStack> left=p.getInventory().addItem(i);
        for(ItemStack x:left.values()) p.getWorld().dropItemNaturally(p.getLocation(),x);
    }

    private boolean cmdBalance(Player p,String[] a) {
        String n=a.length>0?a[0]:p.getName();
        p.sendMessage(color("&aBalance of &f"+n+"&a: $"+moneyFmt.format(balance(n))));
        return true;
    }

    private boolean cmdPay(Player p,String[] a) {
        if(a.length!=2) {
            p.sendMessage("/pay <player> <amount>");
            return true;
        }
        Player t=Bukkit.getPlayerExact(a[0]);
        if(t==null) {
            p.sendMessage(color("&cPlayer not online."));
            return true;
        }
        double amt;
        try {
            amt=Double.parseDouble(a[1]);
        } catch(Exception e) {
            p.sendMessage(color("&cInvalid amount."));
            return true;
        }
        if(amt<=0||balance(p.getName())<amt) {
            p.sendMessage(color("&cInsufficient funds."));
            return true;
        }
        setBalance(p.getName(),balance(p.getName())-amt);
        setBalance(t.getName(),balance(t.getName())+amt);
        p.sendMessage(color("&aPaid $"+moneyFmt.format(amt)+" to "+t.getName()));
        t.sendMessage(color("&aReceived $"+moneyFmt.format(amt)+" from "+p.getName()));
        return true;
    }

    private boolean cmdSell(Player p,String[] a) {
        if(a.length!=1||(!a[0].equalsIgnoreCase("hand")&&!a[0].equalsIgnoreCase("all"))) {
            p.sendMessage("/sell <hand|all>");
            return true;
        }
        double earned=0;
        int sold=0;
        if(a[0].equalsIgnoreCase("hand")) {
            ItemStack i=p.getItemInHand();
            if(i==null||i.getType()==Material.AIR||!sellPrices.containsKey(i.getType())) {
                p.sendMessage(color("&cThat item cannot be sold."));
                return true;
            }
            earned=sellPrices.get(i.getType())*i.getAmount();
            sold=i.getAmount();
            p.setItemInHand(null);
        } else {
            ItemStack[] inv=p.getInventory().getContents();
            for(int slot=0;slot<inv.length;slot++) {
                ItemStack i=inv[slot];
                if(i!=null&&sellPrices.containsKey(i.getType())) {
                    earned+=sellPrices.get(i.getType())*i.getAmount();
                    sold+=i.getAmount();
                    p.getInventory().setItem(slot,null);
                }
            }
        }
        if(sold==0) {
            p.sendMessage(color("&cNothing sellable found."));
            return true;
        }
        setBalance(p.getName(),balance(p.getName())+earned);
        p.sendMessage(color("&aSold "+sold+" items for $"+moneyFmt.format(earned)));
        return true;
    }

    private boolean cmdBuy(Player p,String[] a) {
        if(a.length!=2) {
            p.sendMessage("/buy <item> <amount>");
            return true;
        }
        ShopItem item=buyItems.get(a[0].toLowerCase(Locale.ENGLISH));
        if(item==null) {
            p.sendMessage(color("&cUnknown shop item."));
            return true;
        }
        int amount;
        try {
            amount=Integer.parseInt(a[1]);
        } catch(Exception e) {
            p.sendMessage(color("&cInvalid amount."));
            return true;
        }
        if(amount<1||amount>2304) {
            p.sendMessage(color("&cAmount must be 1-2304."));
            return true;
        }
        double cost=item.price*amount;
        if(balance(p.getName())<cost) {
            p.sendMessage(color("&cNeed $"+moneyFmt.format(cost)+"; you have $"+moneyFmt.format(balance(p.getName()))));
            return true;
        }
        setBalance(p.getName(),balance(p.getName())-cost);
        int left=amount;
        int max=item.material.getMaxStackSize();
        while(left>0) {
            int n=Math.min(left,max);
            add(p,new ItemStack(item.material,n,item.data));
            left-=n;
        }
        p.sendMessage(color("&aBought "+amount+" "+item.key+" for $"+moneyFmt.format(cost)));
        return true;
    }

    private boolean cmdShop(Player p) {
        p.sendMessage(color("&6--- Classic Server Shop ---"));
        p.sendMessage(color("&eSell crops: &fcane $3, cactus $2.25, pumpkin $5.50, melon $0.75, wheat/carrot/potato $1.25"));
        p.sendMessage(color("&eSell ores: &firon $8, gold $14, diamond $60"));
        p.sendMessage(color("&ePvP: &fhealthpot $135, speedpot $95, fireres $110, pearl $160, obsidian $30, steak $6"));
        p.sendMessage(color("&eFarm: &fcane $9, cactus $7, pumpkinseed $8, melonseed $5, sand $2, dirt $1, waterbucket $35"));
        p.sendMessage(color("&eSupplies: &firon $18, brewingstand $140, hopper $65, book $12, lapis $5\n&eBrewing: &fnetherwart $12, glowstone $12, gunpowder $18, glisteringmelon $24, sugar $6, magmacream $22"));
        p.sendMessage(color("&7Use /sell hand, /sell all, or /buy <item> <amount>."));
        return true;
    }

    private boolean cmdStats(Player viewer,String[] a) {
        String name=a.length>0?a[0]:viewer.getName();
        int kills,deaths;
        if(simWorld!=null && simWorld.contains(name)) {
            kills=simWorld.killsFor(name);
            deaths=simWorld.deathsFor(name);
        } else {
            String base="players."+name.toLowerCase(Locale.ENGLISH);
            kills=statsData.getInt(base+".kills",0);
            deaths=statsData.getInt(base+".deaths",0);
        }
        double kdr=deaths==0?kills:((double)kills/(double)deaths);
        Rank rank=effectiveRank(name);
        viewer.sendMessage(color("&6--- "+rankNameColor(rank)+name+"&6 Stats ---"));
        viewer.sendMessage(color("&aKills: &f"+kills+" &8| &cDeaths: &f"+deaths+" &8| &eKDR: &f"+new DecimalFormat("0.00").format(kdr)));
        String fac=factionNameFor(name);
        if(fac!=null && !fac.isEmpty()) viewer.sendMessage(color("&7Faction: &f"+fac));
        return true;
    }

    private void recordHumanDeathStats(Player victim,Player killer) {
        if(victim!=null && !isBotIdentity(victim.getName())) {
            String b="players."+victim.getName().toLowerCase(Locale.ENGLISH);
            statsData.set(b+".deaths",statsData.getInt(b+".deaths",0)+1);
        }
        if(killer!=null && !isBotIdentity(killer.getName()) &&
           (victim==null || !killer.getName().equalsIgnoreCase(victim.getName()))) {
            String b="players."+killer.getName().toLowerCase(Locale.ENGLISH);
            int kills=statsData.getInt(b+".kills",0)+1;
            statsData.set(b+".kills",kills);
            int deaths=statsData.getInt(b+".deaths",0);
            killer.sendMessage(color("&8[&aKill #"+kills+"&8] &7K/D &f"+kills+"&7/&f"+deaths+
                " &8| &7Use &f/stats"));
        }
        saveYaml(statsData,statsFile);
    }

    private boolean cmdFaction(Player p,String[] a) {
        if(a.length==0) {
            sendFactionHelp(p);
            return true;
        }
        String sub=a[0].toLowerCase(Locale.ENGLISH);
        if(sub.equals("create")) {
            if(a.length!=2) {
                p.sendMessage("/f create <name>");
                return true;
            }
            if(factionOf(p.getName())!=null) {
                p.sendMessage(color("&cYou are already in a faction."));
                return true;
            }
            String key=a[1].toLowerCase(Locale.ENGLISH);
            if(factions.containsKey(key)) {
                p.sendMessage(color("&cFaction exists."));
                return true;
            }
            Faction f=new Faction();
            f.name=a[1];
            f.leader=p.getName();
            f.members.add(p.getName());
            f.dtr = maxDtr(f);
            factions.put(key,f);
            saveFactions();
            p.sendMessage(color("&aCreated faction &f"+f.name));
            return true;
        }

        Faction f=factionOf(p.getName());

        if(sub.equals("join")) {
            if(a.length!=2) {
                p.sendMessage("/f join <name>");
                return true;
            }
            if(f!=null) {
                p.sendMessage(color("&cLeave your current faction first."));
                return true;
            }
            Faction target=factions.get(a[1].toLowerCase(Locale.ENGLISH));
            if(target==null||!target.invites.contains(p.getName().toLowerCase(Locale.ENGLISH))) {
                p.sendMessage(color("&cNo invite from that faction."));
                return true;
            }
            if(target.members.size() >= SimWorldDirector.MAX_FACTION_MEMBERS) {
                p.sendMessage(color("&cThat faction is full. Maximum 5 members."));
                return true;
            }
            target.invites.remove(p.getName().toLowerCase(Locale.ENGLISH));
            target.members.add(p.getName());
            target.dtr = Math.min(maxDtr(target), Math.max(target.dtr, 0.1));
            saveFactions();
            p.sendMessage(color("&aJoined &f"+target.name));
            return true;
        }

        if(sub.equals("list")) {
            p.sendMessage(color("&6--- Factions ---"));
            for(Faction x:factions.values()) {
                p.sendMessage(color("&e"+x.name+" &7members="+x.members.size()+" claims="+x.claims.size()+" dtr="+dtrColor(x)+fmtDtr(x.dtr)+"&7/"+fmtDtr(maxDtr(x))));
            }
            return true;
        }

        if(sub.equals("show") || sub.equals("who")) {
            Faction q=f;
            if(a.length>1) {
                q=factions.get(a[1].toLowerCase(Locale.ENGLISH));
                if(q==null) q=factionOf(a[1]);
            }
            if(q==null) {
                p.sendMessage(color("&cFaction/player not found. &7Use /f show <faction|player>."));
                return true;
            }
            sendFactionShow(p,q);
            return true;
        }

        if(f==null) {
            p.sendMessage(color("&cYou are not in a faction."));
            return true;
        }

        boolean leader=f.leader.equalsIgnoreCase(p.getName());

        if(sub.equals("invite")) {
            if(!leader) {
                p.sendMessage(color("&cLeader only."));
                return true;
            }
            if(a.length!=2) {
                p.sendMessage("/f invite <player>");
                return true;
            }
            if(f.members.size() >= SimWorldDirector.MAX_FACTION_MEMBERS) {
                p.sendMessage(color("&cYour faction is full. Maximum 5 members."));
                return true;
            }
            f.invites.add(a[1].toLowerCase(Locale.ENGLISH));
            saveFactions();
            p.sendMessage(color("&aInvited "+a[1]));
            return true;
        }

        if(sub.equals("leave")) {
            if(leader&&f.members.size()>1) {
                p.sendMessage(color("&cLeader must disband or transfer later; cannot leave now."));
                return true;
            }
            f.members.remove(p.getName());
            if(f.members.isEmpty()) removeFaction(f);
            saveFactions();
            p.sendMessage(color("&eYou left "+f.name));
            return true;
        }

        if(sub.equals("kick")) {
            if(!leader) {
                p.sendMessage(color("&cLeader only."));
                return true;
            }
            if(a.length!=2) {
                p.sendMessage("/f kick <player>");
                return true;
            }
            if(a[1].equalsIgnoreCase(f.leader)) {
                p.sendMessage(color("&cCannot kick leader."));
                return true;
            }
            f.members.remove(a[1]);
            saveFactions();
            p.sendMessage(color("&aKicked "+a[1]));
            return true;
        }

        if(sub.equals("disband")) {
            if(!leader) {
                p.sendMessage(color("&cLeader only."));
                return true;
            }
            removeFaction(f);
            saveFactions();
            Bukkit.broadcastMessage(color("&cFaction "+f.name+" disbanded."));
            return true;
        }

        if(sub.equals("claim")) {
            String ck=claimKey(p.getLocation());
            String existing=claimOwners.get(ck);
            if(existing!=null&&!existing.equalsIgnoreCase(f.name)) {
                p.sendMessage(color("&cAlready claimed by "+existing));
                return true;
            }
            if(!leader) {
                p.sendMessage(color("&cLeader only for now."));
                return true;
            }
            int maxClaims=Math.min(getConfig().getInt("claims.max-cap",12), getConfig().getInt("claims.base",4)+f.members.size()*getConfig().getInt("claims.per-member",2));
            if(f.claims.size()+1>maxClaims) {
                p.sendMessage(color("&cYour faction claim limit is "+maxClaims+" chunks."));
                return true;
            }
            f.claims.add(ck);
            claimOwners.put(ck,f.name);
            saveFactions();
            p.sendMessage(color("&aClaimed this chunk."));
            return true;
        }

        if(sub.equals("unclaim")) {
            if(!leader) {
                p.sendMessage(color("&cLeader only."));
                return true;
            }
            String ck=claimKey(p.getLocation());
            if(!f.claims.remove(ck)) {
                p.sendMessage(color("&cYour faction does not own this chunk."));
                return true;
            }
            claimOwners.remove(ck);
            saveFactions();
            p.sendMessage(color("&eUnclaimed chunk."));
            return true;
        }

        if(sub.equals("sethome")) {
            if(!leader) {
                p.sendMessage(color("&cLeader only."));
                return true;
            }
            f.home=p.getLocation();
            saveFactions();
            p.sendMessage(color("&aFaction home set."));
            return true;
        }

        if(sub.equals("home")) {
            if(hcfZones!=null && hcfZones.isTagged(p) && !isOwnerPlayer(p)) {
                p.sendMessage(color("&cYou cannot /f home while combat tagged. &7"+hcfZones.tagSeconds(p)+"s remaining."));
                return true;
            }
            if(f.home==null) {
                p.sendMessage(color("&cNo faction home."));
                return true;
            }
            p.teleport(f.home);
            return true;
        }

        if(sub.equals("c")) {
            String k=p.getName().toLowerCase(Locale.ENGLISH);
            if(factionChat.remove(k)) p.sendMessage(color("&7Faction chat disabled."));
            else {
                factionChat.add(k);
                p.sendMessage(color("&7Faction chat enabled."));
            }
            return true;
        }

        sendFactionHelp(p);
        return true;
    }

    private void sendFactionShow(Player viewer,Faction q) {
        viewer.sendMessage(color("&8&m--------------------------------"));
        viewer.sendMessage(color("&6&l"+q.name+" &7(" + q.members.size()+"/"+SimWorldDirector.MAX_FACTION_MEMBERS + ")"));
        viewer.sendMessage(color("&7Leader: &f"+q.leader+
            " &7DTR: "+dtrColor(q)+fmtDtr(q.dtr)+"&7/&f"+fmtDtr(maxDtr(q))+
            " &7Claims: &f"+q.claims.size()));

        for(String member:q.members) {
            boolean physical=Bukkit.getPlayerExact(member)!=null;
            boolean logical=physical || (simWorld!=null && simWorld.logicalOnlineFor(member));
            Rank rank=effectiveRank(member);
            String marker=logical?"&a●":"&7●";
            String leader=q.leader.equalsIgnoreCase(member)?" &6★ Leader":"";
            int kills=simWorld!=null && simWorld.contains(member)?simWorld.killsFor(member):statsData.getInt("players."+member.toLowerCase(Locale.ENGLISH)+".kills",0);
            viewer.sendMessage(color(" "+marker+" "+identityPrefix(member,rank)+rankNameColor(rank)+member+
                "&7  Kills: &f"+kills+leader));
        }
        viewer.sendMessage(color("&8&m--------------------------------"));
    }

    private void sendFactionHelp(Player p) {
        p.sendMessage(color("&6/f create, invite, join, leave, kick, disband, claim, unclaim, sethome, home, show, who, list, c"));
    }

    private void removeFaction(Faction f) {
        factions.remove(f.name.toLowerCase(Locale.ENGLISH));
        for(String c:new ArrayList<String>(f.claims)) claimOwners.remove(c);
    }

    private Faction factionOf(String player) {
        for(Faction f:factions.values()) {
            for(String m:f.members) {
                if(m.equalsIgnoreCase(player)) return f;
            }
        }
        return null;
    }

    synchronized boolean createSimFactionAuthority(String factionName, String leaderName) {
        String fk = factionName.toLowerCase(Locale.ENGLISH);
        Faction existing = factions.get(fk);
        if (existing != null) {
            if (!existing.members.contains(leaderName)) existing.members.add(leaderName);
            if (existing.leader == null || existing.leader.isEmpty()) existing.leader = leaderName;
            existing.dtr = Math.min(existing.dtr <= 0 ? maxDtr(existing) : existing.dtr, maxDtr(existing));
            saveFactions();
            return true;
        }
        if (factionOf(leaderName) != null) return false;
        Faction f = new Faction();
        f.name = factionName;
        f.leader = leaderName;
        f.members.add(leaderName);
        f.dtr = maxDtr(f);
        factions.put(fk, f);
        saveFactions();
        return true;
    }

    synchronized boolean joinSimFactionAuthority(String factionName, String memberName) {
        Faction f = factions.get(factionName.toLowerCase(Locale.ENGLISH));
        if (f == null || f.members.size() >= SimWorldDirector.MAX_FACTION_MEMBERS) return false;
        Faction old = factionOf(memberName);
        if (old != null && !old.name.equalsIgnoreCase(f.name)) return false;
        f.members.add(memberName);
        f.dtr = Math.min(maxDtr(f), Math.max(0.1, f.dtr));
        saveFactions();
        return true;
    }

    synchronized boolean inviteHumanToSimFaction(String factionName,String inviterName,String humanName) {
        Faction f=factions.get(factionName.toLowerCase(Locale.ENGLISH));
        if(f==null || humanName==null || humanName.trim().isEmpty()) return false;
        if(f.leader==null || !f.leader.equalsIgnoreCase(inviterName)) return false;
        if(f.members.size()>=SimWorldDirector.MAX_FACTION_MEMBERS) return false;

        Faction existing=factionOf(humanName);
        if(existing!=null) return existing.name.equalsIgnoreCase(f.name);

        f.invites.add(humanName.toLowerCase(Locale.ENGLISH));
        saveFactions();

        Player target=Bukkit.getPlayerExact(humanName);
        if(target!=null) {
            target.sendMessage(color("&a"+inviterName+" invited you to &f"+f.name+"&a. &7Use &f/f join "+f.name));
        }
        return true;
    }

    synchronized boolean removeSimFactionMemberAuthority(String factionName,String memberName) {
        Faction f=factions.get(factionName.toLowerCase(Locale.ENGLISH));
        if(f==null || memberName==null) return false;
        if(f.leader!=null && f.leader.equalsIgnoreCase(memberName)) return false;

        boolean removed=false;
        Iterator<String> it=f.members.iterator();
        while(it.hasNext()) {
            if(it.next().equalsIgnoreCase(memberName)) {
                it.remove();
                removed=true;
                break;
            }
        }
        if(!removed) return false;

        f.dtr=Math.min(f.dtr,maxDtr(f));
        saveFactions();
        return true;
    }

    synchronized boolean humanAlreadyFactioned(String humanName) {
        return humanName!=null && factionOf(humanName)!=null;
    }

    synchronized int publicRankLevel(String name) {
        if(name==null) return 0;
        Rank r=effectiveRank(name);
        return r==null?0:r.level;
    }

    boolean upgradeRankFromReward(Player p,String source) {
        if(p==null) return false;
        if(simWorld!=null && simWorld.contains(p.getName()))
            return simWorld.upgradeDonorRankFromReward(p.getName(),source);

        Rank current=getRank(p.getName());
        Rank next=null;
        if(current==Rank.MEMBER) next=Rank.BASIC;
        else if(current==Rank.BASIC) next=Rank.SILVER;
        else if(current==Rank.SILVER) next=Rank.GOLD;
        else if(current==Rank.GOLD) next=Rank.PLATINUM;
        if(next==null) return false;

        setRank(p.getName(),next);
        Bukkit.broadcastMessage(color("&d[Crates] &f"+p.getName()+" &7won a "+next.prefix+" &7rank upgrade from "+source+"&7."));
        p.sendMessage(color("&aYour donor rank is now "+next.prefix+"&a."));
        return true;
    }


    synchronized void resetSimFactionAuthority(List<String> simKeys) {
        Set<String> keys = new HashSet<String>();
        for (String s : simKeys) keys.add(s.toLowerCase(Locale.ENGLISH));

        List<Faction> remove = new ArrayList<Faction>();
        for (Faction f : factions.values()) {
            boolean allSim = true;
            for (String member : f.members) {
                if (!keys.contains(member.toLowerCase(Locale.ENGLISH))) {
                    allSim = false;
                    break;
                }
            }
            if (allSim) remove.add(f);
            else {
                Iterator<String> it = f.members.iterator();
                while (it.hasNext()) {
                    String member = it.next();
                    if (keys.contains(member.toLowerCase(Locale.ENGLISH))) it.remove();
                }
                if (f.members.isEmpty()) remove.add(f);
                else {
                    if (!f.members.contains(f.leader)) f.leader = f.members.iterator().next();
                    f.dtr = Math.min(f.dtr, maxDtr(f));
                }
            }
        }
        for (Faction f : remove) removeFaction(f);
        saveFactions();
    }

    synchronized void applySimulatedFactionDeath(String factionName, String memberName) {
        Faction f = factions.get(factionName.toLowerCase(Locale.ENGLISH));
        if (f == null) return;
        f.dtr -= getConfig().getDouble("dtr.loss-per-death", 1.0);
        f.dtrFrozenUntil = System.currentTimeMillis() + getConfig().getLong("dtr.freeze-seconds-after-death", 120L) * 1000L;
        boolean nowRaidable = isRaidable(f);
        if (nowRaidable && !f.wasRaidable) Bukkit.broadcastMessage(color("&c" + f.name + " is now raidable."));
        f.wasRaidable = nowRaidable;
        saveFactions();
        if (simWorld != null) simWorld.onAuthorityDeath(memberName, f.name, f.dtr, nowRaidable);
    }

    synchronized boolean setSimFactionHomeAndClaims(String factionName, Location home, Collection<String> claims) {
        Faction f = factions.get(factionName.toLowerCase(Locale.ENGLISH));
        if (f == null) return false;

        if (claims != null) {
            // Validate the entire new footprint before mutating any existing
            // claims. Claim replacement is all-or-nothing.
            for (String ck : claims) {
                String owner = claimOwners.get(ck);
                if (owner != null && !owner.equalsIgnoreCase(f.name)) return false;
            }

            Set<String> oldClaims = new LinkedHashSet<String>(f.claims);
            for (String old : oldClaims) claimOwners.remove(old);

            f.claims.clear();
            for (String ck : claims) {
                f.claims.add(ck);
                claimOwners.put(ck, f.name);
            }
        }

        f.home = home == null ? null : home.clone();
        saveFactions();
        return true;
    }

    synchronized double factionDtr(String factionName) {
        Faction f = factions.get(factionName.toLowerCase(Locale.ENGLISH));
        return f == null ? 0.0 : f.dtr;
    }

    synchronized double factionMaxDtr(String factionName) {
        Faction f = factions.get(factionName.toLowerCase(Locale.ENGLISH));
        return f == null ? 0.0 : maxDtr(f);
    }

    synchronized boolean factionRaidable(String factionName) {
        Faction f = factions.get(factionName.toLowerCase(Locale.ENGLISH));
        return f != null && isRaidable(f);
    }

    private double maxDtr(Faction f) {
        double per = getConfig().getDouble("dtr.max-per-member", 1.1);
        double cap = getConfig().getDouble("dtr.max-cap", 5.5);
        return Math.min(cap, Math.max(per, f.members.size() * per));
    }

    private boolean isRaidable(Faction f) {
        return f.dtr <= getConfig().getDouble("dtr.raidable-at", 0.0);
    }

    private String dtrColor(Faction f) {
        if (isRaidable(f)) return "&c";
        if (f.dtr <= 1.1) return "&e";
        return "&a";
    }

    private String fmtDtr(double d) {
        return new DecimalFormat("0.0").format(d);
    }

    private void startDtrRegen() {
        long periodSeconds = Math.max(15L, getConfig().getLong("dtr.regen-interval-seconds", 60L));
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            public void run() {
                long now = System.currentTimeMillis();
                double amount = getConfig().getDouble("dtr.regen-per-interval", 0.25);
                boolean changed = false;

                for (Faction f : factions.values()) {
                    double max = maxDtr(f);
                    if (f.dtr > max) {
                        f.dtr = max;
                        changed = true;
                    }
                    if (now < f.dtrFrozenUntil || f.dtr >= max) continue;

                    boolean was = isRaidable(f);
                    f.dtr = Math.min(max, f.dtr + amount);
                    boolean nowRaidable = isRaidable(f);
                    if (was && !nowRaidable) {
                        Bukkit.broadcastMessage(color("&a" + f.name + " is no longer raidable."));
                    }
                    f.wasRaidable = nowRaidable;
                    changed = true;
                }

                if (changed) saveFactions();
            }
        }, periodSeconds * 20L, periodSeconds * 20L);
    }

    private String claimKey(Location l) {
        return l.getWorld().getName()+":"+l.getChunk().getX()+":"+l.getChunk().getZ();
    }

    private double getPower(String lowerName) {
        Double d=power.get(lowerName.toLowerCase(Locale.ENGLISH));
        return d==null?10.0:d;
    }

    private double totalPower(Faction f) {
        double x=0;
        for(String m:f.members) x+=getPower(m.toLowerCase(Locale.ENGLISH));
        return x;
    }

    private String fmtPower(double d) {
        return new DecimalFormat("0.0").format(d);
    }

    private boolean canBuild(Player p,Location l) {
        if(getRank(p.getName())==Rank.OWNER) return true;
        if(isSafezone(l)) {
            p.sendMessage(color("&cSpawn safezone is protected."));
            return false;
        }
        String owner=claimOwners.get(claimKey(l));
        if(owner==null) return true;
        Faction own=factionOf(p.getName());
        if(own!=null&&own.name.equalsIgnoreCase(owner)) return true;
        Faction target=factions.get(owner.toLowerCase(Locale.ENGLISH));
        if(target!=null&&isRaidable(target)) return true;
        p.sendMessage(color("&cThis land belongs to "+owner+"."));
        return false;
    }

    private boolean isSafezone(Location l) {
        if(hcfZones!=null) return hcfZones.isSafe(l);
        Location s=l.getWorld().getSpawnLocation();
        double dx=l.getX()-s.getX(),dz=l.getZ()-s.getZ();
        double r=getConfig().getDouble("map.safezone-radius",60);
        return dx*dx+dz*dz<=r*r;
    }

    private boolean cmdSimMap(Player p,String[] a) {
        if(getRank(p.getName())!=Rank.OWNER) {
            p.sendMessage(color("&cOwner only."));
            return true;
        }
        boolean rebuild=a.length>0&&a[0].equalsIgnoreCase("rebuild");
        bootstrapMap(p.getWorld(),rebuild);
        return true;
    }

    private void bootstrapMap(final World world, boolean rebuild) {
        if(getConfig().getBoolean("map.complete",false)&&!rebuild) return;
        final int y=getConfig().getInt("map.surface-y",63);
        final ArrayDeque<BlockOp> q=new ArrayDeque<BlockOp>();

        for(int x=-50;x<=50;x++) for(int z=-50;z<=50;z++) q.add(new BlockOp(x,y,z,Material.SMOOTH_BRICK));

        for(int d=51;d<=1200;d++) for(int w=-4;w<=4;w++) {
            q.add(new BlockOp(w,y,d,Material.SMOOTH_BRICK));
            q.add(new BlockOp(w,y,-d,Material.SMOOTH_BRICK));
            q.add(new BlockOp(d,y,w,Material.SMOOTH_BRICK));
            q.add(new BlockOp(-d,y,w,Material.SMOOTH_BRICK));
        }

        for(int x=-10;x<=10;x++) for(int z=-10;z<=10;z++) q.add(new BlockOp(x,y,z,Material.QUARTZ_BLOCK));
        q.add(new BlockOp(0,y,0,Material.EMERALD_BLOCK));

        for(int x=180;x<=220;x++) for(int z=-20;z<=20;z++) q.add(new BlockOp(x,y,z,Material.STONE));
        for(int x=180;x<=220;x++) {
            q.add(new BlockOp(x,y,20,Material.OBSIDIAN));
            q.add(new BlockOp(x,y,-20,Material.OBSIDIAN));
        }
        for(int z=-20;z<=20;z++) {
            q.add(new BlockOp(180,y,z,Material.OBSIDIAN));
            q.add(new BlockOp(220,y,z,Material.OBSIDIAN));
        }
        q.add(new BlockOp(200,y,0,Material.GOLD_BLOCK));

        for(int x=-340;x<=-260;x++) for(int z=-35;z<=35;z++) {
            boolean water=((z+35)%4)==0;
            q.add(new BlockOp(x,y,z,water?Material.STATIONARY_WATER:Material.SAND));
            if(!water&&((z+34)%4)==0) q.add(new BlockOp(x,y+1,z,Material.SUGAR_CANE_BLOCK));
        }

        int[][] sites={{800,800},{-800,800},{800,-800},{-800,-800},{1100,300},{-1100,300},{300,1100},{300,-1100}};
        for(int[] s:sites) {
            int cx=s[0],cz=s[1];
            for(int x=cx-16;x<=cx+16;x++) for(int z=cz-16;z<=cz+16;z++) q.add(new BlockOp(x,y,z,Material.STONE));
            for(int x=cx-16;x<=cx+16;x++) {
                q.add(new BlockOp(x,y+1,cz-16,Material.OBSIDIAN));
                q.add(new BlockOp(x,y+1,cz+16,Material.OBSIDIAN));
            }
            for(int z=cz-16;z<=cz+16;z++) {
                q.add(new BlockOp(cx-16,y+1,z,Material.OBSIDIAN));
                q.add(new BlockOp(cx+16,y+1,z,Material.OBSIDIAN));
            }
        }

        for(int x=690;x<=705;x++) for(int z=-5;z<=5;z++) for(int yy=20;yy<=y;yy++) {
            q.add(new BlockOp(x,yy,z,yy==20?Material.BEDROCK:Material.AIR));
        }

        world.setSpawnLocation(0,y+1,0);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"worldborder set "+getConfig().getInt("map.world-border",3000));
        final int total=q.size();
        getLogger().info("Map bootstrap queued: "+total+" block ops.");

        new BukkitRunnable() {
            int done=0;
            public void run() {
                int batch=0;
                while(batch<2500&&!q.isEmpty()) {
                    BlockOp op=q.poll();
                    Block b=world.getBlockAt(op.x,op.y,op.z);
                    b.setType(op.material);
                    if(op.data!=0) b.setData(op.data);
                    batch++;
                    done++;
                }
                if(q.isEmpty()) {
                    getConfig().set("map.complete",true);
                    saveConfig();
                    Bukkit.broadcastMessage(color("&aEra map bootstrap complete. Spawn, roads, PotPvP lab, KoTH, cane farm, faction sites and chokepoints are ready."));
                    getLogger().info("Map bootstrap complete: "+done+"/"+total);
                    cancel();
                }
            }
        }.runTaskTimer(this,1L,1L);
    }

    private boolean cmdSimState(Player p,String[] a) {
        if(!(p.getName().startsWith("StateBot")||getRank(p.getName())==Rank.OWNER)) {
            p.sendMessage(color("&cReserved for state-handoff test."));
            return true;
        }
        if(a.length==1&&a[0].equalsIgnoreCase("prepare")) {
            PlayerInventory inv=p.getInventory();
            inv.clear();
            inv.setArmorContents(new ItemStack[4]);
            inv.setItem(0,sword(Material.DIAMOND_SWORD,2));
            for(int i=1;i<=13;i++) inv.setItem(i,new ItemStack(Material.POTION,1,(short)16421));
            inv.setItem(14,new ItemStack(Material.ENDER_PEARL,7));
            p.setHealth(12.0);
            p.setFoodLevel(15);
            p.teleport(new Location(p.getWorld(),200.5,getConfig().getInt("map.surface-y",63)+1,0.5));
            p.sendMessage("SIMSTATE_READY "+stateSignature(p));
            return true;
        }
        p.sendMessage("SIMSTATE "+stateSignature(p));
        return true;
    }

    private String stateSignature(Player p) {
        StringBuilder inv=new StringBuilder();
        for(ItemStack i:p.getInventory().getContents()) {
            if(i!=null&&i.getType()!=Material.AIR) inv.append(i.getTypeId()).append(':').append(i.getDurability()).append('x').append(i.getAmount()).append(',');
        }
        Location l=p.getLocation();
        return String.format(Locale.US,"x=%.2f y=%.2f z=%.2f health=%.1f food=%d inv=%s",l.getX(),l.getY(),l.getZ(),p.getHealth(),p.getFoodLevel(),inv.toString());
    }

    private boolean cmdDuelPrep(Player owner) {
        if(getRank(owner.getName())!=Rank.OWNER) {
            owner.sendMessage(color("&cOwner only."));
            return true;
        }
        Player bot=null;
        for(Player x:Bukkit.getOnlinePlayers()) {
            if(x.getName().startsWith("DuelBot")) {
                bot=x;
                break;
            }
        }
        if(bot==null) {
            owner.sendMessage(color("&cStart the duel bot first."));
            return true;
        }
        preparePotKit(owner);
        preparePotKit(bot);
        int y=getConfig().getInt("map.surface-y",63)+1;
        owner.teleport(new Location(owner.getWorld(),187.5,y,0.5,-90f,0f));
        bot.teleport(new Location(owner.getWorld(),212.5,y,0.5,90f,0f));
        Bukkit.broadcastMessage(color("&cPotPvP lab ready: &f"+owner.getName()+" &7vs &f"+bot.getName()));
        return true;
    }

    private void preparePotKit(Player p) {
        PlayerInventory inv=p.getInventory();
        inv.clear();
        inv.setHelmet(armor(Material.DIAMOND_HELMET,2));
        inv.setChestplate(armor(Material.DIAMOND_CHESTPLATE,2));
        inv.setLeggings(armor(Material.DIAMOND_LEGGINGS,2));
        inv.setBoots(armor(Material.DIAMOND_BOOTS,2));
        inv.setItem(0,pvpSword(Material.DIAMOND_SWORD,2,2));

        // Hotbar: sword, five Healing II splashes, Fire Resistance, Speed II, pearls.
        for(int slot=1;slot<=5;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
        inv.setItem(6,new ItemStack(Material.POTION,1,(short)8259));
        inv.setItem(7,new ItemStack(Material.POTION,1,(short)8226));
        inv.setItem(8,new ItemStack(Material.ENDER_PEARL,16));

        // Reserve inventory: healing pots plus replacement drinkable buffs.
        for(int slot=9;slot<33;slot++) inv.setItem(slot,new ItemStack(Material.POTION,1,(short)16421));
        inv.setItem(33,new ItemStack(Material.POTION,1,(short)8259));
        inv.setItem(34,new ItemStack(Material.POTION,1,(short)8226));
        inv.setItem(35,new ItemStack(Material.POTION,1,(short)8226));

        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
    }

    private void startPowerRegen() {
        new BukkitRunnable() {
            public void run() {
                for(Player p:Bukkit.getOnlinePlayers()) {
                    String n=p.getName().toLowerCase(Locale.ENGLISH);
                    power.put(n,Math.min(10.0,getPower(n)+1.0));
                }
                saveFactions();
            }
        }.runTaskTimer(this,20L*300L,20L*300L);
    }

    private void hookTickTimes() {
        try {
            Object craft=Bukkit.getServer();
            Method m=craft.getClass().getMethod("getServer");
            Object nms=m.invoke(craft);

            Class<?> type=nms.getClass();
            while(type!=null&&tickTimes==null) {
                for(Field f:type.getDeclaredFields()) {
                    if(f.getType().equals(long[].class)) {
                        f.setAccessible(true);
                        long[] arr=(long[])f.get(nms);
                        if(arr!=null&&arr.length==100) {
                            tickTimes=arr;
                            getLogger().info("MSPT probe hooked to "+type.getSimpleName()+"."+f.getName());
                            break;
                        }
                    }
                }
                type=type.getSuperclass();
            }

            if(tickTimes==null) {
                getLogger().warning("Could not find native 100-tick timing array; /simprobe will report n/a.");
            }
        } catch(Throwable t) {
            getLogger().warning("Could not hook native tick-time array; /simprobe will report n/a: "+t.getMessage());
        }
    }

    private double[] tickStats() {
        if(tickTimes==null) return null;
        double[] ms=new double[tickTimes.length];
        int count=0;
        for(long n:tickTimes) if(n>0) ms[count++]=n/1000000.0;
        if(count==0) return null;
        double[] v=Arrays.copyOf(ms,count);
        Arrays.sort(v);
        double sum=0;
        for(double x:v) sum+=x;
        double avg=sum/v.length;
        double p95=v[Math.min(v.length-1,(int)Math.floor(v.length*0.95))];
        double max=v[v.length-1];
        return new double[]{avg,p95,max,v.length};
    }

    int adaptiveHotBodyBudget(int requested) {
        requested=Math.max(4,Math.min(12,requested));
        double[] s = tickStats();
        if (s == null) return Math.min(requested,10);
        double p95 = s[1];

        // Combat gets its own reserved pool now; unrelated workers are shed
        // before fighters are promoted. These thresholds therefore govern the
        // size of the fight itself rather than fight + ambient load combined.
        if (p95 >= 46.0) return Math.min(requested,4);
        if (p95 >= 40.0) return Math.min(requested,6);
        if (p95 >= 34.0) return Math.min(requested,7);
        if (p95 >= 28.0) return Math.min(requested,8);
        if (p95 >= 22.0) return Math.min(requested,9);
        if (p95 >= 17.0) return Math.min(requested,10);
        if (p95 >= 12.0) return Math.min(requested,11);
        return requested;
    }

    private String probeString() {
        double[] s=tickStats();
        Runtime r=Runtime.getRuntime();
        long used=(r.totalMemory()-r.freeMemory())/1048576L;
        int physical=Bukkit.getOnlinePlayers().size();
        int logical=simWorld==null?0:simWorld.logicalOnlineCount();
        int population=simWorld==null?0:simWorld.allIdentityNames().size();
        if(s==null) return "SIMPROBE mspt=n/a physical="+physical+" logical="+logical+" population="+population+" jvmUsedMB="+used;
        return String.format(Locale.US,
            "SIMPROBE avgMSPT=%.2f p95MSPT=%.2f maxMSPT=%.2f samples=%d physical=%d logical=%d population=%d jvmUsedMB=%d",
            s[0],s[1],s[2],(int)s[3],physical,logical,population,used);
    }

    private void startMetrics() {
        if(!getConfig().getBoolean("metrics.enabled",true)) return;
        try {
            if(!metricsFile.exists()) {
                FileWriter fw=new FileWriter(metricsFile,true);
                fw.write("epoch_ms,avg_mspt,p95_mspt,max_mspt,samples,players,jvm_used_mb\n");
                fw.close();
            }
        } catch(IOException e) {
            getLogger().warning(e.getMessage());
        }

        long ticks=Math.max(20L,20L*getConfig().getInt("metrics.interval-seconds",5));
        metricsTask=Bukkit.getScheduler().scheduleSyncRepeatingTask(this,new Runnable() {
            public void run() {
                double[] s=tickStats();
                Runtime r=Runtime.getRuntime();
                long used=(r.totalMemory()-r.freeMemory())/1048576L;
                try {
                    FileWriter fw=new FileWriter(metricsFile,true);
                    if(s==null) {
                        fw.write(System.currentTimeMillis()+",,,,,"+Bukkit.getOnlinePlayers().size()+","+used+"\n");
                    } else {
                        fw.write(String.format(Locale.US,"%d,%.4f,%.4f,%.4f,%d,%d,%d\n",System.currentTimeMillis(),s[0],s[1],s[2],(int)s[3],Bukkit.getOnlinePlayers().size(),used));
                    }
                    fw.close();
                } catch(IOException e) {
                    getLogger().warning("metrics write failed: "+e.getMessage());
                }
            }
        },ticks,ticks);
    }

    private void loadFactions() {
        factions.clear();
        claimOwners.clear();
        power.clear();
        ConfigurationSection root=factionsData.getConfigurationSection("factions");
        if(root!=null) {
            for(String k:root.getKeys(false)) {
                ConfigurationSection s=root.getConfigurationSection(k);
                Faction f=new Faction();
                f.name=s.getString("name",k);
                f.leader=s.getString("leader","");
                f.members.addAll(s.getStringList("members"));
                f.dtr=s.getDouble("dtr", Math.min(getConfig().getDouble("dtr.max-cap",5.5), Math.max(getConfig().getDouble("dtr.max-per-member",1.1), f.members.size()*getConfig().getDouble("dtr.max-per-member",1.1))));
                f.dtrFrozenUntil=s.getLong("dtr-frozen-until",0L);
                f.wasRaidable=isRaidable(f);
                f.invites.addAll(s.getStringList("invites"));
                f.claims.addAll(s.getStringList("claims"));
                if(s.isConfigurationSection("home")) {
                    World w=Bukkit.getWorld(s.getString("home.world","world"));
                    if(w!=null) {
                        f.home=new Location(w,s.getDouble("home.x"),s.getDouble("home.y"),s.getDouble("home.z"),(float)s.getDouble("home.yaw"),(float)s.getDouble("home.pitch"));
                    }
                }
                factions.put(k.toLowerCase(Locale.ENGLISH),f);
                for(String c:f.claims) claimOwners.put(c,f.name);
            }
        }
        ConfigurationSection pr=factionsData.getConfigurationSection("power");
        if(pr!=null) {
            for(String k:pr.getKeys(false)) power.put(k.toLowerCase(Locale.ENGLISH),pr.getDouble(k,10.0));
        }
    }

    private void saveFactions() {
        factionsData.set("factions",null);
        for(Faction f:factions.values()) {
            String base="factions."+f.name.toLowerCase(Locale.ENGLISH);
            factionsData.set(base+".name",f.name);
            factionsData.set(base+".leader",f.leader);
            factionsData.set(base+".members",new ArrayList<String>(f.members));
            factionsData.set(base+".dtr",f.dtr);
            factionsData.set(base+".dtr-frozen-until",f.dtrFrozenUntil);
            factionsData.set(base+".invites",new ArrayList<String>(f.invites));
            factionsData.set(base+".claims",new ArrayList<String>(f.claims));
            if(f.home!=null) {
                factionsData.set(base+".home.world",f.home.getWorld().getName());
                factionsData.set(base+".home.x",f.home.getX());
                factionsData.set(base+".home.y",f.home.getY());
                factionsData.set(base+".home.z",f.home.getZ());
                factionsData.set(base+".home.yaw",f.home.getYaw());
                factionsData.set(base+".home.pitch",f.home.getPitch());
            }
        }

        factionsData.set("power",null);
        for(Map.Entry<String,Double> e:power.entrySet()) factionsData.set("power."+e.getKey(),e.getValue());
        saveYaml(factionsData,factionsFile);
    }

    private void saveAll() {
        saveYaml(ranksData,ranksFile);
        saveYaml(kitsData,kitsFile);
        saveYaml(economyData,economyFile);
        saveYaml(statsData,statsFile);
        saveFactions();
        if (warpManager != null) warpManager.save();
    }

    private void saveYaml(YamlConfiguration y,File f) {
        try {
            y.save(f);
        } catch(IOException e) {
            getLogger().severe("Could not save "+f.getName()+": "+e.getMessage());
        }
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&',s);
    }

    private static String join(Collection<String> xs,String sep) {
        StringBuilder b=new StringBuilder();
        for(String x:xs) {
            if(b.length()>0) b.append(sep);
            b.append(x);
        }
        return b.toString();
    }
}
