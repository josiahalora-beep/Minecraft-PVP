package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

final class SimChatDirector {
    private final EraCore plugin;
    private final Random rng = new Random(2014L);
    private final Map<String,Long> identityCooldown = new HashMap<String,Long>();
    private final Map<String,Long> lineCooldown = new HashMap<String,Long>();
    private BukkitTask task;
    private long nextAt;
    private long fanCooldownUntil;

    SimChatDirector(EraCore plugin) {
        this.plugin = plugin;
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
        List<String> names = plugin.getConfig().getStringList("sim-chat.roster");
        List<String> lines = plugin.getConfig().getStringList("sim-chat.lines");
        if (names.isEmpty() || lines.isEmpty()) return;

        String name = chooseIdentity(names);
        String line = chooseLine(lines);
        if (name == null || line == null) return;

        plugin.broadcastSimulatedChat(name, line);
        long now = System.currentTimeMillis();
        identityCooldown.put(name.toLowerCase(Locale.ENGLISH), now);
        lineCooldown.put(line, now);

        if (plugin.isCreatorIdentity(name)) {
            scheduleFanReaction(name, "chat");
        }
    }

    private String chooseIdentity(List<String> names) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("sim-chat.identity-cooldown-seconds", 75L) * 1000L;

        List<String> pool = new ArrayList<String>();
        for (String n : names) {
            if (Bukkit.getPlayerExact(n) != null) continue;
            Long last = identityCooldown.get(n.toLowerCase(Locale.ENGLISH));
            if (last == null || now - last >= cd) pool.add(n);
        }
        if (pool.isEmpty()) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    private String chooseLine(List<String> lines) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("sim-chat.line-cooldown-seconds", 600L) * 1000L;

        List<String> pool = new ArrayList<String>();
        for (String line : lines) {
            Long last = lineCooldown.get(line);
            if (last == null || now - last >= cd) pool.add(line);
        }
        if (pool.isEmpty()) pool.addAll(lines);
        return pool.get(rng.nextInt(pool.size()));
    }

    private void scheduleRecruitmentReplies(final Player player) {
        final String[] recruiters = {"xRico", "SethPvP", "iTzMason", "FrostyHD", "NateMC"};
        final String[] replies = {
            "we got room",
            "msg me",
            "you can join us",
            "we need one more",
            "yeah inv if you want"
        };

        int count = 2 + rng.nextInt(2);
        Set<Integer> used = new HashSet<Integer>();
        for (int i = 0; i < count; i++) {
            int idx;
            do { idx = rng.nextInt(recruiters.length); } while (!used.add(idx));
            final String recruiter = recruiters[idx];
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
