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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.NonNull;

import java.util.*;

import static org.bukkit.Bukkit.createBlockData;

public class Events implements Listener {

    private Match match;
    public void setMatch(Match match) {
        this.match = match;
    }

    private BukkitTask waveTask;
    private static final long WaveInterval = 30L *20;

    HashMap<LivingEntity, String> creeps = new HashMap<>();
    HashMap<LivingEntity, LivingEntity> targets = new HashMap<>();

    Set<LivingEntity> towers = new HashSet<>();
    Set<LivingEntity> banners = new HashSet<>();
    Set<LivingEntity> guardians = new HashSet<>();
    Map<UUID, Integer> aggressorUntil = new HashMap<>();
    Map<Mob, Player> forcedTargets = new HashMap<>();      // minions told to chase an aggressor
    List<TextDisplay> towerDisplays = new ArrayList<>();

    enum MinionTypes {MELEE,RANGED,BANNER,TOWER,GUARDIAN}
    Map<Mob, MinionTypes> types = new HashMap<>();

    Location blue = new Location(Bukkit.getWorld("world"),-77.5,-60,-12.5);
    Location red = new Location(Bukkit.getWorld("world"),78.5,-60,-12.5);

    Location blueTowerBeam = new Location(Bukkit.getWorld("world"),-22.5,-56,-10.5);
    Location redTowerBeam = new Location(Bukkit.getWorld("world"),25,-53.5,-12.5);

    Location blueTowerBase = new Location(Bukkit.getWorld("world"),-25,-60,-12.5);
    Location redTowerBase = new Location(Bukkit.getWorld("world"),26,-60,-12.5);

    Location blueGuardianBase = new Location(Bukkit.getWorld("world"),-68.5,-60,-12.5);
    Location redGuardianBase = new Location(Bukkit.getWorld("world"),69.5,-60,-12.5);

    Map<Mob, Location> anchors = new HashMap<>();
    Map<Mob, Location> returning = new HashMap<>();
    Map<Mob, Integer> playerIgnoreUntil = new HashMap<>();

    private int waveCount = 0;

    private static final double PlayerTargetPenalty = 150.0;
    private static final double AggressorBonus = 300.0;
    private static final int AggressorTicks = 5 * 20;
    private static final double AggroCallRange = 12.0;
    private static final double TowerRange = 10.0;
    private static final double TowerHealth = 200.0;
    private static final double TowerTargetPenalty = 100.0;
    private static final double GuardianTargetPenalty = 100.0;

    private static final double BannerTargetBonus = 50.0;

    private static final double BeamMinDamage = 1.0;
    private static final double BeamMaxDamage = 6.0;
    private static final int BeamRampTicks = 5*20;       // ticks til full powa
    private static final int BeamDamageInterval = 15;   // ticks between dmg
    private static final double BeamMinRadius = 0.02;
    private static final double BeamMaxRadius = 0.20;


    private static final double CrowdScore = 2;

    private static final double MeleeAggroRange = 7.0;
    private static final double MeleeAttackRange = 2.0;
    private static final double MeleeMaxLeash = 9.0;
    private static final int MeleeCooldown = 25; //ticks

    private static final double RangedAggroRange = 7.0;
    private static final double RangedAttackRange = 7.0;
    private static final double RangedMaxLeash = 9.0;
    private static final int RangedCooldown = 30; //ticks

    private static final double BannerHealRange = 14.0;
    private static final int BannerHealCooldown = 8*20; //ticks
    private static final double BannerAttackRange = 5.0;

    private static final double MinionSpeed = 0.23;

    private static final double LaneSpacing = 1.0;

    private static final double BannerMissileSpeed = 1.5;
    private static final double MissileSpeed = 0.4;
    private static final double MissileDamage = 1.0;
    private static final double MeleeDamage = 2.0;

    private static final double GuardianHealth = 300.0;

    private static final double TetherRange = 11.0;
    private static final int PlayerIgnoreTicks = 5 * 20;
    private static final double ReturnArrive = 2.0;

