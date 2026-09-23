package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.*;

/**
 * Era-style KOTH + Conquest state.  Physical players (including HOT Mineflayer
 * identities) capture the same points humans do; logical/offscreen simulation
 * can react through the persisted events.yml state.
 */
final class HcfEventDirector {
    private static final class CapPoint {
        final String id;
        final int dx,dz;
        String owner="";
        String capturing="";
        int progress=0;
        CapPoint(String id,int dx,int dz){this.id=id;this.dx=dx;this.dz=dz;}
    }

    private final EraCore plugin;
    private final HcfMapDirector map;
    private final File file;
    private final YamlConfiguration data;
    private BukkitTask task;

    private String activeKoth="";
    private String kothController="";
    private String kothControllerPlayer="";
    private int kothProgress=0;
    private boolean kothContested=false;

    private boolean conquestActive=false;
    private final List<CapPoint> conquestPoints=new ArrayList<CapPoint>();
    private final Random rng=new Random(20150808L);
    private long nextAutoAt;
    private long activeStartedAt;
    private long warnedForAt;
    private int autoSequence;
    private final Map<String,Integer> conquestScores=new LinkedHashMap<String,Integer>();

    HcfEventDirector(EraCore plugin,HcfMapDirector map) {
        this.plugin=plugin; this.map=map;
        this.file=new File(plugin.getDataFolder(),"events.yml");
        this.data=YamlConfiguration.loadConfiguration(file);
        conquestPoints.add(new CapPoint("Red",-45,-45));
        conquestPoints.add(new CapPoint("Blue",45,-45));
        conquestPoints.add(new CapPoint("Green",-45,45));
        conquestPoints.add(new CapPoint("Yellow",45,45));
        load();
    }

