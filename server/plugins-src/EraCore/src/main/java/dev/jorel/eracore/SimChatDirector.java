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
    private BukkitTask task;
    private long nextAt;
    private long fanCooldownUntil;

    SimChatDirector(EraCore plugin, SimWorldDirector world) {
        this.plugin = plugin;
        this.world = world;
    }

    void start() {
        if (task != null) return;
        nextAt = System.currentTimeMillis() + 12000L;
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

        if (m.contains("faction") && (m.contains("join") || m.contains("recruit") || m.contains("inv"))) {
            scheduleRecruitmentReplies(player);
        }

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

        if (killer != null && plugin.isCreatorIdentity(killer.getName())) {
            scheduleFanReaction(killer.getName(), "kill");
        } else if (plugin.isCreatorIdentity(victim.getName())) {
            scheduleFanReaction(victim.getName(), "death");
        }
    }

    private void tick() {
        if (!enabled() || !plugin.hasHumanOnline()) return;
        long now = System.currentTimeMillis();
        if (now < nextAt) return;
        emitGeneral();
        nextAt = now + nextDelayMillis();
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

    private void emitGeneral() {
        SimWorldDirector.ChatEvent event = world.nextChatEvent();
        if (event == null) return;

        String name = event.name;
        String line = event.message;
        long now = System.currentTimeMillis();
        Long lastIdentity = identityCooldown.get(name.toLowerCase(Locale.ENGLISH));
        long identityCd = plugin.getConfig().getLong("sim-chat.identity-cooldown-seconds", 75L) * 1000L;
        if (lastIdentity != null && now - lastIdentity < identityCd) return;

        Long lastLine = lineCooldown.get(line);
        long lineCd = plugin.getConfig().getLong("sim-chat.line-cooldown-seconds", 600L) * 1000L;
        if (lastLine != null && now - lastLine < lineCd) return;

        plugin.broadcastSimulatedChat(name, line);
        identityCooldown.put(name.toLowerCase(Locale.ENGLISH), now);
        lineCooldown.put(line, now);

        if (plugin.isCreatorIdentity(name)) {
            scheduleFanReaction(name, "chat");
        }
    }

    private void scheduleRecruitmentReplies(final Player player) {
        List<String> candidates = new ArrayList<String>();
        for (String name : plugin.getConfig().getStringList("sim-chat.roster")) {
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
        fanCooldownUntil = now + plugin.getConfig().getLong("sim-chat.fan-reaction-cooldown-seconds", 28L) * 1000L;

        final List<String> fans = plugin.getConfig().getStringList("sim-chat.fans");
        if (fans.isEmpty()) return;

        int count = 1 + rng.nextInt(Math.max(1, plugin.getConfig().getInt("sim-chat.max-fan-reactions", 2)));
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
            String[] x = {"yo " + creator, creator + " is on", "no way " + creator + " joined", "watch " + creator};
            return x[rng.nextInt(x.length)];
        }
        if ("kill".equals(event)) {
            String[] x = {"gg", creator + " is farming", "that combo", "rip"};
            return x[rng.nextInt(x.length)];
        }
        if ("death".equals(event)) {
            String[] x = {"no way lol", "gg", creator + " actually died", "rip"};
            return x[rng.nextInt(x.length)];
        }
        String[] x = {"lol", "yo " + creator, "gg", "watch chat"};
        return x[rng.nextInt(x.length)];
    }
}
