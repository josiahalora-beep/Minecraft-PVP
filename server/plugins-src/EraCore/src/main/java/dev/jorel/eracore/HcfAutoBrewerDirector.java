package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * CPU-bounded, physically inspectable 1.8 HCF auto-brewer.
 *
 * A loaded brewery uses real BrewingStand inventories, real water bottles and
 * real recipe ingredients. Each lane advances at vanilla-like 20 second stages.
 * Unloaded bases stay COLD in SimWorldDirector and do not keep chunks/tile
 * entities ticking just to simulate brewing.
 *
 * The physical coordinates are supplied by the base template anchor system;
 * this controller never guesses that a brewer lives at baseX+20. This keeps the
 * visual machine, worker target and functional brewing lanes on the same blocks.
 *
 * This is an HCF-style functional machine, not a claim that one exact historical
 * Kohi redstone schematic has been recovered.
 */
@SuppressWarnings("deprecation")
final class HcfAutoBrewerDirector {
    private enum Kind { HEAL, SPEED }

    private static final class Lane {
        final Kind kind;
        final int index;
        int stage;
        long finishesAt;
        final List<ItemStack> bottles=new ArrayList<ItemStack>();

        Lane(Kind kind,int index) {
            this.kind=kind;
            this.index=index;
        }

        boolean active() { return !bottles.isEmpty(); }

        void reset() {
            stage=0;
            finishesAt=0L;
            bottles.clear();
        }
    }

    private static final class Site {
        String faction;
        int centerX,floorY,centerZ;
        final List<Lane> lanes=new ArrayList<Lane>();
    }

    private final EraCore plugin;
    private final Map<String,Site> sites=new LinkedHashMap<String,Site>();
    private BukkitTask task;

    HcfAutoBrewerDirector(EraCore plugin) {
        this.plugin=plugin;
    }