    private final Lanes plugin;
    public Events(Lanes plugin){
        this.plugin=plugin;
    }

    private void holdLaneChunks(){
        World w = blue.getWorld();
        int minX = Math.min(blue.getBlockX(), red.getBlockX()) >> 4;
        int maxX = Math.max(blue.getBlockX(), red.getBlockX()) >> 4;
        int z = blue.getBlockZ() >> 4;
        for(int cx = minX; cx <= maxX; cx++){
            for(int cz = z - 1; cz <= z + 1; cz++){
                w.addPluginChunkTicket(cx, cz, plugin);
            }
        }
    }

    public void startMatch(){
        SpawnTowers();
        SpawnGuardians();
        holdLaneChunks();

        waveTask = new BukkitRunnable() {
            @Override
            public void run() {

                spawnWave("BLUE", blue, red);
                spawnWave("RED", red, blue);
            }
        }.runTaskTimer(plugin, 20L * 5, WaveInterval);  //delay wave1 and waves every 20sec after
    }

    public void stopWaves(){
        if(waveTask != null){
            waveTask.cancel();
            waveTask = null;
        }
    }

    public void stopMatch(){
        stopWaves();
        cleanup();
        blue.getWorld().removePluginChunkTickets(plugin);
    }

    private String sideOf(Player p){
        return match.getSide(p);
    }


    private void aggroStart(Mob m){
        Location r = returning.get(m);
        anchors.putIfAbsent(m, r != null ? r : m.getLocation());   // if still returning, the lane point stays the anchor
    }

    private void aggroStop(Mob m){
        Location a = anchors.remove(m);
        if(a != null && a.getWorld() == m.getWorld()
                && a.distanceSquared(m.getLocation()) > TetherRange * TetherRange){
            returning.put(m, a);
        }
    }

    private boolean tetherBroken(Mob m){
        Location a = anchors.get(m);
        if(a == null || a.getWorld() != m.getWorld()) return false;
        if(a.distanceSquared(m.getLocation()) <= TetherRange * TetherRange) return false;

        forcedTargets.remove(m);                                   // stop the "chase the aggressor" order
        playerIgnoreUntil.put(m, Bukkit.getCurrentTick() + PlayerIgnoreTicks);
        return true;
    }

    private boolean targetablePlayer(Player p){
        return match.isPlaying(p) && p.isOnline() && p.isValid()
                && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    private boolean isAggressor(Player p){
        return aggressorUntil.getOrDefault(p.getUniqueId(), 0) > Bukkit.getCurrentTick();
    }

    private Player getForced(Mob minion){
        Player p = forcedTargets.get(minion);
        if(p == null) return null;
        if(Bukkit.getCurrentTick() < playerIgnoreUntil.getOrDefault(minion, 0)){
            forcedTargets.remove(minion);
            return null;
        }
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
            if(!(en.getKey() instanceof Mob mob) || towers.contains(mob) || guardians.contains(mob) || !mob.isValid()) continue;
            if(mob.getWorld() != victim.getWorld()) continue;
            if(mob.getLocation().distanceSquared(victim.getLocation()) <= AggroCallRange * AggroCallRange){
                forcedTargets.put(mob, attacker);
            }
        }
    }

    public void SpawnTowers(){
        spawnTower("RED",redTowerBase,redTowerBeam);

        spawnTower("BLUE",blueTowerBase,blueTowerBeam);
    }

