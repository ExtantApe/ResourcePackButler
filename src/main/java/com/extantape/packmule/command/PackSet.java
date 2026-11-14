package com.extantape.packmule.command;

import com.extantape.packmule.config.PackmuleConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * {@code /packmule pack set <pack> <url>}: points a pack at a new per-build address (D189). This class holds the
 * parts that need no server, so they're tested on their own: what a new address must be, and the config edit.
 *
 * <p>The address must be https, with the SHA-1 as the file name, so the hash can't be given separately and get out of
 * step. The plugin downloads it and checks the SHA-1 before anything is written; a refusal leaves the old address.
 */
public final class PackSet {
    private PackSet() {}

    /** A refusal with a Signalbox error code and plain words. */
    public static final class Refused extends Exception {
        public final String code;
        Refused(String code, String message) { super(message); this.code = code; }
    }

    public static final String CHECK_FAILED = "check-failed";

    public static Refused refusal(String code, String message) { return new Refused(code, message); }

    public record Plan(String pack, String url, String sha1) {}

    /** What the command was asked to do, or why it won't. Nothing is fetched or written here. */
    public static Plan plan(PackmuleConfig config, String pack, String url) throws Refused {
        if (config == null) throw new Refused("config-problem", "config.yml has problems, so no pack can be changed. Fix it and run /packmule reload.");
        if (!config.packs().containsKey(pack)) throw new Refused("not-found", "There's no pack called " + pack + " in config.yml.");
        URI u;
        try { u = new URI(url); } catch (URISyntaxException e) { throw new Refused("bad-argument", "That isn't a valid address."); }
        if (!"https".equals(u.getScheme())) throw new Refused("bad-argument", "The address must start with https://, so the pack can't be swapped on the way.");
        if (u.getHost() == null || u.getHost().isBlank()) throw new Refused("bad-argument", "The address has no host name.");
        if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null)
            throw new Refused("bad-argument", "The address can't carry a user name, a query or a #.");
        String sha1 = PackmuleConfig.sha1FromAddress(url);
        if (sha1 == null) throw new Refused("bad-argument", "The file name must be the pack's SHA-1, such as …/0123456789abcdef0123456789abcdef01234567.zip.");
        return new Plan(pack, url, sha1.toLowerCase(Locale.ROOT));
    }

    /**
     * config.yml with the pack pointed at the new address, as text. Its sha1 and sha1-url go, since the file name now
     * carries the hash. Everything else, comments included, stays as it was.
     */
    public static String rewrite(String configText, Plan plan) throws Refused {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.loadFromString(configText);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new Refused("config-problem", "config.yml can't be read as YAML, so it wasn't changed.");
        }
        ConfigurationSection p = yaml.getConfigurationSection("packs." + plan.pack());
        if (p == null) throw new Refused("not-found", "There's no pack called " + plan.pack() + " in config.yml.");
        p.set("url", plan.url());
        p.set("sha1", null);
        p.set("sha1-url", null);
        return yaml.saveToString();
    }
}
