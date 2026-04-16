/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.mediation.initializer.deployment.application.deployer;

import org.apache.axis2.deployment.repository.util.DeploymentFileData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.wso2.micro.integrator.initializer.deployment.application.deployer.CappDeployer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.wso2.micro.integrator.initializer.deployment.synapse.deployer.SynapseAppDeployerConstants.MEDIATOR_TYPE;
import static org.wso2.micro.integrator.initializer.deployment.synapse.deployer.SynapseAppDeployerConstants.SYNAPSE_LIBRARY_TYPE;
import static org.wso2.micro.integrator.initializer.utils.DeployerUtilTest.createCarFile;
import static org.wso2.micro.integrator.initializer.utils.DeployerUtilTest.createHighPriorityCarFile;
import static org.wso2.micro.integrator.initializer.utils.DeployerUtilTest.writeDescriptorToExistingCarFile;

public class CappDeployerTest {

    private CappDeployer cappDeployer;
    private File tempCAppDir;

    @Before
    public void setUp() throws IOException {

        cappDeployer = new CappDeployer();

        // Create a temp directory and dummy files
        tempCAppDir = File.createTempFile("cappdir", "");
        tempCAppDir.delete(); // Delete the file so we can use the same name for directory
        tempCAppDir.mkdir();

        // Inject the path
        cappDeployer.setDirectory(tempCAppDir.getAbsolutePath());
    }

    @After
    public void tearDown() {

        for (File file : Objects.requireNonNull(tempCAppDir.listFiles())) {
            file.delete();
        }
        tempCAppDir.delete();
    }

    @Test
    public void testSort_AlphabeticalOrderWhenDescriptorMissing() throws IOException {
        // Create actual .car files with descriptor.xml inside tempCAppDir
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carA, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        assertEquals("a.car", files.get(0).getFile().getName());
        assertEquals("b.car", files.get(1).getFile().getName());
        assertEquals("c.car", files.get(2).getFile().getName());
    }

    @Test
    public void testSort_DependencyOrderWhenDescriptorPresentWithoutDependencies() throws Exception {
        // Create .car files with descriptor.xml
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");

        writeDescriptorToExistingCarFile(carA, "group", "a", "1.0.0");
        writeDescriptorToExistingCarFile(carB, "group", "b", "1.0.0");
        writeDescriptorToExistingCarFile(carC, "group", "c", "1.0.0");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carA, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        assertEquals("a.car", files.get(0).getFile().getName());
        assertEquals("b.car", files.get(1).getFile().getName());
        assertEquals("c.car", files.get(2).getFile().getName());
    }

    @Test
    public void testSort_DependencyOrderWhenDescriptorPresentWithDependencies() throws Exception {
        // Create .car files
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");

        // A depends on B, B depends on C, C has no dependencies
        String depB = "<dependency groupId=\"com.example\" artifactId=\"b\" version=\"1.0.0\" type=\"car\"/>";
        String depC = "<dependency groupId=\"com.example\" artifactId=\"c\" version=\"1.0.0\" type=\"car\"/>";

        writeDescriptorToExistingCarFile(carA, "com.example", "a", "1.0.0", depB);
        writeDescriptorToExistingCarFile(carB, "com.example", "b", "1.0.0", depC);
        writeDescriptorToExistingCarFile(carC, "com.example", "c", "1.0.0");

        // Add in reverse order to test sorting
        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carA, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // C should come first, then B, then A
        assertEquals("c.car", files.get(0).getFile().getName());
        assertEquals("b.car", files.get(1).getFile().getName());
        assertEquals("a.car", files.get(2).getFile().getName());
    }

    @Test
    public void testSort_DependencyPresentButCarIdDiffersFromFileName() throws Exception {
        // Create .car files
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");

        // A depends on B, B depends on C, C has no dependencies
        String depB = "<dependency groupId=\"com.example\" artifactId=\"carB\" version=\"1.0.0\" type=\"car\"/>";
        String depC = "<dependency groupId=\"com.example\" artifactId=\"carC\" version=\"1.0.0\" type=\"car\"/>";

        writeDescriptorToExistingCarFile(carA, "com.example", "carA", "1.0.0", depB);
        writeDescriptorToExistingCarFile(carB, "com.example", "carB", "1.0.0", depC);
        writeDescriptorToExistingCarFile(carC, "com.example", "carC", "1.0.0");

        // Add in reverse order to test sorting
        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carA, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // C should come first, then B, then A
        assertEquals("c.car", files.get(0).getFile().getName());
        assertEquals("b.car", files.get(1).getFile().getName());
        assertEquals("a.car", files.get(2).getFile().getName());
    }

