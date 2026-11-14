package com.extantape.packmule.command;

import com.extantape.packmule.config.PackmuleConfig;
import com.extantape.signalbox.Describe;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** /packmule pack set (D189): what a new address must be, and the config edit. The download check is in LinkCheckerTest. */
class PackSetTest {
    static final String H = "0123456789abcdef0123456789abcdef01234567";
    static final String CONFIG = """
            # Packmule
            # A comment the owner wrote.
            packs:
              # the main pack
              main:
                url: https://packs.example.com/old/pack.zip
                sha1-url: https://packs.example.com/old/pack.zip.sha1
              extra:
                url: https://example.com/extra.zip
                sha1: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            worlds:
              world: [main, extra]
            """;

    static PackmuleConfig config() throws Exception {
        var y = new YamlConfiguration();
        y.loadFromString(CONFIG);
        var r = PackmuleConfig.parse(y);
        assertTrue(r.ok(), r.errors().toString());
        return r.config();
    }

    @Test void aPerBuildHttpsAddressIsPlannedWithItsHash() throws Exception {
        var p = PackSet.plan(config(), "main", "https://join.example.org/p/abc/" + H.toUpperCase() + ".zip");
        assertEquals("main", p.pack());
        assertEquals(H, p.sha1(), "the SHA-1 comes from the file name, lowercased");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://join.example.org/p/" + H + ".zip",
            "ftp://join.example.org/p/" + H + ".zip",
            "https:///p/" + H + ".zip",
            "https://user" + "@" + "join.example.org/p/" + H + ".zip",
            "https://join.example.org/p/" + H + ".zip?x=1",
            "https://join.example.org/p/" + H + ".zip#x",
            "https://join.example.org/p/pack.zip",
            "https://join.example.org/p/" + H + ".zip.txt",
            "not a url"})
    void anythingButAnHttpsPerBuildAddressIsRefused(String url) throws Exception {
        var e = assertThrows(PackSet.Refused.class, () -> PackSet.plan(config(), "main", url));
        assertEquals("bad-argument", e.code);
    }

    @Test void anUnknownPackIsRefused() throws Exception {
        assertEquals("not-found", assertThrows(PackSet.Refused.class, () -> PackSet.plan(config(), "nope", "https://x.org/" + H + ".zip")).code);
    }

    @Test void withABrokenConfigNothingCanBeSet() {
        assertEquals("config-problem", assertThrows(PackSet.Refused.class, () -> PackSet.plan(null, "main", "https://x.org/" + H + ".zip")).code);
    }

    @Test void theEditChangesOnlyThatPackAndKeepsTheComments() throws Exception {
        String url = "https://join.example.org/p/abc/" + H + ".zip";
        String out = PackSet.rewrite(CONFIG, new PackSet.Plan("main", url, H));
        assertTrue(out.contains("# A comment the owner wrote."), out);
        assertTrue(out.contains("# the main pack"), out);
        var y = new YamlConfiguration();
        y.loadFromString(out);
        assertEquals(url, y.getString("packs.main.url"));
        assertNull(y.get("packs.main.sha1-url"), "the file name carries the hash now");
        assertNull(y.get("packs.main.sha1"));
        assertEquals("https://example.com/extra.zip", y.getString("packs.extra.url"));
        assertEquals("a".repeat(40), y.getString("packs.extra.sha1"), "other packs are untouched");
        assertEquals(java.util.List.of("main", "extra"), y.getStringList("worlds.world"));
        var parsed = PackmuleConfig.parse(y);
        assertTrue(parsed.ok(), parsed.errors().toString());
        assertEquals(H, parsed.config().packs().get("main").sha1());
    }

    @Test void theEditRefusesYamlItCantRead() {
        assertEquals("config-problem", assertThrows(PackSet.Refused.class,
                () -> PackSet.rewrite("packs: [", new PackSet.Plan("main", "https://x.org/" + H + ".zip", H))).code);
    }

    @Test void describeListsPackSetForLeafJuice() {
        var body = Describe.body("Packmule", "0.2.1", PackmuleCommand.described());
        assertEquals(1, body.get("format"));
        assertTrue(body.toString().contains("name=pack set"), body.toString());
        assertTrue(body.toString().contains("permission=packmule.pack.set"));
    }
}
