package com.hsbc.automation.robotgenerator.infrastructure.template;

import java.net.URI;

import org.springframework.stereotype.Component;

import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.domain.DatabaseCheck;

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
                if (request.isDatabaseCheck()) {
                        return "*** Settings ***\nLibrary   DatabaseLibrary\n\n*** Keywords ***\n"
                                        + "Connect To SWD Database\n    Connect To Database\n"
                                        + "     ...     db_module=${DB_MODULE_NAME}\n   ...     db_name=${DB_NAME}\n"
                                        + "     ...     db_user=${DB_USERNAME}\n        ...     db_password=${DB_PASSWORD}\n"
                                        + "     ...     db_host=${DB_HOST}\n    ...     db_port=${DB_PORT}\n\n"
                                        + "Disconnect From SWD Database\n       Disconnect From Database\n";
                }
        String getApplication = request.hasEndpoint()
                ? "Get Application Endpoint\n"
                + "    [Arguments]    ${endpoint}\n"
                + "    ${response}=    GET On Session\n"
                + "     ...     application\n"
                + "     ...     ${endpoint}\n"
                : "Get Application\n"
                + "    ${response}=    GET On Session\n"
                + "     ...     application\n"
                + "     ...     /\n";

        return "*** Settings ***\n"
                + "Library    RequestsLibrary\n\n"
                + "*** Keywords ***\n"
                + "Create Application Session\n"
                + "    [Arguments]    ${base_url}\n"
                + "    Create Session    application    ${base_url}\n\n"
                + getApplication
                + "    ...    expected_status=anything\n"
                + "    RETURN    ${response}\n\n"
                + "Close Application Session\n"
                + "    Delete All Sessions\n";
    }

    public String renderVariables(GenerationRequest request) {
        if (request.isDatabaseCheck()) {
                DatabaseCheck database = request.getDatabaseCheck();
                return "*** Variables ***\n"
                                + "${DB_MODULE_NAME}    " + robotValue(database.getModule()) + "\n"
                                + "${DB_NAME}    " + robotValue(database.getName()) + "\n"
                                + "${DB_HOST}    " + robotValue(database.getHost()) + "\n"
                                + "${DB_PORT}    " + database.getPort() + "\n"
                                + "${DB_USERNAME}    " + credentialValue(database, database.getUsername()) + "\n"
                                + "${DB_PASSWORD}    " + credentialValue(database, database.getPassword()) + "\n"
                                + "${QUERY_GET_USER_BY_NAME}    " + robotValue(database.getQuery()) + "\n"
                                + "${EXPECTED_ROW_COUNT}    " + database.getExpectedRowCount() + "\n"
                                + "${TC01_TEST_CASE_NAME}    " + robotValue(database.getTestName()) + "\n"
                                + "${TC01_TEST_CASE_DESCRIPTION}    " + robotValue(database.getDescription()) + "\n";
        }
        return "*** Variables ***\n"
                + "${BASE_URL}    " + request.getBaseUrl() + "\n"
                + (request.hasEndpoint() ? "${ENDPOINT}    " + request.getEndpoint() + "\n" : "");
    }

    public String renderTestCase(GenerationRequest request) {
                if (request.isDatabaseCheck()) {
                        return "*** Settings ***\nDocumentation    ${TC01_TEST_CASE_DESCRIPTION}\n"
                                + "Resource     ../../Keywords/BaseTest.robot\nResource       ../../Resources/locators.robot\n\n"
                                + "Test Setup   Connect To SWD Database\nTest Teardown  Disconnect From SWD Database\n\n"
                                + "*** Test Cases ***\n${TC01_TEST_CASE_NAME}\n     [Tags]    Database  Regression\n\n"
                                + "     Row Count Is Equal To X\n       ...     ${QUERY_GET_USER_BY_NAME}\n"
                                + "     ...     ${EXPECTED_ROW_COUNT}";
                }
        URI baseUri = URI.create(request.getBaseUrl());
        String expectedHost = baseUri.getHost()
                + (baseUri.getPort() == -1 ? "" : ":" + baseUri.getPort());
        String assertions = request.hasEndpoint()
                ? "    Should Be Equal As Integers    ${response.status_code}    200\n"
                        : "    Should Be True    200 <= ${response.status_code} < 400\n";
                assertions += "    Should Contain    ${response.url}    " + expectedHost + "\n";
                if (request.hasEndpoint()) {
                        assertions += "   Should Contain        ${response.url}     " + request.getEndpoint() + "\n";
                }
        return "*** Settings ***\n"
                + "Resource    ../../Keywords/BaseTest.robot\n"
                + "Resource    ../../Resources/locators.robot\n\n"
                + "Test Setup    Create Application Session    ${BASE_URL}\n"
                + "Test Teardown    Close Application Session\n\n"
                + "*** Test Cases ***\n"
        + (request.hasEndpoint()
                ? "Verify Application Login Page Accessibility\n"
                        + "    [Documentation]    Verify that the application URL is accessible\n"
                : "Verify Application Accesibility\n"
                        + "     [Documentation]   Verify that the application base URL is accessible\n")
                + "     [Tags]    TC01\n\n"
        + (request.hasEndpoint()
                ? "     ${response}=    Get Application Endpoint    ${ENDPOINT}\n\n"
                : "     ${response}=    Get Application\n\n")
                + "    Log To Console    HTTP status: ${response.status_code}\n"
                + "    Log To Console    Final URL: ${response.url}\n\n"
                + assertions;
    }
    public String renderRequirements(GenerationRequest request) {
                if (request.isDatabaseCheck()) {
                        return renderRequirements().replace("robotframework-seleniumlibrary\n",
                                         "robotframework-seleniumlibrary\nrobotframework-databaselibrary\npymssql\n");
                }
                return renderRequirements();
    }

    private String robotValue(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                boolean variable = "$%@&".indexOf(character) >= 0
                                && index + 1 < value.length() && value.charAt(index + 1) == '{';
                boolean separatorSpace = character == ' ' && (index == 0 || index == value.length() -1
                                || value.charAt(index - 1) == ' ' || value.charAt(index + 1) == ' ');
                if (character == '\\' || character == '|' || variable || separatorSpace
                                || (character == '#' && index == 0)) {
                                        escaped.append('\\');
                                }
                escaped.append(character);
        }
        return escaped.toString();
    }

    private String credentialValue(DatabaseCheck database, String value) {
        return database.usesEnvironmentCredentials() ? "%{" + value + "}"
                        : value.replace("\\", "\\\\").replace("$", "\\$").replace("%", "\\%")
                                .replace("&", "\\&").replace("@", "\\@").replace(" ", "\\ ")
                                .replace("|", "\\|").replace("#", "\\#");
    }
}