    public void SpawnGuardians(){
        spawnRedGuardian();
        spawnBlueGuardian();
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
        guardians.clear();
        aggressorUntil.clear();
        forcedTargets.clear();
        anchors.clear();
        returning.clear();
        playerIgnoreUntil.clear();

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

    private void giveMoney(Location loc, Player p,int amount){
        ItemDisplay ingot = loc.getWorld().spawn(loc.clone().add(0, 1, 0), ItemDisplay.class,d->{
            d.setPersistent(false);
            d.setItemStack(new ItemStack(Material.EMERALD));
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
        match.addMoney(p,amount);
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

        boolean isBlue = "BLUE".equals(creeps.get(shooter));
        Particle proj = isBlue ? Particle.CRIT : Particle.ENCHANTED_HIT;

        new BukkitRunnable() {
            @Override
            public void run() {
                // target died
                if(!target.isValid()){
                    cancel();
                    return;
                }


                Vector toTarget = target.getBoundingBox().getCenter().subtract(pos.toVector());
                double dist = toTarget.length();

                //hit target
                if(dist <= MissileSpeed){
                    Location hit = target.getBoundingBox().getCenter().toLocation(world);
                    target.damage(MissileDamage, shooter);
                    world.spawnParticle(proj, hit, 5,0,0,0,1);
                    //world.spawnParticle(Particle.CRIT, hit, 15, 0.3, 0.3, 0.3, 0.2);
                    cancel();
                    return;
                }

                pos.add(toTarget.normalize().multiply(MissileSpeed));
                world.spawnParticle(proj, pos, 4, 0.01, 0.01, 0.01, 0);

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
                    if(candidate!=banner && candidate.isValid() && !towers.contains(candidate) && !guardians.contains(candidate)){
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
        mob.setRemoveWhenFarAway(false);
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
                if(mob.isDead() || mob.getLocation().distanceSquared(goal) < 3){
                    for(Entity passenger : mob.getPassengers()){
                        passenger.remove();
                    }
                    mob.remove();
                    creeps.remove(mob);
                    targets.remove(mob);
                    banners.remove(mob);
                    types.remove(mob);
                    forcedTargets.remove(mob);

                    anchors.remove(mob);
                    returning.remove(mob);
                    playerIgnoreUntil.remove(mob);
                    this.cancel();

                    this.cancel();
                }

            }
        }.runTaskTimer(plugin,0,10);
    }

    private Component healthBar(String side, String name, double hp, double max){
        int total = 20;
        int filled = (int) Math.ceil(Math.max(0, hp) / max * total);
        NamedTextColor color = side.equals("BLUE") ? NamedTextColor.AQUA : NamedTextColor.RED;
        NamedTextColor bar_color = side.equals("BLUE") ? NamedTextColor.DARK_BLUE : NamedTextColor.DARK_RED;
        return Component.text(side + " " + name, color)
                .append(Component.text("|".repeat(filled), bar_color))
                .append(Component.text("|".repeat(total - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + (int) Math.ceil(Math.max(0, hp)) + "/" + (int) max, NamedTextColor.WHITE));
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
                if(mob.isDead() || bar.isDead()){
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
                        + (guardians.contains(candidate) ? GuardianTargetPenalty : attackerCounter(candidate, minion) * CrowdScore)
                        + (towers.contains(candidate) ? TowerTargetPenalty : 0)
                        - (banners.contains(candidate) ? BannerTargetBonus : 0);

                if(score < bestScore){
                    best=candidate;
                    bestScore=score;
                }
            }
        }
        boolean ignorePlayers = Bukkit.getCurrentTick() < playerIgnoreUntil.getOrDefault(minion, 0);

        for(Player p : Bukkit.getOnlinePlayers()){
            if(ignorePlayers || sideOf(p).equals(side) || !targetablePlayer(p) || p.getWorld() != minion.getWorld()) continue;
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
                    && !(target instanceof Player p && forcedTargets.get(minion) == p && !isAggressor(p))
                    && !tetherBroken(minion);
        }

        @Override
        public void start(){
            aggroStart(minion);
            targets.put(minion,target);
        }

        @Override
        public void stop(){
            aggroStop(minion);
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
                    if(minion instanceof IronGolem){
                        minion.playEffect(EntityEffect.ENTITY_ATTACK);
                    }else{
                        minion.swingMainHand();
                    }
                    nextAttackTick = Bukkit.getCurrentTick() + MeleeCooldown;
                }
            }
            else if(!minion.getPathfinder().hasPath() || Bukkit.getCurrentTick() >= nextRepathTick){
                minion.getPathfinder().moveTo(target.getLocation(), 1.3);
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
                    && !(target instanceof Player p && forcedTargets.get(minion) == p && !isAggressor(p))
                    && !tetherBroken(minion);
        }

        @Override
        public void start(){
            aggroStart(minion);
            targets.put(minion,target);
        }

        @Override
        public void stop(){
            aggroStop(minion);
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
                minion.getPathfinder().moveTo(target.getLocation(), 1.3);
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
            Location back = returning.get(minion);
            if(back != null){
                if(back.getWorld() == minion.getWorld()
                        && back.distanceSquared(minion.getLocation()) <= ReturnArrive * ReturnArrive){
                    returning.remove(minion);//become normal again
                } else {
                    if(!minion.getPathfinder().hasPath() || minion.getTicksLived() % 20 == 0){
                        minion.getPathfinder().moveTo(back, 1.6);//return to lane fast
                    }
                    return;
                }
            }

            if(!minion.getPathfinder().hasPath() || minion.getTicksLived() % 40 == 0){
                minion.getPathfinder().moveTo(goal, 1.3);
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

    private void spawnRedMeleeMinion(String side, Location start, Location goal){
        IronGolem golem = start.getWorld().spawn(start,IronGolem.class, h -> h.setPersistent(false));
        Objects.requireNonNull(golem.getAttribute(Attribute.SCALE)).setBaseValue(0.3);
        Objects.requireNonNull(golem.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(14);
        Objects.requireNonNull(golem.getAttribute(Attribute.MOVEMENT_SPEED)).setBaseValue(MinionSpeed);
        golem.setHealth(14);
        golem.setCollidable(true);

        types.put(golem,MinionTypes.MELEE);

        minionManager(golem,side,goal,new MeleeAttackGoal(golem));
    }
    private void spawnRedRangedMinion(String side, Location start, Location goal){
        Skeleton ghoul = start.getWorld().spawn(start,Skeleton.class, h -> h.setPersistent(false));
        ghoul.setShouldBurnInDay(false);
        Objects.requireNonNull(ghoul.getAttribute(Attribute.SCALE)).setBaseValue(0.3);
        Objects.requireNonNull(ghoul.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(8);
        Objects.requireNonNull(ghoul.getAttribute(Attribute.MOVEMENT_SPEED)).setBaseValue(MinionSpeed);

        ghoul.setHealth(8);
        ghoul.setCollidable(true);


        types.put(ghoul,MinionTypes.RANGED);

        minionManager(ghoul,side,goal,new RangedAttackGoal(ghoul));
    }
    private void spawnRedBannerMinion(String side, Location start, Location goal){
        IronGolem golem = start.getWorld().spawn(start,IronGolem.class, h -> h.setPersistent(false));

        banners.add(golem);

        Objects.requireNonNull(golem.getAttribute(Attribute.SCALE)).setBaseValue(0.45);
        Objects.requireNonNull(golem.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(20);
        Objects.requireNonNull(golem.getAttribute(Attribute.MOVEMENT_SPEED)).setBaseValue(MinionSpeed);

        golem.setHealth(20);
        golem.setCollidable(true);
        golem.setSilent(true);


        types.put(golem,MinionTypes.BANNER);

        minionManager(golem,side,goal,new MeleeAttackGoal(golem));
        //doBannerHeal(golem,side);
    }

    private void spawnBlueMeleeMinion(String side, Location start, Location goal){
        IronGolem golem = start.getWorld().spawn(start,IronGolem.class, h -> h.setPersistent(false));
        Objects.requireNonNull(golem.getAttribute(Attribute.SCALE)).setBaseValue(0.3);
        Objects.requireNonNull(golem.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(14);
        Objects.requireNonNull(golem.getAttribute(Attribute.MOVEMENT_SPEED)).setBaseValue(MinionSpeed);
        golem.setHealth(14);
        golem.setCollidable(true);

        types.put(golem,MinionTypes.MELEE);

        minionManager(golem,side,goal,new MeleeAttackGoal(golem));
    }
    private void spawnBlueRangedMinion(String side, Location start, Location goal){
        Skeleton ghoul = start.getWorld().spawn(start,Skeleton.class, h -> h.setPersistent(false));
        ghoul.setShouldBurnInDay(false);
        Objects.requireNonNull(ghoul.getAttribute(Attribute.SCALE)).setBaseValue(0.3);
        Objects.requireNonNull(ghoul.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(8);
        Objects.requireNonNull(ghoul.getAttribute(Attribute.MOVEMENT_SPEED)).setBaseValue(MinionSpeed);

        ghoul.setHealth(8);
        ghoul.setCollidable(true);


        types.put(ghoul,MinionTypes.RANGED);

        minionManager(ghoul,side,goal,new RangedAttackGoal(ghoul));
    }
    private void spawnBlueBannerMinion(String side, Location start, Location goal){
        IronGolem golem = start.getWorld().spawn(start,IronGolem.class, h -> h.setPersistent(false));

        banners.add(golem);

        Objects.requireNonNull(golem.getAttribute(Attribute.SCALE)).setBaseValue(0.45);
        Objects.requireNonNull(golem.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(20);
        Objects.requireNonNull(golem.getAttribute(Attribute.MOVEMENT_SPEED)).setBaseValue(MinionSpeed);

        golem.setHealth(20);
        golem.setCollidable(true);
        golem.setSilent(true);


        types.put(golem,MinionTypes.BANNER);

        minionManager(golem,side,goal,new MeleeAttackGoal(golem));
        //doBannerHeal(golem,side);
    }

    private void spawnWave(String side, Location start, Location goal){
        Location offset = new Location(start.getWorld(), 0, 0, 1 * LaneSpacing);
        Location offset2 = new Location(start.getWorld(), 0, 0, -1 * LaneSpacing);
        if(side.equals("BLUE")){

            spawnBlueBannerMinion(side, start,goal);

            new BukkitRunnable() {
                @Override
                public void run() {
                    spawnBlueMeleeMinion(side, start.clone().add(offset), goal.clone().add(offset));
                    spawnBlueMeleeMinion(side, start.clone().add(offset2), goal.clone().add(offset2));
                }
            }.runTaskLater(plugin,10);

            new BukkitRunnable() {
                @Override
                public void run() {
                    spawnBlueRangedMinion(side, start.clone().add(offset), goal.clone().add(offset));
                    spawnBlueRangedMinion(side, start.clone().add(offset2), goal.clone().add(offset2));
                }
            }.runTaskLater(plugin,20);
        }else{
            spawnRedBannerMinion(side, start,goal);

            new BukkitRunnable() {
                @Override
                public void run() {

                    spawnRedMeleeMinion(side, start.clone().add(offset), goal.clone().add(offset));
                    spawnRedMeleeMinion(side, start.clone().add(offset2), goal.clone().add(offset2));
                }
            }.runTaskLater(plugin,10);

            new BukkitRunnable() {
                @Override
                public void run() {
                    spawnRedRangedMinion(side, start.clone().add(offset), goal.clone().add(offset));
                    spawnRedRangedMinion(side, start.clone().add(offset2), goal.clone().add(offset2));
                }
            }.runTaskLater(plugin,20);
        }


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
            d.text(healthBar(side, "Tower", TowerHealth, TowerHealth));
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
                    boolean killed = core.getHealth() <= 0;
                    label.remove();
                    towerDisplays.remove(label);
                    creeps.remove(core);
                    towers.remove(core);
                    types.remove(core);
                    core.remove();
                    if(killed){
                        Bukkit.getWorld("world").spawnParticle(Particle.EXPLOSION_EMITTER,core.getLocation(),2,0,0,0,0);
                        Bukkit.broadcast(Component.text(side + " tower destroyed"));
                    }
                    cancel();
                    return;
                }

                int now = Bukkit.getCurrentTick();

                if(now >= nextNameUpdate){
                    label.text(healthBar(side, "Tower", core.getHealth(), TowerHealth));
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
    private void spawnGuardian(String side, Location base, float yaw){
        IronGolem core = base.getWorld().spawn(base, IronGolem.class, g -> {
            g.setPersistent(false);
            g.setAI(false);
            g.setSilent(true);
            g.setRemoveWhenFarAway(false);
        });
        core.setRotation(yaw, 0);
        Objects.requireNonNull(core.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(GuardianHealth);
        Objects.requireNonNull(core.getAttribute(Attribute.KNOCKBACK_RESISTANCE)).setBaseValue(1.0);
        Objects.requireNonNull(core.getAttribute(Attribute.SCALE)).setBaseValue(3);
        core.setHealth(GuardianHealth);

        TextDisplay label = base.getWorld().spawn(base.clone().add(0, 12, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(healthBar(side, "Guardian", GuardianHealth, GuardianHealth));
            d.setTransformation(new Transformation(
                    new Vector3f(),
                    new AxisAngle4f(),
                    new Vector3f(2f, 2f, 2f),
                    new AxisAngle4f()));
        });
        towerDisplays.add(label);
        creeps.put(core, side);
        guardians.add(core);
        types.put(core, MinionTypes.GUARDIAN);

        new BukkitRunnable() {
            @Override
            public void run() {
                if(!core.isValid()){
                    boolean killed = core.getHealth() <= 0;
                    label.remove();
                    towerDisplays.remove(label);
                    creeps.remove(core);
                    guardians.remove(core);
                    types.remove(core);
                    core.remove();
                    if(killed){
                        core.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, core.getLocation(), 2, 0, 0, 0, 0);
                        String winner = side.equals("BLUE") ? "RED" : "BLUE";
                        Bukkit.broadcast(Component.text(side + " guardian destroyed - " + winner + " wins!"));
                        match.endMatch(winner);
                    }
                    cancel();
                    return;
                }
                label.text(healthBar(side, "Guardian", core.getHealth(), GuardianHealth));
            }
        }.runTaskTimer(plugin, 0, 10);
    }
    private void spawnRedGuardian(){
        spawnGuardian("RED",redGuardianBase,90);
    }
    private void spawnBlueGuardian(){
        spawnGuardian("BLUE",blueGuardianBase,-90);
    }

    @EventHandler
    public void sneakEvent(PlayerToggleSneakEvent e){
        Player p = e.getPlayer();
        if(!p.isSneaking()){ //debug end game

            match.endMatch("BLUE");
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
                    giveMoney(dead.getLocation(),p,400);
                } else if (type.equals(MinionTypes.RANGED)) {
                    giveMoney(dead.getLocation(),p,10);
                }else if (type.equals(MinionTypes.MELEE)) {
                    giveMoney(dead.getLocation(),p,15);
                }else if (type.equals(MinionTypes.BANNER)) {
                    giveMoney(dead.getLocation(),p,25);
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
    public void antiSnowAct(EntityBlockFormEvent e){
        if(e.getEntity() instanceof Snowman golem && creeps.containsKey(golem)){
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void netherTravelBanned(PlayerPortalEvent e){
        e.setCancelled(true);
    }

    @EventHandler
    public void netherDetector(EntityPortalEnterEvent e){
        if(e.getEntity() instanceof Player){
            //e.getEntity().sendMessage("yoooo");
        }
    }

    @EventHandler
    public void netherCitizenBan(CreatureSpawnEvent e){
        if(e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL){
            e.setCancelled(true);
        }
        if(e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL){
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e){
        Objects.requireNonNull(e.getPlayer().getAttribute(Attribute.KNOCKBACK_RESISTANCE)).setBaseValue(1.0);
    }

    @EventHandler
    public void antiBurnAct(EntityCombustEvent e){
        if(creeps.containsKey(e.getEntity())){
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void CHICKENJOCKEY(CreatureSpawnEvent e){
        if(e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.JOCKEY){
            e.setCancelled(true);
        }
    }

}
