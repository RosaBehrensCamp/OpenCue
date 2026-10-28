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

import java.io.File;
import java.util.List;
import javax.annotation.Resource;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractTransactionalJUnit4SpringContextTests;
import org.springframework.test.context.support.AnnotationConfigContextLoader;
import org.springframework.transaction.annotation.Transactional;

import com.imageworks.spcue.DispatchFrame;
import com.imageworks.spcue.DispatchHost;
import com.imageworks.spcue.JobDetail;
import com.imageworks.spcue.LayerInterface;
import com.imageworks.spcue.config.TestAppConfig;
import com.imageworks.spcue.dao.DispatcherDao;
import com.imageworks.spcue.dao.HostDao;
import com.imageworks.spcue.dao.MaestroDao;
import com.imageworks.spcue.grpc.host.HardwareState;
import com.imageworks.spcue.grpc.report.RenderHost;
import com.imageworks.spcue.grpc.rqd.RunFrame;
import com.imageworks.spcue.service.AdminManager;
import com.imageworks.spcue.service.HostManager;
import com.imageworks.spcue.service.JobLauncher;
import com.imageworks.spcue.service.JobManager;
import com.imageworks.spcue.test.AssumingPostgresEngine;
import com.imageworks.spcue.util.CueUtil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Slot booking against the database: Maestro's reads see the slot host and slot layer, the commit
 * writes a 0-core proc that holds the frame's slots (accepted by the proc insert trigger), RQD is
 * told the slots, and the legacy dispatcher never sees the slot layer or the slot host.
 */
@Transactional
@ContextConfiguration(classes = TestAppConfig.class, loader = AnnotationConfigContextLoader.class)
public class MaestroSlotDbTests extends AbstractTransactionalJUnit4SpringContextTests {

    @Autowired
    @Rule
    public AssumingPostgresEngine assumingPostgresEngine;

    @Resource
    MaestroDao maestroDao;
    @Resource
    DispatcherDao dispatcherDao;
    @Resource
    DispatchSupport dispatchSupport;
    @Resource
    Dispatcher dispatcher;
    @Resource
    HostDao hostDao;
    @Resource
    HostManager hostManager;
    @Resource
    AdminManager adminManager;
    @Resource
    JobManager jobManager;
    @Resource
    JobLauncher jobLauncher;

    private static final String HOSTNAME = "maestro_slot_host";

    @Before
    public void launchJobAndCreateHost() {
        dispatcher.setTestMode(true);
        jobLauncher.testMode = true;
        jobLauncher.launch(new File("src/test/resources/conf/jobspec/jobspec_dispatch_test.xml"));
        RenderHost host = RenderHost.newBuilder().setName(HOSTNAME).setBootTime(1192369572)
                .setFreeMcp(CueUtil.GB).setFreeMem(53500).setFreeSwap(20760).setLoad(1)
                .setTotalMcp(CueUtil.GB4).setTotalMem(8173264).setTotalSwap(20960)
                .setNimbyEnabled(false).setNumProcs(2).setCoresPerProc(100).addTags("test")
                .setState(HardwareState.UP).setFacility("spi").putAttributes("SP_OS", "Linux")
                .build();
        hostManager.createHost(host, adminManager.findAllocationDetail("spi", "general"));
    }

    private Maestro.BookableHost snapshot(String hostId) {
        for (Maestro.BookableHost host : maestroDao.getSchedulableHosts())
            if (host.hostId.equals(hostId))
                return host;
        throw new AssertionError("host " + hostId + " not in the snapshot");
    }

