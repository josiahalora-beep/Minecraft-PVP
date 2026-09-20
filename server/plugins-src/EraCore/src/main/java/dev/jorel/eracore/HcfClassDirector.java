package dev.jorel.eracore;

import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

final class HcfClassDirector implements Listener {
    private final EraCore plugin;
    private final Map<String,Double> bardEnergy = new HashMap<String,Double>();
    private final Map<String,Long> rogueBackstab = new HashMap<String,Long>();
    private final Map<String,Long> archerTagged = new HashMap<String,Long>();
    private BukkitTask task;

    HcfClassDirector(EraCore plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("hcf-classes.enabled", true)) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { tick(); }
        }, 20L, 20L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (isBard(p)) {
                effect(p, PotionEffectType.SPEED, 60, 1);
                effect(p, PotionEffectType.WEAKNESS, 60, 3);
                effect(p, PotionEffectType.REGENERATION, 60, 0);
                effect(p, PotionEffectType.DAMAGE_RESISTANCE, 60, 1);

                String k = p.getName().toLowerCase(Locale.ENGLISH);
                double energy = Math.min(100.0, bardEnergy.containsKey(k) ? bardEnergy.get(k) + 6.0 : 100.0);
                bardEnergy.put(k, energy);
                applyHeldBardAura(p);
            } else if (isArcher(p)) {
                effect(p, PotionEffectType.SPEED, 60, 2);
            } else if (isMiner(p)) {
                effect(p, PotionEffectType.FAST_DIGGING, 60, 1);
                effect(p, PotionEffectType.NIGHT_VISION, 240, 0);
                if (p.getLocation().getY() <= plugin.getConfig().getInt("hcf-classes.miner-invisibility-y", 20)) {
                    effect(p, PotionEffectType.INVISIBILITY, 60, 0);
                }
            } else if (isRogue(p)) {
                effect(p, PotionEffectType.SPEED, 60, 2);
                effect(p, PotionEffectType.JUMP, 60, 1);
            }
        }
    }

    private void applyHeldBardAura(Player bard) {
        ItemStack hand = bard.getItemInHand();
        if (hand == null) return;

        PotionEffectType type = null;
        int amplifier = 0;

        if (hand.getType() == Material.BLAZE_ROD) {
            type = PotionEffectType.INCREASE_DAMAGE;
            amplifier = 0;
        } else if (hand.getType() == Material.GHAST_TEAR) {
            type = PotionEffectType.REGENERATION;
            amplifier = 0;
        } else if (hand.getType() == Material.FEATHER) {
            type = PotionEffectType.JUMP;
            amplifier = 1;
        } else if (hand.getType() == Material.MAGMA_CREAM) {
            type = PotionEffectType.FIRE_RESISTANCE;
            amplifier = 0;
        }

        if (type == null) return;
        applyFactionAura(bard, type, amplifier, 50);
    }

    private void applyFactionAura(Player bard, PotionEffectType type, int amplifier, int ticks) {
        double radius = plugin.getConfig().getDouble("hcf-classes.bard-radius", 30.0);
        double r2 = radius * radius;
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (!target.getWorld().equals(bard.getWorld())) continue;
            if (target.getLocation().distanceSquared(bard.getLocation()) > r2) continue;
            if (!plugin.sameFactionForClasses(bard.getName(), target.getName()) && !target.equals(bard)) continue;
            effect(target, type, ticks, amplifier);
        }
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onBardClick(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isBard(p)) return;
        if (!e.getAction().name().startsWith("RIGHT_CLICK")) return;

        ItemStack item = e.getItem();
        if (item == null) return;

        PotionEffectType type = null;
        int amplifier = 0;
        double cost = 0;

        if (item.getType() == Material.BLAZE_POWDER) {
            type = PotionEffectType.INCREASE_DAMAGE;
            amplifier = 1;
            cost = 60;
        } else if (item.getType() == Material.SUGAR) {
            type = PotionEffectType.SPEED;
            amplifier = 2;
            cost = 45;
        } else if (item.getType() == Material.GHAST_TEAR) {
            type = PotionEffectType.REGENERATION;
            amplifier = 2;
            cost = 50;
        } else if (item.getType() == Material.MAGMA_CREAM) {
            type = PotionEffectType.FIRE_RESISTANCE;
            amplifier = 0;
            cost = 35;
        } else {
            return;
        }

        String k = p.getName().toLowerCase(Locale.ENGLISH);
        double energy = bardEnergy.containsKey(k) ? bardEnergy.get(k) : 100.0;
        if (energy < cost) {
            p.sendMessage(EraCore.colorText("&7Bard energy: &c" + (int)energy + "&7/&f100"));
            return;
        }

        bardEnergy.put(k, energy - cost);
        applyFactionAura(p, type, amplifier, plugin.getConfig().getInt("hcf-classes.bard-click-seconds", 5) * 20);
        p.sendMessage(EraCore.colorText("&7Bard energy: &f" + (int)(energy - cost) + "&7/&f100"));
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onArcherDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Arrow) || !(e.getEntity() instanceof Player)) return;
        Arrow arrow = (Arrow)e.getDamager();
        if (!(arrow.getShooter() instanceof Player)) return;

        Player shooter = (Player)arrow.getShooter();
        if (!isArcher(shooter)) return;

        double dist = shooter.getLocation().distance(e.getEntity().getLocation());
        double perBlock = plugin.getConfig().getDouble("hcf-classes.archer-bonus-per-block", 0.08);
        double maxBonus = plugin.getConfig().getDouble("hcf-classes.archer-max-bonus-damage", 4.0);
        double bonus = Math.min(maxBonus, Math.max(0.0, dist - 5.0) * perBlock);
        e.setDamage(e.getDamage() + bonus);

        Player target=(Player)e.getEntity();
        long seconds=Math.max(3L,plugin.getConfig().getLong("safezones.archer-tag-seconds",10L));
        archerTagged.put(target.getName().toLowerCase(Locale.ENGLISH),System.currentTimeMillis()+seconds*1000L);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onArcherTaggedDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim=(Player)e.getEntity();
        Long until=archerTagged.get(victim.getName().toLowerCase(Locale.ENGLISH));
        if(until==null) return;
        if(until<=System.currentTimeMillis()) {
            archerTagged.remove(victim.getName().toLowerCase(Locale.ENGLISH));
            return;
        }

        Player attacker=null;
        if(e.getDamager() instanceof Player) attacker=(Player)e.getDamager();
        else if(e.getDamager() instanceof Arrow) {
            Object shooter=((Arrow)e.getDamager()).getShooter();
            if(shooter instanceof Player) attacker=(Player)shooter;
        }
        if(attacker==null || plugin.sameFactionForClasses(attacker.getName(),victim.getName())) return;

        double mult=Math.max(1.0,plugin.getConfig().getDouble("safezones.archer-tag-damage-multiplier",1.20));
        e.setDamage(e.getDamage()*mult);
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onRogueBackstab(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
        Player rogue = (Player)e.getDamager();
        Player target = (Player)e.getEntity();
        if (!isRogue(rogue)) return;

        ItemStack hand = rogue.getItemInHand();
        if (hand == null || hand.getType() != Material.GOLD_SWORD) return;

        String k = rogue.getName().toLowerCase(Locale.ENGLISH);
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("hcf-classes.rogue-backstab-cooldown-seconds", 20L) * 1000L;
        Long last = rogueBackstab.get(k);
        if (last != null && now - last < cd) return;

        Vector targetFacing = target.getLocation().getDirection().setY(0).normalize();
        Vector fromTargetToRogue = rogue.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        if (fromTargetToRogue.lengthSquared() < 0.001) return;
        fromTargetToRogue.normalize();

        // Rogue must be substantially behind the target.
        if (targetFacing.dot(fromTargetToRogue) > -0.45) return;

        rogueBackstab.put(k, now);
        e.setDamage(e.getDamage() + plugin.getConfig().getDouble("hcf-classes.rogue-backstab-bonus-damage", 7.0));
    }

    private boolean isBard(Player p) {
        return fullSet(p, Material.GOLD_HELMET, Material.GOLD_CHESTPLATE, Material.GOLD_LEGGINGS, Material.GOLD_BOOTS);
    }

    private boolean isArcher(Player p) {
        return fullSet(p, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);
    }

    private boolean isMiner(Player p) {
        return fullSet(p, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
    }

    private boolean isRogue(Player p) {
        return fullSet(p, Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS);
    }

    private boolean fullSet(Player p, Material h, Material c, Material l, Material b) {
        ItemStack[] a = p.getInventory().getArmorContents();
        return a != null && a.length == 4
            && a[3] != null && a[3].getType() == h
            && a[2] != null && a[2].getType() == c
            && a[1] != null && a[1].getType() == l
            && a[0] != null && a[0].getType() == b;
    }

    private void effect(Player p, PotionEffectType type, int ticks, int amp) {
        p.addPotionEffect(new PotionEffect(type, ticks, amp, true), true);
    }
}
