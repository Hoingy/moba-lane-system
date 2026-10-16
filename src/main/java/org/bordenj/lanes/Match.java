package org.bordenj.lanes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import javax.xml.stream.events.Namespace;
import java.time.Duration;
import java.util.*;

public class Match implements Listener{
    private static final int teamSize = 2;
    private static final boolean soloTest = true;
    private static final int countdownTime = 5;//sec
    private static final int startMoney = 0;
    private static final long returnTime = 5*20L;//ticks

    private final Location lobbySpawn = new Location(Bukkit.getWorld("world"), -94.5, -60, -59.5);
    private final Location blueSpawn  = new Location(Bukkit.getWorld("world"), -80.5, -60, -12.5, -90f, 0f);
    private final Location redSpawn   = new Location(Bukkit.getWorld("world"), 81.5, -60, -12.5, 90f, 0f);

    private record Champion(String name, Material icon, String description){

    }
    private static final List<Champion> champions = List.of(new Champion("Hero1",Material.COPPER_SWORD,"Placeholder1"),
            new Champion("Hero2",Material.DIAMOND_SWORD,"Placeholder2"),new Champion("Hero3",Material.GOLDEN_SWORD,"Placeholder3"),
            new Champion("Hero4",Material.IRON_AXE,"Placeholder4"));

    private final static String[] SIDES = {"RED","BLUE"};
    private static final int SLOT_BLUE = 0, SLOT_RED = 1, SLOT_HERO = 4, SLOT_READY = 8;

    private enum Phase {LOBBY,COUNTDOWN,IN_GAME,ENDING}

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inv;
        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final Lanes plugin;
    private final Events events;
    private final NamespacedKey itemKey;

    public Match(Lanes plugin, Events events) {
        this.plugin = plugin;
        this.events = events;
        this.itemKey = new NamespacedKey(plugin, "lobby_item");
    }

    private Phase phase = Phase.LOBBY;
    private BukkitTask countdown;

    private final Map<UUID, String> teams = new LinkedHashMap<>();
    private final Map<UUID, String> champs = new HashMap<>();
    private final Set<UUID> ready = new HashSet<>();
    private final Map<UUID, Integer> money = new HashMap<>();
    private final Set<UUID> dead = new HashSet<>();

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, List<String>> lastLines = new HashMap<>();

