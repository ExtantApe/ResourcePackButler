package com.extantape.packmule.send;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Works out what to change on a client to go from the packs Packmule last sent to the packs a world wants.
 * Packs stack in the order they're sent, so everything from the first difference on is removed and sent
 * again; the matching start of the list is left alone. Only Packmule's own pack IDs ever appear here.
 */
public final class PackPlan {
    private PackPlan() {
    }

    /** One pack as sent: its stable ID, and the exact address and hash. */
    public record Pack(UUID id, String name, String url, String sha1) {
        public boolean sameAs(Pack other) {
            return id.equals(other.id) && url.equals(other.url) && sha1.equals(other.sha1);
        }
    }

    /** {@code keep} packs stay as they are; {@code remove} IDs come off, then {@code send} goes on, in order. */
    public record Change(int keep, List<UUID> remove, List<Pack> send) {
        public boolean none() {
            return remove.isEmpty() && send.isEmpty();
        }
    }

    /** Stable per pack name, so a new hash for the same pack replaces it on the client. */
    public static UUID idFor(String packName) {
        // Keeps the namespace from before the rename to Packmule (2026-11-09) on purpose: these IDs go to clients,
        // and changing them would make every player's client drop and re-download every pack once.
        return UUID.nameUUIDFromBytes(("packmule:pack:" + packName).getBytes(StandardCharsets.UTF_8));
    }

    public static Change between(List<Pack> sent, List<Pack> wanted) {
        int keep = 0;
        while (keep < sent.size() && keep < wanted.size() && sent.get(keep).sameAs(wanted.get(keep))) keep++;
        List<UUID> remove = new ArrayList<>();
        for (int i = keep; i < sent.size(); i++) remove.add(sent.get(i).id());
        return new Change(keep, List.copyOf(remove), List.copyOf(wanted.subList(keep, wanted.size())));
    }
}
