package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap authoritative simulation for identities/factions/economy.
 *
 * This is deliberately COLD-state logic: it does not require one Minecraft
 * client per identity. Physical bodies can later be leased from a small HOT
 * Mineflayer pool when an action must be visible.
 */
final class SimWorldDirector {
    static final int MAX_FACTION_MEMBERS = 5;

    enum Stage {
        RECRUITING,
        SCOUT_CLAIM,
        GATHER_STARTER,
        BUILD_STARTER,
        ECONOMY,
        BREWER,
        GEARING,
        PVP_READY
    }

    enum CombatClass {
        DIAMOND,
        BARD,
        ARCHER,
        ROGUE,
        MINER
    }

    static final class SimPlayer {
        String name;
        String faction = "";
        String role = "member";
        String preferredJob = "member";
        CombatClass combatClass = CombatClass.DIAMOND;
        boolean leaderCandidate;
        boolean underdogLeader;
        double balance;
        int skill;          // 0..100
        int aggression;     // 0..100
        int bargaining;     // 0..100
        int leadership;     // 0..100
        int teamwork;       // 0..100
        final Map<String,Integer> stock = new LinkedHashMap<String,Integer>();
    }

    static final class SimFaction {
        String name;
        String leader;
        int targetSize;
        Stage stage = Stage.RECRUITING;
        String basePreset;
        String trapPreset = "none";
        boolean powerFaction;
        boolean underdog;
        boolean claimed;
        boolean storage;
        boolean brewer;
        boolean recoveryMode;
        int baseX;
        int baseY = 64;
        int baseZ;
        int claimRadiusChunks = 1;
        int p4Sets;
        int sharp4Swords;
        int bardSets;
        int archerSets;
        int rogueSets;
        int healPots;
        int pearls;
        int speedPots;
        int firePots;
        int xp;
        int books;
        int lapis;
        int wood;
        int stone;
        int iron;
        int diamonds;
        int obsidian;
        int cane;
        double treasury;
        long actionCounter;
        final List<String> members = new ArrayList<String>();
    }

    static final class ChatEvent {
        final String name;
        final String message;
        ChatEvent(String name, String message) {
            this.name = name;
            this.message = message;
        }
    }

    static final class MarketOrder {
        String owner;
        String side; // SELL or BUY
        String item;
        int qty;
        int total;
        long created;
    }

    static final class Conversation {
        String simName;
        MarketOrder order;
        int quoted;
        int agreed;
        long last;
    }

    private final EraCore plugin;
    private final Random rng = new Random(881994L);
    private final File file;
    private final YamlConfiguration data;
    private final Map<String,SimPlayer> players = new LinkedHashMap<String,SimPlayer>();
    private final Map<String,SimFaction> factions = new LinkedHashMap<String,SimFaction>();
    private final Map<String,MarketOrder> activeOrders = new HashMap<String,MarketOrder>();
    private final Map<String,Conversation> conversations = new HashMap<String,Conversation>();
    private final Map<String,String> lastReplyTarget = new HashMap<String,String>();
    private BukkitTask task;
    private int factionCursor;
    private int factionNameCursor;
    private long sotwTicks;
    private long sotwStartedAt;

    private static final Pattern MONEY = Pattern.compile("(?:\\$\\s*)?(\\d{2,7})");

    private static final String[] PLAYER_NAMES = {
        "xRico","SethPvP","iTzMason","FrostyHD","NateMC","PurpleDino","xNova","CamPvP","iHackLite","Jettison",
        "MasonHD","ClutchKid","xRekt","RyanPvP","HoaxMC","Kinq","Axion","PandaPvP","Toxxic","BreezyMC",
        "RektByPing","Cxdy","LlamaPvP","Beamed","RedstoneKid","CreeperHD","iTzJordan","NightPvP","Vexing","AeroPvP",
        "HollowMC","MangoPvP","iTzChris","xSavage","KiwiMC","Vaporized","NoMercy","Finesse","xPulse","TreyHD",
        "OrbitPvP","Zyro","iTzLuke","Shanked","CrimsonMC","xScope","DizzyPvP","Reborn","JaxMC","VibePvP",
        "RavenHD","Ghosted","xBlade","KarmaMC","Roasted","Venomous","iTzNick","LethalPvP","QuartzMC","Murked",
        "DuskPvP","SpookyMC","xViper","TundraPvP","Jinxed","VividMC","Ripped","AuraPvP","Hexed","WafflePvP",
        "Stimpy","Stimpypvp","Marcel","PainfulPvP","lolitsalex","Skimpy","KairoPvP","Melted","xFrost","Kryptic",
        "JordanHD","Deceive","GrapePvP","SoraMC","BambooPvP","StrafeKid","Comboed","PearlGod","CaneKing","MinerMatt",
        "BrewMaster","Vaulted","ObbyKing","Farmed","xVelocity","iTzBen","CobraPvP","RivalMC","DemonHD","LunarKid"
    };

    private static final String[] FACTION_NAMES = {
        "Voltage","Vortex","Relic","Empire","Vanity","Nox","Pulse","Fatality","Rival","Apex",
        "Revolt","Savage","Eclipse","Velocity","Outcast","Titan","Venom","Origin","Dynasty","Rogue",
        "Inferno","Legacy","Forsaken","Royalty","Phantom","Chaos","Nexus","Reborn","Prime","Havoc",
        "Wicked","Astral","Reapers","Insomnia"
    };

    private static final String[] BASE_PRESETS = {
        "hcf_glass_box",
        "hcf_courtyard",
        "hcf_brewer_base",
        "hcf_trap_base"
    };

    SimWorldDirector(EraCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "simulation.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        loadOrSeed();
    }