    public void init() {
        for (Player p : Bukkit.getOnlinePlayers()) toLobby(p);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBoards, 20L, 10L);
    }

    public String getSide(Player p){
        String team = teams.get(p.getUniqueId());
        return team == null ? "TEAMLESS":team;
    }

    public boolean isPlaying(Player p){
        return phase == Phase.IN_GAME && teams.containsKey(p.getUniqueId());
    }

    public void addMoney(Player p,int amt){
        money.merge(p.getUniqueId(),amt,Integer::sum);
        refreshBoards();
    }

    public void endMatch(String winner){
        if(phase!=Phase.IN_GAME){
            return;
        }
        phase = Phase.ENDING;
        events.stopWaves();

        NamedTextColor c = winner.equals("BLUE")?NamedTextColor.BLUE:NamedTextColor.RED;
        for(Player p: Bukkit.getOnlinePlayers()){
            p.showTitle(Title.title(Component.text(winner+" Wins!",c, TextDecoration.BOLD),
                    Component.text("Returning to Lobby")));
        }
        refreshBoards();
        Bukkit.getScheduler().runTaskLater(plugin,this::resetToLobby,returnTime);
    }





    private boolean inLobbyPhase(){
        return phase == Phase.LOBBY || phase == Phase.COUNTDOWN;
    }

    private int count(String team){
        int n = 0;
        for(String t:teams.values()){
            if(t.equals(team)) {
                n++;
            }
        }
        return n;
    }

    private UUID championOnTeam(String team,String champion,UUID except){
        for(Map.Entry<UUID,String> e:champs.entrySet()){
            if (e.getKey().equals(except) || !e.getValue().equals(champion)) continue;
            if (team.equals(teams.get(e.getKey()))){
                return e.getKey();
            }
        }
        return null;
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "NONE" : n;
    }

    private void joinTeam(Player p, String team) {
        UUID id = p.getUniqueId();
        String current = teams.get(id);

        if (team.equals(current)) {//leave current team
            teams.remove(id);
            ready.remove(id);
            p.sendMessage(Component.text("You've left " + team + ".",NamedTextColor.LIGHT_PURPLE));
            changed();
            return;
        }
        if (count(team) >= teamSize) {
            p.sendMessage(Component.text(team + " is full.", NamedTextColor.LIGHT_PURPLE));
            return;
        }

        teams.put(id, team);
        ready.remove(id);

        String hero = champs.get(id);
        if (hero != null && championOnTeam(team, hero, id) != null) {
            champs.remove(id);
            p.sendMessage(Component.text("Your champions is already taken. Pick another.", NamedTextColor.LIGHT_PURPLE));
        }
        p.sendMessage(Component.text("You've joined " + team + ".", NamedTextColor.GREEN));
        changed();
    }

    private void pickHero(Player p, Champion champ) {
        UUID id = p.getUniqueId();
        String team = teams.get(id);
        if (team == null) return;

        UUID owner = championOnTeam(team, champ.name(), id);
        if (owner != null) {
            p.sendMessage(Component.text(champ.name() + " is taken by " + nameOf(owner) + ".", NamedTextColor.LIGHT_PURPLE));
            return;
        }
        champs.put(id, champ.name());
        ready.remove(id);
        p.sendMessage(Component.text("You picked " + champ.name() + ".", NamedTextColor.GREEN));
        new BukkitRunnable(){
            @Override
            public void run() {
                p.closeInventory();
            }
        }.runTaskLater(plugin,1);
        changed();
    }

    private void toggleReady(Player p) {
        UUID id = p.getUniqueId();
        if (!ready.remove(id)) {
            if (!teams.containsKey(id) || !champs.containsKey(id)) {
                p.sendMessage(Component.text("Pick a team AND a hero first.", NamedTextColor.LIGHT_PURPLE));
                return;
            }
            ready.add(id);
        }
        changed();
    }

    private void changed() {
        if (inLobbyPhase()) for (Player p : Bukkit.getOnlinePlayers()) {
            updateItems(p);
        }
        refreshBoards();
        checkStart();
    }

    private boolean canStart(){
        List<UUID> participants = new ArrayList<>();
        for (UUID id : teams.keySet()){
            if (Bukkit.getPlayer(id) != null){
                participants.add(id);
            }
        }
        if (participants.isEmpty()){
            return false;
        }
        if (!ready.containsAll(participants)){
            return false;
        }
        if (!soloTest && (count("BLUE") != teamSize || count("RED") != teamSize)){
            return false;
        }
        return true;
    }

    private void checkStart() {
        if(phase==Phase.LOBBY && canStart()){
            startCountdown();
        }
    }

    private void startCountdown() {
        phase = Phase.COUNTDOWN;
        countdown = new BukkitRunnable() {
            int t = countdownTime;

            @Override
            public void run() {
                if (!canStart()) {
                    cancelCountdown("Not everyone is ready.");
                    return;
                }
                if (t <= 0) {
                    cancel();
                    countdown = null;
                    startMatch();
                    return;
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(Title.title(
                            Component.text(t, NamedTextColor.GOLD, TextDecoration.BOLD),
                            Component.text("Match starting", NamedTextColor.GRAY),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ZERO)));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                }
                t--;
            }
        }.runTaskTimer(plugin, 0, 20);
        refreshBoards();
    }

    private void cancelCountdown(String reason) {
        if (countdown != null) countdown.cancel();
        countdown = null;
        phase = Phase.LOBBY;
        Bukkit.broadcast(Component.text("Countdown cancelled: " + reason, NamedTextColor.DARK_PURPLE));
        refreshBoards();
    }

    private Location spawn(String team){
        return("BLUE".equals(team)? blueSpawn :redSpawn).clone();
    }

    private void startMatch() {
        phase = Phase.IN_GAME;
        dead.clear();

        for(Player p : Bukkit.getOnlinePlayers()){
            UUID id = p.getUniqueId();
            String team = teams.get(id);
            p.getInventory().clear();

            if(team == null){
                p.setGameMode(GameMode.SPECTATOR);
                p.teleport(blueSpawn);
                continue;
            }

            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue());
            p.setFoodLevel(20);
            p.teleport(spawn(team));
            money.put(id,startMoney);
            giveChampion(p,champs.get(id));

            p.showTitle(Title.title(Component.text("Start"),Component.text(""),5,20,5));
        }
        for(Player p : Bukkit.getOnlinePlayers()){
            buildBoard(p);
        }
        events.startMatch();
    }

    private void giveChampion(Player p, String champ){
        //give later placeholder for now
    }

    private void resetToLobby() {
        events.stopMatch();
        teams.clear();
        champs.clear();
        ready.clear();
        money.clear();
        dead.clear();
        phase = Phase.LOBBY;
        for (Player p : Bukkit.getOnlinePlayers()){
            toLobby(p);
        }
    }

    private void toLobby(Player p){
        p.teleport(lobbySpawn);
        p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue());
        p.setFoodLevel(20);
        updateItems(p);
        buildBoard(p);
    }




    private ItemStack makeItem(Material mat, String id, Component name, boolean glow, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            m.lore(Arrays.stream(lore)
                    .map(s -> Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
        if (glow) m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    private void updateItems(Player p){
        UUID id = p.getUniqueId();
        String mine = teams.get(id);
        PlayerInventory inv = p.getInventory();

        inv.setItem(SLOT_BLUE, makeItem(Material.BLUE_BANNER, "blue",
                Component.text("Blue Team (" + count("BLUE") + "/" + teamSize + ")", NamedTextColor.BLUE),
                "BLUE".equals(mine), "Click to join / leave"));
        inv.setItem(SLOT_RED, makeItem(Material.RED_BANNER, "red",
                Component.text("Red Team (" + count("RED") + "/" + teamSize + ")", NamedTextColor.RED),
                "RED".equals(mine), "Click to join / leave"));
        inv.setItem(SLOT_HERO, makeItem(Material.WOODEN_SWORD, "hero",
                Component.text("Select Champion", NamedTextColor.GOLD), false,
                "Current: " + champs.getOrDefault(id, "none")));

        boolean r = ready.contains(id);
        inv.setItem(SLOT_READY, makeItem(r ? Material.LIME_DYE : Material.GRAY_DYE, "ready",
                Component.text(r ? "READY (click to unready)" : "Click to Ready", r ? NamedTextColor.GREEN : NamedTextColor.RED),
                r, "Needs a team and a hero"));
    }

    private void openMenu(Player p){
        UUID id = p.getUniqueId();
        String team = teams.get(id);
        if (team == null) {
            p.sendMessage(Component.text("Join a team first.", NamedTextColor.RED));
            return;
        }
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 9, Component.text("Select Hero"));
        holder.inv = inv;

        for (int i = 0; i < champions.size(); i++) {
            Champion h = champions.get(i);
            UUID owner = championOnTeam(team, h.name(), id);
            boolean selected = h.name().equals(champs.get(id));
            if (owner != null) {
                inv.setItem(i, makeItem(Material.BARRIER, "none",
                        Component.text(h.name(), NamedTextColor.DARK_GRAY), false, "Taken by " + nameOf(owner)));
            } else {
                inv.setItem(i, makeItem(h.icon(), "none",
                        Component.text(h.name(), selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
                        selected, h.description(), selected ? "Selected" : "Click to select"));
            }
        }
        p.openInventory(inv);
    }





    private void buildBoard(Player viewer) {
        Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = b.registerNewObjective("lanes", Criteria.DUMMY,
                Component.text("LANES", NamedTextColor.GOLD, TextDecoration.BOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // In-game: hide enemy nametags on THIS viewer's board only
        if (phase == Phase.IN_GAME || phase == Phase.ENDING) {
            String mine = teams.get(viewer.getUniqueId());
            for (String side : SIDES) {
                Team t = b.registerNewTeam(side.toLowerCase());
                t.color(side.equals("BLUE") ? NamedTextColor.BLUE : NamedTextColor.RED);
                boolean enemy = mine != null && !side.equals(mine);
                t.setOption(Team.Option.NAME_TAG_VISIBILITY,
                        enemy ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS);
                for (Map.Entry<UUID, String> en : teams.entrySet()) {
                    if (en.getValue().equals(side)) t.addEntry(nameOf(en.getKey()));
                }
            }
        }

        boards.put(viewer.getUniqueId(), b);
        lastLines.remove(viewer.getUniqueId());
        viewer.setScoreboard(b);
        renderBoard(viewer);
    }

    private void refreshBoards() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(p.getUniqueId())) renderBoard(p);
            else buildBoard(p);
        }
    }

    private List<String> buildLines(Player viewer) {
        boolean inGame = phase == Phase.IN_GAME || phase == Phase.ENDING;
        String mine = teams.get(viewer.getUniqueId());
        List<String> lines = new ArrayList<>();
        int unique = 0;

        if (!inGame) {
            lines.add("§e§lLOBBY");
            lines.add("§r".repeat(++unique));
        }

        for (String side : SIDES) {
            String c = side.equals("BLUE") ? "§9" : "§c";
            lines.add(c + "§l" + side + (inGame ? "" : " (" + count(side) + "/" + teamSize + ")"));

            int shown = 0;
            for (Map.Entry<UUID, String> en : teams.entrySet()) {
                if (!en.getValue().equals(side)) continue;
                UUID id = en.getKey();
                String n = nameOf(id);
                String line;
                if (inGame) {
                    line = dead.contains(id) ? "§7[DEAD] " + n : c + n;
                    if (side.equals(mine)) line += " §6" + money.getOrDefault(id, 0) + "g";   // own team only
                } else {
                    line = c + n + " §7" + champs.getOrDefault(id, "...") + (ready.contains(id) ? " §a✔" : "");
                }
                lines.add(" " + line);
                shown++;
            }
            if (!inGame) {
                for (; shown < teamSize; shown++) lines.add(" §8- empty -" + "§r".repeat(++unique));
            }
            lines.add("§r".repeat(++unique));
        }

        if (!inGame) lines.add(phase == Phase.COUNTDOWN ? "§aStarting..." : "§7Waiting...");
        return lines;
    }

    private void renderBoard(Player viewer) {
        Scoreboard b = boards.get(viewer.getUniqueId());
        if (b == null) return;
        Objective obj = b.getObjective("lanes");
        if (obj == null) return;

        List<String> lines = buildLines(viewer);
        List<String> last = lastLines.getOrDefault(viewer.getUniqueId(), List.of());
        if (lines.equals(last)) return;

        for (String old : last) b.resetScores(old);
        int score = lines.size();
        for (String line : lines) obj.getScore(line).setScore(score--);
        lastLines.put(viewer.getUniqueId(), lines);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String team = teams.get(p.getUniqueId());

        if ((phase == Phase.IN_GAME || phase == Phase.ENDING) && team != null) {   // rejoining
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(spawn(team));
            buildBoard(p);
        } else if (phase == Phase.IN_GAME || phase == Phase.ENDING) {
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(blueSpawn);
            buildBoard(p);
        } else {
            toLobby(p);
            changed();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        boards.remove(id);
        lastLines.remove(id);
        if (inLobbyPhase()) {
            teams.remove(id);
            champs.remove(id);
            ready.remove(id);
            changed();
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (id == null || id.equals("none")) return;

        e.setCancelled(true);
        if (!inLobbyPhase()) return;
        Player p = e.getPlayer();

        switch (id) {
            case "blue" -> joinTeam(p, "BLUE");
            case "red" -> joinTeam(p, "RED");
            case "hero" -> openMenu(p);
            case "ready" -> toggleReady(p);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            int slot = e.getSlot();
            if (slot < 0 || slot >= champions.size() || !inLobbyPhase()) return;
            pickHero(p, champions.get(slot));
            return;
        }
        if (phase != Phase.IN_GAME) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (phase != Phase.IN_GAME) e.setCancelled(true); //change later to detect abilities
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (phase != Phase.IN_GAME) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && phase != Phase.IN_GAME) e.setCancelled(true);
    }

    @EventHandler
    public void endWorldHunger(FoodLevelChangeEvent e) {
        if (phase != Phase.IN_GAME) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!isPlaying(p)) return;
        dead.add(p.getUniqueId());
        refreshBoards();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        dead.remove(id);
        String team = teams.get(id);
        e.setRespawnLocation(phase == Phase.IN_GAME && team != null ? spawn(team) : lobbySpawn.clone());
        Bukkit.getScheduler().runTask(plugin, this::refreshBoards);
    }
}