    @Test
    public void testSort_SubsetOfFiles() throws Exception {
        // Create .car files
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");
        File carD = createCarFile(tempCAppDir, "d.car");
        File carE = createCarFile(tempCAppDir, "e.car");

        writeDescriptorToExistingCarFile(carA, "group", "a", "1.0.0");
        writeDescriptorToExistingCarFile(carB, "group", "b", "1.0.0");
        writeDescriptorToExistingCarFile(carC, "group", "c", "1.0.0");
        writeDescriptorToExistingCarFile(carD, "group", "d", "1.0.0");
        writeDescriptorToExistingCarFile(carE, "group", "e", "1.0.0");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carE, cappDeployer));
        files.add(new DeploymentFileData(carD, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carA, cappDeployer));

        // Only sort the middle three (carC, carB, carA)
        cappDeployer.sort(files, 2, 5);

        // carE and carD should remain at index 0 and 1, carA, carB, carC should be sorted alphabetically
        assertEquals("e.car", files.get(0).getFile().getName());
        assertEquals("d.car", files.get(1).getFile().getName());
        assertEquals("a.car", files.get(2).getFile().getName());
        assertEquals("b.car", files.get(3).getFile().getName());
        assertEquals("c.car", files.get(4).getFile().getName());
    }

    @Test
    public void testSort_SubsetWithDependencies() throws Exception {
        // Create .car files
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");
        File carD = createCarFile(tempCAppDir, "d.car");
        File carE = createCarFile(tempCAppDir, "e.car");

        // A depends on B, B depends on C, C has no dependencies
        String depB = "<dependency groupId=\"com.example\" artifactId=\"b\" version=\"1.0.0\" type=\"car\"/>";
        String depC = "<dependency groupId=\"com.example\" artifactId=\"c\" version=\"1.0.0\" type=\"car\"/>";

        writeDescriptorToExistingCarFile(carA, "com.example", "a", "1.0.0", depB);
        writeDescriptorToExistingCarFile(carB, "com.example", "b", "1.0.0", depC);
        writeDescriptorToExistingCarFile(carC, "com.example", "c", "1.0.0");
        writeDescriptorToExistingCarFile(carD, "com.example", "d", "1.0.0");
        writeDescriptorToExistingCarFile(carE, "com.example", "e", "1.0.0");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carE, cappDeployer));
        files.add(new DeploymentFileData(carD, cappDeployer));
        files.add(new DeploymentFileData(carA, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));

        // Only sort the middle three (carA, carB, carC) which have dependencies
        cappDeployer.sort(files, 2, 5);

        // carE and carD should remain at index 0 and 1, carC, carB, carA should be sorted by dependency order
        assertEquals("e.car", files.get(0).getFile().getName());
        assertEquals("d.car", files.get(1).getFile().getName());
        assertEquals("c.car", files.get(2).getFile().getName());
        assertEquals("b.car", files.get(3).getFile().getName());
        assertEquals("a.car", files.get(4).getFile().getName());
    }

    @Test
    public void testSort_DependencyInDirButNotInFilesList() throws Exception {
        // Create .car files
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");
        File carD = createCarFile(tempCAppDir, "d.car"); // d.car will be present in dir but not in files list

        // A depends on B, B depends on D (but D is not in files list), C has no dependencies
        String depB = "<dependency groupId=\"com.example\" artifactId=\"b\" version=\"1.0.0\" type=\"car\"/>";
        String depD = "<dependency groupId=\"com.example\" artifactId=\"d\" version=\"1.0.0\" type=\"car\"/>";

        writeDescriptorToExistingCarFile(carA, "com.example", "a", "1.0.0", depB);
        writeDescriptorToExistingCarFile(carB, "com.example", "b", "1.0.0", depD);
        writeDescriptorToExistingCarFile(carC, "com.example", "c", "1.0.0");
        writeDescriptorToExistingCarFile(carD, "com.example", "d", "1.0.0");

        // Only add A, B, C to the files list (not D)
        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carA, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // C should come first (no deps), then B (depends on D, but D not in list), then A (depends on B)
        assertEquals("c.car", files.get(0).getFile().getName());
        assertEquals("b.car", files.get(1).getFile().getName());
        assertEquals("a.car", files.get(2).getFile().getName());
    }

    @Test
    public void testSort_MixedDescriptorPresence() throws Exception {
        // Create .car files
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");

        // Only carA and carC have descriptor.xml, carB does not
        String depC = "<dependency groupId=\"group\" artifactId=\"c\" version=\"1.0.0\" type=\"car\"/>";
        writeDescriptorToExistingCarFile(carA, "group", "a", "1.0.0", depC);
        // carB does not have a descriptor.xml
        writeDescriptorToExistingCarFile(carC, "group", "c", "1.0.0");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carC, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carA, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // Should sort by dependency: c.car first, then b.car (no descriptor), then a.car (depends on c)
        assertEquals("a.car", files.get(0).getFile().getName());
        assertEquals("b.car", files.get(1).getFile().getName());
        assertEquals("c.car", files.get(2).getFile().getName());
    }

    @Test
    public void testSort_CyclicDependency() throws Exception {
        // Create .car files
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");

        // A depends on B, B depends on C, C depends on A (cycle)
        String depB = "<dependency groupId=\"com.example\" artifactId=\"b\" version=\"1.0.0\" type=\"car\"/>";
        String depC = "<dependency groupId=\"com.example\" artifactId=\"c\" version=\"1.0.0\" type=\"car\"/>";
        String depA = "<dependency groupId=\"com.example\" artifactId=\"a\" version=\"1.0.0\" type=\"car\"/>";

        writeDescriptorToExistingCarFile(carA, "com.example", "a", "1.0.0", depB);
        writeDescriptorToExistingCarFile(carB, "com.example", "b", "1.0.0", depC);
        writeDescriptorToExistingCarFile(carC, "com.example", "c", "1.0.0", depA);

        // Add in arbitrary order
        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));
        files.add(new DeploymentFileData(carA, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // Should sort alphabetically because of cyclic dependency: expect a.car, b.car, c.car (alphabetical order)
        assertEquals("a.car", files.get(0).getFile().getName());
        assertEquals("b.car", files.get(1).getFile().getName());
        assertEquals("c.car", files.get(2).getFile().getName());
    }

    @Test
    public void testSort_MissingDependencyAmongAvailableCApps() throws Exception {
        // Create .car files
        File carA = createCarFile(tempCAppDir, "a.car");
        File carB = createCarFile(tempCAppDir, "b.car");
        File carC = createCarFile(tempCAppDir, "c.car");

        // A depends on X (which does not exist), B and C have no dependencies
        String depX = "<dependency groupId=\"com.example\" artifactId=\"x\" version=\"1.0.0\" type=\"car\"/>";
        writeDescriptorToExistingCarFile(carA, "com.example", "a", "1.0.0", depX);
        writeDescriptorToExistingCarFile(carB, "com.example", "b", "1.0.0");
        writeDescriptorToExistingCarFile(carC, "com.example", "c", "1.0.0");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carA, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));
        files.add(new DeploymentFileData(carC, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // Since a dependency is missing, only the available CApps are sorted alphabetically: expect a.car, b.car, c.car (alphabetical order)
        assertEquals("a.car", files.get(0).getFile().getName());
        assertEquals("b.car", files.get(1).getFile().getName());
        assertEquals("c.car", files.get(2).getFile().getName());
    }

    // -----------------------------------------------------------------------
    // Content-based priority tests (covers issue #4853)
    // -----------------------------------------------------------------------

    /**
     * Reproduces the exact failure from issue #4853:
     * "a-proxy.car" (consumer, no lib artifact) sorts before "z-mediator-lib.car"
     * (provides lib/synapse/mediator) alphabetically.  After content-priority sort
     * the mediator library CApp must come first.
     */
    @Test
    public void testSort_ContentPriority_ClassMediatorCAppDeployedBeforeConsumer() throws Exception {

        File consumerCar = createCarFile(tempCAppDir, "a-proxy.car");
        File mediatorCar = createHighPriorityCarFile(tempCAppDir, "z-mediator-lib.car", MEDIATOR_TYPE);

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(consumerCar, cappDeployer));
        files.add(new DeploymentFileData(mediatorCar, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // Mediator library CApp must deploy first despite alphabetical order putting it second
        assertEquals("z-mediator-lib.car", files.get(0).getFile().getName());
        assertEquals("a-proxy.car", files.get(1).getFile().getName());
    }

    /**
     * Connector CApps (synapse/lib type) must be deployed before CApps that use them,
     * even when the connector CApp sorts after the consumer alphabetically.
     */
    @Test
    public void testSort_ContentPriority_ConnectorCAppDeployedFirst() throws Exception {

        File consumerCar = createCarFile(tempCAppDir, "a-api.car");
        File connectorCar = createHighPriorityCarFile(tempCAppDir, "z-connector.car", SYNAPSE_LIBRARY_TYPE);

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(consumerCar, cappDeployer));
        files.add(new DeploymentFileData(connectorCar, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        assertEquals("z-connector.car", files.get(0).getFile().getName());
        assertEquals("a-api.car", files.get(1).getFile().getName());
    }

    /**
     * Registry-resource CApps (registry/resource type) must be deployed before consumers,
     * even when the registry CApp sorts after the consumer alphabetically.
     */
    @Test
    public void testSort_ContentPriority_RegistryResourceCAppDeployedFirst() throws Exception {

        File consumerCar = createCarFile(tempCAppDir, "a-consumer.car");
        File registryCar = createHighPriorityCarFile(tempCAppDir, "z-registry.car", "registry/resource");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(consumerCar, cappDeployer));
        files.add(new DeploymentFileData(registryCar, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        assertEquals("z-registry.car", files.get(0).getFile().getName());
        assertEquals("a-consumer.car", files.get(1).getFile().getName());
    }

    /**
     * When multiple CApps are high-priority (mediator/connector/registry), they must be
     * sorted alphabetically among themselves and all precede normal-priority CApps.
     */
    @Test
    public void testSort_ContentPriority_AlphabeticalWithinHighPriorityTier() throws Exception {

        File connectorCar = createHighPriorityCarFile(tempCAppDir, "c-connector.car", SYNAPSE_LIBRARY_TYPE);
        File mediatorCar  = createHighPriorityCarFile(tempCAppDir, "a-mediator.car",  MEDIATOR_TYPE);
        File consumerCar  = createCarFile(tempCAppDir, "z-consumer.car");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(connectorCar, cappDeployer));
        files.add(new DeploymentFileData(mediatorCar,  cappDeployer));
        files.add(new DeploymentFileData(consumerCar,  cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // Both high-priority CApps deploy before the consumer; alphabetical within that tier
        assertEquals("a-mediator.car",  files.get(0).getFile().getName());
        assertEquals("c-connector.car", files.get(1).getFile().getName());
        assertEquals("z-consumer.car",  files.get(2).getFile().getName());
    }

    /**
     * Normal-priority CApps (no lib/connector/registry artifacts) must be sorted
     * alphabetically among themselves after all high-priority CApps.
     */
    @Test
    public void testSort_ContentPriority_AlphabeticalWithinNormalPriorityTier() throws Exception {

        File mediatorCar  = createHighPriorityCarFile(tempCAppDir, "z-mediator.car", MEDIATOR_TYPE);
        File consumerCar2 = createCarFile(tempCAppDir, "c-proxy.car");
        File consumerCar1 = createCarFile(tempCAppDir, "a-proxy.car");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(mediatorCar,  cappDeployer));
        files.add(new DeploymentFileData(consumerCar2, cappDeployer));
        files.add(new DeploymentFileData(consumerCar1, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        // High-priority first, then normal-priority CApps alphabetically
        assertEquals("z-mediator.car", files.get(0).getFile().getName());
        assertEquals("a-proxy.car",    files.get(1).getFile().getName());
        assertEquals("c-proxy.car",    files.get(2).getFile().getName());
    }

    /**
     * When every CApp in the deployment is high-priority, the result must be
     * pure alphabetical order (no consumer CApps to push to the back).
     */
    @Test
    public void testSort_ContentPriority_AllHighPriorityOrderedAlphabetically() throws Exception {

        File carC = createHighPriorityCarFile(tempCAppDir, "c-mediator.car",  MEDIATOR_TYPE);
        File carA = createHighPriorityCarFile(tempCAppDir, "a-connector.car", SYNAPSE_LIBRARY_TYPE);
        File carB = createHighPriorityCarFile(tempCAppDir, "b-registry.car",  "registry/resource");

        List<DeploymentFileData> files = new ArrayList<>();
        files.add(new DeploymentFileData(carC, cappDeployer));
        files.add(new DeploymentFileData(carA, cappDeployer));
        files.add(new DeploymentFileData(carB, cappDeployer));

        cappDeployer.sort(files, 0, files.size());

        assertEquals("a-connector.car", files.get(0).getFile().getName());
        assertEquals("b-registry.car",  files.get(1).getFile().getName());
        assertEquals("c-mediator.car",  files.get(2).getFile().getName());
    }
}
