package de.louis.dynamicweather;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class DynamicWeather extends JavaPlugin implements Listener {

    private BukkitTask task;

    private List<String> enabledWorlds;
    private int intervalTicks;

    private boolean thunderDarkness;
    private boolean rainDarkness;

    private boolean mobBuff;
    private final Set<EntityType> buffedMobs = EnumSet.noneOf(EntityType.class);
    private int speedLevel;
    private int strengthLevel;
    private int mobDurationTicks;

    private boolean rainCropGrowth;
    private double cropBonusChance;
    private boolean thunderSlowness;
    private boolean arrowWind;
    private double arrowWindStrength;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        load();
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
    }

    private void load() {
        reloadConfig();
        enabledWorlds = getConfig().getStringList("enabled-worlds");
        intervalTicks = Math.max(1, getConfig().getInt("check-interval-seconds", 2)) * 20;

        thunderDarkness = getConfig().getBoolean("visibility.thunder-darkness", true);
        rainDarkness = getConfig().getBoolean("visibility.rain-darkness", false);

        mobBuff = getConfig().getBoolean("mobs.thunder-buff", true);
        buffedMobs.clear();
        for (String name : getConfig().getStringList("mobs.types")) {
            try {
                buffedMobs.add(EntityType.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Unbekannter Mob-Typ in config.yml: " + name);
            }
        }
        speedLevel = getConfig().getInt("mobs.speed-level", 1);
        strengthLevel = getConfig().getInt("mobs.strength-level", 1);
        mobDurationTicks = getConfig().getInt("mobs.duration-seconds", 300) * 20;

        rainCropGrowth = getConfig().getBoolean("mechanics.rain-crop-growth", true);
        cropBonusChance = getConfig().getDouble("mechanics.crop-bonus-chance", 0.25);
        thunderSlowness = getConfig().getBoolean("mechanics.thunder-slowness", false);
        arrowWind = getConfig().getBoolean("mechanics.thunder-arrow-wind", true);
        arrowWindStrength = getConfig().getDouble("mechanics.arrow-wind-strength", 0.08);

        if (task != null) task.cancel();
        task = getServer().getScheduler().runTaskTimer(this, this::tickPlayers, intervalTicks, intervalTicks);
    }

    // ---------- Sicht & Spieler ----------

    private void tickPlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            World world = player.getWorld();
            if (!worldEnabled(world) || !world.hasStorm()) continue;
            if (!exposed(player.getLocation())) continue;

            boolean thunder = world.isThundering();
            int duration = intervalTicks + 40;

            if ((thunder && thunderDarkness) || rainDarkness) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, duration, 0, true, false, false));
            }
            if (thunder && thunderSlowness) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, 0, true, false, false));
            }
        }
    }

    // ---------- Mobs ----------

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (!mobBuff || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        LivingEntity mob = event.getEntity();
        if (!buffedMobs.contains(mob.getType())) return;

        Location loc = mob.getLocation();
        World world = loc.getWorld();
        if (!worldEnabled(world) || !world.isThundering() || !exposed(loc)) return;

        if (speedLevel > 0) {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, mobDurationTicks, speedLevel - 1, false, false));
        }
        if (strengthLevel > 0) {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, mobDurationTicks, strengthLevel - 1, false, false));
        }
    }

    // ---------- Spielmechaniken ----------

    /** Pflanzen wachsen bei Regen schneller. */
    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!rainCropGrowth) return;
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!worldEnabled(world) || !world.hasStorm() || !exposed(block.getLocation())) return;
        if (ThreadLocalRandom.current().nextDouble() >= cropBonusChance) return;

        BlockData data = event.getNewState().getBlockData();
        if (data instanceof Ageable ageable && ageable.getAge() < ageable.getMaximumAge()) {
            ageable.setAge(ageable.getAge() + 1);
            event.getNewState().setBlockData(ageable);
        }
    }

    /** Wind im Gewitter: Pfeile von Spielern streuen stärker. */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!arrowWind) return;
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;

        World world = shooter.getWorld();
        if (!worldEnabled(world) || !world.isThundering() || !exposed(shooter.getLocation())) return;

        Vector v = arrow.getVelocity();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector jitter = new Vector(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5)
                .multiply(v.length() * arrowWindStrength * 2);
        arrow.setVelocity(v.add(jitter));
    }

    // ---------- Hilfsmethoden ----------

    private boolean worldEnabled(World world) {
        if (enabledWorlds.isEmpty()) return world.getEnvironment() == World.Environment.NORMAL;
        return enabledWorlds.contains(world.getName());
    }

    /** Unter freiem Himmel und in einem Biom, in dem es regnet/schneit (nicht Wüste, Savanne, Mesa). */
    private boolean exposed(Location loc) {
        World world = loc.getWorld();
        if (world.getHighestBlockYAt(loc) > loc.getBlockY()) return false;
        return loc.getBlock().getTemperature() <= 0.95;
    }

    // ---------- Befehl ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage("DynamicWeather: Config neu geladen.");
            return true;
        }
        sender.sendMessage("Benutzung: /" + label + " reload");
        return true;
    }
}
