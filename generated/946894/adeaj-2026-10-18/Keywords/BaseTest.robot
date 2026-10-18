*** Settings ***
Library    RequestsLibrary

*** Keywords ***
Create Application Session
    [Arguments]    ${base_url}
    Create Session    application    ${base_url}

Get Application
    ${response}=    GET On Session
     ...     application
     ...     /
    ...    expected_status=anything
    RETURN    ${response}

Close Application Session
    Delete All Sessions
