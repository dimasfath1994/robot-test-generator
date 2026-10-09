package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
    public EvidenceScreenshotCapture(EvidenceHttpClient httpClient, Environment environment) { this.httpClient = httpClient; this.environment = environment; }

    public void capture(String html, Path screenshot) throws IOException {
        String grid = environment.getProperty("evidence.webdriver.url", "").trim().replaceAll("/+$", "");
        if (grid.isEmpty()) throw new IOException("evidence.webdriver.url is required for screenshot capture (Selenium Grid URL).");
        String browser = environment.getProperty("evidence.webdriver.browser", "edge").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"edge".equals(browser) && !"chrome".equals(browser)) throw new IOException("evidence.webdriver.browser must be either 'edge' or 'chrome'.");
        ObjectNode always = mapper.createObjectNode(); always.put("browserName", "edge".equals(browser) ? "MicrosoftEdge" : "chrome");
        ObjectNode options = always.putObject("edge".equals(browser) ? "ms:edgeOptions" : "goog:chromeOptions");
        options.putArray("args").add("--headless=new");
        ObjectNode capabilities = mapper.createObjectNode(); capabilities.set("alwaysMatch", always);
        ObjectNode payload = mapper.createObjectNode(); payload.putObject("capabilities").set("alwaysMatch", always);
        JsonNode created = call(grid + "/session", "POST", payload.toString());
        String id = created.path("value").path("sessionId").asText();
        if (id.isEmpty()) id = created.path("sessionId").asText();
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("Selenium Grid did not return a valid browser session.");
        String session = grid + "/session/" + id;
        IOException failure = null;
        try {
            call(session + "/window/rect", "POST", "{\"width\":1440,\"height\":1000}");
            call(session + "/url", "POST", "{\"url\":\"about:blank\"}");
            ObjectNode render = mapper.createObjectNode(); render.put("script", "document.open();document.write(arguments[0]);document.close();return true;"); render.putArray("args").add(html);
            call(session + "/execute/sync", "POST", render.toString());
            String encoded = call(session + "/screenshot", "GET", null).path("value").asText();
            byte[] png;
            try { png = Base64.getDecoder().decode(encoded); } catch (IllegalArgumentException exception) { throw new IOException("Selenium Grid returned an invalid screenshot."); }
            if (png.length < 8 || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G' || ImageIO.read(new ByteArrayInputStream(png)) == null) throw new IOException("Selenium Grid did not return a valid PNG image.");
            Files.write(screenshot, png);
        } catch (IOException exception) { failure = exception; throw exception; }
        finally {
            try { httpClient.webdriver(URI.create(session), "DELETE", null); }
            catch (IOException cleanup) { if (failure != null) failure.addSuppressed(cleanup); else throw cleanup; }
        }
    }
    private JsonNode call(String url, String method, String body) throws IOException {
        JsonNode response = mapper.readTree(httpClient.webdriver(URI.create(url), method, body));
        if (response == null || response.path("value").has("error")) throw new IOException("Selenium Grid could not complete " + method + " request.");
        return response;
    }
}