    void start() {
        if(task!=null) return;
        task=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){ tick(); }
        },20L,20L);
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        save();
    }

    String activeType() {
        if(!activeKoth.isEmpty()) return "KOTH";
        if(conquestActive) return "CONQUEST";
        return "";
    }

    String activeId() {
        if(!activeKoth.isEmpty()) return activeKoth;
        return conquestActive?"conquest":"";
    }

    String publicSummary() {
        if(!activeKoth.isEmpty()) {
            HcfMapDirector.Region r=map.region(activeKoth);
            int cap=Math.max(30,plugin.getConfig().getInt("events.koth-capture-seconds",180));
            int left=Math.max(0,cap-kothProgress);
            return "KOTH "+(r==null?activeKoth:r.name)+" "+(kothController.isEmpty()?"uncontrolled":kothController)+
                " "+left+"s";
        }
        if(conquestActive) return "Conquest "+scoreText();
        return "No active event";
    }

    private void tick() {
        if(!plugin.productionWorldReady()) return;
        tickAutoSchedule();
        if(!activeKoth.isEmpty()) tickKoth();
        if(conquestActive) tickConquest();
    }

    private void tickAutoSchedule() {
        if(!plugin.getConfig().getBoolean("events.auto-schedule.enabled",true)) return;
        long now=System.currentTimeMillis();

        if(!activeKoth.isEmpty() || conquestActive) {
            int maxMinutes=Math.max(5,plugin.getConfig().getInt("events.auto-schedule.max-active-minutes",25));
            if(activeStartedAt>0L && now-activeStartedAt>=maxMinutes*60000L) {
                if(!activeKoth.isEmpty()) stopKoth(true);
                if(conquestActive) stopConquest(true);
                scheduleNextAutoEvent(now);
            }
            return;
        }

        if(nextAutoAt<=0L) {
            scheduleNextAutoEvent(now);
            return;
        }

        int warnMinutes=Math.max(1,plugin.getConfig().getInt("events.auto-schedule.warning-minutes",5));
        if(warnedForAt!=nextAutoAt && now>=nextAutoAt-warnMinutes*60000L && now<nextAutoAt) {
            String id=scheduledEventId();
            HcfMapDirector.Region r="conquest".equals(id)?map.region("conquest"):map.region(id);
            String label="conquest".equals(id)?"Conquest":(r==null?id:r.name);
            Bukkit.broadcastMessage(EraCore.colorText(("&6[Events] &e"+label+
                " &7will begin in &f"+warnMinutes+" minutes&7.")));
            warnedForAt=nextAutoAt;
            save();
            return;
        }

        if(now<nextAutoAt) return;
        if(plugin.simWorldProtectionActive()) {
            nextAutoAt=now+5L*60000L;
            warnedForAt=0L;
            save();
            return;
        }
        if(!plugin.hasHumanOnline()) return;

        int minLogical=Math.max(1,plugin.getConfig().getInt("events.auto-schedule.minimum-logical-online",20));
        if(plugin.simulatedLogicalOnlineCount()<minLogical) {
            nextAutoAt=now+5L*60000L;
            warnedForAt=0L;
            save();
            return;
        }

        String id=scheduledEventId();
        autoSequence++;
        if("conquest".equals(id)) startConquest();
        else startKoth(id);
        activeStartedAt=now;
        nextAutoAt=0L;
        warnedForAt=0L;
        save();
    }

    private void scheduleNextAutoEvent(long now) {
        int min=Math.max(10,plugin.getConfig().getInt("events.auto-schedule.min-gap-minutes",35));
        int max=Math.max(min,plugin.getConfig().getInt("events.auto-schedule.max-gap-minutes",70));
        int gap=min+(max==min?0:rng.nextInt(max-min+1));
        nextAutoAt=now+gap*60000L;
        warnedForAt=0L;
        save();
    }

    private String scheduledEventId() {
        String[] rotation={"classic","egypt","endstyle","frost","nether-koth","conquest","end-koth"};
        Calendar c=Calendar.getInstance();
        int day=Math.max(1,c.get(Calendar.DAY_OF_YEAR));
        return rotation[Math.abs(day+autoSequence)%rotation.length];
    }

    private World worldFor(String key) {
        if("overworld".equals(key)) return Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        for(World w:Bukkit.getWorlds()) {
            if("nether".equals(key) && w.getEnvironment()==World.Environment.NETHER) return w;
            if("end".equals(key) && w.getEnvironment()==World.Environment.THE_END) return w;
        }
        return null;
    }

    private Map<String,List<Player>> factionsAt(World w,double x,double z,double radius) {
        Map<String,List<Player>> out=new LinkedHashMap<String,List<Player>>();
        if(w==null) return out;
        double r2=radius*radius;
        for(Player p:w.getPlayers()) {
            Location l=p.getLocation();
            double dx=l.getX()-x,dz=l.getZ()-z;
            if(dx*dx+dz*dz>r2) continue;
            String faction=plugin.factionNameFor(p.getName());
            if(faction==null || faction.isEmpty()) continue;
            List<Player> ps=out.get(faction.toLowerCase(Locale.ENGLISH));
            if(ps==null){ps=new ArrayList<Player>();out.put(faction.toLowerCase(Locale.ENGLISH),ps);}
            ps.add(p);
        }
        return out;
    }

    private void tickKoth() {
        HcfMapDirector.Region r=map.region(activeKoth);
        if(r==null){stopKoth(false);return;}
        World w=worldFor(r.worldKey);
        Map<String,List<Player>> present=factionsAt(w,r.x,r.z,
            plugin.getConfig().getDouble("events.koth-capture-radius",8.0));

        if(present.size()>1) {
            if(!kothContested) {
                kothContested=true;
                Bukkit.broadcastMessage(EraCore.colorText("&6[KOTH] &e"+r.name+" &7is contested."));
            }
            return;
        }

        kothContested=false;
        if(present.isEmpty()) {
            if(!kothController.isEmpty()) {
                Bukkit.broadcastMessage(EraCore.colorText("&6[KOTH] &f"+kothController+
                    " &7was knocked off &e"+r.name+"&7."));
            }
            kothController="";kothControllerPlayer="";kothProgress=0;
            return;
        }

        Map.Entry<String,List<Player>> one=present.entrySet().iterator().next();
        String faction=plugin.factionNameFor(one.getValue().get(0).getName());
        if(!faction.equalsIgnoreCase(kothController)) {
            kothController=faction;
            kothControllerPlayer=one.getValue().get(0).getName();
            kothProgress=0;
            Bukkit.broadcastMessage(EraCore.colorText("&6[KOTH] &f"+faction+" &7is now controlling &e"+r.name+"&7."));
        }

        kothProgress++;
        int cap=Math.max(30,plugin.getConfig().getInt("events.koth-capture-seconds",180));
        int announce=Math.max(15,plugin.getConfig().getInt("events.koth-announce-every-seconds",30));
        if(kothProgress<cap && kothProgress%announce==0) {
            Bukkit.broadcastMessage(EraCore.colorText("&6[KOTH] &f"+faction+" &7controlling &e"+r.name+
                " &8(&f"+(cap-kothProgress)+"s&8)"));
        }
        if(kothProgress>=cap) {
            Player representative=Bukkit.getPlayerExact(kothControllerPlayer);
            Bukkit.broadcastMessage(EraCore.colorText("&6[KOTH] &f"+faction+" &ahas captured &e"+r.name+"&a!"));
            plugin.rewardKothCapture(faction,representative,r.id);
            stopKoth(false);
        }
        save();
    }

    private void tickConquest() {
        HcfMapDirector.Region c=map.region("conquest");
        if(c==null){stopConquest(false);return;}
        World w=worldFor(c.worldKey);
        int claimSeconds=Math.max(5,plugin.getConfig().getInt("events.conquest-point-capture-seconds",15));
        double radius=plugin.getConfig().getDouble("events.conquest-point-radius",7.0);

        for(CapPoint point:conquestPoints) {
            Map<String,List<Player>> present=factionsAt(w,c.x+point.dx,c.z+point.dz,radius);
            if(present.size()!=1) {
                point.capturing="";point.progress=0;
                continue;
            }
            List<Player> ps=present.values().iterator().next();
            String faction=plugin.factionNameFor(ps.get(0).getName());
            if(faction.equalsIgnoreCase(point.owner)) continue;
            if(!faction.equalsIgnoreCase(point.capturing)) {
                point.capturing=faction;point.progress=0;
            }
            point.progress++;
            if(point.progress>=claimSeconds) {
                point.owner=faction;point.capturing="";point.progress=0;
                Bukkit.broadcastMessage(EraCore.colorText("&c[Conquest] &f"+faction+" &7captured &f"+point.id+"&7."));
            }
        }

        for(CapPoint point:conquestPoints) {
            if(point.owner==null || point.owner.isEmpty()) continue;
            String k=point.owner.toLowerCase(Locale.ENGLISH);
            conquestScores.put(k,conquestScores.containsKey(k)?conquestScores.get(k)+1:1);
        }

        int target=Math.max(60,plugin.getConfig().getInt("events.conquest-score-to-win",300));
        String winner="";
        for(Map.Entry<String,Integer> e:conquestScores.entrySet()) if(e.getValue()>=target){winner=e.getKey();break;}
        if(!winner.isEmpty()) {
            String display=winner;
            for(CapPoint p:conquestPoints) if(p.owner.equalsIgnoreCase(winner)){display=p.owner;break;}
            Bukkit.broadcastMessage(EraCore.colorText("&c[Conquest] &f"+display+" &ahas won Conquest!"));
            plugin.rewardConquestCapture(display);
            stopConquest(false);
            return;
        }

        if(System.currentTimeMillis()/1000L%30L==0L && !conquestScores.isEmpty())
            Bukkit.broadcastMessage(EraCore.colorText("&c[Conquest] &7"+scoreText()));
        save();
    }

    boolean commandEvents(Player p,String[] args) {
        p.sendMessage(EraCore.colorText("&6--- HCF Events ---"));
        p.sendMessage(EraCore.colorText("&eKOTH: &f"+(!activeKoth.isEmpty()?publicSummary():"inactive")));
        p.sendMessage(EraCore.colorText("&cConquest: &f"+(conquestActive?scoreText():"inactive")));
        p.sendMessage(EraCore.colorText("&7Use &f/koth list&7, &f/koth nearest&7, or &f/conquest status&7."));
        return true;
    }

    boolean commandKoth(Player p,String[] args) {
        String sub=args.length==0?"status":args[0].toLowerCase(Locale.ENGLISH);
        if("list".equals(sub)) {
            p.sendMessage(EraCore.colorText("&6--- KOTH Locations ---"));
            for(HcfMapDirector.Region r:map.regions()) {
                if(!"koth".equals(r.type)) continue;
                p.sendMessage(EraCore.colorText("&e"+r.id+" &7- &f"+r.name+" &8("+((int)r.x)+", "+((int)r.z)+")"));
            }
            return true;
        }
        if("nearest".equals(sub)) {
            HcfMapDirector.Region r=map.nearest(p.getLocation(),"koth");
            if(r==null) p.sendMessage(EraCore.colorText("&cNo KOTH in this dimension."));
            else p.sendMessage(EraCore.colorText("&6[KOTH] &e"+r.name+" &7is &f"+r.distance(p.getLocation())+
                "m &7away at &f"+((int)r.x)+", "+((int)r.z)));
            return true;
        }
        if("status".equals(sub)) {
            p.sendMessage(EraCore.colorText("&6[KOTH] &7"+(!activeKoth.isEmpty()?publicSummary():"No KOTH is active.")));
            return true;
        }
        if("start".equals(sub)) {
            if(!plugin.isOwnerPlayer(p)) return true;
            if(args.length<2){p.sendMessage("/koth start <id>");return true;}
            HcfMapDirector.Region r=map.region(args[1]);
            if(r==null || !"koth".equals(r.type)){p.sendMessage(EraCore.colorText("&cUnknown KOTH. Use /koth list."));return true;}
            startKoth(r.id);
            return true;
        }
        if("stop".equals(sub)) {
            if(!plugin.isOwnerPlayer(p)) return true;
            stopKoth(true); return true;
        }
        p.sendMessage("/koth <list|nearest|status|start <id>|stop>");
        return true;
    }

    boolean commandConquest(Player p,String[] args) {
        String sub=args.length==0?"status":args[0].toLowerCase(Locale.ENGLISH);
        if("status".equals(sub)) {
            p.sendMessage(EraCore.colorText("&c[Conquest] &7"+(conquestActive?scoreText():"Inactive.")));
            if(conquestActive) for(CapPoint cp:conquestPoints)
                p.sendMessage(EraCore.colorText("&f"+cp.id+" &7- "+(cp.owner.isEmpty()?"Uncontrolled":"&e"+cp.owner)));
            return true;
        }
        if("start".equals(sub)) {
            if(!plugin.isOwnerPlayer(p)) return true;
            startConquest(); return true;
        }
        if("stop".equals(sub)) {
            if(!plugin.isOwnerPlayer(p)) return true;
            stopConquest(true); return true;
        }
        p.sendMessage("/conquest <status|start|stop>");
        return true;
    }

    void startKoth(String id) {
        if(!plugin.productionWorldReady()) return;
        HcfMapDirector.Region r=map.region(id);
        if(r==null || !"koth".equals(r.type)) return;
        activeKoth=r.id;kothController="";kothControllerPlayer="";kothProgress=0;kothContested=false;
        activeStartedAt=System.currentTimeMillis();
        Bukkit.broadcastMessage(EraCore.colorText("&6[KOTH] &e"+r.name+" &7is now capturable at &f"+
            ((int)r.x)+", "+((int)r.z)+"&7."));
        save();
    }

    void stopKoth(boolean announce) {
        if(announce && !activeKoth.isEmpty()) Bukkit.broadcastMessage(EraCore.colorText("&6[KOTH] &7The active KOTH was stopped."));
        activeKoth="";kothController="";kothControllerPlayer="";kothProgress=0;kothContested=false;activeStartedAt=0L;
        if(plugin.getConfig().getBoolean("events.auto-schedule.enabled",true) && nextAutoAt<=0L)
            scheduleNextAutoEvent(System.currentTimeMillis());
        save();
    }

    void startConquest() {
        if(!plugin.productionWorldReady()) return;
        conquestActive=true;activeStartedAt=System.currentTimeMillis();conquestScores.clear();
        for(CapPoint cp:conquestPoints){cp.owner="";cp.capturing="";cp.progress=0;}
        HcfMapDirector.Region c=map.region("conquest");
        Bukkit.broadcastMessage(EraCore.colorText("&c[Conquest] &7Conquest has begun at &f"+
            ((int)c.x)+", "+((int)c.z)+"&7."));
        save();
    }

    void stopConquest(boolean announce) {
        if(announce && conquestActive) Bukkit.broadcastMessage(EraCore.colorText("&c[Conquest] &7Conquest was stopped."));
        conquestActive=false;activeStartedAt=0L;conquestScores.clear();
        for(CapPoint cp:conquestPoints){cp.owner="";cp.capturing="";cp.progress=0;}
        if(plugin.getConfig().getBoolean("events.auto-schedule.enabled",true) && nextAutoAt<=0L)
            scheduleNextAutoEvent(System.currentTimeMillis());
        save();
    }

    private String scoreText() {
        if(conquestScores.isEmpty()) return "No score yet.";
        List<Map.Entry<String,Integer>> xs=new ArrayList<Map.Entry<String,Integer>>(conquestScores.entrySet());
        Collections.sort(xs,new Comparator<Map.Entry<String,Integer>>() {
            public int compare(Map.Entry<String,Integer>a,Map.Entry<String,Integer>b){return b.getValue()-a.getValue();}
        });
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,Integer> e:xs) {
            if(b.length()>0)b.append(" &8| ");
            b.append("&f").append(e.getKey()).append("&7: &c").append(e.getValue());
        }
        return b.toString();
    }

    void resetForNewMap() {
        activeKoth="";kothController="";kothControllerPlayer="";kothProgress=0;kothContested=false;
        conquestActive=false;activeStartedAt=0L;nextAutoAt=0L;warnedForAt=0L;autoSequence=0;conquestScores.clear();
        for(CapPoint cp:conquestPoints){cp.owner="";cp.capturing="";cp.progress=0;}
        save();
    }

    private void load() {
        activeKoth=data.getString("koth.active","");
        kothController=data.getString("koth.controller","");
        kothControllerPlayer=data.getString("koth.controller-player","");
        kothProgress=data.getInt("koth.progress",0);
        conquestActive=data.getBoolean("conquest.active",false);
        nextAutoAt=data.getLong("auto.next-at",0L);
        activeStartedAt=data.getLong("auto.active-started-at",0L);
        warnedForAt=data.getLong("auto.warned-for-at",0L);
        autoSequence=data.getInt("auto.sequence",0);
        if(data.isConfigurationSection("conquest.scores"))
            for(String k:data.getConfigurationSection("conquest.scores").getKeys(false))
                conquestScores.put(k,data.getInt("conquest.scores."+k));
        for(CapPoint cp:conquestPoints) cp.owner=data.getString("conquest.points."+cp.id+".owner","");
    }

    private void save() {
        data.set("koth.active",activeKoth);
        data.set("koth.controller",kothController);
        data.set("koth.controller-player",kothControllerPlayer);
        data.set("koth.progress",kothProgress);
        data.set("conquest.active",conquestActive);
        data.set("auto.next-at",nextAutoAt);
        data.set("auto.active-started-at",activeStartedAt);
        data.set("auto.warned-for-at",warnedForAt);
        data.set("auto.sequence",autoSequence);
        data.set("conquest.scores",null);
        for(Map.Entry<String,Integer> e:conquestScores.entrySet()) data.set("conquest.scores."+e.getKey(),e.getValue());
        for(CapPoint cp:conquestPoints) data.set("conquest.points."+cp.id+".owner",cp.owner);
        try{data.save(file);}catch(IOException e){plugin.getLogger().warning("Could not save events.yml: "+e.getMessage());}
    }
}
