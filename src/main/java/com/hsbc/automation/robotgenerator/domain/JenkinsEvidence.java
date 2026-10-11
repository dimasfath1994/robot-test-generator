package com.hsbc.automation.robotgenerator.domain;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JenkinsEvidence {

    private static final Pattern LOG_LINK = Pattern.compile("(?m)-\\s*Log:\\s*(https://\\S+)");

    private final String target;
    private final String buildUrl;
    private final String gitUrl;
    private final String logUrl;

    public JenkinsEvidence(String target, String buildUrl, String gitUrl, String consoleText) {
        this.target = target;
        this.buildUrl = buildUrl;
        this.gitUrl = gitUrl;
        String detectedLog = null;
        Matcher matches = LOG_LINK.matcher(consoleText);
        while (matches.find()) {
            String candidate = matches.group(1);
            URI uri = URI.create(candidate);
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getPath() != null && uri.getPath().endsWith("/log.html")) {
                detectedLog = candidate;
            }
        }
        if (detectedLog == null) {
            throw new IllegalArgumentException("No '- Log: https://.../log.html' link found in Jenkins Publish Results output.");
        }
        this.logUrl = detectedLog;
    }

    public String getTarget() {
        return target;
    }

    public String getBuildUrl() {
        return buildUrl;
    }

    public String getGitUrl() {
        return gitUrl;
    }

    public String getLogUrl() {
        return logUrl;
    }
}