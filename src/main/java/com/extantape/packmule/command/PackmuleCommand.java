package com.extantape.packmule.command;

import com.extantape.packmule.Packmule;
import com.extantape.packmule.check.LinkChecker.Outcome;
import com.extantape.packmule.config.PackmuleConfig;
import com.extantape.signalbox.Answer;
import com.extantape.signalbox.Describe;
import com.extantape.signalbox.Errors;
import com.extantape.signalbox.Request;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /packmule recheck, reload and status [player], registered through Paper's Brigadier command API. */
public final class PackmuleCommand {
    private static final String RECHECK = "packmule.recheck";
    private static final String RELOAD = "packmule.reload";
    private static final String STATUS = "packmule.status";
    static final String PACK_SET = "packmule.pack.set";

    /** The format-1 commands (Signalbox), for tools such as Leaf Juice. Each also answers people in plain words. */
    public static java.util.List<Describe.Command> described() {
        return List.of(
                new Describe.Command("pack set", PACK_SET, List.of(Describe.Arg.of("pack", "string"), Describe.Arg.of("url", "string")), List.of(), false),
                new Describe.Command("describe", STATUS, List.of(), List.of(), false));
    }

    private final Packmule plugin;

    public PackmuleCommand(Packmule plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        commands.register(tree("packmule", false), "Re-check pack links, reload the config, or show what Packmule is sending.");
        // The old names, for one release (0.2.x) after the rename from ResourcePackButler. Each use says where to move.
        commands.register(tree("butler", true), "Old name for /packmule. Goes in the next release.", List.of("resourcepackbutler", "rpb"));
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> tree(String label, boolean old) {
        return Commands.literal(label)
                .requires(source -> has(source, RECHECK) || has(source, RELOAD) || has(source, STATUS))
                .executes(ctx -> run(old, ctx, () -> reply(ctx.getSource().getSender(), "Usage: /packmule <recheck|reload|status [player]|pack set <pack> <url>|describe>")))
                .then(Commands.literal("recheck").requires(source -> has(source, RECHECK))
                        .executes(ctx -> run(old, ctx, () -> recheck(ctx.getSource().getSender()))))
                .then(Commands.literal("reload").requires(source -> has(source, RELOAD))
                        .executes(ctx -> run(old, ctx, () -> reload(ctx.getSource().getSender()))))
                .then(Commands.literal("pack").requires(source -> has(source, PACK_SET))
                        .then(Commands.literal("set")
                                .executes(ctx -> run(old, ctx, () -> packSet(ctx.getSource().getSender(), new String[0])))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(ctx -> run(old, ctx, () -> packSet(ctx.getSource().getSender(), split(StringArgumentType.getString(ctx, "args"))))))))
                .then(Commands.literal("describe").requires(source -> has(source, STATUS))
                        .executes(ctx -> run(old, ctx, () -> describe(ctx.getSource().getSender(), new String[0])))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> run(old, ctx, () -> describe(ctx.getSource().getSender(), split(StringArgumentType.getString(ctx, "args")))))))
                .then(Commands.literal("status").requires(source -> has(source, STATUS))
                        .executes(ctx -> run(old, ctx, () -> status(ctx.getSource().getSender())))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> run(old, ctx, () -> playerStatus(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"))))))
                .build();
    }

    private int run(boolean old, com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, java.util.function.IntSupplier body) {
        if (old) {
            String line = "/butler, /resourcepackbutler and /rpb are the old names for /packmule. They still work in 0.2.x but go in the next release.";
            plugin.getLogger().warning(line);
            ctx.getSource().getSender().sendMessage(Component.text(line, NamedTextColor.YELLOW));
        }
        return body.getAsInt();
    }

    private boolean has(CommandSourceStack source, String permission) {
        return plugin.allowed(source.getSender(), permission);
    }

    private static int reply(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text));
        return Command.SINGLE_SUCCESS;
    }

    private static String[] split(String raw) {
        String t = raw.trim();
        return t.isEmpty() ? new String[0] : t.split(" +");
    }

    /** One answer line for a tool, or plain words for a person. */
    private static int answer(CommandSender sender, Request r, String code, String message) {
        if (r != null && r.json()) sender.sendMessage(Answer.error(r.id(), code, message));
        else sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return 0;
    }

    private int describe(CommandSender sender, String[] args) {
        Request r;
        try { r = Request.parse(args); } catch (Request.Bad bad) {
            if (bad.id != null) sender.sendMessage(Answer.error(bad.id, bad.code, bad.getMessage())); else reply(sender, bad.getMessage());
            return 0;
        }
        if (r.json()) sender.sendMessage(Answer.ok(r.id(), Describe.body("Packmule", plugin.getPluginMeta().getVersion(), described())));
        else for (Describe.Command d : described()) reply(sender, "/packmule " + d.name() + (d.name().equals("pack set") ? " <pack> <url>" : ""));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /packmule pack set <pack> <url> [--json id]: checks the new per-build address, and only on a pass writes it to
     * config.yml and reloads. Refuses http, a file name that isn't the SHA-1, a download whose SHA-1 doesn't match it,
     * and an unknown pack, leaving everything as it was.
     */
    private int packSet(CommandSender sender, String[] args) {
        Request r;
        try { r = Request.parse(args); } catch (Request.Bad bad) {
            if (bad.id != null) sender.sendMessage(Answer.error(bad.id, bad.code, bad.getMessage())); else reply(sender, bad.getMessage());
            return 0;
        }
        if (r.tokens().size() != 2 || r.freeText() != null) return answer(sender, r, Errors.BAD_ARGUMENT, "Use: /packmule pack set <pack> <url>");
        PackSet.Plan plan;
        try {
            plan = PackSet.plan(plugin.config(), r.tokens().get(0), r.tokens().get(1));
        } catch (PackSet.Refused no) {
            return answer(sender, r, no.code, no.getMessage());
        }
        boolean started = plugin.setPack(plan, refused -> {
            if (refused != null) { answer(sender, r, refused.code, refused.getMessage()); return; }
            if (r.json()) sender.sendMessage(Answer.ok(r.id(), new java.util.LinkedHashMap<>(java.util.Map.of("pack", plan.pack(), "sha1", plan.sha1(), "check", "passed"))));
            else sender.sendMessage(Component.text("Pack '" + plan.pack() + "' now points at " + plan.url() + ". It passed its check and the config was reloaded.", NamedTextColor.GREEN));
        });
        if (!started) return answer(sender, r, Errors.BUSY, "Another pack change is still being checked. Try again in a moment.");
        if (!r.json()) reply(sender, "Checking the new address for pack '" + plan.pack() + "'. Nothing changes unless it passes.");
        return Command.SINGLE_SUCCESS;
    }

    private int recheck(CommandSender sender) {
        if (plugin.config() == null) {
            sender.sendMessage(Component.text("config.yml has problems, so there's nothing to check. Fix it and run /packmule reload.", NamedTextColor.RED));
            return 0;
        }
        reply(sender, "Re-checking every pack link. Players online keep their packs until they change worlds or rejoin.");
        plugin.startCheck(sender, false);
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSender sender) {
        List<String> errors = plugin.reload();
        if (errors.isEmpty()) {
            return reply(sender, "Reloaded, and re-checking links. Players online keep their packs until they change worlds or rejoin.");
        }
        sender.sendMessage(Component.text("config.yml has problems, so nothing changed:", NamedTextColor.RED));
        for (String error : errors) sender.sendMessage(Component.text(" - " + error, NamedTextColor.RED));
        return 0;
    }

    private int playerStatus(CommandSender sender, String name) {
        Player player = plugin.getServer().getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(Component.text(name + " isn't online.", NamedTextColor.RED));
            return 0;
        }
        return reply(sender, player.getName() + " in '" + player.getWorld().getName() + "': " + String.join(", ", plugin.sender().describe(player)));
    }

    private int status(CommandSender sender) {
        PackmuleConfig config = plugin.config();
        if (config == null) {
            sender.sendMessage(Component.text("No config loaded: config.yml has problems. Fix it and run /packmule reload.", NamedTextColor.RED));
            return 0;
        }
        reply(sender, "Packs" + (plugin.checking() ? " (a check is running)" : "") + ":");
        for (String name : config.packs().keySet()) {
            Component line = switch (plugin.outcome(name)) {
                case null -> Component.text(" " + name + ": not checked yet", NamedTextColor.YELLOW);
                case Outcome o when o.live() -> Component.text(" " + name + ": live, " + o.sha1()
                        + (o.buildAddress() ? ", per-build address" : ""), NamedTextColor.GREEN);
                case Outcome o -> Component.text(" " + name + ": DEAD, " + o.deadReason(), NamedTextColor.RED);
            };
            sender.sendMessage(line);
        }
        reply(sender, "Worlds:");
        config.worlds().forEach((world, packs) -> reply(sender, " " + world + ": " + (packs.isEmpty() ? "none" : String.join(", ", packs))));
        return Command.SINGLE_SUCCESS;
    }
}
