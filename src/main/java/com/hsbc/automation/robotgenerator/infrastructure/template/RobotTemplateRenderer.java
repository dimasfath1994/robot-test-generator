package com.hsbc.automation.robotgenerator.infrastructure.template;

import java.net.URI;
import org.springframework.stereotype.Component;
import com.hsbc.automation.robotgenerator.domain.GenerationRequest;

@Component
public class RobotTemplateRenderer {
    public String renderRequirements() {
        return "requests\nrobotframework\nrobotframework-appiumlibrary\nrobotframework-requests\n"
                + "robotframework-seleniumlibrary\nxlrd\nxlutils\nxlwt\nnumpy\nopencv-python-headless\n"
                + "Appium-Python-Client\npytz\nallure-robotframework\nselenium\ncryptography\n";
    }
    public String renderBaseTest(GenerationRequest request) {
        String getApplication = request.hasEndpoint()
                ? "Get Application Endpoint\n    [Arguments]    ${endpoint}\n    ${response}=    GET On Session    application    ${endpoint}\n"
                : "Get Application\n    ${response}=    GET On Session    application    /\n";
        return "*** Settings ***\nLibrary    RequestsLibrary\n*** Keywords ***\nCreate Application Session\n"
                + "    [Arguments]    ${base_url}\n    Create Session    application    ${base_url}\n"
                + getApplication + "getAplicaton\n    ...    expected_status=anything\n    RETURN    ${response}\n"
                + "Close Application Session\n    Delete All Sessions\n";
    }
    public String renderVariables(GenerationRequest request) {
        return "*** Variables ***\n${BASE_URL}    " + request.getBaseUrl() + "\n"
                + (request.hasEndpoint() ? "${ENDPOINT}    " + request.getEndpoint() + "\n" : "");
    }
    public String renderTestCase(GenerationRequest request) {
        URI baseUri = URI.create(request.getBaseUrl());
        String expectedHost = baseUri.getHost() + (baseUri.getPort() == -1 ? "" : ":" + baseUri.getPort());
        String assertions = "    Should Be Equal As Integers    ${response.status_code}    200\n"
                + "    Should Be True    200 <= ${response.status_code} < 400\n"
                + "    Should Contain    ${response.url}    " + expectedHost + "\n"
                + (request.hasEndpoint() ? "    Should Contain    ${response.url}    " + request.getEndpoint() + "\n" : "");
        return "*** Settings ***\nResource    ../../Keywords/BaseTest.robot\nResource    ../../Resources/locators.robot\n"
                + "Test Setup    Create Application Session    ${BASE_URL}\nTest Teardown    Close Application Session\n"
                + "**** Test Cases ***\n"
                + (request.hasEndpoint() ? "Verify Application Login Page Accessibility\n    [Documentation]    Verify that the application URL is accessible\n" : "")
                + "Verify Application Accessibility\n    [Documentation]    Verify that the application base URL is accessible\n"
                + "    [Tags]    TC01\n" + (request.hasEndpoint() ? "    Get Application Endpoint    ${ENDPOINT}\n" : "    Get Application\n")
                + "    Log To Console    HTTP status: ${response.status_code}\n    Log To Console    Final URL: ${response.url}\n" + assertions;
    }
}
