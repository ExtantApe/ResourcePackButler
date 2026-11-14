package com.extantape.packmule.check;

import com.extantape.packmule.config.PackmuleConfig.PackSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a small web server on 127.0.0.1 with made-up packs. */
class LinkCheckerTest {
    private HttpServer server;
    private String base;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final LinkChecker checker = new LinkChecker(Duration.ofSeconds(2));

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/slow")) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                }
                byte[] body = files.get(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static byte[] pack(String text) {
        return ("PK-fake-" + text).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void perBuildAddressIsLive() throws Exception {
        byte[] a = pack("a");
        String h = sha1(a);
        files.put("/tok/" + h + ".zip", a);
        var outcome = checker.check(new PackSource("a", base + "/tok/" + h + ".zip", h, null), null);
        assertTrue(outcome.live(), outcome.deadReason());
        assertTrue(outcome.buildAddress());
    }

    @Test
    void movingAddressPrefersThePerBuildOne() throws Exception {
        byte[] a = pack("a");
        String h = sha1(a);
        files.put("/tok/pack.zip", a);
        files.put("/tok/pack.zip.sha1", (h + "  pack.zip\n").getBytes(StandardCharsets.UTF_8));
        files.put("/tok/" + h + ".zip", a);
        var outcome = checker.check(new PackSource("a", base + "/tok/pack.zip", null, base + "/tok/pack.zip.sha1"), null);
        assertTrue(outcome.live(), outcome.deadReason());
        assertEquals(base + "/tok/" + h + ".zip", outcome.url());
        assertEquals(h, outcome.freshSha1());
    }

    @Test
    void movingAddressWithoutPerBuildFallsBackAndSaysSo() throws Exception {
        byte[] a = pack("a");
        String h = sha1(a);
        files.put("/tok/pack.zip", a);
        files.put("/tok/pack.zip.sha1", h.getBytes(StandardCharsets.UTF_8));
        var outcome = checker.check(new PackSource("a", base + "/tok/pack.zip", null, base + "/tok/pack.zip.sha1"), null);
        assertTrue(outcome.live());
        assertEquals(base + "/tok/pack.zip", outcome.url());
        assertFalse(outcome.buildAddress());
        assertFalse(outcome.notes().isEmpty());
    }

    @Test
    void notFoundIsDead() {
        var outcome = checker.check(new PackSource("a", base + "/gone/" + "a".repeat(40) + ".zip", "a".repeat(40), null), null);
        assertFalse(outcome.live());
        assertTrue(outcome.deadReason().contains("404"), outcome.deadReason());
    }

    @Test
    void wrongHashIsDead() throws Exception {
        files.put("/x.zip", pack("x"));
        var outcome = checker.check(new PackSource("a", base + "/x.zip", "b".repeat(40), null), null);
        assertFalse(outcome.live());
        assertTrue(outcome.deadReason().startsWith("wrong hash"), outcome.deadReason());
    }

    @Test
    void aPerBuildFileThatIsntWhatItsNameSaysIsDead() throws Exception {
        // pack set relies on this: a file named for one SHA-1 that serves another is refused, so the old url stays.
        String named = "c".repeat(40);
        files.put("/p/" + named + ".zip", pack("not that build"));
        var outcome = checker.check(new PackSource("a", base + "/p/" + named + ".zip", named, null), null);
        assertFalse(outcome.live());
        assertTrue(outcome.deadReason().startsWith("wrong hash"), outcome.deadReason());
    }

    @Test
    void timeoutIsDeadAndQuick() {
        files.put("/slow", pack("s"));
        long start = System.nanoTime();
        var outcome = checker.check(new PackSource("a", base + "/slow", "c".repeat(40), null), null);
        assertFalse(outcome.live());
        assertTrue(outcome.deadReason().contains("timed out"), outcome.deadReason());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 4);
    }

    @Test
    void nothingListeningIsDead() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var outcome = checker.check(new PackSource("a", "http://127.0.0.1:" + port + "/x.zip", "c".repeat(40), null), null);
        assertFalse(outcome.live());
    }

    @Test
    void malformedUrlIsDeadNotFatal() throws Exception {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString("packs:\n  bad: {url: 'htp:/nope'}\nworlds:\n  w: [bad]\n");
        var result = com.extantape.packmule.config.PackmuleConfig.parse(yaml);
        assertTrue(result.ok(), result.errors().toString());
        var outcome = checker.check(result.config().packs().get("bad"), null);
        assertFalse(outcome.live());
        assertTrue(outcome.deadReason().startsWith("bad url"), outcome.deadReason());
    }

    @Test
    void unknownHostIsDead() {
        var outcome = checker.check(new PackSource("a", "http://no-such-host.invalid/x.zip", "c".repeat(40), null), null);
        assertFalse(outcome.live());
    }

    @Test
    void brokenHashFileKeepsTheLastGoodHash() throws Exception {
        byte[] old = pack("old");
        String h = sha1(old);
        files.put("/tok/" + h + ".zip", old);
        files.put("/tok/pack.zip", pack("new"));
        files.put("/tok/pack.zip.sha1", "not a hash".getBytes(StandardCharsets.UTF_8));
        var source = new PackSource("a", base + "/tok/pack.zip", null, base + "/tok/pack.zip.sha1");
        var outcome = checker.check(source, h);
        assertTrue(outcome.live(), outcome.deadReason());
        assertEquals(h, outcome.sha1());
        assertNull(outcome.freshSha1());
        assertTrue(outcome.notes().stream().anyMatch(n -> n.contains("last good hash")), outcome.notes().toString());

        files.remove("/tok/pack.zip.sha1");
        outcome = checker.check(source, h);
        assertTrue(outcome.live());
        assertEquals(h, outcome.sha1());

        outcome = checker.check(source, null);
        assertFalse(outcome.live(), "no hash file and no earlier hash");
    }

    @Test
    void hashStoreSurvivesARestart(@TempDir Path dir) {
        var store = new HashStore(dir.resolve("last-good-hashes.yml"));
        assertNull(store.load());
        assertNull(store.put("https://h/a.sha1", "d".repeat(40)));
        var again = new HashStore(dir.resolve("last-good-hashes.yml"));
        assertNull(again.load());
        assertEquals("d".repeat(40), again.get("https://h/a.sha1"));
    }
}