    void start() {
        if(task!=null) return;
        task=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){ tick(); }
        },40L,20L);
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        sites.clear();
    }

    void register(String faction,int centerX,int floorY,int centerZ) {
        if(faction==null || faction.trim().isEmpty()) return;
        String key=faction.toLowerCase(Locale.ENGLISH);
        Site s=sites.get(key);
        if(s==null) {
            s=new Site();
            s.faction=faction;
            // Four healing lanes are deliberate: a 5-man HCF faction needs
            // dozens of heals, while speed demand is much lower.
            s.lanes.add(new Lane(Kind.HEAL,0));
            s.lanes.add(new Lane(Kind.HEAL,1));
            s.lanes.add(new Lane(Kind.HEAL,2));
            s.lanes.add(new Lane(Kind.HEAL,3));
            s.lanes.add(new Lane(Kind.SPEED,4));
            s.lanes.add(new Lane(Kind.SPEED,5));
            sites.put(key,s);
        }
        s.centerX=centerX;
        s.floorY=floorY;
        s.centerZ=centerZ;
    }

    boolean physicallyActive(String faction) {
        Site s=sites.get(faction==null?"":faction.toLowerCase(Locale.ENGLISH));
        if(s==null) return false;
        World w=Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().get(0);
        if(w==null) return false;
        return w.isChunkLoaded(s.centerX>>4,s.centerZ>>4);
    }

    private void tick() {
        if(sites.isEmpty() || Bukkit.getWorlds().isEmpty()) return;
        World w=Bukkit.getWorlds().get(0);

        for(Site s:sites.values()) {
            // Critical CPU rule: do not load remote faction chunks merely to
            // advance brewing. The COLD simulation handles those factions.
            if(!w.isChunkLoaded(s.centerX>>4,s.centerZ>>4)) continue;

            Inventory brewing=plugin.simFactionStorage(s.faction,"brewing");
            Inventory pots=plugin.simFactionStorage(s.faction,"pots");
            if(brewing==null || pots==null) continue;

            for(Lane lane:s.lanes) tickLane(w,s,lane,brewing,pots);
        }
    }

    private void tickLane(World w,Site s,Lane lane,Inventory brewing,Inventory pots) {
        long now=System.currentTimeMillis();

        if(!lane.active()) {
            if(plugin.simBrewerNeed(s.faction,kindName(lane.kind))<=0) return;
            if(countFreeSlots(pots)<3) return;

            List<ItemStack> water=takePotions(brewing,(short)0,3);
            if(water.size()<3) {
                for(ItemStack item:water) brewing.addItem(item);
                return;
            }
            lane.bottles.addAll(water);
            lane.stage=0;
            lane.finishesAt=0L;
        }

        if(lane.finishesAt<=0L) {
            Material ingredient=ingredientFor(lane.kind,lane.stage);
            if(ingredient==null) {
                finishLane(s,lane,pots);
                return;
            }
            if(!takeOne(brewing,ingredient)) return;

            BrewingStand stand=standFor(w,s,lane.index);
            if(stand==null) {
                brewing.addItem(new ItemStack(ingredient,1));
                return;
            }

            BrewerInventory inv=stand.getInventory();
            inv.clear();
            for(int i=0;i<Math.min(3,lane.bottles.size());i++) inv.setItem(i,lane.bottles.get(i).clone());
            inv.setIngredient(new ItemStack(ingredient,1));

            // Vanilla 1.8 brewing took ~20 seconds per ingredient. The Bukkit
            // task controls transfer/locking so we do not need a permanent
            // redstone clock ticking in every remote faction chunk.
            lane.finishesAt=now+20000L;
            return;
        }

        if(now<lane.finishesAt) return;

        BrewingStand stand=standFor(w,s,lane.index);
        if(stand==null) return;
        BrewerInventory inv=stand.getInventory();

        short expected=durabilityAfter(lane.kind,lane.stage);
        lane.bottles.clear();
        for(int i=0;i<3;i++) {
            ItemStack item=inv.getItem(i);
            if(item==null || item.getType()!=Material.POTION) item=new ItemStack(Material.POTION,1,expected);
            else item=new ItemStack(Material.POTION,1,expected);
            lane.bottles.add(item);
        }
        inv.clear();

        lane.stage++;
        lane.finishesAt=0L;

        if(ingredientFor(lane.kind,lane.stage)==null) finishLane(s,lane,pots);
    }

    private void finishLane(Site s,Lane lane,Inventory pots) {
        if(lane.bottles.size()<3 || countFreeSlots(pots)<3) return;
        int delivered=0;
        for(ItemStack item:lane.bottles) {
            Map<Integer,ItemStack> overflow=pots.addItem(item.clone());
            if(overflow.isEmpty()) delivered++;
        }
        if(delivered>0) plugin.creditSimBrewedPotions(s.faction,kindName(lane.kind),delivered);
        lane.reset();
    }

    private BrewingStand standFor(World w,Site s,int laneIndex) {
        // Base templates register the exact brewer core. No coordinate guess is
        // allowed here: moving or resizing a base cannot disconnect the machine.
        int x=s.centerX;
        int z=s.centerZ-5+(laneIndex*2);
        Block b=w.getBlockAt(x,s.floorY+2,z);
        if(b.getType()!=Material.BREWING_STAND || !(b.getState() instanceof BrewingStand)) return null;
        return (BrewingStand)b.getState();
    }

    private Material ingredientFor(Kind kind,int stage) {
        if(kind==Kind.HEAL) {
            if(stage==0) return Material.NETHER_STALK;
            if(stage==1) return Material.SPECKLED_MELON;
            if(stage==2) return Material.GLOWSTONE_DUST;
            if(stage==3) return Material.SULPHUR;
            return null;
        }
        if(kind==Kind.SPEED) {
            if(stage==0) return Material.NETHER_STALK;
            if(stage==1) return Material.SUGAR;
            if(stage==2) return Material.GLOWSTONE_DUST;
            return null;
        }
        return null;
    }

    private short durabilityAfter(Kind kind,int stage) {
        if(stage==0) return (short)16; // awkward
        if(kind==Kind.HEAL) {
            if(stage==1) return (short)8197;  // healing I
            if(stage==2) return (short)8229;  // healing II
            return (short)16421;              // splash healing II
        }
        if(kind==Kind.SPEED) {
            if(stage==1) return (short)8194;  // speed I
            return (short)8226;               // speed II
        }
        return (short)0
    }

    private String kindName(Kind kind) {
        if(kind==Kind.HEAL) return "heal";
        return "speed";
    }

    private List<ItemStack> takePotions(Inventory inv,short data,int amount) {
        List<ItemStack> out=new ArrayList<ItemStack>();
        for(int slot=0;slot<inv.getSize() && out.size()<amount;slot++) {
            ItemStack item=inv.getItem(slot);
            if(item==null || item.getType()!=Material.POTION || item.getDurability()!=data) continue;
            int take=Math.min(item.getAmount(),amount-out.size());
            for(int i=0;i<take;i++) out.add(new ItemStack(Material.POTION,1,data));
            int remain=item.getAmount()-take;
            if(remain<=0) inv.setItem(slot,null);
            else { item.setAmount(remain); inv.setItem(slot,item); }
        }
        return out;
    }

    private boolean takeOne(Inventory inv,Material material) {
        for(int slot=0;slot<inv.getSize();slot++) {
            ItemStack item=inv.getItem(slot);
            if(item==null || item.getType()!=material) continue;
            if(item.getAmount()<=1) inv.setItem(slot,null);
            else { item.setAmount(item.getAmount()-1); inv.setItem(slot,item); }
            return true;
        }
        return false;
    }

    private int countFreeSlots(Inventory inv) {
        int n=0;
        for(ItemStack item:inv.getContents()) if(item==null || item.getType()==Material.AIR) n++;
        return n;
    }
}
