package com.hsbc.automation.robotgenerator.infrastructure.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
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
        assertTrue(testCase.contains("Get Application Endpoint    ${ENDPOINT}"));
        assertTrue(testCase.contains("Should Contain    ${response.url}    example.com:8443"));
        assertTrue(testCase.contains("Should Contain    ${response.url}    /health/check"));
    }

    @Test
    void omitsEndpointVariableForRootRequest() {
        GenerationRequest request = new GenerationRequest("EIM-42", "Portal", "https://example.com", "");

        assertFalse(renderer.renderVariables(request).contains("${ENDPOINT}"));
        assertTrue(renderer.renderTestCase(request).contains("Get Application\n"));
        assertTrue(renderer.renderRequirements().contains("robotframework-requests\n"));
    }
}
