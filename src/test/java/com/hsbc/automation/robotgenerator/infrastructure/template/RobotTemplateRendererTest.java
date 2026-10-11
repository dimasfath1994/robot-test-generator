package com.hsbc.automation.robotgenerator.infrastructure.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.hsbc.automation.robotgenerator.domain.DatabaseCheck;
import com.hsbc.automation.robotgenerator.domain.GenerationRequest;

class RobotTemplateRendererTest {
    private final RobotTemplateRenderer renderer = new RobotTemplateRenderer();

    @Test
    void rendersEndpointVariablesAndAssertions() {
        GenerationRequest request = new GenerationRequest("EIM-42", "Portal", "https://example.com:8443/", "health/check");

        String variables = renderer.renderVariables(request);
        String testCase = renderer.renderTestCase(request);

        assertTrue(variables.contains("${BASE_URL}    https://example.com:8443"));
        assertTrue(variables.contains("${ENDPOINT}    /health/check"));
        assertTrue(testCase.contains("Get Application Endpoint"));
        assertTrue(testCase.contains("${ENDPOINT}"));
        assertTrue(testCase.contains("Should Contain    ${response.url}    example.com:8443"));
        assertTrue(testCase.contains("/health/check"));
    }

    @Test
    void omitsEndpointVariableForRootRequest() {
        GenerationRequest request = new GenerationRequest("EIM-42", "Portal", "https://example.com", "");

        assertFalse(renderer.renderVariables(request).contains("${ENDPOINT}"));
        assertTrue(renderer.renderTestCase(request).contains("Get Application\n"));
        assertTrue(renderer.renderRequirements().contains("robotframework-requests\n"));
    }

    @Test
    void rendersDatabaseCheckSettingsVariablesTestAndRequirements() {
        DatabaseCheck database = new DatabaseCheck("pymssql", "appdb", "sql.example", 1433,
                "SELECT * FROM users", 1, "Verify active users", "Check active user rows");
        GenerationRequest request = new GenerationRequest("EIM-42", "Database Health", database);

        String baseTest = renderer.renderBaseTest(request);
        String variables = renderer.renderVariables(request);
        String testCase = renderer.renderTestCase(request);
        String requirements = renderer.renderRequirements(request);

        assertTrue(baseTest.contains("Library   DatabaseLibrary"));
        assertTrue(baseTest.contains("Connect To Database"));
        assertTrue(variables.contains("${DB_USERNAME}    %{DB_USERNAME}"));
        assertTrue(variables.contains("${DB_PASSWORD}    %{DB_PASSWORD}"));
        assertTrue(variables.contains("${QUERY_GET_USER_BY_NAME}    SELECT * FROM users"));
        assertTrue(testCase.contains("*** Test Cases ***"));
        assertTrue(testCase.contains("${TC01_TEST_CASE_NAME}"));
        assertTrue(testCase.contains("Row Count Is Equal To X"));
        assertTrue(requirements.contains("robotframework-databaselibrary\n"));
        assertTrue(requirements.contains("pymssql\n"));
    }
}
