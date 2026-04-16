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

import org.awaitility.Awaitility;
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
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.wso2.micro.integrator.TestUtils.CLUSTER_DEP_TIMEOUT;
import static org.wso2.micro.integrator.TestUtils.deployArtifacts;
import static org.wso2.micro.integrator.TestUtils.deploymentLog;
import static org.wso2.micro.integrator.TestUtils.getNode;

/**
 * Integration test for issue #4844: CApp un-deployment of multiple scheduled tasks must not
 * sleep {@code heartbeatMaxRetryInterval} for each individual task.
 *
 * <p>Test strategy:
 * <ol>
 *   <li>Start a 2-node cluster with a reduced heartbeat interval so that
 *       {@code hotDeploymentDelay = heartBeatInterval × heartbeatMaxRetry = 4000 ms}.</li>
 *   <li>Deploy 5 coordinated tasks to the shared deployment directory.</li>
 *   <li>Wait until all 5 tasks are scheduled on the coordinator node.</li>
 *   <li>Delete all 5 task deployment files simultaneously.</li>
 *   <li>Assert that all 5 undeployment log entries appear within
 *       {@code 2 × hotDeploymentDelay + BUFFER = 13 000 ms}, proving that the coordinator
 *       slept at most once rather than once per task.</li>
 * </ol>
 *
 * <p>Without the fix the coordinator sleeps 5 × 4000 ms = 20 000 ms, which exceeds the
 * 13 000 ms assertion window and causes the test to fail.
 */
public class TaskCAppUndeploymentTests extends ESBIntegrationTest {

    /**
     * heartBeatInterval (ms) passed to both nodes via system property.
     * hotDeploymentDelay = HEARTBEAT_INTERVAL × HEARTBEAT_MAX_RETRY = 2000 × 2 = 4000 ms.
     */
    private static final int HEARTBEAT_INTERVAL_MS = 2000;
    private static final int HEARTBEAT_MAX_RETRY = 2;
    private static final int HOT_DEPLOY_DELAY_MS = HEARTBEAT_INTERVAL_MS * HEARTBEAT_MAX_RETRY; // 4000 ms

    /**
     * Maximum time allowed for all 5 tasks to be fully undeployed after the files are deleted.
     * Must be less than N × hotDeploymentDelay (= 5 × 4000 = 20 000 ms) to detect the bug,
     * but generous enough to absorb overhead on slow CI machines.
     */
    private static final int MAX_UNDEPLOY_DURATION_MS = 2 * HOT_DEPLOY_DELAY_MS + 5000; // 13 000 ms

    private static final int N_TASKS = 5;
    private static final String TASK_PREFIX = "capp-task-";
    private static final String COORDINATOR_LOG = "Current node state changed from: MEMBER to: COORDINATOR";
    private static final String UNDEPLOYED_LOG_PATTERN = "StartupTask named '";
    private static final String UNDEPLOYED_LOG_SUFFIX = "' has been undeployed";

    private MultipleServersManager serverManager;
    private CarbonTestServerManager node1;
    private CarbonTestServerManager node2;
    private CarbonLogReader reader1;
    private CarbonLogReader reader2;
    private LogReaderManager logManager;

    @BeforeClass
    void initialize() throws Exception {
        context = new AutomationContext();
        serverManager = new MultipleServersManager();
        logManager = new LogReaderManager();

        HashMap<String, String> params1 = new HashMap<>();
        params1.put("-DnodeId", "capp-undeploy-node-1");
        params1.put("-DheartBeatInterval", String.valueOf(HEARTBEAT_INTERVAL_MS));
        params1.put("-DheartbeatMaxRetry", String.valueOf(HEARTBEAT_MAX_RETRY));
        node1 = getNode(50, params1);

        HashMap<String, String> params2 = new HashMap<>();
        params2.put("-DnodeId", "capp-undeploy-node-2");
        params2.put("-DheartBeatInterval", String.valueOf(HEARTBEAT_INTERVAL_MS));
        params2.put("-DheartbeatMaxRetry", String.valueOf(HEARTBEAT_MAX_RETRY));
        node2 = getNode(60, params2);

        serverManager.startServersWithDepSync(true, node1, node2);
        reader1 = new CarbonLogReader(node1.getCarbonHome());
        reader2 = new CarbonLogReader(node2.getCarbonHome());
        logManager.start(reader1, reader2);

        // Deploy all 5 tasks to the shared deployment directory
        deployArtifacts(serverManager.getDeploymentDirectory(), Utils.ArtifactType.TASK,
                "capp-task-1", "capp-task-2", "capp-task-3", "capp-task-4", "capp-task-5");
    }

