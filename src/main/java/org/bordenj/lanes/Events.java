package org.bordenj.lanes;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.NonNull;

import java.util.*;

import static org.bukkit.Bukkit.createBlockData;

public class Events implements Listener {

    HashMap<LivingEntity, String> creeps = new HashMap<>();
    HashMap<LivingEntity, LivingEntity> targets = new HashMap<>();

    Set<LivingEntity> towers = new HashSet<>();
    Set<LivingEntity> banners = new HashSet<>();

    Map<UUID, String> playerSides = new HashMap<>();
    Map<UUID, Integer> aggressorUntil = new HashMap<>();   // player -> tick the flag expires
    Map<Mob, Player> forcedTargets = new HashMap<>();      // minions told to chase an aggressor

    List<TextDisplay> towerDisplays = new ArrayList<>();

    enum MinionTypes {MELEE,RANGED,BANNER,TOWER}
    Map<Mob, MinionTypes> types = new HashMap<>();

    Location blue = new Location(Bukkit.getWorld("world"),-59.5,-60,-12.5);
    Location red = new Location(Bukkit.getWorld("world"),60.5,-60,-12.5);

    Location blueTowerBeam = new Location(Bukkit.getWorld("world"),-22.5,-56,-10.5);
    Location redTowerBeam = new Location(Bukkit.getWorld("world"),25,-53.5,-12.5);

    Location blueTowerBase = new Location(Bukkit.getWorld("world"),-25,-60,-12.5);
    Location redTowerBase = new Location(Bukkit.getWorld("world"),26,-60,-12.5);

    private static final double PlayerTargetPenalty = 150.0;
    private static final double AggressorBonus = 300.0;
    private static final int AggressorTicks = 5 * 20;
    private static final double AggroCallRange = 12.0;
    private static final double TowerRange = 10.0;
    private static final double TowerHealth = 200.0;
    private static final double TowerTargetPenalty = 100.0;

    private static final double BannerTargetBonus = 50.0;

    private static final double BeamMinDamage = 1.0;
    private static final double BeamMaxDamage = 6.0;
    private static final int BeamRampTicks = 5*20;       // ticks til full powa
    private static final int BeamDamageInterval = 15;   // ticks between dmg
    private static final double BeamMinRadius = 0.02;
    private static final double BeamMaxRadius = 0.20;


    private static final double CrowdScore = 5;

    private static final double MeleeAggroRange = 10.0;
    private static final double MeleeAttackRange = 2.0;
    private static final double MeleeMaxLeash = 6.0;
    private static final int MeleeCooldown = 40; //ticks

    private static final double RangedAggroRange = 14.0;
    private static final double RangedAttackRange = 7.0;
    private static final double RangedMaxLeash = 6.0;
    private static final int RangedCooldown = 50; //ticks

    private static final double BannerHealRange = 14.0;
    private static final int BannerHealCooldown = 8*20; //ticks
    private static final double BannerAttackRange = 5.0;


    private static final double LaneSpacing = 1.0;

    private static final double BannerMissileSpeed = 1.5;
    private static final double MissileSpeed = 0.3;
    private static final double MissileDamage = 2;
    private static final double MeleeDamage = 4.0;



    private final Lanes plugin;
    public Events(Lanes plugin){
        this.plugin=plugin;
    }

    //testing everyone bluey
    private String sideOf(Player p){
        return playerSides.computeIfAbsent(p.getUniqueId(), k -> "BLUE");
    }

