package com.hsbc.automation.robotgenerator.application;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

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
import com.hsbc.automation.robotgenerator.infrastructure.evidence.RobotResultSummaryRenderer;


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
    public JenkinsEvidencePublicationService(EvidenceHttpClient http, EvidenceScreenshotCapture screenshots,
            Environment environment) {
        this(http, screenshots, environment, Clock.systemDefaultZone());
    }
    
    public JenkinsEvidencePublicationService(EvidenceHttpClient http, EvidenceScreenshotCapture screenshots,
            Environment environment, Clock clock) {
        this.http = http;
        this.screenshots = screenshots;
        this.environment = environment;
        this.clock = clock;
    }

    public String publish(GenerationRequest request, String suppliedBuildUrl, String suppliedGitUrl)
            throws IOException {
        String pat;
        try {
            pat = environment.getProperty("my.pat.token.confluence", "").trim();
        } catch (IllegalArgumentException exception) {
            throw new IOException("my.pat.token.confluence could not be resolved; check its environment variable.");
        }
        String username = pat.isEmpty() ? setting("my.username") : optionalCredential("my.username");
        String password = pat.isEmpty() ? setting("my.password") : optionalCredential("my.password");
        URI supplied = https(suppliedBuildUrl);
        String directArtifactUrl = null;
        URI build = supplied;
        String suppliedPath = supplied.getRawPath();
        int artifactIndex = suppliedPath.indexOf("/artifact/");
        if (artifactIndex >= 0) {
            if (!supplied.getPath().endsWith("/log.html")) {
                throw new IOException("Enter an archived Robot log URL ending in /log.html.");
            }
            for (String segment : suppliedPath.substring(artifactIndex).split("/")) {
                if ("..".equals(segment) || ".".equals(segment)) {
                    throw new IOException("Unsafe Jenkins artifact URL path.");
                }
            }
            directArtifactUrl = supplied.toString();
            build = URI.create(supplied.getScheme() + "://" + supplied.getRawAuthority()
                    + suppliedPath.substring(0, artifactIndex) + "/");
        }

        URI git = https(suppliedGitUrl);
        URI root = https(setting("confluence.url"));
        String buildUrl = build.toString().replaceAll("/+$", "") + "/";
        if (!build.getPath().matches(".*/job/.*/\\d+/?")) {
            throw new IOException("Enter a specific completed Jenkins build URL ending in </build-number/>.");
        }

        Matcher rootPage = CONFLUENCE_PAGE.matcher(root.getPath());
        if (!rootPage.matches()) {
            throw new IOException("confluence.url must identify a root page using /spaces/<space>/pages/<page-id>/title.");
        }

        String confluenceBase = root.getScheme() + "://" + root.getRawAuthority() + rootPage.group(1);
        String api = confluenceBase + "/rest/api/content";
        String rootId = rootPage.group(2);
        boolean githubPagesSource = "github-pages".equalsIgnoreCase(
                environment.getProperty("evidence.source", "published-log"));
        String configuredScreenshot = environment.getProperty("evidence.screenshot.url", "").trim();

        if (githubPagesSource && configuredScreenshot.isEmpty()) {
            throw new IOException("evidence.screenshot.url is required for the GitHub Pages screenshot source.");
        }

        String[] segments = build.getPath().replaceAll("/+$", "").split("/");
        String buildNumber = segments[segments.length - 1];
        if (directArtifactUrl != null && !githubPagesSource) {
            JsonNode metadata = read(jenkinsGet(URI.create(buildUrl
                    + "api/json?tree=number,building,result"), username, password));
            int metadataNumber = metadata.path("number").asInt();
            if (metadata.path("building").asBoolean(true) || metadataNumber < 1
                    || metadata.path("result").asText().isEmpty()) {
                throw new IOException("Jenkins build is not complete. Evidence can only be collected after Publish Results finishes.");
            }
            if (!String.valueOf(metadataNumber).equals(buildNumber)) {
                throw new IOException("Jenkins returned metadata for a different build.");
            }
        }

        String target = segments[segments.length - 2] + " #" + buildNumber;
        String gitUrl = git.toString();
        boolean artifactSource = !githubPagesSource && (directArtifactUrl != null
                || "jenkins-artifact".equalsIgnoreCase(environment.getProperty("evidence.source", "published-log")));
        String artifactReport = directArtifactUrl == null
                ? archivedLogUrl(buildUrl, username, password) : directArtifactUrl;
        JenkinsEvidence evidence = new JenkinsEvidence(target, buildUrl, gitUrl, "- Log: " + artifactReport);
        URI report = https(evidence.getLogUrl());
        if (!sameOrigin(report, git) && !sameOrigin(report, build)) {
            throw new IOException("Published log host must match the Git or Jenkins origin before credentials can be sent.");
        }
        if (githubPagesSource && (!sameOrigin(report, build)
            || !report.getRawPath().startsWith(build.getRawPath()))) {
                throw new IOException("The Report URL must be a Jenkins log.html URL belonging to the selected build.");
        }

        URI source = report;
        System.out.println("Confluence Report URL: " + report);

        String html = null;
        if (githubPagesSource) {
            URI screenshotSource = https(configuredScreenshot);
            if (!sameOrigin(screenshotSource, git)) {
                throw new IOException("GitHub Pages screenshot URL must match the configured Git origin.");
            }
            try {
                html = robotHtml(screenshotSource, username, password);
            } catch (IOException exception) {
                System.out.println("Warning: GitHub Pages log could not be downloaded; uploading links without HTML or screenshot. "
                        + exception.getMessage());
            }
        } else {
            html = artifactSource ? jenkinsRobotHtml(source, username, password)
                    : logHtml(source, buildUrl, username, password);
        }

        Path screenshot = Files.createTempFile("robot-jenkins-evidence-", ".png");
        Path htmlFile = null;
        String imageMode = environment.getProperty("evidence.image.mode", "screenshot").trim();
        boolean summaryMode = "robot-summary".equalsIgnoreCase(imageMode);
        if (!summaryMode && !"screenshot".equalsIgnoreCase(imageMode)) {
            Files.deleteIfExists(screenshot);
            throw new IOException("evidence.image.mode must be 'screenshot' or 'robot-summary'.");
        }
        try {
            boolean screenshotAvailable = false;
            if (html != null) {
                htmlFile = Files.createTempFile("robot-jenkins-log-", ".html");
                Files.write(htmlFile, html.getBytes(StandardCharsets.UTF_8));
                try {
                    if (summaryMode) {
                        URI outputXml = report.resolve("output.xml");
                        String xml = jenkinsGet(outputXml, username, password);
                        new RobotResultSummaryRenderer().render(xml, target, screenshot);
                        System.out.println("Regression Test Summary generated from " + outputXml);
                    }
                    else {
                        screenshots.capture(html, screenshot);
                    }
                    screenshotAvailable = true;
                } catch (IOException | RuntimeException exception) {
                    System.out.println("Warning: evidence image generation failed; continuing evidence upload without image." 
                        + exception.getMessage());
                }
            }

            JsonNode rootMetadata = read(http.get(URI.create(api + "/" + rootId + "?expand=space"), username, password));
            String space = rootMetadata.path("space").path("key").asText();
            if (space.isEmpty()) {
                throw new IOException("Confluence did not return the root page's space key.");
            }

            String parentTitle = request.getProjectName() + " Health Check";
            JsonNode parent = findChild(api, rootId, parentTitle, username, password);
            if (parent == null) {
                parent = save(api, null, rootId, space, parentTitle, "<p>Health check evidence.</p>", username, password);
            }

            String parentId = contentId(parent);
            String childTitle = parentTitle + " " + LocalDate.now(clock);
            JsonNode child = findChild(api, parentId, childTitle, username, password);
            child = save(api, child, parentId, space, childTitle, storage(evidence, screenshotAvailable, summaryMode), username, password);
            String childId = contentId(child);
            if (htmlFile != null) {
                uploadAttachment(api, childId, htmlFile, HTML_ATTACHMENT, username, password);
            }

            if (screenshotAvailable) {
                uploadAttachment(api, childId, screenshot, ATTACHMENT, username, password);
            }

            return confluenceBase + "/pages/viewpage.action?pageId=" + childId;
        } finally {
            Files.deleteIfExists(screenshot);
            if (htmlFile != null) {
                Files.deleteIfExists(htmlFile);
            }
        }
    }

    private void uploadAttachment(String api, String childId, Path file, String filename,
            String username, String password) throws IOException {
        String base = api + "/" + childId + "/child/attachment";
        JsonNode attachments = read(http.get(URI.create(base + "?filename="
                + URLEncoder.encode(filename, "UTF-8")), username, password));
        String uploadUrl = base;
        if (attachments.path("results").size() > 0) {
            uploadUrl += "/" + attachments.path("results").get(0).path("id") + "/data";
        }
        http.upload(URI.create(uploadUrl), file, filename, username, password);
    }

    private String robotHtml(URI log, String username, String password) throws IOException {
        String html = http.get(log, username, password);
        if (!html.contains("Robot Framework")) {
            throw new IOException("Log URL did not return a Robot Framework log (possibly a login page).");
        }
        return html;
    }

    private String jenkinsGet(URI url, String username, String password) throws IOException {
        String token;
        try {
            token = environment.getProperty("jenkins.api.token", "").trim();
        } catch (IllegalArgumentException exception) {
            throw new IOException("jenkins.api.token could not be resolved; check its environment variable.");
        }
        if (!token.isEmpty()) {
            return http.get(url, setting("my.username"), token);
        }
        return http.get(url, username, password);
    }

    private String jenkinsRobotHtml(URI url, String username, String password) throws IOException {
        String html = jenkinsGet(url, username, password);
        if (!html.contains("Robot Framework")) {
            throw new IOException("Jenkins artifact did not return a Robot Framework log (possibly a login page).");
        }
        return html;
    }

    private String logHtml(URI publishedLog, String buildUrl, String username, String password) throws IOException {
        IOException publishedFailure;
        try {
            String html = sameOrigin(publishedLog, URI.create(buildUrl))
                    ? jenkinsGet(publishedLog, username, password)
                    : http.get(publishedLog, username, password);
            if (!html.contains("Robot Framework")) {
                throw new IOException("Published URL returned a non-Robot page, possibly a login page.");
            }
            return html;
        } catch (IOException exception) {
            publishedFailure = exception;
        }
        try {
            return jenkinsRobotHtml(URI.create(archivedLogUrl(buildUrl, username, password)), username, password);
        } catch (IOException exception) {
            throw new IOException("Published Robot log could not be read: " + publishedFailure.getMessage()
                                + " Jenkins artifact fallback failed: " + exception.getMessage(), exception);
        }
    }

    private String archivedLogUrl(String buildUrl, String username, String password) throws IOException {
       try {
            JsonNode metadata = read(jenkinsGet(URI.create(buildUrl
                    + "api/json?tree=artifacts%5BfileName%2CrelativePath%5D"), username, password));
            List<String> candidates = new ArrayList<>();
            for (JsonNode artifact : metadata.path("artifacts")) {
                String relativePath = artifact.path("relativePath").asText();
                if ("log.html".equals(artifact.path("fileName").asText())) {
                    candidates.add(relativePath);
                }
            }
            String relativePath;
            if (candidates.contains("log.html")) {
                relativePath = "log.html";
            } else if (candidates.size() == 1) {
                relativePath = candidates.get(0);
            } else {
                throw new IOException("Jenkins has no unique archived log.html. Archive the Robot log in this build"
                        + " or enable approved GitHub Pages API authentication.");
            }
            if (relativePath.isEmpty() || relativePath.startsWith("/") || relativePath.contains("\\")
                        || relativePath.contains(":")) {
                throw new IOException("Jenkins returned an invalid log artifact path.");
            }
            for (String segment : relativePath.split("/")) {
                if ("..".equals(segment) || ".".equals(segment)) {
                    throw new IOException("Unsafe Jenkins artifact URL path.");
                }
            }
            String encodedPath = new URI (null,null,  "/artifact/" + relativePath, null).toASCIIString();
            return buildUrl + encodedPath.substring(1);
        } catch (URISyntaxException exception) {
            throw new IOException("Jenkins returned an invalid artifact path.", exception);
        }   
    }

    private JsonNode findChild(String api, String parentId, String title, String username, String password)
            throws IOException {
        for (int start = 0; start < 10000; start += 100) {
            JsonNode response = read(http.get(URI.create(api + "/" + parentId
                    + "/child/page?expand=version&limit=100&start=" + start), username, password));
            JsonNode results = response.path("results");
            for (JsonNode page : results) {
                if (title.equalsIgnoreCase(page.path("title").asText().trim())) {
                    return page;
                }
            }
            if (results.size() < 100) {
                return null;
            }
        }
        throw new IOException("Confluence child-page listing exceeded the supported pagination limit.");
    }

    private JsonNode save(String api, JsonNode existing, String parentId, String space, String title,
            String storage, String username, String password) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "page");
        body.put("title", title);
        body.putObject("space").put("key", space);
        body.putArray("ancestors").addObject().put("id", parentId);
        ObjectNode content = body.putObject("body").putObject("storage");
        content.put("value", storage);
        content.put("representation", "storage");
        String url = api;
        String method = "POST";
        if (existing != null) {
            String id = contentId(existing);
            int version = existing.path("version").path("number").asInt();
            if (version < 1) {
                throw new IOException("Confluence did not return the version required to update the evidence page.");
            }
            body.put("id", id);
            body.putObject("version").put("number", version + 1);
            url += "/" + id;
            method = "PUT";
        }
        return read(http.json(URI.create(url), method, body.toString(), username, password));
    }

    private String storage(JenkinsEvidence evidence, boolean screenshotAvailable, boolean summaryMode) throws IOException {
        StringWriter result = new StringWriter();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(result);
            paragraph(xml, "Target", null, evidence.getTarget());
            paragraph(xml, "Build URL", evidence.getBuildUrl(), evidence.getBuildUrl());
            paragraph(xml, "Git", evidence.getGitUrl(), evidence.getGitUrl());
            paragraph(xml, "Report", evidence.getLogUrl(), "TS01 TC01 Log");
            if (screenshotAvailable) {
                if (summaryMode) {
                    xml.writeStartElement("p");
                    xml.writeCharacters("Regression Test Summary (from output.xml; not an application screenshot)");
                    xml.writeEndElement();
                }
                xml.writeStartElement("ac", "image", "http://atlassian.com/content");
                xml.writeNamespace("ac", "http://atlassian.com/content");
                xml.writeNamespace("ri", "http://atlassian.com/resource/identifier");
                xml.writeAttribute("ac", "http://atlassian.com/content", "width", "1200");
                xml.writeEmptyElement("ri", "attachment", "http://atlassian.com/resource/identifier");
                xml.writeAttribute("ri", "http://atlassian.com/resource/identifier", "filename", ATTACHMENT);
                xml.writeEndElement();
            }
            xml.close();
            return result.toString();
        } catch (XMLStreamException exception) {
            throw new IOException("Could not render Confluence evidence content.", exception);
        }
    }

    private void paragraph(XMLStreamWriter xml, String label, String url, String text) throws XMLStreamException {
        xml.writeStartElement("p");
        xml.writeStartElement("strong");
        xml.writeCharacters(label + ": ");
        xml.writeEndElement();
        if (url != null) {
            xml.writeStartElement("a");
            xml.writeAttribute("href", url);
        }
        xml.writeCharacters(text);
        if (url != null) {
            xml.writeEndElement();
        }
        xml.writeEndElement();
    }

    private String optionalCredential(String name) {
        try {
            return environment.getProperty(name, "");
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private String setting(String name) throws IOException {
        String value;
        try {
            value = environment.getProperty(name, "");
        } catch (IllegalArgumentException exception) {
            throw new IOException(name + " is not configured or its environment variable is missing.");
        }
        if (value.trim().isEmpty()) {
            throw new IOException(name + " is required for evidence publication.");
        }
        return value;
    }

    private URI https(String value) throws IOException {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Evidence URLs must be valid HTTPS URLs.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IOException("Evidence URLs must use HTTPS without embedded credentials, queries, or fragments.");
        }
        return uri;
    }

    private boolean sameOrigin(URI first, URI second) {
        int firstPort = first.getPort() == -1 ? 443 : first.getPort();
        int secondPort = second.getPort() == -1 ? 443 : second.getPort();
        return first.getHost().equalsIgnoreCase(second.getHost()) && firstPort == secondPort;
    }

    private String contentId(JsonNode content) throws IOException {
        String id = content.path("id").asText();
        if (!id.matches("\\d+")) {
            throw new IOException("Confluence returned an invalid page or attachment ID.");
        }
        return id;
    }

    private JsonNode read(String json) throws IOException {
        JsonNode node = mapper.readTree(json);
        if (node == null) {
            throw new IOException("Evidence API returned an empty JSON response.");
        }
        return node;
    }
}
