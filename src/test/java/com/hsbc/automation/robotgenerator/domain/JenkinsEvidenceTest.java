package com.hsbc.automation.robotgenerator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JenkinsEvidenceTest {
    @Test
    void extractsRobotLogUrlFromPublishOutput() {
        JenkinsEvidence evidence = new JenkinsEvidence("job #1", "https://jenkins/job/test/1/", "https://git/test",
                "Publish Results\n- Log: https://jenkins:8443/job/test/1/artifact/log.html\n");

        assertEquals("job #1", evidence.getTarget());
        assertEquals("https://jenkins/job/test/1/", evidence.getBuildUrl());
        assertEquals("https://git/test", evidence.getGitUrl());
        assertEquals("https://jenkins:8443/job/test/1/artifact/log.html", evidence.getLogUrl());
    }

    @Test
    void rejectsOutputWithoutLogLink() {
        assertThrows(IllegalArgumentException.class,
                () -> new JenkinsEvidence("job #1", "build", "git", "Publish Results completed"));
    }

    @Test
    void acceptsLogLinkWithoutExplicitPort() {
        JenkinsEvidence evidence = new JenkinsEvidence(
                "job #1", "build", "git", "- Log: https://jenkins/job/1/log.html");

        assertEquals("https://jenkins/job/1/log.html", evidence.getLogUrl());
    }
}
