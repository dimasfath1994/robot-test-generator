package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class EvidenceHttpClientTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsNonPositiveTimeouts() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new EvidenceHttpClient(0, 1000, 1000));

        assertEquals("Evidence HTTP timeouts must be positive milliseconds.", exception.getMessage());
    }

    @Test
    void rejectsUnsafeAttachmentFilenameBeforeReadingOrSendingFile() {
        EvidenceHttpClient client = new EvidenceHttpClient(1000, 1000, 1000);

        IOException exception = assertThrows(IOException.class, () -> client.upload(
                URI.create("https://confluence.example/attachment"), temporaryDirectory.resolve("missing.png"),
                "../evidence.png", "user", "password"));

        assertEquals("Invalid evidence attachment filename.", exception.getMessage());
    }
}