    /**
     * Regression test for issue #4844.
     *
     * Verifies that undeploying 5 coordinated tasks from the same "CApp" (simulated here as
     * simultaneously-deleted task artifacts) does not incur a per-task settle sleep on the
     * coordinator.  The total undeployment time must be less than
     * {@code 2 × hotDeploymentDelay + BUFFER}.
     */
    @Test
    void testBatchUndeploymentDoesNotSleepPerTask() throws Exception {
        // Identify the coordinator by checking which node logged the coordinator transition
        CarbonLogReader coordinatorReader;
        boolean node1IsCoordinator = reader1.checkForLog(COORDINATOR_LOG, CLUSTER_DEP_TIMEOUT);
        coordinatorReader = node1IsCoordinator ? reader1 : reader2;

        // Wait until all 5 tasks are scheduled on the coordinator
        for (int i = 1; i <= N_TASKS; i++) {
            String taskName = TASK_PREFIX + i;
            if (!coordinatorReader.checkForLog(deploymentLog(taskName), CLUSTER_DEP_TIMEOUT)) {
                Assert.fail("Task '" + taskName + "' was not scheduled on the coordinator within "
                        + CLUSTER_DEP_TIMEOUT + " seconds.");
            }
        }

        // Clear accumulated logs so we can measure purely from this point forward
        reader1.clearLogs();
        reader2.clearLogs();

        // Delete all 5 task files from the shared deployment directory to trigger hot undeployment
        String tasksDeployDir = serverManager.getDeploymentDirectory() + File.separator + "server"
                + File.separator + "synapse-configs" + File.separator + "default"
                + File.separator + "tasks";
        long deleteStart = System.currentTimeMillis();
        for (int i = 1; i <= N_TASKS; i++) {
            File taskFile = new File(tasksDeployDir, TASK_PREFIX + i + ".xml");
            if (!taskFile.delete()) {
                log.warn("Could not delete task file: " + taskFile.getAbsolutePath());
            }
        }

        // Wait for all 5 undeployment messages to appear, bounded by MAX_UNDEPLOY_DURATION_MS.
        // If the bug is present (per-task sleep), all 5 undeployments take ~20 000 ms which
        // exceeds the window and Awaitility throws a ConditionTimeoutException → test fails.
        try {
            Awaitility.await()
                    .atMost(MAX_UNDEPLOY_DURATION_MS, MILLISECONDS)
                    .pollInterval(500, MILLISECONDS)
                    .until(() -> allTasksUndeployed(coordinatorReader));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - deleteStart;
            Assert.fail("All " + N_TASKS + " tasks were not undeployed within "
                    + MAX_UNDEPLOY_DURATION_MS + " ms (elapsed=" + elapsed + " ms)."
                    + " This indicates the per-task sleep regression (issue #4844) is present."
                    + " Expected O(hotDeploymentDelay)=" + HOT_DEPLOY_DELAY_MS + " ms total,"
                    + " but with the bug it would be O(N × hotDeploymentDelay)="
                    + (N_TASKS * HOT_DEPLOY_DELAY_MS) + " ms.");
        }

        long elapsed = System.currentTimeMillis() - deleteStart;
        log.info("All " + N_TASKS + " tasks undeployed in " + elapsed + " ms"
                + " (hotDeploymentDelay=" + HOT_DEPLOY_DELAY_MS + " ms,"
                + " N×hotDeploymentDelay=" + (N_TASKS * HOT_DEPLOY_DELAY_MS) + " ms).");

        // Soft assertion: elapsed should be well below N × hotDeploymentDelay
        Assert.assertTrue(
                "Undeployment took " + elapsed + " ms but should be < N×hotDeploymentDelay="
                        + (N_TASKS * HOT_DEPLOY_DELAY_MS) + " ms. Bug (issue #4844) may still be present.",
                elapsed < (long) N_TASKS * HOT_DEPLOY_DELAY_MS);
    }

    private boolean allTasksUndeployed(CarbonLogReader reader) throws Exception {
        for (int i = 1; i <= N_TASKS; i++) {
            String pattern = UNDEPLOYED_LOG_PATTERN + TASK_PREFIX + i + UNDEPLOYED_LOG_SUFFIX;
            if (reader.getNumberOfOccurencesForLog(pattern) < 1) {
                return false;
            }
        }
        return true;
    }

    @AfterClass
    void stop() throws Exception {
        logManager.stopAll();
        serverManager.stopAllServers();
    }
}
