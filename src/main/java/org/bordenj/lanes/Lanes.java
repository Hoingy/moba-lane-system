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

    @Override
    public void onEnable() {
        // Plugin startup logic
        events = new Events(this);
        getServer().getPluginManager().registerEvents(events, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                events.DEBUGtowers();
                events.DEBUGguardians();

            }
        }.runTaskLater(this,20);

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
