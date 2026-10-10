package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class EvidenceScreenshotCapture {

    private final EvidenceHttpClient httpClient;
    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();

    public EvidenceScreenshotCapture(EvidenceHttpClient httpClient, Environment environment) {
        this.httpClient = httpClient;
        this.environment = environment;
    }

    public void capture(String html, Path screenshot) throws IOException {
        String configuredGrid = environment.getProperty("evidence.webdriver.url", "").trim();
        if (configuredGrid.isEmpty()) {
            throw new IOException("evidence.webdriver.url is required for screenshot capture (Selenium Grid URL).");
        }
        String browser = selectedBrowser();
        String capabilities = sessionCapabilities(browser);
        String grid = configuredGrid.replaceAll("/+$", "");
        JsonNode gridStatus;
        try {
            gridStatus = mapper.readTree(httpClient.webdriver(URI.create(grid + "/status"), "GET", null));
        } catch (IOException exception) {
            captureLocally(html, screenshot, browser);
            return;
        }
        JsonNode ready = gridStatus == null ? null : gridStatus.path("value").get("ready");
        if (ready != null && !ready.asBoolean()) {
            captureLocally(html, screenshot, browser);
            return;
        }
        JsonNode created = call(grid + "/session", "POST", capabilities);
        String sessionId = created.path("value").path("sessionId").asText();
        if (sessionId.isEmpty()) {
            sessionId = created.path("sessionId").asText();
        }
        if (!sessionId.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("Selenium Grid did not return a valid browser session.");
        }
        String session = grid + "/session/" + sessionId;
        Exception failure = null;
        try {
            call(session + "/timeouts", "POST", "{\"script\":30000,\"pageLoad\":30000}");
            call(session + "/window/rect", "POST", "{\"width\":1440,\"height\":1000}");
            call(session + "/url", "POST", "{\"url\":\"about:blank\"}");
            ObjectNode render = mapper.createObjectNode();
            render.put("script", "document.open();document.write(arguments[0]);document.close();return true;");
            render.putArray("args").add(html);
            call(session + "/execute/sync", "POST", render.toString());
            ObjectNode wait = mapper.createObjectNode();
            wait.put("script", "var done=arguments[arguments.length-1],start=Date.now();"
                    + "(function ready(){var text=document.body.document.body.innerText.toLowerCase();"
                    + "if(text.indexOf('test statistics')>0){done(true);}"
                    + "else if(Date.now()-start>25000){done(false);}else{setTimeout(ready,100);}})();");
            wait.putArray("args");
            if (!call(session + "/execute/async", "POST", wait.toString()).path("value").asBoolean()) {
                throw new IOException("Robot log did not render test statistics in the browser.");
            }
            String encoded = call(session + "/screenshot", "GET", null).path("value").asText();
            byte[] png;
            try {
                png = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Selenium Grid returned an invalid screenshot.");
            }
            if (png.length < 8 || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
                if (ImageIO.read(new ByteArrayInputStream(png)) == null) {
                    throw new IOException("Selenium Grid did not return a valid PNG image.");
                }
            }
            Files.write(screenshot, png);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                call(session, "DELETE", null);
            } catch (IOException | RuntimeException cleanupFailure) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private String selectedBrowser() throws IOException {
        String browser = environment.getProperty("evidence.webdriver.browser", "edge")
                .trim().toLowerCase(Locale.ROOT);
        if (!"edge".equals(browser) && !"chrome".equals(browser)) {
            throw new IOException("evidence.webdriver.browser must be either 'edge' or 'chrome'.");
        }
        return browser;
    }

    private String sessionCapabilities(String browser) throws IOException {
        ObjectNode alwaysMatch = mapper.createObjectNode();
        ObjectNode options;
        if ("edge".equals(browser)) {
            alwaysMatch.put("browserName", "MicrosoftEdge");
            options = alwaysMatch.putObject("ms:edgeOptions");
        } else if ("chrome".equals(browser)) {
            alwaysMatch.put("browserName", "chrome");
            options = alwaysMatch.putObject("goog:chromeOptions");
        } else {
            throw new IOException("evidence.webdriver.browser must be either 'edge' or 'chrome'.");
        }
        options.putArray("args").add("--headless=new");
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.set("alwaysMatch", alwaysMatch);
        ObjectNode request = mapper.createObjectNode();
        request.set("capabilities", capabilities);
        return request.toString();
    }

    protected void captureLocally(String html, Path screenshot, String browser) throws IOException {
        String executable = localBrowserExecutable(browser);
        Path htmlFile = Files.createTempFile("robot-log-local-", ".html");
        Path processOutput = Files.createTempFile("robot-browser-local-", ".log");
        htmlFile.toFile().deleteOnExit();
        processOutput.toFile().deleteOnExit();
        Path profileDirectory = Files.createTempDirectory("robot-browser-profile-");
        profileDirectory.toFile().deleteOnExit();
        try {
            Files.write(htmlFile, html.getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(screenshot);
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.add("--headless");
            command.add("--disable-gpu");
            command.add("--disable-background-mode");
            command.add("--disable-extensions");
            command.add("--enable-logging=stderr");
            command.add("--v=1");
            command.add("--hide-scrollbars");
            command.add("--no-first-run");
            command.add("--no-default-browser-check");
            command.add("--allow-file-access-from-files");
            command.add("--user-data-dir=" + profileDirectory.toAbsolutePath());
            command.add("--window-size=1440,1000");
            command.add("--virtual-time-budget=5000");
            command.add("--screenshot=" + screenshot.toAbsolutePath());
            command.add(htmlFile.toUri().toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(processOutput.toFile()).start();
            boolean completed;
            try {
                completed = process.waitFor(60, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Local headless browser screenshot was interrupted.", exception);
            }
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("Local headless browser timed out after 60 seconds.");
            }
            try {
                validatePng(screenshot);
            } catch (IOException invalidScreenshot) {
                if (process.exitValue() != 0) {
                    throw new IOException("Local headless browser exited with code " + process.exitValue()
                            + " and did not produce a valid PNG." + browserOutput(processOutput), invalidScreenshot);
                }
                throw invalidScreenshot;
            }
        } finally {
            deleteTemporaryFile(htmlFile);
            deleteTemporaryFile(processOutput);
            try {
                deleteTree(profileDirectory);
            } catch (IOException exception) {
                profileDirectory.toFile().deleteOnExit();
            }
        }
    }

    private String browserOutput(Path output) {
        try {
            byte[] bytes = Files.readAllBytes(output);
            int start = Math.max(0, bytes.length - 4096);
            String message = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8).trim();
            return message.isEmpty() ? " No browser diagnostic output was produced." : " " + message;
        } catch (IOException exception) {
            return " Browser diagnostic output could not be read.";
        }
    }

    private void deleteTemporaryFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            file.toFile().deleteOnExit();
        }
    }

    private void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path path, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private String localBrowserExecutable(String browser) throws IOException {
        String configured = environment.getProperty("evidence.webdriver.local.executable", "").trim();
        if (!configured.isEmpty()) {
            if (Files.isRegularFile(Paths.get(configured))) {
                return configured;
            }
            throw new IOException("Configured local browser executable was not found; check evidence.webdriver.local.executable.");
        }
        String executableName = "edge".equals(browser) ? "msedge.exe" : "chrome.exe";
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String programFiles = System.getenv("ProgramFiles");
        String localAppData = System.getenv("LOCALAPPDATA");
        List<Path> candidates = new ArrayList<>();
        if ("edge".equals(browser)) {
            addCandidate(candidates, programFilesX86, "Microsoft/Edge/Application/msedge.exe");
            addCandidate(candidates, programFiles, "Microsoft/Edge/Application/msedge.exe");
            addCandidate(candidates, localAppData, "Microsoft/Edge/Application/msedge.exe");
        } else {
            addCandidate(candidates, programFilesX86, "Google/Chrome/Application/chrome.exe");
            addCandidate(candidates, programFiles, "Google/Chrome/Application/chrome.exe");
            addCandidate(candidates, localAppData, "Google/Chrome/Application/chrome.exe");
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String directory : pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                Path candidate = Paths.get(directory).resolve(executableName);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        throw new IOException("Selenium Grid is not ready and local " + browser
                + " was not found. Set evidence.webdriver.local.executable to its executable path.");
    }

    private void addCandidate(List<Path> candidates, String root, String relativePath) {
        if (root != null && !root.trim().isEmpty()) {
            candidates.add(Paths.get(root).resolve(relativePath));
        }
    }

    private void validatePng(Path screenshot) throws IOException {
        byte[] png = Files.readAllBytes(screenshot);
        if (png.length < 8 || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G'
                || ImageIO.read(new ByteArrayInputStream(png)) == null) {
            throw new IOException("Headless browser did not produce a valid PNG screenshot.");
        }
    }

    private JsonNode call(String url, String method, String body) throws IOException {
        JsonNode response = mapper.readTree(httpClient.webdriver(URI.create(url), method, body));
        if (response == null || response.path("value").has("error")) {
            throw new IOException("Selenium Grid could not complete " + method + " request.");
        }
        return response;
    }
}
