package com.extantape.packmule.send;

import com.extantape.packmule.send.PackPlan.Pack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackPlanTest {
    private static Pack pack(String name, String sha1) {
        return new Pack(PackPlan.idFor(name), name, "https://h/" + sha1 + ".zip", sha1);
    }

    private static final Pack A = pack("a", "1".repeat(40));
    private static final Pack B = pack("b", "2".repeat(40));
    private static final Pack C = pack("c", "3".repeat(40));

    @Test
    void samePacksChangeNothing() {
        assertTrue(PackPlan.between(List.of(A, B), List.of(A, B)).none());
    }

    @Test
    void emptyListRemovesOnlyPackmulesPacks() {
        var change = PackPlan.between(List.of(A, B), List.of());
        assertEquals(List.of(A.id(), B.id()), change.remove());
        assertTrue(change.send().isEmpty());
    }

    @Test
    void swapReplacesTheWholeList() {
        var change = PackPlan.between(List.of(A), List.of(C));
        assertEquals(List.of(A.id()), change.remove());
        assertEquals(List.of(C), change.send());
    }

    @Test
    void sharedStartIsKept() {
        var change = PackPlan.between(List.of(A, B), List.of(A, C));
        assertEquals(1, change.keep());
        assertEquals(List.of(B.id()), change.remove());
        assertEquals(List.of(C), change.send());
    }

    @Test
    void insertedPackResendsWhatFollowsToKeepOrder() {
        var change = PackPlan.between(List.of(A, B), List.of(A, C, B));
        assertEquals(List.of(B.id()), change.remove());
        assertEquals(List.of(C, B), change.send());
    }

    @Test
    void newHashForSamePackIsResent() {
        Pack newer = pack("a", "9".repeat(40));
        assertEquals(A.id(), newer.id());
        var change = PackPlan.between(List.of(A), List.of(newer));
        assertEquals(List.of(newer), change.send());
    }

    @Test
    void idsAreStableAndDistinct() {
        assertEquals(PackPlan.idFor("main"), PackPlan.idFor("main"));
        assertNotEquals(PackPlan.idFor("main"), PackPlan.idFor("other"));
    }

    @Test
    void packIdsUseThePackmuleNamespace() {
        // Clients see these IDs. If they change, every player re-downloads every pack once (they did in 0.3.0, with the move to packmule:pack:).
        assertEquals(java.util.UUID.fromString("7a4d3d03-22bb-36c5-8e61-df5f60b72d5f"), PackPlan.idFor("main"));
    }
}
