package com.hsbc.automation.robotgenerator.application;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.domain.JenkinsEvidence;
import com.hsbc.automation.robotgenerator.infrastructure.evidence.EvidenceHttpClient;
import com.hsbc.automation.robotgenerator.infrastructure.evidence.EvidenceScreenshotCapture;

@Service
public class JenkinsEvidencePublicationService {
    private static final String ATTACHMENT = "jenkins-evidence.png";
    private static final String HTML_ATTACHMENT = "log.html";
    private static final Pattern CONFLUENCE_PAGE = Pattern.compile("^(.*)?/(?:spaces/[^/]+/)?pages/(\\d+)(?:/.*)?$");
    private final EvidenceHttpClient http;
    private final EvidenceScreenshotCapture screenshots;
    private final Environment environment;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public JenkinsEvidencePublicationService(EvidenceHttpClient http, EvidenceScreenshotCapture screenshots, Environment environment, Clock clock) {
        this.http = http; this.screenshots = screenshots; this.environment = environment; this.clock = clock;
    }
    public String publish(GenerationRequest request, String suppliedBuildUrl, String suppliedGitUrl) throws IOException {
        String username = setting("my.username");
        String password = setting("my.password");
        URI build = https(suppliedBuildUrl), git = https(suppliedGitUrl), root = https(setting("confluence.url"));
        String buildUrl = build.toString().replaceAll("/+$", "") + "/";
        if (!build.getPath().matches(".*/job/.*/\\d+/?[^/]*$")) throw new IOException("Enter a specific completed Jenkins build URL ending in </build-number/>.");
        Matcher page = CONFLUENCE_PAGE.matcher(root.getPath());
        if (!page.matches()) throw new IOException("confluence.url must identify a root page using /spaces/<space>/pages/<page-id>/title.");
        String confluenceBase = root.getScheme() + "://" + root.getRawAuthority() + page.group(1);
        String api = confluenceBase + "/rest/api/content";
        String console = http.get(URI.create(buildUrl + "consoleText"), username, password);
        String buildNumber = build.getPath().replaceAll("/+$", "").replaceAll(".*/", "");
        JenkinsEvidence evidence;
        try { evidence = new JenkinsEvidence(request.getProjectName() + " #" + buildNumber, buildUrl, git.toString(), console); }
        catch (IllegalArgumentException exception) { throw new IOException(exception.getMessage(), exception); }
        URI report = https(evidence.getLogUrl());
        if (!sameOrigin(report, build) && !sameOrigin(report, git)) throw new IOException("Published log host must match the Git or Jenkins origin before credentials can be sent.");
        String html = http.get(report, username, password);
        if (!html.contains("Robot Framework")) throw new IOException("Published URL returned a non-Robot page, possibly a login page.");
        Path screenshot = Files.createTempFile("robot-jenkins-evidence-", ".png");
        Path htmlFile = Files.createTempFile("robot-jenkins-log-", ".html");
        try {
            boolean captured = false;
            Files.write(htmlFile, html.getBytes(StandardCharsets.UTF_8));
            try { screenshots.capture(html, screenshot); captured = true; } catch (IOException | RuntimeException ignored) { }
            JsonNode rootMetadata = read(http.get(URI.create(api + "/?" + page.group(2) + "?expand=space"), username, password));
            String space = rootMetadata.path("space").path("key").asText();
            if (space.isEmpty()) throw new IOException("Confluence did not return the root page's space key.");
            String title = request.getProjectName() + " Health Check " + LocalDate.now(clock);
            String storage = "<p><strong>Build URL: </strong><a href=\"" + evidence.getBuildUrl() + "\">" + evidence.getBuildUrl() + "</a></p>"
                    + "<p><strong>Git: </strong><a href=\"" + evidence.getGitUrl() + "\">" + evidence.getGitUrl() + "</a></p>"
                    + "<p><strong>Report: </strong><a href=\"" + evidence.getLogUrl() + "\">TS01 TC01 Log</a></p>"
                    + (captured ? "<ac:image><ri:attachment ri:filename=\"" + ATTACHMENT + "\" /></ac:image>" : "");
            ObjectNode body = mapper.createObjectNode(); body.put("type", "page"); body.put("title", title); body.putObject("space").put("key", space);
            body.putArray("ancestors").addObject().put("id", page.group(2));
            body.putObject("body").putObject("storage").put("value", storage).put("representation", "storage");
            JsonNode created = read(http.json(URI.create(api), "POST", body.toString(), username, password));
            String id = created.path("id").asText();
            if (!id.matches("\\d+")) throw new IOException("Confluence returned an invalid page ID.");
            upload(api, id, htmlFile, HTML_ATTACHMENT, username, password);
            if (captured) upload(api, id, screenshot, ATTACHMENT, username, password);
            return confluenceBase + "/pages/viewpage.action?pageId=" + id;
        } finally { Files.deleteIfExists(screenshot); Files.deleteIfExists(htmlFile); }
    }
    private void upload(String api, String id, Path file, String name, String user, String password) throws IOException {
        String base = api + "/" + id + "/child/attachment";
        JsonNode current = read(http.get(URI.create(base + "?filename=" + URLEncoder.encode(name, "UTF-8")), user, password));
        String url = base;
        if (current.path("results").size() > 0) url += "/" + current.path("results").get(0).path("id").asText() + "/data";
        http.upload(URI.create(url), file, name, user, password);
    }
    private JsonNode read(String value) throws IOException { JsonNode node = mapper.readTree(value); if (node == null) throw new IOException("Evidence API returned an empty JSON response."); return node; }
    private String setting(String name) throws IOException {
        try { String value = environment.getProperty(name, ""); if (!value.trim().isEmpty()) return value; }
        catch (IllegalArgumentException exception) { throw new IOException(name + " is not configured or its environment variable is missing."); }
        throw new IOException(name + " is required for evidence publication.");
    }
    private URI https(String value) throws IOException {
        URI uri; try { uri = URI.create(value.trim()); } catch (IllegalArgumentException exception) { throw new IOException("Evidence URLs must be valid HTTPS URLs."); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IOException("Evidence URLs must use HTTPS without embedded credentials, queries, or fragments.");
        return uri;
    }
    private boolean sameOrigin(URI a, URI b) { return a.getHost().equalsIgnoreCase(b.getHost()) && (a.getPort() == -1 ? 443 : a.getPort()) == (b.getPort() == -1 ? 443 : b.getPort()); }
}
