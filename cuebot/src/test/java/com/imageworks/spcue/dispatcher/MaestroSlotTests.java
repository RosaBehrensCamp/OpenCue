/*
 * Copyright Contributors to the OpenCue Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.imageworks.spcue.dispatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

/**
 * Slot-based booking in Maestro: slot hosts and slot layers are strictly paired by the host-spec
 * group, a slot frame is fit and scored on slots alone, and only max_slots (job, folder,
 * subscription) caps a slot layer; core caps and the core burst never do.
 */
public class MaestroSlotTests {

    private static final Maestro.TagMatcher ANY = (group, layer) -> true;

    private static Maestro.BookableHost slotHost(String id, int limit, int idle) {
        Maestro.BookableHost h = new Maestro.BookableHost();
        h.hostId = id;
        h.hostName = id;
        h.pkAlloc = "alloc";
        h.pkFacility = "facility";
        h.tagsRaw = "general";
        h.os = "linux";
        h.coresTotal = 800;
        h.coresIdle = 800;
        h.memTotal = 32L * 1024 * 1024;
        h.memIdle = h.memTotal;
        h.slotsLimit = limit;
        h.slotsIdle = idle;
        return h;
    }

    private static Maestro.BookableHost regularHost(String id) {
        Maestro.BookableHost h = slotHost(id, -1, 0);
        h.slotsLimit = -1;
        return h;
    }

    private static Maestro.LayerCandidate slotLayer(String id, int slots) {
        Maestro.LayerCandidate c = new Maestro.LayerCandidate();
        c.layerId = id;
        c.jobId = "job-" + id;
        c.showId = "show";
        c.folderId = "folder";
        c.folderMax = -1;
        c.priority = 100;
        c.waitingFrameCount = 100;
        c.rssProven = true;
        c.slotsRequired = slots;
        // A regular job cap the slot layer must ignore: the job is already at max cores.
        c.jobCoresInUse = 1000;
        c.jobMaxCores = 1000;
        c.showBurstCores = Integer.MAX_VALUE;
        return c;
    }

    private static Maestro.LayerCandidate regularLayer(String id) {
        Maestro.LayerCandidate c = slotLayer(id, 0);
        c.layerCoresMin = 100;
        c.layerMemMin = 1024;
        c.jobCoresInUse = 0;
        c.threadable = true;
        return c;
    }

    private static Maestro.TickCandidates tick(List<Maestro.LayerCandidate> layers, long cores,
            long burst, int maxSlots, int slotsInUse) {
        List<Maestro.CandidateRow> rows = new ArrayList<>();
        for (Maestro.LayerCandidate c : layers)
            rows.add(new Maestro.CandidateRow(c, "facility", null, "general"));
        Map<String, long[]> subs = new HashMap<>();
        subs.put(Maestro.subKey("show", "alloc"),
                new long[] {cores, burst, 0, maxSlots, slotsInUse});
        return new Maestro.TickCandidates(rows, subs);
    }

    private static Set<String> ids(List<Maestro.LayerCandidate> cs) {
        Set<String> out = new HashSet<>();
        for (Maestro.LayerCandidate c : cs)
            out.add(c.layerId);
        return out;
    }

    // ---- pairing ----------------------------------------------------------

    @Test
    public void aSlotHostGroupsApartFromAnOtherwiseIdenticalHost() {
        assertNotEquals(Maestro.hostSpecKey(slotHost("a", 4, 4)),
                Maestro.hostSpecKey(regularHost("b")));
        assertEquals(Maestro.hostSpecKey(slotHost("a", 4, 4)),
                Maestro.hostSpecKey(slotHost("c", 8, 0)));
    }

    @Test
    public void slotGroupsAdmitOnlySlotLayersAndRegularGroupsOnlyRegularOnes() {
        Maestro.TickCandidates tick =
                tick(Arrays.asList(slotLayer("slot", 1), regularLayer("regular")), 0, 10000, -1, 0);
        List<Maestro.LayerCandidate> slotGroup = Maestro.groupCandidates(
                Maestro.hostSpecKey(slotHost("a", 4, 4)), 800, tick, 10, ANY, new Random(1));
        List<Maestro.LayerCandidate> regularGroup = Maestro.groupCandidates(
                Maestro.hostSpecKey(regularHost("b")), 800, tick, 10, ANY, new Random(1));
        assertEquals(Set.of("slot"), ids(slotGroup));
        assertEquals(Set.of("regular"), ids(regularGroup));
    }

    @Test
    public void fitNeverCrossesThePairing() {
        assertFalse(Maestro.fitsOnHost(regularLayer("r"), slotHost("a", 4, 4)));
        assertFalse(Maestro.fitsOnHost(slotLayer("s", 1), regularHost("b")));
    }

    // ---- admission: max_slots, not the core burst -------------------------

    @Test
    public void aSlotLayerIsAdmittedWhileItsShowIsOverItsCoreBurst() {
        Maestro.TickCandidates tick = tick(Arrays.asList(slotLayer("slot", 1)), 500, 100, -1, 0);
        assertEquals(1, Maestro.groupCandidates(Maestro.hostSpecKey(slotHost("a", 4, 4)), 800, tick,
                10, ANY, new Random(1)).size());
    }

    @Test
    public void aSubscriptionWithZeroMaxSlotsRejectsSlotWork() {
        Maestro.TickCandidates tick = tick(Arrays.asList(slotLayer("slot", 1)), 0, 10000, 0, 0);
        assertTrue(Maestro.groupCandidates(Maestro.hostSpecKey(slotHost("a", 4, 4)), 800, tick, 10,
                ANY, new Random(1)).isEmpty());
    }

