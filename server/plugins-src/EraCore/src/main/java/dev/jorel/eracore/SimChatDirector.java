package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

final class SimChatDirector {
    private final EraCore plugin;
    private final SimWorldDirector world;
    private final Random rng = new Random(2014L);
    private final Map<String,Long> identityCooldown = new HashMap<String,Long>();
    private final Map<String,Long> lineCooldown = new HashMap<String,Long>();
    private final Map<String,Long> semanticCooldown = new LinkedHashMap<String,Long>();
    private final Map<String,Deque<String>> recentBySpeaker = new HashMap<String,Deque<String>>();
    private final Deque<String> recentGlobal = new ArrayDeque<String>();
    private BukkitTask task;
    private long nextAt;
    private long fanCooldownUntil;

    SimChatDirector(EraCore plugin, SimWorldDirector world) {
        this.plugin = plugin;
        this.world = world;
    }

    void start() {
        if (task != null) return;
        nextAt = System.currentTimeMillis() + 2500L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { tick(); }
        }, 20L, 20L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("sim-chat.enabled", true);
    }

    void setEnabled(boolean enabled) {
        plugin.getConfig().set("sim-chat.enabled", enabled);
        plugin.saveConfig();
        if (enabled) nextAt = System.currentTimeMillis() + 2500L;
    }

    void pulse() {
        emitGeneral();
    }

    void onHumanChat(final Player player, String message) {
        if (!enabled() || plugin.isBotIdentity(player.getName())) return;
        String m = message.toLowerCase(Locale.ENGLISH);
        world.onHumanPublicChat(player,message);

        if (plugin.isCreatorIdentity(player.getName())) {
            scheduleFanReaction(player.getName(), "chat");
        }
    }

    void onJoin(Player player) {
        if (!enabled()) return;
        if (plugin.isCreatorIdentity(player.getName())) {
            scheduleFanReaction(player.getName(), "join");
        }
    }

    void onDeath(PlayerDeathEvent event) {
        if (!enabled()) return;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        world.onLiveDeath(victim.getName(), killer == null ? "" : killer.getName());

        // Creator kill/death reactions are queued by SimWorldDirector so
        // real and offscreen deaths use the same pacing and do not double-post.
    }

    private void tick() {
        if (!enabled() || !plugin.hasHumanOnline()) return;
        long now = System.currentTimeMillis();
        if (now < nextAt) return;
        boolean fast = emitGeneral();
        nextAt = now + (fast ? (1400L + rng.nextInt(2200)) : nextDelayMillis());

        // Busy SOTW chat arrives in short uneven bursts, not a metronome.
        int online = world.logicalOnlineCount();
        if (online >= 50 && rng.nextInt(100) < 38) {
            long d1 = 24L + rng.nextInt(34);
            Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
                public void run(){ if(enabled() && plugin.hasHumanOnline()) emitGeneral(); }
            },d1);

            if (online >= 75 && rng.nextInt(100) < 22) {
                long d2 = d1 + 28L + rng.nextInt(42);
                Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
                    public void run(){ if(enabled() && plugin.hasHumanOnline()) emitGeneral(); }
                },d2);
            }
        }
    }

    private long nextDelayMillis() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        int min = plugin.getConfig().getInt("sim-chat.min-delay-seconds", 18);
        int max = plugin.getConfig().getInt("sim-chat.max-delay-seconds", 42);

        if (hour >= 1 && hour < 7) {
            min = plugin.getConfig().getInt("sim-chat.quiet-min-delay-seconds", 55);
            max = plugin.getConfig().getInt("sim-chat.quiet-max-delay-seconds", 120);
        }

        if (max < min) max = min;
        return (min + rng.nextInt(max - min + 1)) * 1000L;
    }

    private boolean emitGeneral() {
        for(int attempt=0;attempt<6;attempt++) {
            SimWorldDirector.ChatEvent event = world.nextChatEvent();
            if (event == null) return false;

            String name = event.name;
            String line = event.message==null?"":event.message.trim();
            if(line.isEmpty()) continue;
            long now = System.currentTimeMillis();
            String speakerKey=name.toLowerCase(Locale.ENGLISH);

            Long lastIdentity = identityCooldown.get(speakerKey);
            long identityCd = (event.fastFollow ? 4L : plugin.getConfig().getLong("sim-chat.identity-cooldown-seconds", 75L)) * 1000L;
            if (lastIdentity != null && now - lastIdentity < identityCd) continue;

            Long lastLine = lineCooldown.get(line.toLowerCase(Locale.ENGLISH));
            long lineCd = (event.fastFollow ? 15L : plugin.getConfig().getLong("sim-chat.line-cooldown-seconds", 600L)) * 1000L;
            if (lastLine != null && now - lastLine < lineCd) continue;

            String semantic=semanticKey(line);
            long semanticWindow=Math.max(60L,plugin.getConfig().getLong("sim-chat.semantic-repeat-window-seconds",1200L))*1000L;
            Long lastSemantic=semanticCooldown.get(semantic);
            if(!event.fastFollow && lastSemantic!=null && now-lastSemantic<semanticWindow) continue;

            Deque<String> own=recentBySpeaker.get(speakerKey);
            if(own==null){own=new ArrayDeque<String>();recentBySpeaker.put(speakerKey,own);}
            if(own.contains(semantic) && !event.fastFollow) continue;

            plugin.broadcastSimulatedChat(name, line);
            identityCooldown.put(speakerKey, now);
            lineCooldown.put(line.toLowerCase(Locale.ENGLISH), now);
            semanticCooldown.put(semantic,now);
            own.addLast(semantic);
            int ownMax=Math.max(4,plugin.getConfig().getInt("sim-chat.per-speaker-recent-window",12));
            while(own.size()>ownMax) own.removeFirst();

            recentGlobal.addLast(semantic);
            int globalMax=Math.max(20,plugin.getConfig().getInt("sim-chat.recent-line-window",80));
            while(recentGlobal.size()>globalMax) recentGlobal.removeFirst();

            while(semanticCooldown.size()>600) {
                Iterator<String> it=semanticCooldown.keySet().iterator();
                if(it.hasNext()){it.next();it.remove();} else break;
            }

            if (plugin.isCreatorIdentity(name)) scheduleFanReaction(name, "chat");
            return event.fastFollow;
        }
        return false;
    }

    private String semanticKey(String line) {
        String s=line==null?"":line.toLowerCase(Locale.ENGLISH);
        s=s.replaceAll("\\$?\\d+(?:\\.\\d+)?","<n>");
        s=s.replaceAll("\\b(?:north|south|east|west)\\b","<dir>");
        s=s.replaceAll("\\s+"," ").trim();

        // Collapse high-frequency HCF sentence families while preserving topics.
        if(s.contains("buying ") || s.contains("wtb ")) return "market:buy:"+marketTopic(s);
        if(s.contains("selling ") || s.contains("wts ")) return "market:sell:"+marketTopic(s);
        if(s.contains("regen") && s.contains("dtr")) return "dtr:regen";
        if(s.contains("spawn") && (s.contains("who")||s.contains("anyone"))) return "presence:spawn";
        if(s.contains("need ") && s.contains("pots")) return "need:pots";
        if(s.contains("need ") && s.contains("pearl")) return "need:pearls";
        if(s.contains("building") && s.contains("base")) return "base:building";
        return s;
    }

    private String marketTopic(String s) {
        String[] topics={"pearls","pearl","pots","potion","diamond","diamonds","glass","obsidian","obby","wart","glowstone","books","book","iron","cane"};
        for(String t:topics) if(s.contains(t)) return t;
        return "other";
    }

    private void scheduleRecruitmentReplies(final Player player) {
        List<String> candidates = new ArrayList<String>();
        for (String name : world.logicalOnlineIdentityNames()) {
            String faction = world.factionOf(name);
            if (faction.isEmpty()) continue;
            if (world.factionMembers(faction).size() >= SimWorldDirector.MAX_FACTION_MEMBERS) continue;
            candidates.add(name);
        }
        if (candidates.isEmpty()) return;
        Collections.shuffle(candidates, rng);

        final String[] replies = {
            "we got room",
            "msg me",
            "you can join us",
            "we need one more",
            "yeah we have a spot"
        };

        int count = Math.min(candidates.size(), 1 + rng.nextInt(3));
        for (int i = 0; i < count; i++) {
            final String recruiter = candidates.get(i);
            final String reply = replies[rng.nextInt(replies.length)];
            long delay = 30L + (i * 38L) + rng.nextInt(28);

            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                public void run() {
                    if (player.isOnline()) plugin.sendSimulatedPrivate(player, recruiter, reply);
                }
            }, delay);
        }
    }

    private void scheduleFanReaction(final String creator, final String event) {
        long now = System.currentTimeMillis();
        if (now < fanCooldownUntil) return;
        fanCooldownUntil = now + plugin.getConfig().getLong("sim-chat.fan-reaction-cooldown-seconds", 10L) * 1000L;

        final List<String> fans = plugin.getConfig().getStringList("sim-chat.fans");
        if (fans.isEmpty()) return;

        int maxFans=Math.max(1,plugin.getConfig().getInt("sim-chat.max-fan-reactions",2));
        int count=1+rng.nextInt(maxFans);
        for (int i = 0; i < count; i++) {
            final String fan = fans.get(rng.nextInt(fans.size()));
            final String msg = fanLine(creator, event);
            long delay = 25L + i * 34L + rng.nextInt(30);

            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                public void run() {
                    if (plugin.hasHumanOnline() && Bukkit.getPlayerExact(fan) == null) {
                        plugin.broadcastSimulatedChat(fan, msg);
                    }
                }
            }, delay);
        }
    }

    private String fanLine(String creator, String event) {
        if ("join".equals(event)) {
            String[] x = {"yo " + creator, creator + " is on", "no way " + creator + " joined", "watch " + creator, creator + " come spawn", "someone fight " + creator, "yt is on lol"};
            return x[rng.nextInt(x.length)];
        }
        if ("kill".equals(event)) {
            String[] x = {"gg", creator + " is farming", "that combo", "rip", "who is fighting " + creator, creator + " is cooking", "clip that"};
            return x[rng.nextInt(x.length)];
        }
        if ("death".equals(event)) {
            String[] x = {"no way lol", "gg", creator + " actually died", "rip", "who killed " + creator, "clip that death lol"};
            return x[rng.nextInt(x.length)];
        }
        String[] x = {"lol", "yo " + creator, "gg", "watch chat", creator + " what faction", creator + " come end", "1v1 me " + creator, "yt chat is active"};
        return x[rng.nextInt(x.length)];
    }
}
