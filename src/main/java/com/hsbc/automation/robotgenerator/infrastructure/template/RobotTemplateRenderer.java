package com.hsbc.automation.robotgenerator.infrastructure.template;

import java.net.URI;
import org.springframework.stereotype.Component;
import com.hsbc.automation.robotgenerator.domain.GenerationRequest;

@Component
public class RobotTemplateRenderer {

    public String renderRequirements() {
        return "requests\n"
                + "robotframework\n"
                + "robotframework-appiumlibrary\n"
                + "robotframework-requests\n"
                + "robotframework-seleniumlibrary\n"
                + "xlrd\n"
                + "xlutils\n"
                + "xlwt\n"
                + "numpy\n"
                + "opencv-python-headless\n"
                + "Appium-Python-Client\n"
                + "pytz\n"
                + "allure-robotframework\n"
                + "selenium\n"
                + "cryptography\n";
    }

    public String renderBaseTest(GenerationRequest request) {
        String getApplication = request.hasEndpoint()
                ? "Get Application Endpoint\n"
                + "    [Arguments]    ${endpoint}\n"
                + "    ${response}=    GET On Session    application    ${endpoint}\n"
                : "Get Application\n"
                + "    ${response}=    GET On Session    application    /\n";

        return "*** Settings ***\n"
                + "Library    RequestsLibrary\n"
                + "*** Keywords ***\n"
                + "Create Application Session\n"
                + "    [Arguments]    ${base_url}\n"
                + "    Create Session    application    ${base_url}\n"
                + "getAplicaton\n" // Sesuai typo di gambar baris 48
                + "    ...    expected_status=anything\n"
                + "    RETURN    ${response}\n"
                + "Close Application Session\n"
                + "    Delete All Sessions\n";
    }

    public String renderVariables(GenerationRequest request) {
        return "*** Variables ***\n"
                + "${BASE_URL}    " + request.getBaseUrl() + "\n"
                + (request.hasEndpoint() ? "${ENDPOINT}    " + request.getEndpoint() + "\n" : "");
    }

    public String renderTestCase(GenerationRequest request) {
        URI baseUri = URI.create(request.getBaseUrl());
        String expectedHost = baseUri.getHost()
                + (baseUri.getPort() == -1 ? "" : ":" + baseUri.getPort());
        String assertions = request.hasEndpoint()
                ? "    Should Be Equal As Integers    ${response.status_code}    200\n"
                + "    Should Be True    200 <= ${response.status_code} < 400\n"
                + "    Should Contain    ${response.url}    " + expectedHost + "\n"
                + (request.hasEndpoint() ? "    Should Contain    ${response.url}    " + request.getEndpoint() + "\n" : "")
                : "    Should Be Equal As Integers    ${response.status_code}    200\n"
                + "    Should Be True    200 <= ${response.status_code} < 400\n"
                + "    Should Contain    ${response.url}    " + expectedHost + "\n";

        return "*** Settings ***\n"
                + "Resource    ../../Keywords/BaseTest.robot\n"
                + "Resource    ../../Resources/locators.robot\n"
                + "Test Setup    Create Application Session    ${BASE_URL}\n"
                + "Test Teardown    Close Application Session\n"
                + "**** Test Cases ***\n"
                + (request.hasEndpoint()
                ? "Verify Application Login Page Accessibility\n"
                + "    [Documentation]    Verify that the application URL is accessible\n"
                : "")
                + "Verify Application Accessibility\n"
                + "    [Documentation]    Verify that the application base URL is accessible\n"
                + "    [Tags]    TC01\n"
                + (request.hasEndpoint()
                ? "    Get Application Endpoint    ${ENDPOINT}\n"
                : "    Get Application\n")
                + "    Log To Console    HTTP status: ${response.status_code}\n"
                + "    Log To Console    Final URL: ${response.url}\n"
                + assertions;
    }
}
