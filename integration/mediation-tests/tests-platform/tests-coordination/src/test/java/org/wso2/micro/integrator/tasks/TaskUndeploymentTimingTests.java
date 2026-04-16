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

package org.wso2.micro.integrator.tasks;

import org.junit.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.automation.engine.context.AutomationContext;
import org.wso2.esb.integration.common.extensions.carbonserver.CarbonTestServerManager;
import org.wso2.esb.integration.common.extensions.carbonserver.MultipleServersManager;
import org.wso2.esb.integration.common.utils.CarbonLogReader;
import org.wso2.esb.integration.common.utils.ESBIntegrationTest;
import org.wso2.esb.integration.common.utils.LogReaderManager;
import org.wso2.esb.integration.common.utils.Utils;

import java.io.File;
import java.util.HashMap;

import static org.wso2.micro.integrator.TestUtils.CLUSTER_DEP_TIMEOUT;
import static org.wso2.micro.integrator.TestUtils.deployArtifacts;
import static org.wso2.micro.integrator.TestUtils.getNode;

/**
 * Regression test for Issue #4879: CApp undeploy with multiple coordinated tasks must not
 * block for N * heartbeatMaxRetryInterval milliseconds.
 *
 * The fix batches the hot-deployment sleep so it fires only once per undeploy wave
 * regardless of the number of tasks. This test verifies that the "Waiting for X ms"
 * log entry appears exactly once when 5 tasks are undeployed together.
 *
 * Requires the cluster-tests Maven profile (-P cluster-tests) and a running coordination DB.
 * The heartbeat is set to 2 s (heartBeatInterval=2000, heartbeatMaxRetry=1) to keep the
 * test fast while still providing a clear signal.
 */
public class TaskUndeploymentTimingTests extends ESBIntegrationTest {

    private static final String[] TASKS = {"task-1", "task-2", "task-3", "task-4", "task-5"};
    private static final String COORDINATOR_LOG = "Current node state changed from: MEMBER to: COORDINATOR";
    private static final String HOT_DEPLOY_SLEEP_LOG = "ms for hot deployment to settle.";
    private static final String TASK_DB_DELETE_LOG = "from the data base since this is a coordinated task.";

    // heartBeatInterval=2000ms, heartbeatMaxRetry=1 → effective delay = 2000 ms per wave.
    private static final int HEARTBEAT_INTERVAL_MS = 2000;
    private static final int SERVER_STARTUP_TIMEOUT = 180;

    // Upper bound: 2 × heartbeat interval gives enough room for one sleep + overhead.
    // Without the fix, 5 tasks × 2 s = 10 s, which would exceed this bound.
    private static final int MAX_ACCEPTABLE_UNDEPLOY_SECONDS =
            (2 * HEARTBEAT_INTERVAL_MS / 1000) + 5;

    private MultipleServersManager serverManager;
    private CarbonTestServerManager node;
    private CarbonLogReader logReader;
    private LogReaderManager logManager;
    private String carbonHome;

    @BeforeClass
    void initialize() throws Exception {
        context = new AutomationContext();
        serverManager = new MultipleServersManager();
        logManager = new LogReaderManager();

        HashMap<String, String> startupParams = new HashMap<>();
        startupParams.put("-DnodeId", "timing-test-node");
        // Short heartbeat so the test completes quickly.
        startupParams.put("-DheartBeatInterval", String.valueOf(HEARTBEAT_INTERVAL_MS));
        startupParams.put("-DheartbeatMaxRetry", "1");

        node = getNode(30, startupParams);
        serverManager.startServers(node);
        carbonHome = node.getCarbonHome();

        logReader = new CarbonLogReader(carbonHome);
        logManager.start(logReader);

        // Wait until this node becomes coordinator.
        boolean becameCoordinator = logReader.checkForLog(COORDINATOR_LOG, SERVER_STARTUP_TIMEOUT);
        if (!becameCoordinator) {
            Assert.fail("Node did not become coordinator within " + SERVER_STARTUP_TIMEOUT + " s");
        }

        // Deploy all 5 tasks and wait for them to be registered in the coordination DB.
        String depDir = String.join(File.separator, carbonHome, "repository", "deployment");
        deployArtifacts(depDir, Utils.ArtifactType.TASK, TASKS);
        for (String task : TASKS) {
            if (!logReader.checkForLog("Task scheduled: [ESB_TASK][" + task + "]", CLUSTER_DEP_TIMEOUT)) {
                Assert.fail("Task " + task + " was not deployed within " + CLUSTER_DEP_TIMEOUT + " s");
            }
        }
    }

    /**
     * Deletes all 5 task files and asserts that:
     * 1. The hot-deployment sleep log appears exactly once (not once per task).
     * 2. All 5 DB deletion logs appear within MAX_ACCEPTABLE_UNDEPLOY_SECONDS.
     *
     * Without the fix, observation from Issue #4879:
     *   5 tasks × 2 000 ms = 10 000 ms — well above MAX_ACCEPTABLE_UNDEPLOY_SECONDS.
     * With the fix:
     *   1 sleep × 2 000 ms ≈ 2 000 ms + small overhead — well within the bound.
     */
    @Test
    void testMultipleCoordinatedTasksUndeployedWithSingleSleep() throws Exception {
        logReader.clearLogs();

        // Remove all task XML files to trigger hot-undeploy.
        for (String task : TASKS) {
            File taskFile = new File(String.join(File.separator, carbonHome, "repository", "deployment",
                    "server", "synapse-configs", "default", "tasks", task + ".xml"));
            if (!taskFile.delete()) {
                Assert.fail("Could not delete task file: " + taskFile.getAbsolutePath());
            }
        }

        long undeployStart = System.currentTimeMillis();

        // Wait until the last task's DB deletion log appears.
        String lastTaskDeleteLog = "Deleting task " + TASKS[TASKS.length - 1] + " " + TASK_DB_DELETE_LOG;
        boolean allDeleted = logReader.checkForLog(lastTaskDeleteLog, MAX_ACCEPTABLE_UNDEPLOY_SECONDS);
        long elapsed = System.currentTimeMillis() - undeployStart;

        Assert.assertTrue("All 5 tasks were not deleted from DB within " + MAX_ACCEPTABLE_UNDEPLOY_SECONDS
                + " s. Elapsed: " + elapsed + " ms", allDeleted);

        // The fix: sleep must fire exactly once, not once per task.
        int sleepCount = logReader.getNumberOfOccurencesForLog(HOT_DEPLOY_SLEEP_LOG);
        Assert.assertEquals(
                "Hot-deployment sleep should occur exactly once for N tasks, but occurred " + sleepCount + " times",
                1, sleepCount);
    }

    @AfterClass
    void stop() throws Exception {
        logManager.stopAll();
        serverManager.stopAllServers();
    }
}
