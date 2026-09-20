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
        int economicIq;      // 0..100
        int loyalty;         // 0..100
        int riskTolerance;   // 0..100
        int sociability;     // 0..100
        int patience;        // 0..100
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


    static final class WorkerTask {
        String identity;
        String faction;
        String action;
        int x;
        int y;
        int z;
        int priority;

        String wire() {
            return "action=" + action +
                " faction=" + (faction == null || faction.isEmpty() ? "none" : faction) +
                " x=" + x + " y=" + y + " z=" + z +
                " priority=" + priority;
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
                " aggression=" + aggression +
                " risk=" + risk +
                " x=" + x + " y=" + y + " z=" + z +
                " homeX=" + homeX + " homeY=" + homeY + " homeZ=" + homeZ +
                " trapX=" + trapX + " trapY=" + trapY + " trapZ=" + trapZ +
                " trapType=" + trapType +
                " focus=" + focus +
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
        int firePots;
        int minerIron;
    }

    private final EraCore plugin;
    private final SimEconomyModel economy;
    private final ContextChatBrain chatBrain;
    private final Random rng = new Random(881994L);
    private final File file;
    private final File combatFile;
    private final YamlConfiguration data;
    private final Map<String,SimPlayer> players = new LinkedHashMap<String,SimPlayer>();
    private final Map<String,SimFaction> factions = new LinkedHashMap<String,SimFaction>();
    private final Map<String,MarketOrder> activeOrders = new HashMap<String,MarketOrder>();
    private final Map<String,Conversation> conversations = new HashMap<String,Conversation>();
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
        this.file = new File(plugin.getDataFolder(), "simulation.yml");
        this.combatFile = new File(plugin.getDataFolder(), "combat-hot.yml");
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

    void repairExistingBaseTerrainAndClaims() {
        if (data.getInt("meta.terrain-repair-version",0) >= 1) return;

        org.bukkit.World world=Bukkit.getWorlds().get(0);
        if(world==null) return;

        for(SimFaction f : factions.values()) {
            if(f.baseX==0 && f.baseZ==0) continue;

            // Fill support below already-built structures and reapply the
            // chosen preset exactly once for this migration.
            plugin.queueSimFoundationRepair(f.name,f.basePreset,f.trapPreset,f.baseX,f.baseY,f.baseZ);

            // Expand legacy claims to the complete base/farm/trap footprint.
            List<String> desired=baseFootprintClaims(world.getName(),f);
            org.bukkit.Location home=new org.bukkit.Location(world,f.baseX+0.5,f.baseY+1,f.baseZ+0.5);
            plugin.setSimFactionHomeAndClaims(f.name,home,desired);
        }
        data.set("meta.terrain-repair-version",1);
        save();
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


    int combatAssignmentCount() {
        return visibleFight == null ? 0 : visibleFight.assignments.size();
    }

    String visibleFightSummary() {
        if(visibleFight==null) return "none";
        return visibleFight.type+" id="+visibleFight.id+" world="+visibleFight.world+
            " bodies="+visibleFight.assignments.size()+
            " center="+visibleFight.centerX+","+visibleFight.centerY+","+visibleFight.centerZ;
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

        // Physical HCF loadout matches the authoritative readiness gate.
        int heal=24;
        int pearls=8;
        int speed=2;
        int fire=1;
        if(f.healPots<heal || f.pearls<pearls || f.speedPots<speed || f.firePots<fire) return false;

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
        f.firePots-=fire;

        CombatReservation r=new CombatReservation();
        r.fightId=fightId;
        r.name=p.name;
        r.faction=f.name;
        r.type=type;
        r.healPots=heal;
        r.pearls=pearls;
        r.speedPots=speed;
        r.firePots=fire;
        r.minerIron=type==CombatClass.MINER?24:0;
        combatReservations.put(key(name),r);
        save();
        return true;
    }

    boolean hasCombatReservation(String name) {
        return combatReservations.containsKey(key(name));
    }

    String releaseCombatLoadout(Player body) {
        if(body==null) return "none";
        CombatReservation r=combatReservations.remove(key(body.getName()));
        if(r==null) return "none";

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
        int fires=countPotion(body,(short)8259);
        int pearls=countMaterial(body,Material.ENDER_PEARL);
        f.healPots+=heals;
        f.speedPots+=speeds;
        f.firePots+=fires;
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

        int sharp4=0;
        for(org.bukkit.inventory.ItemStack item:allPhysicalItems(body)) {
            if(item==null || item.getType()!=Material.DIAMOND_SWORD) continue;
            Integer lvl=item.getEnchantments().get(org.bukkit.enchantments.Enchantment.DAMAGE_ALL);
            if(lvl!=null && lvl>=4) sharp4++;
        }
        f.sharp4Swords+=sharp4;

        clearCombatInventory(body);
        save();
        return "heal="+heals+" pearls="+pearls+" speed="+speeds+" fire="+fires+
            " p4="+(diamondPieces/4)+" sharp4="+sharp4;
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

    boolean startFiveVFiveTest(Player observer) {
        if(observer==null || observer.getWorld()==null) return false;
        List<SimFaction> five=new ArrayList<SimFaction>();
        for(SimFaction f:factions.values()) if(f.members.size()>=5) five.add(f);
        if(five.size()<2) return false;

        Collections.sort(five,new Comparator<SimFaction>() {
            public int compare(SimFaction a,SimFaction b) {
                return Integer.compare(teamStrength(b),teamStrength(a));
            }
        });

        SimFaction a=five.get(0), b=five.get(1);
        List<SimPlayer> aa=testTeam(a), bb=testTeam(b);
        if(aa.size()<5 || bb.size()<5) return false;

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
        fight.id="TEST5V5_"+System.currentTimeMillis();
        fight.type="TEST_5V5";
        fight.world=observer.getWorld().getName();
        fight.centerX=cx;fight.centerY=cy;fight.centerZ=cz;
        fight.expiresAt=System.currentTimeMillis()+180000L;

        addFiveVFiveAssignments(fight,a,b,aa,bb,-1);
        addFiveVFiveAssignments(fight,b,a,bb,aa,1);
        visibleFight=fight;
        nextVisibleFightAt=fight.expiresAt+15000L;
        writeCombatFile();
        return true;
    }

    private int teamStrength(SimFaction f) {
        int s=0;
        for(String n:f.members) {
            SimPlayer p=players.get(key(n));
            if(p!=null) s+=p.skill+p.teamwork/3;
        }
        return s;
    }

    private List<SimPlayer> testTeam(SimFaction f) {
        List<SimPlayer> xs=new ArrayList<SimPlayer>();
        for(String n:f.members) {
            SimPlayer p=players.get(key(n));
            if(p!=null) xs.add(p);
        }
        Collections.sort(xs,new Comparator<SimPlayer>() {
            public int compare(SimPlayer a,SimPlayer b){return Integer.compare(b.skill,a.skill);}
        });
        while(xs.size()>5) xs.remove(xs.size()-1);
        return xs;
    }

    private void addFiveVFiveAssignments(VisibleFight fight,SimFaction own,SimFaction enemy,
                                         List<SimPlayer> allies,List<SimPlayer> enemies,int side) {
        SimPlayer bard=bestSupport(allies,true,null);
        SimPlayer archer=bestSupport(allies,false,bard);
        List<SimPlayer> diamonds=new ArrayList<SimPlayer>();
        for(SimPlayer p:allies) if(p!=bard && p!=archer) diamonds.add(p);
        Collections.sort(diamonds,new Comparator<SimPlayer>() {
            public int compare(SimPlayer a,SimPlayer b){return Integer.compare(b.skill,a.skill);}
        });

        SimPlayer enemyBard=bestSupport(enemies,true,null);
        SimPlayer enemyArcher=bestSupport(enemies,false,enemyBard);
        SimPlayer focus;
        // These are deliberately the strongest five-man factions available,
        // so test their ability to kit-snipe support instead of just tunneling
        // the nearest Diamond.
        if(enemyBard!=null && enemyArcher!=null) focus=rng.nextInt(100)<65?enemyBard:enemyArcher;
        else if(enemyBard!=null) focus=enemyBard;
        else if(enemyArcher!=null) focus=enemyArcher;
        else focus=chooseTestFocus(enemies);
        int index=0;
        for(SimPlayer p:allies) {
            CombatAssignment ca=new CombatAssignment();
            ca.fightId=fight.id;ca.name=p.name;ca.faction=own.name;ca.enemyFaction=enemy.name;
            ca.world=fight.world;
            ca.skill=p.skill;ca.aggression=p.aggression;ca.risk=p.riskTolerance;
            ca.homeX=own.baseX;ca.homeY=own.baseY+1;ca.homeZ=own.baseZ;
            ca.trapType="none";ca.focus=focus==null?"":focus.name;
            ca.combatClass=(p==bard)?CombatClass.BARD:((p==archer)?CombatClass.ARCHER:CombatClass.DIAMOND);
            ca.action=(p==bard)?"BARD_SUPPORT":((p==archer)?"ARCHER_RANGE":"FOCUS");
            ca.x=fight.centerX+side*(10+(index%2)*2);
            ca.z=fight.centerZ+(index-2)*3;
            World fightWorld=Bukkit.getWorld(fight.world);
            ca.y=Math.max(4,(fightWorld==null?Bukkit.getWorlds().get(0):fightWorld).getHighestBlockYAt(ca.x,ca.z)+1);
            for(SimPlayer e:enemies) ca.enemies.add(e.name);
            for(SimPlayer m:allies) if(m!=p) ca.allies.add(m.name);
            fight.assignments.put(key(p.name),ca);
            index++;
        }
    }

    private SimPlayer bestSupport(List<SimPlayer> xs,boolean bard,SimPlayer exclude) {
        SimPlayer best=null;int bestScore=Integer.MIN_VALUE;
        for(SimPlayer p:xs) {
            if(p==exclude) continue;
            int score;
            if(bard) score=p.teamwork+p.patience+p.riskTolerance+p.skill/2;
            else score=p.skill+p.aggression+p.riskTolerance+p.teamwork/2;
            if(score>bestScore){best=p;bestScore=score;}
        }
        return best;
    }

    private SimPlayer chooseTestFocus(List<SimPlayer> enemies) {
        if(enemies.isEmpty()) return null;
        SimPlayer best=enemies.get(0);
        int bestScore=Integer.MAX_VALUE;
        for(SimPlayer p:enemies) {
            int score=p.skill+p.teamwork/2+p.riskTolerance/3;
            if(score<bestScore){best=p;bestScore=score;}
        }
        return best;
    }

    void refreshVisibleCombat() {
        if (sotwProtectionActive()) {
            clearVisibleFight();
            return;
        }

        Player observer = nearestHumanObserver();
        if (observer == null) {
            clearVisibleFight();
            return;
        }

        long now=System.currentTimeMillis();
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

        int minS=Math.max(12,plugin.getConfig().getInt("combat-director.visible-fight-min-seconds",25));
        int maxS=Math.max(minS,plugin.getConfig().getInt("combat-director.visible-fight-max-seconds",55));
        nextVisibleFightAt=now+(minS+rng.nextInt(maxS-minS+1))*1000L;

        if (rng.nextInt(100) >= plugin.getConfig().getInt("combat-director.visible-fight-chance-percent",62)) return;

        VisibleFight fight=createVisibleFight(observer);
        if(fight!=null) {
            visibleFight=fight;
            writeCombatFile();
        }
    }

    private Player nearestHumanObserver() {
        Player best=null;
        for(Player p:Bukkit.getOnlinePlayers()) {
            if(plugin.isBotIdentity(p.getName())) continue;
            if(best==null) best=p;
        }
        return best;
    }

    private boolean fightStillRelevant(Player observer, VisibleFight f) {
        if(observer==null || f==null) return false;
        if(f.world==null || !observer.getWorld().getName().equalsIgnoreCase(f.world)) return false;

        for(CombatAssignment ca:f.assignments.values()) {
            SimFaction sf=factions.get(key(ca.faction));
            if(sf!=null && (sf.recoveryMode || plugin.factionRaidable(sf.name))) return false;
        }

        int radius=Math.max(80,plugin.getConfig().getInt("combat-director.observation-radius",160));
        double dx=observer.getLocation().getX()-f.centerX;
        double dz=observer.getLocation().getZ()-f.centerZ;
        return dx*dx+dz*dz <= (double)(radius*2)*(radius*2);
    }

    private VisibleFight createVisibleFight(Player observer) {
        List<SimFaction> ready=new ArrayList<SimFaction>();
        for(SimFaction f:factions.values()) {
            if(f.stage!=Stage.PVP_READY || f.recoveryMode || plugin.factionRaidable(f.name)) continue;
            int active=0;
            for(String member:f.members) {
                SimPlayer p=players.get(key(member));
                if(p!=null && p.logicalOnline && shouldSeekPvp(p.name)) active++;
            }
            if(active>0) ready.add(f);
        }
        if(ready.size()<2) return null;

        final Location ol=observer.getLocation();
        Collections.sort(ready,new Comparator<SimFaction>() {
            public int compare(SimFaction a,SimFaction b) {
                double da=distSq(ol.getX(),ol.getZ(),a.baseX,a.baseZ);
                double db=distSq(ol.getX(),ol.getZ(),b.baseX,b.baseZ);
                return Double.compare(da,db);
            }
        });

        SimFaction a=ready.get(0);

        // Power/creator neighborhoods can occasionally turn into the messy
        // three-faction brawls that old HCF maps were known for. Keep the HOT
        // representation capped at eight bodies; the rest stays authoritative COLD.
        if (ready.size() >= 3 && rng.nextInt(100) <
                plugin.getConfig().getInt("combat-director.three-way-brawl-chance-percent",14)) {
            VisibleFight multi=createThreeWayFight(observer,ready,a);
            if(multi!=null) return multi;
        }

        SimFaction b=null;

        // If this faction is being deliberately camped, use the campers first.
        for(SimFaction candidate:ready) {
            if(candidate==a) continue;
            if(candidate.campTarget!=null && candidate.campTarget.equalsIgnoreCase(a.name)) {
                b=candidate;
                break;
            }
        }

        // Prefer real neighbors/rivals before teleporting a distant rivalry into view.
        int neighborRadius=Math.max(250,plugin.getConfig().getInt("combat-director.brawl-radius",420)*2);
        for(int i=1;b==null && i<ready.size();i++) {
            SimFaction candidate=ready.get(i);
            if(distSq(a.baseX,a.baseZ,candidate.baseX,candidate.baseZ)<=neighborRadius*neighborRadius) {
                b=candidate;
                break;
            }
        }
        if(b==null) b=ready.get(1);

        boolean atBase=observer.getWorld().equals(Bukkit.getWorlds().get(0)) &&
            distSq(ol.getX(),ol.getZ(),a.baseX,a.baseZ) <=
            Math.pow(plugin.getConfig().getInt("combat-director.observation-radius",160)*1.6,2);

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
            double dist=plugin.isHcfSafezone(ol) ? (78+rng.nextInt(35)) : (32+rng.nextInt(36));
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

        for(SimFaction f:ready) {
            if(f==anchor) continue;
            if(distSq(anchor.baseX,anchor.baseZ,f.baseX,f.baseZ)<=radius*radius) nearby.add(f);
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

        List<SimPlayer> aa=pickFightMembers(anchor,Math.min(3,activeFightMembers(anchor)));
        List<SimPlayer> bb=pickFightMembers(b,Math.min(3,activeFightMembers(b)));
        List<SimPlayer> dd=pickFightMembers(d,Math.min(2,activeFightMembers(d)));
        if(aa.isEmpty()||bb.isEmpty()||dd.isEmpty()) return null;

        // Hard physical ceiling for a three-way: 8 represented fighters.
        while(aa.size()+bb.size()+dd.size()>8) {
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
            ca.aggression=p.aggression;
            ca.risk=p.riskTolerance;
            ca.homeX=own.baseX; ca.homeY=own.baseY+1; ca.homeZ=own.baseZ;
            ca.trapX=trap[0]; ca.trapY=trap[1]; ca.trapZ=trap[2];
            ca.trapType=own.trapPreset==null?"none":own.trapPreset;
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
        int r=rng.nextInt(100);
        int sa,sb;
        if(r<18) { sa=1; sb=1; }
        else if(r<34) { sa=1; sb=2; }
        else if(r<56) { sa=2; sb=2; }
        else if(r<70) { sa=2; sb=3; }
        else if(r<84) { sa=3; sb=3; }
        else if(r<92) { sa=3; sb=4; }
        else if(r<97) { sa=4; sb=4; }
        else { sa=1; sb=5; } // rare outnumbered clip / attempted trap / clutch
        sa=Math.min(sa,maxA);
        sb=Math.min(sb,maxB);
        return new int[]{Math.max(1,sa),Math.max(1,sb)};
    }

    private int activeFightMembers(SimFaction f) {
        int n=0;
        for(String member:f.members) {
            SimPlayer p=players.get(key(member));
            if(p!=null && p.logicalOnline && shouldSeekPvp(p.name)) n++;
        }
        return n;
    }

    private List<SimPlayer> pickFightMembers(SimFaction f,int count) {
        List<SimPlayer> xs=new ArrayList<SimPlayer>();
        for(String member:f.members) {
            SimPlayer p=players.get(key(member));
            if(p==null || !p.logicalOnline || !shouldSeekPvp(p.name)) continue;
            xs.add(p);
        }
        Collections.sort(xs,new Comparator<SimPlayer>() {
            public int compare(SimPlayer a,SimPlayer b) {
                int aa=("patrol".equals(a.currentGoal)?30:0)+a.aggression+a.skill/2+a.riskTolerance/3;
                int bb=("patrol".equals(b.currentGoal)?30:0)+b.aggression+b.skill/2+b.riskTolerance/3;
                return Integer.compare(bb,aa);
            }
        });

        List<SimPlayer> chosen=new ArrayList<SimPlayer>();
        // Prefer one Bard and one Archer in larger groups if the faction has them.
        if(count>=3) {
            addFirstClass(xs,chosen,CombatClass.BARD);
            addFirstClass(xs,chosen,CombatClass.ARCHER);
        }
        for(SimPlayer p:xs) {
            if(chosen.size()>=count) break;
            if(!chosen.contains(p)) chosen.add(p);
        }
        while(chosen.size()>count) chosen.remove(chosen.size()-1);
        return chosen;
    }

    private void addFirstClass(List<SimPlayer> xs,List<SimPlayer> out,CombatClass type) {
        for(SimPlayer p:xs) {
            if(p.combatClass==type) {
                out.add(p);
                return;
            }
        }
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
            ca.aggression=p.aggression;
            ca.risk=p.riskTolerance;
            ca.homeX=own.baseX; ca.homeY=own.baseY+1; ca.homeZ=own.baseZ;
            ca.trapX=trap[0]; ca.trapY=trap[1]; ca.trapZ=trap[2];
            ca.trapType=own.trapPreset == null ? "none" : own.trapPreset;
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
            else if(enemies.size()>=allies.size()+2 && p.skill<92) ca.action="KITE_HOME";
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
                int s=p.riskTolerance+p.skill+p.aggression;
                int b=best.riskTolerance+best.skill+best.aggression;
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

            for(CombatAssignment ca:visibleFight.assignments.values()) {
                String b="participants."+key(ca.name);
                y.set(b+".name",ca.name);
                y.set(b+".faction",ca.faction);
                y.set(b+".enemy-faction",ca.enemyFaction);
                y.set(b+".world",ca.world);
                y.set(b+".class",ca.combatClass.name());
                y.set(b+".action",ca.action);
                y.set(b+".skill",ca.skill);
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
                y.set(b+".enemies",ca.enemies);
                y.set(b+".allies",ca.allies);
            }
        }
        try { y.save(combatFile); }
        catch(IOException e) { plugin.getLogger().warning("Could not save combat-hot.yml: "+e.getMessage()); }
    }

    WorkerTask workerTaskFor(String name) {
        SimPlayer p = players.get(key(name));
        WorkerTask t = new WorkerTask();
        t.identity = name;
        t.action = "idle";
        t.faction = "";
        org.bukkit.World world = Bukkit.getWorlds().get(0);
        org.bukkit.Location spawn = world == null ? null : world.getSpawnLocation();
        t.x = spawn == null ? 0 : spawn.getBlockX();
        t.y = spawn == null ? 64 : spawn.getBlockY() + 1;
        t.z = spawn == null ? 0 : spawn.getBlockZ();
        t.priority = 0;

        if (p == null || p.faction.isEmpty()) return t;
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
        int bx = f.baseX;
        int by = f.baseY > 0 ? f.baseY + 1 : t.y;
        int bz = f.baseZ;

        if (f.recoveryMode) {
            t.action = "safe";
            t.x = bx;
            t.y = by;
            t.z = bz;
            t.priority = 95;
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
                t.action = "miner".equals(p.preferredJob) ? "mine" : "gather";
                t.x = bx + 7;
                t.y = by;
                t.z = bz + 7;
                t.priority = "miner".equals(p.preferredJob) ? 92 : 55;
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
                    t.x = bx + 6;
                    t.y = by + 1;
                    t.z = bz - 5;
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
                if ("farmer".equals(p.preferredJob)) {
                    t.action = "farm";
                    t.x = bx - 8;
                    t.y = by;
                    t.z = bz + 8;
                    t.priority = 66;
                } else if ("brewer".equals(p.preferredJob)) {
                    t.action = "brew";
                    t.x = bx + 6;
                    t.y = by + 1;
                    t.z = bz - 5;
                    t.priority = 70;
                } else {
                    t.action = "patrol";
                    t.x = bx;
                    t.y = by;
                    t.z = bz;
                    t.priority = 35;
                }
                break;
        }

        if (p.logicalOnline && p.currentGoal != null && !p.currentGoal.isEmpty()) {
            String g=p.currentGoal;
            if ("mine".equals(g) || "gather".equals(g) || "supply".equals(g) || "build".equals(g) ||
                "farm".equals(g) || "brew".equals(g) || "gear".equals(g) || "patrol".equals(g) ||
                "scout".equals(g) || "safe".equals(g) || "recruit".equals(g)) {
                t.action=g;
                t.priority=Math.max(t.priority,goalPriority(p,g));
            }
        }
        return t;
    }

    private int goalPriority(SimPlayer p,String goal) {
        if ("safe".equals(goal)) return 110;
        if ("build".equals(goal)) return "builder".equals(p.preferredJob)?108:82;
        if ("mine".equals(goal)) return "miner".equals(p.preferredJob)?104:70;
        if ("farm".equals(goal)) return "farmer".equals(p.preferredJob)?102:64;
        if ("brew".equals(goal)) return "brewer".equals(p.preferredJob)?103:65;
        if ("gear".equals(goal)) return 82;
        if ("scout".equals(goal)) return "leader".equals(p.role)?86:52;
        if ("patrol".equals(goal)) return 64 + p.aggression/4;
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
        if (p == null || p.faction.isEmpty()) return "no-sim-player";
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
        if (cane > 0) p.stock.put("cane", getStock(p,"cane") + cane);
        if (cactus > 0) p.stock.put("cactus", getStock(p,"cactus") + cactus);
        if (pumpkin > 0) p.stock.put("pumpkin", getStock(p,"pumpkin") + pumpkin);
        if (melon > 0) p.stock.put("melon", getStock(p,"melon") + melon);

        save();
        return "stone="+stone+" wood="+wood+" iron="+iron+" diamond="+diamond+
            " obsidian="+obsidian+" cane="+cane+" cactus="+cactus+
            " pumpkin="+pumpkin+" melon="+melon;
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
        }

        String[] neutral = {"gg","anyone at spawn","who has pearls","who wants ally","selling stuff msg me","lol","need levels","who is outside","need pots","who has p4"};
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

        SimPlayer respondent = chooseContextResponder(human.getName(), lower);
        if (respondent != null) {
            ContextChatBrain.Snapshot snap = chatSnapshot(human.getName(), respondent, message);
            String prior = lastPublicLineBySpeaker.get(key(respondent.name));
            String reply = prior == null ? chatBrain.reply(snap) : chatBrain.followup(snap, prior);
            if (reply != null && !reply.trim().isEmpty()) {
                enqueue(respondent.name, reply, true);
                rememberPublic(respondent.name, reply);

                // Occasionally another relevant player joins the same thread.
                if (rng.nextInt(100) < plugin.getConfig().getInt("sim-chat.second-responder-chance-percent",22)) {
                    SimPlayer second = chooseSecondResponder(respondent, lower);
                    if (second != null) {
                        ContextChatBrain.Snapshot secondSnap = chatSnapshot(human.getName(), second, message);
                        String secondReply = chatBrain.reply(secondSnap);
                        if (secondReply != null && !secondReply.equalsIgnoreCase(reply)) {
                            enqueue(second.name, secondReply, true);
                            rememberPublic(second.name, secondReply);
                        }
                    }
                }
                return;
            }
        }

        // Legacy intent fallbacks only fire when contextual chat chose silence.
        if (lower.contains("who wants pvp") || lower.contains("1v1") || lower.contains("anyone at spawn")) {
            SimPlayer fighter = findReadyFighter();
            if (fighter != null) enqueue(fighter.name, fighter.skill >= 80 ? "im down" : "give me a min", true);
        }

        if (lower.contains("koth") && !lower.contains("where")) {
            SimPlayer fighter = findReadyFighter();
            if (fighter != null && !fighter.faction.isEmpty()) enqueue(fighter.name, "our fac might go", true);
        }
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
            } else if (lower.contains("pvp") || lower.contains("fight") || lower.contains("1v1") || lower.contains("koth")) {
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
            candidates.addAll(players.values());
        }

        return candidates.get(rng.nextInt(candidates.size()));
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
        if (visibleFight.id != null && visibleFight.id.startsWith("TEST5V5_")) {
            visibleFight = null;
            writeCombatFile();
        }
    }

    void onLiveDeath(String victimName, String killerName) {
        recentVictim = victimName == null ? "" : victimName;
        recentKiller = killerName == null ? "" : killerName;
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
                    vf.healPots = Math.max(0, vf.healPots - 10 - rng.nextInt(11));
                    vf.pearls = Math.max(0, vf.pearls - 2 - rng.nextInt(5));
                    vf.speedPots = Math.max(0, vf.speedPots - 1);
                    vf.firePots = Math.max(0, vf.firePots - 1);

                    if (victim.combatClass == CombatClass.DIAMOND) {
                        vf.p4Sets = Math.max(0, vf.p4Sets - 1);
                        vf.sharp4Swords = Math.max(0, vf.sharp4Swords - 1);
                    } else if (victim.combatClass == CombatClass.BARD) {
                        vf.bardSets = Math.max(0, vf.bardSets - 1);
                    } else if (victim.combatClass == CombatClass.ARCHER) {
                        vf.archerSets = Math.max(0, vf.archerSets - 1);
                    } else if (victim.combatClass == CombatClass.ROGUE) {
                        vf.rogueSets = Math.max(0, vf.rogueSets - 1);
                    }
                }
                updateDtrStrategy(vf);
            }
        }

        if (victim != null && killer != null && !victim.faction.isEmpty() && !killer.faction.isEmpty()
                && !victim.faction.equalsIgnoreCase(killer.faction)) {
            recordRivalry(victim.faction,killer.faction,10 + rng.nextInt(9));
            queueDeathConversation(victim,killer);
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

        for(SimPlayer p:players.values()) {
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
                if("miner".equals(p.preferredJob)) return "mine";
                if("builder".equals(p.preferredJob)) return rng.nextInt(100)<70?"gather":"scout";
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
                if ("PVP".equals(f.archetype) || "TRAPPER".equals(f.archetype)) {
                    if ("farmer".equals(p.preferredJob) && rng.nextInt(100)<18) return "farm";
                    if ("brewer".equals(p.preferredJob) && rng.nextInt(100)<28) return "brew";
                    return rng.nextInt(100)<78 ? "patrol" : "social";
                }
                int rivalryHeat=0;
                String rival=strongestRival(f.name);
                if(!rival.isEmpty()) rivalryHeat=rivalryScore(f.name,rival);
                int roam=p.aggression+p.riskTolerance+p.skill/2+rivalryHeat/2;
                if("farmer".equals(p.preferredJob) && p.economicIq>=70 && rng.nextInt(100)<55) return "farm";
                if("brewer".equals(p.preferredJob) && rng.nextInt(100)<45) return "brew";
                if(roam>=170) return "patrol";
                if(p.economicIq>=78 && rng.nextInt(100)<35) return "trade";
                return rng.nextBoolean()?"patrol":"social";
        }
        return "idle";
    }

    private void tick() {
        sotwTicks++;
        updateLogicalSessionsAndGoals();
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
                }
                break;

            case BUILD_STARTER:
                f.buildProgress = Math.min(f.buildTarget, f.buildProgress + factionBuildWork(f));
                if (f.buildProgress >= f.buildTarget) {
                    f.storage = true;
                    f.stage = Stage.ECONOMY;
                }
                break;

            case ECONOMY:
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
            if (p == null || !p.logicalOnline) continue;

            // HOT bodies are visual projections of this authoritative work.
            // Keeping production here prevents embodiment from stalling SOTW.
            if ("mine".equals(p.currentGoal) || "gather".equals(p.currentGoal) || "supply".equals(p.currentGoal)) {
                int minerBonus = "miner".equals(p.preferredJob) ? 8 : 0;
                f.stone += 20 + minerBonus + rng.nextInt(18);
                f.iron += 2 + ("miner".equals(p.preferredJob) ? 2 : 0) + rng.nextInt(4);
                f.xp += 2 + rng.nextInt(4);
                if (rng.nextInt(100) < (12 + p.economicIq / 6)) f.diamonds += 1;
                if (rng.nextInt(100) < (18 + p.economicIq / 7)) f.obsidian += 1 + rng.nextInt(2);
            } else if ("farm".equals(p.currentGoal)) {
                // Farming is handled by SimEconomyModel so cash/items are conserved.
            } else if ("brew".equals(p.currentGoal) || "gear".equals(p.currentGoal)) {
                // Brewing/gearing consume explicit resources in their dedicated models.
            } else if ("build".equals(p.currentGoal)) {
                f.wood += 8 + rng.nextInt(10);
                f.stone += 8 + rng.nextInt(14);
            } else if ("recruit".equals(p.currentGoal) || "social".equals(p.currentGoal) || "trade".equals(p.currentGoal)) {
                // Social/economic actions intentionally produce no free materials.
            } else {
                f.wood += 4 + rng.nextInt(8);
                f.stone += 4 + rng.nextInt(10);
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
        if (f.treasury < 300) return;
        if (f.obsidian < 8) {
            double unit = plugin.buyUnitPrice("obsidian");
            int n = Math.min(8 - f.obsidian, (int)(f.treasury / unit));
            if (n > 0) {
                f.obsidian += n;
                f.treasury -= n * unit;
            }
        }
        if (f.iron < 35) {
            double unit = plugin.buyUnitPrice("iron");
            int n = Math.min(35 - f.iron, (int)(f.treasury / unit));
            if (n > 0) {
                f.iron += n;
                f.treasury -= n * unit;
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

        // Approximate enough successful level-I book outcomes to build one IV via balanced combining.
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

        int members = Math.max(1, f.members.size());
        double healCost = (plugin.buyUnitPrice("netherwart") + plugin.buyUnitPrice("glisteringmelon")
                         + plugin.buyUnitPrice("glowstone") + plugin.buyUnitPrice("gunpowder")) / 3.0;
        double speedCost = (plugin.buyUnitPrice("netherwart") + plugin.buyUnitPrice("sugar")
                          + plugin.buyUnitPrice("glowstone")) / 3.0;
        double fireCost = (plugin.buyUnitPrice("netherwart") + plugin.buyUnitPrice("magmacream")
                         + plugin.buyUnitPrice("redstone")) / 3.0;

        int healNeed = Math.max(0, members * 28 - f.healPots);
        int healBatch = Math.min(9, healNeed);
        int canHeal = Math.min(healBatch, (int)Math.floor(f.treasury / healCost));
        if (canHeal > 0) {
            f.healPots += canHeal;
            f.treasury -= canHeal * healCost;
        }

        int speedNeed = Math.max(0, members * 3 - f.speedPots);
        int speedBatch = Math.min(3, speedNeed);
        int canSpeed = Math.min(speedBatch, (int)Math.floor(f.treasury / speedCost));
        if (canSpeed > 0) {
            f.speedPots += canSpeed;
            f.treasury -= canSpeed * speedCost;
        }

        int fireNeed = Math.max(0, members * 2 - f.firePots);
        int fireBatch = Math.min(2, fireNeed);
        int canFire = Math.min(fireBatch, (int)Math.floor(f.treasury / fireCost));
        if (canFire > 0) {
            f.firePots += canFire;
            f.treasury -= canFire * fireCost;
        }

        double pearlPrice = plugin.buyUnitPrice("pearl");
        if (f.pearls < members * 8 && f.treasury >= pearlPrice) {
            int buy = Math.min(3, Math.min(members * 8 - f.pearls, (int)(f.treasury / pearlPrice)));
            f.pearls += buy;
            f.treasury -= buy * pearlPrice;
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
            String[] a = {"you guys jumped me","gg i had no pots","why were all of you there","i was trying to kite"};
            String[] b = {"gg","you pushed us first","we saw you outside","shouldve went home"};
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
            p.economicIq = s.getInt("economic-iq", 0);
            p.loyalty = s.getInt("loyalty", 45 + rng.nextInt(51));
            p.riskTolerance = s.getInt("risk-tolerance", 25 + rng.nextInt(71));
            p.sociability = s.getInt("sociability", 25 + rng.nextInt(71));
            p.patience = s.getInt("patience", 25 + rng.nextInt(71));
            p.logicalOnline = s.getBoolean("logical-online", rng.nextInt(100)<45);
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

        sotwTicks = data.getLong("meta.sotw-ticks", 0L);
        sotwStartedAt = data.getLong("meta.sotw-started-at", System.currentTimeMillis());
        factionNameCursor = data.getInt("meta.faction-name-cursor", 0);
        ConfigurationSection rr = data.getConfigurationSection("rivalries");
        if (rr != null) for (String k : rr.getKeys(false)) rivalries.put(k,rr.getInt(k,0));

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

        normalizeFactionClasses();
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
        rivalries.clear();
        pendingChat.clear();
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
            p.balance = plugin.getConfig().getDouble("economy.starting-balance", 500.0);
            p.skill = creatorSkillOverride(p.name, skillRoll());
            p.aggression = 25 + rng.nextInt(66);
            p.bargaining = 25 + rng.nextInt(66);
            p.leadership = 25 + rng.nextInt(71);
            p.teamwork = 35 + rng.nextInt(61);
            p.loyalty = 40 + rng.nextInt(61);
            p.riskTolerance = 20 + rng.nextInt(81);
            p.sociability = 20 + rng.nextInt(81);
            p.patience = 20 + rng.nextInt(81);
            p.logicalOnline = rng.nextInt(100) < 48;
            p.sessionTicksLeft = 5 + rng.nextInt(20);
            p.currentGoal = "idle";
            p.preferredJob = randomJob();
            p.role = p.preferredJob;
            p.combatClass = classFor(p);

            // Every strong/elite identity begins SOTW solo and is expected to
            // form/lead a power faction rather than being auto-slotted under another leader.
            p.leaderCandidate = p.skill >= 80;
            economy.initializePlayer(p);
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
        int sizeRoll = rng.nextInt(100);
        if (best.underdogLeader) {
            f.targetSize = sizeRoll < 25 ? 2 : (sizeRoll < 65 ? 3 : (sizeRoll < 90 ? 4 : 5));
        } else {
            f.targetSize = sizeRoll < 20 ? 3 : (sizeRoll < 70 ? 4 : 5);
        }
        f.targetSize = Math.min(MAX_FACTION_MEMBERS, f.targetSize);
        f.basePreset = BASE_PRESETS[rng.nextInt(BASE_PRESETS.length)];
        f.powerFaction = !best.underdogLeader;
        f.underdog = best.underdogLeader;
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

        if (best == null) return false;
        if (!plugin.joinSimFactionAuthority(f.name, best.name)) return false;
        best.faction = f.name;
        best.role = best.preferredJob;
        f.members.add(best.name);
        contributeToFaction(best, f, 0.12);
        normalizeFactionClasses(f);
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

    private int[] baseMaterialCost(String preset) {
        if ("hcf_compact_2015".equalsIgnoreCase(preset)) return new int[]{90,220,22,0};
        if ("hcf_split_level".equalsIgnoreCase(preset)) return new int[]{78,300,26,0};
        if ("hcf_archer_tower".equalsIgnoreCase(preset)) return new int[]{118,250,22,0};
        if ("hcf_double_layer".equalsIgnoreCase(preset)) return new int[]{72,390,30,6};
        if ("hcf_courtyard".equalsIgnoreCase(preset)) return new int[]{58,320,22,0};
        if ("hcf_brewer_base".equalsIgnoreCase(preset)) return new int[]{72,300,34,0};
        if ("hcf_trap_base".equalsIgnoreCase(preset)) return new int[]{64,270,24,8};
        return new int[]{70,280,24,0};
    }

    private boolean baseMaterialsReady(SimFaction f) {
        int[] cost = baseMaterialCost(f.basePreset);
        return f.wood >= cost[0] && f.stone >= cost[1] && f.iron >= cost[2] && f.obsidian >= cost[3];
    }

    private void consumeBaseMaterials(SimFaction f) {
        int[] cost = baseMaterialCost(f.basePreset);
        f.wood -= cost[0];
        f.stone -= cost[1];
        f.iron -= cost[2];
        f.obsidian -= cost[3];
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
        List<String> claims = baseFootprintClaims(world.getName(),f);
        f.claimRadiusChunks = Math.max(1,(int)Math.ceil(Math.sqrt(claims.size())/2.0));

        if (!plugin.setSimFactionHomeAndClaims(f.name, home, claims)) {
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
        int r=16;
        if ("hcf_courtyard".equalsIgnoreCase(f.basePreset)) r=18;
        else if ("hcf_double_layer".equalsIgnoreCase(f.basePreset)) r=17;
        else if ("hcf_split_level".equalsIgnoreCase(f.basePreset)) r=15;
        if ("fall_trap".equalsIgnoreCase(f.trapPreset)) r=Math.max(r,20);
        return r;
    }

    private List<String> baseFootprintClaims(String world, SimFaction f) {
        int r=baseTerrainRadius(f);
        int minX=f.baseX-r;
        int maxX=f.baseX+r;
        int minZ=f.baseZ-r;
        int maxZ=f.baseZ+r;

        // Reserve the standard farm pad too so faction infrastructure never
        // hangs outside the protected base claim.
        minX=Math.min(minX,f.baseX-26);
        maxX=Math.max(maxX,f.baseX+20);
        minZ=Math.min(minZ,f.baseZ-20);
        maxZ=Math.max(maxZ,f.baseZ+22);

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

        // Recovery factions never get dragged back into a brawl.
        SimFaction loser = weightedBrawlLoser(cluster);
        if (loser == null) return;

        SimPlayer victim = weakestExposedMember(loser);
        if (victim == null) return;

        SimFaction winner = null;
        double bestStrength = -1;
        for (SimFaction candidate : cluster) {
            if (candidate == loser) continue;
            double strength = factionFightStrength(candidate);
            if (winner == null || strength > bestStrength) {
                winner = candidate;
                bestStrength = strength;
            }
        }

        plugin.applySimulatedFactionDeath(loser.name, victim.name);
        if (winner != null) {
            recordRivalry(loser.name,winner.name,12 + rng.nextInt(10));
            SimPlayer killer = players.get(key(winner.leader));
            if (killer != null) queueDeathConversation(victim,killer);
            if (killer != null && plugin.isCreatorIdentity(killer.name)) queueCreatorKillReactions(killer.name);
        }
        if (plugin.isCreatorIdentity(victim.name)) queueCreatorDeathReactions(victim.name);

        // A death also consumes some combat stock rather than duplicating gear/pots.
        loser.healPots = Math.max(0, loser.healPots - 10 - rng.nextInt(10));
        loser.pearls = Math.max(0, loser.pearls - 2 - rng.nextInt(4));
        if (victim.combatClass == CombatClass.DIAMOND && loser.p4Sets > 0) loser.p4Sets--;
        if (loser.sharp4Swords > 0 && victim.combatClass == CombatClass.DIAMOND) loser.sharp4Swords--;

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
            data.set(b + ".economic-iq", p.economicIq);
            data.set(b + ".loyalty", p.loyalty);
            data.set(b + ".risk-tolerance", p.riskTolerance);
            data.set(b + ".sociability", p.sociability);
            data.set(b + ".patience", p.patience);
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

        data.set("rivalries", null);
        for (Map.Entry<String,Integer> e : rivalries.entrySet()) data.set("rivalries." + e.getKey(), e.getValue());

        data.set("meta.schema", 4);
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
