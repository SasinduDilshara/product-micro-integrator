/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.micro.integrator.ntask.core.impl.standalone;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.quartz.JobKey;
import org.quartz.ListenerManager;
import org.quartz.Scheduler;
import org.wso2.micro.integrator.coordination.ClusterCoordinator;
import org.wso2.micro.integrator.ntask.coordination.task.store.TaskStore;
import org.wso2.micro.integrator.ntask.core.TaskRepository;
import org.wso2.micro.integrator.ntask.core.internal.DataHolder;
import org.wso2.micro.integrator.ntask.core.internal.TasksDSComponent;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledTaskManager#deleteTask(String)} verifying that the
 * hot-deployment settle sleep is incurred only once per batch of coordinated-task deletions
 * (fix for issue #4844).
 *
 * <p>All tests are in the same package as {@code ScheduledTaskManager} so they can invoke
 * its package-private constructor.  Heavy OSGi dependencies (Quartz scheduler,
 * TasksDSComponent, DataHolder) are satisfied via reflection before each test.
 */
public class ScheduledTaskManagerDeleteTest {

    /**
     * Hot-deployment delay used across tests (ms).  Small enough for fast execution,
     * large enough that a missed sleep is detectable by wall-clock timing.
     */
    private static final int HOT_DEPLOY_DELAY_MS = 300;

    private Scheduler mockScheduler;
    private ClusterCoordinator mockCoordinator;
    private TaskStore mockTaskStore;
    private TaskRepository mockTaskRepository;
    private ScheduledTaskManager manager;

    @Before
    public void setUp() throws Exception {
        mockScheduler = Mockito.mock(Scheduler.class);
        ListenerManager mockListenerManager = Mockito.mock(ListenerManager.class);
        when(mockScheduler.getListenerManager()).thenReturn(mockListenerManager);
        when(mockScheduler.deleteJob(any(JobKey.class))).thenReturn(true);

        // Inject mock scheduler into the static field TasksDSComponent.scheduler
        Field schedulerField = TasksDSComponent.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(null, mockScheduler);

        mockCoordinator = Mockito.mock(ClusterCoordinator.class);
        when(mockCoordinator.isLeader()).thenReturn(true);
        when(mockCoordinator.getHeartbeatMaxRetryInterval()).thenReturn(HOT_DEPLOY_DELAY_MS);
        when(mockCoordinator.getThisNodeId()).thenReturn("unit-test-node");

        // Inject mock coordinator into DataHolder singleton so coordination is enabled
        Field coordField = DataHolder.class.getDeclaredField("clusterCoordinator");
        coordField.setAccessible(true);
        coordField.set(DataHolder.getInstance(), mockCoordinator);

        mockTaskStore = Mockito.mock(TaskStore.class);

        mockTaskRepository = Mockito.mock(TaskRepository.class);
        when(mockTaskRepository.getTenantId()).thenReturn(-1234);
        when(mockTaskRepository.getTasksType()).thenReturn("ESB_TASK");
        when(mockTaskRepository.deleteTask(any(String.class))).thenReturn(true);

        manager = new ScheduledTaskManager(mockTaskRepository, mockTaskStore);
    }

    @After
    public void tearDown() throws Exception {
        // Clear DataHolder coordinator so other tests are not affected
        Field coordField = DataHolder.class.getDeclaredField("clusterCoordinator");
        coordField.setAccessible(true);
        coordField.set(DataHolder.getInstance(), null);

        // Clear static scheduler
        Field schedulerField = TasksDSComponent.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(null, null);
    }

    // -----------------------------------------------------------------------
    // Bug scenario: deleting N coordinated tasks must not sleep N times
    // -----------------------------------------------------------------------

    /**
     * Regression test for issue #4844.
     *
     * Before the fix, each call to {@code deleteTask()} on the coordinator node slept for
     * {@code heartbeatMaxRetryInterval} regardless of whether a sleep had just occurred.
     * With N=5 tasks and a 300 ms delay this produced a 1500 ms total delay.
     *
     * After the fix {@code lastHotDeploymentSettleTime} prevents re-sleeping within the
     * same settle window: the total elapsed time for 5 rapid deletions must be less than
     * {@code 2 × HOT_DEPLOY_DELAY_MS} (i.e. at most one sleep occurred).
     */
    @Test
    public void testBatchDeleteOnlySleepsOnce() throws Exception {
        addToDeployedCoordinatedTasks(manager, "batch-task-1", "batch-task-2",
                "batch-task-3", "batch-task-4", "batch-task-5");

        long start = System.currentTimeMillis();
        for (int i = 1; i <= 5; i++) {
            manager.deleteTask("batch-task-" + i);
        }
        long elapsed = System.currentTimeMillis() - start;

        long maxAllowed = 2L * HOT_DEPLOY_DELAY_MS; // one sleep + generous overhead
        Assert.assertTrue(
                "Expected batch delete to sleep at most once (< " + maxAllowed + " ms) but elapsed=" + elapsed + " ms."
                        + " This indicates the per-task sleep regression (issue #4844) is present.",
                elapsed < maxAllowed);
    }

    // -----------------------------------------------------------------------
    // Edge case: the very first deletion must always sleep
    // -----------------------------------------------------------------------

    /**
     * Verifies that the first coordinated-task deletion on a freshly-created manager
     * (i.e. {@code lastHotDeploymentSettleTime == 0}) does perform the settle sleep.
     */
    @Test
    public void testFirstDeleteAlwaysSleeps() throws Exception {
        addToDeployedCoordinatedTasks(manager, "first-task");

        long start = System.currentTimeMillis();
        manager.deleteTask("first-task");
        long elapsed = System.currentTimeMillis() - start;

        Assert.assertTrue(
                "Expected first delete to sleep for at least " + HOT_DEPLOY_DELAY_MS + " ms but elapsed=" + elapsed + " ms.",
                elapsed >= HOT_DEPLOY_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Edge case: deletion within the same settle window skips the sleep
    // -----------------------------------------------------------------------

    /**
     * If {@code lastHotDeploymentSettleTime} was set very recently (simulating a deletion
     * that just completed the settle sleep), the next deletion must skip the sleep and
     * execute quickly.
     */
    @Test
    public void testSubsequentDeleteWithinWindowSkipsSleep() throws Exception {
        // Simulate: a previous deletion just completed its sleep right now
        Field settleField = ScheduledTaskManager.class.getDeclaredField("lastHotDeploymentSettleTime");
        settleField.setAccessible(true);
        settleField.setLong(manager, System.currentTimeMillis());

        addToDeployedCoordinatedTasks(manager, "quick-task");

        long start = System.currentTimeMillis();
        manager.deleteTask("quick-task");
        long elapsed = System.currentTimeMillis() - start;

        Assert.assertTrue(
                "Expected deletion within the settle window to skip sleep (< " + HOT_DEPLOY_DELAY_MS + " ms)"
                        + " but elapsed=" + elapsed + " ms.",
                elapsed < HOT_DEPLOY_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Negative case: non-leader node must NOT delete from the task store
    // -----------------------------------------------------------------------

    /**
     * On a non-leader (worker) node the coordinator is responsible for cleaning up
     * the task store.  {@code deleteTask()} on a worker must not call
     * {@code taskStore.deleteTasks()} and must not sleep.
     */
    @Test
    public void testNonLeaderSkipsTaskStoreDeletion() throws Exception {
        when(mockCoordinator.isLeader()).thenReturn(false);
        addToDeployedCoordinatedTasks(manager, "worker-task");

        long start = System.currentTimeMillis();
        manager.deleteTask("worker-task");
        long elapsed = System.currentTimeMillis() - start;

        verify(mockTaskStore, never()).deleteTasks(any());
        Assert.assertTrue(
                "Non-leader deleteTask should not sleep, but elapsed=" + elapsed + " ms.",
                elapsed < HOT_DEPLOY_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Negative case: no cluster coordinator means no coordination logic runs
    // -----------------------------------------------------------------------

    /**
     * When clustering is not enabled (no {@code ClusterCoordinator} in {@code DataHolder}),
     * {@code deleteTask()} must return immediately without touching the task store.
     */
    @Test
    public void testNoCoordinatorSkipsCoordinationLogic() throws Exception {
        // Disable coordination by clearing the coordinator from DataHolder
        Field coordField = DataHolder.class.getDeclaredField("clusterCoordinator");
        coordField.setAccessible(true);
        coordField.set(DataHolder.getInstance(), null);

        // Re-create manager without coordination enabled
        ScheduledTaskManager nonClusterManager = new ScheduledTaskManager(mockTaskRepository, mockTaskStore);
        addToDeployedCoordinatedTasks(nonClusterManager, "standalone-task");

        long start = System.currentTimeMillis();
        nonClusterManager.deleteTask("standalone-task");
        long elapsed = System.currentTimeMillis() - start;

        verify(mockTaskStore, never()).deleteTasks(any());
        Assert.assertTrue(
                "Standalone deleteTask should not sleep, but elapsed=" + elapsed + " ms.",
                elapsed < HOT_DEPLOY_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void addToDeployedCoordinatedTasks(ScheduledTaskManager mgr, String... taskNames)
            throws Exception {
        Field f = ScheduledTaskManager.class.getDeclaredField("deployedCoordinatedTasks");
        f.setAccessible(true);
        List<String> list = (List<String>) f.get(mgr);
        Collections.addAll(list, taskNames);
    }
}