    @Test
    public void theSubscriptionSlotFiguresRideOnTheGroupCopy() {
        Maestro.TickCandidates tick = tick(Arrays.asList(slotLayer("slot", 1)), 0, 10000, 12, 5);
        Maestro.LayerCandidate c = Maestro.groupCandidates(Maestro.hostSpecKey(slotHost("a", 4, 4)),
                800, tick, 10, ANY, new Random(1)).get(0);
        assertEquals(12, c.showMaxSlots);
        assertEquals(5, c.showSlotsInUse);
        assertEquals(1, c.slotsRequired);
    }

    // ---- fit, score, headroom ---------------------------------------------

    @Test
    public void aSlotFrameFitsOnFreeSlotsAloneIgnoringCoresAndMemory() {
        Maestro.BookableHost h = slotHost("a", 4, 2);
        h.coresIdle = 0;
        h.memIdle = 0;
        assertTrue(Maestro.fitsOnHost(slotLayer("s", 2), h));
        assertFalse(Maestro.fitsOnHost(slotLayer("s", 3), h));
    }

    @Test
    public void theScorePrefersTheLessLoadedSlotHost() {
        Maestro.LayerCandidate c = slotLayer("s", 1);
        assertTrue(Maestro.placementScore(slotHost("empty", 8, 8), c) < Maestro
                .placementScore(slotHost("busy", 8, 2), c));
    }

    @Test
    public void headroomOnAHostIsBoundedByItsFreeSlots() {
        // Five free slots, two per frame: this frame and one more.
        assertEquals(1, Maestro.computeMaxMore(slotHost("a", 8, 5), slotLayer("s", 2)));
    }

    @Test
    public void slotRoomTreatsMinusOneAsUnlimitedAndZeroAsRejectAll() {
        assertEquals(Integer.MAX_VALUE, Maestro.slotRoom(-1, 100, 1));
        assertEquals(0, Maestro.slotRoom(0, 0, 1));
        assertEquals(2, Maestro.slotRoom(10, 5, 2));
        assertEquals(0, Maestro.slotRoom(4, 6, 1));
    }

    @Test
    public void aFullSlotFarmIsCapacityNotAShapeMismatch() {
        assertEquals("slots", Maestro.classifyFragmentation(slotLayer("s", 1),
                Arrays.asList(slotHost("a", 4, 0), slotHost("b", 4, 0))));
        assertEquals("fit", Maestro.classifyFragmentation(slotLayer("s", 1),
                Arrays.asList(slotHost("a", 4, 0), slotHost("b", 4, 1))));
    }

    // ---- placement end to end, in memory ----------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T field(Maestro s, String name) throws Exception {
        Field f = Maestro.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(s);
    }

    /** One group's placement loop; returns the frames planned. */
    private static int place(Maestro s, List<Maestro.BookableHost> hosts,
            List<Maestro.LayerCandidate> candidates) throws Exception {
        Method m = null;
        for (Method x : Maestro.class.getDeclaredMethods())
            if (x.getName().equals("dispatchGroupWithScoring"))
                m = x;
        m.setAccessible(true);
        Field stats = Maestro.class.getDeclaredField("lastTickStats");
        stats.setAccessible(true);
        if (stats.get(s) == null)
            stats.set(s, new MaestroMetrics.TickStats());
        return (Integer) m.invoke(s, hosts, hosts, candidates, new HashSet<String>(), "alloc",
                new HashMap<String, Integer>(), new HashMap<String, Integer>(),
                new HashMap<String, Integer>(), new ArrayList<>(), new HashMap<String, Integer>(),
                new HashMap<String, Set<String>>(), new HashMap<>(), new HashMap<String, Integer>(),
                new HashMap<String, Set<String>>());
    }

    @Test
    public void theJobMaxSlotsCapsATicksPlacementsAcrossHosts() throws Exception {
        Maestro s = new Maestro();
        Maestro.BookableHost a = slotHost("a", 4, 4);
        Maestro.BookableHost b = slotHost("b", 4, 4);
        Maestro.LayerCandidate c = slotLayer("s", 1);
        c.jobMaxSlots = 5;
        c.jobSlotsInUse = 2;
        assertEquals(3, place(s, Arrays.asList(a, b), new ArrayList<>(Arrays.asList(c))));
        assertEquals(5, a.slotsIdle + b.slotsIdle);
        Map<String, Integer> jobSlotsUsed = field(s, "jobSlotsUsed");
        assertEquals(Integer.valueOf(5), jobSlotsUsed.get(c.jobId));
    }

    @Test
    public void aSlotLayerFillsEverySlotHostToItsCap() throws Exception {
        Maestro s = new Maestro();
        Maestro.BookableHost a = slotHost("a", 4, 4);
        Maestro.BookableHost b = slotHost("b", 2, 1);
        Maestro.LayerCandidate c = slotLayer("s", 1);
        assertEquals(5, place(s, Arrays.asList(a, b), new ArrayList<>(Arrays.asList(c))));
        assertEquals(0, a.slotsIdle);
        assertEquals(0, b.slotsIdle);
    }

    @Test
    public void theFolderAndSubscriptionSlotCapsBindToo() throws Exception {
        Maestro.LayerCandidate folderCapped = slotLayer("f", 2);
        folderCapped.folderMaxSlots = 4;
        assertEquals(2, place(new Maestro(), Arrays.asList(slotHost("a", 16, 16)),
                new ArrayList<>(Arrays.asList(folderCapped))));

        Maestro.LayerCandidate subCapped = slotLayer("u", 1);
        subCapped.showMaxSlots = 3;
        subCapped.showSlotsInUse = 3;
        assertEquals(0, place(new Maestro(), Arrays.asList(slotHost("a", 16, 16)),
                new ArrayList<>(Arrays.asList(subCapped))));
    }
}
