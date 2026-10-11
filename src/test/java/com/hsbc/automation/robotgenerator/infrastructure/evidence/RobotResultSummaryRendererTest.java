package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobotResultSummaryRendererTest {
    @TempDir
    Path temporaryDirectory;

    private final RobotResultSummaryRenderer renderer = new RobotResultSummaryRenderer();

    @Test
    void rendersRobotResultsAsPngAndIncludesBuildSummary() throws Exception {
        Path output = temporaryDirectory.resolve("summary.png");

        renderer.render(robotOutput("PASS", "1", "0", "0"), "Portal #42", output);

        BufferedImage image = ImageIO.read(output.toFile());
        assertTrue(Files.size(output) > 0);
        assertEquals(1200, image.getWidth());
        assertTrue(image.getHeight() > 150);
        assertTrue(renderer.summaryLines(robotOutput("PASS", "1", "0", "0"), "Portal #42")
                .contains("Total: 1 PASS / 0 FAIL / 0 SKIP"));
    }

    @Test
    void rejectsNonRobotXmlDoctypesAndUnsupportedTestStatuses() {
        assertThrows(IOException.class, () -> renderer.summaryLines("<not-robot/>", "Portal #42"));
        assertThrows(IOException.class, () -> renderer.summaryLines(
                "<!DOCTYPE robot [<!ENTITY x SYSTEM 'file:///secret'>]><robot>&x;</robot>", "Portal #42"));
        assertThrows(IOException.class, () -> renderer.summaryLines(robotOutput("UNKNOWN", "1", "0", "0"), "Portal #42"));
    }

    private String robotOutput(String status, String passed, String failed, String skipped) {
        return "<robot generated=\"2026-10-11T10:00:00\" name=\"Regression\">"
                + "<suite name=\"Health Checks\"><test name=\"Database status\">"
                + "<status status=\"" + status + "\" elapsed=\"1.2\" start=\"2026-10-11T10:00:00\"/>"
                + "</test></suite><statistics><total><stat pass=\"" + passed + "\" fail=\"" + failed
                + "\" skip=\"" + skipped + "\">All Tests</stat></total></statistics></robot>";
    }
}