    void start() {
        if (task != null) return;
        long period = Math.max(20L * 10L, plugin.getConfig().getLong("sim-world.tick-seconds", 30L) * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { tick(); }
        }, 20L * 8L, period);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        save();
    }

    boolean contains(String name) {
        return players.containsKey(key(name));
    }

    String factionOf(String name) {
        SimPlayer p = players.get(key(name));
        return p == null ? "" : p.faction;
    }

    List<String> factionMembers(String faction) {
        SimFaction f = factions.get(key(faction));
        if (f == null) return Collections.emptyList();
        return new ArrayList<String>(f.members);
    }

    ChatEvent nextChatEvent() {
        if (players.isEmpty()) return null;

        // During SOTW, faction formation should dominate chat naturally.
        if (sotwRecruitingActive() && rng.nextInt(100) < 55) {
            ChatEvent recruitment = recruitmentChatEvent();
            if (recruitment != null) return recruitment;
        }

        // Outside recruitment, most chat should still be caused by actual needs or inventory.
        if (rng.nextInt(100) < 62) {
            MarketOrder order = makeMarketOrder();
            if (order != null) {
                activeOrders.put(key(order.owner), order);
                String msg;
                if ("SELL".equals(order.side)) {
                    msg = "selling " + order.qty + " " + pretty(order.item) + " for $" + order.total + " msg me";
                } else {
                    msg = "buying " + order.qty + " " + pretty(order.item) + " paying $" + order.total;
                }
                return new ChatEvent(order.owner, msg);
            }
        }

        SimPlayer p = randomPlayer();
        if (p == null) return null;
        SimFaction f = p.faction.isEmpty() ? null : factions.get(key(p.faction));

        if (f != null) {
            if (f.recoveryMode) return new ChatEvent(p.name, rng.nextBoolean() ? "we are regening dtr" : "staying in base till dtr regens");
            if (f.stage == Stage.SCOUT_CLAIM) return new ChatEvent(p.name, "looking for a spot to claim");
            if (f.stage == Stage.GATHER_STARTER && "miner".equals(p.role)) return new ChatEvent(p.name, "mining for the base rn");
            if (f.stage == Stage.BREWER && !f.brewer) return new ChatEvent(p.name, "buying redstone stuff for an auto brewer");
            if (f.stage == Stage.GEARING && f.p4Sets < Math.min(2, f.members.size())) return new ChatEvent(p.name, "buying prot books msg me");
            if (f.stage == Stage.PVP_READY && rng.nextBoolean()) return new ChatEvent(p.name, "who is at spawn");
        }

        String[] neutral = {"gg","anyone at spawn","who has pearls","koth soon?","who wants ally","selling stuff msg me","lol","need levels"};
        return new ChatEvent(p.name, neutral[rng.nextInt(neutral.length)]);
    }

    String handlePrivate(Player human, String simName, String text) {
        SimPlayer sim = players.get(key(simName));
        if (sim == null) return null;

        String hk = key(human.getName());
        lastReplyTarget.put(hk, sim.name);
        Conversation conv = conversations.get(hk);
        if (conv == null || !conv.simName.equalsIgnoreCase(sim.name) || System.currentTimeMillis() - conv.last > 180000L) {
            conv = new Conversation();
            conv.simName = sim.name;
            conv.order = activeOrders.get(key(sim.name));
            conv.last = System.currentTimeMillis();
            conversations.put(hk, conv);
        }
        conv.last = System.currentTimeMillis();

        String lower = text.toLowerCase(Locale.ENGLISH).trim();
        if (conv.order == null) {
            MarketOrder order = makeOrderFor(sim);
            if (order != null) {
                conv.order = order;
                activeOrders.put(key(sim.name), order);
            }
        }

        MarketOrder o = conv.order;
        if (o == null) {
            if (lower.contains("sell") || lower.contains("buy") || lower.contains("price") || lower.contains("how much")) {
                return "not trading anything rn";
            }
            return casualReply(sim, lower);
        }

        if (lower.contains("what") || lower.contains("price") || lower.contains("how much") || lower.contains("hm")) {
            conv.quoted = o.total;
            return ("SELL".equals(o.side) ? "i can do $" : "im paying $") + o.total + " for " + o.qty + " " + pretty(o.item);
        }

        Matcher m = MONEY.matcher(lower.replace(",", ""));
        if (m.find()) {
            int offered;
            try { offered = Integer.parseInt(m.group(1)); }
            catch (Exception e) { offered = 0; }

            if ("SELL".equals(o.side)) {
                int floor = sellerFloor(sim, o.total);
                if (offered >= o.total) {
                    conv.agreed = o.total;
                    return "yeah $" + o.total + " deal";
                }
                if (offered >= floor) {
                    conv.agreed = offered;
                    return "alright $" + offered + " deal";
                }
                int counter = Math.max(floor, (offered + o.total) / 2);
                conv.quoted = counter;
                return "too low i can do $" + counter;
            } else {
                int ceiling = buyerCeiling(sim, o.total);
                if (offered <= o.total && offered > 0) {
                    conv.agreed = o.total;
                    return "yeah ill pay $" + o.total;
                }
                if (offered <= ceiling && offered > 0) {
                    conv.agreed = offered;
                    return "fine $" + offered;
                }
                conv.quoted = ceiling;
                return "nah highest ill do is $" + ceiling;
            }
        }

        if (lower.contains("deal") || lower.contains("take it") || lower.equals("yes") || lower.equals("yea") || lower.equals("yeah") || lower.equals("sure")) {
            int price = conv.agreed > 0 ? conv.agreed : (conv.quoted > 0 ? conv.quoted : o.total);
            String result = executeTrade(human, sim, o, price);
            if (result != null) {
                conversations.remove(hk);
                activeOrders.remove(key(sim.name));
                save();
                return result;
            }
        }

        if (lower.contains("lower") || lower.contains("cheaper")) {
            int floor = sellerFloor(sim, o.total);
            int counter = Math.max(floor, o.total - Math.max(50, o.total / 12));
            conv.quoted = counter;
            return "lowest ill go is $" + counter;
        }

        return casualReply(sim, lower);
    }

    String handleReply(Player human, String text) {
        String target = lastReplyTarget.get(key(human.getName()));
        if (target == null) return null;
        return handlePrivate(human, target, text);
    }

    private String executeTrade(Player human, SimPlayer sim, MarketOrder o, int price) {
        if (price <= 0) return "nvm";

        if ("SELL".equals(o.side)) {
            int available = getStock(sim, o.item);
            if (available < o.qty) return "i dont have that much anymore";
            if (plugin.balanceForSimTrade(human.getName()) < price) return "you dont have enough money";
            if (!plugin.deliverSimTradeItem(human, o.item, o.qty)) return "inventory full or item isnt ready";
            plugin.changeBalanceForSimTrade(human.getName(), -price);
            sim.balance += price;
            setStock(sim, o.item, available - o.qty);
            return "deal";
        }

        if (sim.balance < price) return "i spent too much already";
        if (!plugin.takeSimTradeItem(human, o.item, o.qty)) return "you dont have " + o.qty + " " + pretty(o.item);
        plugin.changeBalanceForSimTrade(human.getName(), price);
        sim.balance -= price;
        setStock(sim, o.item, getStock(sim, o.item) + o.qty);
        return "ty";
    }

    private int sellerFloor(SimPlayer sim, int ask) {
        double flexibility = 0.82 + (sim.bargaining / 100.0) * 0.12;
        return Math.max(1, (int)Math.round(ask * flexibility));
    }

    private int buyerCeiling(SimPlayer sim, int ask) {
        double flexibility = 1.05 + ((100 - sim.bargaining) / 100.0) * 0.16;
        return Math.max(ask, (int)Math.round(ask * flexibility));
    }

    private String casualReply(SimPlayer sim, String lower) {
        if (lower.contains("faction")) {
            if (sim.faction.isEmpty()) return "im solo rn";
            SimFaction f = factions.get(key(sim.faction));
            return f == null ? "idk" : "im in " + f.name;
        }
        if (lower.contains("where")) return "around spawn";
        if (lower.contains("thanks") || lower.equals("ty")) return "np";
        if (lower.contains("pvp")) return sim.skill >= 70 ? "maybe in a min" : "not geared yet";
        return "yeah";
    }

    private void tick() {
        sotwTicks++;
        formationTick();

        if (factions.isEmpty()) {
            save();
            return;
        }

        int work = Math.max(1, plugin.getConfig().getInt("sim-world.factions-per-tick", 4));
        List<SimFaction> list = new ArrayList<SimFaction>(factions.values());
        for (int i = 0; i < work; i++) {
            if (factionCursor >= list.size()) factionCursor = 0;
            SimFaction f = list.get(factionCursor++);
            updateDtrStrategy(f);
            advance(f);
        }

        maybeResolveOffscreenBrawl();
        save();
    }

    private void advance(SimFaction f) {
        f.actionCounter++;
        produce(f);

        switch (f.stage) {
            case RECRUITING:
                if (f.members.size() >= Math.min(2, f.targetSize) || !sotwRecruitingActive()) {
                    f.stage = Stage.SCOUT_CLAIM;
                }
                break;

            case SCOUT_CLAIM:
                if (f.actionCounter % 2 == 0 && planAndClaimBase(f)) {
                    f.claimed = true;
                    f.stage = Stage.GATHER_STARTER;
                }
                break;

            case GATHER_STARTER:
                if (f.wood >= 96 && f.stone >= 192 && f.iron >= 24) {
                    f.wood -= 96;
                    f.stone -= 192;
                    f.iron -= 24;
                    f.stage = Stage.BUILD_STARTER;
                }
                break;

            case BUILD_STARTER:
                if (f.actionCounter % 3 == 0) {
                    if (!f.storage) plugin.queueSimBaseBuild(f.name, f.basePreset, f.trapPreset, f.baseX, f.baseY, f.baseZ);
                    f.storage = true;
                    f.stage = Stage.ECONOMY;
                }
                break;

            case ECONOMY:
                // Mature factions nearly always prioritize potion infrastructure.
                if (f.iron >= 35 && f.stone >= 96 && f.obsidian >= 8) {
                    f.stage = Stage.BREWER;
                } else if (f.actionCounter % 4 == 0) {
                    buyMissingInfrastructure(f);
                }
                break;

            case BREWER:
                if (!f.brewer) {
                    // Abstract the known 1.8 hopper/timed-output brewery blueprint.
                    if (f.iron >= 35 && f.stone >= 96) {
                        f.iron -= 35;
                        f.stone -= 96;
                        f.brewer = true;
                        plugin.queueSimBrewerBuild(f.name, f.baseX, f.baseY, f.baseZ);
                    }
                }
                if (f.brewer) f.stage = Stage.GEARING;
                break;

            case GEARING:
                craftBooksAndGear(f);
                brewCombatStock(f);
                if (combatReady(f)) f.stage = Stage.PVP_READY;
                break;

            case PVP_READY:
                brewCombatStock(f);
                // They continue farming/mining/economy rather than becoming PvP-only bots.
                if (f.p4Sets < Math.max(1, f.members.size() - 1)) craftBooksAndGear(f);
                break;
        }
    }

    private void produce(SimFaction f) {
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p == null) continue;

            if ("miner".equals(p.role)) {
                f.stone += 28 + rng.nextInt(22);
                f.iron += 3 + rng.nextInt(5);
                f.xp += 3 + rng.nextInt(5);
                if (rng.nextInt(100) < 22) f.diamonds += 1 + rng.nextInt(2);
                if (rng.nextInt(100) < 30) f.obsidian += 1 + rng.nextInt(3);
            } else if ("farmer".equals(p.role)) {
                int cane = 48 + rng.nextInt(65);
                f.cane += cane;
                f.treasury += cane * 4.0;
                p.balance += cane * 1.2;
            } else if ("brewer".equals(p.role) && f.brewer) {
                f.healPots += 3 + rng.nextInt(4);
                if (rng.nextBoolean()) f.speedPots++;
                if (rng.nextInt(3) == 0) f.firePots++;
            } else {
                f.wood += 10 + rng.nextInt(15);
                f.stone += 8 + rng.nextInt(18);
            }
        }

        // Some faction wealth is spent on missing ingredients/resources instead of appearing from nowhere.
        if (f.treasury > 250 && f.iron < 24) {
            int buy = Math.min(12, (int)(f.treasury / 20));
            f.iron += buy;
            f.treasury -= buy * 20;
        }
    }

    private void buyMissingInfrastructure(SimFaction f) {
        if (f.treasury < 500) return;
        if (f.obsidian < 8) {
            int n = Math.min(8 - f.obsidian, (int)(f.treasury / 30));
            if (n > 0) {
                f.obsidian += n;
                f.treasury -= n * 30;
            }
        }
        if (f.iron < 35) {
            int n = Math.min(35 - f.iron, (int)(f.treasury / 20));
            if (n > 0) {
                f.iron += n;
                f.treasury -= n * 20;
            }
        }
    }

    /**
     * 1.8 gearing model:
     * - mass low-level book enchanting produces candidate Protection I / Sharpness I books,
     * - equal levels combine upward in balanced pairs,
     * - this avoids repeatedly stacking prior-work penalty onto one item.
     * We abstract individual GUI clicks while COLD; a HOT worker can perform them physically later.
     */
    private void craftBooksAndGear(SimFaction f) {
        // Mining/farms feed XP/lapis/books; this is not free gear creation.
        f.books += Math.max(1, f.members.size() / 2);
        f.lapis += 2 + rng.nextInt(4);
        f.xp += 2 + rng.nextInt(4);

        // Approximate enough successful level-I book outcomes to build one IV via 8 I books.
        // We deliberately require surplus generic books/lapis/XP to account for RNG misses.
        int protCostBooks = 18;
        int sharpCostBooks = 20;

        int neededBard = classCount(f, CombatClass.BARD);
        int neededArcher = classCount(f, CombatClass.ARCHER);
        int neededRogue = classCount(f, CombatClass.ROGUE);

        // HCF support sets are cheaper materially than diamond, but still must
        // actually be crafted and stocked before those players are fight-ready.
        if (f.bardSets < neededBard && f.iron >= 4) {
            f.iron -= 4;
            f.bardSets++;
        }
        if (f.archerSets < neededArcher && f.cane >= 24) {
            f.cane -= 24;
            f.archerSets++;
        }
        if (f.rogueSets < neededRogue && f.iron >= 24) {
            f.iron -= 24;
            f.rogueSets++;
        }

        if (f.p4Sets < f.members.size() && f.diamonds >= 24 && f.books >= protCostBooks * 4 && f.lapis >= 32 && f.xp >= 32) {
            f.diamonds -= 24;
            f.books -= protCostBooks * 4;
            f.lapis -= 32;
            f.xp -= 32;
            f.p4Sets++;
        }

        if (f.sharp4Swords < f.members.size() && f.diamonds >= 2 && f.books >= sharpCostBooks && f.lapis >= 8 && f.xp >= 10) {
            f.diamonds -= 2;
            f.books -= sharpCostBooks;
            f.lapis -= 8;
            f.xp -= 10;
            f.sharp4Swords++;
        }
    }

    private void brewCombatStock(SimFaction f) {
        if (!f.brewer) return;
        // Mature factions keep restocking rather than spawning a full inventory at once.
        if (f.healPots < f.members.size() * 28) f.healPots += 3 + rng.nextInt(6);
        if (f.speedPots < f.members.size() * 3) f.speedPots += 1 + rng.nextInt(2);
        if (f.firePots < f.members.size() * 2 && rng.nextBoolean()) f.firePots++;
        if (f.pearls < f.members.size() * 8 && f.treasury >= 150) {
            int buy = Math.min(3, (int)(f.treasury / 150));
            f.pearls += buy;
            f.treasury -= buy * 150;
        }
    }

    private boolean combatReady(SimFaction f) {
        int diamonds = 0;
        int bards = 0;
        int archers = 0;
        int rogues = 0;
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p == null) continue;
            if (p.combatClass == CombatClass.BARD) bards++;
            else if (p.combatClass == CombatClass.ARCHER) archers++;
            else if (p.combatClass == CombatClass.ROGUE) rogues++;
            else diamonds++;
        }

        // P4 + Sharp4 is the baseline for diamond fighters. Support classes use
        // their complete HCF armor sets instead, and nobody roams without pots/pearls.
        return f.p4Sets >= diamonds
            && f.sharp4Swords >= Math.max(1, diamonds)
            && f.bardSets >= bards
            && f.archerSets >= archers
            && f.rogueSets >= rogues
            && f.healPots >= Math.max(1, f.members.size()) * 24
            && f.pearls >= Math.max(1, f.members.size()) * 8
            && f.speedPots >= Math.max(1, f.members.size()) * 2
            && f.firePots >= Math.max(1, f.members.size());
    }

    boolean shouldSeekPvp(String name) {
        SimPlayer p = players.get(key(name));
        if (p == null || p.faction.isEmpty()) return false;
        SimFaction f = factions.get(key(p.faction));
        if (f == null) return false;
        if (sotwProtectionActive()) return false;
        if (f.recoveryMode || plugin.factionRaidable(f.name) || plugin.factionDtr(f.name) <= getDtrSafetyFloor(f)) return false;
        if (f.stage == Stage.PVP_READY) return true;
        // Elite/strong players may defend or take a favorable local fight earlier,
        // but are not told to roam undergeared.
        return false;
    }

    String tacticalDecision(String name, int enemiesNearby, int alliesNearby, boolean nearHome, boolean bardNearby) {
        SimPlayer p = players.get(key(name));
        if (p == null) return "DISENGAGE";

        SimFaction faction = p.faction.isEmpty() ? null : factions.get(key(p.faction));
        if (faction != null && faction.recoveryMode) {
            if (nearHome) return "HOLD_SAFE_ROOM";
            return "KITE_HOME_FOR_DTR";
        }

        boolean top = p.skill >= 92;
        int disadvantage = enemiesNearby - alliesNearby;

        if (disadvantage >= 2 && !top) {
            if (nearHome) return "KITE_HOME";
            if (alliesNearby > 0) return "KITE_TO_TEAM";
            return "DISENGAGE";
        }

        if (disadvantage == 1 && !top && !bardNearby) return "GROUP_BEFORE_ENGAGE";
        if (!shouldSeekPvp(name)) return enemiesNearby > 0 ? "DEFEND_OR_ESCAPE" : "AVOID_ROAM";
        if (bardNearby || alliesNearby >= enemiesNearby) return "ENGAGE";
        return p.aggression >= 65 ? "PRESSURE_CAUTIOUS" : "HOLD";
    }

    private MarketOrder makeMarketOrder() {
        for (int tries = 0; tries < 12; tries++) {
            SimPlayer p = randomPlayer();
            if (p == null) return null;
            MarketOrder o = makeOrderFor(p);
            if (o != null) return o;
        }
        return null;
    }

    private MarketOrder makeOrderFor(SimPlayer p) {
        if (!p.faction.isEmpty()) {
            SimFaction f = factions.get(key(p.faction));
            if (f != null) {
                if (f.stage.ordinal() <= Stage.BREWER.ordinal()) {
                    if (f.obsidian < 8) return buyOrder(p, "obsidian", 8 - f.obsidian, 28);
                    if (f.iron < 35) return buyOrder(p, "iron", Math.min(24, 35 - f.iron), 18);
                }
                if (f.stage == Stage.GEARING) {
                    if (f.pearls < f.members.size() * 8) return buyOrder(p, "pearl", 8, 145);
                    if (f.healPots < f.members.size() * 24) return buyOrder(p, "healthpot", 6, 82);
                }
            }
        }

        // Economic identities sell what they actually have.
        if ("farmer".equals(p.role)) {
            int qty = Math.min(512, getStock(p, "cane"));
            if (qty >= 64) return sellOrder(p, "cane", qty, 4);
        }
        if ("miner".equals(p.role)) {
            int qty = Math.min(32, getStock(p, "iron"));
            if (qty >= 8) return sellOrder(p, "iron", qty, 12);
        }

        int pearls = getStock(p, "pearl");
        if (pearls >= 8 && rng.nextBoolean()) return sellOrder(p, "pearl", 8, 175);
        return null;
    }

    private MarketOrder sellOrder(SimPlayer p, String item, int qty, int unit) {
        MarketOrder o = new MarketOrder();
        o.owner = p.name;
        o.side = "SELL";
        o.item = item;
        o.qty = Math.max(1, qty);
        o.total = o.qty * unit;
        o.created = System.currentTimeMillis();
        return o;
    }

    private MarketOrder buyOrder(SimPlayer p, String item, int qty, int unit) {
        MarketOrder o = new MarketOrder();
        o.owner = p.name;
        o.side = "BUY";
        o.item = item;
        o.qty = Math.max(1, qty);
        o.total = o.qty * unit;
        o.created = System.currentTimeMillis();
        return o;
    }

    private SimPlayer randomPlayer() {
        if (players.isEmpty()) return null;
        List<SimPlayer> xs = new ArrayList<SimPlayer>(players.values());
        return xs.get(rng.nextInt(xs.size()));
    }

    private String pretty(String key) {
        if ("healthpot".equals(key)) return "heal pots";
        if ("speedpot".equals(key)) return "speed pots";
        if ("fireres".equals(key)) return "fire res";
        if ("pearl".equals(key)) return "pearls";
        if ("cane".equals(key)) return "cane";
        return key;
    }

    private int getStock(SimPlayer p, String item) {
        Integer n = p.stock.get(item);
        return n == null ? 0 : n;
    }

    private void setStock(SimPlayer p, String item, int n) {
        p.stock.put(item, Math.max(0, n));
    }

    private void loadOrSeed() {
        ConfigurationSection ps = data.getConfigurationSection("players");
        if (ps == null || ps.getKeys(false).isEmpty()) {
            seed();
            save();
            return;
        }

        for (String k : ps.getKeys(false)) {
            ConfigurationSection s = ps.getConfigurationSection(k);
            SimPlayer p = new SimPlayer();
            p.name = s.getString("name", k);
            p.faction = s.getString("faction", "");
            p.role = s.getString("role", "member");
            p.preferredJob = s.getString("preferred-job", p.role);
            try { p.combatClass = CombatClass.valueOf(s.getString("combat-class", "DIAMOND")); } catch (Exception ignored) {}
            p.leaderCandidate = s.getBoolean("leader-candidate", false);
            p.underdogLeader = s.getBoolean("underdog-leader", false);
            p.balance = s.getDouble("balance", 500);
            p.skill = s.getInt("skill", 50);
            p.aggression = s.getInt("aggression", 50);
            p.bargaining = s.getInt("bargaining", 50);
            p.leadership = s.getInt("leadership", 50);
            p.teamwork = s.getInt("teamwork", 50);
            ConfigurationSection st = s.getConfigurationSection("stock");
            if (st != null) for (String item : st.getKeys(false)) p.stock.put(item, st.getInt(item));
            players.put(key(p.name), p);
        }

        sotwTicks = data.getLong("meta.sotw-ticks", 0L);
        sotwStartedAt = data.getLong("meta.sotw-started-at", System.currentTimeMillis());
        factionNameCursor = data.getInt("meta.faction-name-cursor", 0);

        ConfigurationSection fs = data.getConfigurationSection("factions");
        if (fs != null) {
            for (String k : fs.getKeys(false)) {
                ConfigurationSection s = fs.getConfigurationSection(k);
                SimFaction f = new SimFaction();
                f.name = s.getString("name", k);
                f.leader = s.getString("leader", "");
                f.targetSize = Math.min(MAX_FACTION_MEMBERS, s.getInt("target-size", 3));
                try { f.stage = Stage.valueOf(s.getString("stage", "SCOUT_CLAIM")); } catch (Exception ignored) {}
                f.basePreset = s.getString("base-preset", "hcf_glass_box");
                f.trapPreset = s.getString("trap-preset", "none");
                f.baseX = s.getInt("base-x", 0);
                f.baseY = s.getInt("base-y", 64);
                f.baseZ = s.getInt("base-z", 0);
                f.claimRadiusChunks = s.getInt("claim-radius-chunks", 1);
                f.recoveryMode = s.getBoolean("recovery-mode", false);
                f.powerFaction = s.getBoolean("power-faction", false);
                f.underdog = s.getBoolean("underdog", false);
                f.claimed = s.getBoolean("claimed");
                f.storage = s.getBoolean("storage");
                f.brewer = s.getBoolean("brewer");
                f.p4Sets = s.getInt("p4-sets");
                f.sharp4Swords = s.getInt("sharp4-swords");
                f.bardSets = s.getInt("bard-sets");
                f.archerSets = s.getInt("archer-sets");
                f.rogueSets = s.getInt("rogue-sets");
                f.healPots = s.getInt("heal-pots");
                f.pearls = s.getInt("pearls");
                f.speedPots = s.getInt("speed-pots");
                f.firePots = s.getInt("fire-pots");
                f.xp = s.getInt("xp");
                f.books = s.getInt("books");
                f.lapis = s.getInt("lapis");
                f.wood = s.getInt("wood");
                f.stone = s.getInt("stone");
                f.iron = s.getInt("iron");
                f.diamonds = s.getInt("diamonds");
                f.obsidian = s.getInt("obsidian");
                f.cane = s.getInt("cane");
                f.treasury = s.getDouble("treasury");
                f.actionCounter = s.getLong("actions");
                f.members.addAll(s.getStringList("members"));
                while (f.members.size() > MAX_FACTION_MEMBERS) f.members.remove(f.members.size() - 1);
                factions.put(key(f.name), f);
            }
        }

        syncFactionAuthority();
    }

    private void syncFactionAuthority() {
        for (SimFaction f : factions.values()) {
            plugin.createSimFactionAuthority(f.name, f.leader);
            for (String member : f.members) {
                if (!member.equalsIgnoreCase(f.leader)) plugin.joinSimFactionAuthority(f.name, member);
            }
        }
    }

    private void seed() {
        players.clear();
        factions.clear();
        sotwTicks = 0;
        sotwStartedAt = System.currentTimeMillis();
        factionNameCursor = 0;

        int target = Math.max(30, Math.min(100, plugin.getConfig().getInt("sim-world.population", 90)));
        List<String> names = new ArrayList<String>(Arrays.asList(PLAYER_NAMES));
        Collections.shuffle(names, new Random(2015L));

        int count = Math.min(target, names.size());
        for (int i = 0; i < count; i++) {
            SimPlayer p = new SimPlayer();
            p.name = names.get(i);
            p.balance = 250 + rng.nextInt(3500);
            p.skill = creatorSkillOverride(p.name, skillRoll());
            p.aggression = 25 + rng.nextInt(66);
            p.bargaining = 25 + rng.nextInt(66);
            p.leadership = 25 + rng.nextInt(71);
            p.teamwork = 35 + rng.nextInt(61);
            p.preferredJob = randomJob();
            p.role = p.preferredJob;
            p.combatClass = classFor(p);

            // Every strong/elite identity begins SOTW solo and is expected to
            // form/lead a power faction rather than being auto-slotted under another leader.
            p.leaderCandidate = p.skill >= 80;
            seedStock(p);
            players.put(key(p.name), p);
        }

        // A small number of non-elite leaders become underdog power-faction seeds.
        List<SimPlayer> underdogPool = new ArrayList<SimPlayer>();
        for (SimPlayer p : players.values()) {
            if (!p.leaderCandidate && p.skill >= 52 && p.skill < 80) underdogPool.add(p);
        }
        Collections.sort(underdogPool, new Comparator<SimPlayer>() {
            public int compare(SimPlayer a, SimPlayer b) {
                int sa = a.leadership + a.teamwork + ("farmer".equals(a.preferredJob) || "miner".equals(a.preferredJob) ? 20 : 0);
                int sb = b.leadership + b.teamwork + ("farmer".equals(b.preferredJob) || "miner".equals(b.preferredJob) ? 20 : 0);
                return Integer.compare(sb, sa);
            }
        });
        int underdogs = Math.min(plugin.getConfig().getInt("sim-world.underdog-leaders", 2), underdogPool.size());
        for (int i = 0; i < underdogs; i++) {
            underdogPool.get(i).leaderCandidate = true;
            underdogPool.get(i).underdogLeader = true;
        }

        // Critical SOTW rule: nobody is preassigned to a faction.
        for (SimPlayer p : players.values()) p.faction = "";
    }

    void resetForSotw() {
        plugin.resetSimFactionAuthority(new ArrayList<String>(players.keySet()));
        seed();
        save();
    }

    void endSotwProtection() {
        long mins = plugin.getConfig().getLong("sotw.protection-minutes", 60L);
        sotwStartedAt = System.currentTimeMillis() - (mins * 60L * 1000L) - 1000L;
        save();
    }

    boolean sotwProtectionActive() {
        long mins = plugin.getConfig().getLong("sotw.protection-minutes", 60L);
        return System.currentTimeMillis() - sotwStartedAt < mins * 60L * 1000L;
    }

    boolean sotwRecruitingActive() {
        int unaffiliated = 0;
        for (SimPlayer p : players.values()) if (p.faction.isEmpty()) unaffiliated++;
        int minSolo = Math.max(8, (int)Math.round(players.size() * 0.18));
        return unaffiliated > minSolo || hasUnformedLeader();
    }

    String sotwStatus() {
        int solo = 0;
        for (SimPlayer p : players.values()) if (p.faction.isEmpty()) solo++;
        long left = Math.max(0L, plugin.getConfig().getLong("sotw.protection-minutes",60L)*60L*1000L - (System.currentTimeMillis()-sotwStartedAt));
        return "SOTW factions=" + factions.size() + " solo=" + solo + " population=" + players.size() + " protection=" + (left/60000L) + "m";
    }

    private boolean hasUnformedLeader() {
        for (SimPlayer p : players.values()) if (p.leaderCandidate && p.faction.isEmpty()) return true;
        return false;
    }

    private void formationTick() {
        if (!sotwRecruitingActive()) return;

        if (sotwTicks % 2L == 0L) createNextLeaderFaction();

        List<SimFaction> open = new ArrayList<SimFaction>(factions.values());
        Collections.shuffle(open, rng);
        int recruits = 0;
        for (SimFaction f : open) {
            if (f.members.size() >= f.targetSize || f.members.size() >= MAX_FACTION_MEMBERS) continue;
            if (rng.nextInt(100) < 48 && recruitBestCandidate(f)) recruits++;
            if (recruits >= 2) break;
        }
    }

    private void createNextLeaderFaction() {
        SimPlayer best = null;
        for (SimPlayer p : players.values()) {
            if (!p.leaderCandidate || !p.faction.isEmpty()) continue;
            if (best == null) best = p;
            else {
                int ps = p.skill + p.leadership + (p.underdogLeader ? -18 : 20);
                int bs = best.skill + best.leadership + (best.underdogLeader ? -18 : 20);
                if (ps > bs) best = p;
            }
        }
        if (best == null) return;

        String name = nextFactionName();
        SimFaction f = new SimFaction();
        f.name = name;
        f.leader = best.name;
        f.targetSize = best.underdogLeader ? (3 + rng.nextInt(3)) : (rng.nextInt(100) < 55 ? 5 : 4);
        f.targetSize = Math.min(MAX_FACTION_MEMBERS, f.targetSize);
        f.basePreset = BASE_PRESETS[rng.nextInt(BASE_PRESETS.length)];
        f.powerFaction = !best.underdogLeader;
        f.underdog = best.underdogLeader;
        f.trapPreset = (best.skill < 72 || best.underdogLeader) && rng.nextInt(100) < 65 ? "fall_trap" : "none";
        f.treasury = 250 + rng.nextInt(best.underdogLeader ? 900 : 1500);
        f.members.add(best.name);

        if (!plugin.createSimFactionAuthority(f.name, best.name)) return;
        best.faction = f.name;
        best.role = "leader";
        factions.put(key(f.name), f);
    }

    private String nextFactionName() {
        for (int i = 0; i < FACTION_NAMES.length; i++) {
            String candidate = FACTION_NAMES[(factionNameCursor++) % FACTION_NAMES.length];
            if (!factions.containsKey(key(candidate))) return candidate;
        }
        return "Faction" + (factionNameCursor++);
    }

    private boolean recruitBestCandidate(SimFaction f) {
        SimPlayer best = null;
        int bestScore = Integer.MIN_VALUE;

        for (SimPlayer p : players.values()) {
            if (!p.faction.isEmpty() || p.leaderCandidate) continue;

            int score = candidateScore(f, p);
            score += rng.nextInt(17) - 8;
            if (score > bestScore) {
                best = p;
                bestScore = score;
            }
        }

        if (best == null) return false;
        if (!plugin.joinSimFactionAuthority(f.name, best.name)) return false;
        best.faction = f.name;
        best.role = best.preferredJob;
        f.members.add(best.name);
        return true;
    }

    private int candidateScore(SimFaction f, SimPlayer p) {
        int score = p.teamwork / 2 + p.leadership / 5;
        score += f.powerFaction ? p.skill : p.skill / 2;

        if (classCount(f, CombatClass.BARD) == 0 && p.combatClass == CombatClass.BARD) score += 45;
        if (classCount(f, CombatClass.ARCHER) == 0 && p.combatClass == CombatClass.ARCHER) score += 34;
        if (classCount(f, CombatClass.DIAMOND) < 2 && p.combatClass == CombatClass.DIAMOND) score += 25;

        if (jobCount(f, "miner") == 0 && "miner".equals(p.preferredJob)) score += 35;
        if (jobCount(f, "farmer") == 0 && "farmer".equals(p.preferredJob)) score += 30;
        if (jobCount(f, "builder") == 0 && "builder".equals(p.preferredJob)) score += 24;
        if (jobCount(f, "brewer") == 0 && "brewer".equals(p.preferredJob)) score += 28;

        if (f.underdog) {
            if ("farmer".equals(p.preferredJob) || "miner".equals(p.preferredJob) || "brewer".equals(p.preferredJob)) score += 28;
            score += p.teamwork / 2;
        }

        return score;
    }

    private int classCount(SimFaction f, CombatClass type) {
        int n = 0;
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p != null && p.combatClass == type) n++;
        }
        return n;
    }

    private int jobCount(SimFaction f, String job) {
        int n = 0;
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p != null && job.equals(p.preferredJob)) n++;
        }
        return n;
    }

    private ChatEvent recruitmentChatEvent() {
        List<SimPlayer> solos = new ArrayList<SimPlayer>();
        for (SimPlayer p : players.values()) {
            if (p.faction.isEmpty() && !p.leaderCandidate) solos.add(p);
        }

        List<SimFaction> open = new ArrayList<SimFaction>();
        for (SimFaction f : factions.values()) {
            if (f.members.size() < f.targetSize && f.members.size() < MAX_FACTION_MEMBERS) open.add(f);
        }

        if (!solos.isEmpty() && (open.isEmpty() || rng.nextBoolean())) {
            SimPlayer p = solos.get(rng.nextInt(solos.size()));
            return new ChatEvent(p.name, lffLine(p));
        }

        if (!open.isEmpty()) {
            SimFaction f = open.get(rng.nextInt(open.size()));
            SimPlayer leader = players.get(key(f.leader));
            if (leader != null) return new ChatEvent(leader.name, recruitingLine(f));
        }

        return null;
    }

    private String lffLine(SimPlayer p) {
        String cls = p.combatClass.name().toLowerCase(Locale.ENGLISH);
        if ("miner".equals(p.preferredJob) || "builder".equals(p.preferredJob) || "brewer".equals(p.preferredJob)) {
            return "lff " + p.preferredJob + " can " + cls;
        }
        if (p.skill >= 70) return "lff " + cls + " good at pvp";
        return "lff " + cls + " active";
    }

    private String recruitingLine(SimFaction f) {
        List<String> needs = new ArrayList<String>();
        if (classCount(f, CombatClass.BARD) == 0) needs.add("bard");
        if (classCount(f, CombatClass.ARCHER) == 0) needs.add("archer");
        if (jobCount(f, "miner") == 0) needs.add("miner");
        if (jobCount(f, "brewer") == 0) needs.add("brewer");
        if (classCount(f, CombatClass.DIAMOND) < 2) needs.add("diamond");

        int slots = Math.min(MAX_FACTION_MEMBERS, f.targetSize) - f.members.size();
        String needText = needs.isEmpty() ? "active players" : joinWords(needs, 2);
        return f.name + " recruiting " + slots + " need " + needText + " msg me";
    }

    private String joinWords(List<String> xs, int limit) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size() && i < limit; i++) {
            if (b.length() > 0) b.append(" + ");
            b.append(xs.get(i));
        }
        return b.toString();
    }

    void onAuthorityDeath(String playerName, String factionName, double dtr, boolean raidable) {
        if (factionName == null || factionName.isEmpty()) return;
        SimFaction f = factions.get(key(factionName));
        if (f == null) return;
        updateDtrStrategy(f);
        save();
    }

    private void updateDtrStrategy(SimFaction f) {
        double dtr = plugin.factionDtr(f.name);
        double max = plugin.factionMaxDtr(f.name);
        double floor = getDtrSafetyFloor(f);
        boolean danger = plugin.factionRaidable(f.name) || dtr <= floor;

        // Factions protect map progress when DTR is in danger instead of feeding.
        f.recoveryMode = danger;

        if (!danger && max > 0 && dtr >= Math.min(max, floor + 1.0)) {
            f.recoveryMode = false;
        }
    }

    private double getDtrSafetyFloor(SimFaction f) {
        // Better leaders become cautious slightly earlier; elite players still
        // may defend at home, but the faction does not deliberately roam.
        SimPlayer leader = players.get(key(f.leader));
        if (leader != null && leader.leadership >= 75) return 1.5;
        return 1.0;
    }

    private boolean planAndClaimBase(SimFaction f) {
        if (f.baseX != 0 || f.baseZ != 0) return true;

        org.bukkit.World world = Bukkit.getWorlds().get(0);
        if (world == null) return false;

        int[] point = chooseBasePoint(f);
        f.baseX = point[0];
        f.baseY = Math.max(64, plugin.getConfig().getInt("sim-world.base-y", 64));
        f.baseZ = point[1];
        f.claimRadiusChunks = f.targetSize >= 5 ? 1 : (rng.nextInt(100) < 22 ? 1 : 0);

        org.bukkit.Location home = new org.bukkit.Location(world, f.baseX + 0.5, f.baseY + 1, f.baseZ + 0.5);
        List<String> claims = squareClaims(world.getName(), f.baseX >> 4, f.baseZ >> 4, f.claimRadiusChunks);
        if (!plugin.setSimFactionHomeAndClaims(f.name, home, claims)) {
            f.baseX = 0;
            f.baseZ = 0;
            return false;
        }
        return true;
    }

    private int[] chooseBasePoint(SimFaction f) {
        org.bukkit.Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
        List<SimFaction> creatorAnchors = new ArrayList<SimFaction>();
        for (SimFaction x : factions.values()) {
            if (x.baseX == 0 && x.baseZ == 0) continue;
            SimPlayer leader = players.get(key(x.leader));
            if (leader != null && plugin.isCreatorIdentity(leader.name)) creatorAnchors.add(x);
        }

        SimPlayer leader = players.get(key(f.leader));
        boolean creatorLed = leader != null && plugin.isCreatorIdentity(leader.name);

        if (!creatorLed && !creatorAnchors.isEmpty() && rng.nextInt(100) < (f.powerFaction ? 78 : 52)) {
            SimFaction anchor = creatorAnchors.get(rng.nextInt(creatorAnchors.size()));
            double angle = rng.nextDouble() * Math.PI * 2.0;
            int distance = (f.powerFaction ? 140 : 220) + rng.nextInt(f.powerFaction ? 160 : 260);
            return new int[]{
                anchor.baseX + (int)Math.round(Math.cos(angle) * distance),
                anchor.baseZ + (int)Math.round(Math.sin(angle) * distance)
            };
        }

        double angle = rng.nextDouble() * Math.PI * 2.0;
        int radius = creatorLed ? 500 + rng.nextInt(350) : 650 + rng.nextInt(650);
        return new int[]{
            spawn.getBlockX() + (int)Math.round(Math.cos(angle) * radius),
            spawn.getBlockZ() + (int)Math.round(Math.sin(angle) * radius)
        };
    }

    private List<String> squareClaims(String world, int cx, int cz, int radius) {
        List<String> out = new ArrayList<String>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                out.add(world + ":" + x + ":" + z);
            }
        }
        return out;
    }

    /**
     * CPU-aware visible combat budget.
     *
     * A strategic fight can contain any number of identities, but only this
     * many should be promoted to real combat clients at once. Remaining
     * participants stay WARM/COLD until a HOT slot becomes relevant.
     */
    int hotCombatBudget() {
        int configured = Math.max(4, Math.min(12, plugin.getConfig().getInt("combat-director.hot-body-budget", 8)));
        return plugin.adaptiveHotBodyBudget(configured);
    }

    int hotCombatPerFactionCap() {
        return Math.max(1, Math.min(4, plugin.getConfig().getInt("combat-director.max-hot-per-faction", 3)));
    }

    String brawlPolicy(int factionsNearby, int totalParticipants, boolean ownerObserving) {
        int budget = hotCombatBudget();
        if (totalParticipants <= budget) return "ALL_HOT";
        if (ownerObserving) return "OWNER_BUBBLE_PRIORITY_" + budget;
        return factionsNearby >= 3 ? "COLD_RESOLVE_WITH_" + Math.min(4, budget) + "_REPRESENTATIVES" : "ROTATE_HOT_" + budget;
    }

    private void maybeResolveOffscreenBrawl() {
        if (sotwProtectionActive() || rng.nextInt(100) >= plugin.getConfig().getInt("combat-director.offscreen-brawl-chance-percent", 12)) return;

        List<SimFaction> ready = new ArrayList<SimFaction>();
        for (SimFaction f : factions.values()) {
            if (f.stage != Stage.PVP_READY || f.recoveryMode || plugin.factionRaidable(f.name)) continue;
            ready.add(f);
        }
        if (ready.size() < 2) return;

        // Prefer fights near creator/power-faction neighborhoods.
        SimFaction anchor = ready.get(rng.nextInt(ready.size()));
        for (SimFaction f : ready) {
            SimPlayer leader = players.get(key(f.leader));
            if (leader != null && plugin.isCreatorIdentity(leader.name) && rng.nextInt(100) < 70) {
                anchor = f;
                break;
            }
        }

        int radius = plugin.getConfig().getInt("combat-director.brawl-radius", 420);
        List<SimFaction> cluster = new ArrayList<SimFaction>();
        for (SimFaction f : ready) {
            long dx = (long)f.baseX - anchor.baseX;
            long dz = (long)f.baseZ - anchor.baseZ;
            if (dx*dx + dz*dz <= (long)radius*radius) cluster.add(f);
        }
        if (cluster.size() < 2) return;

        Collections.shuffle(cluster, rng);
        while (cluster.size() > 4) cluster.remove(cluster.size()-1);

        // Recovery factions never get dragged back into a brawl.
        SimFaction loser = weightedBrawlLoser(cluster);
        if (loser == null) return;

        SimPlayer victim = weakestExposedMember(loser);
        if (victim == null) return;

        plugin.applySimulatedFactionDeath(loser.name, victim.name);

        // A death also consumes some combat stock rather than duplicating gear/pots.
        loser.healPots = Math.max(0, loser.healPots - 10 - rng.nextInt(10));
        loser.pearls = Math.max(0, loser.pearls - 2 - rng.nextInt(4));
        if (victim.combatClass == CombatClass.DIAMOND && loser.p4Sets > 0) loser.p4Sets--;
        if (loser.sharp4Swords > 0 && victim.combatClass == CombatClass.DIAMOND) loser.sharp4Swords--;

        ChatEvent event = new ChatEvent(victim.name, rng.nextBoolean() ? "gg" : "got jumped");
        // Reuse chat cooldown path naturally on the next pulse by storing no synthetic loot.
    }

    private SimFaction weightedBrawlLoser(List<SimFaction> cluster) {
        double totalInverse = 0.0;
        List<Double> weights = new ArrayList<Double>();
        for (SimFaction f : cluster) {
            double strength = factionFightStrength(f);
            double w = 1.0 / Math.max(1.0, strength);
            weights.add(w);
            totalInverse += w;
        }
        if (totalInverse <= 0) return null;
        double roll = rng.nextDouble() * totalInverse;
        for (int i=0;i<cluster.size();i++) {
            roll -= weights.get(i);
            if (roll <= 0) return cluster.get(i);
        }
        return cluster.get(cluster.size()-1);
    }

    private double factionFightStrength(SimFaction f) {
        double score = 0;
        int n = 0;
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p == null) continue;
            score += p.skill * 1.0 + p.teamwork * 0.35 + p.aggression * 0.12;
            if (p.combatClass == CombatClass.BARD) score += 16;
            else if (p.combatClass == CombatClass.ARCHER) score += 9;
            else if (p.combatClass == CombatClass.ROGUE) score += 6;
            n++;
        }
        if (n == 0) return 1;
        score /= n;
        score += Math.min(f.members.size(),3) * 7;
        if (f.healPots >= f.members.size()*20) score += 8;
        if (f.pearls >= f.members.size()*6) score += 5;
        return score;
    }

    private SimPlayer weakestExposedMember(SimFaction f) {
        List<SimPlayer> candidates = new ArrayList<SimPlayer>();
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p != null) candidates.add(p);
        }
        if (candidates.isEmpty()) return null;
        Collections.sort(candidates, new Comparator<SimPlayer>() {
            public int compare(SimPlayer a, SimPlayer b) {
                return Integer.compare(a.skill + a.teamwork/3, b.skill + b.teamwork/3);
            }
        });
        int bound = Math.min(2, candidates.size());
        return candidates.get(rng.nextInt(bound));
    }

    private void seedStock(SimPlayer p) {
        if ("farmer".equals(p.role) || "trader".equals(p.role)) p.stock.put("cane", 64 + rng.nextInt(512));
        if ("miner".equals(p.role)) p.stock.put("iron", 8 + rng.nextInt(48));
        if (p.balance > 1500 && rng.nextInt(100) < 30) p.stock.put("pearl", 8 + rng.nextInt(24));
    }

    private int weightedFactionSize() {
        int r = rng.nextInt(100);
        if (r < 18) return 2;
        if (r < 48) return 3;
        if (r < 80) return 4;
        return 5;
    }

    private String randomJob() {
        int r = rng.nextInt(100);
        if (r < 23) return "miner";
        if (r < 43) return "farmer";
        if (r < 61) return "builder";
        if (r < 75) return "brewer";
        return "fighter";
    }

    private CombatClass classFor(SimPlayer p) {
        int r = rng.nextInt(100);
        if (p.skill >= 80) {
            if (r < 72) return CombatClass.DIAMOND;
            if (r < 82) return CombatClass.ARCHER;
            if (r < 90) return CombatClass.BARD;
            if (r < 97) return CombatClass.ROGUE;
            return CombatClass.MINER;
        }
        if (r < 48) return CombatClass.DIAMOND;
        if (r < 64) return CombatClass.BARD;
        if (r < 78) return CombatClass.ARCHER;
        if (r < 88) return CombatClass.ROGUE;
        return CombatClass.MINER;
    }

    private int creatorSkillOverride(String name, int rolled) {
        String n = key(name);
        if (n.equals("stimpy") || n.equals("stimpypvp") || n.equals("marcel") || n.equals("painfulpvp")) return 95 + rng.nextInt(6);
        if (n.equals("lolitsalex")) return 86 + rng.nextInt(7);
        if (n.equals("skimpy")) return 78 + rng.nextInt(8);
        return rolled;
    }

    private int skillRoll() {
        int r = rng.nextInt(100);
        if (r < 12) return 20 + rng.nextInt(15);
        if (r < 35) return 35 + rng.nextInt(15);
        if (r < 75) return 50 + rng.nextInt(15);
        if (r < 93) return 65 + rng.nextInt(15);
        if (r < 98) return 80 + rng.nextInt(12);
        return 92 + rng.nextInt(9);
    }

    void save() {
        data.set("players", null);
        for (SimPlayer p : players.values()) {
            String b = "players." + key(p.name);
            data.set(b + ".name", p.name);
            data.set(b + ".faction", p.faction);
            data.set(b + ".role", p.role);
            data.set(b + ".preferred-job", p.preferredJob);
            data.set(b + ".combat-class", p.combatClass.name());
            data.set(b + ".leader-candidate", p.leaderCandidate);
            data.set(b + ".underdog-leader", p.underdogLeader);
            data.set(b + ".balance", p.balance);
            data.set(b + ".skill", p.skill);
            data.set(b + ".aggression", p.aggression);
            data.set(b + ".bargaining", p.bargaining);
            data.set(b + ".leadership", p.leadership);
            data.set(b + ".teamwork", p.teamwork);
            for (Map.Entry<String,Integer> e : p.stock.entrySet()) data.set(b + ".stock." + e.getKey(), e.getValue());
        }

        data.set("factions", null);
        for (SimFaction f : factions.values()) {
            String b = "factions." + key(f.name);
            data.set(b + ".name", f.name);
            data.set(b + ".leader", f.leader);
            data.set(b + ".target-size", f.targetSize);
            data.set(b + ".stage", f.stage.name());
            data.set(b + ".base-preset", f.basePreset);
            data.set(b + ".trap-preset", f.trapPreset);
            data.set(b + ".base-x", f.baseX);
            data.set(b + ".base-y", f.baseY);
            data.set(b + ".base-z", f.baseZ);
            data.set(b + ".claim-radius-chunks", f.claimRadiusChunks);
            data.set(b + ".recovery-mode", f.recoveryMode);
            data.set(b + ".power-faction", f.powerFaction);
            data.set(b + ".underdog", f.underdog);
            data.set(b + ".claimed", f.claimed);
            data.set(b + ".storage", f.storage);
            data.set(b + ".brewer", f.brewer);
            data.set(b + ".p4-sets", f.p4Sets);
            data.set(b + ".sharp4-swords", f.sharp4Swords);
            data.set(b + ".bard-sets", f.bardSets);
            data.set(b + ".archer-sets", f.archerSets);
            data.set(b + ".rogue-sets", f.rogueSets);
            data.set(b + ".heal-pots", f.healPots);
            data.set(b + ".pearls", f.pearls);
            data.set(b + ".speed-pots", f.speedPots);
            data.set(b + ".fire-pots", f.firePots);
            data.set(b + ".xp", f.xp);
            data.set(b + ".books", f.books);
            data.set(b + ".lapis", f.lapis);
            data.set(b + ".wood", f.wood);
            data.set(b + ".stone", f.stone);
            data.set(b + ".iron", f.iron);
            data.set(b + ".diamonds", f.diamonds);
            data.set(b + ".obsidian", f.obsidian);
            data.set(b + ".cane", f.cane);
            data.set(b + ".treasury", f.treasury);
            data.set(b + ".actions", f.actionCounter);
            data.set(b + ".members", new ArrayList<String>(f.members));
        }

        data.set("meta.sotw-ticks", sotwTicks);
        data.set("meta.sotw-started-at", sotwStartedAt);
        data.set("meta.faction-name-cursor", factionNameCursor);

        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save simulation.yml: " + e.getMessage());
        }
    }

    private String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ENGLISH);
    }
}
