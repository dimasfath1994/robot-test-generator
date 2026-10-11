package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class RobotResultSummaryRenderer {
    public void render(String xml, String target, Path destination) throws IOException {
        List<String> lines = summaryLines(xml, target);
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D measuring = measure.createGraphics();
        List<String> wrapped = new ArrayList<>();
        try {
            measuring.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            FontMetrics metrics = measuring.getFontMetrics();
            for (String line : lines) {
                String remaining = line;
                while (metrics.stringWidth(remaining) > 1112) {
                    int end = remaining.length();
                    while (metrics.stringWidth(remaining.substring(0, end)) > 1112) {
                        end--;
                    }
                    wrapped.add(remaining.substring(0, end));
                    remaining = remaining.substring(end);
                }
                wrapped.add(remaining);
            }
        } finally {
            measuring.dispose();
        }
        if (wrapped.size() > 1000) {
            throw new IOException("Robot summary is too large for one evidence image.");
        }
        BufferedImage image = new BufferedImage(1200, 150 + wrapped.size() * 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(30, 90, 65));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            graphics.drawString("Regression Test Summary", 44, 49);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            graphics.setColor(new Color(32, 32, 32));
            int baseline = 116;
            for (String line : wrapped) {
                graphics.drawString(line, 44, baseline);
                baseline += 32;
            }
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", destination.toFile())) {
            throw new IOException("Could not write Robot summary PNG.");
        }
    }

    List<String> summaryLines(String xml, String target) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            if (!"robot".equals(root.getTagName())) {
                throw new IOException("Artifact is not a Robot Framework output.xml");
            }
            List<String> lines = new ArrayList<>();
            lines.add("Target: " + target);
            lines.add("Generated: " + root.getAttribute("generated") + " (as recorded in output.xml)");
            lines.add("Source: Jenkins output.xml; not an application screenshot");
            Element statistics = child(root, "statistics");
            Element total = child(child(statistics, "total"), "stat");
            lines.add("Total: " + total.getAttribute("pass") + " PASS / "
                   + total.getAttribute("fail") + " FAIL / " + total.getAttribute("skip") + " SKIP");
            NodeList tests = root.getElementsByTagName("test");
            if (tests.getLength() == 0 || tests.getLength() > 1000) {
                throw new IOException("Robot summary requires between 1 and 1000 tests");
            }
            for (int index = 0; index < tests.getLength(); index++) {
                Element test = (Element) tests.item(index);
                Element status = child(test, "status");
                String result = status.getAttribute("status");
                if (!"PASS".equals(result) && !"FAIL".equals(result) && !"SKIP".equals(result)) {
                    throw new IOException("Robot test has an unsupported status.");
                }
                lines.add("");
                lines.add("Suite: " + ((Element) test.getParentNode()).getAttribute("name"));
                lines.add("Test: " + test.getAttribute("name"));
                lines.add("Status: " + result + " | Elapsed: " + status.getAttribute("elapsed") + " seconds");
                lines.add("Start: " + status.getAttribute("start"));
            }
            return lines;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not parse Robot Framework output.xml", exception);
        }
    }

    private Element child(Element parent, String name) throws IOException {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        throw new IOException("Robot output.xml is missing " + name + ".");
    }
}
