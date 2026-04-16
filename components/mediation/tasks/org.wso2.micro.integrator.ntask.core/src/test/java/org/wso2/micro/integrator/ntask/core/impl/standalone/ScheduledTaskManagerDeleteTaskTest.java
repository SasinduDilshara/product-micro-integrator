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

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.quartz.ListenerManager;
import org.quartz.Scheduler;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.micro.integrator.coordination.ClusterCoordinator;
import org.wso2.micro.integrator.ntask.common.TaskException;
import org.wso2.micro.integrator.ntask.coordination.task.store.TaskStore;
import org.wso2.micro.integrator.ntask.core.TaskRepository;
import org.wso2.micro.integrator.ntask.core.internal.DataHolder;
import org.wso2.micro.integrator.ntask.core.internal.TasksDSComponent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for ScheduledTaskManager.deleteTask() verifying that the hot-deployment
 * sleep fires at most once per batch rather than once per task (Issue #4879).
 */
public class ScheduledTaskManagerDeleteTaskTest {

    // Short delay so tests run quickly while still distinguishing one-sleep vs N-sleep.
    private static final int HEARTBEAT_DELAY_MS = 200;
    private static final int TENANT_ID = -1234;
    private static final String TASK_TYPE = "ESB_TASK";

    @Mock private Scheduler mockScheduler;
    @Mock private ListenerManager mockListenerManager;
    @Mock private TaskRepository mockTaskRepository;
    @Mock private TaskStore mockTaskStore;
    @Mock private ClusterCoordinator mockCoordinator;

    private AutoCloseable mocks;
    private ScheduledTaskManager manager;

    @BeforeMethod
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);

        // Configure mocks needed by the AbstractQuartzTaskManager constructor.
        when(mockScheduler.getListenerManager()).thenReturn(mockListenerManager);
        when(mockTaskRepository.getTenantId()).thenReturn(TENANT_ID);
        when(mockTaskRepository.getTasksType()).thenReturn(TASK_TYPE);
        when(mockTaskRepository.deleteTask(anyString())).thenReturn(true);
        when(mockScheduler.deleteJob(any())).thenReturn(true);

        // Configure mocks needed during deleteTask() execution.
        when(mockCoordinator.getThisNodeId()).thenReturn("test-node-1");
        when(mockCoordinator.isLeader()).thenReturn(true);
        when(mockCoordinator.getHeartbeatMaxRetryInterval()).thenReturn(HEARTBEAT_DELAY_MS);

        // Wire the mock scheduler into TasksDSComponent (bypasses OSGi) and the mock
        // coordinator into the DataHolder singleton (bypasses OSGi activation).
        setStaticField(TasksDSComponent.class, "scheduler", mockScheduler);
        setInstanceField(DataHolder.getInstance(), DataHolder.class, "clusterCoordinator", mockCoordinator);

        manager = new ScheduledTaskManager(mockTaskRepository, mockTaskStore);
    }

    @AfterMethod
    void tearDown() throws Exception {
        // Restore statics to avoid cross-test pollution.
        setStaticField(TasksDSComponent.class, "scheduler", null);
        setInstanceField(DataHolder.getInstance(), DataHolder.class, "clusterCoordinator", null);
        mocks.close();
    }

    /**
     * Regression test for Issue #4879: deleting N coordinated tasks during CApp undeploy
     * must sleep at most once, not N times.
     *
     * Without the fix the total would be N * HEARTBEAT_DELAY_MS; with the fix it is
     * approximately 1 * HEARTBEAT_DELAY_MS.
     */
    @Test
    void testBatchedSleepOccursOnlyOnceWhenDeletingMultipleTasks() throws Exception {
        List<String> tasks = Arrays.asList("task-1", "task-2", "task-3", "task-4", "task-5");
        setDeployedCoordinatedTasks(manager, new ArrayList<>(tasks));

        long start = System.currentTimeMillis();
        for (String task : tasks) {
            manager.deleteTask(task);
        }
        long elapsed = System.currentTimeMillis() - start;

        // With fix: 1 sleep. Generous upper bound = 3 * delay to absorb OS scheduling noise.
        // Without fix: 5 sleeps = 5 * HEARTBEAT_DELAY_MS, which would exceed the bound.
        assertTrue(elapsed < 3L * HEARTBEAT_DELAY_MS,
                   "Expected at most one sleep for 5 tasks, but elapsed " + elapsed
                   + " ms >= " + (3 * HEARTBEAT_DELAY_MS) + " ms (N-sleep threshold)");

        // Every task must still be removed from the DB even though sleep was skipped.
        verify(mockTaskStore, times(5)).deleteTasks(anyList());
    }

    /**
     * Edge case: the sleep guard resets after the heartbeat window has elapsed, so a second
     * wave of deletions (arriving after the window) does get its own sleep.
     */
    @Test
    void testSleepReoccursAfterHeartbeatWindowHasPassed() throws Exception {
        setDeployedCoordinatedTasks(manager, new ArrayList<>(Arrays.asList("task-1", "task-2")));

        // First deletion triggers the sleep; task-2 remains in the deployed list.
        manager.deleteTask("task-1");

        // Advance past the heartbeat window.
        Thread.sleep(HEARTBEAT_DELAY_MS + 100);

        long start = System.currentTimeMillis();
        manager.deleteTask("task-2");
        long elapsed = System.currentTimeMillis() - start;

        // The second deletion should re-trigger the sleep because the window has expired.
        assertTrue(elapsed >= HEARTBEAT_DELAY_MS - 50,
                   "Expected a second sleep after the heartbeat window, but elapsed only " + elapsed + " ms");
        verify(mockTaskStore, times(2)).deleteTasks(anyList());
    }

    /**
     * Negative test: when coordination is not active (null coordinator), deleteTask must
     * return immediately without sleeping or touching the task store.
     */
    @Test
    void testNoSleepAndNoDbDeletionWhenClusterCoordinatorIsNull() throws Exception {
        // Re-create the manager without a coordinator in DataHolder.
        setInstanceField(DataHolder.getInstance(), DataHolder.class, "clusterCoordinator", null);
        manager = new ScheduledTaskManager(mockTaskRepository, mockTaskStore);

        long start = System.currentTimeMillis();
        manager.deleteTask("task-1");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 100,
                   "No sleep expected in standalone mode, but elapsed " + elapsed + " ms");
        verify(mockTaskStore, never()).deleteTasks(anyList());
    }

    /**
     * Negative test: tasks not tracked as coordinated (e.g., pinned tasks or tasks that were
     * never deployed via handleTask) must not trigger the sleep or a DB deletion.
     */
    @Test
    void testNoSleepAndNoDbDeletionForTaskNotInDeployedList() throws Exception {
        setDeployedCoordinatedTasks(manager, new ArrayList<>());

        long start = System.currentTimeMillis();
        manager.deleteTask("unknown-task");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 100,
                   "No sleep expected for non-coordinated task, but elapsed " + elapsed + " ms");
        verify(mockTaskStore, never()).deleteTasks(anyList());
    }

    /**
     * Negative test: a non-leader node must not touch the task store (the coordinator is
     * responsible for DB cleanup).
     */
    @Test
    void testNoDbDeletionWhenNodeIsNotLeader() throws Exception {
        when(mockCoordinator.isLeader()).thenReturn(false);
        setDeployedCoordinatedTasks(manager, new ArrayList<>(Arrays.asList("task-1")));

        manager.deleteTask("task-1");

        verify(mockTaskStore, never()).deleteTasks(anyList());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void setDeployedCoordinatedTasks(ScheduledTaskManager mgr, List<String> tasks) throws Exception {
        Field field = ScheduledTaskManager.class.getDeclaredField("deployedCoordinatedTasks");
        field.setAccessible(true);
        field.set(mgr, tasks);
    }

    private void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private void setInstanceField(Object target, Class<?> declaringClass, String fieldName, Object value)
            throws Exception {
        Field field = declaringClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
