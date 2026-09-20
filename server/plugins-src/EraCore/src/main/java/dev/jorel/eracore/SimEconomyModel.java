package dev.jorel.eracore;

import org.bukkit.Material;

import java.util.*;

/**
 * Persistent low-frequency economy brain.
 *
 * Money is conserved through explicit sources/sinks:
 * - crops/ores are production sources,
 * - server shop sales create money,
 * - shop purchases destroy money,
 * - player-to-player trades only transfer money/items.
 */
final class SimEconomyModel {
    private final EraCore plugin;
    private final Random rng = new Random(14052015L);

    SimEconomyModel(EraCore plugin) {
        this.plugin = plugin;
    }

    void initializePlayer(SimWorldDirector.SimPlayer p) {
        if (p.economicIq <= 0) p.economicIq = economicIqFor(p.name);

        if ("farmer".equals(p.preferredJob) && p.farmCells <= 0) {
            p.farmCrop = chooseCrop(p);
            investStarterFarm(p);
        }
    }

    void tickAll(Collection<SimWorldDirector.SimPlayer> players,
                 Map<String,SimWorldDirector.SimFaction> factions,
                 long directorTick) {
        int cadence = Math.max(2, plugin.getConfig().getInt("economy.cycle-director-ticks", 10));
        if (directorTick % cadence != 0L) return;

        for (SimWorldDirector.SimPlayer p : players) {
            if ("farmer".equals(p.preferredJob)) tickFarm(p, factions);
        }

        matchInternalMarkets(players, factions);
    }

    private int economicIqFor(String name) {
        String n = name.toLowerCase(Locale.ENGLISH);
        if (n.equals("caneking")) return 99;
        if (n.equals("farmed")) return 98;
        if (n.equals("brewmaster")) return 96;
        if (n.equals("minermatt")) return 91;
        int roll = rng.nextInt(100);
        if (roll < 12) return 25 + rng.nextInt(20);
        if (roll < 62) return 45 + rng.nextInt(25);
        if (roll < 92) return 70 + rng.nextInt(20);
        return 90 + rng.nextInt(7);
    }

    private String chooseCrop(SimWorldDirector.SimPlayer p) {
        String[] crops = {"cane","cactus","pumpkin","melon"};

        // Elite economic players evaluate return + liquidity instead of choosing randomly.
        if (p.economicIq >= 90) {
            String best = "cane";
            double bestScore = -1;
            for (String crop : crops) {
                double score = expectedRevenuePerCell(crop) * liquidity(crop) / Math.max(0.01, cellCost(crop));
                if (score > bestScore) {
                    bestScore = score;
                    best = crop;
                }
            }
            return best;
        }

        // Average players still understand farming, but preferences and habits matter.
        int r = rng.nextInt(100);
        if (r < 52) return "cane";
        if (r < 76) return "cactus";
        if (r < 90) return "pumpkin";
        return "melon";
    }

    private void investStarterFarm(SimWorldDirector.SimPlayer p) {
        double reserve = 110.0;
        double fraction;
        if (p.economicIq >= 95) fraction = 0.78;
        else if (p.economicIq >= 80) fraction = 0.70;
        else if (p.economicIq >= 60) fraction = 0.60;
        else fraction = 0.50;

        double budget = Math.max(0.0, Math.min(p.balance - reserve, p.balance * fraction));
        double cell = cellCost(p.farmCrop);
        int cells = Math.max(0, (int)Math.floor(budget / cell));

        // A farm smaller than 12 cells isn't worth building; keep the money instead.
        if (cells < 12) return;

        double spend = cells * cell;
        p.balance -= spend;
        p.farmCells = cells;
        p.farmInvestment += spend;
        p.farmReady = true;
    }

    private void tickFarm(SimWorldDirector.SimPlayer p,
                          Map<String,SimWorldDirector.SimFaction> factions) {
        if (!p.farmReady || p.farmCells <= 0) {
            initializePlayer(p);
            if (!p.farmReady) return;
        }

        double skillMult = 0.78 + (p.economicIq / 100.0) * 0.34;
        double variance = 0.88 + rng.nextDouble() * 0.24;
        int produced = Math.max(1, (int)Math.floor(
            p.farmCells * productionRate(p.farmCrop) * skillMult * variance
        ));

        String stockKey = p.farmCrop.equals("pumpkin") ? "pumpkin" :
                          p.farmCrop.equals("melon") ? "melon" :
                          p.farmCrop;

        addStock(p, stockKey, produced);
        p.farmCycles++;

        // Smart farmers keep some inventory for player demand and liquidate the rest.
        int keep = p.economicIq >= 90 ? Math.min(256, p.farmCells * 2) : Math.min(128, p.farmCells);
        int current = stock(p, stockKey);
        if (current > keep) {
            int sellQty = current - keep;
            double unit = serverSellPrice(stockKey);
            double gross = sellQty * unit;
            if (gross > 0) {
                setStock(p, stockKey, current - sellQty);
                double contribution = p.faction.isEmpty() ? 0.0 : (p.economicIq >= 90 ? 0.30 : 0.22);
                double factionCut = gross * contribution;
                p.balance += gross - factionCut;

                if (!p.faction.isEmpty()) {
                    SimWorldDirector.SimFaction f = factions.get(p.faction.toLowerCase(Locale.ENGLISH));
                    if (f != null) f.treasury += factionCut;
                }
            }
        }

        reinvest(p);
    }

