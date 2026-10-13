package org.bordenj.lanes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class Lanes extends JavaPlugin {

    private Events events;

    @Override
    public void onEnable() {
        // Plugin startup logic
        events = new Events(this);
        getServer().getPluginManager().registerEvents(events, this);

        Bukkit.getScheduler().runTask(this, () -> {
            events.DEBUGtowers();
            // events.startWaves(); // enable once towers work
        });

        for(Player p : Bukkit.getOnlinePlayers()){
            Objects.requireNonNull(p.getAttribute(Attribute.KNOCKBACK_RESISTANCE)).setBaseValue(1.0);
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        events.cleanup();
    }
}