    private boolean targetablePlayer(Player p){
        return p.isOnline() && p.isValid()
                && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    private boolean isAggressor(Player p){
        return aggressorUntil.getOrDefault(p.getUniqueId(), 0) > Bukkit.getCurrentTick();
    }

    private Player getForced(Mob minion){
        Player p = forcedTargets.get(minion);
        if(p == null) return null;
        if(!targetablePlayer(p) || !isAggressor(p)){
            forcedTargets.remove(minion);
            return null;
        }
        return p;
    }

    private void markAggressor(Player attacker, Player victim){
        aggressorUntil.put(attacker.getUniqueId(), Bukkit.getCurrentTick() + AggressorTicks);
        String victimSide = sideOf(victim);

        for(Map.Entry<LivingEntity, String> en : creeps.entrySet()){
            if(!en.getValue().equals(victimSide)) continue;
            if(!(en.getKey() instanceof Mob mob) || towers.contains(mob) || !mob.isValid()) continue;
            if(mob.getWorld() != victim.getWorld()) continue;
            if(mob.getLocation().distanceSquared(victim.getLocation()) <= AggroCallRange * AggroCallRange){
                forcedTargets.put(mob, attacker);
            }
        }
    }

    public void DEBUGtowers(){
        spawnTower("RED",redTowerBase,redTowerBeam);
        spawnTower("BLUE",blueTowerBase,blueTowerBeam);
    }

    public void cleanup(){
        for(LivingEntity e : new ArrayList<>(creeps.keySet())){
            for(Entity p : e.getPassengers()) p.remove();
            e.remove();
        }
        creeps.clear();
        targets.clear();
        towers.clear();
        banners.clear();
        types.clear();
        playerSides.clear();
        aggressorUntil.clear();
        forcedTargets.clear();

        towerDisplays.forEach(Entity::remove);
        towerDisplays.clear();
    }

    private void drawCircle(Location center, double radius, Color color, float size){
        World world = center.getWorld();
        int points = Math.max(16, (int) (2 * Math.PI * radius / 0.5)); //spacing = 0.5
        Particle.DustOptions opts = new Particle.DustOptions(color, size);

        for(int i = 0; i < points; i++){
            double a = i * (Math.PI * 2 / points);
            world.spawnParticle(Particle.DUST,
                    center.getX() + Math.cos(a) * radius,
                    center.getY(),
                    center.getZ() + Math.sin(a) * radius,
                    1, 0.0, 0.0, 0.0, 0.0, opts);
        }
    }

    private void giveGold(Location loc, Player p,int amount){
        ItemDisplay ingot = loc.getWorld().spawn(loc.clone().add(0, 1, 0), ItemDisplay.class,d->{
            d.setPersistent(false);
            d.setItemStack(new ItemStack(Material.GOLD_INGOT));
            d.setBillboard(Display.Billboard.CENTER);
            d.setTeleportDuration(1);
            d.setTransformation(new Transformation(
                    new Vector3f(),
                    new AxisAngle4f(),
                    new Vector3f(0.4f, 0.4f, 0.4f),
                    new AxisAngle4f()));
        });

        new BukkitRunnable() {
            int age = 0;
            final int life =7;
            @Override
            public void run() {
                if(!ingot.isValid() || age>=life){
                    ingot.remove();
                    cancel();
                    return;
                }
                ingot.teleport(loc.clone().add(0, 1.0+age*0.12, 0));
                age++;
            }
        }.runTaskTimer(plugin,0,1);
        //GIVE PLAYER GOLD ON BOARD LATER
    }

    private void drawBeam(Location from, LivingEntity target, double radius, Color color){
        World world = from.getWorld();
        Location end = target.getBoundingBox().getCenter().toLocation(world);
        Vector dir = end.toVector().subtract(from.toVector());
        double len = dir.length();
        if(len < 0.01) return;
        dir.normalize();

        Vector up = Math.abs(dir.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector right = dir.clone().crossProduct(up).normalize();
        Vector perp = right.clone().crossProduct(dir).normalize();

        Particle.DustOptions opts = new Particle.DustOptions(color, (float) (0.4 + radius * 2));
        int ringPoints = radius < 0.08 ? 1 : 6;

        for(double d = 0; d <= len; d += 0.3){
            Location center = from.clone().add(dir.clone().multiply(d));
            if(ringPoints == 1){
                world.spawnParticle(Particle.DUST, center, 1, 0.0, 0.0, 0.0, 0.0, opts);
                continue;
            }
            for(int i = 0; i < ringPoints; i++){
                double a = i * (Math.PI * 2 / ringPoints);
                Location p = center.clone()
                        .add(right.clone().multiply(Math.cos(a) * radius))
                        .add(perp.clone().multiply(Math.sin(a) * radius));
                world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, opts);
            }
        }
    }

    private void shootMissile(LivingEntity shooter, LivingEntity target){
        World world = shooter.getWorld();
        Location pos = shooter.getEyeLocation();

        new BukkitRunnable() {
            @Override
            public void run() {
                // target died
                if(!target.isValid()){
                    cancel();
                    return;
                }

                //shooter dead
                if(!shooter.isValid()){
                    cancel();
                    return;
                }

                Vector toTarget = target.getBoundingBox().getCenter().subtract(pos.toVector());
                double dist = toTarget.length();

                //hit target
                if(dist <= MissileSpeed){
                    Location hit = target.getBoundingBox().getCenter().toLocation(world);
                    target.damage(MissileDamage, shooter);
                    world.spawnParticle(Particle.ENCHANTED_HIT, hit, 5,0,0,0,1);
                    //world.spawnParticle(Particle.CRIT, hit, 15, 0.3, 0.3, 0.3, 0.2);
                    cancel();
                    return;
                }

                pos.add(toTarget.normalize().multiply(MissileSpeed));
                world.spawnParticle(Particle.ENCHANTED_HIT, pos, 4, 0.02, 0.02, 0.02, 0);
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    private void shootHealMissile(LivingEntity shooter, LivingEntity target,double healpercent){
        World world = shooter.getWorld();
        Location pos = shooter.getEyeLocation();

        new BukkitRunnable() {
            @Override
            public void run() {
                // target died
                if(!target.isValid()){
                    cancel();
                    return;
                }

                //shooter dead
                if(!shooter.isValid()){
                    cancel();
                    return;
                }

                Vector toTarget = target.getBoundingBox().getCenter().subtract(pos.toVector());
                double dist = toTarget.length();

                //hit target
                if(dist <= BannerMissileSpeed){
                    Location hit = target.getBoundingBox().getCenter().toLocation(world);
                    double maxHP = Objects.requireNonNull(target.getAttribute(Attribute.MAX_HEALTH)).getValue();
                    double heal = Math.max((maxHP - target.getHealth()) * healpercent, 1.0);
                    target.setHealth(Math.min(maxHP, target.getHealth() + heal));
                    world.spawnParticle(Particle.HEART, hit, 1, 0, 0, 0);
                    cancel();
                    return;
                }

                Vector step = toTarget.normalize().multiply(BannerMissileSpeed);

                // draw missile
                int samples = 7;
                for(int i = 1; i <= samples; i++){
                    Location p = pos.clone().add(step.clone().multiply(i / (double) samples));
                    world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0,
                            new Particle.DustOptions(Color.LIME,1.0f));
                }

                pos.add(step);
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    private int attackerCounter(LivingEntity target,Mob minion){
        int count = 0;
        for(Map.Entry<LivingEntity, LivingEntity> e:targets.entrySet()){
            if(e.getValue()==target && e.getKey()!=minion){
                count++;
            }
        }
        return count;
    }

    private void doBannerHeal(Mob banner, String side){
        new BukkitRunnable(){
            @Override
            public void run() {
                if(!banner.isValid()){
                    cancel();
                    return;
                }

                for(Map.Entry<LivingEntity,String>e : creeps.entrySet()){
                    LivingEntity candidate =  e.getKey();
                    if(candidate!=banner && candidate.isValid()&&!towers.contains(candidate)){
                        double max = Objects.requireNonNull(candidate.getAttribute(Attribute.MAX_HEALTH)).getValue();
                        if(e.getValue().equals(side)&&candidate.getHealth()!=max){
                            if(candidate.getLocation().distanceSquared(banner.getLocation())<=BannerHealRange*BannerHealRange){
                                shootHealMissile(banner,candidate,0.30);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin,5*20,BannerHealCooldown);
    }

    private void minionManager(Mob mob, String side, Location goal, Goal<Mob> attackGoal){
        creeps.put(mob, side);
        attachHealthBar(mob, side);
        MobGoals goals = Bukkit.getMobGoals();
        goals.removeAllGoals(mob);
        Bukkit.getMobGoals().removeAllGoals(mob,GoalType.TARGET);
        mob.setTarget(null);
        goals.addGoal(mob,1,attackGoal);
        goals.addGoal(mob,2,new MinionWalkLane(mob,goal));

        new BukkitRunnable() {
            @Override
            public void run() {
                if(!mob.isValid()|| mob.getLocation().distanceSquared(goal)<3){
                    for(Entity passenger : mob.getPassengers()){
                        passenger.remove();
                    }
                    mob.remove();
                    creeps.remove(mob);
                    targets.remove(mob);
                    banners.remove(mob);
                    types.remove(mob);
                    forcedTargets.remove(mob);
                    this.cancel();

                    this.cancel();
                }

            }
        }.runTaskTimer(plugin,0,10);
    }

    private Component healthBar(String side, double hp, double max){
        int total = 20;
        int filled = (int) Math.ceil(hp / max * total);
        NamedTextColor color = side.equals("BLUE") ? NamedTextColor.AQUA : NamedTextColor.RED;
        NamedTextColor bar_color = side.equals("BLUE") ? NamedTextColor.DARK_BLUE : NamedTextColor.DARK_RED;
        return Component.text(side + " Tower", color)
                .append(Component.text("|".repeat(filled), bar_color))
                .append(Component.text("|".repeat(total - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + (int) Math.ceil(hp) + "/" + (int) max, NamedTextColor.WHITE));
    }

    private Component minionHealthBar(String side, double hp, double max){
        int total = 10;
        int filled = (int) Math.ceil(Math.max(0, hp) / max * total);
        NamedTextColor on = side.equals("BLUE") ? NamedTextColor.BLUE : NamedTextColor.RED;
        return Component.text("|".repeat(filled), on)
                .append(Component.text("|".repeat(total - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + (int) Math.ceil(hp) + "/" + (int) max, NamedTextColor.WHITE));
    }

    private void attachHealthBar(Mob mob, String side){
        double max = Objects.requireNonNull(mob.getAttribute(Attribute.MAX_HEALTH)).getValue();

        TextDisplay bar = mob.getWorld().spawn(mob.getLocation(), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setDefaultBackground(false);
            d.setViewRange(0.2f);
            d.text(minionHealthBar(side, mob.getHealth(), max));
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0.35f, 0f),
                    new AxisAngle4f(),
                    new Vector3f(0.8f, 0.8f, 0.8f),
                    new AxisAngle4f()));
        });
        mob.addPassenger(bar);

        new BukkitRunnable() {
            int lastFilled = -1;

            @Override
            public void run() {
                if(!mob.isValid() || !bar.isValid()){
                    bar.remove();
                    cancel();
                    return;
                }

                int filled = (int) Math.ceil(Math.max(0, mob.getHealth()) / max * 10);
                if(filled != lastFilled){
                    bar.text(minionHealthBar(side, mob.getHealth(), max));
                    lastFilled = filled;
                }
            }
        }.runTaskTimer(plugin, 0, 4);
    }

    public LivingEntity findTarget(Mob minion,double range){
        String side = creeps.get(minion);
        LivingEntity best = null; //MINION targeting
        double bestScore = Double.MAX_VALUE;

        for(LivingEntity candidate : creeps.keySet()){
            if(!Objects.equals(creeps.get(candidate), side)&&candidate.isValid()){
                double d = candidate.getLocation().distanceSquared(minion.getLocation());
                if(d>range*range){
                    continue;
                }
                double score = Math.sqrt(d)
                        + attackerCounter(candidate, minion) * CrowdScore
                        + (towers.contains(candidate) ? TowerTargetPenalty : 0)
                        - (banners.contains(candidate) ? BannerTargetBonus : 0);

                if(score < bestScore){
                    best=candidate;
                    bestScore=score;
                }
            }
        }

        for(Player p : Bukkit.getOnlinePlayers()){
            if(sideOf(p).equals(side) || !targetablePlayer(p) || p.getWorld() != minion.getWorld()) continue;
            double d = p.getLocation().distanceSquared(minion.getLocation());
            if(d > range * range) continue;
            double score = Math.sqrt(d)
                    + attackerCounter(p, minion) * CrowdScore
                    + PlayerTargetPenalty
                    - (isAggressor(p) ? AggressorBonus : 0);
            if(score < bestScore){
                best = p;
                bestScore = score;
            }
        }

        return best;
    }
    private LivingEntity findTowerTarget(String side, Location base){
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for(Map.Entry<LivingEntity, String> e : creeps.entrySet()){
            LivingEntity creep = e.getKey();
            if(Objects.equals(e.getValue(), side) || !creep.isValid() || towers.contains(creep)) continue;
            double d = creep.getLocation().distanceSquared(base);
            if(d > TowerRange * TowerRange) continue;
            double score = Math.sqrt(d) - (banners.contains(creep) ? BannerTargetBonus : 0);
            if(score < bestScore){
                best = creep;
                bestScore = score;
            }
        }
        if(best != null) return best;

        // no minions in range? beam any enemy player in range
        double bestDist = TowerRange * TowerRange;
        for(Player p : Bukkit.getOnlinePlayers()){
            if(sideOf(p).equals(side) || !targetablePlayer(p)) continue;
            if(p.getWorld() != base.getWorld()) continue;
            double d = p.getLocation().distanceSquared(base);
            if(d <= bestDist){
                best = p;
                bestDist = d;
            }
        }
        return best;


    }

    public class MeleeAttackGoal implements Goal<Mob>{
        private final GoalKey<Mob> key = GoalKey.of(Mob.class,new NamespacedKey(plugin,"melee_attack"));
        private final Mob minion;
        private LivingEntity target;
        MeleeAttackGoal(Mob minion){
            this.minion=minion;
        }

        private int nextScanTick = 0;
        private int nextAttackTick = 0;
        private int nextRepathTick = 0;

        @Override
        public boolean shouldActivate(){
            Player forced = getForced(minion);
            if(forced != null){
                target = forced;
                return true;
            }

            int now = Bukkit.getCurrentTick();
            if(now < nextScanTick) return false;
            nextScanTick = now + 5;
            target = findTarget(minion,MeleeAggroRange);
            return target != null;
        }

        @Override
        public boolean shouldStayActive(){
            return target!=null && target.isValid()
                    && target.getLocation().distanceSquared(minion.getLocation())<=MeleeMaxLeash*MeleeMaxLeash
                    && !(target instanceof Player p && forcedTargets.get(minion) == p && !isAggressor(p));
        }

        @Override
        public void start(){
            targets.put(minion,target);
        }

        @Override
        public void stop(){
            minion.getPathfinder().stopPathfinding();
            targets.remove(minion);
            target=null;
        }

        @Override
        public void tick(){
            Player forced = getForced(minion);
            if(forced != null && forced != target){
                target = forced;
                targets.put(minion, forced);
            }

            minion.lookAt(target);

            if(minion.getBoundingBox().expand(MeleeAttackRange).overlaps(target.getBoundingBox())){
                minion.getPathfinder().stopPathfinding();
                if(Bukkit.getCurrentTick() >= nextAttackTick){
                    target.damage(MeleeDamage, minion);
                    minion.playEffect(EntityEffect.ENTITY_ATTACK);
                    nextAttackTick = Bukkit.getCurrentTick() + MeleeCooldown;
                }
            }
            else if(!minion.getPathfinder().hasPath() || Bukkit.getCurrentTick() >= nextRepathTick){
                minion.getPathfinder().moveTo(target.getLocation(), 1.1);
                nextRepathTick = Bukkit.getCurrentTick() + 5;
            }
        }

        @Override
        public GoalKey<Mob> getKey() {
            return key;
        }

        @Override
        public EnumSet<GoalType> getTypes() {
            return EnumSet.of(GoalType.MOVE,GoalType.LOOK);
        }
    }
    public class RangedAttackGoal implements Goal<Mob>{
        private final GoalKey<Mob> key = GoalKey.of(Mob.class,new NamespacedKey(plugin,"ranged_attack"));
        private final Mob minion;
        private LivingEntity target;

        RangedAttackGoal(Mob minion){
            this.minion=minion;
        }

        private int nextScanTick = 0;
        private int nextAttackTick = 0;
        private int nextRepathTick = 0;

        @Override
        public boolean shouldActivate(){
            Player forced = getForced(minion);
            if(forced != null){
                target = forced;
                return true;
            }

            int now = Bukkit.getCurrentTick();
            if(now < nextScanTick) return false;
            nextScanTick = now + 5;
            target = findTarget(minion,RangedAggroRange);
            return target != null;
        }

        @Override
        public boolean shouldStayActive(){
            return target!=null && target.isValid()
                    && target.getLocation().distanceSquared(minion.getLocation())<=RangedMaxLeash*RangedMaxLeash
                    && !(target instanceof Player p && forcedTargets.get(minion) == p && !isAggressor(p));
        }

        @Override
        public void start(){
            targets.put(minion,target);
        }

        @Override
        public void stop(){
            minion.getPathfinder().stopPathfinding();
            targets.remove(minion);
            target=null;
        }

        @Override
        public void tick(){
            Player forced = getForced(minion);
            if(forced != null && forced != target){
                target = forced;
                targets.put(minion, forced);
            }

            minion.lookAt(target);
            int now = Bukkit.getCurrentTick();

            double distSq = target.getLocation().distanceSquared(minion.getLocation());
            double ranges = 0.0;
            if(banners.contains(minion)){
                ranges=BannerAttackRange*BannerAttackRange;
            }else{
                ranges=RangedAttackRange*RangedAttackRange;
            }
            boolean inRange = distSq <= ranges;
            boolean canSee = towers.contains(target) || minion.hasLineOfSight(target);

            if(inRange && canSee){
                minion.getPathfinder().stopPathfinding();
                if(now >= nextAttackTick){
                    shootMissile(minion,target);
                    nextAttackTick = now + RangedCooldown;
                }
            }
            else if(!minion.getPathfinder().hasPath() || now >= nextRepathTick){
                minion.getPathfinder().moveTo(target.getLocation(), 1.1);
                nextRepathTick = now + 5;
            }
        }

        @Override
        public GoalKey<Mob> getKey() {
            return key;
        }

        @Override
        public EnumSet<GoalType> getTypes() {
            return EnumSet.of(GoalType.MOVE,GoalType.LOOK);
        }
    }
    private class MinionWalkLane implements Goal<Mob>{
        private final GoalKey<Mob> key = GoalKey.of(Mob.class,new NamespacedKey(plugin,"minion_walk_lane"));
        private final Mob minion;
        private final Location goal;

        MinionWalkLane(Mob minion, Location goal){
            this.minion=minion;
            this.goal=goal;
        }

        @Override
        public boolean shouldActivate(){
            return true;
        }

        @Override
        public void tick(){
            if(!minion.getPathfinder().hasPath() || minion.getTicksLived() % 40 == 0){
                minion.getPathfinder().moveTo(goal, 1.1);
            }
        }

        @Override
        public @NonNull GoalKey<Mob> getKey() {
            return key;
        }

        @Override
        public @NonNull EnumSet<GoalType> getTypes() {
            return EnumSet.of(GoalType.MOVE);
        }
    }

    private void spawnMeleeMinion(String side, Location start, Location goal){
        IronGolem golem = start.getWorld().spawn(start,IronGolem.class, h -> h.setPersistent(false));
        Objects.requireNonNull(golem.getAttribute(Attribute.SCALE)).setBaseValue(0.3);
        Objects.requireNonNull(golem.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(24);
        golem.setHealth(24);
        golem.setCollidable(true);

        types.put(golem,MinionTypes.MELEE);

        minionManager(golem,side,goal,new MeleeAttackGoal(golem));
    }
    private void spawnRangedMinion(String side, Location start, Location goal){
        IronGolem golem = start.getWorld().spawn(start,IronGolem.class, h -> h.setPersistent(false));
        Objects.requireNonNull(golem.getAttribute(Attribute.SCALE)).setBaseValue(0.3);
        Objects.requireNonNull(golem.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(14);
        golem.setHealth(14);
        golem.setCollidable(true);

        types.put(golem,MinionTypes.RANGED);

        minionManager(golem,side,goal,new RangedAttackGoal(golem));
    }
    private void spawnBannerMinion(String side, Location start, Location goal){
        IronGolem golem = start.getWorld().spawn(start,IronGolem.class, h -> h.setPersistent(false));

        banners.add(golem);

        Objects.requireNonNull(golem.getAttribute(Attribute.SCALE)).setBaseValue(0.4);
        Objects.requireNonNull(golem.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(30);
        golem.setHealth(30);
        golem.setCollidable(true);

        types.put(golem,MinionTypes.BANNER);

        minionManager(golem,side,goal,new RangedAttackGoal(golem));
        doBannerHeal(golem,side);
    }
    private void spawnWave(String side, Location start, Location goal){
        Location offset = new Location(start.getWorld(), 0, 0, 1 * LaneSpacing);
        Location offset2 = new Location(start.getWorld(), 0, 0, -1 * LaneSpacing);

        spawnMeleeMinion(side, start.clone().add(offset), goal.clone().add(offset));
        spawnMeleeMinion(side, start.clone().add(offset2), goal.clone().add(offset2));


        new BukkitRunnable() {
            @Override
            public void run() {
                spawnBannerMinion(side, start,goal);
            }
        }.runTaskLater(plugin,10);

        new BukkitRunnable() {
            @Override
            public void run() {
                spawnRangedMinion(side, start.clone().add(offset), goal.clone().add(offset));
                spawnRangedMinion(side, start.clone().add(offset2), goal.clone().add(offset2));
            }
        }.runTaskLater(plugin,20);

    }
    private void spawnTower(String side, Location base, Location beam){
        IronGolem core = base.getWorld().spawn(base,IronGolem.class, g -> {
            g.setPersistent(false);
            g.setAI(false);
            g.setInvisible(true);
            g.setSilent(true);
            g.setCollidable(false);
            g.setRemoveWhenFarAway(false);
        });
        core.setRotation(90,0);
        TextDisplay label = base.getWorld().spawn(base.clone().add(0, 9.5, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(healthBar(side, TowerHealth, TowerHealth));
            d.setTransformation(new Transformation(
                    new Vector3f(),
                    new AxisAngle4f(),
                    new Vector3f(2f, 2f, 2f),
                    new AxisAngle4f()));
        });
        towerDisplays.add(label);
        Objects.requireNonNull(core.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(TowerHealth);
        Objects.requireNonNull(core.getAttribute(Attribute.KNOCKBACK_RESISTANCE)).setBaseValue(1.0);
        Objects.requireNonNull(core.getAttribute(Attribute.SCALE)).setBaseValue(2);
        core.setHealth(TowerHealth);

        creeps.put(core,side);
        towers.add(core);
        types.put(core,MinionTypes.TOWER);

        Color beamColor = side.equals("BLUE") ? Color.fromRGB(80, 140, 255) : Color.RED;

        Location ringCenter = base.clone().add(0, 0.1, 0);

        new BukkitRunnable() {
            LivingEntity target;
            int lockStart = 0;
            int nextDamage = 0;
            int nextNameUpdate = 0;
            int nextRing = 0;

            @Override
            public void run() {
                if(!core.isValid()){
                    label.remove();
                    towerDisplays.remove(label);
                    creeps.remove(core);
                    towers.remove(core);
                    types.remove(core);
                    core.remove();
                    Bukkit.getWorld("world").spawnParticle(Particle.EXPLOSION_EMITTER,core.getLocation(),2,0,0,0,0);
                    Bukkit.broadcast(Component.text(side + " tower destroyed"));
                    cancel();
                    return;
                }

                int now = Bukkit.getCurrentTick();

                if(now >= nextNameUpdate){
                    label.text(healthBar(side, core.getHealth(), TowerHealth));
                    nextNameUpdate = now + 10;
                }

                if(now >= nextRing){
                    drawCircle(ringCenter, TowerRange, beamColor, 0.8f);
                    nextRing = now + 5;
                }

                boolean lost = target == null || !target.isValid()
                        || target.getLocation().distanceSquared(base) > TowerRange * TowerRange
                        || (target instanceof Player p && !targetablePlayer(p));

                if(lost){
                    LivingEntity next = findTowerTarget(side, base);
                    if(next != target){
                        target = next;
                        lockStart = now;
                        nextDamage = now + BeamDamageInterval;
                    }
                }
                if(target == null) return;

                double ramp = Math.min(1.0, (now - lockStart) / (double) BeamRampTicks);

                drawBeam(beam, target, BeamMinRadius + (BeamMaxRadius - BeamMinRadius) * ramp, beamColor);

                if(now >= nextDamage){
                    double dmg = BeamMinDamage + (BeamMaxDamage - BeamMinDamage) * ramp;
                    target.damage(dmg, core);
                    nextDamage = now + BeamDamageInterval;
                }
            }
        }.runTaskTimer(plugin, 0, 1);
    }


    @EventHandler
    public void sneakEvent(PlayerToggleSneakEvent e){
        Player p = e.getPlayer();
        if(!p.isSneaking()){ //TESTING

            //spawnWave("BLUE",blue,red);
            //spawnWave("RED",red,blue);

        }
    }

    @EventHandler
    public void deathTrigger(EntityDeathEvent e){
        LivingEntity dead =  e.getEntity();
        if(creeps.containsKey(dead)){
            e.getDrops().clear();
            e.setDroppedExp(0);

            MinionTypes type = types.get(dead);
            if(dead.getLastDamageCause() instanceof EntityDamageByEntityEvent evn && evn.getDamager() instanceof Player){
                Player p = (Player) evn.getDamager();
                p.sendMessage(Component.text("You killed "+type.toString()));
                //give gold
                if(type.equals(MinionTypes.TOWER)){
                    giveGold(dead.getLocation(),p,50);
                } else if (type.equals(MinionTypes.RANGED)) {
                    giveGold(dead.getLocation(),p,2);
                }else if (type.equals(MinionTypes.MELEE)) {
                    giveGold(dead.getLocation(),p,3);
                }else if (type.equals(MinionTypes.BANNER)) {
                    giveGold(dead.getLocation(),p,5);
                }
            }

        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent e){
        Player attacker = null;
        if(e.getDamager() instanceof Player direct){
            attacker = direct;
        } else if(e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter){
            attacker = shooter;
        }
        if(attacker == null) return;

        String victimSide;
        if(e.getEntity() instanceof Player vp){
            victimSide = sideOf(vp);
        } else {
            victimSide = creeps.get(e.getEntity());
        }
        if(victimSide == null) return;

        if(victimSide.equals(sideOf(attacker))){
            e.setCancelled(true);
            return;
        }
        if(e.getEntity() instanceof Player victim){
            markAggressor(attacker, victim);
        }
    }

    @EventHandler
    public void TheSlimening(EntitySpawnEvent e){
        if(e.getEntity() instanceof Slime){
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onSuffocate(EntityDamageEvent e){
        if(e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION && creeps.containsKey(e.getEntity())){
            e.setCancelled(true);

        }
    }

    @EventHandler
    public void MossStop(BlockSpreadEvent e){
        if(e.getSource().getType() == Material.VINE){
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void Knockback(EntityKnockbackByEntityEvent e){
        e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (creeps.containsKey(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    @EventHandler
    public void antiSnowAct(EntityBlockFormEvent e){
        if(e.getEntity() instanceof Snowman golem && creeps.containsKey(golem)){
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e){
        Objects.requireNonNull(e.getPlayer().getAttribute(Attribute.KNOCKBACK_RESISTANCE)).setBaseValue(1.0);
    }

}
