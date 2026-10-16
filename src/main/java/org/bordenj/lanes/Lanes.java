package org.bordenj.lanes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Objects;

public final class Lanes extends JavaPlugin {

    private Events events;
    private Match match;

    @Override
    public void onEnable() {
        // Plugin startup logic
        events = new Events(this);
        match = new Match(this,events);
        events.setMatch(match);
        getServer().getPluginManager().registerEvents(events, this);
        getServer().getPluginManager().registerEvents(match, this);
        match.init();

        for(Player p : Bukkit.getOnlinePlayers()){
            Objects.requireNonNull(p.getAttribute(Attribute.KNOCKBACK_RESISTANCE)).setBaseValue(1.0);
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        events.stopMatch();
    }
}