    private void reinvest(SimWorldDirector.SimPlayer p) {
        int maxCells;
        double fraction;
        double reserve;

        if (p.economicIq >= 95) {
            maxCells = 640;
            fraction = 0.72;
            reserve = 180;
        } else if (p.economicIq >= 80) {
            maxCells = 384;
            fraction = 0.55;
            reserve = 220;
        } else if (p.economicIq >= 60) {
            maxCells = 240;
            fraction = 0.38;
            reserve = 260;
        } else {
            maxCells = 144;
            fraction = 0.22;
            reserve = 300;
        }

        if (p.farmCells >= maxCells || p.balance <= reserve) return;

        double available = (p.balance - reserve) * fraction;
        double cost = cellCost(p.farmCrop);
        int add = Math.min(maxCells - p.farmCells, (int)Math.floor(available / cost));
        if (add <= 0) return;

        double spend = add * cost;
        p.balance -= spend;
        p.farmInvestment += spend;
        p.farmCells += add;
    }

    /**
     * AI-to-AI market: fulfill a faction's real resource shortages from another
     * identity before using the infinite server shop. This transfers value only.
     */
    private void matchInternalMarkets(Collection<SimWorldDirector.SimPlayer> players,
                                      Map<String,SimWorldDirector.SimFaction> factions) {
        if (factions.isEmpty()) return;

        for (SimWorldDirector.SimFaction buyerFaction : factions.values()) {
            if (buyerFaction.treasury < 100) continue;

            String item = null;
            int want = 0;

            if (buyerFaction.iron < 24) {
                item = "iron";
                want = 24 - buyerFaction.iron;
            } else if (buyerFaction.stage.ordinal() >= SimWorldDirector.Stage.GEARING.ordinal()
                    && buyerFaction.pearls < Math.max(8, buyerFaction.members.size() * 6)) {
                item = "pearl";
                want = Math.min(8, Math.max(0, buyerFaction.members.size() * 6 - buyerFaction.pearls));
            }

            if (item == null || want <= 0) continue;

            SimWorldDirector.SimPlayer seller = bestSeller(players, buyerFaction.name, item, want);
            if (seller == null) continue;

            int qty = Math.min(want, stock(seller, item));
            if (qty <= 0) continue;

            double serverFallback = item.equals("pearl") ? plugin.buyUnitPrice("pearl") :
                                    Math.max(1.0, plugin.sellUnitPrice(Material.IRON_INGOT) * 1.35);
            double pricePer = serverFallback * (0.82 + (seller.bargaining / 100.0) * 0.10);
            double total = qty * pricePer;
            if (buyerFaction.treasury < total) {
                qty = (int)Math.floor(buyerFaction.treasury / pricePer);
                total = qty * pricePer;
            }
            if (qty <= 0) continue;

            setStock(seller, item, stock(seller, item) - qty);
            seller.balance += total;
            buyerFaction.treasury -= total;

            if (item.equals("iron")) buyerFaction.iron += qty;
            else if (item.equals("pearl")) buyerFaction.pearls += qty;
        }
    }

    private SimWorldDirector.SimPlayer bestSeller(Collection<SimWorldDirector.SimPlayer> players,
                                                   String buyerFaction,
                                                   String item,
                                                   int want) {
        SimWorldDirector.SimPlayer best = null;
        int bestStock = 0;
        for (SimWorldDirector.SimPlayer p : players) {
            if (!p.faction.isEmpty() && p.faction.equalsIgnoreCase(buyerFaction)) continue;
            int have = stock(p,item);
            if (have <= 0) continue;
            if (best == null || have > bestStock || (have == bestStock && p.bargaining < best.bargaining)) {
                best = p;
                bestStock = have;
            }
        }
        return best;
    }

    private double serverSellPrice(String crop) {
        if (crop.equals("cane")) return plugin.sellUnitPrice(Material.SUGAR_CANE);
        if (crop.equals("cactus")) return plugin.sellUnitPrice(Material.CACTUS);
        if (crop.equals("pumpkin")) return plugin.sellUnitPrice(Material.PUMPKIN);
        if (crop.equals("melon")) return plugin.sellUnitPrice(Material.MELON);
        return 0.0;
    }

    private double cellCost(String crop) {
        if (crop.equals("cane")) {
            return plugin.buyUnitPrice("cane") + plugin.buyUnitPrice("sand") + plugin.buyUnitPrice("waterbucket") / 16.0;
        }
        if (crop.equals("cactus")) {
            return plugin.buyUnitPrice("cactus") + plugin.buyUnitPrice("sand");
        }
        if (crop.equals("pumpkin")) {
            return plugin.buyUnitPrice("pumpkinseed") + plugin.buyUnitPrice("dirt") + plugin.buyUnitPrice("waterbucket") / 16.0;
        }
        return plugin.buyUnitPrice("melonseed") + plugin.buyUnitPrice("dirt") + plugin.buyUnitPrice("waterbucket") / 16.0;
    }

    private double productionRate(String crop) {
        if (crop.equals("cane")) return 0.28;
        if (crop.equals("cactus")) return 0.26;
        if (crop.equals("pumpkin")) return 0.11;
        return 0.55;
    }

    private double expectedRevenuePerCell(String crop) {
        return productionRate(crop) * serverSellPrice(crop);
    }

    private double liquidity(String crop) {
        if (crop.equals("cane")) return 1.15;
        if (crop.equals("cactus")) return 1.00;
        if (crop.equals("pumpkin")) return 0.92;
        return 0.88;
    }

    private int stock(SimWorldDirector.SimPlayer p, String item) {
        Integer x = p.stock.get(item);
        return x == null ? 0 : x;
    }

    private void setStock(SimWorldDirector.SimPlayer p, String item, int value) {
        p.stock.put(item, Math.max(0, value));
    }

    private void addStock(SimWorldDirector.SimPlayer p, String item, int amount) {
        setStock(p,item,stock(p,item)+Math.max(0,amount));
    }
}
