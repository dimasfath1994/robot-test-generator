package com.hsbc.automation.robotgenerator.application;

import java.sql.SQLException;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component 
@EnableScheduling 
@ConditionalOnProperty(name = "sync.h2.db", havingValue = "true")
public class H2GenerationWorker {
    private final H2GenerationBatchService batch;

    public H2GenerationWorker(H2GenerationBatchService batch) {
        this.batch = batch;
    }

    @Scheduled(initialDelayString = "${sync.h2.initial-delay-ms:1000}", fixedDelayString = "${sync.h2.poll-interval-ms:30000}")
    public void poll() {
        System.out.println("H2 worker checking database at " + Instant.now());
        try {
            H2GenerationBatchService.BatchResult result = batch.processActiveRequests();
            System.out.println("H2 worker check complete; completed=" + result.getCompleted()
                     + ", failed=" + result.getFailed() + ". Waiting for next polling cycle.");
        } catch (SQLException exception) {
            System.err.println("H2 worker database check failed; retrying on next cycle: " + exception.getMessage());
        }
    }
}