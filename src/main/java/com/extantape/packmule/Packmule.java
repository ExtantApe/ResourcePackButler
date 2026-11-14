package com.extantape.packmule;

import com.extantape.packmule.check.HashStore;
import com.extantape.packmule.check.LinkChecker;
import com.extantape.packmule.check.LinkChecker.Outcome;
import com.extantape.packmule.command.PackmuleCommand;
import com.extantape.packmule.config.PackmuleConfig;
import com.extantape.packmule.config.PackmuleConfig.PackSource;
import com.extantape.packmule.send.PackSender;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Packmule extends JavaPlugin implements Listener {
    private static final long WARN_AGAIN_AFTER_MS = 60_000;

    private PackmuleConfig config;
    /** Each pack's last check, with the source it was made from so a changed source is never sent stale. */
    private volatile Map<String, Checked> outcomes = Map.of();
    private HashStore hashStore;
    private PackSender sender;
    private ExecutorService checks;
    private long checkRound;
    private boolean checking;
    private final Map<String, Long> lastWarned = new HashMap<>();

    private record Checked(PackSource source, Outcome outcome) {
    }

    /** One pack change at a time, so two callers can't race on config.yml. */
    private volatile boolean setting;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        checks = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "Packmule-check");
            thread.setDaemon(true);
            return thread;
        });
        hashStore = new HashStore(getDataFolder().toPath().resolve("last-good-hashes.yml"));
        String storeProblem = hashStore.load();
        if (storeProblem != null) getLogger().warning(storeProblem);

        PackmuleConfig.Result result = readConfig();
        if (result.ok()) {
            config = result.config();
        } else {
            logErrors(result);
            getLogger().severe("config.yml has problems, so Packmule sends no packs until it's fixed and /packmule reload is run.");
        }

        sender = new PackSender(this);
        Bukkit.getPluginManager().registerEvents(sender, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        PackmuleCommand commands = new PackmuleCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> commands.register(event.registrar()));

        // On the first tick, when every plugin has loaded its worlds.
        Bukkit.getScheduler().runTask(this, () -> {
            warnAboutServerProperties();
            if (config != null) {
                warnAboutWorlds();
                startCheck(null, true);
            }
        });
    }

    @Override
    public void onDisable() {
        if (checks != null) checks.shutdownNow();
        if (sender != null) sender.clear();
    }

    public PackmuleConfig config() {
        return config;
    }

    /** The checked state of a pack, or null while it hasn't been checked since its source last changed. */
    public Outcome outcome(String pack) {
        Checked checked = outcomes.get(pack);
        if (checked == null || config == null || !checked.source().equals(config.packs().get(pack))) return null;
        return checked.outcome();
    }

    public PackSender sender() {
        return sender;
    }

    public boolean checking() {
        return checking;
    }

    /** Reads config.yml again. On any problem the old config stays in use. Returns the problems. */
    public List<String> reload() {
        PackmuleConfig.Result result = readConfig();
        if (!result.ok()) {
            logErrors(result);
            getLogger().warning(config == null ? "Still sending no packs." : "Keeping the config that was already loaded.");
            return result.errors();
        }
        config = result.config();
        warnAboutServerProperties();
        warnAboutWorlds();
        startCheck(null, false);
        return List.of();
    }

    /**
     * Points a pack at a new per-build address (D189). Downloads it off the main thread and checks its SHA-1 against
     * the file name. Only a pass is written to config.yml (comments kept), and then the config is reloaded as if by
     * /packmule reload. Any failure leaves config.yml and the pack as they were. {@code done} gets the outcome on the
     * main thread: null when it worked, or the refusal.
     */
    public boolean setPack(com.extantape.packmule.command.PackSet.Plan plan, java.util.function.Consumer<com.extantape.packmule.command.PackSet.Refused> done) {
        if (setting) return false;
        setting = true;
        LinkChecker checker = new LinkChecker(Duration.ofSeconds(config.checkTimeoutSeconds()));
        CompletableFuture.supplyAsync(() -> checker.check(new PackSource(plan.pack(), plan.url(), plan.sha1(), null), null), checks)
                .whenComplete((outcome, error) -> {
                    if (!isEnabled()) return;
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            if (error != null) done.accept(com.extantape.packmule.command.PackSet.refusal(com.extantape.packmule.command.PackSet.CHECK_FAILED, "The check stopped early, so nothing changed."));
                            else if (!outcome.live()) done.accept(com.extantape.packmule.command.PackSet.refusal(com.extantape.packmule.command.PackSet.CHECK_FAILED,
                                    "The new address failed its check (" + outcome.deadReason() + "), so the old one stays."));
                            else done.accept(write(plan));
                        } finally {
                            setting = false;
                        }
                    });
                });
        return true;
    }

    /** Writes the checked address and reloads. On any problem config.yml goes back to what it was. */
    private com.extantape.packmule.command.PackSet.Refused write(com.extantape.packmule.command.PackSet.Plan plan) {
        java.nio.file.Path file = getDataFolder().toPath().resolve("config.yml");
        String before;
        try {
            before = java.nio.file.Files.readString(file);
            String after = com.extantape.packmule.command.PackSet.rewrite(before, plan);
            java.nio.file.Path tmp = file.resolveSibling("config.yml.packmule-new");
            java.nio.file.Files.writeString(tmp, after);
            java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (com.extantape.packmule.command.PackSet.Refused r) {
            return r;
        } catch (java.io.IOException e) {
            getLogger().warning("Couldn't write config.yml for pack '" + plan.pack() + "': " + e.getMessage());
            return com.extantape.packmule.command.PackSet.refusal("internal", "config.yml couldn't be written, so nothing changed. The server log has the details.");
        }
        List<String> errors = reload();
        if (!errors.isEmpty()) {
            try { java.nio.file.Files.writeString(file, before); } catch (java.io.IOException e) { getLogger().severe("Couldn't put config.yml back: " + e.getMessage()); }
            reload();
            return com.extantape.packmule.command.PackSet.refusal("config-problem", "The changed config.yml didn't load (" + String.join("; ", errors) + "), so it was put back.");
        }
        getLogger().info("Pack '" + plan.pack() + "' now points at its new build, " + plan.sha1() + ".");
        return null;
    }

    /** Re-tests every link and re-reads every hash file. Players online keep what they have until they change worlds. */
    public void startCheck(org.bukkit.command.CommandSender reportTo, boolean first) {
        PackmuleConfig snapshot = config;
        if (snapshot == null) return;
        long round = ++checkRound;
        checking = true;
        LinkChecker checker = new LinkChecker(Duration.ofSeconds(snapshot.checkTimeoutSeconds()));
        List<CompletableFuture<Checked>> futures = new ArrayList<>();
        for (PackSource source : snapshot.packs().values()) {
            String lastGood = source.movingAddress() ? hashStore.get(source.sha1Url()) : null;
            futures.add(CompletableFuture.supplyAsync(() -> new Checked(source, checker.check(source, lastGood)), checks));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            Map<String, Checked> done = new LinkedHashMap<>();
            for (CompletableFuture<Checked> future : futures) {
                if (!future.isCompletedExceptionally()) {
                    Checked checked = future.join();
                    done.put(checked.source().name(), checked);
                }
            }
            for (Checked checked : done.values()) {
                Outcome outcome = checked.outcome();
                if (outcome.live() && outcome.freshSha1() != null) {
                    String problem = hashStore.put(checked.source().sha1Url(), outcome.freshSha1());
                    if (problem != null) getLogger().warning(problem);
                }
            }
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> finishCheck(round, snapshot, done, reportTo, first, error));
        });
    }

    private void finishCheck(long round, PackmuleConfig snapshot, Map<String, Checked> done,
                             org.bukkit.command.CommandSender reportTo, boolean first, Throwable error) {
        if (round != checkRound) return; // A newer check started; it reports instead.
        checking = false;
        outcomes = Map.copyOf(done);
        if (error != null) getLogger().warning("A link check stopped early: " + error);
        int dead = 0;
        for (Checked checked : done.values()) {
            Outcome outcome = checked.outcome();
            String name = outcome.name();
            for (String note : outcome.notes()) getLogger().warning("Pack '" + name + "': " + note);
            if (outcome.live()) {
                getLogger().info("Pack '" + name + "' is live: " + outcome.sha1() + " at "
                        + (outcome.buildAddress() ? "its per-build address" : "its address") + ".");
            } else {
                dead++;
                List<String> worlds = worldsUsing(snapshot, name);
                warnAdmins("dead:" + name, "Pack '" + name + "' link is dead: " + outcome.deadReason() + ". "
                        + (worlds.isEmpty() ? "No world uses it." : "Players in " + String.join(", ", worlds) + " get in without it.")
                        + " Run /packmule recheck once it's fixed.", true);
            }
        }
        String summary = done.size() + " pack" + (done.size() == 1 ? "" : "s") + " checked, " + dead + " dead.";
        getLogger().info(summary);
        if (reportTo != null && !(reportTo instanceof org.bukkit.command.ConsoleCommandSender)) {
            reportTo.sendMessage(Component.text("Packmule: " + summary, dead == 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        if (first) sender.fillMissing();
    }

    private static List<String> worldsUsing(PackmuleConfig config, String pack) {
        List<String> worlds = new ArrayList<>();
        config.worlds().forEach((world, packs) -> {
            if (packs.contains(pack)) worlds.add(world);
        });
        return worlds;
    }

    public void warnAdmins(String key, String message) {
        warnAdmins(key, message, false);
    }

    /** Logs a warning and tells online admins. The same warning repeats at most once a minute unless forced. */
    public void warnAdmins(String key, String message, boolean force) {
        long now = System.currentTimeMillis();
        Long last = lastWarned.get(key);
        if (!force && last != null && now - last < WARN_AGAIN_AFTER_MS) return;
        lastWarned.put(key, now);
        getLogger().warning(message);
        Component line = Component.text("[Packmule] " + message, NamedTextColor.RED);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("packmule.notify")) player.sendMessage(line);
        }
    }

    @EventHandler
    public void onAdminJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("packmule.notify") || config == null) return;
        List<String> dead = new ArrayList<>();
        for (String name : config.packs().keySet()) {
            Outcome outcome = outcome(name);
            if (outcome != null && !outcome.live()) dead.add(name);
        }
        if (!dead.isEmpty()) {
            player.sendMessage(Component.text("[Packmule] Dead pack links: " + String.join(", ", dead)
                    + ". /packmule status has details.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (config != null && !config.worlds().containsKey(event.getWorld().getName())) {
            getLogger().warning("World '" + event.getWorld().getName() + "' isn't listed under worlds, so Packmule sends it no packs.");
        }
    }

    private PackmuleConfig.Result readConfig() {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            return new PackmuleConfig.Result(null, List.of("config.yml can't be read: " + e.getMessage()));
        }
        return PackmuleConfig.parse(yaml);
    }

    private void logErrors(PackmuleConfig.Result result) {
        for (String error : result.errors()) getLogger().severe(error);
    }

    private void warnAboutServerProperties() {
        String url = Bukkit.getResourcePack();
        if (url != null && !url.isBlank()) {
            getLogger().warning("server.properties sets resource-pack=" + url + ". That pack fights Packmule's packs: clear resource-pack= and restart.");
        }
    }

    private void warnAboutWorlds() {
        for (World world : Bukkit.getWorlds()) {
            if (!config.worlds().containsKey(world.getName())) {
                getLogger().warning("World '" + world.getName() + "' isn't listed under worlds, so Packmule sends it no packs. List it, with [] if that's intended.");
            }
        }
        for (String world : config.worlds().keySet()) {
            if (Bukkit.getWorld(world) == null) getLogger().info("World '" + world + "' is listed but not loaded right now.");
        }
        for (String pack : config.packs().keySet()) {
            if (worldsUsing(config, pack).isEmpty()) getLogger().info("Pack '" + pack + "' isn't used by any world.");
        }
    }
}
