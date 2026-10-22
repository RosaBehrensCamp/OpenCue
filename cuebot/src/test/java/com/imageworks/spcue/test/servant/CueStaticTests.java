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

package com.imageworks.spcue.test.servant;

import java.util.ArrayList;
import java.util.List;

import io.grpc.stub.StreamObserver;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.imageworks.spcue.dispatcher.BookingQueue;
import com.imageworks.spcue.dispatcher.DispatchQueue;
import com.imageworks.spcue.dispatcher.HostReportQueue;
import com.imageworks.spcue.grpc.cue.CueGetSystemStatsRequest;
import com.imageworks.spcue.grpc.cue.CueGetSystemStatsResponse;
import com.imageworks.spcue.grpc.cue.SystemStats;
import com.imageworks.spcue.servant.CueStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/** Unit tests for {@link CueStatic#getSystemStats}. */
public class CueStaticTests {

    private CueStatic cueStatic;
    private HostReportQueue reportQueue;

    private static class RecordingStreamObserver<T> implements StreamObserver<T> {
        final List<T> values = new ArrayList<>();
        boolean completed = false;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            throw new AssertionError(t);
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }

    @Before
    public void setUp() {
        reportQueue = new HostReportQueue(1, 1, 1);
        cueStatic = new CueStatic();
        cueStatic.setDispatchQueue(mock(DispatchQueue.class));
        cueStatic.setManageQueue(mock(DispatchQueue.class));
        cueStatic.setBookingQueue(mock(BookingQueue.class));
        cueStatic.setReportQueue(reportQueue);
    }

    @After
    public void tearDown() {
        reportQueue.shutdownNow();
    }

    private SystemStats getSystemStats(MockEnvironment env) {
        ReflectionTestUtils.setField(cueStatic, "env", env);
        RecordingStreamObserver<CueGetSystemStatsResponse> observer =
                new RecordingStreamObserver<>();
        cueStatic.getSystemStats(CueGetSystemStatsRequest.newBuilder().build(), observer);
        assertTrue(observer.completed);
        assertEquals(1, observer.values.size());
        return observer.values.get(0).getStats();
    }

    @Test
    public void testFinishedJobsReadonlyEnabled() {
        SystemStats stats = getSystemStats(
                new MockEnvironment().withProperty("layer.finished_jobs_readonly", "true")
                        .withProperty("frame.finished_jobs_readonly", "true"));
        assertTrue(stats.getLayerFinishedJobsReadonly());
        assertTrue(stats.getFrameFinishedJobsReadonly());
    }

    @Test
    public void testFinishedJobsReadonlyDisabled() {
        SystemStats stats = getSystemStats(
                new MockEnvironment().withProperty("layer.finished_jobs_readonly", "false")
                        .withProperty("frame.finished_jobs_readonly", "false"));
        assertFalse(stats.getLayerFinishedJobsReadonly());
        assertFalse(stats.getFrameFinishedJobsReadonly());
    }

    @Test
    public void testFinishedJobsReadonlyIndependent() {
        SystemStats stats = getSystemStats(
                new MockEnvironment().withProperty("layer.finished_jobs_readonly", "true")
                        .withProperty("frame.finished_jobs_readonly", "false"));
        assertTrue(stats.getLayerFinishedJobsReadonly());
        assertFalse(stats.getFrameFinishedJobsReadonly());
    }

    @Test
    public void testFinishedJobsReadonlyDefaultsToFalse() {
        SystemStats stats = getSystemStats(new MockEnvironment());
        assertFalse(stats.getLayerFinishedJobsReadonly());
        assertFalse(stats.getFrameFinishedJobsReadonly());
    }
}
