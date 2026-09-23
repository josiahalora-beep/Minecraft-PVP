package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
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

    enum PvpIntent {
        AVOID,
        SOLO_HUNT,
        SMALL_TEAM,
        TEAMFIGHT,
        TRAP_PLAY
    }

    static final class SimPlayer {
        String name;
        String faction = "";
        String role = "member";
        String factionTitle = "member";
        String preferredJob = "member";
        CombatClass combatClass = CombatClass.DIAMOND;
        boolean leaderCandidate;
        boolean underdogLeader;
        double balance;
        int skill;          // 0..100 legacy/composite combat estimate
        int mechanics;      // 0..100 aim/movement/pot/pearl execution
        int pvpIq;          // 0..100 tactical fighting decisions
        int gameSense;      // 0..100 terrain/inventory/HCF knowledge
        int aggression;     // 0..100
        int bargaining;     // 0..100
        int leadership;     // 0..100
        int composure;      // 0..100 leader pressure handling
        int charisma;       // 0..100 recruiting/social pull
        int decisiveness;   // 0..100 willingness to make roster/strategic calls
        int standards;      // 0..100 selectiveness for elite factions
        int politicalIq;    // 0..100 alliances/rivalries/community reading
        int leaderExperience;
        int duelWins;
        int duelLosses;
        int teamwork;       // 0..100
        int economicIq;      // 0..100
        int loyalty;         // 0..100
        int riskTolerance;   // 0..100
        int sociability;     // 0..100
        int patience;        // 0..100
        int reputation;      // persistent PvP reputation, 0+
        int kills;
        int deaths;
        int donorLevel;       // 0 Member, 1 Basic, 2 Silver, 3 Gold, 4 Platinum
        double donationUsd;    // simulated lifetime store spend; rank may also be won
        String staffRole = ""; // "", MOD, ADMIN
        int ownerAffinity;    // -100..100, learned from owner interactions
        int moderationTrust;  // 0..100
        long bannedUntil;
        long communityJoinedTick;
        int pendingVoteKeys;
        int pendingDonorKeys;
        long lastVoteAt;
        long lastDonorKeyAt;
        long nextDuelRequestAt;
        boolean logicalOnline;
        int sessionTicksLeft;
        long nextGoalTick;
        String currentGoal = "idle";
        String farmCrop = "";
        int farmCells;
        double farmInvestment;
        long farmCycles;
        boolean farmReady;
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
        boolean farmBuilt;
        boolean recoveryMode;
        String archetype = "BALANCED";
        String campTarget = "";
        boolean specialTrapBuilt;
        int baseX;
        int baseY = 64;
        int baseZ;
        int claimRadiusChunks = 1;
        int buildProgress;
        int buildTarget;
        boolean baseQueued;
        boolean surfaceQueued;
        int storageTier;
        boolean netherPortal;
        boolean endPortal;
        int p4Sets;
        int sharp4Swords;
        int bardSets;
        int archerSets;
        int rogueSets;
        int healPots;
        int pearls;
        int speedPots;
        int firePots; // legacy save compatibility only; always zero in live rules
        int xp;
        int books;
        int lapis;
        int wood;
        int stone;
        int iron;
        int diamonds;
        int obsidian;
        int glass;
        int cane;
        double treasury;
        long actionCounter;
        final List<String> members = new ArrayList<String>();
    }

    static final class ChatEvent {
        final String name;
        final String message;
        final boolean fastFollow;
        ChatEvent(String name, String message) {
            this(name,message,false);
        }
        ChatEvent(String name, String message, boolean fastFollow) {
            this.name = name;
            this.message = message;
            this.fastFollow = fastFollow;
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

    static final class SocialEdge {
        String from;
        String to;
        int affinity; // -100..100
        int trust;    // 0..100
        int respect;  // 0..100
        int grudge;   // 0..100
        long lastInteraction;
        final Deque<String> memories=new ArrayDeque<String>();
    }

    static final class HistoryEvent {
        long at;
        int importance;
        String type="";
        String summary="";
        String faction="";
        final List<String> people=new ArrayList<String>();
    }


    static final class WorkerTask {
        String identity;
        String faction;
        String action;
        String zone = "base";
        String combatClass = "DIAMOND";
        String preferredJob = "member";
        String allies = "";
        String leader = "";
        String keyType = "";
        String interaction = "none";
        String interactionAction = "none";
        String targetBlock = "";
        String event = "none";
        String pvpIntent = "AVOID";
        int desiredPartySize = 1;
        int homeX;
        int homeY;
        int homeZ;
        int gateX;
        int gateY;
        int gateZ;
        int dropX;
        int dropY;
        int dropZ;
        int elevatorX;
        int elevatorY;
        int elevatorZ;
        int undergroundY;
        int x;
        int y;
        int z;
        int priority;
        int aggression;
        int loyalty;
        int teamwork;
        int risk;
        int patience;
        int economicIq;

        String wire() {
            return "action=" + action +
                " faction=" + (faction == null || faction.isEmpty() ? "none" : faction) +
                " zone=" + zone +
                " class=" + combatClass +
                " job=" + preferredJob +
                " allies=" + allies +
                " leader=" + leader +
                " keyType=" + keyType +
                " interaction=" + interaction +
                " interactionAction=" + interactionAction +
                " targetBlock=" + targetBlock +
                " event=" + event +
                " pvpIntent=" + pvpIntent +
                " partySize=" + desiredPartySize +
                " homeX=" + homeX + " homeY=" + homeY + " homeZ=" + homeZ +
                " gateX=" + gateX + " gateY=" + gateY + " gateZ=" + gateZ +
                " dropX=" + dropX + " dropY=" + dropY + " dropZ=" + dropZ +
                " elevatorX=" + elevatorX + " elevatorY=" + elevatorY + " elevatorZ=" + elevatorZ +
                " undergroundY=" + undergroundY +
                " x=" + x + " y=" + y + " z=" + z +
                " priority=" + priority +
                " aggression=" + aggression +
                " loyalty=" + loyalty +
                " teamwork=" + teamwork +
                " risk=" + risk +
                " patience=" + patience +
                " economicIq=" + economicIq;
        }
    }


    static final class CombatAssignment {
        String fightId;
        String name;
        String faction;
        String enemyFaction;
        String world = "world";
        CombatClass combatClass;
        String action;
        int skill;
        int mechanics;
        int pvpIq;
        int gameSense;
        int composure;
        int mistake;
        int aggression;
        int risk;
        int x;
        int y;
        int z;
        int homeX;
        int homeY;
        int homeZ;
        int trapX;
        int trapY;
        int trapZ;
        String trapType = "none";
        String focus = "";
        int lootHealNeed;
        int lootPearlNeed;
        int lootSpeedNeed;
        int lootSetNeed;
        final List<String> enemies = new ArrayList<String>();
        final List<String> allies = new ArrayList<String>();

        String wire() {
            return "fight=" + fightId +
                " world=" + world +
                " faction=" + faction +
                " enemyFaction=" + enemyFaction +
                " class=" + combatClass.name() +
                " action=" + action +
                " skill=" + skill +
                " mechanics=" + mechanics +
                " pvpIq=" + pvpIq +
                " gameSense=" + gameSense +
                " composure=" + composure +
                " mistake=" + mistake +
                " aggression=" + aggression +
                " risk=" + risk +
                " x=" + x + " y=" + y + " z=" + z +
                " homeX=" + homeX + " homeY=" + homeY + " homeZ=" + homeZ +
                " trapX=" + trapX + " trapY=" + trapY + " trapZ=" + trapZ +
                " trapType=" + trapType +
                " focus=" + focus +
                " lootHealNeed=" + lootHealNeed +
                " lootPearlNeed=" + lootPearlNeed +
                " lootSpeedNeed=" + lootSpeedNeed +
                " lootSetNeed=" + lootSetNeed +
                " enemies=" + joinNames(enemies) +
                " allies=" + joinNames(allies);
        }

        private static String joinNames(List<String> xs) {
            StringBuilder b=new StringBuilder();
            for(String x:xs) {
                if(b.length()>0) b.append(',');
                b.append(x);
            }
            return b.toString();
        }
    }

    static final class VisibleFight {
        String id;
        long expiresAt;
        String type;
        String world = "world";
        int centerX;
        int centerY;
        int centerZ;
        String anchorFaction="";
        String ownerName="";
        int teamSize=0;
        final Map<String,CombatAssignment> assignments = new LinkedHashMap<String,CombatAssignment>();
    }


    static final class CombatReservation {
        String fightId;
        String name;
        String faction;
        CombatClass type;
        int healPots;
        int pearls;
        int speedPots;
        int minerIron;
        boolean personal;
    }

    private final EraCore plugin;
    private final SimEconomyModel economy;
    private final ContextChatBrain chatBrain;
    private final AiChatBridge aiChat;
    private final Random rng = new Random(881994L);
    private final File file;
    private final File combatFile;
    private final File memoryFile;
    private final YamlConfiguration data;
    private final Map<String,SimPlayer> players = new LinkedHashMap<String,SimPlayer>();
    private final Map<String,SimFaction> factions = new LinkedHashMap<String,SimFaction>();
    private final Map<String,MarketOrder> activeOrders = new HashMap<String,MarketOrder>();
    private final Map<String,Conversation> conversations = new HashMap<String,Conversation>();
    private final Map<String,SocialEdge> socialEdges = new LinkedHashMap<String,SocialEdge>();
    private final Deque<HistoryEvent> communityHistory = new ArrayDeque<HistoryEvent>();
    private final Map<String,Long> recentHistoryFingerprints = new LinkedHashMap<String,Long>();
    private final Set<String> combatLootMemoryOnce = new LinkedHashSet<String>();
    private final Map<String,String> lastReplyTarget = new HashMap<String,String>();
    private final Deque<ChatEvent> pendingChat = new ArrayDeque<ChatEvent>();
    private final Map<String,Integer> rivalries = new HashMap<String,Integer>();
    private final Deque<String> recentPublicSpeakers = new ArrayDeque<String>();
    private final Deque<String> recentPublicMessages = new ArrayDeque<String>();
    private final Map<String,String> lastPublicLineBySpeaker = new HashMap<String,String>();
    private String recentKiller = "";
    private String recentVictim = "";
    private VisibleFight visibleFight;
    private long nextVisibleFightAt;
    private final Map<String,CombatReservation> combatReservations = new HashMap<String,CombatReservation>();
    private final Set<String> projectedCombatDeaths = new HashSet<String>();
    private final Map<String,org.bukkit.Location> storageChestCache = new HashMap<String,org.bukkit.Location>();
    private BukkitTask task;
    private BukkitTask physicalBaseTask;
    private final Set<String> physicalBaseVerified = new HashSet<String>();
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
        "BrewMaster","Vaulted","ObbyKing","Farmed","xVelocity","iTzBen","CobraPvP","RivalMC","DemonHD","LunarKid",
        "RazePvP","DriftMC","KiloPvP","NovaHD","HexPvP","SwayMC","Avenge","BoltPvP","MercyMC","Shiver",
        "Ruthless","FluxMC","TempoPvP","EonMC","FablePvP","HazeMC","ScythePvP","NeroMC","WraithPvP","GlitchMC",
        "KeenPvP","RookMC","Knockback","Pearled","RefillKid","Quickdrop","Spleefed","Potting","KiteMC","ArcherKid",
        "BardMain","RogueMain","DiamondKid","CappedPvP","Tagged","DTRKing","KOTHKid","RoadPvP","NetherKid","EndRoamer",
        "Claimed","FacFocus","DebuffMC","InvisKid","PearlClip","StrafeMC","ComboMC","Chased","Trapped","GateKid",
        "VaultMC","BrewerKid","CaneFarmer","SilkTouch","ObbyMiner","Glowstone","BlazeKid","RegenPvP","Speeded","FireRes"
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
        "hcf_trap_base",
        "hcf_compact_2015",
        "hcf_split_level",
        "hcf_archer_tower",
        "hcf_double_layer"
    };

    SimWorldDirector(EraCore plugin) {
        this.plugin = plugin;
        this.economy = new SimEconomyModel(plugin);
        this.chatBrain = new ContextChatBrain();
        this.aiChat = new AiChatBridge(plugin);
        this.file = new File(plugin.getDataFolder(), "simulation.yml");
        this.combatFile = new File(plugin.getDataFolder(), "combat-hot.yml");
        this.memoryFile = new File(plugin.getDataFolder(), "memory-events.log");

        YamlConfiguration loaded=new YamlConfiguration();
        if(file.isFile() && file.length()>0L) {
            try {
                loaded.load(file);
            } catch(Exception ex) {
                throw new IllegalStateException(
                    "simulation.yml is invalid; refusing to seed a replacement over persistent faction/player state. "+
                    "Restore the last-good/installer backup before starting EraCore.",ex);
            }
        }
        this.data = loaded;
        loadOrSeed();
        loadMemoryArchive();
    }

    void start() {
        if (task != null) return;
        long period = Math.max(20L * 10L, plugin.getConfig().getLong("sim-world.tick-seconds", 30L) * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { tick(); }
        }, 20L * 8L, period);

        if(plugin.getConfig().getBoolean("base-builder.lazy-materialization",true)) {
            long reconcileTicks=Math.max(60L,plugin.getConfig().getLong("base-builder.lazy-check-ticks",100L));
            physicalBaseTask=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
                public void run(){ reconcileVisiblePhysicalBase(); }
            },100L,reconcileTicks);
        }
    }

    void registerExistingAutoBrewers() {
        for(SimFaction f:factions.values()) {
            if(f.brewer && (f.baseX!=0 || f.baseZ!=0))
                plugin.registerAutoBrewerSite(f.name,f.basePreset,f.baseX,f.baseY,f.baseZ);
        }
    }

    void repairExistingBaseTerrainAndClaims() {
        if (data.getInt("meta.terrain-repair-version",0) >= 9) return;

        org.bukkit.World world=Bukkit.getWorlds().get(0);
        if(world==null) return;

        int rebuilt=0;
        for(SimFaction f : factions.values()) {
            if(f.baseX==0 && f.baseZ==0) continue;

            // Version 9 is a true rematerialization. Version 8 only overlaid
            // geometry and could be skipped/suppressed, leaving broken bases in place.
            plugin.forceSimBaseRebuild(f.name,f.basePreset,f.trapPreset,
                f.baseX,f.baseY,f.baseZ,Math.max(1,f.storageTier),
                f.brewer,f.netherPortal,f.endPortal);
            rebuilt++;

            int[] rect=baseFootprintRect(f);
            org.bukkit.Location home=new org.bukkit.Location(world,f.baseX+0.5,f.baseY+1,f.baseZ+0.5);
            plugin.setSimFactionHomeAndRectClaim(f.name,home,rect[0],rect[1],rect[2],rect[3]);
        }
        data.set("meta.terrain-repair-version",9);
        recordHistory("BASE_REBUILD",5,"Base Intelligence v9 force-rematerialized "+rebuilt+
            " faction bases","", "");
        plugin.getLogger().info("Base Intelligence v9: force-rematerializing "+rebuilt+
            " saved faction bases with sealed connected geometry.");
        save();
    }

    int forceRebuildBases(String selector) {
        org.bukkit.World world=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(world==null) return 0;

        String wanted=selector==null?"":selector.trim();
        boolean all="all".equalsIgnoreCase(wanted) || wanted.isEmpty();
        int rebuilt=0;

        for(SimFaction f:factions.values()) {
            if(f.baseX==0 && f.baseZ==0) continue;
            if(!all && !f.name.equalsIgnoreCase(wanted)) continue;

            plugin.forceSimBaseRebuild(f.name,f.basePreset,f.trapPreset,
                f.baseX,f.baseY,f.baseZ,Math.max(1,f.storageTier),
                f.brewer,f.netherPortal,f.endPortal);

            int[] rect=baseFootprintRect(f);
            org.bukkit.Location home=new org.bukkit.Location(world,f.baseX+0.5,f.baseY+1,f.baseZ+0.5);
            plugin.setSimFactionHomeAndRectClaim(f.name,home,rect[0],rect[1],rect[2],rect[3]);
            rebuilt++;
        }

        if(rebuilt>0) {
            data.set("meta.terrain-repair-version",9);
            recordHistory("BASE_REBUILD",5,"Owner force-rebuilt "+rebuilt+
                (all?" faction bases":" faction base: "+wanted),"", "");
            save();
        }
        return rebuilt;
    }


    void stop() {
        if (task != null) task.cancel();
        task = null;
        if(physicalBaseTask!=null) physicalBaseTask.cancel();
        physicalBaseTask=null;
        physicalBaseVerified.clear();
        save();
    }

    private void reconcileVisiblePhysicalBase() {
        if(!plugin.getConfig().getBoolean("base-builder.lazy-materialization",true)) return;
        if(plugin.simBaseQueuedOperations()>0) return;

        double p95=plugin.currentP95Mspt();
        double maxP95=Math.max(10.0,plugin.getConfig().getDouble("base-builder.lazy-max-p95-mspt",18.0));
        if(p95>=0.0 && p95>maxP95) return;

        World world=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(world==null) return;
        double radius=Math.max(64.0,plugin.getConfig().getDouble("base-builder.lazy-radius",144.0));
        double r2=radius*radius;

        for(Player body:Bukkit.getOnlinePlayers()) {
            if(body==null || !body.isOnline() || body.getWorld()!=world) continue;
            Location here=body.getLocation();

            SimFaction best=null;
            double bestD2=Double.MAX_VALUE;
            for(SimFaction f:factions.values()) {
                if(f.baseX==0 && f.baseZ==0) continue;
                if(!f.surfaceQueued && !f.baseQueued &&
                   f.stage.ordinal()<Stage.BUILD_STARTER.ordinal()) continue;

                double dx=here.getX()-f.baseX;
                double dz=here.getZ()-f.baseZ;
                double d2=dx*dx+dz*dz;
                if(d2<=r2 && d2<bestD2) { best=f; bestD2=d2; }
            }
            if(best==null) continue;

            String key=best.name.toLowerCase(Locale.ENGLISH);
            if(physicalBaseVerified.contains(key)) continue;
            if(!plugin.simBaseFootprintLoaded(best.name,best.basePreset,best.baseX,best.baseY,best.baseZ)) continue;

            if(plugin.simBaseLooksMaterialized(best.name,best.basePreset,best.baseX,best.baseY,best.baseZ)) {
                physicalBaseVerified.add(key);
                continue;
            }

            if(best.baseQueued || best.stage.ordinal()>=Stage.BUILD_STARTER.ordinal()) {
                plugin.lazyMaterializeSimBase(best.name,best.basePreset,best.trapPreset,
                    best.baseX,best.baseY,best.baseZ,Math.max(1,best.storageTier),
                    best.brewer,best.netherPortal,best.endPortal);
            } else if(best.surfaceQueued) {
                plugin.queueSimSurfaceBuild(best.name,best.basePreset,best.baseX,best.baseY,best.baseZ);
            } else {
                continue;
            }

            int[] rect=baseFootprintRect(best);
            Location home=new Location(world,best.baseX+0.5,best.baseY+1,best.baseZ+0.5);
            plugin.setSimFactionHomeAndRectClaim(best.name,home,rect[0],rect[1],rect[2],rect[3]);
            physicalBaseVerified.add(key);
            plugin.getLogger().info("Lazy Base Intelligence: materializing visible faction base "+best.name+
                " near "+body.getName()+" queuedOps="+plugin.simBaseQueuedOperations()+
                " p95="+String.format(Locale.US,"%.2f",Math.max(0.0,p95)));
            return; // one faction per reconciliation pass
        }
    }

    boolean contains(String name) {
        return players.containsKey(key(name));
    }

    String nearestBaseFaction(Location at,double maxDistance) {
        if(at==null || at.getWorld()==null) return "";
        SimFaction best=null;
        double bestD=maxDistance*maxDistance;
        for(SimFaction f:factions.values()) {
            if(f.baseX==0 && f.baseZ==0) continue;
            Location l=new Location(at.getWorld(),f.baseX+0.5,f.baseY+1.0,f.baseZ+0.5);
            double d=at.distanceSquared(l);
            if(d<=bestD) {best=f;bestD=d;}
        }
        return best==null?"":best.name;
    }

    boolean recordBaseRating(String faction,int score,String rater) {
        SimFaction f=factions.get(key(faction));
        if(f==null) return false;
        int rating=Math.max(1,Math.min(5,score));
        HcfBasePlan.Profile p=baseProfile(f.name);
        long now=System.currentTimeMillis();
        String b="base-feedback."+now+"-"+Math.abs((f.name+"|"+rater).hashCode());
        data.set(b+".at",now);
        data.set(b+".faction",f.name);
        data.set(b+".rater",rater==null?"":rater);
        data.set(b+".score",rating);
        data.set(b+".members",p.members);
        data.set(b+".builder-quality",p.builderQuality);
        data.set(b+".organization",p.organization);
        data.set(b+".pvp-iq",p.pvpIq);
        data.set(b+".economic-iq",p.economicIq);
        data.set(b+".risk",p.riskTolerance);
        data.set(b+".game-sense",p.gameSense);
        data.set(b+".decisiveness",p.decisiveness);
        data.set(b+".wealth-tier",p.wealthTier);
        data.set(b+".archetype",p.archetype);
        data.set(b+".storage-tier",f.storageTier);
        data.set(b+".nether-portal",f.netherPortal);
        data.set(b+".end-portal",f.endPortal);
        recordHistory("BASE_RATING",4,(rater==null?"Owner":rater)+" rated "+f.name+
            " base "+rating+"/5",f.name,rater==null?"":rater);
        save();
        return true;
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

    HcfBasePlan.Profile baseProfile(String faction) {
        HcfBasePlan.Profile out=new HcfBasePlan.Profile();
        SimFaction f=factions.get(key(faction));
        if(f==null) return out;

        // Geometry is frozen to the faction's intended roster size so later recruiting
        // cannot move semantic anchors away from already-materialized rooms.
        out.members=Math.max(1,f.targetSize);
        out.archetype=f.archetype==null?"BALANCED":f.archetype;
        out.wealthTier=f.treasury>=8000?3:(f.treasury>=3000?2:(f.treasury>=1000?1:0));

        SimPlayer leader=players.get(key(f.leader));
        SimPlayer builder=null;
        for(String n:f.members) {
            SimPlayer p=players.get(key(n));
            if(p!=null && "builder".equals(p.preferredJob)) {
                if(builder==null || p.patience+p.economicIq+p.gameSense >
                    builder.patience+builder.economicIq+builder.gameSense) builder=p;
            }
        }
        if(builder==null) builder=leader;

        if(leader!=null) {
            out.pvpIq=leader.pvpIq;
            out.economicIq=leader.economicIq;
            out.riskTolerance=leader.riskTolerance;
            out.gameSense=leader.gameSense;
            out.decisiveness=leader.decisiveness;
            out.organization=Math.max(0,Math.min(100,
                (leader.economicIq+leader.patience+leader.gameSense)/3));
        }
        if(builder!=null) {
            out.builderQuality=Math.max(0,Math.min(100,
                (builder.patience+builder.economicIq+builder.gameSense+builder.mechanics)/4));
            out.organization=Math.max(0,Math.min(100,
                (out.organization+builder.patience+builder.economicIq)/3));
        }
        return out;
    }


    int combatAssignmentCount() {
        return visibleFight == null ? 0 : visibleFight.assignments.size();
    }

    String visibleFightSummary() {
        if(visibleFight==null) return "none";
        String owner=visibleFight.ownerName==null||visibleFight.ownerName.isEmpty()?"":" owner="+visibleFight.ownerName;
        return visibleFight.type+" id="+visibleFight.id+" world="+visibleFight.world+
            " botBodies="+visibleFight.assignments.size()+owner+
            " center="+visibleFight.centerX+","+visibleFight.centerY+","+visibleFight.centerZ;
    }

    boolean hasVisibleFight() {
        return visibleFight != null;
    }

    String currentVisibleFightId() {
        return visibleFight == null ? "" : visibleFight.id;
    }

    Location ownerTestSpawn() {
        if(visibleFight==null || visibleFight.ownerName==null || visibleFight.ownerName.isEmpty()) return null;
        World w=Bukkit.getWorld(visibleFight.world);
        if(w==null) return null;
        int x=visibleFight.centerX-14;
        int z=visibleFight.centerZ;
        int y=Math.max(4,w.getHighestBlockYAt(x,z)+1);
        return new Location(w,x+0.5,y,z+0.5,-90f,0f);
    }

    boolean reserveCombatLoadout(String name, CombatClass type, String fightId) {
        SimPlayer p=players.get(key(name));
        if(p==null || p.faction.isEmpty()) return false;
        SimFaction f=factions.get(key(p.faction));
        if(f==null) return false;

        CombatReservation current=combatReservations.get(key(name));
        if(current!=null) {
            if(fightId.equals(current.fightId)) return true;
            return false;
        }

        // A HOT DIAMOND body that already claimed and equipped a donor/creator
        // kit can fight immediately without pretending that P1/P2/P3 gear is P4.
        if(type==CombatClass.DIAMOND && hasPersonalPhysicalCombatKit(p)) {
            CombatReservation r=new CombatReservation();
            r.fightId=fightId;
            r.name=p.name;
            r.faction=f.name;
            r.type=type;
            r.personal=true;
            combatReservations.put(key(name),r);
            save();
            return true;
        }

        // Otherwise withdraw a full faction-stock HCF loadout.
        int heal=24;
        int pearls=8;
        int speed=2;
        if(f.healPots<heal || f.pearls<pearls || f.speedPots<speed) return false;

        if(type==CombatClass.DIAMOND) {
            if(f.p4Sets<1 || f.sharp4Swords<1) return false;
            f.p4Sets--;
            f.sharp4Swords--;
        } else if(type==CombatClass.BARD) {
            if(f.bardSets<1) return false;
            f.bardSets--;
        } else if(type==CombatClass.ARCHER) {
            if(f.archerSets<1) return false;
            f.archerSets--;
        } else if(type==CombatClass.ROGUE) {
            if(f.rogueSets<1) return false;
            f.rogueSets--;
        } else {
            if(f.iron<24) return false;
            f.iron-=24;
        }

        f.healPots-=heal;
        f.pearls-=pearls;
        f.speedPots-=speed;

        CombatReservation r=new CombatReservation();
        r.fightId=fightId;
        r.name=p.name;
        r.faction=f.name;
        r.type=type;
        r.healPots=heal;
        r.pearls=pearls;
        r.speedPots=speed;
        r.minerIron=type==CombatClass.MINER?24:0;
        combatReservations.put(key(name),r);
        save();
        return true;
    }

    boolean hasCombatReservation(String name) {
        return combatReservations.containsKey(key(name));
    }

    boolean personalCombatReservationFor(String name,String fightId) {
        CombatReservation r=combatReservations.get(key(name));
        return r!=null && r.personal && (fightId==null || fightId.equals(r.fightId));
    }

    String releaseCombatLoadout(Player body) {
        if(body==null) return "none";
        CombatReservation r=combatReservations.remove(key(body.getName()));
        if(r==null) return "none";

        if(r.personal) {
            save();
            return "personal-kit";
        }

        SimFaction f=factions.get(key(r.faction));
        if(f==null) {
            clearCombatInventory(body);
            save();
            return "orphan";
        }

        // Return every tracked consumable still physically present. This also
        // conserves loot picked up from enemies because it was never in this
        // faction's stock before the fight.
        int heals=countPotion(body,(short)16421);
        int speeds=countPotion(body,(short)8226);
        int pearls=countMaterial(body,Material.ENDER_PEARL);
        f.healPots+=heals;
        f.speedPots+=speeds;
        f.pearls+=pearls;

        // Return surviving worn gear and recognizable captured sets.
        int diamondPieces=countArmorPieces(body,Material.DIAMOND_HELMET,Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_LEGGINGS,Material.DIAMOND_BOOTS);
        int goldPieces=countArmorPieces(body,Material.GOLD_HELMET,Material.GOLD_CHESTPLATE,
            Material.GOLD_LEGGINGS,Material.GOLD_BOOTS);
        int leatherPieces=countArmorPieces(body,Material.LEATHER_HELMET,Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS,Material.LEATHER_BOOTS);
        int chainPieces=countArmorPieces(body,Material.CHAINMAIL_HELMET,Material.CHAINMAIL_CHESTPLATE,
            Material.CHAINMAIL_LEGGINGS,Material.CHAINMAIL_BOOTS);
        int ironPieces=countArmorPieces(body,Material.IRON_HELMET,Material.IRON_CHESTPLATE,
            Material.IRON_LEGGINGS,Material.IRON_BOOTS);

        f.p4Sets += diamondPieces/4;
        f.bardSets += goldPieces/4;
        f.archerSets += leatherPieces/4;
        f.rogueSets += chainPieces/4;
        f.iron += (ironPieces/4)*24;

        int sharp2=0;
        for(org.bukkit.inventory.ItemStack item:allPhysicalItems(body)) {
            if(item==null || item.getType()!=Material.DIAMOND_SWORD) continue;
            Integer lvl=item.getEnchantments().get(org.bukkit.enchantments.Enchantment.DAMAGE_ALL);
            if(lvl!=null && lvl>=2) sharp2++;
        }
        f.sharp4Swords+=sharp2;

        clearCombatInventory(body);
        save();
        return "heal="+heals+" pearls="+pearls+" speed="+speeds+
            " p2="+(diamondPieces/4)+" sharp2="+sharp2;
    }

    boolean settleCombatDeath(Player body) {
        if(body==null) return false;
        CombatReservation r=combatReservations.remove(key(body.getName()));
        if(r==null) return false;

        // The reservation was already withdrawn at promotion. On death the
        // physical inventory remains in the world as loot, so return nothing.
        projectedCombatDeaths.add(key(body.getName()));
        save();
        return true;
    }

    private int countPotion(Player p,short dataValue) {
        int n=0;
        for(org.bukkit.inventory.ItemStack item:allPhysicalItems(p)) {
            if(item!=null && item.getType()==Material.POTION && item.getDurability()==dataValue) n+=item.getAmount();
        }
        return n;
    }

    private int countMaterial(Player p,Material material) {
        int n=0;
        for(org.bukkit.inventory.ItemStack item:allPhysicalItems(p)) {
            if(item!=null && item.getType()==material) n+=item.getAmount();
        }
        return n;
    }

    private int countArmorPieces(Player p,Material h,Material c,Material l,Material b) {
        int n=0;
        for(org.bukkit.inventory.ItemStack item:allPhysicalItems(p)) {
            if(item==null) continue;
            Material m=item.getType();
            if(m==h||m==c||m==l||m==b) n+=item.getAmount();
        }
        return n;
    }

    private List<org.bukkit.inventory.ItemStack> allPhysicalItems(Player p) {
        List<org.bukkit.inventory.ItemStack> out=new ArrayList<org.bukkit.inventory.ItemStack>();
        for(org.bukkit.inventory.ItemStack i:p.getInventory().getContents()) if(i!=null) out.add(i);
        for(org.bukkit.inventory.ItemStack i:p.getInventory().getArmorContents()) if(i!=null) out.add(i);
        return out;
    }

    private void clearCombatInventory(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        p.updateInventory();
    }

    CombatAssignment combatAssignmentFor(String name) {
        if (visibleFight == null) return null;
        return visibleFight.assignments.get(key(name));
    }

    boolean startTeamFightTest(Player observer,int requestedSize) {
        if(observer==null || observer.getWorld()==null || visibleFight!=null) return false;
        int teamSize=Math.max(3,Math.min(5,requestedSize));

        List<SimFaction> eligible=new ArrayList<SimFaction>();
        for(SimFaction f:factions.values()) {
            if(f.members.size()>=teamSize) eligible.add(f);
        }
        if(eligible.size()<2) return false;

        Collections.sort(eligible,new Comparator<SimFaction>() {
            public int compare(SimFaction a,SimFaction b) {
                return Integer.compare(teamStrength(b),teamStrength(a));
            }
        });

        SimFaction a=eligible.get(0), b=eligible.get(1);
        List<SimPlayer> aa=testTeam(a,teamSize-1);
        List<SimPlayer> bb=testTeam(b,teamSize);
        if(aa.size()<teamSize-1 || bb.size()<teamSize) return false;

        Location ol=observer.getLocation();
        Vector dir=ol.getDirection().setY(0);
        if(dir.lengthSquared()<0.01) dir=new Vector(1,0,0);
        dir.normalize();
        double testDistance=plugin.isHcfSafezone(ol)?88.0:34.0;
        int cx=(int)Math.round(ol.getX()+dir.getX()*testDistance);
        int cz=(int)Math.round(ol.getZ()+dir.getZ()*testDistance);
        World w=observer.getWorld();
        int cy=Math.max(4,w.getHighestBlockYAt(cx,cz)+1);

        VisibleFight fight=new VisibleFight();
        fight.id="TESTTEAM_"+teamSize+"_"+System.currentTimeMillis();
        fight.type="TEST_"+teamSize+"V"+teamSize+"_OWNER";
        fight.world=observer.getWorld().getName();
        fight.centerX=cx;fight.centerY=cy;fight.centerZ=cz;
        fight.ownerName=observer.getName();
        fight.teamSize=teamSize;
        fight.expiresAt=System.currentTimeMillis()+180000L;

        addTestAssignments(fight,a,b,aa,bb,-1,observer.getName(),true,teamSize);
        addTestAssignments(fight,b,a,bb,aa,1,observer.getName(),false,teamSize);
        visibleFight=fight;
        nextVisibleFightAt=fight.expiresAt+15000L;
        writeCombatFile();
        return true;
    }

    private int teamStrength(SimFaction f) {
        int s=0;
        for(String n:f.members) {
            SimPlayer p=players.get(key(n));
            if(p!=null) s+=overallCombatSkill(p)+p.teamwork/3+p.pvpIq/8;
        }
        return s;
    }

    private List<SimPlayer> testTeam(SimFaction f,int count) {
        List<SimPlayer> xs=new ArrayList<SimPlayer>();
        for(String n:f.members) {
            SimPlayer p=players.get(key(n));
            if(p!=null) xs.add(p);
        }
        Collections.sort(xs,new Comparator<SimPlayer>() {
            public int compare(SimPlayer a,SimPlayer b){return Integer.compare(overallCombatSkill(b),overallCombatSkill(a));}
        });
        while(xs.size()>count) xs.remove(xs.size()-1);
        return xs;
    }

    private void applyLootNeeds(CombatAssignment ca,SimFaction f) {
        if(ca==null || f==null) return;
        int members=Math.max(1,f.members.size());
        ca.lootHealNeed=Math.max(0,members*28-f.healPots);
        ca.lootPearlNeed=Math.max(0,members*8-f.pearls);
        ca.lootSpeedNeed=Math.max(0,members*3-f.speedPots);
        ca.lootSetNeed=Math.max(0,members-f.p4Sets);
    }

    private void addTestAssignments(VisibleFight fight,SimFaction own,SimFaction enemy,
                                    List<SimPlayer> allies,List<SimPlayer> enemies,int side,
                                    String ownerName,boolean ownerOnOwnSide,int teamSize) {
        // 3v3: Diamond owner + Diamond + Archer.
        // 4v4/5v5: one Bard + one Archer, remaining simulated teammates Diamond.
        SimPlayer bard=teamSize>=4?bestSupport(allies,true,null):null;
        SimPlayer archer=teamSize>=3?bestSupport(allies,false,bard):null;

        SimPlayer focus=chooseTestFocus(enemies);
        int index=0;
        for(SimPlayer p:allies) {
            CombatAssignment ca=new CombatAssignment();
            ca.fightId=fight.id;ca.name=p.name;ca.faction=own.name;ca.enemyFaction=enemy.name;
            ca.world=fight.world;
            ca.skill=p.skill;
            ca.mechanics=p.mechanics;
            ca.pvpIq=p.pvpIq;
            ca.gameSense=p.gameSense;
            ca.composure=p.composure;
            ca.mistake=combatMistakePropensity(p);
            ca.aggression=p.aggression;ca.risk=p.riskTolerance;
            ca.homeX=own.baseX;ca.homeY=own.baseY+1;ca.homeZ=own.baseZ;
            ca.trapType="none";
            applyLootNeeds(ca,own);
            if(!ownerOnOwnSide && rng.nextInt(100)<40) ca.focus=ownerName;
            else ca.focus=focus==null?"":focus.name;
            ca.combatClass=(p==bard)?CombatClass.BARD:((p==archer)?CombatClass.ARCHER:CombatClass.DIAMOND);
            ca.action=(p==bard)?"BARD_SUPPORT":((p==archer)?"ARCHER_RANGE":"FOCUS");
            ca.x=fight.centerX+side*(10+(index%2)*2);
            ca.z=fight.centerZ+(index-(allies.size()/2))*3;
            World fightWorld=Bukkit.getWorld(fight.world);
            ca.y=Math.max(4,(fightWorld==null?Bukkit.getWorlds().get(0):fightWorld).getHighestBlockYAt(ca.x,ca.z)+1);
            for(SimPlayer e:enemies) ca.enemies.add(e.name);
            for(SimPlayer m:allies) if(m!=p) ca.allies.add(m.name);
            if(ownerOnOwnSide) ca.allies.add(ownerName);
            else ca.enemies.add(ownerName);
            fight.assignments.put(key(p.name),ca);
            index++;
        }
    }

    private SimPlayer bestSupport(List<SimPlayer> xs,boolean bard,SimPlayer exclude) {
        SimPlayer best=null;int bestScore=Integer.MIN_VALUE;
        for(SimPlayer p:xs) {
            if(p==exclude) continue;
            int score;
            if(bard) score=p.teamwork+p.patience+p.gameSense+p.pvpIq/2;
            else score=p.mechanics+p.pvpIq+p.aggression/2+p.teamwork/2;
            if(score>bestScore){best=p;bestScore=score;}
        }
        return best;
    }

    private SimPlayer chooseTestFocus(List<SimPlayer> enemies) {
        if(enemies.isEmpty()) return null;
        SimPlayer best=enemies.get(0);
        int bestScore=Integer.MAX_VALUE;
        for(SimPlayer p:enemies) {
            int score=overallCombatSkill(p)+p.teamwork/2+p.composure/3+p.gameSense/4;
            if(score<bestScore){best=p;bestScore=score;}
        }
        return best;
    }

    void refreshVisibleCombat() {
        // Community duels have their own request/timeout/death lifecycle and are
        // allowed during SOTW inside the dedicated arena.
        if(visibleFight!=null && "DUEL".equals(visibleFight.type)) {
            writeCombatFile();
            return;
        }

        long now=System.currentTimeMillis();
        Player observer = combatObserver();

        if (sotwProtectionActive()) {
            if(visibleFight!=null && visibleFight.id!=null &&
               visibleFight.id.startsWith("TESTTEAM_SOTW_")) {
                if(now>=visibleFight.expiresAt) clearVisibleFight();
                else writeCombatFile();
                return;
            }
            clearVisibleFight();
            if(observer==null || now<nextVisibleFightAt) return;

            int minS=Math.max(10,plugin.getConfig().getInt("combat-director.sotw-scrim-min-seconds",18));
            int maxS=Math.max(minS,plugin.getConfig().getInt("combat-director.sotw-scrim-max-seconds",34));
            nextVisibleFightAt=now+(minS+rng.nextInt(maxS-minS+1))*1000L;
            if(rng.nextInt(100)<plugin.getConfig().getInt("combat-director.sotw-scrim-chance-percent",72)) {
                VisibleFight scrim=createSotwScrim(observer);
                if(scrim!=null) {
                    visibleFight=scrim;
                    writeCombatFile();
                }
            }
            return;
        }

        if (observer == null) return;
        if (visibleFight != null) {
            if (now >= visibleFight.expiresAt || !fightStillRelevant(observer,visibleFight)) {
                visibleFight=null;
                writeCombatFile();
            } else {
                writeCombatFile();
                return;
            }
        }

        if (now < nextVisibleFightAt) return;

        int minS=Math.max(6,plugin.getConfig().getInt("combat-director.visible-fight-min-seconds",10));
        int maxS=Math.max(minS,plugin.getConfig().getInt("combat-director.visible-fight-max-seconds",22));
        nextVisibleFightAt=now+(minS+rng.nextInt(maxS-minS+1))*1000L;

        if (rng.nextInt(100) >= plugin.getConfig().getInt("combat-director.visible-fight-chance-percent",94)) return;

        VisibleFight fight=createVisibleFight(observer);
        if(fight!=null) {
            visibleFight=fight;
            writeCombatFile();
        }
    }

    private VisibleFight createSotwScrim(Player observer) {
        if(observer==null || !plugin.duelArenaReady()) return null;
        Location center=plugin.duelCenterLocation();
        if(center==null || center.getWorld()==null) return null;

        List<SimFaction> eligible=new ArrayList<SimFaction>();
        for(SimFaction f:factions.values()) {
            int online=0;
            for(String n:f.members) {
                SimPlayer p=players.get(key(n));
                if(p!=null && p.logicalOnline && p.bannedUntil<=System.currentTimeMillis()) online++;
            }
            if(online>0) eligible.add(f);
        }
        if(eligible.size()<2) return null;

        Collections.sort(eligible,new Comparator<SimFaction>() {
            public int compare(SimFaction a,SimFaction b) {
                return Integer.compare(teamStrength(b),teamStrength(a));
            }
        });

        SimFaction a=eligible.get(rng.nextInt(Math.min(4,eligible.size())));
        SimFaction b=null;
        for(SimFaction candidate:eligible) {
            if(candidate!=a){b=candidate;break;}
        }
        if(b==null) return null;

        int desired=(a.members.size()>=2 && b.members.size()>=2 && rng.nextInt(100)<35)?2:1;
        List<SimPlayer> aa=testTeam(a,desired);
        List<SimPlayer> bb=testTeam(b,desired);
        if(aa.size()<desired || bb.size()<desired) return null;

        VisibleFight fight=new VisibleFight();
        fight.id="TESTTEAM_SOTW_"+desired+"_"+System.currentTimeMillis();
        fight.type="SOTW_SCRIM_"+desired+"v"+desired;
        fight.world=center.getWorld().getName();
        fight.centerX=center.getBlockX();
        fight.centerY=center.getBlockY();
        fight.centerZ=center.getBlockZ();
        fight.anchorFaction=a.name;
        fight.teamSize=desired;
        fight.expiresAt=System.currentTimeMillis()+
            Math.max(45,plugin.getConfig().getInt("combat-director.sotw-scrim-duration-seconds",75))*1000L;

        addAssignments(fight,a,b,aa,bb,false);
        addAssignments(fight,b,a,bb,aa,false);
        recordHistory("SOTW_SCRIM",2,a.name+" and "+b.name+" ran a protected practice scrim",
            a.name,a.leader,b.leader);
        return fight;
    }

    private Player combatObserver() {
        // Prefer a human so visible combat naturally forms around the player.
        for(Player body:Bukkit.getOnlinePlayers())
            if(!plugin.isBotIdentity(body.getName())) return body;

        // Offline/solo-server mode still needs a living PvP scene. Prefer a HOT
        // worker who is already roaming for fights, then any embodied worker.
        Player fallback=null;
        for(Player body:Bukkit.getOnlinePlayers()) {
            if(!plugin.isBotIdentity(body.getName())) continue;
            SimPlayer p=players.get(key(body.getName()));
            if(p!=null && "patrol".equals(p.currentGoal) && shouldSeekPvp(p.name)) return body;
            if(fallback==null) fallback=body;
        }
        return fallback;
    }

    private boolean fightStillRelevant(Player observer, VisibleFight f) {
        if(f==null) return false;

        for(CombatAssignment ca:f.assignments.values()) {
            SimFaction sf=factions.get(key(ca.faction));
            if(sf!=null && (sf.recoveryMode || plugin.factionRaidable(sf.name))) return false;
        }

        // Autonomous fights are strategic events, not camera tricks. Once
        // started they persist until timeout/recovery even if the worker that
        // happened to seed the encounter rotates out.
        if(observer==null || plugin.isBotIdentity(observer.getName())) return true;
        if(f.world==null || !observer.getWorld().getName().equalsIgnoreCase(f.world)) return false;

        int radius=Math.max(80,plugin.getConfig().getInt("combat-director.observation-radius",160));
        double dx=observer.getLocation().getX()-f.centerX;
        double dz=observer.getLocation().getZ()-f.centerZ;
        return dx*dx+dz*dz <= (double)(radius*2)*(radius*2);
    }

    private VisibleFight createVisibleFight(Player observer) {
        List<SimFaction> allReady=new ArrayList<SimFaction>();
        for(SimFaction f:factions.values()) {
            boolean openingKit=personalCombatSlots(f)>0;
            if((f.stage!=Stage.PVP_READY && f.stage!=Stage.GEARING && !openingKit) ||
               f.recoveryMode || plugin.factionRaidable(f.name) || combatStockSlots(f)<=0) continue;
            int active=0;
            for(String member:f.members) {
                SimPlayer p=players.get(key(member));
                if(p!=null && p.logicalOnline && shouldSeekPvp(p.name)) active++;
            }
            if(active>0) allReady.add(f);
        }
        if(allReady.size()<2) return null;

        final Location ol=observer.getLocation();
        SimFaction observedBase=null;
        if(observer.getWorld().equals(Bukkit.getWorlds().get(0))) {
            double limit=Math.pow(plugin.getConfig().getInt("combat-director.observation-radius",160)*1.6,2);
            double best=Double.MAX_VALUE;
            for(SimFaction f:allReady) {
                double d=distSq(ol.getX(),ol.getZ(),f.baseX,f.baseZ);
                if(d<=limit && d<best){observedBase=f;best=d;}
            }
        }

        final String observerZone=zoneForWorld(observer.getWorld());
        List<SimFaction> ready=new ArrayList<SimFaction>();
        if(observedBase!=null) {
            ready.add(observedBase);
            for(SimFaction f:allReady) {
                if(f==observedBase) continue;
                if((f.campTarget!=null && f.campTarget.equalsIgnoreCase(observedBase.name)) ||
                   rivalryScore(f.name,observedBase.name)>=8) ready.add(f);
            }
            if(ready.size()<2) {
                for(SimFaction f:allReady) if(f!=observedBase && !ready.contains(f)) {ready.add(f);break;}
            }
        } else {
            for(SimFaction f:allReady) if(observerZone.equals(warzoneForFaction(f))) ready.add(f);
        }
        if(ready.size()<2) return null;

        Collections.sort(ready,new Comparator<SimFaction>() {
            public int compare(SimFaction a,SimFaction b) {
                int pa=(a.powerFaction?30:0)+("PVP".equals(a.archetype)?20:0);
                int pb=(b.powerFaction?30:0)+("PVP".equals(b.archetype)?20:0);
                if(pa!=pb) return Integer.compare(pb,pa);
                return Integer.compare(teamStrength(b),teamStrength(a));
            }
        });

        SimFaction roamingFaction=null;
        if(observedBase==null && plugin.isBotIdentity(observer.getName())) {
            SimPlayer roaming=players.get(key(observer.getName()));
            if(roaming!=null && roaming.faction!=null && !roaming.faction.isEmpty()) {
                SimFaction candidate=factions.get(key(roaming.faction));
                if(candidate!=null && ready.contains(candidate)) roamingFaction=candidate;
            }
        }
        SimFaction a=observedBase!=null?observedBase:(roamingFaction!=null?roamingFaction:ready.get(0));

        // Power/creator neighborhoods can occasionally turn into the messy
        // three-faction brawls that old HCF maps were known for. The worker pool
        // now reserves the physical budget for combat first, so these can scale
        // past the old eight-body ceiling without stacking normal workers on top.
        if (ready.size() >= 3 && rng.nextInt(100) <
                plugin.getConfig().getInt("combat-director.three-way-brawl-chance-percent",14)) {
            VisibleFight multi=createThreeWayFight(observer,ready,a);
            if(multi!=null) return multi;
        }

        SimFaction b=null;

        // A roaming HOT worker that actually sees an enemy should create that
        // encounter first. This converts organic Mineflayer proximity into a
        // director-sanctioned fight instead of teleporting unrelated factions
        // into the scene.
        if(observedBase==null) {
            double bestBody=Double.MAX_VALUE;
            for(Player body:Bukkit.getOnlinePlayers()) {
                if(!plugin.isBotIdentity(body.getName()) || body.equals(observer) ||
                   !body.getWorld().equals(observer.getWorld())) continue;
                SimPlayer other=players.get(key(body.getName()));
                if(other==null || other.faction==null || other.faction.isEmpty() ||
                   other.faction.equalsIgnoreCase(a.name)) continue;
                SimFaction candidate=factions.get(key(other.faction));
                if(candidate==null || !ready.contains(candidate)) continue;
                double d=body.getLocation().distanceSquared(observer.getLocation());
                if(d<bestBody) { bestBody=d; b=candidate; }
            }
        }

        // If this faction is being deliberately camped, use the campers first.
        for(SimFaction candidate:ready) {
            if(b!=null) break;
            if(candidate==a) continue;
            if(candidate.campTarget!=null && candidate.campTarget.equalsIgnoreCase(a.name)) {
                b=candidate;
                break;
            }
        }

        // Prefer real neighbors/rivals before teleporting a distant rivalry into view.
        int neighborRadius=Math.max(250,plugin.getConfig().getInt("combat-director.brawl-radius",420)*2);
        for(SimFaction candidate:ready) {
            if(b!=null) break;
            if(candidate==a) continue;
            if(distSq(a.baseX,a.baseZ,candidate.baseX,candidate.baseZ)<=neighborRadius*neighborRadius) {
                b=candidate;
                break;
            }
        }
        if(b==null) {
            for(SimFaction candidate:ready) {
                if(candidate!=a){b=candidate;break;}
            }
        }
        if(b==null) return null;

        boolean atBase=observedBase!=null && a==observedBase;

        int[] sizes=rollFightSizes(a,b);
        List<SimPlayer> sideA=pickFightMembers(a,sizes[0]);
        List<SimPlayer> sideB=pickFightMembers(b,sizes[1]);
        if(sideA.isEmpty() || sideB.isEmpty()) return null;

        int cx,cz;
        String anchor="";
        boolean trapFight=false;
        if(atBase) {
            cx=a.baseX;
            cz=a.baseZ-26;
            anchor=a.name;
            trapFight=!"none".equalsIgnoreCase(a.trapPreset) && rng.nextInt(100)<trapBaitChance(a);
        } else {
            // Stage the visible part of an already-roaming encounter around the observer.
            // If the observer is inside a spawn Safezone, force the fight beyond
            // the protected boundary so it appears in Warzone, not inside spawn.
            double angle=rng.nextDouble()*Math.PI*2.0;
            int safeRadius=Math.max(40,plugin.getConfig().getInt("safezones.overworld-radius",110));
            double dist=plugin.isHcfSafezone(ol) ? (safeRadius+12+rng.nextInt(30)) : (32+rng.nextInt(36));
            cx=(int)Math.round(ol.getX()+Math.cos(angle)*dist);
            cz=(int)Math.round(ol.getZ()+Math.sin(angle)*dist);
        }

        World w=observer.getWorld();
        int cy=Math.max(4,w.getHighestBlockYAt(cx,cz)+1);

        VisibleFight fight=new VisibleFight();
        fight.id="F"+System.currentTimeMillis();
        fight.type=fightType(sizes[0],sizes[1],trapFight);
        fight.world=observer.getWorld().getName();
        fight.centerX=cx; fight.centerY=cy; fight.centerZ=cz;
        fight.anchorFaction=anchor;
        int duration=Math.max(35,plugin.getConfig().getInt("combat-director.visible-fight-duration-seconds",95));
        fight.expiresAt=System.currentTimeMillis()+duration*1000L;

        addAssignments(fight,a,b,sideA,sideB,trapFight && a==a);
        addAssignments(fight,b,a,sideB,sideA,false);

        if(trapFight) {
            recordRivalry(a.name,b.name,3);
            SimPlayer bait=bestBaiter(sideA);
            if(bait!=null) {
                CombatAssignment ba=fight.assignments.get(key(bait.name));
                if(ba!=null) {
                    if("fall_trap".equalsIgnoreCase(a.trapPreset)) ba.action="BAIT_FALL";
                    else if("drop_chute".equalsIgnoreCase(a.trapPreset)) ba.action="BAIT_DROP";
                    else ba.action="BAIT_GATE";
                }
            }
        }

        return fight;
    }

    private static double distSq(double ax,double az,double bx,double bz) {
        double dx=ax-bx,dz=az-bz;
        return dx*dx+dz*dz;
    }

    private VisibleFight createThreeWayFight(Player observer,List<SimFaction> ready,SimFaction anchor) {
        List<SimFaction> nearby=new ArrayList<SimFaction>();
        int radius=Math.max(300,plugin.getConfig().getInt("combat-director.brawl-radius",420)*2);

        boolean overworld=observer.getWorld().equals(Bukkit.getWorlds().get(0));
        for(SimFaction f:ready) {
            if(f==anchor) continue;
            if(!overworld || distSq(anchor.baseX,anchor.baseZ,f.baseX,f.baseZ)<=radius*radius) nearby.add(f);
        }
        if(nearby.size()<2) return null;

        Collections.sort(nearby,new Comparator<SimFaction>() {
            public int compare(SimFaction x,SimFaction y) {
                int rx=rivalryScore(anchor.name,x.name);
                int ry=rivalryScore(anchor.name,y.name);
                return Integer.compare(ry,rx);
            }
        });

        SimFaction b=nearby.get(0);
        SimFaction d=nearby.get(1);

        int multiBudget=Math.max(6,Math.min(12,hotCombatBudget()));
        int perSide=Math.max(2,Math.min(4,multiBudget/3));
        List<SimPlayer> aa=pickFightMembers(anchor,Math.min(perSide,activeFightMembers(anchor)));
        List<SimPlayer> bb=pickFightMembers(b,Math.min(perSide,activeFightMembers(b)));
        List<SimPlayer> dd=pickFightMembers(d,Math.min(perSide,activeFightMembers(d)));
        if(aa.isEmpty()||bb.isEmpty()||dd.isEmpty()) return null;

        while(aa.size()+bb.size()+dd.size()>multiBudget) {
            if(aa.size()>=bb.size() && aa.size()>=dd.size() && aa.size()>1) aa.remove(aa.size()-1);
            else if(bb.size()>=dd.size() && bb.size()>1) bb.remove(bb.size()-1);
            else if(dd.size()>1) dd.remove(dd.size()-1);
            else break;
        }

        Location ol=observer.getLocation();
        boolean nearAnchor=observer.getWorld().equals(Bukkit.getWorlds().get(0)) &&
            distSq(ol.getX(),ol.getZ(),anchor.baseX,anchor.baseZ) <=
            Math.pow(plugin.getConfig().getInt("combat-director.observation-radius",160)*1.7,2);

        int cx,cz;
        if(nearAnchor) {
            cx=anchor.baseX;
            cz=anchor.baseZ-30;
        } else {
            double angle=rng.nextDouble()*Math.PI*2.0;
            double dist=plugin.isHcfSafezone(ol) ? (82+rng.nextInt(36)) : (38+rng.nextInt(30));
            cx=(int)Math.round(ol.getX()+Math.cos(angle)*dist);
            cz=(int)Math.round(ol.getZ()+Math.sin(angle)*dist);
        }

        World w=observer.getWorld();
        int cy=Math.max(4,w.getHighestBlockYAt(cx,cz)+1);

        VisibleFight fight=new VisibleFight();
        fight.id="M"+System.currentTimeMillis();
        fight.type="BRAWL_3WAY_"+aa.size()+"v"+bb.size()+"v"+dd.size();
        fight.world=observer.getWorld().getName();
        fight.centerX=cx; fight.centerY=cy; fight.centerZ=cz;
        fight.anchorFaction=nearAnchor?anchor.name:"";
        fight.expiresAt=System.currentTimeMillis()+
            Math.max(70,plugin.getConfig().getInt("combat-director.visible-fight-duration-seconds",95))*1000L;

        addMultiAssignments(fight,anchor,aa,bb,dd,0);
        addMultiAssignments(fight,b,bb,aa,dd,1);
        addMultiAssignments(fight,d,dd,aa,bb,2);

        recordRivalry(anchor.name,b.name,4+rng.nextInt(5));
        recordRivalry(anchor.name,d.name,3+rng.nextInt(5));
        recordRivalry(b.name,d.name,2+rng.nextInt(4));
        return fight;
    }

    private void addMultiAssignments(VisibleFight fight,SimFaction own,List<SimPlayer> allies,
                                     List<SimPlayer> enemyA,List<SimPlayer> enemyB,int side) {
        List<SimPlayer> enemies=new ArrayList<SimPlayer>();
        enemies.addAll(enemyA);
        enemies.addAll(enemyB);
        SimPlayer focusTarget=chooseFocusTarget(enemies);
        int[] trap=trapPoint(own);

        double theta=(Math.PI*2.0/3.0)*side;
        int sx=(int)Math.round(Math.cos(theta)*9);
        int sz=(int)Math.round(Math.sin(theta)*9);

        for(int i=0;i<allies.size();i++) {
            SimPlayer p=allies.get(i);
            CombatAssignment ca=new CombatAssignment();
            ca.fightId=fight.id;
            ca.name=p.name;
            ca.faction=own.name;
            ca.enemyFaction="MULTI";
            ca.world=fight.world;
            ca.combatClass=p.combatClass;
            ca.skill=p.skill;
            ca.mechanics=p.mechanics;
            ca.pvpIq=p.pvpIq;
            ca.gameSense=p.gameSense;
            ca.composure=p.composure;
            ca.mistake=combatMistakePropensity(p);
            ca.aggression=p.aggression;
            ca.risk=p.riskTolerance;
            ca.homeX=own.baseX; ca.homeY=own.baseY+1; ca.homeZ=own.baseZ;
            ca.trapX=trap[0]; ca.trapY=trap[1]; ca.trapZ=trap[2];
            ca.trapType=own.trapPreset==null?"none":own.trapPreset;
            applyLootNeeds(ca,own);
            ca.focus=focusTarget==null?"":focusTarget.name;

            ca.x=fight.centerX+sx+(i-allies.size()/2)*2;
            ca.z=fight.centerZ+sz+(i-allies.size()/2)*2;
            ca.y=Math.max(4,Bukkit.getWorlds().get(0).getHighestBlockYAt(ca.x,ca.z)+1);

            for(SimPlayer e:enemies) ca.enemies.add(e.name);
            for(SimPlayer a:allies) if(!a.name.equalsIgnoreCase(p.name)) ca.allies.add(a.name);

            if(p.combatClass==CombatClass.BARD) ca.action="BARD_SUPPORT";
            else if(p.combatClass==CombatClass.ARCHER) ca.action="ARCHER_RANGE";
            else if(p.combatClass==CombatClass.ROGUE) ca.action="ROGUE_FLANK";
            else if(enemies.size()>=allies.size()+3 && p.skill<92) ca.action="KITE_HOME";
            else if(enemies.size()>=allies.size()+3) ca.action="CLUTCH";
            else ca.action="FOCUS";
            fight.assignments.put(key(p.name),ca);
        }
    }

    private int[] rollFightSizes(SimFaction a,SimFaction b) {
        int maxA=Math.max(1,activeFightMembers(a));
        int maxB=Math.max(1,activeFightMembers(b));
        int wantA=Math.max(1,Math.min(maxA,factionDesiredPvpSize(a)));
        int wantB=Math.max(1,Math.min(maxB,factionDesiredPvpSize(b)));

        // Match intent instead of rolling arbitrary lobby sizes. Two solo hunters
        // naturally produce a 1v1; small parties produce 2v2/3v3; two factions
        // explicitly looking for a teamfight consume the available HOT budget.
        int sa,sb;
        int common=Math.min(wantA,wantB);
        if(common>=3) {
            sa=common; sb=common;
            if(wantA>wantB && rng.nextInt(100)<28) sa=Math.min(maxA,sb+1);
            else if(wantB>wantA && rng.nextInt(100)<28) sb=Math.min(maxB,sa+1);
        } else if(common==2) {
            sa=2; sb=2;
            if(wantA>=3 && rng.nextInt(100)<18) sa=Math.min(3,maxA);
            if(wantB>=3 && rng.nextInt(100)<18) sb=Math.min(3,maxB);
        } else {
            sa=1; sb=1;
            // Organic 1v2s happen, but they are an exception rather than the
            // director manufacturing constant unfair fights.
            if(wantA>=2 && rng.nextInt(100)<15) sa=Math.min(2,maxA);
            else if(wantB>=2 && rng.nextInt(100)<15) sb=Math.min(2,maxB);
        }

        int budget=Math.max(4,Math.min(12,hotCombatBudget()));
        while(sa+sb>budget) {
            if(sa>=sb && sa>1) sa--;
            else if(sb>1) sb--;
            else break;
        }
        return new int[]{Math.max(1,sa),Math.max(1,sb)};
    }

    private int activeFightMembers(SimFaction f) {
        int n=0;
        for(String member:f.members) {
            SimPlayer p=players.get(key(member));
            if(p!=null && p.logicalOnline && shouldSeekPvp(p.name)) n++;
        }
        return Math.min(n,combatStockSlots(f));
    }

    private List<SimPlayer> pickFightMembers(SimFaction f,int count) {
        List<SimPlayer> xs=new ArrayList<SimPlayer>();
        for(String member:f.members) {
            SimPlayer p=players.get(key(member));
            if(p==null || !p.logicalOnline || !shouldSeekPvp(p.name)) continue;
            xs.add(p);
        }

        final boolean large=count>=3;
        Collections.sort(xs,new Comparator<SimPlayer>() {
            public int compare(SimPlayer a,SimPlayer b) {
                int aa=("patrol".equals(a.currentGoal)?30:0)+a.aggression+a.skill/2+a.riskTolerance/3+
                    a.reputation/3+a.loyalty/3+a.teamwork/3;
                int bb=("patrol".equals(b.currentGoal)?30:0)+b.aggression+b.skill/2+b.riskTolerance/3+
                    b.reputation/3+b.loyalty/3+b.teamwork/3;
                if(large) {
                    if(a.combatClass==CombatClass.BARD) aa+=55;
                    else if(a.combatClass==CombatClass.ARCHER) aa+=35;
                    if(b.combatClass==CombatClass.BARD) bb+=55;
                    else if(b.combatClass==CombatClass.ARCHER) bb+=35;
                }
                return Integer.compare(bb,aa);
            }
        });

        int diamond=Math.max(0,Math.min(f.p4Sets,f.sharp4Swords));
        int bard=Math.max(0,f.bardSets);
        int archer=Math.max(0,f.archerSets);
        int rogue=Math.max(0,f.rogueSets);
        int miner=Math.max(0,f.iron/24);
        int slots=Math.min(count,combatStockSlots(f));

        List<SimPlayer> chosen=new ArrayList<SimPlayer>();
        for(SimPlayer p:xs) {
            if(chosen.size()>=slots) break;
            boolean available=hasPersonalPhysicalCombatKit(p);
            if(!available && p.combatClass==CombatClass.DIAMOND && diamond>0) { diamond--; available=true; }
            else if(p.combatClass==CombatClass.BARD && bard>0) { bard--; available=true; }
            else if(p.combatClass==CombatClass.ARCHER && archer>0) { archer--; available=true; }
            else if(p.combatClass==CombatClass.ROGUE && rogue>0) { rogue--; available=true; }
            else if(p.combatClass==CombatClass.MINER && miner>0) { miner--; available=true; }
            if(available) chosen.add(p);
        }
        return chosen;
    }

    private int trapBaitChance(SimFaction f) {
        SimPlayer leader=players.get(key(f.leader));
        int base=25;
        if(f.underdog) base+=25;
        if(leader!=null && leader.skill<72) base+=18;
        return Math.min(75,base);
    }

    private String fightType(int a,int b,boolean trap) {
        if(trap) return "TRAP_BAIT_"+a+"v"+b;
        if(a+b>=7) return "BRAWL_"+a+"v"+b;
        return "SKIRMISH_"+a+"v"+b;
    }

    private SimPlayer chooseFocusTarget(List<SimPlayer> enemies) {
        SimPlayer best=null;
        int bestScore=Integer.MAX_VALUE;
        for(SimPlayer p:enemies) {
            int score=p.skill + p.teamwork/3;
            if(p.combatClass==CombatClass.BARD) score-=32;
            else if(p.combatClass==CombatClass.ARCHER) score-=16;
            else if(p.combatClass==CombatClass.ROGUE) score-=8;
            score += p.riskTolerance/5;
            if(best==null || score<bestScore) {
                best=p;
                bestScore=score;
            }
        }
        return best;
    }

    private int[] trapPoint(SimFaction f) {
        if("fall_trap".equalsIgnoreCase(f.trapPreset)) return new int[]{f.baseX+9,f.baseY+1,f.baseZ-16};
        if("fence_gate_bow".equalsIgnoreCase(f.trapPreset)) return new int[]{f.baseX,f.baseY+1,f.baseZ-18};
        if("drop_chute".equalsIgnoreCase(f.trapPreset)) return new int[]{f.baseX-10,f.baseY+1,f.baseZ-17};
        return new int[]{f.baseX,f.baseY+1,f.baseZ};
    }

    private void addAssignments(VisibleFight fight,SimFaction own,SimFaction enemy,
                                List<SimPlayer> allies,List<SimPlayer> enemies,boolean defenderTrap) {
        SimPlayer focusTarget=chooseFocusTarget(enemies);
        int[] trap=trapPoint(own);
        for(int i=0;i<allies.size();i++) {
            SimPlayer p=allies.get(i);
            CombatAssignment ca=new CombatAssignment();
            ca.fightId=fight.id;
            ca.name=p.name;
            ca.faction=own.name;
            ca.enemyFaction=enemy.name;
            ca.world=fight.world;
            ca.combatClass=p.combatClass;
            ca.skill=p.skill;
            ca.mechanics=p.mechanics;
            ca.pvpIq=p.pvpIq;
            ca.gameSense=p.gameSense;
            ca.composure=p.composure;
            ca.mistake=combatMistakePropensity(p);
            ca.aggression=p.aggression;
            ca.risk=p.riskTolerance;
            ca.homeX=own.baseX; ca.homeY=own.baseY+1; ca.homeZ=own.baseZ;
            ca.trapX=trap[0]; ca.trapY=trap[1]; ca.trapZ=trap[2];
            ca.trapType=own.trapPreset == null ? "none" : own.trapPreset;
            applyLootNeeds(ca,own);
            ca.focus=focusTarget == null ? "" : focusTarget.name;

            int side=own.name.equalsIgnoreCase(fight.anchorFaction)?1:-1;
            ca.x=fight.centerX + side*(7+i*2);
            ca.z=fight.centerZ + (i-allies.size()/2)*3;
            ca.y=Math.max(4,Bukkit.getWorlds().get(0).getHighestBlockYAt(ca.x,ca.z)+1);

            for(SimPlayer e:enemies) ca.enemies.add(e.name);
            for(SimPlayer a:allies) if(!a.name.equalsIgnoreCase(p.name)) ca.allies.add(a.name);

            if(defenderTrap && i==0) ca.action="BAIT";
            else if(p.combatClass==CombatClass.BARD) ca.action="BARD_SUPPORT";
            else if(p.combatClass==CombatClass.ARCHER) ca.action="ARCHER_RANGE";
            else if(p.combatClass==CombatClass.ROGUE) ca.action="ROGUE_FLANK";
            else if(enemies.size()>=allies.size()+2 && p.pvpIq<90) ca.action="KITE_HOME";
            else if(enemies.size()>=allies.size()+2) ca.action="CLUTCH";
            else ca.action="FOCUS";

            fight.assignments.put(key(p.name),ca);
        }
    }

    private SimPlayer bestBaiter(List<SimPlayer> xs) {
        SimPlayer best=null;
        for(SimPlayer p:xs) {
            if(best==null) best=p;
            else {
                int s=p.riskTolerance+p.pvpIq+p.gameSense+p.aggression/2;
                int b=best.riskTolerance+best.pvpIq+best.gameSense+best.aggression/2;
                if(s>b) best=p;
            }
        }
        return best;
    }

    private void clearVisibleFight() {
        if(visibleFight!=null) {
            visibleFight=null;
            writeCombatFile();
        }
    }

    private void writeCombatFile() {
        YamlConfiguration y=new YamlConfiguration();
        if(visibleFight!=null) {
            y.set("fight.id",visibleFight.id);
            y.set("fight.type",visibleFight.type);
            y.set("fight.world",visibleFight.world);
            y.set("fight.expires-at",visibleFight.expiresAt);
            y.set("fight.center-x",visibleFight.centerX);
            y.set("fight.center-y",visibleFight.centerY);
            y.set("fight.center-z",visibleFight.centerZ);
            y.set("fight.anchor-faction",visibleFight.anchorFaction);
            y.set("fight.owner-name",visibleFight.ownerName);
            y.set("fight.team-size",visibleFight.teamSize);

            for(CombatAssignment ca:visibleFight.assignments.values()) {
                String b="participants."+key(ca.name);
                y.set(b+".name",ca.name);
                y.set(b+".faction",ca.faction);
                y.set(b+".enemy-faction",ca.enemyFaction);
                y.set(b+".world",ca.world);
                y.set(b+".class",ca.combatClass.name());
                y.set(b+".action",ca.action);
                y.set(b+".skill",ca.skill);
                y.set(b+".mechanics",ca.mechanics);
                y.set(b+".pvp-iq",ca.pvpIq);
                y.set(b+".game-sense",ca.gameSense);
                y.set(b+".composure",ca.composure);
                y.set(b+".mistake",ca.mistake);
                y.set(b+".aggression",ca.aggression);
                y.set(b+".risk",ca.risk);
                y.set(b+".x",ca.x);
                y.set(b+".y",ca.y);
                y.set(b+".z",ca.z);
                y.set(b+".home-x",ca.homeX);
                y.set(b+".home-y",ca.homeY);
                y.set(b+".home-z",ca.homeZ);
                y.set(b+".trap-x",ca.trapX);
                y.set(b+".trap-y",ca.trapY);
                y.set(b+".trap-z",ca.trapZ);
                y.set(b+".trap-type",ca.trapType);
                y.set(b+".focus",ca.focus);
                y.set(b+".loot-heal-need",ca.lootHealNeed);
                y.set(b+".loot-pearl-need",ca.lootPearlNeed);
                y.set(b+".loot-speed-need",ca.lootSpeedNeed);
                y.set(b+".loot-set-need",ca.lootSetNeed);
                y.set(b+".enemies",ca.enemies);
                y.set(b+".allies",ca.allies);
            }
        }
        try { y.save(combatFile); }
        catch(IOException e) { plugin.getLogger().warning("Could not save combat-hot.yml: "+e.getMessage()); }
    }

    boolean hasIdentity(String name) {
        return name!=null && players.containsKey(key(name));
    }

    String canonicalIdentity(String name) {
        SimPlayer p=name==null?null:players.get(key(name));
        return p==null?name:p.name;
    }

    boolean isLogicalOnlineIdentity(String name) {
        SimPlayer p=name==null?null:players.get(key(name));
        return p!=null && p.logicalOnline && p.bannedUntil<=System.currentTimeMillis();
    }

    String factionOfIdentity(String name) {
        SimPlayer p=name==null?null:players.get(key(name));
        return p==null || p.faction==null?"":p.faction;
    }

    Location logicalLocationFor(String name) {
        SimPlayer p=name==null?null:players.get(key(name));
        if(p==null || !p.logicalOnline) return null;
        WorkerTask t=workerTaskFor(p.name);
        World w=worldForActorZone(t.zone);
        if(w==null) return null;

        double x=t.x;
        double z=t.z;
        if(Math.abs(x)<0.001 && Math.abs(z)<0.001) {
            Location spawn=w.getSpawnLocation();
            x=spawn.getX();z=spawn.getZ();
        }
        double y=t.y;
        if(y<=1 || y>=w.getMaxHeight()) y=Math.max(2,w.getHighestBlockYAt((int)Math.floor(x),(int)Math.floor(z))+1);
        return new Location(w,x+0.5,y,z+0.5);
    }

    private World worldForActorZone(String zone) {
        String z=zone==null?"":zone.toLowerCase(Locale.ENGLISH);
        World.Environment wanted=
            "nether".equals(z)?World.Environment.NETHER:
            ("end".equals(z)?World.Environment.THE_END:World.Environment.NORMAL);
        for(World w:Bukkit.getWorlds()) if(w.getEnvironment()==wanted) return w;
        return Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
    }

    WorkerTask workerTaskFor(String name) {
        SimPlayer p = players.get(key(name));
        WorkerTask t = new WorkerTask();
        t.identity = name;
        t.action = "idle";
        t.faction = "";
        t.zone = "spawn";
        t.combatClass = p == null ? "DIAMOND" : p.combatClass.name();
        t.preferredJob = p == null ? "member" : p.preferredJob;
        if(p!=null) {
            t.aggression=p.aggression;
            t.loyalty=p.loyalty;
            t.teamwork=p.teamwork;
            t.risk=p.riskTolerance;
            t.patience=p.patience;
            t.economicIq=p.economicIq;
        }
        org.bukkit.World world = Bukkit.getWorlds().get(0);
        org.bukkit.Location spawn = world == null ? null : world.getSpawnLocation();
        t.x = spawn == null ? 0 : spawn.getBlockX();
        t.y = spawn == null ? 64 : spawn.getBlockY() + 1;
        t.z = spawn == null ? 0 : spawn.getBlockZ();
        t.priority = 0;

        if (p == null) return t;

        if (p.faction.isEmpty()) {
            if(p.logicalOnline && p.bannedUntil<=System.currentTimeMillis() &&
               (p.pendingDonorKeys>0 || p.pendingVoteKeys>0)) {
                String type=p.pendingDonorKeys>0?"donor":"vote";
                Location crate=plugin.crateLocation(type);
                if(crate!=null) {
                    t.action="crate";
                    t.zone="spawn";
                    t.keyType=type;
                    t.interaction="crate";
                    t.interactionAction="open";
                    t.targetBlock="donor".equals(type)?"ender_chest":"chest";
                    t.x=crate.getBlockX();
                    t.y=crate.getBlockY()+1;
                    t.z=crate.getBlockZ();
                    t.priority=cratePriority(p,false);
                    return t;
                }
            }
            if(!p.logicalOnline || p.bannedUntil>System.currentTimeMillis()) return t;
            t.action=soloActionFor(p);
            t.zone=soloZoneFor(p);
            t.priority=26+p.sociability/5+p.aggression/8+p.reputation/10;
            t.combatClass=p.combatClass.name();
            t.preferredJob=p.preferredJob;

            if("solo_build".equals(t.action)) {
                org.bukkit.Location s=world==null?null:world.getSpawnLocation();
                if(s!=null) {
                    int h=Math.abs((key(p.name).hashCode()*31+(int)(sotwTicks/18L)*17));
                    double angle=(h%360)*Math.PI/180.0;
                    int radius=145+((h/360)%150);
                    t.x=s.getBlockX()+(int)Math.round(Math.cos(angle)*radius);
                    t.z=s.getBlockZ()+(int)Math.round(Math.sin(angle)*radius);
                    t.y=Math.max(4,world.getHighestBlockYAt(t.x,t.z)+1);
                    t.zone="spawn";
                    t.priority+=10;
                }
            } else if("solo_loot".equals(t.action)) {
                t.priority+=18;
            }
            return t;
        }
        if (!p.logicalOnline) {
            if (!plugin.isCreatorIdentity(p.name)) return t;
            t.faction = p.faction;
            t.action = "idle";
            t.priority = 1;
            return t;
        }
        SimFaction f = factions.get(key(p.faction));
        if (f == null) return t;

        t.faction = f.name;
        t.leader = f.leader == null ? "" : f.leader;
        t.combatClass = p.combatClass.name();
        t.preferredJob = p.preferredJob;
        PvpIntent intent=pvpIntentFor(p,f);
        t.pvpIntent=intent.name();
        t.desiredPartySize=desiredPartySize(intent,f);
        StringBuilder allyNames=new StringBuilder();
        for(String member:f.members) {
            if(allyNames.length()>0) allyNames.append(',');
            allyNames.append(member);
        }
        t.allies=allyNames.toString();
        t.zone = "base";
        int bx = f.baseX;
        int by = f.baseY > 0 ? f.baseY + 1 : t.y;
        int bz = f.baseZ;
        t.homeX=bx;
        t.homeY=by;
        t.homeZ=bz;
        int[] frontGate=plugin.simBaseAnchor(f.name,f.basePreset,"gate",f.baseX,f.baseY,f.baseZ);
        t.gateX=frontGate[0];
        t.gateY=frontGate[1];
        t.gateZ=frontGate[2];
        int[] drop=plugin.simBaseAnchor(f.name,f.basePreset,"drop",f.baseX,f.baseY,f.baseZ);
        int[] elevator=plugin.simBaseAnchor(f.name,f.basePreset,"elevator",f.baseX,f.baseY,f.baseZ);
        int[] core=plugin.simBaseAnchor(f.name,f.basePreset,"core",f.baseX,f.baseY,f.baseZ);
        t.dropX=drop[0]; t.dropY=drop[1]; t.dropZ=drop[2];
        t.elevatorX=elevator[0]; t.elevatorY=elevator[1]; t.elevatorZ=elevator[2];
        t.undergroundY=core[1];

        boolean assignedFight=visibleFight!=null && visibleFight.assignments.containsKey(key(p.name));
        boolean factionFight=factionInVisibleFight(f.name);
        int helpPriority=factionFight?factionHelpPriority(p):0;

        // An actual assigned fight is always more important than keys/economy.
        // Combat workers are still controlled by combat-hot.yml; this task keeps
        // their body reserved in the HOT pool if the normal selector asks.
        if(assignedFight) {
            t.action="combat_wait";
            t.zone=zoneForFight(visibleFight);
            t.x=visibleFight.centerX;
            t.y=visibleFight.centerY;
            t.z=visibleFight.centerZ;
            t.priority=140;
            return t;
        }

        if (f.recoveryMode) {
            t.action = "safe";
            t.x = bx;
            t.y = by;
            t.z = bz;
            t.priority = Math.max(105,90+p.loyalty/5+p.patience/8);
            return t;
        }

        if(p.pendingDonorKeys>0 || p.pendingVoteKeys>0) {
            int cratePriority=cratePriority(p,true);
            if(!factionFight || cratePriority>helpPriority) {
                String type=p.pendingDonorKeys>0?"donor":"vote";
                Location crate=plugin.crateLocation(type);
                if(crate!=null) {
                    t.action="crate";
                    t.zone="spawn";
                    t.keyType=type;
                    t.interaction="crate";
                    t.interactionAction="open";
                    t.targetBlock="donor".equals(type)?"ender_chest":"chest";
                    t.x=crate.getBlockX();
                    t.y=crate.getBlockY()+1;
                    t.z=crate.getBlockZ();
                    t.priority=cratePriority;
                    return t;
                }
            }
        }

        // Loyal/team-oriented members who are not selected into the bounded
        // visible fight stay available near the active front rather than going
        // shopping/opening keys. The combat director can promote them next if a
        // slot opens.
        if(factionFight && helpPriority>=88 && visibleFight!=null) {
            t.action="patrol";
            t.zone=zoneForFight(visibleFight);
            t.x=visibleFight.centerX;
            t.y=visibleFight.centerY;
            t.z=visibleFight.centerZ;
            t.priority=helpPriority;
            return t;
        }

        switch (f.stage) {
            case RECRUITING:
                t.action = "recruit";
                t.priority = "leader".equals(p.role) ? 35 : 10;
                break;

            case SCOUT_CLAIM:
                t.action = "scout";
                t.x = bx == 0 ? t.x : bx;
                t.y = by;
                t.z = bz == 0 ? t.z : bz;
                t.priority = "leader".equals(p.role) ? 60 : 20;
                break;

            case GATHER_STARTER:
                if(f.surfaceQueued && !f.baseQueued && "builder".equals(p.preferredJob)) {
                    t.action="build";
                    t.x=bx; t.y=by; t.z=bz;
                    t.priority=108;
                } else {
                    t.action = "miner".equals(p.preferredJob) ? "mine" : "gather";
                    t.x = bx + 7;
                    t.y = by;
                    t.z = bz + 7;
                    t.priority = "miner".equals(p.preferredJob) ? 92 : 55;
                }
                break;

            case BUILD_STARTER:
                t.action = "build";
                t.x = bx;
                t.y = by;
                t.z = bz;
                t.priority = "builder".equals(p.preferredJob) ? 100 :
                    ("miner".equals(p.preferredJob) ? 90 : 72);
                break;

            case ECONOMY:
                if ("farmer".equals(p.preferredJob)) {
                    t.action = "farm";
                    t.x = bx - 8;
                    t.y = by;
                    t.z = bz + 8;
                    t.priority = 88;
                } else {
                    t.action = "supply";
                    t.x = bx;
                    t.y = by;
                    t.z = bz;
                    t.priority = "miner".equals(p.preferredJob) ? 72 : 45;
                }
                break;

            case BREWER:
                if ("brewer".equals(p.preferredJob)) {
                    t.action = "brew";
                    int[] brewer=plugin.simBaseAnchor(f.name,f.basePreset,"brewer",f.baseX,f.baseY,f.baseZ);
                    t.x = brewer[0];
                    t.y = brewer[1]+1;
                    t.z = brewer[2];
                    t.interaction="brewer";
                    t.interactionAction="operate";
                    t.targetBlock="brewing_stand";
                    t.priority = 96;
                } else {
                    t.action = "supply";
                    t.x = bx;
                    t.y = by;
                    t.z = bz;
                    t.priority = 55;
                }
                break;

            case GEARING:
                t.action = "gear";
                t.x = bx + 5;
                t.y = by;
                t.z = bz + 5;
                t.priority = ("miner".equals(p.preferredJob) || "brewer".equals(p.preferredJob)) ? 82 : 60;
                break;

            case PVP_READY:
                if(shouldContestActiveEvent(p,f)) {
                    int[] eventPoint=plugin.activeHcfEventPoint();
                    t.action="patrol";
                    t.zone="spawn";
                    t.event=plugin.activeHcfEventId();
                    if(eventPoint!=null) {
                        t.x=eventPoint[0];t.y=eventPoint[1];t.z=eventPoint[2];
                    }
                    t.priority=94+p.teamwork/5+p.reputation/8;
                } else if ("farmer".equals(p.preferredJob)) {
                    t.action = "farm";
                    t.x = bx - 8;
                    t.y = by;
                    t.z = bz + 8;
                    t.priority = 72;
                } else if ("brewer".equals(p.preferredJob)) {
                    t.action = "brew";
                    int[] brewer=plugin.simBaseAnchor(f.name,f.basePreset,"brewer",f.baseX,f.baseY,f.baseZ);
                    t.x = brewer[0];
                    t.y = brewer[1]+1;
                    t.z = brewer[2];
                    t.interaction="brewer";
                    t.interactionAction="operate";
                    t.targetBlock="brewing_stand";
                    t.priority = f.healPots<Math.max(24,f.members.size()*12)?82:68;
                } else if ("miner".equals(p.preferredJob) && !shouldRoamNow(p,f)) {
                    t.action = "mine";
                    t.zone = "base";
                    t.x = bx;
                    t.y = by;
                    t.z = bz;
                    t.priority = 68;
                } else if ("builder".equals(p.preferredJob) && !shouldRoamNow(p,f)) {
                    t.action = "build";
                    t.x = bx;
                    t.y = by;
                    t.z = bz;
                    t.priority = 65;
                } else if (shouldRoamNow(p,f)) {
                    t.action = "patrol";
                    t.zone = warzoneForFaction(f);
                    int[] patrol=plugin.hcfPatrolPoint(f.name,t.zone);
                    if(patrol!=null){t.x=patrol[0];t.y=patrol[1];t.z=patrol[2];}
                    t.priority = 58 + p.aggression/4 + ("leader".equals(p.role)?12:0);
                } else {
                    // Mature HCF did not mean every member lived in warzone.
                    // The remaining bodies visibly refill, sort, inspect and
                    // socialize around the base until the faction is ready to go.
                    t.action = (p.economicIq>=68?"gear":"social");
                    t.x = bx;
                    t.y = by;
                    t.z = bz;
                    t.priority = 54 + p.loyalty/6;
                }
                break;
        }

        if (p.logicalOnline && p.currentGoal != null && !p.currentGoal.isEmpty()) {
            String g=p.currentGoal;
            if("patrol".equals(g) && f.stage==Stage.PVP_READY &&
               !shouldRoamNow(p,f) && !shouldContestActiveEvent(p,f)) {
                g=readyBaseAction(p,f);
            }
            if ("mine".equals(g) || "gather".equals(g) || "supply".equals(g) || "build".equals(g) ||
                "farm".equals(g) || "brew".equals(g) || "gear".equals(g) || "patrol".equals(g) ||
                "scout".equals(g) || "safe".equals(g) || "recruit".equals(g) || "social".equals(g)) {
                t.action=g;
                if("patrol".equals(g)) t.zone=warzoneForFaction(f);
                t.priority=Math.max(t.priority,goalPriority(p,g));
            }
        }

        // Re-derive the physical interaction target after goal overrides. The
        // semantic target and the movement coordinates therefore cannot drift
        // apart when COLD-state planning changes a worker's current goal.
        if("brew".equals(t.action)) {
            int[] brewer=plugin.simBaseAnchor(f.name,f.basePreset,"brewer",f.baseX,f.baseY,f.baseZ);
            t.x=brewer[0]; t.y=brewer[1]+1; t.z=brewer[2];
            t.interaction="brewer";
            t.interactionAction="operate";
            t.targetBlock="brewing_stand";
        } else if("gear".equals(t.action)) {
            int[] storage=plugin.simStorageAnchor(f.name,f.basePreset,"kits",f.baseX,f.baseY,f.baseZ);
            t.x=storage[0]; t.y=storage[1]; t.z=storage[2];
            t.interaction="storage";
            t.interactionAction="open";
            t.targetBlock="chest";
        } else if("farm".equals(t.action)) {
            int[] farm=plugin.simBaseAnchor(f.name,f.basePreset,"farm",f.baseX,f.baseY,f.baseZ);
            t.x=farm[0]; t.y=farm[1]; t.z=farm[2];
        } else if("patrol".equals(t.action)) {
            if(shouldContestActiveEvent(p,f)) {
                int[] eventPoint=plugin.activeHcfEventPoint();
                t.zone="spawn";
                t.event=plugin.activeHcfEventId();
                if(eventPoint!=null){t.x=eventPoint[0];t.y=eventPoint[1];t.z=eventPoint[2];}
            } else {
                int[] patrol=plugin.hcfPatrolPoint(f.name,t.zone);
                if(patrol!=null){t.x=patrol[0];t.y=patrol[1];t.z=patrol[2];}
            }
        }
        return t;
    }

    private boolean shouldContestActiveEvent(SimPlayer p,SimFaction f) {
        if(p==null || f==null || f.recoveryMode || f.stage!=Stage.PVP_READY) return false;
        String type=plugin.activeHcfEventType();
        if(type==null || type.isEmpty() || plugin.activeHcfEventPoint()==null) return false;
        if(combatStockSlots(f)<=0 || f.healPots<8 || f.pearls<2) return false;

        int score=p.aggression+p.teamwork+p.pvpIq+p.gameSense;
        if("leader".equals(p.role)) score+=55;
        if(p.combatClass==CombatClass.BARD || p.combatClass==CombatClass.ARCHER) score+=18;
        if("farmer".equals(p.preferredJob)) score-=55;
        if("brewer".equals(p.preferredJob) && f.healPots<f.members.size()*12) score-=65;

        long epoch=System.currentTimeMillis()/180000L;
        int gate=Math.abs((key(p.name)+"|"+plugin.activeHcfEventId()+"|"+epoch).hashCode())%100;
        int chance=Math.max(15,Math.min(88,(score-145)/3));
        return gate<chance;
    }

    private boolean shouldRoamNow(SimPlayer p,SimFaction f) {
        if(p==null || f==null || f.recoveryMode || combatStockSlots(f)<=0) return false;

        int desire=p.aggression+p.riskTolerance+p.pvpIq+p.gameSense/2+p.reputation/2;
        if("leader".equals(p.role)) desire+=30;
        if("PVP".equals(f.archetype) || f.powerFaction) desire+=22;
        if("farmer".equals(p.preferredJob)) desire-=80;
        if("brewer".equals(p.preferredJob)) desire-=55;
        if("miner".equals(p.preferredJob)) desire-=30;
        if("builder".equals(p.preferredJob)) desire-=25;

        long epoch=System.currentTimeMillis()/150000L;
        int roll=Math.abs((key(p.name)+"|roam|"+epoch).hashCode())%100;
        int chance=Math.max(12,Math.min(72,(desire-115)/3));
        return roll<chance;
    }

    private String readyBaseAction(SimPlayer p,SimFaction f) {
        if(p==null) return "social";
        if("farmer".equals(p.preferredJob)) return "farm";
        if("brewer".equals(p.preferredJob)) return "brew";
        if("miner".equals(p.preferredJob)) return "mine";
        if("builder".equals(p.preferredJob)) return "build";
        return p.economicIq>=65?"gear":"social";
    }

    private int cratePriority(SimPlayer p,boolean factioned) {
        int score=62+p.economicIq/5+p.patience/6+p.riskTolerance/7;
        if(p.pendingDonorKeys>0) score+=8;
        if(p.pendingVoteKeys+p.pendingDonorKeys>=4) score+=8;
        if(factioned) score-=p.loyalty/12;
        return Math.max(55,Math.min(112,score));
    }

    private int factionHelpPriority(SimPlayer p) {
        int score=58+p.loyalty/3+p.teamwork/3+p.aggression/5;
        if(p.combatClass==CombatClass.BARD) score+=8;
        else if(p.combatClass==CombatClass.ARCHER) score+=5;
        if("farmer".equals(p.preferredJob) || "miner".equals(p.preferredJob)) score-=6;
        return Math.max(60,Math.min(128,score));
    }

    private boolean factionInVisibleFight(String faction) {
        if(visibleFight==null || faction==null || faction.isEmpty()) return false;
        for(CombatAssignment ca:visibleFight.assignments.values())
            if(faction.equalsIgnoreCase(ca.faction)) return true;
        return false;
    }

    private String zoneForFight(VisibleFight fight) {
        if(fight==null || fight.world==null) return "spawn";
        World w=Bukkit.getWorld(fight.world);
        return zoneForWorld(w);
    }

    private String soloActionFor(SimPlayer p) {
        long epoch=Math.max(0L,sotwTicks/8L);
        int roll=Math.abs((key(p.name).hashCode()*37+(int)epoch*19)%100);

        if(("builder".equals(p.preferredJob) || p.patience>=72) && roll<26) return "solo_build";
        if(p.aggression+p.riskTolerance>=125 && roll<62) return "solo_loot";
        if(p.economicIq>=72 && roll<22) return "solo_loot";
        return "solo";
    }

    private String soloZoneFor(SimPlayer p) {
        long epoch=Math.max(0L,sotwTicks/10L);
        int roll=Math.abs((key(p.name).hashCode()*31+(int)epoch*13)%100);
        if(p.aggression>=70) {
            if(roll<48) return "spawn";
            if(roll<76) return "end";
            return "nether";
        }
        if("miner".equals(p.preferredJob) && roll<45) return "nether";
        if(roll<62) return "spawn";
        if(roll<82) return "end";
        return "nether";
    }

    private String warzoneForFaction(SimFaction f) {
        if(f==null) return "spawn";

        // Population-aware funnel: most PvP-ready factions share one rotating
        // prime hotspot, so "looking for a fight" actually finds another roam.
        // Some independent roams remain for the End/Nether to avoid a scripted
        // single-lane server.
        long epoch=Math.max(0L,sotwTicks/6L);
        int global=Math.abs((int)((epoch*37L+17L)%100L));
        String prime=global<62?"spawn":(global<84?"end":"nether");

        if("TRAPPER".equals(f.archetype)) return "spawn";

        int personal=Math.abs((f.name.toLowerCase(Locale.ENGLISH).hashCode()*31+(int)epoch*19)%100);
        int funnel=plugin.getConfig().getInt("combat-director.hotspot-funnel-percent",76);
        if(personal<Math.max(50,Math.min(95,funnel))) return prime;

        if("PVP".equals(f.archetype) || f.powerFaction)
            return personal%2==0?"spawn":"end";
        return personal%3==0?"nether":(personal%2==0?"end":"spawn");
    }

    private String zoneForWorld(World w) {
        if(w==null) return "spawn";
        if(w.getEnvironment()==World.Environment.NETHER) return "nether";
        if(w.getEnvironment()==World.Environment.THE_END) return "end";
        return "spawn";
    }

    private int goalPriority(SimPlayer p,String goal) {
        if ("safe".equals(goal)) return 110;
        if ("build".equals(goal)) return "builder".equals(p.preferredJob)?108:82;
        if ("mine".equals(goal)) return "miner".equals(p.preferredJob)?104:70;
        if ("farm".equals(goal)) return "farmer".equals(p.preferredJob)?102:64;
        if ("brew".equals(goal)) return "brewer".equals(p.preferredJob)?103:65;
        if ("gear".equals(goal)) return 82;
        if ("scout".equals(goal)) return "leader".equals(p.role)?86:52;
        if ("patrol".equals(goal)) {
            SimFaction f=p.faction==null||p.faction.isEmpty()?null:factions.get(key(p.faction));
            PvpIntent intent=pvpIntentFor(p,f);
            int bonus=intent==PvpIntent.TEAMFIGHT?22:(intent==PvpIntent.SMALL_TEAM?16:
                (intent==PvpIntent.SOLO_HUNT?12:(intent==PvpIntent.TRAP_PLAY?14:0)));
            return 70 + p.aggression/4 + bonus;
        }
        if ("recruit".equals(goal)) return 48 + p.sociability/3;
        return 50;
    }

    private SimPlayer firstJobMember(SimFaction f, String job) {
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p != null && job.equals(p.preferredJob)) return p;
        }
        return null;
    }

    String depositEmbodiedWorker(Player body) {
        SimPlayer p = players.get(key(body.getName()));
        if (p == null) return "no-sim-player";
        if (p.faction.isEmpty()) return depositSoloLoot(body,p);
        SimFaction f = factions.get(key(p.faction));
        if (f == null) return "no-faction";

        int stone=0, wood=0, iron=0, diamond=0, obsidian=0;
        int cane=0, cactus=0, pumpkin=0, melon=0;

        org.bukkit.inventory.ItemStack[] contents = body.getInventory().getContents();
        for (int slot=0; slot<contents.length; slot++) {
            org.bukkit.inventory.ItemStack item = contents[slot];
            if (item == null || item.getType() == Material.AIR) continue;
            int n = item.getAmount();
            boolean take = true;

            switch (item.getType()) {
                case COBBLESTONE:
                case STONE:
                    stone += n; break;
                case LOG:
                case LOG_2:
                case WOOD:
                    wood += n; break;
                case IRON_ORE:
                case IRON_INGOT:
                    iron += n; break;
                case DIAMOND_ORE:
                case DIAMOND:
                    diamond += n; break;
                case OBSIDIAN:
                    obsidian += n; break;
                case SUGAR_CANE:
                case SUGAR_CANE_BLOCK:
                    cane += n; break;
                case CACTUS:
                    cactus += n; break;
                case PUMPKIN:
                    pumpkin += n; break;
                case MELON:
                    melon += n; break;
                default:
                    take = false;
            }

            if (take) body.getInventory().setItem(slot,null);
        }

        f.stone += stone;
        f.wood += wood;
        f.iron += iron;
        f.diamonds += diamond;
        f.obsidian += obsidian;
        if(f.storage) {
            mirrorDepositToStorage(f,Material.COBBLESTONE,stone);
            mirrorDepositToStorage(f,Material.LOG,wood);
            mirrorDepositToStorage(f,Material.IRON_INGOT,iron);
            mirrorDepositToStorage(f,Material.DIAMOND,diamond);
            mirrorDepositToStorage(f,Material.OBSIDIAN,obsidian);
        }
        if (cane > 0) p.stock.put("cane", getStock(p,"cane") + cane);
        if (cactus > 0) p.stock.put("cactus", getStock(p,"cactus") + cactus);
        if (pumpkin > 0) p.stock.put("pumpkin", getStock(p,"pumpkin") + pumpkin);
        if (melon > 0) p.stock.put("melon", getStock(p,"melon") + melon);

        save();
        return "stone="+stone+" wood="+wood+" iron="+iron+" diamond="+diamond+
            " obsidian="+obsidian+" cane="+cane+" cactus="+cactus+
            " pumpkin="+pumpkin+" melon="+melon;
    }

    private String depositSoloLoot(Player body,SimPlayer p) {
        org.bukkit.inventory.PlayerInventory inv=body.getInventory();
        int pearls=0,heals=0,speeds=0,fires=0,iron=0,obby=0,diamonds=0,cane=0,cactus=0;
        int keptPearls=0,keptHeals=0,keptSpeeds=0,keptFires=0;

        for(int slot=0;slot<36;slot++) {
            org.bukkit.inventory.ItemStack item=inv.getItem(slot);
            if(item==null || item.getType()==Material.AIR) continue;
            Material m=item.getType();
            int amount=item.getAmount();
            int take=0;
            String stockKey=null;

            if(m==Material.ENDER_PEARL) {
                int keep=Math.max(0,4-keptPearls);
                int retained=Math.min(keep,amount);
                keptPearls+=retained;
                take=amount-retained;
                stockKey="pearl";
            } else if(m==Material.POTION && item.getDurability()==(short)16421) {
                int keep=Math.max(0,10-keptHeals);
                int retained=Math.min(keep,amount);
                keptHeals+=retained;
                take=amount-retained;
                stockKey="healthpot";
            } else if(m==Material.POTION && item.getDurability()==(short)8226) {
                int keep=Math.max(0,1-keptSpeeds);
                int retained=Math.min(keep,amount);
                keptSpeeds+=retained;
                take=amount-retained;
                stockKey="speedpot";
            } else if(m==Material.POTION && item.getDurability()==(short)8259) {
                int keep=Math.max(0,1-keptFires);
                int retained=Math.min(keep,amount);
                keptFires+=retained;
                take=amount-retained;
                stockKey="fireres";
            } else if(m==Material.IRON_INGOT || m==Material.IRON_ORE) {
                take=amount; stockKey="iron";
            } else if(m==Material.OBSIDIAN) {
                take=amount; stockKey="obsidian";
            } else if(m==Material.DIAMOND || m==Material.DIAMOND_ORE) {
                take=amount; stockKey="diamond";
            } else if(m==Material.SUGAR_CANE || m==Material.SUGAR_CANE_BLOCK) {
                take=amount; stockKey="cane";
            } else if(m==Material.CACTUS) {
                take=amount; stockKey="cactus";
            }

            if(take<=0 || stockKey==null) continue;

            int remain=amount-take;
            if(remain<=0) inv.setItem(slot,null);
            else {
                item.setAmount(remain);
                inv.setItem(slot,item);
            }
            setStock(p,stockKey,getStock(p,stockKey)+take);

            if("pearl".equals(stockKey)) pearls+=take;
            else if("healthpot".equals(stockKey)) heals+=take;
            else if("speedpot".equals(stockKey)) speeds+=take;
            else if("fireres".equals(stockKey)) fires+=take;
            else if("iron".equals(stockKey)) iron+=take;
            else if("obsidian".equals(stockKey)) obby+=take;
            else if("diamond".equals(stockKey)) diamonds+=take;
            else if("cane".equals(stockKey)) cane+=take;
            else if("cactus".equals(stockKey)) cactus+=take;
        }

        body.updateInventory();
        int value=pearls*150+heals*90+speeds*70+fires*80+iron*10+obby*22+diamonds*55+cane*3+cactus*2;
        if(value>0) {
            p.reputation=Math.min(999,p.reputation+Math.min(3,1+value/1200));
            save();
        }
        return "solo-bank pearls="+pearls+" heals="+heals+" speed="+speeds+" fire="+fires+
            " iron="+iron+" obby="+obby+" diamond="+diamonds+" cane="+cane+" cactus="+cactus;
    }

    String stashEmbodiedWorker(Player body) {
        SimPlayer p=players.get(key(body.getName()));
        if(p==null || p.faction.isEmpty()) return "no-sim-player";
        SimFaction f=factions.get(key(p.faction));
        if(f==null) return "no-faction";

        // Equipping carried armor is always legal. Storage only contributes
        // additional pieces if its physical chests already exist.
        org.bukkit.inventory.PlayerInventory inv=body.getInventory();
        int moved=0;
        int healsKept=0, pearlsKept=0, foodKept=0;
        int keepSwordSlot=bestCarriedSwordSlot(inv,p.combatClass);

        for(int slot=0;slot<36;slot++) {
            org.bukkit.inventory.ItemStack item=inv.getItem(slot);
            if(item==null || item.getType()==Material.AIR) continue;
            Material m=item.getType();
            boolean stash=false;
            String category=null;

            if(m.name().endsWith("_HELMET") || m.name().endsWith("_CHESTPLATE") ||
               m.name().endsWith("_LEGGINGS") || m.name().endsWith("_BOOTS")) {
                // Equipped armor lives outside these main slots, so inventory armor is spare.
                stash=true;
            } else if(m.name().endsWith("_SWORD")) {
                stash=slot!=keepSwordSlot;
            } else if(m==Material.BOW || m==Material.ARROW) {
                stash=true;
            } else if(m==Material.DIAMOND_PICKAXE || m==Material.IRON_PICKAXE ||
                      m==Material.STONE_PICKAXE || m==Material.GOLD_PICKAXE ||
                      m==Material.WOOD_PICKAXE || m==Material.FEATHER) {
                stash=true;
                category="kits";
            } else if(m==Material.ENDER_PEARL) {
                int keep=Math.max(0,8-pearlsKept);
                if(item.getAmount()<=keep) { pearlsKept+=item.getAmount(); continue; }
                if(keep>0) {
                    org.bukkit.inventory.ItemStack excess=item.clone();
                    excess.setAmount(item.getAmount()-keep);
                    item.setAmount(keep);
                    inv.setItem(slot,item);
                    if(putVisibleStorage(f,excess,"pearls")) moved+=excess.getAmount();
                    pearlsKept+=keep;
                    continue;
                }
                stash=true;
            } else if(m==Material.POTION && item.getDurability()==(short)16421) {
                if(healsKept<20) { healsKept++; continue; }
                stash=true;
            } else if(m==Material.COOKED_BEEF) {
                int keep=Math.max(0,32-foodKept);
                if(item.getAmount()<=keep){foodKept+=item.getAmount();continue;}
                if(keep>0){
                    org.bukkit.inventory.ItemStack excess=item.clone();
                    excess.setAmount(item.getAmount()-keep);
                    item.setAmount(keep);
                    inv.setItem(slot,item);
                    if(putVisibleStorage(f,excess,"overflow")) moved+=excess.getAmount();
                    foodKept+=keep;
                    continue;
                }
                stash=true;
            } else if(m==Material.POTION) {
                // Spare speed/fire/utility pots are faction stock after the first few slots.
                stash=true;
            } else if(m==Material.DIAMOND || m==Material.DIAMOND_ORE ||
                      m==Material.IRON_INGOT || m==Material.IRON_ORE ||
                      m==Material.GOLD_INGOT || m==Material.GOLD_ORE ||
                      m==Material.EMERALD || m==Material.OBSIDIAN) {
                stash=true;
                category="valuables";
            } else if(m==Material.COBBLESTONE || m==Material.STONE ||
                      m==Material.DIRT || m==Material.LOG || m==Material.LOG_2 ||
                      m==Material.WOOD) {
                stash=true;
                category="blocks";
            } else if(m==Material.NETHER_STALK || m==Material.SPECKLED_MELON ||
                      m==Material.GLOWSTONE_DUST || m==Material.REDSTONE ||
                      m==Material.SUGAR || m==Material.MAGMA_CREAM ||
                      m==Material.SULPHUR || m==Material.BLAZE_ROD ||
                      m==Material.BLAZE_POWDER || m==Material.GHAST_TEAR) {
                stash=true;
                category="brewing";
            }

            if(!stash) continue;
            org.bukkit.inventory.ItemStack copy=item.clone();
            if(putVisibleStorage(f,copy,category)) {
                moved+=copy.getAmount();
                inv.setItem(slot,null);
            }
        }

        body.updateInventory();
        return "moved="+moved;
    }

    String processClaimedKit(Player body) {
        // Equip what this identity should actually use before touching storage.
        // Then bank the remaining kit value for teammates, and finally fill any
        // role-specific holes from the shared vault.
        String before=gearEmbodiedWorker(body);
        String stashed=stashEmbodiedWorker(body);
        String after=gearEmbodiedWorker(body);
        return "equip={"+before+"} stash={"+stashed+"} refill={"+after+"}";
    }

    String gearEmbodiedWorker(Player body) {
        SimPlayer p=players.get(key(body.getName()));
        if(p==null || p.faction.isEmpty()) return "no-faction";
        SimFaction f=factions.get(key(p.faction));
        if(f==null) return "no-faction";

        org.bukkit.inventory.PlayerInventory inv=body.getInventory();
        normalizePvpInventory(body);
        int armorChanged=0,weaponAdded=0,supplies=0,classItems=0;

        Material[][] armorOptions=armorOptionsFor(p.combatClass);
        String[] categories={"helmets","chestplates","leggings","boots"};
        for(int part=0;part<4;part++) {
            org.bukkit.inventory.ItemStack current=getArmorPiece(inv,part);
            org.bukkit.inventory.ItemStack candidate=takeBestArmorCandidate(inv,f,categories[part],armorOptions[part]);
            if(candidate==null) continue;

            if(current!=null && armorAllowed(current.getType(),armorOptions[part]) &&
               itemCombatValue(current)>=itemCombatValue(candidate)) {
                // Put the unused withdrawal back; a teammate may need it.
                putVisibleStorage(f,candidate,categories[part]);
                continue;
            }

            if(current!=null) bankOrCarry(inv,f,current,categories[part]);
            setArmorPiece(inv,part,candidate);
            armorChanged++;
        }

        // Rogue's mechanic requires a gold sword. Everyone else carries the best
        // sword available; the HOT combat controller may later switch to bow,
        // bard item, pearl, potion, etc. as the situation requires.
        Material requiredSword=p.combatClass==CombatClass.ROGUE?Material.GOLD_SWORD:null;
        org.bukkit.inventory.ItemStack sword=takeBestSword(inv,f,requiredSword);
        if(sword!=null) {
            java.util.Map<Integer,org.bukkit.inventory.ItemStack> overflow=inv.addItem(sword);
            if(overflow.isEmpty()) weaponAdded++;
            else putVisibleStorage(f,sword,"swords");
        }

        if(p.combatClass==CombatClass.ARCHER) {
            if(countInventoryMaterial(inv,Material.BOW)<1) {
                org.bukkit.inventory.ItemStack bow=takeMatchingFromStorage(f,"bows",Material.BOW,(short)-1);
                if(bow!=null) {
                    if(inv.addItem(bow).isEmpty()) classItems++;
                    else putVisibleStorage(f,bow,"bows");
                }
            }
            supplies+=refillMaterial(inv,f,"bows",Material.ARROW,32);
        } else if(p.combatClass==CombatClass.BARD) {
            classItems+=refillSingle(inv,f,"brewing",Material.BLAZE_ROD);
            classItems+=refillSingle(inv,f,"brewing",Material.GHAST_TEAR);
            classItems+=refillSingle(inv,f,"kits",Material.FEATHER);
            classItems+=refillSingle(inv,f,"brewing",Material.MAGMA_CREAM);
            classItems+=refillSingle(inv,f,"brewing",Material.SUGAR);
            classItems+=refillSingle(inv,f,"brewing",Material.BLAZE_POWDER);
        } else if(p.combatClass==CombatClass.MINER) {
            if(!hasPickaxe(inv)) {
                org.bukkit.inventory.ItemStack pick=takeBestPickaxe(f);
                if(pick!=null) {
                    if(inv.addItem(pick).isEmpty()) classItems++;
                    else putVisibleStorage(f,pick,"kits");
                }
            }
        }

        supplies+=refillPotion(inv,f,(short)16421,20);
        supplies+=refillPotion(inv,f,(short)8226,2);
        supplies+=refillMaterial(inv,f,"pearls",Material.ENDER_PEARL,8);
        supplies+=refillMaterial(inv,f,"overflow",Material.COOKED_BEEF,32);

        int hand=bestCarriedSwordSlot(inv,p.combatClass);
        if(hand>=0 && hand<9) inv.setHeldItemSlot(hand);

        body.updateInventory();
        return "class="+p.combatClass.name()+" armor="+armorChanged+
            " weapon="+weaponAdded+" classItems="+classItems+" supplies="+supplies;
    }

    private void normalizePvpInventory(Player body) {
        if(body==null) return;
        org.bukkit.inventory.PlayerInventory inv=body.getInventory();
        for(int slot=0;slot<36;slot++) {
            org.bukkit.inventory.ItemStack item=inv.getItem(slot);
            if(item!=null) capPvpItem(item);
        }
        for(org.bukkit.inventory.ItemStack item:inv.getArmorContents())
            if(item!=null) capPvpItem(item);
        body.updateInventory();
    }

    private void capPvpItem(org.bukkit.inventory.ItemStack item) {
        if(item==null) return;
        String n=item.getType().name();
        if(n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") ||
           n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")) {
            int prot=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL);
            if(prot>2) {
                item.removeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL);
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL,2);
            }
        }
        if(n.endsWith("_SWORD")) {
            int sharp=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DAMAGE_ALL);
            if(sharp>2) {
                item.removeEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL);
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL,2);
            }
            item.removeEnchantment(org.bukkit.enchantments.Enchantment.FIRE_ASPECT);
        }
        if(item.getType()==Material.BOW) {
            int power=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.ARROW_DAMAGE);
            if(power>2) {
                item.removeEnchantment(org.bukkit.enchantments.Enchantment.ARROW_DAMAGE);
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.ARROW_DAMAGE,2);
            }
            item.removeEnchantment(org.bukkit.enchantments.Enchantment.ARROW_FIRE);
        }
    }

    private Material[][] armorOptionsFor(CombatClass type) {
        if(type==CombatClass.BARD) return new Material[][]{
            {Material.GOLD_HELMET},{Material.GOLD_CHESTPLATE},{Material.GOLD_LEGGINGS},{Material.GOLD_BOOTS}};
        if(type==CombatClass.ARCHER) return new Material[][]{
            {Material.LEATHER_HELMET},{Material.LEATHER_CHESTPLATE},{Material.LEATHER_LEGGINGS},{Material.LEATHER_BOOTS}};
        if(type==CombatClass.ROGUE) return new Material[][]{
            {Material.CHAINMAIL_HELMET},{Material.CHAINMAIL_CHESTPLATE},{Material.CHAINMAIL_LEGGINGS},{Material.CHAINMAIL_BOOTS}};
        if(type==CombatClass.MINER) return new Material[][]{
            {Material.IRON_HELMET},{Material.IRON_CHESTPLATE},{Material.IRON_LEGGINGS},{Material.IRON_BOOTS}};
        return new Material[][]{
            {Material.DIAMOND_HELMET,Material.IRON_HELMET,Material.CHAINMAIL_HELMET,Material.GOLD_HELMET,Material.LEATHER_HELMET},
            {Material.DIAMOND_CHESTPLATE,Material.IRON_CHESTPLATE,Material.CHAINMAIL_CHESTPLATE,Material.GOLD_CHESTPLATE,Material.LEATHER_CHESTPLATE},
            {Material.DIAMOND_LEGGINGS,Material.IRON_LEGGINGS,Material.CHAINMAIL_LEGGINGS,Material.GOLD_LEGGINGS,Material.LEATHER_LEGGINGS},
            {Material.DIAMOND_BOOTS,Material.IRON_BOOTS,Material.CHAINMAIL_BOOTS,Material.GOLD_BOOTS,Material.LEATHER_BOOTS}};
    }

    private boolean armorAllowed(Material material,Material[] options) {
        if(material==null) return false;
        for(Material m:options) if(m==material) return true;
        return false;
    }

    private org.bukkit.inventory.ItemStack getArmorPiece(org.bukkit.inventory.PlayerInventory inv,int part) {
        if(part==0) return inv.getHelmet();
        if(part==1) return inv.getChestplate();
        if(part==2) return inv.getLeggings();
        return inv.getBoots();
    }

    private void setArmorPiece(org.bukkit.inventory.PlayerInventory inv,int part,org.bukkit.inventory.ItemStack item) {
        if(part==0) inv.setHelmet(item);
        else if(part==1) inv.setChestplate(item);
        else if(part==2) inv.setLeggings(item);
        else inv.setBoots(item);
    }

    private int itemCombatValue(org.bukkit.inventory.ItemStack item) {
        if(item==null || item.getType()==Material.AIR) return -1;
        int material=materialCombatTier(item.getType())*10000;
        int prot=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL)*500;
        int sharp=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DAMAGE_ALL)*500;
        int power=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.ARROW_DAMAGE)*400;
        int fire=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FIRE_ASPECT)*150;
        int unbreaking=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DURABILITY)*25;
        int durability=0;
        if(item.getType().getMaxDurability()>0)
            durability=Math.max(0,item.getType().getMaxDurability()-item.getDurability());
        return material+prot+sharp+power+fire+unbreaking+durability;
    }

    private int materialCombatTier(Material m) {
        String n=m.name();
        if(n.startsWith("DIAMOND_")) return 5;
        if(n.startsWith("IRON_")) return 4;
        if(n.startsWith("CHAINMAIL_")) return 3;
        if(n.startsWith("GOLD_")) return 2;
        if(n.startsWith("LEATHER_") || n.startsWith("STONE_")) return 1;
        if(n.startsWith("WOOD_")) return 0;
        return 0;
    }

    private org.bukkit.inventory.ItemStack takeBestArmorCandidate(org.bukkit.inventory.PlayerInventory inv,SimFaction f,
                                                                  String category,Material[] allowed) {
        int bestSlot=-1,bestValue=-1;
        for(int i=0;i<36;i++) {
            org.bukkit.inventory.ItemStack item=inv.getItem(i);
            if(item==null || !armorAllowed(item.getType(),allowed)) continue;
            int v=itemCombatValue(item);
            if(v>bestValue){bestValue=v;bestSlot=i;}
        }

        org.bukkit.inventory.ItemStack storageBest=peekBestStorageItem(f,category,allowed,false);
        if(storageBest!=null && itemCombatValue(storageBest)>bestValue)
            return takeExactStorageItem(f,category,storageBest);

        if(bestSlot<0) return null;
        org.bukkit.inventory.ItemStack item=inv.getItem(bestSlot);
        org.bukkit.inventory.ItemStack one=item.clone();
        one.setAmount(1);
        if(item.getAmount()<=1) inv.setItem(bestSlot,null);
        else { item.setAmount(item.getAmount()-1); inv.setItem(bestSlot,item); }
        return one;
    }

    private org.bukkit.inventory.ItemStack peekBestStorageItem(SimFaction f,String category,Material[] allowed,boolean swords) {
        org.bukkit.inventory.Inventory storage=factionStorageInventory(f,category);
        if(storage==null) return null;
        org.bukkit.inventory.ItemStack best=null;
        for(org.bukkit.inventory.ItemStack item:storage.getContents()) {
            if(item==null || item.getType()==Material.AIR) continue;
            if(swords) {
                if(!item.getType().name().endsWith("_SWORD")) continue;
                if(allowed!=null && allowed.length>0 && item.getType()!=allowed[0]) continue;
            } else if(!armorAllowed(item.getType(),allowed)) continue;
            if(best==null || itemCombatValue(item)>itemCombatValue(best)) best=item.clone();
        }
        if(best!=null) best.setAmount(1);
        return best;
    }

    private org.bukkit.inventory.ItemStack takeExactStorageItem(SimFaction f,String category,org.bukkit.inventory.ItemStack wanted) {
        org.bukkit.inventory.Inventory storage=factionStorageInventory(f,category);
        if(storage==null || wanted==null) return null;
        int wantedValue=itemCombatValue(wanted);
        for(int i=0;i<storage.getSize();i++) {
            org.bukkit.inventory.ItemStack item=storage.getItem(i);
            if(item==null || item.getType()!=wanted.getType() || itemCombatValue(item)!=wantedValue) continue;
            org.bukkit.inventory.ItemStack one=item.clone();
            one.setAmount(1);
            if(item.getAmount()<=1) storage.setItem(i,null);
            else { item.setAmount(item.getAmount()-1); storage.setItem(i,item); }
            return one;
        }
        return null;
    }

    private void bankOrCarry(org.bukkit.inventory.PlayerInventory inv,SimFaction f,org.bukkit.inventory.ItemStack item,String category) {
        if(item==null) return;
        if(!putVisibleStorage(f,item,category)) inv.addItem(item);
    }

    private int bestCarriedSwordSlot(org.bukkit.inventory.PlayerInventory inv,CombatClass type) {
        Material required=type==CombatClass.ROGUE?Material.GOLD_SWORD:null;
        int best=-1,bestValue=-1;

        // Prefer hotbar weapons so setting heldItemSlot is visible immediately.
        for(int pass=0;pass<2;pass++) {
            int from=pass==0?0:9;
            int to=pass==0?9:36;
            for(int slot=from;slot<to;slot++) {
                org.bukkit.inventory.ItemStack item=inv.getItem(slot);
                if(item==null || !item.getType().name().endsWith("_SWORD")) continue;
                if(required!=null && item.getType()!=required) continue;
                int v=itemCombatValue(item)+(pass==0?50:0);
                if(v>bestValue){bestValue=v;best=slot;}
            }
        }
        return best;
    }

    private boolean hasUsableSword(org.bukkit.inventory.PlayerInventory inv,Material required) {
        for(org.bukkit.inventory.ItemStack item:inv.getContents()) {
            if(item==null || !item.getType().name().endsWith("_SWORD")) continue;
            if(required==null || item.getType()==required) return true;
        }
        return false;
    }

    private org.bukkit.inventory.ItemStack takeBestSword(org.bukkit.inventory.PlayerInventory inv,SimFaction f,Material required) {
        int bestSlot=-1,bestValue=-1;
        for(int i=0;i<36;i++) {
            org.bukkit.inventory.ItemStack item=inv.getItem(i);
            if(item==null || !item.getType().name().endsWith("_SWORD")) continue;
            if(required!=null && item.getType()!=required) continue;
            int v=itemCombatValue(item);
            if(v>bestValue){bestValue=v;bestSlot=i;}
        }

        Material[] filter=required==null?null:new Material[]{required};
        org.bukkit.inventory.ItemStack storageBest=peekBestStorageItem(f,"swords",filter,true);
        if(required==null) {
            org.bukkit.inventory.Inventory storage=factionStorageInventory(f,"swords");
            if(storage!=null) {
                for(org.bukkit.inventory.ItemStack item:storage.getContents()) {
                    if(item==null || !item.getType().name().endsWith("_SWORD")) continue;
                    if(storageBest==null || itemCombatValue(item)>itemCombatValue(storageBest)) {
                        storageBest=item.clone(); storageBest.setAmount(1);
                    }
                }
            }
        }
        if(storageBest!=null && itemCombatValue(storageBest)>bestValue)
            return takeExactStorageItem(f,"swords",storageBest);

        if(bestSlot<0) return null;
        org.bukkit.inventory.ItemStack item=inv.getItem(bestSlot);
        org.bukkit.inventory.ItemStack one=item.clone(); one.setAmount(1);
        if(item.getAmount()<=1) inv.setItem(bestSlot,null);
        else {item.setAmount(item.getAmount()-1);inv.setItem(bestSlot,item);}
        return one;
    }

    private org.bukkit.inventory.ItemStack takeMatchingFromStorage(SimFaction f,String category,Material material,short durability) {
        org.bukkit.inventory.Inventory storage=factionStorageInventory(f,category);
        if(storage==null) return null;
        for(int i=0;i<storage.getSize();i++) {
            org.bukkit.inventory.ItemStack item=storage.getItem(i);
            if(item==null || item.getType()!=material) continue;
            if(durability>=0 && item.getDurability()!=durability) continue;
            org.bukkit.inventory.ItemStack one=item.clone(); one.setAmount(1);
            if(item.getAmount()<=1) storage.setItem(i,null);
            else {item.setAmount(item.getAmount()-1);storage.setItem(i,item);}
            return one;
        }
        return null;
    }

    private int countInventoryMaterial(org.bukkit.inventory.PlayerInventory inv,Material material) {
        int n=0;
        for(org.bukkit.inventory.ItemStack item:inv.getContents())
            if(item!=null && item.getType()==material) n+=item.getAmount();
        return n;
    }

    private int countInventoryPotion(org.bukkit.inventory.PlayerInventory inv,short durability) {
        int n=0;
        for(org.bukkit.inventory.ItemStack item:inv.getContents())
            if(item!=null && item.getType()==Material.POTION && item.getDurability()==durability) n+=item.getAmount();
        return n;
    }

    private int refillMaterial(org.bukkit.inventory.PlayerInventory inv,SimFaction f,String category,Material material,int target) {
        int have=countInventoryMaterial(inv,material),moved=0;
        while(have<target) {
            org.bukkit.inventory.ItemStack item=takeMatchingFromStorage(f,category,material,(short)-1);
            if(item==null) break;
            if(!inv.addItem(item).isEmpty()) { putVisibleStorage(f,item,category); break; }
            have++; moved++;
        }
        return moved;
    }

    private int refillPotion(org.bukkit.inventory.PlayerInventory inv,SimFaction f,short durability,int target) {
        int have=countInventoryPotion(inv,durability),moved=0;
        while(have<target) {
            org.bukkit.inventory.ItemStack item=takeMatchingFromStorage(f,"pots",Material.POTION,durability);
            if(item==null) break;
            if(!inv.addItem(item).isEmpty()) { putVisibleStorage(f,item,"pots"); break; }
            have++; moved++;
        }
        return moved;
    }

    private int refillSingle(org.bukkit.inventory.PlayerInventory inv,SimFaction f,String category,Material material) {
        if(countInventoryMaterial(inv,material)>0) return 0;
        org.bukkit.inventory.ItemStack item=takeMatchingFromStorage(f,category,material,(short)-1);
        if(item==null) return 0;
        if(inv.addItem(item).isEmpty()) return 1;
        putVisibleStorage(f,item,category);
        return 0;
    }

    private boolean hasPickaxe(org.bukkit.inventory.PlayerInventory inv) {
        for(org.bukkit.inventory.ItemStack item:inv.getContents())
            if(item!=null && item.getType().name().endsWith("_PICKAXE")) return true;
        return false;
    }

    private org.bukkit.inventory.ItemStack takeBestPickaxe(SimFaction f) {
        org.bukkit.inventory.Inventory storage=factionStorageInventory(f,"kits");
        if(storage==null) return null;
        int best=-1,bestValue=-1;
        for(int i=0;i<storage.getSize();i++) {
            org.bukkit.inventory.ItemStack item=storage.getItem(i);
            if(item==null || !item.getType().name().endsWith("_PICKAXE")) continue;
            int v=materialCombatTier(item.getType())*100+item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DIG_SPEED)*20;
            if(v>bestValue){bestValue=v;best=i;}
        }
        if(best<0) return null;
        org.bukkit.inventory.ItemStack item=storage.getItem(best);
        org.bukkit.inventory.ItemStack one=item.clone();one.setAmount(1);
        if(item.getAmount()<=1) storage.setItem(best,null);
        else {item.setAmount(item.getAmount()-1);storage.setItem(best,item);}
        return one;
    }

    String prepareEmbodiedWorkerForCrates(Player body) {
        SimPlayer p=players.get(key(body.getName()));
        if(p==null || p.faction.isEmpty()) return "no-faction";
        SimFaction f=factions.get(key(p.faction));
        if(f==null || !f.storage) return "no-storage";

        org.bukkit.inventory.PlayerInventory inv=body.getInventory();
        int moved=0,keptPearls=0,keptHeals=0,keptFood=0;
        boolean keptSword=false;

        for(int slot=0;slot<36;slot++) {
            org.bukkit.inventory.ItemStack item=inv.getItem(slot);
            if(item==null || item.getType()==Material.AIR) continue;
            Material m=item.getType();

            // The physical key must survive the cleanup trip.
            if(m==Material.TRIPWIRE_HOOK || m==Material.BLAZE_ROD) continue;

            boolean keep=false;
            if(m.name().endsWith("_SWORD") && !keptSword) {
                keptSword=true;
                keep=true;
            } else if(m==Material.ENDER_PEARL && keptPearls<4) {
                int allowed=Math.min(item.getAmount(),4-keptPearls);
                keptPearls+=allowed;
                if(allowed==item.getAmount()) keep=true;
                else {
                    org.bukkit.inventory.ItemStack excess=item.clone();
                    excess.setAmount(item.getAmount()-allowed);
                    item.setAmount(allowed);
                    inv.setItem(slot,item);
                    if(putVisibleStorage(f,excess,"pearls")) moved+=excess.getAmount();
                    keep=true;
                }
            } else if(m==Material.POTION && item.getDurability()==(short)16421 && keptHeals<6) {
                keptHeals++;
                keep=true;
            } else if(m==Material.COOKED_BEEF && keptFood<16) {
                int allowed=Math.min(item.getAmount(),16-keptFood);
                keptFood+=allowed;
                if(allowed==item.getAmount()) keep=true;
                else {
                    org.bukkit.inventory.ItemStack excess=item.clone();
                    excess.setAmount(item.getAmount()-allowed);
                    item.setAmount(allowed);
                    inv.setItem(slot,item);
                    if(putVisibleStorage(f,excess,"overflow")) moved+=excess.getAmount();
                    keep=true;
                }
            }

            if(keep) continue;
            org.bukkit.inventory.ItemStack copy=item.clone();
            if(putVisibleStorage(f,copy,null)) {
                moved+=copy.getAmount();
                inv.setItem(slot,null);
            }
        }

        body.updateInventory();
        int free=0;
        for(int slot=0;slot<36;slot++) {
            org.bukkit.inventory.ItemStack item=inv.getItem(slot);
            if(item==null || item.getType()==Material.AIR) free++;
        }
        return "moved="+moved+" free="+free;
    }

    void ensureSoloBuildMaterials(Player body) {
        SimPlayer p=players.get(key(body.getName()));
        if(p==null || !p.faction.isEmpty()) return;

        int blocks=0;
        for(org.bukkit.inventory.ItemStack item:body.getInventory().getContents()) {
            if(item==null) continue;
            Material m=item.getType();
            if(m==Material.COBBLESTONE || m==Material.WOOD || m==Material.DIRT || m==Material.STONE)
                blocks+=item.getAmount();
        }
        if(blocks>=8 || p.balance<12.0) return;

        p.balance-=12.0;
        body.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.COBBLESTONE,12));
        body.updateInventory();
        save();
    }

    void applyDonorKitClaim(String name,int rankLevel) {
        SimPlayer p=players.get(key(name));
        if(p==null || p.faction.isEmpty() || rankLevel<0) return;
        SimFaction f=factions.get(key(p.faction));
        if(f==null) return;

        // Every donor tier is one capped P2/S2 diamond set. Higher tiers only
        // scale consumables modestly because higher ranks can claim lower kits too.
        int pearls=1,heals=2,speed=0;
        if(rankLevel==1){pearls=2;heals=3;speed=1;}
        else if(rankLevel==2){pearls=3;heals=4;speed=1;}
        else if(rankLevel==3){pearls=4;heals=5;speed=1;}
        else if(rankLevel>=4){pearls=5;heals=6;speed=1;}

        // Legacy field names are retained for save compatibility; their meaning
        // is now capped P2 sets and S2 swords.
        f.p4Sets+=1;
        f.sharp4Swords+=1;
        f.pearls+=pearls;
        f.healPots+=heals;
        f.speedPots+=speed;
        f.firePots=0;

        p.reputation=Math.min(999,p.reputation+1);
        save();
    }

    int workerCandidateCount() {
        int n = 0;
        for (SimPlayer p : players.values()) {
            WorkerTask t = workerTaskFor(p.name);
            if (t.priority > 0) n++;
        }
        return n;
    }

    ChatEvent nextChatEvent() {
        if (players.isEmpty()) return null;
        if (!pendingChat.isEmpty()) return pendingChat.pollFirst();

        if (!sotwRecruitingActive() && rng.nextInt(100) < 11) {
            ChatEvent rivalry = rivalryChatEvent();
            if (rivalry != null) return rivalry;
        }

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

        SimPlayer p = randomOnlinePlayer();
        if (p == null) return null;
        SimFaction f = p.faction.isEmpty() ? null : factions.get(key(p.faction));

        if (f != null) {
            if (f.recoveryMode) return new ChatEvent(p.name, rng.nextBoolean() ? "we are regening dtr" : "staying in base till dtr regens");
            if (f.stage == Stage.SCOUT_CLAIM) return new ChatEvent(p.name, "looking for a spot to claim");
            if (f.stage == Stage.GATHER_STARTER && "miner".equals(p.role)) return new ChatEvent(p.name, "mining for the base rn");
            if (f.stage == Stage.BREWER && !f.brewer) return new ChatEvent(p.name, "buying redstone stuff for an auto brewer");
            if (f.stage == Stage.GEARING && f.p4Sets < Math.min(2, f.members.size())) return new ChatEvent(p.name, "buying prot books msg me");
            if (f.stage == Stage.PVP_READY && rng.nextBoolean()) return new ChatEvent(p.name, "who is at spawn");
        } else {
            String solo=soloActionFor(p);
            if("solo_loot".equals(solo)) {
                String[] x={"any loot at spawn","who just died outside spawn","found pots on the ground lol","im just running around warzone"};
                return new ChatEvent(p.name,x[rng.nextInt(x.length)]);
            }
            if("solo_build".equals(solo)) {
                String[] x={"building something outside spawn","need blocks","making a little spot in warzone","who has dirt lol"};
                return new ChatEvent(p.name,x[rng.nextInt(x.length)]);
            }
            if(rng.nextInt(100)<45) {
                String[] x={"anyone wanna run around","im solo rn","who is at end","might go nether","just chilling"};
                return new ChatEvent(p.name,x[rng.nextInt(x.length)]);
            }
        }

        String[] neutral = {"gg","anyone at spawn","who has pearls","who wants ally","selling stuff msg me","lol","need levels","who is outside","need pots","who has p2"};
        return new ChatEvent(p.name, neutral[rng.nextInt(neutral.length)]);
    }

    void onHumanPublicChat(Player human, String message) {
        String lower = message.toLowerCase(Locale.ENGLISH);
        rememberPublic(human.getName(), message);

        if (!plugin.getConfig().getBoolean("sim-chat.contextual-chat", true)) return;

        // Transaction intent remains authoritative and creates real orders.
        String item = itemFromText(lower);
        if (item != null && (lower.contains("selling") || lower.startsWith("sell ") || lower.contains("wts"))) {
            SimPlayer buyer = findInterestedBuyer(item);
            if (buyer != null) {
                int qty = suggestedTradeQty(item, buyer, true);
                MarketOrder order = buyOrder(buyer,item,qty,fairAiBuyUnit(item));
                activeOrders.put(key(buyer.name), order);
                enqueue(buyer.name, rng.nextBoolean() ? "how much for " + qty + " " + pretty(item) : "ill buy " + qty + " " + pretty(item), true);
                rememberPublic(buyer.name, pendingChat.peekLast() == null ? "" : pendingChat.peekLast().message);
                return;
            }
        } else if (item != null && (lower.contains("buying") || lower.startsWith("buy ") || lower.contains("wtb"))) {
            SimPlayer seller = findSeller(item);
            if (seller != null) {
                int qty = Math.min(suggestedTradeQty(item,seller,false), getStock(seller,item));
                if (qty > 0) {
                    MarketOrder order = sellOrder(seller,item,qty,fairAiSellUnit(item));
                    activeOrders.put(key(seller.name), order);
                    enqueue(seller.name, rng.nextBoolean() ? "i have " + qty + " " + pretty(item) : "msg me i can sell " + qty, true);
                    rememberPublic(seller.name, pendingChat.peekLast() == null ? "" : pendingChat.peekLast().message);
                    return;
                }
            }
        }

        final SimPlayer respondent = chooseContextResponder(human.getName(), lower);
        if (respondent != null) {
            final ContextChatBrain.Snapshot snap = chatSnapshot(human.getName(), respondent, message);
            String prior = lastPublicLineBySpeaker.get(key(respondent.name));
            final String fallback = prior == null ? chatBrain.reply(snap) : chatBrain.followup(snap, prior);
            final String speakerName=human.getName();
            final String rawMessage=message;

            boolean dispatched=aiChat.request("public",speakerName,respondent.name,
                semanticContext(respondent,speakerName),rawMessage,new AiChatBridge.Handler() {
                    public void complete(AiChatBridge.AiReply ai) {
                        String reply=ai!=null?ai.text:fallback;
                        if(reply==null || reply.trim().isEmpty()) return;

                        if(ai!=null) {
                            applyAiRelationship(respondent,speakerName,ai,rawMessage);
                            handleAiSocialAction(respondent,speakerName,ai);
                        } else {
                            maybeFallbackFactionInvite(respondent,speakerName,rawMessage);
                        }

                        enqueue(respondent.name,reply,true);
                        rememberPublic(respondent.name,reply);

                        // A second person can join the same conversation, but semantic
                        // calls stay bounded: the follow-up uses the cheap local brain.
                        if(rng.nextInt(100)<plugin.getConfig().getInt("sim-chat.second-responder-chance-percent",22)) {
                            SimPlayer second=chooseSecondResponder(respondent,rawMessage.toLowerCase(Locale.ENGLISH));
                            if(second!=null) {
                                ContextChatBrain.Snapshot ss=chatSnapshot(speakerName,second,rawMessage);
                                String secondReply=chatBrain.reply(ss);
                                if(secondReply!=null && !secondReply.equalsIgnoreCase(reply)) {
                                    enqueue(second.name,secondReply,true);
                                    rememberPublic(second.name,secondReply);
                                }
                            }
                        }
                        save();
                    }
                });
            if(dispatched) return;

            if (fallback != null && !fallback.trim().isEmpty()) {
                enqueue(respondent.name, fallback, true);
                rememberPublic(respondent.name, fallback);
                return;
            }
        }

        // Legacy intent fallbacks only fire when contextual chat chose silence.
        if (lower.contains("who wants pvp") || lower.contains("1v1") || lower.contains("anyone at spawn")) {
            SimPlayer fighter = findReadyFighter();
            if (fighter != null) enqueue(fighter.name, fighter.skill >= 80 ? "im down" : "give me a min", true);
        }

    }

    private boolean isConfiguredOwner(String name) {
        String owner=plugin.getConfig().getString("owner.name","");
        return name!=null && !owner.isEmpty() && owner.equalsIgnoreCase(name);
    }

    private int clampAffinity(int n) {
        return Math.max(-100,Math.min(100,n));
    }

    private String semanticContext(SimPlayer p,String speaker) {
        SocialEdge rel=relationship(p.name,speaker,true);
        StringBuilder b=new StringBuilder();
        b.append("identity=").append(p.name)
         .append("; faction=").append(p.faction==null||p.faction.isEmpty()?"solo":p.faction)
         .append("; role=").append(p.role)
         .append("; factionTitle=").append(p.factionTitle)
         .append("; job=").append(p.preferredJob)
         .append("; class=").append(p.combatClass.name())
         .append("; donor=").append(donorName(p.donorLevel))
         .append("; lifetimeStoreSpendUsd=").append((int)Math.round(p.donationUsd))
         .append("; donorInfluence=").append(donorInfluence(p))
         .append("; staff=").append(p.staffRole==null||p.staffRole.isEmpty()?"none":p.staffRole)
         .append("; ownerAffinity=").append(p.ownerAffinity)
         .append("; publicNamePrestige=").append(namePrestigeTier(p.name))
         .append("; knownCreator=").append(plugin.isCreatorIdentity(p.name))
         .append("; kills=").append(p.kills)
         .append("; deaths=").append(p.deaths)
         .append("; aggression=").append(p.aggression)
         .append("; sociability=").append(p.sociability)
         .append("; loyalty=").append(p.loyalty)
         .append("; reputation=").append(p.reputation)
         .append("; leaderStyle=").append("leader".equals(p.role)?leaderStyle(p):"not-leader")
         .append("; leaderQualityPublic=").append("leader".equals(p.role)?publicLeaderReputation(p):"n/a")
         .append("; goal=").append(p.currentGoal)
         .append("; speaker=").append(speaker)
         .append("; speakerIsOwner=").append(isConfiguredOwner(speaker))
         .append("; relationshipAffinity=").append(rel.affinity)
         .append("; relationshipTrust=").append(rel.trust)
         .append("; relationshipRespect=").append(rel.respect)
         .append("; relationshipGrudge=").append(rel.grudge);

        if(p.faction!=null && !p.faction.isEmpty()) {
            SimFaction f=factions.get(key(p.faction));
            if(f!=null) {
                b.append("; responderIsFactionLeader=").append(f.leader!=null && f.leader.equalsIgnoreCase(p.name))
                 .append("; responderCanVouch=true")
                 .append("; factionLeader=").append(f.leader)
                 .append("; factionHasSpace=").append(f.members.size()<MAX_FACTION_MEMBERS)
                 .append("; speakerAlreadyFactioned=").append(plugin.humanAlreadyFactioned(speaker))
                 .append("; factionMembers=").append(f.members.size()).append("/").append(MAX_FACTION_MEMBERS)
                 .append("; factionStage=").append(f.stage.name())
                 .append("; recovery=").append(f.recoveryMode)
                 .append("; zone=").append(warzoneForFaction(f))
                 .append("; dtr=").append(plugin.factionDtr(f.name))
                 .append("/").append(plugin.factionMaxDtr(f.name));
            }
        }

        String historyContext=historyContextFor(p);
        if(!historyContext.isEmpty()) b.append("; rememberedServerHistory=[").append(historyContext.replace(';',',')).append("]");

        if(!rel.memories.isEmpty()) {
            b.append("; durableMemories=");
            int memoryTake=Math.max(2,Math.min(16,plugin.getConfig().getInt("memory.chat-relationship-memories",10)));
            int skipMem=Math.max(0,rel.memories.size()-memoryTake),mi=0;
            for(String memory:rel.memories) {
                if(mi++<skipMem) continue;
                b.append("[").append(memory.replace(';',',')).append("]");
            }
        }
        if(!recentKiller.isEmpty()) b.append("; recentKill=").append(recentKiller).append(">").append(recentVictim);
        if(!recentPublicMessages.isEmpty()) {
            b.append("; recentChat=");
            int skip=Math.max(0,recentPublicMessages.size()-5),i=0;
            Iterator<String> it=recentPublicMessages.iterator();
            while(it.hasNext()) {
                String line=it.next();
                if(i++<skip) continue;
                if(b.length()>1800) break;
                b.append("[").append(line.replace(';',',')).append("]");
            }
        }
        return b.toString();
    }

    boolean requestPrivateAi(final Player human,final String simName,final String text) {
        final SimPlayer sim=players.get(key(simName));
        if(sim==null || text==null || text.trim().isEmpty()) return false;

        String lower=text.toLowerCase(Locale.ENGLISH);
        if(isTradeIntent(lower)) return false;

        final String fallback=casualReply(sim,lower);
        final String humanName=human.getName();
        lastReplyTarget.put(key(humanName),sim.name);

        return aiChat.request("private",humanName,sim.name,semanticContext(sim,humanName),text,new AiChatBridge.Handler() {
            public void complete(AiChatBridge.AiReply ai) {
                if(!human.isOnline()) return;
                String reply=ai!=null?ai.text:fallback;
                if(reply==null||reply.trim().isEmpty()) return;
                if(ai!=null) {
                    applyAiRelationship(sim,humanName,ai,text);
                    handleAiSocialAction(sim,humanName,ai);
                } else {
                    maybeFallbackFactionInvite(sim,humanName,text);
                }
                plugin.sendSimulatedPrivate(human,sim.name,reply);
                save();
            }
        });
    }

    private String memoryEncode(String s) {
        if(s==null) s="";
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String memoryDecode(String s) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(s),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception ignored) { return ""; }
    }

    private void writeMemoryLine(BufferedWriter w,HistoryEvent e) throws IOException {
        StringBuilder people=new StringBuilder();
        for(String p:e.people) {
            if(people.length()>0) people.append(',');
            people.append(p.replace(',','_'));
        }
        w.write(Long.toString(e.at));w.write("\t");
        w.write(Integer.toString(e.importance));w.write("\t");
        w.write(memoryEncode(e.type));w.write("\t");
        w.write(memoryEncode(e.faction));w.write("\t");
        w.write(memoryEncode(people.toString()));w.write("\t");
        w.write(memoryEncode(e.summary));
        w.newLine();
    }

    private File coldArchiveDir() {
        String configured=plugin.getConfig().getString("memory.cold-archive-directory","");
        File dir;
        if(configured!=null && !configured.trim().isEmpty()) dir=new File(configured.trim());
        else dir=new File(plugin.getDataFolder(),"memory-archive");
        if(!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void archiveColdShard(File source) throws IOException {
        if(source==null || !source.isFile() || source.length()<=0L) return;
        File dir=coldArchiveDir();
        String name="memory-events-"+System.currentTimeMillis()+".log.gz";
        File out=new File(dir,name);
        InputStream in=new FileInputStream(source);
        OutputStream gz=new GZIPOutputStream(new FileOutputStream(out));
        try {
            byte[] buf=new byte[32768];
            int n;
            while((n=in.read(buf))>0) gz.write(buf,0,n);
        } finally {
            try{in.close();}catch(Exception ignored){}
            try{gz.close();}catch(Exception ignored){}
        }
        pruneColdShards(dir);
        plugin.getLogger().info("Archived cold actor memory shard "+out.getAbsolutePath()+
            " bytes="+out.length());
    }

    private void pruneColdShards(File dir) {
        File[] files=dir.listFiles(new FilenameFilter(){
            public boolean accept(File d,String name){
                return name.startsWith("memory-events-") && name.endsWith(".log.gz");
            }
        });
        if(files==null) return;
        Arrays.sort(files,new Comparator<File>(){
            public int compare(File a,File b){return Long.compare(a.lastModified(),b.lastModified());}
        });
        int max=Math.max(4,Math.min(256,plugin.getConfig().getInt("memory.cold-max-shards",48)));
        for(int i=0;i<files.length-max;i++) files[i].delete();
    }

    private void compactMemoryArchive() {
        if(!memoryFile.getParentFile().exists()) memoryFile.getParentFile().mkdirs();
        File tmp=new File(memoryFile.getParentFile(),"memory-events.compact.tmp");
        File bak=new File(memoryFile.getParentFile(),"memory-events.compact.bak");
        try {
            BufferedWriter w=new BufferedWriter(new FileWriter(tmp,false));
            for(HistoryEvent h:communityHistory) writeMemoryLine(w,h);
            w.close();

            if(bak.exists()) bak.delete();
            if(memoryFile.exists()) {
                archiveColdShard(memoryFile);
                if(!memoryFile.renameTo(bak))
                    throw new IOException("could not stage old archive");
            }
            if(!tmp.renameTo(memoryFile)) {
                if(bak.exists()) bak.renameTo(memoryFile);
                throw new IOException("could not install compact archive");
            }
            if(bak.exists()) bak.delete();
            plugin.getLogger().info("Compacted actor memory archive to "+communityHistory.size()+" salient/recent events.");
        } catch(IOException ex) {
            if(tmp.exists()) tmp.delete();
            plugin.getLogger().warning("Could not compact memory archive: "+ex.getMessage());
        }
    }

    private void appendMemoryArchive(HistoryEvent e) {
        if(e==null) return;
        try {
            if(!memoryFile.getParentFile().exists()) memoryFile.getParentFile().mkdirs();
            // recordHistory inserts e into the bounded in-memory deque first, so
            // compaction already contains this event and must not append it twice.
            if(memoryFile.exists() && memoryFile.length()>archiveMaxBytes()) {
                compactMemoryArchive();
                return;
            }
            BufferedWriter w=new BufferedWriter(new FileWriter(memoryFile,true));
            writeMemoryLine(w,e);
            w.close();
        } catch(IOException ex) {
            plugin.getLogger().warning("Could not append memory archive: "+ex.getMessage());
        }
    }

    private void loadMemoryArchive() {
        if(!memoryFile.exists() || memoryFile.length()<=0L) {
            // Bootstrap the long-term archive from the recent YAML history once.
            for(HistoryEvent e:new ArrayList<HistoryEvent>(communityHistory)) appendMemoryArchive(e);
            return;
        }
        Deque<HistoryEvent> loaded=new ArrayDeque<HistoryEvent>();
        try {
            BufferedReader r=new BufferedReader(new FileReader(memoryFile));
            String line;
            while((line=r.readLine())!=null) {
                String[] p=line.split("\\t",-1);
                if(p.length<6) continue;
                HistoryEvent e=new HistoryEvent();
                try { e.at=Long.parseLong(p[0]); } catch(Exception ignored) { continue; }
                try { e.importance=Integer.parseInt(p[1]); } catch(Exception ignored) { e.importance=1; }
                e.type=memoryDecode(p[2]);
                e.faction=memoryDecode(p[3]);
                String people=memoryDecode(p[4]);
                if(!people.isEmpty()) for(String person:people.split(",")) if(!person.isEmpty()) e.people.add(person);
                e.summary=memoryDecode(p[5]);
                if(e.summary.isEmpty()) continue;
                loaded.addLast(e);
                while(loaded.size()>historyMemoryLimit()) loaded.removeFirst();
            }
            r.close();
        } catch(IOException ex) {
            plugin.getLogger().warning("Could not load memory archive: "+ex.getMessage());
            return;
        }
        if(!loaded.isEmpty()) {
            communityHistory.clear();
            communityHistory.addAll(loaded);
        }
    }

    private void recordHistory(String type,int importance,String summary,String faction,String... people) {
        if(summary==null || summary.trim().isEmpty()) return;
        HistoryEvent e=new HistoryEvent();
        e.at=System.currentTimeMillis();
        e.importance=Math.max(1,Math.min(10,importance));
        e.type=type==null?"EVENT":type;
        e.summary=summary.replace('\n',' ').replace('\r',' ').trim();
        if(e.summary.length()>180) e.summary=e.summary.substring(0,180).trim();
        e.faction=faction==null?"":faction;
        if(people!=null) {
            for(String person:people) {
                if(person==null || person.trim().isEmpty()) continue;
                if(!e.people.contains(person)) e.people.add(person);
            }
        }

        // Low-value repeated ambient events should reinforce context, not grow
        // storage. High-importance kills/raids/promotions are never suppressed.
        if(e.importance<=4) {
            String fp=(e.type+"|"+e.faction+"|"+e.summary).toLowerCase(Locale.ENGLISH);
            Long last=recentHistoryFingerprints.get(fp);
            if(last!=null && e.at-last<historyDuplicateWindowMillis()) return;
            recentHistoryFingerprints.put(fp,e.at);
            while(recentHistoryFingerprints.size()>1200) {
                Iterator<String> it=recentHistoryFingerprints.keySet().iterator();
                if(it.hasNext()){it.next();it.remove();} else break;
            }
        }

        communityHistory.addLast(e);
        while(communityHistory.size()>historyMemoryLimit()) communityHistory.removeFirst();
        appendMemoryArchive(e);
    }

    private List<HistoryEvent> relevantHistory(String person,String faction,int limit) {
        List<HistoryEvent> out=new ArrayList<HistoryEvent>();
        Iterator<HistoryEvent> it=communityHistory.descendingIterator();
        while(it.hasNext() && out.size()<limit) {
            HistoryEvent e=it.next();
            boolean relevant=false;
            if(faction!=null && !faction.isEmpty() && faction.equalsIgnoreCase(e.faction)) relevant=true;
            if(!relevant && person!=null && !person.isEmpty()) {
                for(String p:e.people) if(person.equalsIgnoreCase(p)) { relevant=true; break; }
            }
            if(relevant || (e.importance>=9 && (e.faction==null || e.faction.isEmpty()))) out.add(e);
        }
        return out;
    }

    List<String> historyLines(String subject,int limit) {
        String faction="";
        String person=subject==null?"":subject;
        SimFaction f=factions.get(key(subject));
        if(f!=null) { faction=f.name; person=""; }
        else {
            SimPlayer p=players.get(key(subject));
            if(p!=null) faction=p.faction;
        }

        List<String> lines=new ArrayList<String>();
        for(HistoryEvent e:relevantHistory(person,faction,Math.max(1,Math.min(20,limit)))) {
            lines.add("["+e.type+"] "+e.summary);
        }
        return lines;
    }

    private String historyContextFor(SimPlayer p) {
        String faction=p==null?"":p.faction;
        int max=Math.max(3,Math.min(24,plugin.getConfig().getInt("memory.chat-history-events",12)));
        List<HistoryEvent> xs=relevantHistory(p==null?"":p.name,faction,max);
        if(xs.isEmpty()) return "";
        StringBuilder b=new StringBuilder();
        for(HistoryEvent e:xs) {
            if(b.length()>0) b.append(" | ");
            b.append(e.summary);
            if(b.length()>1400) break;
        }
        return b.toString();
    }

    private String socialKey(String from,String to) {
        return key(from)+"__to__"+key(to);
    }

    private SocialEdge relationship(String from,String to,boolean create) {
        if(from==null || to==null || from.trim().isEmpty() || to.trim().isEmpty()) return neutralEdge(from,to);
        String k=socialKey(from,to);
        SocialEdge e=socialEdges.get(k);
        if(e!=null || !create) return e;

        e=new SocialEdge();
        e.from=from;
        e.to=to;
        int h=Math.abs((key(from)+"|"+key(to)).hashCode());
        e.affinity=(h%17)-8;
        e.trust=42+(h%17);
        e.respect=42+((h/17)%17);
        e.grudge=0;
        SimPlayer p=players.get(key(from));
        if(p!=null && isConfiguredOwner(to)) e.affinity=clampAffinity(p.ownerAffinity);
        socialEdges.put(k,e);
        return e;
    }

    private SocialEdge neutralEdge(String from,String to) {
        SocialEdge e=new SocialEdge();
        e.from=from==null?"":from;
        e.to=to==null?"":to;
        e.affinity=0;e.trust=50;e.respect=50;e.grudge=0;
        return e;
    }

    private int clampSocial(int n) {
        return Math.max(0,Math.min(100,n));
    }

    private int relationshipMemoryLimit() {
        return Math.max(8,Math.min(64,plugin.getConfig().getInt("memory.relationship-max",32)));
    }

    private int historyMemoryLimit() {
        return Math.max(250,Math.min(10000,plugin.getConfig().getInt("memory.history-max-events",2500)));
    }

    private long historyDuplicateWindowMillis() {
        return Math.max(30L,plugin.getConfig().getLong("memory.duplicate-window-seconds",900L))*1000L;
    }

    private long archiveMaxBytes() {
        return Math.max(1024L*1024L,plugin.getConfig().getLong("memory.archive-max-bytes",8L*1024L*1024L));
    }

    private void rememberRelationship(SocialEdge e,String memory) {
        if(e==null || memory==null) return;
        String m=memory.replace('\n',' ').replace('\r',' ').replace(';',',').trim();
        if(m.isEmpty()) return;
        if(m.length()>140) m=m.substring(0,140).trim();

        // Reinforcement beats duplication: a repeated durable fact becomes the
        // newest memory instead of consuming another slot forever.
        Iterator<String> it=e.memories.iterator();
        while(it.hasNext()) {
            if(it.next().equalsIgnoreCase(m)) { it.remove(); break; }
        }
        e.memories.addLast(m);
        while(e.memories.size()>relationshipMemoryLimit()) e.memories.removeFirst();
        e.lastInteraction=System.currentTimeMillis();
    }

    private void applyAiRelationship(SimPlayer responder,String speaker,AiChatBridge.AiReply ai,String message) {
        if(responder==null || ai==null) return;
        SocialEdge e=relationship(responder.name,speaker,true);
        e.affinity=clampAffinity(e.affinity+ai.affinityDelta);
        e.trust=clampSocial(e.trust+ai.trustDelta);
        e.respect=clampSocial(e.respect+ai.respectDelta);
        if(ai.affinityDelta<0 || ai.trustDelta<0) e.grudge=clampSocial(e.grudge+Math.abs(ai.affinityDelta)+Math.abs(ai.trustDelta));
        else if(ai.affinityDelta>0 || ai.trustDelta>0) e.grudge=Math.max(0,e.grudge-1);
        e.lastInteraction=System.currentTimeMillis();

        if(ai.memory!=null && !ai.memory.isEmpty()) rememberRelationship(e,ai.memory);

        if(isConfiguredOwner(speaker)) responder.ownerAffinity=e.affinity;

        // Mirror a weaker impression in the opposite direction for simulated
        // speakers. Human feelings are never fabricated.
        SimPlayer speakerSim=players.get(key(speaker));
        if(speakerSim!=null) {
            SocialEdge reverse=relationship(speaker,responder.name,true);
            reverse.respect=clampSocial(reverse.respect+Integer.signum(ai.respectDelta));
            reverse.lastInteraction=System.currentTimeMillis();
        }
    }

    private boolean looksFactionJoinRequest(String message) {
        String m=message==null?"":message.toLowerCase(Locale.ENGLISH);
        return (m.contains("join") || m.contains("inv") || m.contains("invite")) &&
            (m.contains("fac") || m.contains("faction") || m.contains("team") || m.contains("you guys") || m.contains("yall"));
    }

    private int recruitInfluenceForSpeaker(SimFaction f,SimPlayer responder,String speaker) {
        SocialEdge responderRel=relationship(responder.name,speaker,true);
        SimPlayer leader=players.get(key(f.leader));
        SocialEdge leaderRel=leader==null?null:relationship(leader.name,speaker,true);

        int score=responderRel.affinity/2+(responderRel.trust-50)/2+(responderRel.respect-50)/3;
        if(leaderRel!=null) score+=leaderRel.affinity/3+(leaderRel.trust-50)/4;

        int donor=plugin.publicRankLevel(speaker);
        if(donor==1) score+=10;
        else if(donor==2) score+=28;
        else if(donor==3) score+=55;
        else if(donor>=4) score+=90;

        if(isConfiguredOwner(speaker)) score+=110;
        if(plugin.isCreatorIdentity(speaker)) score+=50;
        return score;
    }

    private boolean mayInviteSpeaker(SimPlayer responder,String speaker) {
        if(responder==null || responder.faction==null || responder.faction.isEmpty()) return false;
        SimFaction f=factions.get(key(responder.faction));
        if(f==null || f.leader==null || f.leader.isEmpty()) return false;
        if(plugin.humanAlreadyFactioned(speaker)) return false;

        SocialEdge rel=relationship(responder.name,speaker,true);
        if(rel.affinity<=-55 || rel.grudge>=75) return false;

        // A normal member can vouch to the leader. Owners are influential, but
        // a player who genuinely dislikes/distrusts the owner still does not
        // automatically help them.
        if(isConfiguredOwner(speaker) && rel.affinity<0 && rel.trust<45) return false;
        if(!isConfiguredOwner(speaker) && rel.affinity<5 && rel.trust<48 &&
           plugin.publicRankLevel(speaker)<2 && !plugin.isCreatorIdentity(speaker)) return false;

        if(f.members.size()<MAX_FACTION_MEMBERS) return true;
        return replaceableMember(f,recruitInfluenceForSpeaker(f,responder,speaker))!=null;
    }

    private boolean executeFactionInvite(SimPlayer responder,String speaker) {
        if(!mayInviteSpeaker(responder,speaker)) return false;
        SimFaction f=factions.get(key(responder.faction));
        if(f==null) return false;

        if(f.members.size()>=MAX_FACTION_MEMBERS) {
            SimPlayer kicked=replaceableMember(f,recruitInfluenceForSpeaker(f,responder,speaker));
            if(kicked==null || !plugin.removeSimFactionMemberAuthority(f.name,kicked.name)) return false;
            f.members.remove(kicked.name);
            recordFactionKick(f,kicked,speaker);
            enqueue(f.leader,"made room for "+speaker,false);
        }

        if(!plugin.inviteHumanToSimFaction(f.name,f.leader,speaker)) return false;

        SocialEdge e=relationship(responder.name,speaker,true);
        e.affinity=clampAffinity(e.affinity+3);
        e.trust=clampSocial(e.trust+1);
        rememberRelationship(e,"vouched for "+speaker+" to join "+f.name);

        SimPlayer leader=players.get(key(f.leader));
        if(leader!=null) {
            SocialEdge leaderEdge=relationship(leader.name,speaker,true);
            rememberRelationship(leaderEdge,responder.name+" vouched for "+speaker+" to join "+f.name);
        }

        if(!f.leader.equalsIgnoreCase(responder.name)) {
            enqueue(responder.name,"leader sent you the inv",true);
        }
        return true;
    }

    private final Map<String,String> pendingHumanTryoutFaction=new HashMap<String,String>();

    private void handleAiSocialAction(SimPlayer responder,String speaker,AiChatBridge.AiReply ai) {
        if(responder==null || ai==null) return;
        if("INVITE_FACTION".equals(ai.action)) {
            executeFactionInvite(responder,speaker);
            return;
        }
        if("DUEL_TRYOUT".equals(ai.action) && responder.faction!=null && !responder.faction.isEmpty()) {
            SimFaction f=factions.get(key(responder.faction));
            if(f==null) return;
            SimPlayer leader=players.get(key(f.leader));
            if(leader==null) return;
            pendingHumanTryoutFaction.put(key(speaker),f.name);
            plugin.offerSimulatedDuel(leader.name,speaker,"wants a tryout for "+f.name);
            rememberRelationship(relationship(leader.name,speaker,true),"asked "+speaker+" to duel for a spot in "+f.name);
        }
    }

    private void maybeFallbackFactionInvite(SimPlayer responder,String speaker,String message) {
        if(!looksFactionJoinRequest(message) || !mayInviteSpeaker(responder,speaker)) return;
        SocialEdge e=relationship(responder.name,speaker,true);
        int threshold=isConfiguredOwner(speaker)?0:8;
        if(e.affinity<threshold && e.trust<48 &&
           plugin.publicRankLevel(speaker)<2 && !plugin.isCreatorIdentity(speaker)) return;
        executeFactionInvite(responder,speaker);
    }

    private boolean isTradeIntent(String lower) {
        return lower.contains("buy") || lower.contains("sell") || lower.contains("price") ||
            lower.contains("how much") || lower.contains("$") || lower.contains("deal") ||
            lower.contains("pay ") || lower.contains("offer");
    }

    private void rememberPublic(String speaker, String message) {
        if (speaker == null || message == null || message.trim().isEmpty()) return;
        recentPublicSpeakers.addLast(speaker);
        recentPublicMessages.addLast(message);
        lastPublicLineBySpeaker.put(key(speaker), message);
        int maxMemory = Math.max(4, Math.min(40, plugin.getConfig().getInt("sim-chat.max-conversation-memory",12)));
        while (recentPublicSpeakers.size() > maxMemory) recentPublicSpeakers.removeFirst();
        while (recentPublicMessages.size() > maxMemory) recentPublicMessages.removeFirst();
    }

    private SimPlayer chooseContextResponder(String humanName, String lower) {
        // Direct name mention wins.
        for (SimPlayer p : players.values()) {
            if (lower.contains(p.name.toLowerCase(Locale.ENGLISH)) && (p.logicalOnline || plugin.isCreatorIdentity(p.name))) return p;
        }

        // Faction mention should pull a member of that faction.
        for (SimFaction f : factions.values()) {
            if (lower.contains(f.name.toLowerCase(Locale.ENGLISH))) {
                SimPlayer leader = players.get(key(f.leader));
                if (leader != null) return leader;
                for (String member : f.members) {
                    SimPlayer p = players.get(key(member));
                    if (p != null) return p;
                }
            }
        }

        List<SimPlayer> candidates = new ArrayList<SimPlayer>();
        for (SimPlayer p : players.values()) {
            if (p.name.equalsIgnoreCase(humanName) || !p.logicalOnline) continue;
            if (lower.contains("faction") || lower.contains("lff") || lower.contains("recruit")) {
                if (!p.faction.isEmpty()) candidates.add(p);
            } else if (lower.contains("pvp") || lower.contains("fight") || lower.contains("1v1") ) {
                if (shouldSeekPvp(p.name) || p.skill >= 70) candidates.add(p);
            } else if (lower.contains("farm") || lower.contains("money") || lower.contains("cane")) {
                if ("farmer".equals(p.preferredJob)) candidates.add(p);
            } else if (lower.contains("mine") || lower.contains("iron") || lower.contains("diamond")) {
                if ("miner".equals(p.preferredJob)) candidates.add(p);
            } else if (lower.contains("brew") || lower.contains("pots")) {
                if ("brewer".equals(p.preferredJob)) candidates.add(p);
            } else if (lower.contains("dtr") || lower.contains("raid")) {
                if (!p.faction.isEmpty()) {
                    SimFaction f = factions.get(key(p.faction));
                    if (f != null && (f.recoveryMode || plugin.factionDtr(f.name) <= 2.0)) candidates.add(p);
                }
            }
        }

        if (candidates.isEmpty()) {
            if (!isConversationWorthy(lower)) return null;
            for(SimPlayer p:players.values()) if(p.logicalOnline) candidates.add(p);
        }

        if(candidates.isEmpty()) return null;

        // Creators are community magnets. They are more likely to be pulled
        // into public threads without monopolizing every conversation.
        List<SimPlayer> weighted=new ArrayList<SimPlayer>();
        for(SimPlayer p:candidates) {
            weighted.add(p);
            if(plugin.isCreatorIdentity(p.name)) {
                weighted.add(p);
                weighted.add(p);
            } else if(namePrestigeTier(p.name)>=2) {
                weighted.add(p);
            }
        }
        return weighted.get(rng.nextInt(weighted.size()));
    }

    private SimPlayer chooseSecondResponder(SimPlayer first, String lower) {
        if (first == null) return null;
        List<SimPlayer> xs = new ArrayList<SimPlayer>();

        if (!first.faction.isEmpty()) {
            SimFaction f = factions.get(key(first.faction));
            if (f != null) {
                for (String member : f.members) {
                    SimPlayer p = players.get(key(member));
                    if (p != null && !p.name.equalsIgnoreCase(first.name)) xs.add(p);
                }
            }
        }

        if (xs.isEmpty() && !first.faction.isEmpty()) {
            for (SimPlayer p : players.values()) {
                if (p.name.equalsIgnoreCase(first.name) || p.faction.isEmpty()) continue;
                if (!p.faction.equalsIgnoreCase(first.faction)) xs.add(p);
            }
        }

        if (xs.isEmpty()) return null;
        return xs.get(rng.nextInt(xs.size()));
    }

    private boolean isConversationWorthy(String lower) {
        if (lower == null || lower.trim().isEmpty()) return false;
        return lower.endsWith("?") || lower.contains("who ") || lower.contains("what ") ||
            lower.contains("where ") || lower.contains("when ") || lower.contains("how ") ||
            lower.contains("gg") || lower.contains("lol") || lower.contains("bruh") ||
            lower.contains("trash") || lower.contains("nice") || lower.contains("base") ||
            lower.contains("faction") || lower.contains("koth") || lower.contains("dtr");
    }

    private ContextChatBrain.Snapshot chatSnapshot(String speaker, SimPlayer responder, String message) {
        ContextChatBrain.Snapshot s = new ContextChatBrain.Snapshot();
        s.speaker = speaker;
        s.message = message;
        s.responder = responder.name;
        s.responderFaction = responder.faction == null ? "" : responder.faction;
        s.responderRole = responder.role;
        s.responderJob = responder.preferredJob;
        s.responderClass = responder.combatClass.name();
        s.responderSkill = responder.skill;
        s.responderAggression = responder.aggression;
        s.responderBargaining = responder.bargaining;
        s.responderCreator = plugin.isCreatorIdentity(responder.name);
        s.creatorMentioned = mentionsCreator(message);
        s.recentKiller = recentKiller;
        s.recentVictim = recentVictim;

        SocialEdge edge=relationship(responder.name,speaker,true);
        s.affinity=edge.affinity;
        s.trust=edge.trust;
        s.respect=edge.respect;
        s.grudge=edge.grudge;
        s.rememberedFact=memoryHintFor(responder,speaker);
        s.hasHistory=!s.rememberedFact.isEmpty() || edge.lastInteraction>0L;

        SimPlayer speakerSim = players.get(key(speaker));
        if (speakerSim != null) s.speakerFaction = speakerSim.faction == null ? "" : speakerSim.faction;

        if (!responder.faction.isEmpty()) {
            SimFaction f = factions.get(key(responder.faction));
            if (f != null) {
                s.factionStage = f.stage.name();
                s.factionNeed = factionNeedText(f);
                s.recovery = f.recoveryMode;
                s.raidable = plugin.factionRaidable(f.name);
                s.dtr = plugin.factionDtr(f.name);
                s.maxDtr = plugin.factionMaxDtr(f.name);
                s.pvpReady = f.stage == Stage.PVP_READY && !f.recoveryMode && !s.raidable;

                String rival = strongestRival(f.name);
                s.rivalFaction = rival;
                s.rivalry = rival.isEmpty() ? 0 : rivalryScore(f.name,rival);
            }
        }

        return s;
    }

    private String memoryHintFor(SimPlayer responder,String speaker) {
        if(responder==null) return "";
        String faction=responder.faction==null?"":responder.faction;
        Iterator<HistoryEvent> it=communityHistory.descendingIterator();
        int scanned=0;
        while(it.hasNext() && scanned++<300) {
            HistoryEvent e=it.next();
            boolean responderIn=false,speakerIn=false;
            for(String p:e.people) {
                if(p.equalsIgnoreCase(responder.name)) responderIn=true;
                if(speaker!=null && p.equalsIgnoreCase(speaker)) speakerIn=true;
            }
            boolean factionEvent=!faction.isEmpty() && faction.equalsIgnoreCase(e.faction);
            if((responderIn && speakerIn) || (speakerIn && factionEvent) ||
               (responderIn && e.importance>=6)) return e.summary;
        }
        SocialEdge edge=relationship(responder.name,speaker,false);
        if(edge!=null && !edge.memories.isEmpty()) return edge.memories.peekLast();
        return "";
    }

    private String factionNeedText(SimFaction f) {
        List<String> needs = new ArrayList<String>();
        if (classCount(f, CombatClass.BARD) == 0) needs.add("bard");
        if (classCount(f, CombatClass.ARCHER) == 0) needs.add("archer");
        if (jobCount(f, "miner") == 0) needs.add("miner");
        if (jobCount(f, "brewer") == 0) needs.add("brewer");
        if (jobCount(f, "builder") == 0) needs.add("builder");
        if (needs.isEmpty()) return "";
        return joinWords(needs,2);
    }

    private String strongestRival(String faction) {
        String best = "";
        int bestScore = 0;
        for (SimFaction other : factions.values()) {
            if (other.name.equalsIgnoreCase(faction)) continue;
            int score = rivalryScore(faction,other.name);
            if (score > bestScore) {
                bestScore = score;
                best = other.name;
            }
        }
        return best;
    }

    private int rivalryScore(String a, String b) {
        Integer n = rivalries.get(rivalryKey(a,b));
        return n == null ? 0 : n;
    }

    private boolean mentionsCreator(String text) {
        String m = text == null ? "" : text.toLowerCase(Locale.ENGLISH);
        for (String name : plugin.getConfig().getStringList("creator-tag.creators")) {
            if (m.contains(name.toLowerCase(Locale.ENGLISH))) return true;
        }
        return false;
    }

    void noteCombatLoot(String name,String itemName) {
        SimPlayer p=players.get(key(name));
        if(p==null || visibleFight==null) return;
        CombatAssignment ca=visibleFight.assignments.get(key(name));
        if(ca==null) return;

        String item=itemName==null?"loot":itemName.toLowerCase(Locale.ENGLISH);
        String category;
        if(item.contains("potion")) category="potions";
        else if(item.contains("pearl")) category="pearls";
        else if(item.contains("helmet")||item.contains("chestplate")||item.contains("leggings")||item.contains("boots")) category="armor";
        else if(item.contains("sword")||item.contains("bow")) category="weapons";
        else category="supplies";

        String once=key(ca.fightId)+"|"+key(name)+"|"+category;
        if(!combatLootMemoryOnce.add(once)) return;
        while(combatLootMemoryOnce.size()>1200) {
            Iterator<String> it=combatLootMemoryOnce.iterator();
            if(it.hasNext()){it.next();it.remove();} else break;
        }

        recordHistory("LOOT",3,name+" helped secure "+category+" for "+ca.faction+
            " after "+ca.fightId,ca.faction,name);
        for(String ally:ca.allies) {
            SimPlayer ap=players.get(key(ally));
            if(ap==null || !ap.faction.equalsIgnoreCase(p.faction)) continue;
            SocialEdge a=relationship(name,ally,true);
            a.trust=clampSocial(a.trust+1);
            rememberRelationship(a,"secured fight loot with "+ally);
        }
        save();
    }

    void onTestFightDeath(String victimName, String killerName) {
        recentVictim = victimName == null ? "" : victimName;
        recentKiller = killerName == null ? "" : killerName;
        if (visibleFight != null && victimName != null) {
            visibleFight.assignments.remove(key(victimName));
            writeCombatFile();
        }
    }

    void stopVisibleFightTest() {
        if (visibleFight == null) return;
        if (visibleFight.id != null && visibleFight.id.startsWith("TESTTEAM_")) {
            visibleFight = null;
            writeCombatFile();
        }
    }

    void onLiveDeath(String victimName, String killerName) {
        recentVictim = victimName == null ? "" : victimName;
        recentKiller = killerName == null ? "" : killerName;
        CombatAssignment killerAssignment=visibleFight==null?null:visibleFight.assignments.get(key(killerName));
        if (visibleFight != null && victimName != null) {
            visibleFight.assignments.remove(key(victimName));
            writeCombatFile();
        }
        SimPlayer victim = players.get(key(victimName));
        SimPlayer killer = players.get(key(killerName));

        boolean projectedDeath=projectedCombatDeaths.remove(key(victimName));
        if (victim != null && !victim.faction.isEmpty()) {
            SimFaction vf = factions.get(key(victim.faction));
            if (vf != null) {
                // COLD deaths approximate lost inventory here. HOT projected
                // deaths already withdrew the exact physical loadout on promotion.
                if(!projectedDeath) {
                    int lostHeals=Math.min(vf.healPots,10+rng.nextInt(11));
                    int lostPearls=Math.min(vf.pearls,2+rng.nextInt(5));
                    int lostSpeed=Math.min(vf.speedPots,1);
                    vf.healPots -= lostHeals;
                    vf.pearls -= lostPearls;
                    vf.speedPots -= lostSpeed;

                    boolean lostMainSet=false;
                    if (victim.combatClass == CombatClass.DIAMOND) {
                        if(vf.p4Sets>0){vf.p4Sets--;lostMainSet=true;}
                        if(vf.sharp4Swords>0) vf.sharp4Swords--;
                    } else if (victim.combatClass == CombatClass.BARD) {
                        if(vf.bardSets>0){vf.bardSets--;lostMainSet=true;}
                    } else if (victim.combatClass == CombatClass.ARCHER) {
                        if(vf.archerSets>0){vf.archerSets--;lostMainSet=true;}
                    } else if (victim.combatClass == CombatClass.ROGUE) {
                        if(vf.rogueSets>0){vf.rogueSets--;lostMainSet=true;}
                    }

                    if(killer!=null && !killer.faction.isEmpty() && !killer.faction.equalsIgnoreCase(victim.faction)) {
                        SimFaction kf=factions.get(key(killer.faction));
                        if(kf!=null) {
                            // Simulate what survives on the ground and is actually picked up.
                            kf.healPots += (int)Math.floor(lostHeals*0.70);
                            kf.pearls += (int)Math.floor(lostPearls*0.85);
                            kf.speedPots += lostSpeed;
                            if(lostMainSet && rng.nextInt(100)<78) {
                                if(victim.combatClass==CombatClass.DIAMOND){kf.p4Sets++;kf.sharp4Swords++;}
                                else if(victim.combatClass==CombatClass.BARD) kf.bardSets++;
                                else if(victim.combatClass==CombatClass.ARCHER) kf.archerSets++;
                                else if(victim.combatClass==CombatClass.ROGUE) kf.rogueSets++;
                            }
                        }
                    }
                }
                updateDtrStrategy(vf);
            }
        }

        if(victim!=null) {
            victim.deaths++;
            victim.reputation=Math.max(0,victim.reputation-2);
        }
        if(killer!=null && victim!=null && !killer.name.equalsIgnoreCase(victim.name)) {
            killer.kills++;
            killer.reputation=Math.min(999,killer.reputation+5+overallCombatSkill(victim)/18+rng.nextInt(5));
            plugin.broadcastKillCounter(killer.name,killer.kills,killer.deaths);
        }

        if (victim != null && killer != null && !victim.faction.isEmpty() && !killer.faction.isEmpty()
                && !victim.faction.equalsIgnoreCase(killer.faction)) {
            recordRivalry(victim.faction,killer.faction,10 + rng.nextInt(9));
            queueDeathConversation(victim,killer);
        }

        if(killer!=null && victim!=null && !killer.name.equalsIgnoreCase(victim.name)) {
            List<String> people=new ArrayList<String>();
            people.add(killer.name);people.add(victim.name);
            List<String> helpers=new ArrayList<String>();
            if(killerAssignment!=null) {
                Player kb=Bukkit.getPlayerExact(killer.name);
                for(String allyName:killerAssignment.allies) {
                    SimPlayer ally=players.get(key(allyName));
                    Player ab=Bukkit.getPlayerExact(allyName);
                    if(ally==null || !ally.faction.equalsIgnoreCase(killer.faction) || ab==null || !ab.isOnline()) continue;
                    if(kb!=null && kb.getWorld().equals(ab.getWorld()) &&
                       kb.getLocation().distanceSquared(ab.getLocation())<=24.0*24.0) {
                        helpers.add(ally.name);people.add(ally.name);
                        SocialEdge ka=relationship(killer.name,ally.name,true);
                        SocialEdge ak=relationship(ally.name,killer.name,true);
                        ka.trust=clampSocial(ka.trust+2);ka.respect=clampSocial(ka.respect+1);
                        ak.trust=clampSocial(ak.trust+2);ak.respect=clampSocial(ak.respect+1);
                        rememberRelationship(ka,"fought beside "+ally.name+" against "+victim.name);
                        rememberRelationship(ak,"helped "+killer.name+" fight "+victim.name);
                    }
                }
            }
            String summary=killer.name+" killed "+victim.name;
            if(!helpers.isEmpty()) summary+=" with help from "+joinWords(helpers,3);
            recordHistory("KILL",5,summary,killer.faction,people.toArray(new String[people.size()]));

            SocialEdge vk=relationship(victim.name,killer.name,true);
            vk.grudge=clampSocial(vk.grudge+4);
            rememberRelationship(vk,"was killed by "+killer.name);
            SocialEdge kv=relationship(killer.name,victim.name,true);
            kv.respect=clampSocial(kv.respect+2);
            rememberRelationship(kv,"killed "+victim.name+" in open combat");
        }

        if (plugin.isCreatorIdentity(victimName)) queueCreatorDeathReactions(victimName);
        if (killerName != null && plugin.isCreatorIdentity(killerName)) queueCreatorKillReactions(killerName);
        save();
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

    List<String> allIdentityNames() {
        List<String> out=new ArrayList<String>();
        for(SimPlayer p:players.values()) out.add(p.name);
        Collections.sort(out,String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    String identityDisplayName(String keyOrName) {
        SimPlayer p=players.get(key(keyOrName));
        return p==null?keyOrName:p.name;
    }

    int logicalOnlineCount() {
        int n=0;
        for(SimPlayer p:players.values()) if(p.logicalOnline) n++;
        return n;
    }

    private Collection<SimPlayer> logicallyOnlinePlayers() {
        List<SimPlayer> out=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) if(p.logicalOnline) out.add(p);
        return out;
    }

    private SimPlayer randomOnlinePlayer() {
        List<SimPlayer> xs=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) if(p.logicalOnline) xs.add(p);
        if(xs.isEmpty()) return null;
        return xs.get(rng.nextInt(xs.size()));
    }

    private int logicalOnlineTarget() {
        Calendar cal=Calendar.getInstance();
        int hour=cal.get(Calendar.HOUR_OF_DAY);
        int min=Math.max(6,plugin.getConfig().getInt("sim-world.logical-online-min",18));
        int peak=Math.max(min,plugin.getConfig().getInt("sim-world.logical-online-peak",46));
        int late=Math.max(4,plugin.getConfig().getInt("sim-world.logical-online-late-night",12));

        if(hour>=1 && hour<7) return Math.min(players.size(),late+rng.nextInt(4));
        if(hour>=17 && hour<24) return Math.min(players.size(),peak-3+rng.nextInt(7));
        if(hour>=12 && hour<17) return Math.min(players.size(),min+(peak-min)*2/3+rng.nextInt(5));
        return Math.min(players.size(),min+rng.nextInt(Math.max(2,(peak-min)/3+1)));
    }

    private int newSessionTicks(SimPlayer p) {
        int minM=Math.max(10,plugin.getConfig().getInt("sim-world.session-min-minutes",25));
        int maxM=Math.max(minM,plugin.getConfig().getInt("sim-world.session-max-minutes",140));
        int minutes=minM+rng.nextInt(maxM-minM+1);
        minutes += p.sociability/8;
        if(plugin.isCreatorIdentity(p.name)) minutes += 30;
        long tickSeconds=Math.max(10L,plugin.getConfig().getLong("sim-world.tick-seconds",30L));
        return Math.max(2,(int)Math.ceil((minutes*60.0)/tickSeconds));
    }

    private void updateLogicalSessionsAndGoals() {
        int target=logicalOnlineTarget();
        int online=0;
        int presenceBudget=2;
        List<SimPlayer> offline=new ArrayList<SimPlayer>();

        long nowMs=System.currentTimeMillis();
        for(SimPlayer p:players.values()) {
            if(p.bannedUntil>nowMs) {
                if(p.logicalOnline) plugin.broadcastSimulatedPresence(p.name,false);
                p.logicalOnline=false;
                p.currentGoal="banned";
                continue;
            }
            if(p.bannedUntil>0 && p.bannedUntil<=nowMs) {
                p.bannedUntil=0L;
                if(rng.nextInt(100)<45) enqueue(p.name,"im unbanned finally",false);
            }

            if(p.logicalOnline) {
                online++;
                p.sessionTicksLeft--;
                if(p.sessionTicksLeft<=0 && online>target && !plugin.isCreatorIdentity(p.name)) {
                    p.logicalOnline=false;
                    p.currentGoal="offline";
                    online--;
                    offline.add(p);
                    if(presenceBudget>0 && rng.nextInt(100)<65) {
                        plugin.broadcastSimulatedPresence(p.name,false);
                        presenceBudget--;
                    }
                } else if(p.sessionTicksLeft<=0) {
                    p.sessionTicksLeft=newSessionTicks(p);
                }
            } else {
                offline.add(p);
            }
        }

        Collections.shuffle(offline,rng);
        for(SimPlayer p:offline) {
            if(online>=target) break;
            if(p.logicalOnline) continue;
            int loginBias=20+p.sociability/2+(p.faction.isEmpty()?15:0);
            if(plugin.isCreatorIdentity(p.name)) loginBias+=20;
            if(rng.nextInt(100)>=Math.min(95,loginBias) && online>=target-3) continue;
            p.logicalOnline=true;
            p.sessionTicksLeft=newSessionTicks(p);
            p.nextGoalTick=sotwTicks;
            online++;
            if(presenceBudget>0 && rng.nextInt(100)<65) {
                plugin.broadcastSimulatedPresence(p.name,true);
                presenceBudget--;
            }
        }

        for(SimPlayer p:players.values()) {
            if(!p.logicalOnline) continue;
            if(sotwTicks>=p.nextGoalTick) {
                p.currentGoal=chooseGoal(p);
                p.nextGoalTick=sotwTicks+2+rng.nextInt(6);
            }
        }
    }

    private String chooseGoal(SimPlayer p) {
        if(p.faction.isEmpty()) {
            if(p.leaderCandidate) return p.sociability>=45?"recruit":"gather";
            if(p.sociability>=65) return "recruit";
            if("miner".equals(p.preferredJob)) return "mine";
            if("farmer".equals(p.preferredJob)) return "gather";
            return rng.nextBoolean()?"gather":"social";
        }

        SimFaction f=factions.get(key(p.faction));
        if(f==null) return "idle";
        if(f.recoveryMode) return p.riskTolerance<80?"safe":"defend";

        switch(f.stage) {
            case RECRUITING:
                if("leader".equals(p.role) || p.sociability>=65) return "recruit";
                return "miner".equals(p.preferredJob)?"mine":"gather";
            case SCOUT_CLAIM:
                return "leader".equals(p.role)?"scout":("miner".equals(p.preferredJob)?"mine":"gather");
            case GATHER_STARTER:
                if(f.surfaceQueued && !f.baseQueued && "builder".equals(p.preferredJob)) return "build";
                if("miner".equals(p.preferredJob)) return "mine";
                if("builder".equals(p.preferredJob)) return rng.nextInt(100)<82?"gather":"scout";
                return "gather";
            case BUILD_STARTER:
                return (p.patience>=35 || "builder".equals(p.preferredJob))?"build":"gather";
            case ECONOMY:
                if("farmer".equals(p.preferredJob)) return "farm";
                if("miner".equals(p.preferredJob)) return "mine";
                if("brewer".equals(p.preferredJob)) return "supply";
                if(p.economicIq>=75) return "trade";
                return rng.nextBoolean()?"gather":"social";
            case BREWER:
                if("brewer".equals(p.preferredJob)) return "brew";
                if("miner".equals(p.preferredJob)) return "mine";
                return "supply";
            case GEARING:
                if("miner".equals(p.preferredJob)) return "mine";
                if("brewer".equals(p.preferredJob)) return "brew";
                return "gear";
            case PVP_READY:
                if (!combatReady(f)) {
                    if ("brewer".equals(p.preferredJob)) return "brew";
                    if ("miner".equals(p.preferredJob)) return "mine";
                    if ("farmer".equals(p.preferredJob)) return "farm";
                    return "gear";
                }

                int rivalryHeat=0;
                String rival=strongestRival(f.name);
                if(!rival.isEmpty()) rivalryHeat=rivalryScore(f.name,rival);
                int rewardDrive=p.aggression+p.riskTolerance+p.skill/2+p.reputation/3+rivalryHeat/2;

                if ("PVP".equals(f.archetype) || "TRAPPER".equals(f.archetype)) {
                    if ("farmer".equals(p.preferredJob) && rng.nextInt(100)<12) return "farm";
                    if ("brewer".equals(p.preferredJob) && rng.nextInt(100)<20) return "brew";
                    return rng.nextInt(100)<88 ? "patrol" : "social";
                }

                if("farmer".equals(p.preferredJob) && p.economicIq>=70 && rng.nextInt(100)<38) return "farm";
                if("brewer".equals(p.preferredJob) && rng.nextInt(100)<32) return "brew";
                if(rewardDrive>=145) return "patrol";
                if(p.economicIq>=78 && rng.nextInt(100)<28) return "trade";
                return rng.nextInt(100)<58?"patrol":"social";
        }
        return "idle";
    }

    private void tick() {
        sotwTicks++;
        updateLogicalSessionsAndGoals();

        // During a fresh SOTW physical map build, identities may be logically
        // online and chat, but claims/economy/base/event strategy must wait for
        // the authoritative geometry to exist.
        if(!plugin.productionWorldReady()) {
            if(sotwTicks%4L==0L) save();
            return;
        }

        communityTick();
        formationTick();
        applyCreatorFactionSpecializations();
        updateCampTargets();
        economy.tickAll(logicallyOnlinePlayers(), factions, sotwTicks);

        if (factions.isEmpty()) {
            save();
            return;
        }

        List<SimFaction> list = new ArrayList<SimFaction>(factions.values());
        int work = Math.min(list.size(), Math.max(1, plugin.getConfig().getInt("sim-world.factions-per-tick", 4)));
        for (int i = 0; i < work; i++) {
            if (factionCursor >= list.size()) factionCursor = 0;
            SimFaction f = list.get(factionCursor++);
            updateDtrStrategy(f);
            advance(f);
        }

        maybeResolveOffscreenBrawl();
        save();
    }

    private void communityTick() {
        if(sotwTicks%3L!=0L) return;

        maybeCommunityRewards();

        int roll=rng.nextInt(100);
        if(roll<8) maybeLeadershipDecision();
        else if(roll<15) maybeDuelCulture();
        else if(roll<27) maybeHistoryGossip();
        else if(roll<42) maybeSocialBond();
        else if(roll<52) maybeFactionDrama();
        else if(roll<57) maybeFactionUpgradeRecruit();
        else if(roll<61) maybeFactionDefection();
        else if(roll<69) maybeCreatorPvpDrama();
        else if(roll<76) maybeDonorUpgrade();
        else if(roll<82) maybeStaffReport();
        else if(roll<85) maybeModerationAction();
        else if(roll<96) maybeOwnerCommunityMessage();
    }

    private void maybeCommunityRewards() {
        long now=System.currentTimeMillis();

        // Simulated voting is intentionally a trickle. Early SOTW gets visible
        // vote activity, while the real-time cooldown prevents infinite key inflation.
        if(rng.nextInt(100)<8) {
            List<SimPlayer> eligible=new ArrayList<SimPlayer>();
            long voteCd=Math.max(1,plugin.getConfig().getLong("rewards.vote-cooldown-hours",12L))*3600000L;
            for(SimPlayer p:players.values()) {
                if(!p.logicalOnline || p.bannedUntil>now) continue;
                if(now-p.lastVoteAt>=voteCd) eligible.add(p);
            }
            if(!eligible.isEmpty()) {
                SimPlayer p=eligible.get(rng.nextInt(eligible.size()));
                p.lastVoteAt=now;
                plugin.recordSimulatedVote(p.name);
            }
        }

        if(rng.nextInt(100)<5) {
            List<SimPlayer> donors=new ArrayList<SimPlayer>();
            for(SimPlayer p:players.values()) {
                if(!p.logicalOnline || p.donorLevel<=0 || p.bannedUntil>now) continue;
                long hours=p.donorLevel>=4 ? plugin.getConfig().getLong("rewards.platinum-key-hours",8L) :
                    (p.donorLevel==3 ? plugin.getConfig().getLong("rewards.gold-key-hours",12L) :
                    (p.donorLevel==2 ? plugin.getConfig().getLong("rewards.silver-key-hours",18L) :
                                      plugin.getConfig().getLong("rewards.basic-key-hours",24L)));
                long donorCd=Math.max(1,hours)*3600000L;
                if(now-p.lastDonorKeyAt>=donorCd) donors.add(p);
            }
            if(!donors.isEmpty()) {
                SimPlayer p=donors.get(rng.nextInt(donors.size()));
                p.lastDonorKeyAt=now;
                int keys=p.donorLevel>=4?3:(p.donorLevel==3?2:1);
                addPendingKey(p.name,"donor",keys);
                plugin.broadcastCommunityEvent("&6[Donor] &f"+p.name+" &7received &e"+keys+" daily Donor Key"+(keys==1?"":"s")+"&7.");
                if(rng.nextInt(100)<45) enqueue(p.name,"going spawn for my donor keys",false);
            }
        }
    }

    int pendingKeyCount(String name,String type) {
        SimPlayer p=players.get(key(name));
        if(p==null) return 0;
        return "donor".equalsIgnoreCase(type)?p.pendingDonorKeys:p.pendingVoteKeys;
    }

    void addPendingKey(String name,String type,int amount) {
        if(amount<=0) return;
        SimPlayer p=players.get(key(name));
        if(p==null) return;
        if("donor".equalsIgnoreCase(type)) p.pendingDonorKeys=Math.min(64,p.pendingDonorKeys+amount);
        else p.pendingVoteKeys=Math.min(64,p.pendingVoteKeys+amount);
        save();
    }

    void consumePendingKey(String name,String type,int amount) {
        if(amount<=0) return;
        SimPlayer p=players.get(key(name));
        if(p==null) return;
        if("donor".equalsIgnoreCase(type)) p.pendingDonorKeys=Math.max(0,p.pendingDonorKeys-amount);
        else p.pendingVoteKeys=Math.max(0,p.pendingVoteKeys-amount);
        save();
    }

    void rewardVoteParty() {
        for(SimPlayer p:players.values()) {
            if(p.logicalOnline && p.bannedUntil<=System.currentTimeMillis())
                p.pendingVoteKeys=Math.min(64,p.pendingVoteKeys+1);
        }
        save();
    }

    void creditPlayerBalance(String name,double amount) {
        SimPlayer p=players.get(key(name));
        if(p==null || amount<=0) return;
        p.balance+=amount;
        save();
    }

    private void maybeLeadershipDecision() {
        if(factions.isEmpty()) return;
        List<SimFaction> fs=new ArrayList<SimFaction>(factions.values());
        SimFaction f=fs.get(rng.nextInt(fs.size()));
        SimPlayer leader=players.get(key(f.leader));
        if(leader==null || !leader.logicalOnline) return;

        int q=leaderQuality(leader);
        leader.leaderExperience++;

        // Strong leaders issue terse, useful direction based on faction state.
        if(f.recoveryMode) {
            if(q>=65) enqueue(leader.name,oneOf("everyone home low dtr","dont go out till dtr","hold base for now"),false);
            else if(leader.riskTolerance>=70) enqueue(leader.name,oneOf("we can still fight","one more then home","dont be scared lol"),false);
            return;
        }

        int members=Math.max(1,f.members.size());
        if(f.healPots<members*16) {
            SimPlayer brewer=firstJobMember(f,"brewer");
            if(brewer!=null) brewer.currentGoal="brew";
            if(q>=62) enqueue(leader.name,"we need pots before we go out",false);
        } else if(f.pearls<members*6) {
            if(q>=62) enqueue(leader.name,"we need pearls go end",false);
        } else if(f.treasury<500 && q>=68) {
            SimPlayer farmer=firstJobMember(f,"farmer");
            if(farmer!=null) farmer.currentGoal="farm";
            enqueue(leader.name,"make money first then pvp",false);
        } else if(f.stage==Stage.PVP_READY && q>=70 && leader.composure>=65) {
            enqueue(leader.name,oneOf("get on lets go end","gear up we're going out","meet spawn side"),false);
        } else if(q<50 && leader.riskTolerance>=70 && rng.nextInt(100)<55) {
            enqueue(leader.name,oneOf("everyone go spawn rn","just fight them","we dont need more pots"),false);
        }

        maybePromotionTryout(f,leader);
    }

    private void maybePromotionTryout(SimFaction f,SimPlayer leader) {
        if(f==null || leader==null || f.members.size()<3 || rng.nextInt(100)>=30) return;

        List<SimPlayer> candidates=new ArrayList<SimPlayer>();
        for(String n:f.members) {
            if(n.equalsIgnoreCase(f.leader)) continue;
            SimPlayer p=players.get(key(n));
            if(p==null || !"member".equalsIgnoreCase(p.factionTitle)) continue;
            if(p.reputation>=10 || p.duelWins>0 || relationship(leader.name,p.name,true).trust>=62)
                candidates.add(p);
        }
        if(candidates.isEmpty()) return;

        SimPlayer candidate=candidates.get(rng.nextInt(candidates.size()));
        SocialEdge rel=relationship(leader.name,candidate.name,true);
        boolean formal=leader.standards>=68 || f.powerFaction;
        boolean passed;

        if(formal) {
            int performance=(candidate.mechanics*52+candidate.pvpIq*33+candidate.composure*15)/100+
                candidate.duelWins*3+rng.nextInt(31)-15;
            int bar=70+leader.standards/4;
            passed=performance>=bar;
            candidate.duelWins+=passed?1:0;
            candidate.duelLosses+=passed?0:1;
            recordHistory("TRYOUT",5,candidate.name+(passed?" passed ":" failed ")+
                "an officer duel for "+f.name,f.name,candidate.name,leader.name);
        } else {
            // Weak leaders can promote a friend even without proving much.
            passed=rel.affinity>=28 || rel.trust>=68 || rng.nextInt(100)<18;
        }

        if(passed) {
            candidate.factionTitle="officer";
            rel.trust=clampSocial(rel.trust+4);
            rel.respect=clampSocial(rel.respect+5);
            recordHistory("PROMOTION",7,leader.name+" promoted "+candidate.name+" to officer in "+f.name,
                f.name,leader.name,candidate.name);
            enqueue(leader.name,""+candidate.name+" is officer now",false);
        }
    }

    private void maybeDuelCulture() {
        if(visibleFight!=null) return;
        String ownerName=plugin.getConfig().getString("owner.name","");
        Player owner=ownerName.isEmpty()?null:Bukkit.getPlayerExact(ownerName);
        if(owner==null) return;

        long now=System.currentTimeMillis();
        List<SimPlayer> candidates=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) {
            if(!p.logicalOnline || p.bannedUntil>now || p.nextDuelRequestAt>now) continue;
            SocialEdge rel=relationship(p.name,ownerName,true);
            int urge=p.aggression/2+p.riskTolerance/3+p.sociability/4+p.reputation/5+
                (plugin.isCreatorIdentity(p.name)?18:0)+(p.duelWins>p.duelLosses?8:0);
            if("leader".equals(p.role)) urge+=p.standards/5;
            if(rel.grudge>=40) urge+=18;
            if(urge+rng.nextInt(45)>=72) candidates.add(p);
        }
        if(candidates.isEmpty()) return;

        SimPlayer p=candidates.get(rng.nextInt(candidates.size()));
        p.nextDuelRequestAt=now+(8+rng.nextInt(18))*60L*1000L;
        String reason;
        if("leader".equals(p.role) && p.standards>=72) reason="wants to see if you can actually fight";
        else if(p.reputation>=35) reason="wants a clean 1v1";
        else if(relationship(p.name,ownerName,true).grudge>=40) reason="called you out";
        else reason="sent you a duel request";
        plugin.offerSimulatedDuel(p.name,ownerName,reason);
    }

    String duelDecision(String challenger,String target,boolean challengerIsOwner) {
        SimPlayer p=players.get(key(target));
        if(p==null || !p.logicalOnline || p.bannedUntil>System.currentTimeMillis()) return "DECLINE|not online rn";
        if(visibleFight!=null) return "DECLINE|im busy fighting rn";

        SocialEdge rel=relationship(p.name,challenger,true);
        int score=p.aggression/3+p.riskTolerance/3+p.sociability/5+p.reputation/6+
            (plugin.isCreatorIdentity(p.name)?12:0)+(challengerIsOwner?12:0)+rng.nextInt(31);
        score+=rel.respect/5-rel.grudge/7;

        if("leader".equals(p.role)) {
            // Calm leaders don't spam duels, but high-standard leaders use them
            // deliberately to evaluate important people.
            score+=p.standards/4;
            score-=p.composure>=80?8:0;
        }

        if(score>=58) {
            String msg;
            if("leader".equals(p.role) && p.composure>=72)
                msg=oneOf("yeah queue it","sure lets run it","one duel then i gotta lead");
            else if(p.aggression>=75)
                msg=oneOf("send it","yeah rn","queue me");
            else msg=oneOf("sure","yeah im down","lets do it");
            return "ACCEPT|"+msg;
        }

        return "DECLINE|"+oneOf("not rn","im good","maybe later","got stuff to do");
    }

    boolean startHumanVsSimDuel(Player human,String simName) {
        if(human==null || visibleFight!=null) return false;
        SimPlayer p=players.get(key(simName));
        if(p==null || !p.logicalOnline || p.bannedUntil>System.currentTimeMillis()) return false;

        if(!plugin.duelArenaReady()) return false;
        Location center=plugin.duelCenterLocation();
        Location simSpawn=plugin.duelSimSpawnLocation();
        if(center==null || simSpawn==null || center.getWorld()==null) return false;
        World w=center.getWorld();
        int y=center.getBlockY();
        int cx=center.getBlockX();
        int cz=center.getBlockZ();

        VisibleFight fight=new VisibleFight();
        fight.id="DUEL_"+System.currentTimeMillis();
        fight.type="DUEL";
        fight.world=w.getName();
        fight.centerX=cx; fight.centerY=y; fight.centerZ=cz;
        fight.ownerName=human.getName();
        fight.teamSize=1;
        fight.expiresAt=System.currentTimeMillis()+
            Math.max(60,plugin.getConfig().getInt("duels.timeout-seconds",180))*1000L;

        CombatAssignment ca=new CombatAssignment();
        ca.fightId=fight.id;
        ca.name=p.name;
        ca.faction=p.faction==null||p.faction.isEmpty()?"none":p.faction;
        ca.enemyFaction="DUEL";
        ca.world=w.getName();
        ca.combatClass=CombatClass.DIAMOND;
        ca.action="FOCUS";
        ca.skill=p.skill;
        ca.mechanics=p.mechanics;
        ca.pvpIq=p.pvpIq;
        ca.gameSense=p.gameSense;
        ca.composure=p.composure;
        ca.mistake=combatMistakePropensity(p);
        ca.aggression=p.aggression;
        ca.risk=p.riskTolerance;
        ca.x=simSpawn.getBlockX(); ca.y=simSpawn.getBlockY(); ca.z=simSpawn.getBlockZ();
        ca.homeX=ca.x; ca.homeY=ca.y; ca.homeZ=ca.z;
        ca.focus=human.getName();
        ca.enemies.add(human.getName());
        fight.assignments.put(key(p.name),ca);

        visibleFight=fight;
        writeCombatFile();
        recordHistory("DUEL",5,human.getName()+" and "+p.name+" agreed to duel",p.faction,human.getName(),p.name);
        return true;
    }

    Location humanDuelSpawn() {
        if(visibleFight==null || !"DUEL".equals(visibleFight.type)) return null;
        return plugin.duelHumanSpawnLocation();
    }

    void finishDuel(String winner,String loser) {
        SimPlayer win=players.get(key(winner));
        SimPlayer lose=players.get(key(loser));
        if(win!=null) {
            win.duelWins++;
            win.reputation=Math.min(999,win.reputation+3+(lose!=null?Math.max(0,lose.reputation/20):2));
        }
        if(lose!=null) lose.duelLosses++;

        if(win!=null && loser!=null) {
            SocialEdge e=relationship(win.name,loser,true);
            e.respect=clampSocial(e.respect+3);
            rememberRelationship(e,"beat "+loser+" in a duel");
        }
        if(lose!=null && winner!=null) {
            SocialEdge e=relationship(lose.name,winner,true);
            e.respect=clampSocial(e.respect+4);
            rememberRelationship(e,"lost a duel to "+winner);
        }

        String faction=win!=null?win.faction:(lose!=null?lose.faction:"");
        recordHistory("DUEL",7,winner+" beat "+loser+" in a duel",faction,winner,loser);

        String human=!players.containsKey(key(winner))?winner:(!players.containsKey(key(loser))?loser:"");
        if(!human.isEmpty()) {
            String tryoutFaction=pendingHumanTryoutFaction.remove(key(human));
            if(tryoutFaction!=null) {
                SimFaction tf=factions.get(key(tryoutFaction));
                SimPlayer tl=tf==null?null:players.get(key(tf.leader));
                boolean humanWon=winner.equalsIgnoreCase(human);
                if(humanWon && tl!=null) {
                    executeFactionInvite(tl,human);
                    Player hp=Bukkit.getPlayerExact(human);
                    if(hp!=null) plugin.sendSimulatedPrivate(hp,tl.name,"you passed. leader sent the inv");
                    recordHistory("TRYOUT",8,human+" passed "+tryoutFaction+"'s live duel tryout",
                        tryoutFaction,human,tl.name);
                } else if(tl!=null) {
                    Player hp=Bukkit.getPlayerExact(human);
                    if(hp!=null) plugin.sendSimulatedPrivate(hp,tl.name,"not yet. get better and ask again");
                    recordHistory("TRYOUT",5,human+" failed "+tryoutFaction+"'s live duel tryout",
                        tryoutFaction,human,tl.name);
                }
            }
        }
        visibleFight=null;
        writeCombatFile();
        save();
    }

    void cancelDuel(String a,String b,String reason) {
        if(reason!=null && !reason.isEmpty())
            recordHistory("DUEL",3,a+" vs "+b+" ended: "+reason,"",a,b);
        if(visibleFight!=null && "DUEL".equals(visibleFight.type)) {
            visibleFight=null;
            writeCombatFile();
        }
    }

    void onDuelDeclined(String challenger,String target) {
        SimPlayer sim=players.get(key(challenger));
        String other=target;
        if(sim==null) { sim=players.get(key(target)); other=challenger; }
        if(sim==null) return;
        SocialEdge e=relationship(sim.name,other,true);
        // Most people do not treat a declined duel as betrayal.
        if(sim.aggression>=80 && sim.composure<50) e.affinity=clampAffinity(e.affinity-2);
        e.lastInteraction=System.currentTimeMillis();
    }

    int duelWinsFor(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?0:p.duelWins;
    }

    int duelLossesFor(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?0:p.duelLosses;
    }

    private void maybeHistoryGossip() {
        if(communityHistory.isEmpty()) return;
        List<SimPlayer> online=onlineCommunityPlayers();
        if(online.isEmpty()) return;

        List<HistoryEvent> candidates=new ArrayList<HistoryEvent>();
        Iterator<HistoryEvent> it=communityHistory.descendingIterator();
        int seen=0;
        while(it.hasNext() && seen++<30) {
            HistoryEvent e=it.next();
            if(e.importance>=5) candidates.add(e);
        }
        if(candidates.isEmpty()) return;

        HistoryEvent e=candidates.get(rng.nextInt(candidates.size()));
        SimPlayer speaker=online.get(rng.nextInt(online.size()));
        String line;
        if("BETRAYAL".equals(e.type)) line=oneOf("still wild that "+e.summary,"people forgot "+e.summary,"thats why i dont trust everyone");
        else if("DUEL".equals(e.type)||"TRYOUT".equals(e.type)) line=oneOf("remember "+e.summary,"that duel changed how people looked at them",e.summary);
        else if("RAID".equals(e.type)) line=oneOf("remember when "+e.summary,e.summary,"that was a crazy map moment");
        else line=e.summary;
        enqueue(speaker.name,line,false);
    }

    private List<SimPlayer> onlineCommunityPlayers() {
        List<SimPlayer> out=new ArrayList<SimPlayer>();
        long now=System.currentTimeMillis();
        for(SimPlayer p:players.values()) if(p.logicalOnline && p.bannedUntil<=now) out.add(p);
        return out;
    }

    private void maybeSocialBond() {
        List<SimPlayer> online=onlineCommunityPlayers();
        if(online.size()<2) return;
        SimPlayer a=online.get(rng.nextInt(online.size()));
        SimPlayer b=online.get(rng.nextInt(online.size()));
        if(a==b) return;

        boolean sameFaction=!a.faction.isEmpty() && a.faction.equalsIgnoreCase(b.faction);
        SocialEdge ab=relationship(a.name,b.name,true);
        SocialEdge ba=relationship(b.name,a.name,true);

        if(sameFaction) {
            int gain=1+rng.nextInt(3);
            ab.affinity=clampAffinity(ab.affinity+gain);
            ba.affinity=clampAffinity(ba.affinity+gain);
            ab.trust=clampSocial(ab.trust+1);
            ba.trust=clampSocial(ba.trust+1);
            if(ab.affinity>=38 && rng.nextInt(100)<28) {
                rememberRelationship(ab,b.name+" has been reliable in "+a.faction);
                rememberRelationship(ba,a.name+" has been reliable in "+b.faction);
            }
        } else if(!a.faction.isEmpty() && !b.faction.isEmpty() && !a.faction.equalsIgnoreCase(b.faction)) {
            int heat=rivalryScore(a.faction,b.faction);
            if(heat>=8 && rng.nextInt(100)<34) {
                ab.respect=clampSocial(ab.respect+1);
                ba.respect=clampSocial(ba.respect+1);
                rememberRelationship(ab,"rivalry with "+b.name+" from "+b.faction);
            }
        }
    }

    private void maybeFactionDrama() {
        List<SimFaction> fs=new ArrayList<SimFaction>(factions.values());
        if(fs.isEmpty()) return;
        SimFaction f=fs.get(rng.nextInt(fs.size()));
        if(f.members.size()<2) return;

        SimPlayer a=players.get(key(f.members.get(rng.nextInt(f.members.size()))));
        SimPlayer b=players.get(key(f.members.get(rng.nextInt(f.members.size()))));
        if(a==null || b==null || a==b) return;

        SocialEdge ab=relationship(a.name,b.name,true);
        SocialEdge ba=relationship(b.name,a.name,true);

        if(rng.nextInt(100)<55) {
            int hit=1+rng.nextInt(3);
            ab.affinity=clampAffinity(ab.affinity-hit);
            ba.affinity=clampAffinity(ba.affinity-hit);
            ab.trust=clampSocial(ab.trust-1);
            ba.trust=clampSocial(ba.trust-1);
            ab.grudge=clampSocial(ab.grudge+1);
            ba.grudge=clampSocial(ba.grudge+1);
            if(rng.nextInt(100)<30) {
                enqueue(a.name,oneOf("why did you take my set","bro stop taking all the pearls","you keep leaving us in fights","dont touch my chest stuff"),false);
                rememberRelationship(ab,"argued with "+b.name+" in "+f.name);
            }
        } else {
            ab.affinity=clampAffinity(ab.affinity+2);
            ba.affinity=clampAffinity(ba.affinity+2);
            ab.trust=clampSocial(ab.trust+1);
            ba.trust=clampSocial(ba.trust+1);
        }
    }

    private int knownRosterValue(SimFaction f,SimPlayer p) {
        if(p==null) return Integer.MIN_VALUE/4;
        SimPlayer leader=players.get(key(f.leader));
        SocialEdge rel=leader==null?null:relationship(leader.name,p.name,true);
        int score=donorInfluence(p)+p.reputation/3+p.teamwork/4+p.loyalty/4;
        if(plugin.isCreatorIdentity(p.name)) score+=45;
        if(rel!=null) score+=rel.affinity/2+(rel.trust-50)/2-rel.grudge/2;
        if(p.combatClass==CombatClass.BARD && classCount(f,CombatClass.BARD)<=1) score+=28;
        if(p.combatClass==CombatClass.ARCHER && classCount(f,CombatClass.ARCHER)<=1) score+=22;
        if("brewer".equals(p.preferredJob) && jobCount(f,"brewer")<=1) score+=22;
        if("miner".equals(p.preferredJob) && jobCount(f,"miner")<=1) score+=18;
        return score;
    }

    private SimPlayer replaceableMember(SimFaction f,int incomingKnownValue) {
        SimPlayer worst=null;
        int worstScore=Integer.MAX_VALUE;
        for(String name:f.members) {
            if(name.equalsIgnoreCase(f.leader)) continue;
            SimPlayer p=players.get(key(name));
            if(p==null) continue;
            int score=knownRosterValue(f,p);
            if(score<worstScore) { worst=p; worstScore=score; }
        }
        if(worst==null) return null;
        return incomingKnownValue>=worstScore+24 ? worst : null;
    }

    private void recordFactionKick(SimFaction f,SimPlayer kicked,String replacementName) {
        if(f==null || kicked==null) return;
        SimPlayer leader=players.get(key(f.leader));
        if(leader!=null) {
            SocialEdge kl=relationship(kicked.name,leader.name,true);
            kl.affinity=clampAffinity(kl.affinity-14);
            kl.trust=clampSocial(kl.trust-16);
            kl.grudge=clampSocial(kl.grudge+18);
            rememberRelationship(kl,leader.name+" kicked me from "+f.name+
                (replacementName==null||replacementName.isEmpty()?"":" for "+replacementName));
        }
        kicked.loyalty=Math.max(0,kicked.loyalty-4);
        kicked.faction="";
        kicked.role=kicked.preferredJob;
        kicked.factionTitle="member";
        kicked.currentGoal="social";
        recordHistory("KICK",6,kicked.name+" was kicked from "+f.name+
            (replacementName==null||replacementName.isEmpty()?"":" to make room for "+replacementName),
            f.name,kicked.name,f.leader,replacementName);
        enqueue(kicked.name,rng.nextBoolean()?"wow kicked for no reason":"lff again lol",false);
    }

    private void maybeFactionUpgradeRecruit() {
        List<SimPlayer> solos=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) if(p.logicalOnline && p.faction.isEmpty() && !p.leaderCandidate) solos.add(p);
        if(solos.isEmpty()) return;

        SimPlayer candidate=solos.get(rng.nextInt(solos.size()));
        for(SimFaction f:factions.values()) {
            if(f.members.size()<MAX_FACTION_MEMBERS) continue;
            int incoming=candidateScore(f,candidate);
            SimPlayer kicked=replaceableMember(f,incoming);
            if(kicked==null) continue;

            if(!plugin.removeSimFactionMemberAuthority(f.name,kicked.name)) continue;
            f.members.remove(kicked.name);
            recordFactionKick(f,kicked,candidate.name);

            if(plugin.joinSimFactionAuthority(f.name,candidate.name)) {
                candidate.faction=f.name;
                candidate.role=candidate.preferredJob;
                f.members.add(candidate.name);
                SocialEdge leaderRel=relationship(f.leader,candidate.name,true);
                rememberRelationship(leaderRel,"made room for "+candidate.name+" in "+f.name);
                enqueue(f.leader,"made room for "+candidate.name,false);
                normalizeFactionClasses(f);
            }
            return;
        }
    }

    private void maybeFactionDefection() {
        List<SimPlayer> candidates=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) {
            if(p.faction.isEmpty() || "leader".equals(p.role) || !p.logicalOnline || p.loyalty>42) continue;
            SimFaction old=factions.get(key(p.faction));
            if(old==null) continue;
            SocialEdge toLeader=relationship(p.name,old.leader,true);
            if(toLeader.affinity<-20 || toLeader.grudge>45) candidates.add(p);
        }
        if(candidates.isEmpty()) return;

        SimPlayer p=candidates.get(rng.nextInt(candidates.size()));
        SimFaction old=factions.get(key(p.faction));
        SimFaction target=null;
        int best=Integer.MIN_VALUE;
        for(SimFaction f:factions.values()) {
            if(f==old || f.members.size()>=MAX_FACTION_MEMBERS) continue;
            int score=candidateScore(f,p);
            if(score>best){best=score;target=f;}
        }
        if(target==null || rng.nextInt(100)>=18) return;

        if(!plugin.removeSimFactionMemberAuthority(old.name,p.name)) return;
        old.members.remove(p.name);
        String oldName=old.name;
        recordFactionKick(old,p,target.name);

        int stolenPearls=Math.min(old.pearls,rng.nextInt(5));
        int stolenHeals=Math.min(old.healPots,rng.nextInt(7));
        old.pearls-=stolenPearls; old.healPots-=stolenHeals;

        if(plugin.joinSimFactionAuthority(target.name,p.name)) {
            p.faction=target.name;
            p.role=p.preferredJob;
            p.factionTitle="member";
            target.members.add(p.name);
            target.pearls+=stolenPearls;
            target.healPots+=stolenHeals;
            rememberRelationship(relationship(old.leader,p.name,true),
                p.name+" left "+oldName+" for "+target.name+" and took supplies");
            recordHistory("BETRAYAL",9,p.name+" left "+oldName+" for "+target.name+
                " and took "+stolenPearls+" pearls / "+stolenHeals+" heals",
                oldName,p.name,old.leader,target.leader);
            enqueue(p.name,"joined "+target.name,false);
            recordRivalry(oldName,target.name,8);
        }
    }

    private void maybeCreatorPvpDrama() {
        List<SimPlayer> creators=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) if(p.logicalOnline && plugin.isCreatorIdentity(p.name)) creators.add(p);
        if(creators.isEmpty()) return;
        SimPlayer creator=creators.get(rng.nextInt(creators.size()));

        List<SimPlayer> others=onlineCommunityPlayers();
        if(others.size()<2) return;
        SimPlayer other=others.get(rng.nextInt(others.size()));
        if(other==creator) return;

        SocialEdge edge=relationship(other.name,creator.name,true);
        if(edge.respect>=65) enqueue(other.name,oneOf(creator.name+" is actually nasty",creator.name+" got combos","who is fighting "+creator.name),false);
        else if(edge.grudge>=40) enqueue(other.name,oneOf(creator.name+" talks too much lol","someone fight "+creator.name,creator.name+" come spawn"),false);
    }

    private void maybeDonorUpgrade() {
        List<SimPlayer> eligible=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) {
            if(!p.logicalOnline || p.bannedUntil>System.currentTimeMillis()) continue;
            int score=p.reputation/4+p.sociability/3+p.loyalty/4+p.ownerAffinity/5+
                p.riskTolerance/5+rng.nextInt(35);
            if(score>=35) eligible.add(p);
        }
        if(eligible.isEmpty()) return;

        SimPlayer p=eligible.get(rng.nextInt(eligible.size()));
        boolean gambler=p.riskTolerance>=68 || (p.pendingDonorKeys>0 && rng.nextBoolean());

        // Some players support the server mostly by buying keys rather than
        // climbing the rank ladder. Risk-tolerant players do this much more.
        if(gambler && rng.nextInt(100)<62) {
            int keys=p.riskTolerance>=85 ? (5+rng.nextInt(6)) : (2+rng.nextInt(4));
            double usd=keys*2.5;
            p.pendingDonorKeys=Math.min(64,p.pendingDonorKeys+keys);
            p.donationUsd+=usd;
            plugin.broadcastCommunityEvent("&6[Store] &f"+p.name+" &7purchased &e"+keys+" Donor Keys&7.");
            if(keys>=7) recordHistory("STORE",4,p.name+" bought "+keys+" Donor Keys",p.faction,p.name);
            if(rng.nextInt(100)<55) enqueue(p.name,oneOf("im opening keys at spawn","these keys better pay out","one more key bro"),false);
            return;
        }

        if(p.donorLevel>=4) return;
        p.donorLevel=Math.min(4,p.donorLevel+1);
        double paid=p.donorLevel==1?15.0:(p.donorLevel==2?20.0:(p.donorLevel==3?25.0:40.0));
        p.donationUsd+=paid;
        String rank=donorName(p.donorLevel);
        plugin.broadcastCommunityEvent("&6[Store] &f"+p.name+" &7upgraded to &f"+rank+"&7.");
        if(p.donorLevel>=3) recordHistory("RANK",5,p.name+" became "+rank,p.faction,p.name);
        if(p.ownerAffinity>=20) enqueue(p.name,oneOf("worth it","server has been fun","finally got "+rank.toLowerCase(Locale.ENGLISH)),false);
        else if(rng.nextBoolean()) enqueue(p.name,"got "+rank.toLowerCase(Locale.ENGLISH)+" lets go",false);
    }

    boolean upgradeDonorRankFromReward(String name,String source) {
        SimPlayer p=players.get(key(name));
        if(p==null || p.donorLevel>=4) return false;
        p.donorLevel++;
        String rank=donorName(p.donorLevel);
        plugin.broadcastCommunityEvent("&d[Crates] &f"+p.name+" &7won a &f"+rank+" &7rank upgrade from "+source+"&7.");
        recordHistory("RANK",7,p.name+" won "+rank+" from "+source,p.faction,p.name);
        rememberRelationship(relationship(p.name,plugin.getConfig().getString("owner.name","Owner"),true),
            "won "+rank+" from a "+source+" reward");
        save();
        return true;
    }

    private String donorName(int level) {
        if(level>=4) return "Platinum";
        if(level==3) return "Gold";
        if(level==2) return "Silver";
        if(level==1) return "Basic";
        return "Member";
    }

    private double initialDonationUsd(int donorLevel) {
        if(donorLevel>=4) return 100.0;
        if(donorLevel==3) return 60.0;
        if(donorLevel==2) return 35.0;
        if(donorLevel==1) return 15.0;
        return 0.0;
    }

    private int donorInfluence(SimPlayer p) {
        if(p==null) return 0;
        int rank;
        if(p.donorLevel>=4) rank=82;
        else if(p.donorLevel==3) rank=48;
        else if(p.donorLevel==2) rank=24;
        else if(p.donorLevel==1) rank=8;
        else rank=0;
        int spend=(int)Math.min(38.0,Math.floor(Math.max(0.0,p.donationUsd)/8.0));
        return rank+spend;
    }

    private void maybeStaffReport() {
        List<SimPlayer> staff=new ArrayList<SimPlayer>();
        List<SimPlayer> suspects=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) {
            if(!p.logicalOnline) continue;
            if("MOD".equals(p.staffRole)||"ADMIN".equals(p.staffRole)) staff.add(p);
            else if(!plugin.isCreatorIdentity(p.name) && p.bannedUntil<=System.currentTimeMillis()) suspects.add(p);
        }
        if(staff.isEmpty()||suspects.isEmpty()) return;

        SimPlayer mod=staff.get(rng.nextInt(staff.size()));
        SimPlayer suspect=suspects.get(rng.nextInt(suspects.size()));
        String[] lines={
            "watching "+suspect.name+" mining rn, got an xray report",
            "checking "+suspect.name+" for xray, dont ban yet",
            "report on "+suspect.name+" says reach but i need more proof",
            "im spectating "+suspect.name+", looks weird but not enough yet",
            "someone reported "+suspect.name+" for autoclicking"
        };
        plugin.sendSimulatedStaffChat(mod.name,lines[rng.nextInt(lines.length)]);
    }

    private void maybeModerationAction() {
        List<SimPlayer> staff=new ArrayList<SimPlayer>();
        List<SimPlayer> candidates=new ArrayList<SimPlayer>();
        long now=System.currentTimeMillis();
        for(SimPlayer p:players.values()) {
            if("MOD".equals(p.staffRole)||"ADMIN".equals(p.staffRole)) staff.add(p);
            else if(p.logicalOnline && !plugin.isCreatorIdentity(p.name) && p.bannedUntil<=now) candidates.add(p);
        }
        if(staff.isEmpty()||candidates.isEmpty()) return;

        SimPlayer mod=staff.get(rng.nextInt(staff.size()));
        SimPlayer target=candidates.get(rng.nextInt(candidates.size()));

        // Actual bans are intentionally rare even when a report is generated.
        if(rng.nextInt(100)<28) {
            long mins=10+rng.nextInt(21);
            target.bannedUntil=now+mins*60000L;
            target.logicalOnline=false;
            target.currentGoal="banned";
            plugin.broadcastCommunityEvent("&c[Staff] &f"+target.name+" &7was temporarily banned for &f"+mins+"m&7.");
            recordHistory("MODERATION",6,target.name+" was temporarily banned by "+mod.name,target.faction,target.name,mod.name);
            plugin.sendSimulatedStaffChat(mod.name,"banned "+target.name+" after watching them, logs looked bad");
        } else {
            plugin.sendSimulatedStaffChat(mod.name,"cleared "+target.name+", not enough evidence to punish");
        }
    }

    private void maybeOwnerCommunityMessage() {
        String owner=plugin.getConfig().getString("owner.name","");
        Player ownerPlayer=owner.isEmpty()?null:Bukkit.getPlayerExact(owner);
        if(ownerPlayer==null) return;

        List<SimPlayer> online=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) if(p.logicalOnline && p.bannedUntil<=System.currentTimeMillis()) online.add(p);
        if(online.isEmpty()) return;
        SimPlayer p=online.get(rng.nextInt(online.size()));

        String msg;
        if(p.ownerAffinity>=45) {
            String[] x={"server is actually fun rn","thanks for fixing the lag","you should keep this map up","can you add more end fights","bases look way better now"};
            msg=x[rng.nextInt(x.length)];
        } else if(p.ownerAffinity>=5) {
            String[] x={"can you look at the lag at end","spawn pvp has been active","can we get more flat warzone","shop prices feel a little high","server feels active today"};
            msg=x[rng.nextInt(x.length)];
        } else {
            String[] x={"warzone still needs work","pots feel expensive","some bases are weird","end gets camped too hard","please dont change pvp again"};
            msg=x[rng.nextInt(x.length)];
        }
        plugin.sendSimulatedPrivate(ownerPlayer,p.name,msg);
    }

    private String oneOf(String... xs) {
        if(xs==null||xs.length==0) return "";
        return xs[rng.nextInt(xs.length)];
    }

    private void advance(SimFaction f) {
        f.actionCounter++;
        produce(f);

        // Once a faction has storage, excess materials become working capital.
        // Keep strategic reserves first; only liquidate genuine surplus.
        if (f.storage && f.stage.ordinal() >= Stage.ECONOMY.ordinal() && f.actionCounter % 4 == 0) {
            liquidateSurplus(f);
        }

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
                // SOTW priority #1 after claiming: get a roofed, fence-gated
                // upper shell online before protection expires.  The expensive
                // underground core can continue while the faction is rushed.
                if(!f.surfaceQueued) {
                    if(surfaceMaterialsReady(f)) {
                        consumeSurfaceMaterials(f);
                        plugin.queueSimSurfaceBuild(f.name,f.basePreset,f.baseX,f.baseY,f.baseZ);
                        f.surfaceQueued=true;
                    } else {
                        buyMissingInfrastructure(f);
                        break;
                    }
                }

                if (baseMaterialsReady(f)) {
                    consumeBaseMaterials(f);
                    f.buildTarget = baseBuildTarget(f.basePreset);
                    f.buildProgress = 0;
                    f.stage = Stage.BUILD_STARTER;
                    if (!f.baseQueued) {
                        plugin.queueSimBaseBuild(f.name, f.basePreset, f.trapPreset, f.baseX, f.baseY, f.baseZ);
                        f.baseQueued = true;
                        if ("TRAPPER".equals(f.archetype) && f.trapPreset != null && !"none".equalsIgnoreCase(f.trapPreset)) {
                            f.specialTrapBuilt = true;
                        }
                    }
                } else if(f.actionCounter%2==0) {
                    buyMissingInfrastructure(f);
                }
                break;

            case BUILD_STARTER:
                f.buildProgress = Math.min(f.buildTarget, f.buildProgress + factionBuildWork(f));
                if (f.buildProgress >= f.buildTarget) {
                    f.storage = true;
                    f.storageTier=Math.max(1,f.storageTier);
                    f.farmBuilt=true; // the SOTW core includes cane + wart/melon farms
                    seedVisibleStorage(f);
                    f.stage = Stage.ECONOMY;
                }
                break;

            case ECONOMY:
                maybeUpgradeInfrastructure(f);
                if (!f.farmBuilt) {
                    SimPlayer farmer = firstJobMember(f,"farmer");
                    if (farmer != null && farmer.farmReady) {
                        plugin.queueSimFarmBuild(f.name, farmer.farmCrop, f.baseX, f.baseY, f.baseZ);
                        f.farmBuilt = true;
                    }
                }
                // Mature factions nearly always prioritize potion infrastructure.
                if (f.iron >= 35 && f.stone >= 96 && f.obsidian >= 8) {
                    f.stage = Stage.BREWER;
                } else if (f.actionCounter % 4 == 0) {
                    buyMissingInfrastructure(f);
                }
                break;

            case BREWER:
                if (!f.brewer) {
                    // Build the physical six-lane 1.8 HCF brewery. Remote bases
                    // remain COLD; loaded brewer chunks become physically active.
                    if (f.iron >= 35 && f.stone >= 96) {
                        f.iron -= 35;
                        f.stone -= 96;
                        mirrorConsumeFromStorage(f,Material.IRON_INGOT,35);
                        mirrorConsumeFromStorage(f,Material.COBBLESTONE,96);
                        f.brewer = true;
                        plugin.queueSimBrewerBuild(f.name,f.basePreset,f.baseX,f.baseY,f.baseZ);
                    }
                }
                if (f.brewer) f.stage = Stage.GEARING;
                break;

            case GEARING:
                craftBooksAndGear(f);
                brewCombatStock(f);
                // A real HCF faction does not wait until every member owns a
                // complete endgame loadout before anyone leaves base. As soon as
                // it can field one genuine combat loadout, PvP and gearing run
                // concurrently.
                if (combatStockSlots(f) > 0) f.stage = Stage.PVP_READY;
                break;

            case PVP_READY:
                maybeUpgradeInfrastructure(f);
                brewCombatStock(f);
                // They continue farming/mining/economy rather than becoming PvP-only bots.
                if (f.p4Sets < Math.max(1, f.members.size() - 1)) craftBooksAndGear(f);
                break;
        }
    }

    private void produce(SimFaction f) {
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p == null || !p.logicalOnline) continue;

            // A HOT body doing physical resource work deposits its real
            // inventory through /simworker deposit. Do not also credit the same
            // worker's COLD mining/building roll in the same period.
            boolean embodied=Bukkit.getPlayerExact(p.name)!=null;
            if (embodied && ("mine".equals(p.currentGoal) || "gather".equals(p.currentGoal) ||
                "supply".equals(p.currentGoal) || "build".equals(p.currentGoal))) {
                continue;
            }

            if ("mine".equals(p.currentGoal) || "gather".equals(p.currentGoal) || "supply".equals(p.currentGoal)) {
                int urgency=(!f.surfaceQueued && sotwMillisLeft()<=180000L)?2:1;
                if("gather".equals(p.currentGoal) || "supply".equals(p.currentGoal)) {
                    // General SOTW gathering must actually produce logs; the old
                    // abstraction only produced stone/iron and could deadlock a
                    // resource-honest surface build waiting for wood forever.
                    int woodMade=urgency*(10+("builder".equals(p.preferredJob)?5:0)+rng.nextInt(12));
                    int stoneMade=urgency*(8+rng.nextInt(12));
                    f.wood+=woodMade;
                    f.stone+=stoneMade;
                    if(f.storage) {
                        mirrorDepositToStorage(f,Material.LOG,woodMade);
                        mirrorDepositToStorage(f,Material.COBBLESTONE,stoneMade);
                    }
                } else {
                    int minerBonus = "miner".equals(p.preferredJob) ? 8 : 0;
                    int stoneMade=urgency*(20 + minerBonus + rng.nextInt(18));
                    int ironMade=urgency*(2 + ("miner".equals(p.preferredJob) ? 2 : 0) + rng.nextInt(4));
                    f.stone += stoneMade;
                    f.iron += ironMade;
                    if(f.storage) {
                        mirrorDepositToStorage(f,Material.COBBLESTONE,stoneMade);
                        mirrorDepositToStorage(f,Material.IRON_INGOT,ironMade);
                    }
                    f.xp += 2 + rng.nextInt(4);
                    if (rng.nextInt(100) < (12 + p.economicIq / 6)) {
                        f.diamonds += 1;
                        if(f.storage) mirrorDepositToStorage(f,Material.DIAMOND,1);
                    }
                    if (rng.nextInt(100) < (18 + p.economicIq / 7)) {
                        int obby=1+rng.nextInt(2);
                        f.obsidian += obby;
                        if(f.storage) mirrorDepositToStorage(f,Material.OBSIDIAN,obby);
                    }
                }
            } else if ("farm".equals(p.currentGoal)) {
                // Farming is handled by SimEconomyModel so cash/items are conserved.
            } else if ("brew".equals(p.currentGoal) || "gear".equals(p.currentGoal)) {
                // Brewing/gearing consume explicit resources in their dedicated models.
            } else if ("build".equals(p.currentGoal)) {
                int woodMade=8+rng.nextInt(10);
                int stoneMade=8+rng.nextInt(14);
                f.wood += woodMade;
                f.stone += stoneMade;
                if(f.storage) {
                    mirrorDepositToStorage(f,Material.LOG,woodMade);
                    mirrorDepositToStorage(f,Material.COBBLESTONE,stoneMade);
                }
            } else if ("recruit".equals(p.currentGoal) || "social".equals(p.currentGoal) || "trade".equals(p.currentGoal)) {
                // Social/economic actions intentionally produce no free materials.
            } else {
                int woodMade=4+rng.nextInt(8);
                int stoneMade=4+rng.nextInt(10);
                f.wood += woodMade;
                f.stone += stoneMade;
                if(f.storage) {
                    mirrorDepositToStorage(f,Material.LOG,woodMade);
                    mirrorDepositToStorage(f,Material.COBBLESTONE,stoneMade);
                }
            }
        }

        // Some faction wealth is spent on missing ingredients/resources instead of appearing from nowhere.
        if (f.treasury > 250 && f.iron < 24) {
            double unit = plugin.buyUnitPrice("iron");
            int buy = Math.min(12, (int)(f.treasury / unit));
            f.iron += buy;
            f.treasury -= buy * unit;
        }
    }

    private void buyMissingInfrastructure(SimFaction f) {
        if (f.treasury < 40) return;

        if(!f.surfaceQueued) {
            int need=surfaceMaterialCost(f)[4]-f.glass;
            if(need>0) {
                double unit=plugin.buyUnitPrice("glass");
                int n=Math.min(need,(int)(f.treasury/unit));
                if(n>0) { f.glass+=n; f.treasury-=n*unit; }
            }
        }

        if (f.obsidian < 14 && f.treasury >= plugin.buyUnitPrice("obsidian")) {
            double unit = plugin.buyUnitPrice("obsidian");
            int n = Math.min(14 - f.obsidian, (int)(f.treasury / unit));
            if (n > 0) { f.obsidian += n; f.treasury -= n * unit; }
        }
        if (f.iron < 40 && f.treasury >= plugin.buyUnitPrice("iron")) {
            double unit = plugin.buyUnitPrice("iron");
            int n = Math.min(40 - f.iron, (int)(f.treasury / unit));
            if (n > 0) { f.iron += n; f.treasury -= n * unit; }
        }
    }

    private void maybeUpgradeInfrastructure(SimFaction f) {
        if(!f.storage || f.baseX==0 && f.baseZ==0) return;

        if(f.storageTier<2 && f.treasury>=1500.0 && f.wood>=48 && f.stone>=96) {
            f.treasury-=650.0; f.wood-=48; f.stone-=96;
            f.storageTier=2;
            plugin.queueSimStorageUpgrade(f.name,f.basePreset,2,f.baseX,f.baseY,f.baseZ);
        } else if(f.storageTier<3 && f.treasury>=4500.0 && f.wood>=72 && f.stone>=144) {
            f.treasury-=1400.0; f.wood-=72; f.stone-=144;
            f.storageTier=3;
            plugin.queueSimStorageUpgrade(f.name,f.basePreset,3,f.baseX,f.baseY,f.baseZ);
        }

        boolean large=f.members.size()>=4 || f.powerFaction;
        double flintCost=Math.max(1500.0,plugin.buyUnitPrice("flintsteel"));
        if(large && f.brewer && !f.netherPortal && f.treasury>=Math.max(3500.0,flintCost) && f.obsidian>=14) {
            // Obsidian is consumed from faction stock; the expensive shop-only
            // activation tool represents the convenience premium of fast Nether access.
            f.treasury-=flintCost;
            f.obsidian-=14;
            f.netherPortal=true;
            plugin.queueSimPortalBuild(f.name,f.basePreset,"nether",f.baseX,f.baseY,f.baseZ);
        }

        double endCost=12.0*Math.max(1200.0,plugin.buyUnitPrice("endframe"))+
            12.0*Math.max(250.0,plugin.buyUnitPrice("eyeofender"));
        if(large && f.netherPortal && !f.endPortal && f.treasury>=endCost) {
            f.treasury-=endCost;
            f.endPortal=true;
            plugin.queueSimPortalBuild(f.name,f.basePreset,"end",f.baseX,f.baseY,f.baseZ);
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
        // XP comes from mining/activity. Books and lapis are explicit purchases when needed.
        int targetBooks = Math.max(20, f.members.size() * 20);
        if (f.books < targetBooks && f.treasury >= plugin.buyUnitPrice("book")) {
            int n = Math.min(targetBooks - f.books, (int)(f.treasury / plugin.buyUnitPrice("book")));
            f.books += n;
            f.treasury -= n * plugin.buyUnitPrice("book");
        }
        int targetLapis = Math.max(16, f.members.size() * 12);
        if (f.lapis < targetLapis && f.treasury >= plugin.buyUnitPrice("lapis")) {
            int n = Math.min(targetLapis - f.lapis, (int)(f.treasury / plugin.buyUnitPrice("lapis")));
            f.lapis += n;
            f.treasury -= n * plugin.buyUnitPrice("lapis");
        }

        // P2/S2 is the hard map-wide combat ceiling. We still require enough
        // low-level books/lapis/XP to represent imperfect enchanting outcomes.
        int protCostBooks = 4;
        int sharpCostBooks = 4;

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

        if (f.p4Sets < f.members.size() && f.diamonds >= 24 && f.books >= protCostBooks * 4 && f.lapis >= 16 && f.xp >= 16) {
            f.diamonds -= 24;
            f.books -= protCostBooks * 4;
            f.lapis -= 16;
            f.xp -= 16;
            f.p4Sets++;
        }

        if (f.sharp4Swords < f.members.size() && f.diamonds >= 2 && f.books >= sharpCostBooks && f.lapis >= 4 && f.xp >= 6) {
            f.diamonds -= 2;
            f.books -= sharpCostBooks;
            f.lapis -= 4;
            f.xp -= 6;
            f.sharp4Swords++;
        }
    }

    private void brewCombatStock(SimFaction f) {
        if (!f.brewer) return;
        plugin.registerAutoBrewerSite(f.name,f.basePreset,f.baseX,f.baseY,f.baseZ);

        int members=Math.max(1,f.members.size());

        // When the brewery chunk is visible/loaded, the physical stands become
        // authoritative. We only buy real ingredients/water bottles into the
        // faction Brewing chest and wait for actual 20-second stages.
        if(plugin.autoBrewerPhysicalActive(f.name)) {
            stockPhysicalBrewerInputs(f);
        } else {
            // COLD path: preserve 24/7 progression without loading remote chunks.
            // Costs are ingredient-batch costs; one ingredient set brews 3 pots.
            double waterCost=Math.max(1.0,plugin.buyUnitPrice("glass"));
            double healBatchCost=waterCost*3.0+plugin.buyUnitPrice("netherwart")+
                plugin.buyUnitPrice("glisteringmelon")+plugin.buyUnitPrice("glowstone")+
                plugin.buyUnitPrice("gunpowder");
            double speedBatchCost=waterCost*3.0+plugin.buyUnitPrice("netherwart")+
                plugin.buyUnitPrice("sugar")+plugin.buyUnitPrice("glowstone");

            int healNeed=Math.max(0,members*28-f.healPots);
            if(healNeed>0 && f.treasury>=healBatchCost) {
                int batches=Math.min(4,Math.min((healNeed+2)/3,(int)Math.floor(f.treasury/healBatchCost)));
                f.healPots+=batches*3;
                f.treasury-=batches*healBatchCost;
            }

            int speedNeed=Math.max(0,members*3-f.speedPots);
            if(speedNeed>0 && f.treasury>=speedBatchCost) {
                int batches=Math.min(1,Math.min((speedNeed+2)/3,(int)Math.floor(f.treasury/speedBatchCost)));
                f.speedPots+=batches*3;
                f.treasury-=batches*speedBatchCost;
            }

        }

        double pearlPrice=plugin.buyUnitPrice("pearl");
        if(f.pearls<members*8 && f.treasury>=pearlPrice) {
            int buy=Math.min(3,Math.min(members*8-f.pearls,(int)(f.treasury/pearlPrice)));
            f.pearls+=buy;
            f.treasury-=buy*pearlPrice;
        }
    }

    private void stockPhysicalBrewerInputs(SimFaction f) {
        org.bukkit.inventory.Inventory inv=factionStorageInventory(f,"brewing");
        if(inv==null) return;

        int members=Math.max(1,f.members.size());
        int healNeed=Math.max(0,members*28-f.healPots);
        int speedNeed=Math.max(0,members*3-f.speedPots);
        if(healNeed+speedNeed<=0) return;

        int activeKinds=(healNeed>0?1:0)+(speedNeed>0?1:0);
        int bottleTarget=Math.min(24,Math.max(6,activeKinds*6+(healNeed>18?6:0)));
        buyBrewerItemToTarget(f,inv,Material.POTION,(short)0,bottleTarget,"glass",1.0);

        int wartTarget=(healNeed>0?4:0)+(speedNeed>0?2:0);
        buyBrewerItemToTarget(f,inv,Material.NETHER_STALK,(short)0,wartTarget,"netherwart",1.0);
        if(healNeed>0) {
            buyBrewerItemToTarget(f,inv,Material.SPECKLED_MELON,(short)0,4,"glisteringmelon",1.0);
            buyBrewerItemToTarget(f,inv,Material.GLOWSTONE_DUST,(short)0,5,"glowstone",1.0);
            buyBrewerItemToTarget(f,inv,Material.SULPHUR,(short)0,4,"gunpowder",1.0);
        }
        if(speedNeed>0) {
            buyBrewerItemToTarget(f,inv,Material.SUGAR,(short)0,2,"sugar",1.0);
            buyBrewerItemToTarget(f,inv,Material.GLOWSTONE_DUST,(short)0,5,"glowstone",1.0);
        }
    }

    private void buyBrewerItemToTarget(SimFaction f,org.bukkit.inventory.Inventory inv,Material material,
                                       short dataValue,int target,String shopKey,double multiplier) {
        int have=countInventoryItem(inv,material,dataValue);
        int missing=Math.max(0,target-have);
        if(missing<=0) return;

        double unit=plugin.buyUnitPrice(shopKey)*Math.max(0.01,multiplier);
        if(!Double.isFinite(unit) || unit<=0.0) return;
        int can=Math.min(missing,(int)Math.floor(f.treasury/unit));
        if(can<=0) return;

        int added=0;
        for(int i=0;i<can;i++) {
            org.bukkit.inventory.ItemStack item=new org.bukkit.inventory.ItemStack(material,1,dataValue);
            if(inv.addItem(item).isEmpty()) added++;
            else break;
        }
        f.treasury-=added*unit;
    }

    private int countInventoryItem(org.bukkit.inventory.Inventory inv,Material material,short dataValue) {
        int n=0;
        for(org.bukkit.inventory.ItemStack item:inv.getContents()) {
            if(item==null || item.getType()!=material) continue;
            if(material==Material.POTION && item.getDurability()!=dataValue) continue;
            n+=item.getAmount();
        }
        return n;
    }

    int brewerNeed(String faction,String type) {
        SimFaction f=factions.get(key(faction));
        if(f==null || !f.brewer) return 0;
        int members=Math.max(1,f.members.size());
        if("heal".equalsIgnoreCase(type)) return Math.max(0,members*28-f.healPots);
        if("speed".equalsIgnoreCase(type)) return Math.max(0,members*3-f.speedPots);
        return 0;
    }

    void creditBrewedPotions(String faction,String type,int amount) {
        if(amount<=0) return;
        SimFaction f=factions.get(key(faction));
        if(f==null) return;
        if("heal".equalsIgnoreCase(type)) f.healPots+=amount;
        else if("speed".equalsIgnoreCase(type)) f.speedPots+=amount;
        save();
    }

    private void equipRoleArmorFromInventory(Player body,CombatClass type) {
        if(body==null) return;
        org.bukkit.inventory.PlayerInventory inv=body.getInventory();
        Material[][] options=armorOptionsFor(type);
        String[] suffixes={"_HELMET","_CHESTPLATE","_LEGGINGS","_BOOTS"};

        for(int part=0;part<4;part++) {
            org.bukkit.inventory.ItemStack current=getArmorPiece(inv,part);
            org.bukkit.inventory.ItemStack best=current;
            int bestValue=(current!=null && armorAllowed(current.getType(),options[part]))
                ? itemCombatValue(current) : -1;
            int bestSlot=-1;

            for(int slot=0;slot<36;slot++) {
                org.bukkit.inventory.ItemStack item=inv.getItem(slot);
                if(item==null || !item.getType().name().endsWith(suffixes[part])) continue;
                if(!armorAllowed(item.getType(),options[part])) continue;
                int value=itemCombatValue(item);
                if(value>bestValue) { best=item; bestValue=value; bestSlot=slot; }
            }

            if(bestSlot>=0 && best!=null) {
                org.bukkit.inventory.ItemStack one=best.clone();
                one.setAmount(1);
                if(best.getAmount()<=1) inv.setItem(bestSlot,null);
                else { best.setAmount(best.getAmount()-1); inv.setItem(bestSlot,best); }
                if(current!=null) inv.addItem(current);
                setArmorPiece(inv,part,one);
            }
        }
        body.updateInventory();
    }

    private boolean hasPersonalPhysicalCombatKit(SimPlayer p) {
        if(p==null || p.combatClass!=CombatClass.DIAMOND) return false;
        Player body=Bukkit.getPlayerExact(p.name);
        if(body==null || !body.isOnline()) return false;

        equipRoleArmorFromInventory(body,p.combatClass);
        org.bukkit.inventory.PlayerInventory inv=body.getInventory();
        boolean fullDiamond=
            inv.getHelmet()!=null && inv.getHelmet().getType()==Material.DIAMOND_HELMET &&
            inv.getChestplate()!=null && inv.getChestplate().getType()==Material.DIAMOND_CHESTPLATE &&
            inv.getLeggings()!=null && inv.getLeggings().getType()==Material.DIAMOND_LEGGINGS &&
            inv.getBoots()!=null && inv.getBoots().getType()==Material.DIAMOND_BOOTS;

        int swords=0;
        for(org.bukkit.inventory.ItemStack item:allPhysicalItems(body)) {
            if(item==null) continue;
            if(item.getType()==Material.DIAMOND_SWORD || item.getType()==Material.IRON_SWORD) swords+=item.getAmount();
        }
        int heals=countPotion(body,(short)16421);
        int pearls=countMaterial(body,Material.ENDER_PEARL);
        return fullDiamond && swords>=1 && heals>=4 && pearls>=2;
    }

    private int personalCombatSlots(SimFaction f) {
        if(f==null) return 0;
        int n=0;
        for(String member:f.members) {
            SimPlayer p=players.get(key(member));
            if(hasPersonalPhysicalCombatKit(p)) n++;
        }
        return n;
    }

    private boolean combatReady(SimFaction f) {
        // Deep-stock readiness remains useful for strategy/UI, but no longer
        // blocks the first geared member from roaming.
        return combatStockSlots(f) >= Math.max(1,Math.min(f.members.size(),3));
    }

    private int combatStockSlots(SimFaction f) {
        if(f==null) return 0;
        int personal=personalCombatSlots(f);
        int gear=Math.max(0,Math.min(f.p4Sets,f.sharp4Swords))+
            Math.max(0,f.bardSets)+Math.max(0,f.archerSets)+Math.max(0,f.rogueSets)+
            Math.max(0,f.iron/24);
        int consumables=Math.min(
            Math.min(Math.max(0,f.healPots/24),Math.max(0,f.pearls/8)),
            Math.max(0,f.speedPots/2)
        );
        return Math.max(0,personal+Math.min(gear,consumables));
    }

    private boolean canFieldCombatant(SimFaction f,SimPlayer p) {
        if(f==null || p==null) return false;
        if(hasPersonalPhysicalCombatKit(p)) return true;
        if(combatStockSlots(f)<=0) return false;
        if(f.healPots<24 || f.pearls<8 || f.speedPots<2) return false;
        if(p.combatClass==CombatClass.DIAMOND) return f.p4Sets>0 && f.sharp4Swords>0;
        if(p.combatClass==CombatClass.BARD) return f.bardSets>0;
        if(p.combatClass==CombatClass.ARCHER) return f.archerSets>0;
        if(p.combatClass==CombatClass.ROGUE) return f.rogueSets>0;
        return f.iron>=24;
    }

    private PvpIntent pvpIntentFor(SimPlayer p,SimFaction f) {
        if(p==null || f==null || !p.logicalOnline || !canFieldCombatant(f,p)) return PvpIntent.AVOID;
        if(sotwProtectionActive() || f.recoveryMode || plugin.factionRaidable(f.name) ||
           plugin.factionDtr(f.name)<=getDtrSafetyFloor(f)) return PvpIntent.AVOID;

        int fightDrive=p.aggression+p.riskTolerance+p.mechanics/2+p.pvpIq/2+
            Math.min(40,p.reputation/2);
        int teamDrive=p.teamwork+p.loyalty+p.gameSense/2+
            ("leader".equals(p.role)?p.leadership/2:0);
        if("PVP".equals(f.archetype)) fightDrive+=28;
        if("TRAPPER".equals(f.archetype)) {
            fightDrive+=18;
            if(p.gameSense+p.patience>=120) return PvpIntent.TRAP_PLAY;
        }
        if(f.campTarget!=null && !f.campTarget.isEmpty()) {
            fightDrive+=18;
            teamDrive+=12;
        }

        if(fightDrive<105) return PvpIntent.AVOID;
        if(combatStockSlots(f)>=3 && teamDrive>=150) return PvpIntent.TEAMFIGHT;
        if(combatStockSlots(f)>=2 && teamDrive>=112) return PvpIntent.SMALL_TEAM;
        return PvpIntent.SOLO_HUNT;
    }

    private int desiredPartySize(PvpIntent intent,SimFaction f) {
        int stock=Math.max(1,combatStockSlots(f));
        if(intent==PvpIntent.TEAMFIGHT) return Math.min(5,Math.max(3,stock));
        if(intent==PvpIntent.SMALL_TEAM) return Math.min(3,Math.max(2,stock));
        return 1;
    }

    private int factionDesiredPvpSize(SimFaction f) {
        int solo=0,small=0,team=0,trap=0,eligible=0;
        for(String member:f.members) {
            SimPlayer p=players.get(key(member));
            if(p==null || !p.logicalOnline) continue;
            PvpIntent intent=pvpIntentFor(p,f);
            if(intent==PvpIntent.AVOID) continue;
            eligible++;
            if(intent==PvpIntent.TEAMFIGHT) team++;
            else if(intent==PvpIntent.SMALL_TEAM) small++;
            else if(intent==PvpIntent.TRAP_PLAY) trap++;
            else solo++;
        }
        int cap=Math.min(Math.min(eligible,combatStockSlots(f)),hotCombatPerFactionCap());
        if(cap<=0) return 0;
        if(team>=2 && cap>=3) return Math.min(5,cap);
        if((small+team)>=2 && cap>=2) return Math.min(3,cap);
        if(trap>0 && cap>=2) return Math.min(2,cap);
        return 1;
    }

    boolean shouldSeekPvp(String name) {
        SimPlayer p = players.get(key(name));
        if (p == null || p.faction.isEmpty()) return false;
        SimFaction f = factions.get(key(p.faction));
        return pvpIntentFor(p,f)!=PvpIntent.AVOID;
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

    private void enqueue(String name, String message, boolean fast) {
        if (name == null || message == null || message.trim().isEmpty()) return;
        if (pendingChat.size() >= 18) return;
        pendingChat.addLast(new ChatEvent(name,message,fast));
        rememberPublic(name,message);
    }

    private String rivalryKey(String a, String b) {
        String x = key(a), y = key(b);
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    private void recordRivalry(String a, String b, int amount) {
        if (a == null || b == null || a.equalsIgnoreCase(b)) return;
        String k = rivalryKey(a,b);
        rivalries.put(k, Math.min(100, (rivalries.containsKey(k) ? rivalries.get(k) : 0) + Math.max(1,amount)));
    }

    private ChatEvent rivalryChatEvent() {
        if (rivalries.isEmpty()) return null;

        List<Map.Entry<String,Integer>> hot = new ArrayList<Map.Entry<String,Integer>>();
        for (Map.Entry<String,Integer> e : rivalries.entrySet()) if (e.getValue() >= 12) hot.add(e);
        if (hot.isEmpty()) return null;

        Map.Entry<String,Integer> e = hot.get(rng.nextInt(hot.size()));
        String[] parts = e.getKey().split("\\|",2);
        if (parts.length != 2) return null;
        SimFaction a = factions.get(parts[0]);
        SimFaction b = factions.get(parts[1]);
        if (a == null || b == null) return null;
        SimPlayer pa = players.get(key(a.leader));
        SimPlayer pb = players.get(key(b.leader));
        if (pa == null || pb == null) return null;

        String[] first = {
            b.name + " only fights with numbers",
            "why are " + b.name + " always outside our base",
            "gg " + b.name + " but stop chasing for 10 mins",
            b.name + " you guys love jumping people"
        };
        String[] reply = {
            "then dont come out",
            "you chased us first lol",
            "gg stop crying",
            "we live next to you what do you expect"
        };
        String[] last = {"fair lol","come outside then","just wait","alright gg"};

        enqueue(pb.name, reply[rng.nextInt(reply.length)], true);
        if (e.getValue() >= 35 && rng.nextBoolean()) enqueue(pa.name, last[rng.nextInt(last.length)], true);
        return new ChatEvent(pa.name, first[rng.nextInt(first.length)], false);
    }

    private void queueDeathConversation(SimPlayer victim, SimPlayer killer) {
        if (rng.nextInt(100) < 45) {
            String[] a = {"you guys jumped me","gg i had no pots","why were all of you there","i was trying to kite","rip my set"};
            String[] b = killer.reputation>=35
                ? new String[]{"gg","thanks for the set","another set lol","you pushed us first","we saw you outside"}
                : new String[]{"gg","you pushed us first","we saw you outside","shouldve went home","got the set"};
            enqueue(victim.name,a[rng.nextInt(a.length)],true);
            enqueue(killer.name,b[rng.nextInt(b.length)],true);
        }

        SimFaction vf = factions.get(key(victim.faction));
        if (vf != null && vf.members.size() >= 2 && rng.nextInt(100) < 12) {
            SimPlayer mate = players.get(key(vf.members.get(rng.nextInt(vf.members.size()))));
            if (mate != null && !mate.name.equalsIgnoreCase(victim.name)) {
                enqueue(mate.name, "why did you chase that", true);
                enqueue(victim.name, rng.nextBoolean() ? "thought you were behind me" : "i was trying to get out", true);
            }
        }
    }

    private void queueCreatorDeathReactions(String creator) {
        List<String> fans = plugin.getConfig().getStringList("sim-chat.fans");
        if (fans.isEmpty()) return;
        Collections.shuffle(fans,rng);
        int count = Math.min(fans.size(), 1 + rng.nextInt(2));
        String[] lines = {"no way " + creator + " died","who killed " + creator,"rip " + creator,"gg " + creator};
        for (int i=0;i<count;i++) enqueue(fans.get(i), lines[rng.nextInt(lines.length)], true);
    }

    private void queueCreatorKillReactions(String creator) {
        List<String> fans = plugin.getConfig().getStringList("sim-chat.fans");
        if (fans.isEmpty() || rng.nextInt(100) >= 55) return;
        String fan = fans.get(rng.nextInt(fans.size()));
        String[] lines = {"that combo","gg","" + creator + " is farming","rip"};
        enqueue(fan,lines[rng.nextInt(lines.length)],true);
    }

    private SimPlayer findReadyFighter() {
        List<SimPlayer> ready = new ArrayList<SimPlayer>();
        for (SimPlayer p : players.values()) if (shouldSeekPvp(p.name)) ready.add(p);
        if (ready.isEmpty()) return null;
        return ready.get(rng.nextInt(ready.size()));
    }

    private SimPlayer findSeller(String item) {
        SimPlayer best = null;
        for (SimPlayer p : players.values()) {
            if (getStock(p,item) <= 0) continue;
            if (best == null || getStock(p,item) > getStock(best,item)) best = p;
        }
        return best;
    }

    private SimPlayer findInterestedBuyer(String item) {
        List<SimPlayer> candidates = new ArrayList<SimPlayer>();
        for (SimPlayer p : players.values()) {
            if (p.faction.isEmpty()) continue;
            SimFaction f = factions.get(key(p.faction));
            if (f == null || f.treasury < 50) continue;
            if (item.equals("pearl") && f.pearls < Math.max(8,f.members.size()*8)) candidates.add(p);
            else if (item.equals("iron") && f.iron < 35) candidates.add(p);
            else if (item.equals("obsidian") && f.obsidian < 8) candidates.add(p);
            else if (item.equals("healthpot") && f.healPots < f.members.size()*24) candidates.add(p);
            else if (item.equals("cane") && ("farmer".equals(p.preferredJob) || f.books < 20)) candidates.add(p);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private int suggestedTradeQty(String item, SimPlayer p, boolean buying) {
        if (item.equals("pearl")) return 8;
        if (item.equals("healthpot")) return 6;
        if (item.equals("iron")) return 16;
        if (item.equals("obsidian")) return 8;
        if (item.equals("cane") || item.equals("cactus")) return 64;
        return 16;
    }

    private int fairAiBuyUnit(String item) {
        if (item.equals("pearl")) return 145 + rng.nextInt(11);
        if (item.equals("healthpot")) return 90 + rng.nextInt(21);
        if (item.equals("iron")) return 10 + rng.nextInt(4);
        if (item.equals("obsidian")) return 22 + rng.nextInt(5);
        if (item.equals("cane")) return 4;
        if (item.equals("cactus")) return 3;
        return 5;
    }

    private int fairAiSellUnit(String item) {
        if (item.equals("pearl")) return 150 + rng.nextInt(11);
        if (item.equals("healthpot")) return 105 + rng.nextInt(16);
        if (item.equals("iron")) return 13 + rng.nextInt(3);
        if (item.equals("obsidian")) return 25 + rng.nextInt(4);
        if (item.equals("cane")) return 4;
        if (item.equals("cactus")) return 3;
        return 6;
    }

    private String itemFromText(String lower) {
        if (lower.contains("pearl")) return "pearl";
        if (lower.contains("heal pot") || lower.contains("health pot") || lower.contains("pots")) return "healthpot";
        if (lower.contains("obsidian") || lower.contains("obby")) return "obsidian";
        if (lower.contains("iron")) return "iron";
        if (lower.contains("sugar cane") || lower.contains("cane")) return "cane";
        if (lower.contains("cactus")) return "cactus";
        return null;
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
        int schema = data.getInt("meta.schema", 0);
        if (schema < 4) {
            plugin.resetSimFactionAuthority(Arrays.asList(PLAYER_NAMES));
            seed();
            save();
            return;
        }

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
            p.factionTitle = s.getString("faction-title","leader".equalsIgnoreCase(p.role)?"leader":"member");
            p.preferredJob = s.getString("preferred-job", p.role);
            try { p.combatClass = CombatClass.valueOf(s.getString("combat-class", "DIAMOND")); } catch (Exception ignored) {}
            p.leaderCandidate = s.getBoolean("leader-candidate", false);
            p.underdogLeader = s.getBoolean("underdog-leader", false);
            p.balance = s.getDouble("balance", 500);
            p.skill = s.getInt("skill", 50);
            p.mechanics = s.getInt("mechanics",combatTrait(p.name,"mechanics",p.skill,18));
            p.pvpIq = s.getInt("pvp-iq",combatTrait(p.name,"pvp-iq",p.skill,22));
            p.gameSense = s.getInt("game-sense",combatTrait(p.name,"game-sense",Math.max(35,p.skill-3),26));
            applyCreatorCombatOverrides(p);
            p.skill = overallCombatSkill(p);
            p.aggression = s.getInt("aggression", 50);
            p.bargaining = s.getInt("bargaining", 50);
            p.leadership = s.getInt("leadership", 50);
            p.composure = s.getInt("composure", leadershipTrait(p.name,"composure",p.leadership));
            p.charisma = s.getInt("charisma", leadershipTrait(p.name,"charisma",50));
            p.decisiveness = s.getInt("decisiveness", leadershipTrait(p.name,"decisiveness",p.leadership));
            p.standards = s.getInt("standards", leadershipTrait(p.name,"standards",55));
            p.politicalIq = s.getInt("political-iq", leadershipTrait(p.name,"political",50));
            p.leaderExperience = s.getInt("leader-experience",0);
            p.duelWins = s.getInt("duel-wins",0);
            p.duelLosses = s.getInt("duel-losses",0);
            p.teamwork = s.getInt("teamwork", 50);
            p.economicIq = s.getInt("economic-iq", 0);
            p.loyalty = s.getInt("loyalty", 45 + rng.nextInt(51));
            p.riskTolerance = s.getInt("risk-tolerance", 25 + rng.nextInt(71));
            p.sociability = s.getInt("sociability", 25 + rng.nextInt(71));
            p.patience = s.getInt("patience", 25 + rng.nextInt(71));
            p.reputation = s.getInt("reputation", 0);
            p.kills = s.getInt("kills", 0);
            p.deaths = s.getInt("deaths", 0);
            p.donorLevel = s.getInt("donor-level", initialDonorLevel(p.name));
            applyCreatorOpeningAccess(p);
            p.donationUsd = s.getDouble("donation-usd", initialDonationUsd(p.donorLevel));
            p.staffRole = s.getString("staff-role", "");
            p.ownerAffinity = s.getInt("owner-affinity", rng.nextInt(31)-5);
            p.moderationTrust = s.getInt("moderation-trust", 30+rng.nextInt(51));
            p.bannedUntil = s.getLong("banned-until", 0L);
            p.communityJoinedTick = s.getLong("community-joined-tick", 0L);
            p.pendingVoteKeys = s.getInt("pending-vote-keys",0);
            p.pendingDonorKeys = s.getInt("pending-donor-keys",p.donorLevel>0?1:0);
            p.lastVoteAt = s.getLong("last-vote-at",0L);
            p.lastDonorKeyAt = s.getLong("last-donor-key-at",0L);
            p.nextDuelRequestAt = s.getLong("next-duel-request-at",0L);
            p.logicalOnline = s.getBoolean("logical-online", rng.nextInt(100)<45) && p.bannedUntil<=System.currentTimeMillis();
            p.sessionTicksLeft = s.getInt("session-ticks-left", Math.max(2,5+rng.nextInt(20)));
            p.nextGoalTick = s.getLong("next-goal-tick", 0L);
            p.currentGoal = s.getString("current-goal", "idle");
            p.farmCrop = s.getString("farm-crop", "");
            p.farmCells = s.getInt("farm-cells", 0);
            p.farmInvestment = s.getDouble("farm-investment", 0.0);
            p.farmCycles = s.getLong("farm-cycles", 0L);
            p.farmReady = s.getBoolean("farm-ready", false);
            ConfigurationSection st = s.getConfigurationSection("stock");
            if (st != null) for (String item : st.getKeys(false)) p.stock.put(item, st.getInt(item));
            economy.initializePlayer(p);
            players.put(key(p.name), p);
        }

        int nameSkillVersion=data.getInt("meta.name-skill-model-version",0);
        if(nameSkillVersion<1) {
            for(SimPlayer p:players.values()) {
                p.skill=creatorSkillOverride(p.name,rebalanceExistingSkillForName(p.name,p.skill));
            }
            data.set("meta.name-skill-model-version",1);
        }

        sotwTicks = data.getLong("meta.sotw-ticks", 0L);
        sotwStartedAt = data.getLong("meta.sotw-started-at", System.currentTimeMillis());
        factionNameCursor = data.getInt("meta.faction-name-cursor", 0);
        ConfigurationSection rr = data.getConfigurationSection("rivalries");
        if (rr != null) for (String k : rr.getKeys(false)) rivalries.put(k,rr.getInt(k,0));

        ConfigurationSection history=data.getConfigurationSection("history");
        if(history!=null) {
            List<String> keys=new ArrayList<String>(history.getKeys(false));
            Collections.sort(keys,new Comparator<String>() {
                public int compare(String a,String b) {
                    try { return Long.compare(Long.parseLong(a),Long.parseLong(b)); }
                    catch(Exception ignored) { return a.compareTo(b); }
                }
            });
            for(String hk:keys) {
                ConfigurationSection h=history.getConfigurationSection(hk);
                if(h==null) continue;
                HistoryEvent e=new HistoryEvent();
                e.at=h.getLong("at",0L);
                e.importance=h.getInt("importance",1);
                e.type=h.getString("type","");
                e.summary=h.getString("summary","");
                e.faction=h.getString("faction","");
                e.people.addAll(h.getStringList("people"));
                if(!e.summary.isEmpty()) communityHistory.addLast(e);
            }
            while(communityHistory.size()>historyMemoryLimit()) communityHistory.removeFirst();
        }

        ConfigurationSection social = data.getConfigurationSection("social");
        if(social!=null) {
            for(String sk:social.getKeys(false)) {
                ConfigurationSection s=social.getConfigurationSection(sk);
                if(s==null) continue;
                SocialEdge e=new SocialEdge();
                e.from=s.getString("from","");
                e.to=s.getString("to","");
                if(e.from.isEmpty() || e.to.isEmpty()) continue;
                e.affinity=clampAffinity(s.getInt("affinity",0));
                e.trust=clampSocial(s.getInt("trust",50));
                e.respect=clampSocial(s.getInt("respect",50));
                e.grudge=clampSocial(s.getInt("grudge",0));
                e.lastInteraction=s.getLong("last-interaction",0L);
                for(String memory:s.getStringList("memories")) {
                    if(memory!=null && !memory.trim().isEmpty()) e.memories.addLast(memory);
                }
                while(e.memories.size()>relationshipMemoryLimit()) e.memories.removeFirst();
                socialEdges.put(socialKey(e.from,e.to),e);
            }
        }

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
                f.buildProgress = s.getInt("build-progress", 0);
                f.buildTarget = s.getInt("build-target", 0);
                f.baseQueued = s.getBoolean("base-queued", false);
                f.surfaceQueued = s.getBoolean("surface-queued", f.baseQueued);
                f.storageTier = s.getInt("storage-tier", s.getBoolean("storage")?1:0);
                f.netherPortal = s.getBoolean("nether-portal", false);
                f.endPortal = s.getBoolean("end-portal", false);
                f.recoveryMode = s.getBoolean("recovery-mode", false);
                f.archetype = s.getString("archetype", "");
                f.campTarget = s.getString("camp-target", "");
                f.specialTrapBuilt = s.getBoolean("special-trap-built", false);
                f.powerFaction = s.getBoolean("power-faction", false);
                f.underdog = s.getBoolean("underdog", false);
                if (f.archetype == null || f.archetype.isEmpty()) f.archetype = inferArchetype(f);
                f.claimed = s.getBoolean("claimed");
                f.storage = s.getBoolean("storage");
                f.brewer = s.getBoolean("brewer");
                f.farmBuilt = s.getBoolean("farm-built", false);
                f.p4Sets = s.getInt("p4-sets");
                f.sharp4Swords = s.getInt("sharp4-swords");
                f.bardSets = s.getInt("bard-sets");
                f.archerSets = s.getInt("archer-sets");
                f.rogueSets = s.getInt("rogue-sets");
                f.healPots = s.getInt("heal-pots");
                f.pearls = s.getInt("pearls");
                f.speedPots = s.getInt("speed-pots");
                f.firePots = 0;
                f.xp = s.getInt("xp");
                f.books = s.getInt("books");
                f.lapis = s.getInt("lapis");
                f.wood = s.getInt("wood");
                f.stone = s.getInt("stone");
                f.iron = s.getInt("iron");
                f.diamonds = s.getInt("diamonds");
                f.obsidian = s.getInt("obsidian");
                f.glass = s.getInt("glass");
                f.cane = s.getInt("cane");
                f.treasury = s.getDouble("treasury");
                f.actionCounter = s.getLong("actions");
                f.members.addAll(s.getStringList("members"));
                while (f.members.size() > MAX_FACTION_MEMBERS) f.members.remove(f.members.size() - 1);
                factions.put(key(f.name), f);
            }
        }

        expandPopulationIfConfigured();
        normalizeFactionClasses();
        seedStaffRolesIfNeeded();
        syncFactionAuthority();
    }

    private void expandPopulationIfConfigured() {
        int target=Math.max(30,Math.min(180,plugin.getConfig().getInt("sim-world.population",90)));
        target=Math.min(target,PLAYER_NAMES.length);
        if(players.size()>=target) return;

        List<String> names=new ArrayList<String>(Arrays.asList(PLAYER_NAMES));
        Collections.shuffle(names,new Random(2015L+players.size()*31L));
        int added=0;
        for(String name:names) {
            if(players.size()>=target) break;
            if(players.containsKey(key(name))) continue;

            SimPlayer p=new SimPlayer();
            p.name=name;
            p.balance=plugin.getConfig().getDouble("economy.starting-balance",500.0);
            p.skill=creatorSkillOverride(p.name,skillRollForName(p.name));
            p.mechanics=combatTrait(p.name,"mechanics",p.skill,18);
            p.pvpIq=combatTrait(p.name,"pvp-iq",p.skill,22);
            p.gameSense=combatTrait(p.name,"game-sense",Math.max(30,p.skill-4),28);
            applyCreatorCombatOverrides(p);
            p.skill=overallCombatSkill(p);
            p.aggression=25+rng.nextInt(66);
            p.bargaining=25+rng.nextInt(66);
            p.leadership=25+rng.nextInt(71);
            p.composure=leadershipTrait(p.name,"composure",p.leadership);
            p.charisma=leadershipTrait(p.name,"charisma",50+rng.nextInt(21)-10);
            p.decisiveness=leadershipTrait(p.name,"decisiveness",p.leadership);
            p.standards=leadershipTrait(p.name,"standards",50+rng.nextInt(31)-15);
            p.politicalIq=leadershipTrait(p.name,"political",50+rng.nextInt(31)-15);
            p.teamwork=35+rng.nextInt(61);
            p.economicIq=35+rng.nextInt(61);
            p.loyalty=40+rng.nextInt(61);
            p.riskTolerance=20+rng.nextInt(81);
            p.sociability=20+rng.nextInt(81);
            p.patience=20+rng.nextInt(81);
            p.reputation=plugin.isCreatorIdentity(p.name)?12+rng.nextInt(10):rng.nextInt(6);
            p.donorLevel=initialDonorLevel(p.name);
            p.donationUsd=initialDonationUsd(p.donorLevel);
            p.ownerAffinity=plugin.isCreatorIdentity(p.name)?10+rng.nextInt(18):rng.nextInt(31)-5;
            p.moderationTrust=30+rng.nextInt(51);
            p.communityJoinedTick=sotwTicks;
            p.pendingDonorKeys=p.donorLevel>0?1:0;
            p.logicalOnline=rng.nextInt(100)<58;
            p.sessionTicksLeft=5+rng.nextInt(20);
            p.currentGoal="idle";
            p.preferredJob=randomJob();
            p.role=p.preferredJob;
            p.factionTitle="member";
            p.combatClass=classFor(p);
            applyCreatorOpeningAccess(p);
            int lq=leaderQuality(p);
            p.leaderCandidate=lq>=76 ||
                (p.reputation>=15 && p.leadership>=68 && p.decisiveness>=65);
            economy.initializePlayer(p);
            players.put(key(p.name),p);
            added++;
        }
        if(added>0) {
            data.set("meta.population-expansion-version",1);
            recordHistory("POPULATION",3,added+" new players joined the map","","");
            save();
            plugin.getLogger().info("Expanded simulation population by "+added+" to "+players.size()+".");
        }
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
        rivalries.clear();
        communityHistory.clear();
        pendingChat.clear();
        sotwTicks = 0;
        sotwStartedAt = System.currentTimeMillis();
        factionNameCursor = 0;

        int target = Math.max(30, Math.min(180, plugin.getConfig().getInt("sim-world.population", 90)));
        List<String> names = new ArrayList<String>(Arrays.asList(PLAYER_NAMES));
        Collections.shuffle(names, new Random(2015L));

        int count = Math.min(target, names.size());
        for (int i = 0; i < count; i++) {
            SimPlayer p = new SimPlayer();
            p.name = names.get(i);
            p.balance = plugin.getConfig().getDouble("economy.starting-balance", 500.0);
            p.skill = creatorSkillOverride(p.name, skillRollForName(p.name));
            p.mechanics = combatTrait(p.name,"mechanics",p.skill,18);
            p.pvpIq = combatTrait(p.name,"pvp-iq",p.skill,22);
            p.gameSense = combatTrait(p.name,"game-sense",Math.max(30,p.skill-4),28);
            applyCreatorCombatOverrides(p);
            p.skill = overallCombatSkill(p);
            p.aggression = 25 + rng.nextInt(66);
            p.bargaining = 25 + rng.nextInt(66);
            p.leadership = 25 + rng.nextInt(71);
            p.composure = leadershipTrait(p.name,"composure",p.leadership);
            p.charisma = leadershipTrait(p.name,"charisma",50+rng.nextInt(21)-10);
            p.decisiveness = leadershipTrait(p.name,"decisiveness",p.leadership);
            p.standards = leadershipTrait(p.name,"standards",50+rng.nextInt(31)-15);
            p.politicalIq = leadershipTrait(p.name,"political",50+rng.nextInt(31)-15);
            p.leaderExperience = 0;
            p.duelWins = 0;
            p.duelLosses = 0;
            p.teamwork = 35 + rng.nextInt(61);
            p.loyalty = 40 + rng.nextInt(61);
            p.riskTolerance = 20 + rng.nextInt(81);
            p.sociability = 20 + rng.nextInt(81);
            p.patience = 20 + rng.nextInt(81);
            p.reputation = plugin.isCreatorIdentity(p.name) ? 12 + rng.nextInt(10) : rng.nextInt(6);
            p.kills = 0;
            p.deaths = 0;
            p.donorLevel = initialDonorLevel(p.name);
            p.donationUsd = initialDonationUsd(p.donorLevel);
            p.staffRole = "";
            p.ownerAffinity = plugin.isCreatorIdentity(p.name) ? 10+rng.nextInt(18) : rng.nextInt(31)-5;
            p.moderationTrust = 30+rng.nextInt(51);
            p.bannedUntil = 0L;
            p.communityJoinedTick = sotwTicks;
            p.pendingVoteKeys = 0;
            p.pendingDonorKeys = p.donorLevel>0?1:0;
            p.lastVoteAt = 0L;
            p.lastDonorKeyAt = 0L;
            p.nextDuelRequestAt = 0L;
            p.logicalOnline = rng.nextInt(100) < 48;
            p.sessionTicksLeft = 5 + rng.nextInt(20);
            p.currentGoal = "idle";
            p.preferredJob = randomJob();
            p.role = p.preferredJob;
            p.factionTitle = "member";
            p.combatClass = classFor(p);
            applyCreatorOpeningAccess(p);

            // Leadership is separate from PvP. Strong public PvPers can become
            // leaders, but most serious factions are seeded by people with
            // actual command/composure/decision traits.
            int lq=leaderQuality(p);
            p.leaderCandidate = lq>=73 ||
                (plugin.isCreatorIdentity(p.name) && p.charisma>=58) ||
                (p.reputation>=15 && p.leadership>=68 && p.decisiveness>=65);
            economy.initializePlayer(p);
            players.put(key(p.name), p);
        }

        // Underdogs are intentionally heterogeneous: one can be a smart captain
        // with weak teammates; another can simply be a kid/casual leader whose
        // faction later discovers a sleeper PvPer.
        List<SimPlayer> underdogPool = new ArrayList<SimPlayer>();
        for (SimPlayer p : players.values()) if(!p.leaderCandidate) underdogPool.add(p);
        Collections.sort(underdogPool,new Comparator<SimPlayer>() {
            public int compare(SimPlayer a,SimPlayer b) {
                return Integer.compare(leaderQuality(b),leaderQuality(a));
            }
        });
        int underdogs=Math.min(plugin.getConfig().getInt("sim-world.underdog-leaders",2),underdogPool.size());
        if(underdogs>0) {
            SimPlayer smart=underdogPool.get(0);
            smart.leaderCandidate=true; smart.underdogLeader=true;
        }
        if(underdogs>1 && underdogPool.size()>1) {
            int start=Math.max(1,underdogPool.size()/2);
            SimPlayer messy=underdogPool.get(start+rng.nextInt(underdogPool.size()-start));
            messy.leaderCandidate=true; messy.underdogLeader=true;
        }

        // Critical SOTW rule: nobody is preassigned to a faction.
        for (SimPlayer p : players.values()) p.faction = "";
        seedStaffRolesIfNeeded();
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

    private long sotwMillisLeft() {
        long total=plugin.getConfig().getLong("sotw.protection-minutes",60L)*60L*1000L;
        return Math.max(0L,total-(System.currentTimeMillis()-sotwStartedAt));
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

        createNextLeaderFaction();

        List<SimFaction> open = new ArrayList<SimFaction>(factions.values());
        Collections.shuffle(open, rng);
        int recruits = 0;
        for (SimFaction f : open) {
            if (f.members.size() >= f.targetSize || f.members.size() >= MAX_FACTION_MEMBERS) continue;
            if (rng.nextInt(100) < 72 && recruitBestCandidate(f)) recruits++;
            if (recruits >= 8) break;
        }
    }

    private String archetypeForLeader(SimPlayer leader) {
        if (leader == null) return "BALANCED";
        if (key(leader.name).equals("lolitsalex")) return "TRAPPER";
        if (leader.underdogLeader) return "UNDERDOG";

        int donor=plugin.simulatedDonorLevel(leader.name);
        int pvpScore=leader.skill+leader.aggression+leader.riskTolerance+donor*12;
        if (donor>=2 && leader.skill>=70 && pvpScore>=225 && rng.nextInt(100)<58) return "PVP";
        if (leader.economicIq>=82 && rng.nextInt(100)<55) return "ECONOMY";
        return "BALANCED";
    }

    private String inferArchetype(SimFaction f) {
        if (f != null) {
            for (String member : f.members) {
                if (key(member).equals("lolitsalex")) return "TRAPPER";
            }
        }
        SimPlayer leader=players.get(key(f.leader));
        if (leader!=null && key(leader.name).equals("lolitsalex")) return "TRAPPER";
        if (f.underdog) return "UNDERDOG";
        if (leader!=null) return archetypeForLeader(leader);
        return f.powerFaction?"BALANCED":"UNDERDOG";
    }

    private void applyCreatorFactionSpecializations() {
        SimPlayer alex=players.get("lolitsalex");
        if (alex==null || alex.faction==null || alex.faction.isEmpty()) return;
        SimFaction f=factions.get(key(alex.faction));
        if(f==null) return;

        f.archetype="TRAPPER";
        if(f.trapPreset==null || "none".equalsIgnoreCase(f.trapPreset)) {
            int tr=rng.nextInt(100);
            f.trapPreset = tr < 38 ? "fall_trap" : (tr < 82 ? "fence_gate_bow" : "drop_chute");
        }

        // Existing live SOTW bases get only the trap add-on, not a destructive
        // full-base replacement.
        if(f.storage && f.baseX!=0 && !f.specialTrapBuilt) {
            plugin.queueSimTrapAddon(f.name,f.trapPreset,f.baseX,f.baseY,f.baseZ);
            f.specialTrapBuilt=true;
        }
    }

    private void updateCampTargets() {
        SimPlayer alex=players.get("lolitsalex");
        String alexFaction=(alex==null)?"":alex.faction;

        for(SimFaction f:factions.values()) {
            if(f.recoveryMode || f.stage!=Stage.PVP_READY) {
                if(rng.nextInt(100)<25) f.campTarget="";
                continue;
            }

            if(!alexFaction.isEmpty() && !f.name.equalsIgnoreCase(alexFaction)) {
                if(("PVP".equals(f.archetype) && rng.nextInt(100)<48) ||
                   (f.powerFaction && rng.nextInt(100)<13)) {
                    f.campTarget=alexFaction;
                    recordRivalry(f.name,alexFaction,1+rng.nextInt(2));
                    continue;
                }
            }

            if("PVP".equals(f.archetype) && (f.campTarget==null || f.campTarget.isEmpty()) && rng.nextInt(100)<30) {
                String rival=strongestRival(f.name);
                if(!rival.isEmpty()) f.campTarget=rival;
            }
        }
    }

    private void createNextLeaderFaction() {
        SimPlayer best = null;
        for (SimPlayer p : players.values()) {
            if (!p.leaderCandidate || !p.faction.isEmpty() || !p.logicalOnline) continue;
            if (best == null) best = p;
            else {
                int ps=leaderQuality(p)+p.charisma/3+p.reputation/4+(p.underdogLeader?-10:12);
                int bs=leaderQuality(best)+best.charisma/3+best.reputation/4+(best.underdogLeader?-10:12);
                if(ps>bs) best=p;
            }
        }
        if (best == null) return;

        String name = nextFactionName();
        SimFaction f = new SimFaction();
        f.name = name;
        f.leader = best.name;
        int sizeRoll=rng.nextInt(100);
        int quality=leaderQuality(best);
        if(best.underdogLeader) {
            if(quality>=70) f.targetSize=sizeRoll<20?3:(sizeRoll<55?4:5);
            else f.targetSize=sizeRoll<48?2:(sizeRoll<84?3:4);
        } else if(quality>=78 && best.charisma>=65) {
            f.targetSize=sizeRoll<15?4:5;
        } else if(quality>=64) {
            f.targetSize=sizeRoll<25?3:(sizeRoll<70?4:5);
        } else {
            f.targetSize=sizeRoll<55?2:(sizeRoll<90?3:4);
        }
        f.targetSize=Math.min(MAX_FACTION_MEMBERS,f.targetSize);
        f.basePreset=BASE_PRESETS[rng.nextInt(BASE_PRESETS.length)];
        f.powerFaction=!best.underdogLeader && quality>=70;
        f.underdog=best.underdogLeader;
        f.archetype = archetypeForLeader(best);
        if (key(best.name).equals("lolitsalex")) {
            f.archetype = "TRAPPER";
            f.basePreset = "hcf_trap_base";
            int tr=rng.nextInt(100);
            f.trapPreset = tr < 38 ? "fall_trap" : (tr < 82 ? "fence_gate_bow" : "drop_chute");
        } else if ((best.skill < 72 || best.underdogLeader) && rng.nextInt(100) < 68) {
            int tr=rng.nextInt(100);
            f.trapPreset = tr < 45 ? "fall_trap" : (tr < 80 ? "fence_gate_bow" : "drop_chute");
        } else if (!"TRAPPER".equals(f.archetype)) {
            f.trapPreset = "none";
        }
        f.treasury = 0.0;
        f.members.add(best.name);

        if (!plugin.createSimFactionAuthority(f.name, best.name)) return;
        contributeToFaction(best, f, 0.15);
        best.faction = f.name;
        best.role = "leader";
        best.factionTitle = "leader";
        best.leaderExperience++;
        factions.put(key(f.name), f);
        recordHistory("FOUNDING",7,best.name+" founded "+f.name+" as a "+leaderStyle(best),
            f.name,best.name);
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
            if (!p.faction.isEmpty() || p.leaderCandidate || !p.logicalOnline) continue;
            if (p.combatClass == CombatClass.BARD && classCount(f,CombatClass.BARD) >= 1) continue;
            if (p.combatClass == CombatClass.ARCHER && classCount(f,CombatClass.ARCHER) >= 1) continue;

            int score = candidateScore(f, p);
            score += rng.nextInt(17) - 8;
            if (score > bestScore) {
                best = p;
                bestScore = score;
            }
        }

        if(best==null) return false;

        SimPlayer leader=players.get(key(f.leader));
        int quality=leaderQuality(leader);
        int threshold=55;
        if(leader!=null) threshold+=Math.max(0,(leader.standards-50)/2);
        if(f.powerFaction) threshold+=12;
        if(f.underdog) threshold-=10;

        boolean tryout=false;
        boolean passed=true;
        if(leader!=null && (f.powerFaction || leader.standards>=72) &&
           bestScore<threshold+35 && rng.nextInt(100)<55) {
            tryout=true;
            int performance=(best.mechanics*55+best.pvpIq*30+best.composure*15)/100+
                best.duelWins*2+rng.nextInt(31)-15;
            int bar=68+leader.standards/4+(f.powerFaction?7:0);
            passed=performance>=bar;
            best.duelWins+=passed?1:0;
            best.duelLosses+=passed?0:1;
            recordHistory("TRYOUT",passed?6:4,
                best.name+(passed?" passed ":" failed ")+f.name+"'s duel tryout",
                f.name,best.name,leader.name);
            SocialEdge edge=relationship(leader.name,best.name,true);
            edge.respect=clampSocial(edge.respect+(passed?5:-1));
            rememberRelationship(edge,best.name+(passed?" impressed me in a recruitment duel":" did not pass my recruitment duel"));
        }

        // Weak/inexperienced leaders sometimes make a questionable signing.
        if(bestScore<threshold && !tryout) {
            int mistakeChance=leader==null?12:Math.max(3,38-leaderQuality(leader)/2);
            if(rng.nextInt(100)>=mistakeChance) return false;
        }
        if(tryout && !passed) return false;

        if (!plugin.joinSimFactionAuthority(f.name, best.name)) return false;
        best.faction = f.name;
        best.role = best.preferredJob;
        best.factionTitle = "member";
        f.members.add(best.name);
        contributeToFaction(best, f, 0.12);
        normalizeFactionClasses(f);
        recordHistory("RECRUIT",4,best.name+" joined "+f.name+(tryout?" after a duel tryout":""),
            f.name,best.name,f.leader);
        return true;
    }

    private void contributeToFaction(SimPlayer p, SimFaction f, double fraction) {
        if (p == null || f == null || fraction <= 0) return;
        double reserve = 120.0;
        double available = Math.max(0.0, p.balance - reserve);
        double contribution = Math.min(available, p.balance * fraction);
        if (contribution <= 0) return;
        p.balance -= contribution;
        f.treasury += contribution;
    }

    private int candidateScore(SimFaction f, SimPlayer p) {
        // Recruitment cannot see hidden PvP skill. Leaders judge only signals
        // that would plausibly be known on a real server: relationships,
        // reputation, donor status, activity/personality, role fit and public
        // creator notoriety.
        SimPlayer leader=players.get(key(f.leader));
        SocialEdge rel=leader==null?null:relationship(leader.name,p.name,true);

        int score=p.teamwork/3+p.sociability/4+p.loyalty/4+p.reputation/3;
        score+=donorInfluence(p);
        if(rel!=null) {
            score+=rel.affinity/2;
            score+=(rel.trust-50)/2;
            score+=(rel.respect-50)/3;
            score-=rel.grudge/2;
        }

        // Configured creator personas have public PvP notoriety. This models
        // reputation/fame, not omniscient access to their hidden skill value.
        if(plugin.isCreatorIdentity(p.name)) score+=38;

        if (classCount(f, CombatClass.BARD) == 0 && p.combatClass == CombatClass.BARD) score += 45;
        if (classCount(f, CombatClass.ARCHER) == 0 && p.combatClass == CombatClass.ARCHER) score += 34;
        if (classCount(f, CombatClass.DIAMOND) < 2 && p.combatClass == CombatClass.DIAMOND) score += 20;

        if (jobCount(f, "miner") == 0 && "miner".equals(p.preferredJob)) score += 35;
        if (jobCount(f, "farmer") == 0 && "farmer".equals(p.preferredJob)) score += 30;
        if (jobCount(f, "builder") == 0 && "builder".equals(p.preferredJob)) score += 24;
        if (jobCount(f, "brewer") == 0 && "brewer".equals(p.preferredJob)) score += 28;

        if (f.underdog) {
            if ("farmer".equals(p.preferredJob) || "miner".equals(p.preferredJob) || "brewer".equals(p.preferredJob)) score += 28;
            score += p.teamwork / 3;
        }

        return score;
    }

    private void normalizeFactionClasses() {
        for(SimFaction f:factions.values()) normalizeFactionClasses(f);
    }

    private void normalizeFactionClasses(SimFaction f) {
        if(f==null) return;
        limitSupportClass(f,CombatClass.BARD);
        limitSupportClass(f,CombatClass.ARCHER);
    }

    private void limitSupportClass(SimFaction f,CombatClass type) {
        List<SimPlayer> same=new ArrayList<SimPlayer>();
        for(String n:f.members) {
            SimPlayer p=players.get(key(n));
            if(p!=null && p.combatClass==type) same.add(p);
        }
        if(same.size()<=1) return;
        Collections.sort(same,new Comparator<SimPlayer>() {
            public int compare(SimPlayer a,SimPlayer b) {
                int sa=a.skill+a.teamwork+a.riskTolerance/2;
                int sb=b.skill+b.teamwork+b.riskTolerance/2;
                return Integer.compare(sb,sa);
            }
        });
        for(int i=1;i<same.size();i++) same.get(i).combatClass=CombatClass.DIAMOND;
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
        if (plugin.isCreatorIdentity(p.name)) return "lff " + cls + " you know me";
        if (p.donorLevel>=2) return "lff " + cls + " active donor";
        if (p.reputation>=35) return "lff " + cls + " been active";
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
        SimPlayer leader=players.get(key(f.leader));
        if(leader!=null) {
            leader.leaderExperience++;
            if(raidable) {
                leader.composure=Math.max(10,leader.composure-(leader.patience<50?2:0));
                recordHistory("RAID",9,f.name+" went raidable after "+playerName+" died",f.name,playerName,f.leader);
            }
        }
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

    List<String> directorStatusLines() {
        List<String> out=new ArrayList<String>();
        out.add("fight="+visibleFightSummary()+" sotwProtected="+sotwProtectionActive()+
            " hotBudget="+hotCombatBudget());
        for(SimFaction f:factions.values()) {
            int solo=0,small=0,team=0,trap=0,avoid=0,online=0;
            for(String member:f.members) {
                SimPlayer p=players.get(key(member));
                if(p==null || !p.logicalOnline) continue;
                online++;
                PvpIntent intent=pvpIntentFor(p,f);
                if(intent==PvpIntent.SOLO_HUNT) solo++;
                else if(intent==PvpIntent.SMALL_TEAM) small++;
                else if(intent==PvpIntent.TEAMFIGHT) team++;
                else if(intent==PvpIntent.TRAP_PLAY) trap++;
                else avoid++;
            }
            int seekers=solo+small+team+trap;
            out.add(f.name+
                " stage="+f.stage.name()+
                " online="+online+
                " combatSlots="+combatStockSlots(f)+
                " seekers="+seekers+
                " intent[S="+solo+",SM="+small+",T="+team+",TR="+trap+",A="+avoid+"]"+
                " party="+factionDesiredPvpSize(f)+
                " hotspot="+warzoneForFaction(f)+
                " dtr="+String.format(Locale.ENGLISH,"%.1f",plugin.factionDtr(f.name))+
                (f.recoveryMode?" RECOVERY":""));
        }
        return out;
    }


    private double getDtrSafetyFloor(SimFaction f) {
        SimPlayer leader=players.get(key(f.leader));
        if(leader==null) return 1.0;

        int q=leaderQuality(leader);
        // Smart/composed leaders preserve map progress. Reckless or immature
        // leaders stay out too long and occasionally become cautionary stories.
        if(q>=82 && leader.composure>=75) return 2.0;
        if(q>=70) return 1.5;
        if(q<48 && leader.riskTolerance>=65) return 0.55;
        return 1.0;
    }

    private String chooseBasePreset(SimFaction f) {
        SimPlayer leader = players.get(key(f.leader));
        int diamonds = classCount(f, CombatClass.DIAMOND);
        int archers = classCount(f, CombatClass.ARCHER);
        boolean hasBrewer = jobCount(f, "brewer") > 0;
        boolean weakerPvP = leader == null || leader.skill < 72;
        boolean compact = f.targetSize <= 3;

        if ((f.underdog || weakerPvP) && rng.nextInt(100) < 58) {
            if (f.trapPreset == null || "none".equalsIgnoreCase(f.trapPreset)) {
                int tr=rng.nextInt(100);
                f.trapPreset = tr < 45 ? "fall_trap" : (tr < 80 ? "fence_gate_bow" : "drop_chute");
            }
            return "hcf_trap_base";
        }
        if (archers >= 2 && rng.nextInt(100) < 70) return "hcf_archer_tower";
        if (hasBrewer && rng.nextInt(100) < 58) return "hcf_brewer_base";
        if (compact) return rng.nextBoolean() ? "hcf_compact_2015" : "hcf_split_level";
        if (f.powerFaction && f.targetSize >= 5) {
            int r = rng.nextInt(100);
            if (r < 45) return "hcf_double_layer";
            if (r < 75) return "hcf_courtyard";
            return "hcf_glass_box";
        }
        if (diamonds >= 3 && rng.nextBoolean()) return "hcf_double_layer";
        return BASE_PRESETS[rng.nextInt(BASE_PRESETS.length)];
    }

    private int baseBuildTarget(String preset) {
        if ("hcf_compact_2015".equalsIgnoreCase(preset)) return 42;
        if ("hcf_split_level".equalsIgnoreCase(preset)) return 56;
        if ("hcf_archer_tower".equalsIgnoreCase(preset)) return 64;
        if ("hcf_double_layer".equalsIgnoreCase(preset)) return 78;
        if ("hcf_courtyard".equalsIgnoreCase(preset)) return 70;
        if ("hcf_brewer_base".equalsIgnoreCase(preset)) return 68;
        if ("hcf_trap_base".equalsIgnoreCase(preset)) return 62;
        return 58;
    }

    private int factionBuildWork(SimFaction f) {
        int work = 0;
        for (String member : f.members) {
            SimPlayer p = players.get(key(member));
            if (p == null || !p.logicalOnline) continue;
            int contribution = "build".equals(p.currentGoal) ? 2 : 1;
            if ("builder".equals(p.preferredJob)) contribution += 5;
            else if ("miner".equals(p.preferredJob)) contribution += 3;
            else if ("leader".equals(p.role)) contribution += 2;
            work += contribution;
        }
        return Math.max(1, Math.min(20, work));
    }

    private int[] baseMaterialCost(SimFaction f) {
        int members=Math.max(2,Math.max(f.targetSize,f.members.size()));
        // wood, stone, iron, obsidian, glass.  The underground core is mostly
        // mined stone/stone brick; glass is charged in the separate surface bill.
        return new int[]{48+members*14,240+members*58,12+members*2,0,0};
    }

    private boolean baseMaterialsReady(SimFaction f) {
        int[] cost=baseMaterialCost(f);
        return f.wood>=cost[0] && f.stone>=cost[1] && f.iron>=cost[2] &&
            f.obsidian>=cost[3] && f.glass>=cost[4];
    }

    private void consumeBaseMaterials(SimFaction f) {
        int[] cost=baseMaterialCost(f);
        f.wood-=cost[0]; f.stone-=cost[1]; f.iron-=cost[2];
        f.obsidian-=cost[3]; f.glass-=cost[4];
        mirrorConsumeFromStorage(f,Material.LOG,cost[0]);
        mirrorConsumeFromStorage(f,Material.COBBLESTONE,cost[1]);
        mirrorConsumeFromStorage(f,Material.IRON_INGOT,cost[2]);
        mirrorConsumeFromStorage(f,Material.OBSIDIAN,cost[3]);
        mirrorConsumeFromStorage(f,Material.GLASS,cost[4]);
    }

    private int[] surfaceMaterialCost(SimFaction f) {
        int members=Math.max(2,Math.max(f.targetSize,f.members.size()));
        return new int[]{22+members*6,90+members*24,8+members,0,105+members*18};
    }

    private boolean surfaceMaterialsReady(SimFaction f) {
        int[] c=surfaceMaterialCost(f);
        return f.wood>=c[0] && f.stone>=c[1] && f.iron>=c[2] &&
            f.obsidian>=c[3] && f.glass>=c[4];
    }

    private void consumeSurfaceMaterials(SimFaction f) {
        int[] c=surfaceMaterialCost(f);
        f.wood-=c[0]; f.stone-=c[1]; f.iron-=c[2]; f.obsidian-=c[3]; f.glass-=c[4];
    }

    private org.bukkit.inventory.Inventory factionStorageInventory(SimFaction f,String category) {
        if(f==null || (f.baseX==0 && f.baseZ==0)) return null;
        org.bukkit.World w=Bukkit.getWorlds().get(0);
        if(w==null) return null;

        String cat=category==null?"overflow":category.toLowerCase(Locale.ENGLISH);
        int[] a=plugin.simStorageAnchor(f.name,f.basePreset,cat,f.baseX,f.baseY,f.baseZ);
        String cacheKey=key(f.name)+":"+cat;

        org.bukkit.Location cached=storageChestCache.get(cacheKey);
        if(cached!=null && cached.getWorld()!=null) {
            org.bukkit.block.Block cb=cached.getBlock();
            if((cb.getType()==Material.CHEST || cb.getType()==Material.TRAPPED_CHEST) &&
               cb.getState() instanceof org.bukkit.block.Chest)
                return ((org.bukkit.block.Chest)cb.getState()).getInventory();
            storageChestCache.remove(cacheKey);
        }

        org.bukkit.block.Block exact=w.getBlockAt(a[0],a[1],a[2]);
        if((exact.getType()==Material.CHEST || exact.getType()==Material.TRAPPED_CHEST) &&
           exact.getState() instanceof org.bukkit.block.Chest) {
            storageChestCache.put(cacheKey,exact.getLocation());
            return ((org.bukkit.block.Chest)exact.getState()).getInventory();
        }

        // Migration/build-in-progress fallback searches around the semantic slot,
        // including deep underground rather than only around surface Y.
        org.bukkit.block.Chest best=null;
        double bestD=Double.MAX_VALUE;
        for(int x=a[0]-12;x<=a[0]+12;x++) for(int z=a[2]-12;z<=a[2]+12;z++)
            for(int y=Math.max(2,a[1]-5);y<=Math.min(w.getMaxHeight()-1,a[1]+5);y++) {
                org.bukkit.block.Block b=w.getBlockAt(x,y,z);
                if(b.getType()!=Material.CHEST && b.getType()!=Material.TRAPPED_CHEST) continue;
                if(!(b.getState() instanceof org.bukkit.block.Chest)) continue;
                double d=(x-a[0])*(x-a[0])+(z-a[2])*(z-a[2])+(y-a[1])*(y-a[1]);
                if(d<bestD) {best=(org.bukkit.block.Chest)b.getState();bestD=d;}
            }
        if(best==null) return null;
        storageChestCache.put(cacheKey,best.getLocation());
        return best.getInventory();
    }

    org.bukkit.inventory.Inventory visibleFactionStorage(String faction,String category) {
        SimFaction f=factions.get(key(faction));
        return f==null?null:factionStorageInventory(f,category);
    }

    private String storageCategory(Material material) {
        if(material==null) return "overflow";
        String n=material.name();
        if(n.endsWith("_HELMET")) return "helmets";
        if(n.endsWith("_CHESTPLATE")) return "chestplates";
        if(n.endsWith("_LEGGINGS")) return "leggings";
        if(n.endsWith("_BOOTS")) return "boots";
        if(n.endsWith("_SWORD")) return "swords";
        if(material==Material.BOW || material==Material.ARROW) return "bows";
        if(material==Material.DIAMOND_PICKAXE || material==Material.IRON_PICKAXE ||
           material==Material.STONE_PICKAXE || material==Material.GOLD_PICKAXE ||
           material==Material.WOOD_PICKAXE || material==Material.FEATHER) return "kits";
        if(material==Material.POTION) return "pots";
        if(material==Material.ENDER_PEARL) return "pearls";
        if(material==Material.DIAMOND || material==Material.DIAMOND_ORE ||
           material==Material.IRON_INGOT || material==Material.IRON_ORE ||
           material==Material.GOLD_INGOT || material==Material.GOLD_ORE ||
           material==Material.OBSIDIAN || material==Material.EMERALD) return "valuables";
        if(material==Material.COBBLESTONE || material==Material.STONE ||
           material==Material.LOG || material==Material.LOG_2 || material==Material.WOOD ||
           material==Material.DIRT || material==Material.SAND || material==Material.GLASS) return "blocks";
        if(material==Material.NETHER_STALK || material==Material.GLOWSTONE_DUST ||
           material==Material.SUGAR || material==Material.MAGMA_CREAM ||
           material==Material.SULPHUR || material==Material.SPECKLED_MELON ||
           material==Material.BLAZE_ROD || material==Material.BLAZE_POWDER ||
           material==Material.GHAST_TEAR) return "brewing";
        if(material==Material.SUGAR_CANE || material==Material.SUGAR_CANE_BLOCK ||
           material==Material.CACTUS || material==Material.PUMPKIN ||
           material==Material.MELON || material==Material.MELON_BLOCK ||
           material==Material.WHEAT || material==Material.CARROT_ITEM ||
           material==Material.POTATO_ITEM) return "farm";
        return "overflow";
    }

    private boolean putVisibleStorage(SimFaction f,org.bukkit.inventory.ItemStack item,String overrideCategory) {
        if(item==null || item.getType()==Material.AIR || item.getAmount()<=0) return false;
        String cat=overrideCategory==null?storageCategory(item.getType()):overrideCategory;
        org.bukkit.inventory.Inventory inv=factionStorageInventory(f,cat);
        if(inv==null) return false;
        return inv.addItem(item.clone()).isEmpty();
    }

    private void mirrorDepositToStorage(SimFaction f,Material material,int amount) {
        if(!f.storage || amount<=0 || material==null) return;
        org.bukkit.inventory.Inventory inv=factionStorageInventory(f,storageCategory(material));
        if(inv==null) return;
        int left=amount;
        while(left>0) {
            int n=Math.min(64,left);
            java.util.Map<Integer,org.bukkit.inventory.ItemStack> overflow=
                inv.addItem(new org.bukkit.inventory.ItemStack(material,n));
            if(!overflow.isEmpty()) break;
            left-=n;
        }
    }

    private int mirrorConsumeFromStorage(SimFaction f,Material material,int amount) {
        if(amount<=0 || material==null) return 0;
        org.bukkit.inventory.Inventory inv=factionStorageInventory(f,storageCategory(material));
        if(inv==null) return 0;
        int left=amount,removed=0;
        org.bukkit.inventory.ItemStack[] contents=inv.getContents();
        for(int i=0;i<contents.length && left>0;i++) {
            org.bukkit.inventory.ItemStack item=contents[i];
            if(item==null || item.getType()!=material) continue;
            int take=Math.min(left,item.getAmount());
            int remain=item.getAmount()-take;
            if(remain<=0) inv.setItem(i,null);
            else { item.setAmount(remain); inv.setItem(i,item); }
            left-=take; removed+=take;
        }
        return removed;
    }

    private void seedVisibleStorage(SimFaction f) {
        // Seed only a bounded physical view of existing ledger stock. Future
        // production/deposits keep it moving, while consumption/sales drain it.
        mirrorDepositToStorage(f,Material.LOG,Math.min(f.wood,128));
        mirrorDepositToStorage(f,Material.COBBLESTONE,Math.min(f.stone,192));
        mirrorDepositToStorage(f,Material.IRON_INGOT,Math.min(f.iron,64));
        mirrorDepositToStorage(f,Material.DIAMOND,Math.min(f.diamonds,32));
        mirrorDepositToStorage(f,Material.OBSIDIAN,Math.min(f.obsidian,32));
    }

    private int sellFactionSurplus(SimFaction f,Material material,int available,int reserve,int cap) {
        int qty=Math.min(cap,Math.max(0,available-reserve));
        if(qty<=0) return 0;
        double unit=plugin.sellUnitPrice(material);
        if(unit<=0.0) return 0;
        mirrorConsumeFromStorage(f,material,qty);
        f.treasury+=qty*unit;
        return qty;
    }

    private void liquidateSurplus(SimFaction f) {
        int stoneReserve=f.brewer?128:Math.max(220,baseMaterialCost(f)[1]);
        int woodReserve=Math.max(96,baseMaterialCost(f)[0]/2);
        int ironReserve=f.brewer?48:Math.max(40,baseMaterialCost(f)[2]+35);
        int obbyReserve="TRAPPER".equals(f.archetype)?24:12;

        int sold=sellFactionSurplus(f,Material.COBBLESTONE,f.stone,stoneReserve,96);
        f.stone-=sold;
        sold=sellFactionSurplus(f,Material.LOG,f.wood,woodReserve,64);
        f.wood-=sold;
        sold=sellFactionSurplus(f,Material.IRON_INGOT,f.iron,ironReserve,24);
        f.iron-=sold;
        sold=sellFactionSurplus(f,Material.OBSIDIAN,f.obsidian,obbyReserve,12);
        f.obsidian-=sold;

        // Never sell diamonds until the faction has enough reserve for at least
        // one replacement set per Diamond-class member plus spare swords.
        int diamondReserve=Math.max(12,classCount(f,CombatClass.DIAMOND)*26);
        sold=sellFactionSurplus(f,Material.DIAMOND,f.diamonds,diamondReserve,12);
        f.diamonds-=sold;
    }

    private boolean planAndClaimBase(SimFaction f) {
        if (f.baseX != 0 || f.baseZ != 0) return true;

        org.bukkit.World world = Bukkit.getWorlds().get(0);
        if (world == null) return false;

        if (f.basePreset == null || f.basePreset.isEmpty()) f.basePreset = chooseBasePreset(f);

        int terrainRadius = baseTerrainRadius(f);
        int maxRelief = Math.max(2, plugin.getConfig().getInt("sim-world.max-base-site-relief", 6));
        int maxLiquids = Math.max(0, plugin.getConfig().getInt("sim-world.max-base-site-liquid-samples", 1));

        int[] bestPoint = null;
        int[] bestEval = null;
        int bestScore = Integer.MAX_VALUE;

        // Evaluate several nearby candidates instead of accepting the first hill/ravine.
        for (int attempt=0; attempt<14; attempt++) {
            int[] raw = chooseBasePoint(f);
            int x = alignChunkCenter(raw[0]);
            int z = alignChunkCenter(raw[1]);
            int[] eval = plugin.evaluateSimBaseSite(x,z,terrainRadius); // medianY, relief, liquid samples

            int minY = Math.max(50, plugin.getConfig().getInt("sim-world.min-base-y", 50));
            int maxY = Math.min(110, plugin.getConfig().getInt("sim-world.max-base-y", 110));
            if (eval[0] < minY || eval[0] > maxY) continue;

            int score = eval[1] * 20 + eval[2] * 100;
            if (score < bestScore) {
                bestScore = score;
                bestPoint = new int[]{x,z};
                bestEval = eval;
            }

            if (eval[1] <= maxRelief && eval[2] <= maxLiquids) break;
        }

        if (bestPoint == null || bestEval == null) return false;

        f.baseX = bestPoint[0];
        f.baseZ = bestPoint[1];

        int minY = Math.max(50, plugin.getConfig().getInt("sim-world.min-base-y", 50));
        int maxY = Math.min(110, plugin.getConfig().getInt("sim-world.max-base-y", 110));
        f.baseY = Math.max(minY, Math.min(maxY, bestEval[0]));

        org.bukkit.Location home = new org.bukkit.Location(world, f.baseX + 0.5, f.baseY + 1, f.baseZ + 0.5);
        int[] rect=baseFootprintRect(f);
        int radiusBlocks=Math.max(Math.abs(f.baseX-rect[0]),Math.abs(f.baseZ-rect[2]));
        f.claimRadiusChunks=Math.max(1,(int)Math.ceil(radiusBlocks/16.0));

        if (!plugin.setSimFactionHomeAndRectClaim(f.name,home,rect[0],rect[1],rect[2],rect[3])) {
            f.baseX = 0;
            f.baseZ = 0;
            return false;
        }
        return true;
    }

    private int alignChunkCenter(int block) {
        return ((block >> 4) << 4) + 8;
    }

    private int baseTerrainRadius(SimFaction f) {
        HcfBasePlan.Profile p=baseProfile(f.name);
        int members=Math.max(1,p.members);
        int r=18+members;
        if("fall_trap".equalsIgnoreCase(f.trapPreset)) r=Math.max(r,22);
        return r;
    }

    private int[] baseFootprintRect(SimFaction f) {
        int buffer=Math.max(4,plugin.getConfig().getInt("claims.sim-base-buffer-blocks",8));
        int r=baseTerrainRadius(f)+buffer;
        return new int[]{f.baseX-r,f.baseX+r,f.baseZ-r,f.baseZ+r};
    }

    private List<String> baseFootprintClaims(String world, SimFaction f) {
        int r=baseTerrainRadius(f);
        int minX=f.baseX-r;
        int maxX=f.baseX+r;
        int minZ=f.baseZ-r;
        int maxZ=f.baseZ+r;

        int minCx=minX >> 4;
        int maxCx=maxX >> 4;
        int minCz=minZ >> 4;
        int maxCz=maxZ >> 4;

        List<String> out=new ArrayList<String>();
        int cap=Math.max(4,plugin.getConfig().getInt("claims.max-cap",12));
        for(int cx=minCx;cx<=maxCx;cx++) {
            for(int cz=minCz;cz<=maxCz;cz++) {
                if(out.size()>=cap) return out;
                out.add(world+":"+cx+":"+cz);
            }
        }
        return out;
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
            recordRivalry(f.name,anchor.name,f.powerFaction ? 7 : 3);
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

        // Sample one performance for this encounter. Real ability anchors
        // the result; form, pressure and mistakes can swing close fights.
        Map<SimFaction,Double> performances=new LinkedHashMap<SimFaction,Double>();
        SimFaction loser=null,winner=null;
        double worst=Double.MAX_VALUE,best=-Double.MAX_VALUE;
        for(SimFaction candidate:cluster) {
            double performance=sampleFactionFightPerformance(candidate);
            performances.put(candidate,performance);
            if(performance<worst){worst=performance;loser=candidate;}
            if(performance>best){best=performance;winner=candidate;}
        }
        if(loser==null || winner==null || loser==winner) return;

        SimPlayer victim = weakestExposedMember(loser);
        if (victim == null) return;

        plugin.applySimulatedFactionDeath(loser.name, victim.name);
        SimPlayer killer = null;
        if (winner != null) {
            recordRivalry(loser.name,winner.name,12 + rng.nextInt(10));
            killer = players.get(key(winner.leader));
            if (killer != null) queueDeathConversation(victim,killer);
            if (killer != null && plugin.isCreatorIdentity(killer.name)) queueCreatorKillReactions(killer.name);
        }
        if (plugin.isCreatorIdentity(victim.name)) queueCreatorDeathReactions(victim.name);

        victim.deaths++;
        victim.reputation=Math.max(0,victim.reputation-2);
        if(killer!=null) {
            killer.kills++;
            killer.reputation=Math.min(999,killer.reputation+5+overallCombatSkill(victim)/18+rng.nextInt(5));
        }

        int lostHeals=Math.min(loser.healPots,10+rng.nextInt(10));
        int lostPearls=Math.min(loser.pearls,2+rng.nextInt(4));
        loser.healPots-=lostHeals;
        loser.pearls-=lostPearls;

        boolean setLost=false;
        if (victim.combatClass == CombatClass.DIAMOND && loser.p4Sets > 0) {
            loser.p4Sets--;
            setLost=true;
            if(loser.sharp4Swords>0) loser.sharp4Swords--;
        } else if(victim.combatClass==CombatClass.BARD && loser.bardSets>0) {
            loser.bardSets--;setLost=true;
        } else if(victim.combatClass==CombatClass.ARCHER && loser.archerSets>0) {
            loser.archerSets--;setLost=true;
        } else if(victim.combatClass==CombatClass.ROGUE && loser.rogueSets>0) {
            loser.rogueSets--;setLost=true;
        }

        if(winner!=null) {
            winner.healPots+=(int)Math.floor(lostHeals*0.70);
            winner.pearls+=(int)Math.floor(lostPearls*0.85);
            if(setLost && rng.nextInt(100)<78) {
                if(victim.combatClass==CombatClass.DIAMOND){winner.p4Sets++;winner.sharp4Swords++;}
                else if(victim.combatClass==CombatClass.BARD) winner.bardSets++;
                else if(victim.combatClass==CombatClass.ARCHER) winner.archerSets++;
                else if(victim.combatClass==CombatClass.ROGUE) winner.rogueSets++;
            }
        }

    }

    private double sampleFactionFightPerformance(SimFaction f) {
        double base=factionFightStrength(f);
        if(f==null || f.members.isEmpty()) return base;

        double mistake=0,composure=0,pvpIq=0;
        int n=0;
        for(String name:f.members) {
            SimPlayer p=players.get(key(name));
            if(p==null) continue;
            mistake+=combatMistakePropensity(p);
            composure+=p.composure;
            pvpIq+=p.pvpIq;
            n++;
        }
        if(n==0) return base;
        mistake/=n; composure/=n; pvpIq/=n;

        // Close teams can trade wins. Stronger teams retain a real edge because
        // variance is bounded instead of replacing strength with a lottery.
        double sigma=3.0 + mistake*0.18 + Math.max(0,55-composure)*0.05;
        double form=rng.nextGaussian()*sigma;
        double smartConsistency=Math.max(-2.0,Math.min(3.0,(pvpIq-55.0)/18.0));
        return base+form+smartConsistency;
    }

    private double factionFightStrength(SimFaction f) {
        double score=0;
        int n=0;
        for(String member:f.members) {
            SimPlayer p=players.get(key(member));
            if(p==null) continue;
            score += p.mechanics*0.52 + p.pvpIq*0.30 + p.gameSense*0.12 +
                p.teamwork*0.18 + p.composure*0.08 + p.aggression*0.05;
            if(p.combatClass==CombatClass.BARD) score+=12+p.gameSense*0.05;
            else if(p.combatClass==CombatClass.ARCHER) score+=7+p.mechanics*0.03;
            else if(p.combatClass==CombatClass.ROGUE) score+=5+p.pvpIq*0.03;
            n++;
        }
        if(n==0) return 1;
        score/=n;

        SimPlayer leader=players.get(key(f.leader));
        if(leader!=null) score+=(leaderQuality(leader)-50)*0.10;

        score+=Math.min(f.members.size(),3)*6;
        if(f.healPots>=f.members.size()*20) score+=8;
        if(f.pearls>=f.members.size()*6) score+=5;
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
                int sa=overallCombatSkill(a)+a.teamwork/3+a.composure/3+a.gameSense/4;
                int sb=overallCombatSkill(b)+b.teamwork/3+b.composure/3+b.gameSense/4;
                return Integer.compare(sa,sb);
            }
        });
        int bound = Math.min(2, candidates.size());
        return candidates.get(rng.nextInt(bound));
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

    private int initialDonorLevel(String name) {
        int roll=Math.abs(key(name).hashCode())%100;
        int level;
        if(roll<66) level=0;
        else if(roll<82) level=1;
        else if(roll<92) level=2;
        else if(roll<98) level=3;
        else level=4;
        if(plugin.isCreatorIdentity(name)) {
            int floor=Math.max(0,Math.min(4,plugin.getConfig().getInt("creator-tag.minimum-donor-level",3)));
            level=Math.max(level,floor);
        }
        return level;
    }

    private void applyCreatorOpeningAccess(SimPlayer p) {
        if(p==null || !plugin.isCreatorIdentity(p.name)) return;
        int floor=Math.max(0,Math.min(4,plugin.getConfig().getInt("creator-tag.minimum-donor-level",3)));
        p.donorLevel=Math.max(p.donorLevel,floor);
        // The configured creator roster represents PotPvP/HCF personalities.
        // They open the map as DIAMOND fighters; support classes stay available
        // to ordinary faction members instead of forcing a creator into Miner iron.
        p.combatClass=CombatClass.DIAMOND;
    }

    String factionTitleFor(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?"member":p.factionTitle;
    }

    String publicLeaderStyleFor(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?"unknown":leaderStyle(p);
    }

    String publicLeaderReputationFor(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?"unproven":publicLeaderReputation(p);
    }

    int killsFor(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?0:p.kills;
    }

    int deathsFor(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?0:p.deaths;
    }

    boolean logicalOnlineFor(String name) {
        SimPlayer p=players.get(key(name));
        return p!=null && p.logicalOnline && p.bannedUntil<=System.currentTimeMillis();
    }

    int simulatedDonorLevelFor(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?-1:Math.max(0,Math.min(4,p.donorLevel));
    }

    String simulatedStaffRole(String name) {
        SimPlayer p=players.get(key(name));
        return p==null?"":p.staffRole;
    }

    private void seedStaffRolesIfNeeded() {
        int existing=0;
        for(SimPlayer p:players.values()) if(p.staffRole!=null && !p.staffRole.isEmpty()) existing++;
        if(existing>0) return;

        List<SimPlayer> candidates=new ArrayList<SimPlayer>();
        for(SimPlayer p:players.values()) {
            if(plugin.isCreatorIdentity(p.name)) continue;
            candidates.add(p);
        }
        Collections.sort(candidates,new Comparator<SimPlayer>() {
            public int compare(SimPlayer a,SimPlayer b) {
                int sa=a.leadership+a.patience+a.loyalty+a.moderationTrust;
                int sb=b.leadership+b.patience+b.loyalty+b.moderationTrust;
                return Integer.compare(sb,sa);
            }
        });

        if(!candidates.isEmpty()) candidates.get(0).staffRole="ADMIN";
        for(int i=1;i<Math.min(4,candidates.size());i++) candidates.get(i).staffRole="MOD";
    }

    private int namePrestigeTier(String name) {
        if(name==null || name.isEmpty()) return 0;
        if(plugin.isCreatorIdentity(name)) return 3;

        String lower=name.toLowerCase(Locale.ENGLISH);
        boolean letters=name.matches("[A-Za-z]+");
        boolean digits=name.matches(".*\\d.*");
        boolean casualMarker=lower.contains("pvp") || lower.contains("hd") || lower.contains("mc") ||
            lower.contains("fan") || lower.contains("kid") || lower.contains("king") ||
            lower.contains("rekt") || lower.contains("itz") || lower.contains("x_") ||
            lower.startsWith("xx") || lower.endsWith("xx");

        // Short clean single-word handles were culturally valuable on old PvP
        // servers and are therefore a strong-but-noisy public signal.
        if(letters && !casualMarker && name.length()>=4 && name.length()<=8) return 2;
        if(letters && !casualMarker && name.length()<=11) return 1;
        if(!digits && !casualMarker && name.length()<=10) return 1;
        return 0;
    }

    private int skillRollForName(String name) {
        int base=skillRoll();
        int tier=namePrestigeTier(name);
        int bias=tier==2?10:(tier==1?3:-6);
        // Keep sleepers and overrated names. The handle predicts skill; it does
        // not reveal it.
        int noise=rng.nextInt(13)-6;
        return Math.max(18,Math.min(100,base+bias+noise));
    }

    private int rebalanceExistingSkillForName(String name,int existing) {
        int tier=namePrestigeTier(name);
        int target=existing;
        if(tier==2) target+=6+rng.nextInt(5);
        else if(tier==1) target+=rng.nextInt(5)-1;
        else target-=2+rng.nextInt(5);
        return Math.max(18,Math.min(100,target));
    }

    int publicNamePrestige(String name) {
        return namePrestigeTier(name);
    }

    private int combatTrait(String name,String trait,int base,int spread) {
        int h=(key(name)+"|combat|"+trait).hashCode();
        int positive=h==Integer.MIN_VALUE?0:Math.abs(h);
        int noise=(positive%(spread*2+1))-spread;
        return Math.max(12,Math.min(100,base+noise));
    }

    private int overallCombatSkill(SimPlayer p) {
        if(p==null) return 50;
        // Mechanics remains the largest component, but decision quality matters
        // enough for a slightly weaker mechanical player to win intelligently.
        return Math.max(1,Math.min(100,
            (p.mechanics*55+p.pvpIq*30+p.gameSense*15)/100));
    }

    private int combatMistakePropensity(SimPlayer p) {
        if(p==null) return 18;
        int protection=(p.pvpIq*38+p.composure*30+p.gameSense*18+p.mechanics*14)/100;
        int volatility=Math.max(0,p.riskTolerance-70)/5;
        // Never zero: even elite players occasionally make a bad read/execution.
        return Math.max(3,Math.min(34,29-protection/4+volatility));
    }

    private void applyCreatorCombatOverrides(SimPlayer p) {
        if(p==null) return;
        String n=key(p.name);
        if(n.equals("stimpy")||n.equals("stimpypvp")||n.equals("marcel")||n.equals("painfulpvp")) {
            p.mechanics=Math.max(p.mechanics,94);
            p.pvpIq=Math.max(p.pvpIq,88);
            p.gameSense=Math.max(p.gameSense,84);
        } else if(n.equals("lolitsalex")) {
            p.mechanics=Math.max(p.mechanics,82);
            p.pvpIq=Math.max(p.pvpIq,88);
            p.gameSense=Math.max(p.gameSense,94);
        } else if(n.equals("skimpy")) {
            p.mechanics=Math.max(p.mechanics,76);
            p.pvpIq=Math.max(p.pvpIq,74);
            p.gameSense=Math.max(p.gameSense,72);
        }
    }

    private int leadershipTrait(String name,String trait,int base) {
        int h=Math.abs((key(name)+"|"+trait).hashCode());
        int noise=(h%31)-15;
        return Math.max(10,Math.min(100,base+noise));
    }

    private int leaderQuality(SimPlayer p) {
        if(p==null) return 0;
        return (p.leadership*26+p.composure*18+p.decisiveness*17+p.politicalIq*15+
            p.economicIq*10+p.charisma*8+p.teamwork*6)/100;
    }

    private String publicLeaderReputation(SimPlayer p) {
        int q=leaderQuality(p);
        if(p.leaderExperience<4) return q>=72?"promising":"unproven";
        if(q>=82) return "widely respected";
        if(q>=70) return "respected";
        if(q>=56) return "mixed";
        return "questioned";
    }

    private String leaderStyle(SimPlayer p) {
        if(p==null) return "unknown";
        int q=leaderQuality(p);
        if(p.composure>=78 && p.decisiveness>=74 && q>=76) return "calm commander";
        if(p.charisma>=82 && p.politicalIq>=70) return "charismatic coalition-builder";
        if(p.economicIq>=82 && p.patience>=68) return "methodical operator";
        if(p.aggression>=78 && p.composure<55) return "hotheaded shot-caller";
        if(p.leadership<45 && p.decisiveness<50) return "inexperienced kid leader";
        if(p.underdogLeader && q>=66) return "underdog captain";
        if(q>=70) return "disciplined leader";
        if(q<48) return "loose casual leader";
        return "ordinary faction leader";
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
            data.set(b + ".faction-title",p.factionTitle);
            data.set(b + ".preferred-job", p.preferredJob);
            data.set(b + ".combat-class", p.combatClass.name());
            data.set(b + ".leader-candidate", p.leaderCandidate);
            data.set(b + ".underdog-leader", p.underdogLeader);
            data.set(b + ".balance", p.balance);
            data.set(b + ".skill", p.skill);
            data.set(b + ".mechanics",p.mechanics);
            data.set(b + ".pvp-iq",p.pvpIq);
            data.set(b + ".game-sense",p.gameSense);
            data.set(b + ".aggression", p.aggression);
            data.set(b + ".bargaining", p.bargaining);
            data.set(b + ".leadership", p.leadership);
            data.set(b + ".composure", p.composure);
            data.set(b + ".charisma", p.charisma);
            data.set(b + ".decisiveness", p.decisiveness);
            data.set(b + ".standards", p.standards);
            data.set(b + ".political-iq", p.politicalIq);
            data.set(b + ".leader-experience",p.leaderExperience);
            data.set(b + ".duel-wins",p.duelWins);
            data.set(b + ".duel-losses",p.duelLosses);
            data.set(b + ".teamwork", p.teamwork);
            data.set(b + ".economic-iq", p.economicIq);
            data.set(b + ".loyalty", p.loyalty);
            data.set(b + ".risk-tolerance", p.riskTolerance);
            data.set(b + ".sociability", p.sociability);
            data.set(b + ".patience", p.patience);
            data.set(b + ".reputation", p.reputation);
            data.set(b + ".kills", p.kills);
            data.set(b + ".deaths", p.deaths);
            data.set(b + ".donor-level", p.donorLevel);
            data.set(b + ".donation-usd", p.donationUsd);
            data.set(b + ".staff-role", p.staffRole);
            data.set(b + ".owner-affinity", p.ownerAffinity);
            data.set(b + ".moderation-trust", p.moderationTrust);
            data.set(b + ".banned-until", p.bannedUntil);
            data.set(b + ".community-joined-tick", p.communityJoinedTick);
            data.set(b + ".pending-vote-keys",p.pendingVoteKeys);
            data.set(b + ".pending-donor-keys",p.pendingDonorKeys);
            data.set(b + ".last-vote-at",p.lastVoteAt);
            data.set(b + ".last-donor-key-at",p.lastDonorKeyAt);
            data.set(b + ".next-duel-request-at",p.nextDuelRequestAt);
            data.set(b + ".logical-online", p.logicalOnline);
            data.set(b + ".session-ticks-left", p.sessionTicksLeft);
            data.set(b + ".next-goal-tick", p.nextGoalTick);
            data.set(b + ".current-goal", p.currentGoal);
            data.set(b + ".farm-crop", p.farmCrop);
            data.set(b + ".farm-cells", p.farmCells);
            data.set(b + ".farm-investment", p.farmInvestment);
            data.set(b + ".farm-cycles", p.farmCycles);
            data.set(b + ".farm-ready", p.farmReady);
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
            data.set(b + ".build-progress", f.buildProgress);
            data.set(b + ".build-target", f.buildTarget);
            data.set(b + ".base-queued", f.baseQueued);
            data.set(b + ".surface-queued", f.surfaceQueued);
            data.set(b + ".storage-tier", f.storageTier);
            data.set(b + ".nether-portal", f.netherPortal);
            data.set(b + ".end-portal", f.endPortal);
            data.set(b + ".recovery-mode", f.recoveryMode);
            data.set(b + ".archetype", f.archetype);
            data.set(b + ".camp-target", f.campTarget);
            data.set(b + ".special-trap-built", f.specialTrapBuilt);
            data.set(b + ".power-faction", f.powerFaction);
            data.set(b + ".underdog", f.underdog);
            data.set(b + ".claimed", f.claimed);
            data.set(b + ".storage", f.storage);
            data.set(b + ".brewer", f.brewer);
            data.set(b + ".farm-built", f.farmBuilt);
            data.set(b + ".p4-sets", f.p4Sets);
            data.set(b + ".sharp4-swords", f.sharp4Swords);
            data.set(b + ".bard-sets", f.bardSets);
            data.set(b + ".archer-sets", f.archerSets);
            data.set(b + ".rogue-sets", f.rogueSets);
            data.set(b + ".heal-pots", f.healPots);
            data.set(b + ".pearls", f.pearls);
            data.set(b + ".speed-pots", f.speedPots);
            data.set(b + ".fire-pots", 0);
            data.set(b + ".xp", f.xp);
            data.set(b + ".books", f.books);
            data.set(b + ".lapis", f.lapis);
            data.set(b + ".wood", f.wood);
            data.set(b + ".stone", f.stone);
            data.set(b + ".iron", f.iron);
            data.set(b + ".diamonds", f.diamonds);
            data.set(b + ".obsidian", f.obsidian);
            data.set(b + ".glass", f.glass);
            data.set(b + ".cane", f.cane);
            data.set(b + ".treasury", f.treasury);
            data.set(b + ".actions", f.actionCounter);
            data.set(b + ".members", new ArrayList<String>(f.members));
        }

        data.set("rivalries", null);
        for (Map.Entry<String,Integer> e : rivalries.entrySet()) data.set("rivalries." + e.getKey(), e.getValue());

        data.set("social",null);
        for(Map.Entry<String,SocialEdge> entry:socialEdges.entrySet()) {
            SocialEdge e=entry.getValue();
            String b="social."+entry.getKey();
            data.set(b+".from",e.from);
            data.set(b+".to",e.to);
            data.set(b+".affinity",e.affinity);
            data.set(b+".trust",e.trust);
            data.set(b+".respect",e.respect);
            data.set(b+".grudge",e.grudge);
            data.set(b+".last-interaction",e.lastInteraction);
            data.set(b+".memories",new ArrayList<String>(e.memories));
        }

        data.set("history",null);
        int hi=0;
        for(HistoryEvent e:communityHistory) {
            String b="history."+String.format(Locale.ENGLISH,"%04d",hi++);
            data.set(b+".at",e.at);
            data.set(b+".importance",e.importance);
            data.set(b+".type",e.type);
            data.set(b+".summary",e.summary);
            data.set(b+".faction",e.faction);
            data.set(b+".people",new ArrayList<String>(e.people));
        }

        data.set("meta.schema", 4);
        data.set("meta.name-skill-model-version",1);
        data.set("meta.sotw-ticks", sotwTicks);
        data.set("meta.sotw-started-at", sotwStartedAt);
        data.set("meta.faction-name-cursor", factionNameCursor);

        File tmp=new File(plugin.getDataFolder(),"simulation.yml.tmp");
        File lastGood=new File(plugin.getDataFolder(),"simulation.lastgood.yml");
        try {
            data.save(tmp);

            // Preserve the previous known-readable file before replacing it.
            if(file.isFile() && file.length()>0L) {
                java.nio.file.Files.copy(file.toPath(),lastGood.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                java.nio.file.Files.move(tmp.toPath(),file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch(Exception atomicUnsupported) {
                java.nio.file.Files.move(tmp.toPath(),file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if(tmp.exists()) tmp.delete();
            plugin.getLogger().warning("Could not atomically save simulation.yml: " + e.getMessage());
        }
    }

    private String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ENGLISH);
    }
}
