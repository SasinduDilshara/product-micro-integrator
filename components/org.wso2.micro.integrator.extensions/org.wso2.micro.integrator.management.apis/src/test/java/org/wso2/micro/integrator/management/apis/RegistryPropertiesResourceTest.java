/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.micro.integrator.management.apis;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the fix introduced in issue #4767:
 * "Error message returned when there are no properties for a registry resource".
 *
 * Before the fix, populateRegistryProperties() called Utils.createJSONList(0) was not used in
 * the null-properties branch; instead it put the string "Error while fetching properties" into
 * the LIST field. The fix replaces that branch with Utils.createJSONList(0) so the response
 * shape is {"count":0,"list":[]} for a resource that has no properties file.
 *
 * These unit tests verify the helper method that the fix delegates to, ensuring it produces
 * exactly the shape required.
 */
public class RegistryPropertiesResourceTest {

    /**
     * Issue #4767 — core fix: when getResourceProperties() returns null (no .properties file
     * exists for the resource), the API must return {"count":0,"list":[]} rather than
     * {"list":"Error while fetching properties"}.
     *
     * This test verifies that Utils.createJSONList(0) — the method now used in the null branch —
     * produces the required shape.
     */
    @Test
    public void testCreateJSONListWithZeroCountProducesEmptyListShape() {

        JSONObject result = Utils.createJSONList(0);

        Assert.assertNotNull("createJSONList(0) must not return null", result);
        // Must have a "count" key equal to 0
        Assert.assertTrue("Response must contain 'count' key", result.has(Constants.COUNT));
        Assert.assertEquals("count must be 0 for a resource with no properties", 0,
                result.getInt(Constants.COUNT));
        // Must have a "list" key that is an empty JSON array
        Assert.assertTrue("Response must contain 'list' key", result.has(Constants.LIST));
        Assert.assertEquals("list must be empty for a resource with no properties", 0,
                result.getJSONArray(Constants.LIST).length());
        // The response must NOT contain the old error string that was the bug symptom
        Assert.assertFalse("Response must not contain 'Error while fetching properties'",
                result.toString().contains("Error while fetching properties"));
    }

    /**
     * Edge case: verify that createJSONList produces the correct count and list length
     * when there are entries (regression guard — the non-null branch must still work).
     */
    @Test
    public void testCreateJSONListWithPositiveCountProducesCorrectShape() {

        int count = 3;
        JSONObject result = Utils.createJSONList(count);

        Assert.assertNotNull("createJSONList(3) must not return null", result);
        Assert.assertTrue("Response must contain 'count' key", result.has(Constants.COUNT));
        Assert.assertEquals("count must match the supplied value", count,
                result.getInt(Constants.COUNT));
        Assert.assertTrue("Response must contain 'list' key", result.has(Constants.LIST));
        // The list itself starts empty — the caller is expected to populate it.
        // This mirrors the production code in populateRegistryProperties().
        Assert.assertEquals("Initial list must be empty before population", 0,
                result.getJSONArray(Constants.LIST).length());
    }

    /**
     * Negative test: the old buggy behaviour was to set the list value to a plain string.
     * Confirm that createJSONList(0) does NOT place a String under the LIST key.
     */
    @Test
    public void testCreateJSONListDoesNotReturnStringInListField() {

        JSONObject result = Utils.createJSONList(0);

        // Accessing getJSONArray must succeed (no ClassCastException / JSONException)
        try {
            result.getJSONArray(Constants.LIST);
        } catch (Exception e) {
            Assert.fail("The LIST field must be a JSONArray, not a String. Exception: " + e.getMessage());
        }
    }
}