    @Test
    @Rollback(true)
    public void aSlotFrameBooksZeroCoresAndItsSlotsOnASlotHost() {
        JobDetail job = jobManager.findJobDetail("pipe-dev.cue-testuser_shell_dispatch_test_v1");
        LayerInterface layer = jobManager.getLayers(job).get(0);
        jdbcTemplate.update("UPDATE layer SET int_slots_required = 1 WHERE pk_layer = ?",
                layer.getLayerId());
        jdbcTemplate.update("UPDATE host SET int_concurrent_slots_limit = 4 WHERE str_name = ?",
                HOSTNAME);
        DispatchHost host = hostDao.findDispatchHost(HOSTNAME);
        assertEquals(4, host.concurrentSlotsLimit);

        // Legacy never books the slot layer.
        for (DispatchFrame frame : dispatcherDao.findNextDispatchFrames(job, host, 100))
            assertNotEquals(layer.getLayerId(), frame.getLayerId());

        // Maestro's reads: the slot host with every slot free, the slot layer with no
        // core-side demand.
        Maestro.BookableHost before = snapshot(host.id);
        assertEquals(4, before.slotsLimit);
        assertEquals(4, before.slotsIdle);
        Maestro.LayerCandidate candidate = null;
        for (Maestro.CandidateRow row : maestroDao.getCandidateRows(true))
            if (row.row.layerId.equals(layer.getLayerId()))
                candidate = row.row;
        assertEquals(1, candidate.slotsRequired);
        assertEquals(0, candidate.layerCoresMin);
        assertEquals(0, candidate.layerMemMin);
        assertEquals(-1, candidate.jobMaxSlots);

        // Plan and commit two frames.
        List<DispatchFrame> frames = dispatchSupport.findNextDispatchFrames(layer.getLayerId(), 2);
        assertEquals(2, frames.size());
        assertEquals(1, frames.get(0).slotsRequired);
        assertFalse(frames.get(0).threadable);
        List<FrameBooking> plan = dispatcher.planHost(host, 0, 0, frames);
        assertEquals(2, plan.size());
        List<FrameBooking> won = dispatchSupport.startFramesAndProcsBatch(plan);
        assertEquals(2, won.size());

        assertEquals(Integer.valueOf(2),
                jdbcTemplate.queryForObject(
                        "SELECT SUM(int_slots_reserved)::int FROM proc WHERE pk_host = ?",
                        Integer.class, host.id));
        assertEquals(Integer.valueOf(0),
                jdbcTemplate.queryForObject(
                        "SELECT SUM(int_cores_reserved)::int FROM proc WHERE pk_host = ?",
                        Integer.class, host.id));
        assertEquals(2, snapshot(host.id).slotsIdle);

        // RQD is told the frame's slots and no cores.
        FrameBooking booking = won.get(0);
        RunFrame runFrame = dispatchSupport.prepareRqdRunFrame(booking.proc, booking.frame);
        assertEquals(1, runFrame.getSlotsRequired());
        assertEquals(0, runFrame.getNumCores());
    }

    /** The slot layer's next waiting frames, planned onto the test host without a slot check. */
    private List<FrameBooking> planSlotFrames(LayerInterface layer, int count) {
        return dispatcher.planHost(hostDao.findDispatchHost(HOSTNAME), 0, 0,
                dispatchSupport.findNextDispatchFrames(layer.getLayerId(), count));
    }

    private int slotsHeld(String hostId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(int_slots_reserved), 0)::int FROM proc WHERE pk_host = ?",
                Integer.class, hostId);
    }

    @Test
    @Rollback(true)
    public void theCommitKeepsSlotBookingsPastTheHostCapWaiting() {
        JobDetail job = jobManager.findJobDetail("pipe-dev.cue-testuser_shell_dispatch_test_v1");
        LayerInterface layer = jobManager.getLayers(job).get(0);
        jdbcTemplate.update("UPDATE layer SET int_slots_required = 1 WHERE pk_layer = ?",
                layer.getLayerId());
        jdbcTemplate.update("UPDATE host SET int_concurrent_slots_limit = 3 WHERE str_name = ?",
                HOSTNAME);
        DispatchHost host = hostDao.findDispatchHost(HOSTNAME);
        int idleCores = host.idleCores;

        assertEquals(2, dispatchSupport.startFramesAndProcsBatch(planSlotFrames(layer, 2)).size());
        assertEquals(2, slotsHeld(host.id));

        // A plan of two more against a cap with one free slot left (a stale snapshot, or a
        // cap lowered after planning): the commit lands one and keeps the other WAITING.
        List<FrameBooking> plan = planSlotFrames(layer, 2);
        List<FrameBooking> won = dispatchSupport.startFramesAndProcsBatch(plan);
        assertEquals(1, won.size());
        assertEquals(3, slotsHeld(host.id));
        for (FrameBooking booking : plan) {
            if (!won.contains(booking))
                assertEquals("WAITING",
                        jdbcTemplate.queryForObject(
                                "SELECT str_state FROM frame WHERE pk_frame = ?", String.class,
                                booking.frame.getFrameId()));
        }
        assertEquals(idleCores, hostDao.findDispatchHost(HOSTNAME).idleCores);

        // At the cap, nothing more lands.
        assertTrue(dispatchSupport.startFramesAndProcsBatch(planSlotFrames(layer, 1)).isEmpty());
        assertEquals(3, slotsHeld(host.id));
    }

    @Test
    @Rollback(true)
    public void theCommitNeverLandsASlotBookingOnARegularHost() {
        JobDetail job = jobManager.findJobDetail("pipe-dev.cue-testuser_shell_dispatch_test_v1");
        LayerInterface layer = jobManager.getLayers(job).get(0);
        jdbcTemplate.update("UPDATE layer SET int_slots_required = 1 WHERE pk_layer = ?",
                layer.getLayerId());
        DispatchHost host = hostDao.findDispatchHost(HOSTNAME);
        assertEquals(-1, host.concurrentSlotsLimit);

        assertTrue(dispatchSupport.startFramesAndProcsBatch(planSlotFrames(layer, 1)).isEmpty());
        assertEquals(0, slotsHeld(host.id));
    }

    @Test
    @Rollback(true)
    public void aRegularHostSnapshotsWithoutSlots() {
        DispatchHost host = hostDao.findDispatchHost(HOSTNAME);
        assertEquals(-1, host.concurrentSlotsLimit);
        assertTrue(snapshot(host.id).slotsLimit < 0);
    }
}
