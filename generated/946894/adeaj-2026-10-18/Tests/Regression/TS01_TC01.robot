*** Settings ***
Resource    ../../Keywords/BaseTest.robot
Resource    ../../Resources/locators.robot

Test Setup    Create Application Session    ${BASE_URL}
Test Teardown    Close Application Session

*** Test Cases ***
Verify Application Accesibility
     [Documentation]   Verify that the application base URL is accessible
     [Tags]    TC01

     ${response}=    Get Application

    Log To Console    HTTP status: ${response.status_code}
    Log To Console    Final URL: ${response.url}

    Should Be True    200 <= ${response.status_code} < 400
    Should Contain    ${response.url}    www.google.com
