package com.factory.ai.task.service;

import com.factory.ai.task.domain.*;
import com.factory.ai.task.mapper.*;
import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskClaimServiceTest {

    @Autowired TaskClaimService svc;
    @Autowired TaskStepMapper steps;
    @Autowired TaskMapper tasks;

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary GitNexusClient gitNexus() {
            return new GitNexusClient() {
                public QueryResult query(String q, String r) { return new QueryResult(List.of(), List.of()); }
                public SymbolContext context(String n, String r) {
                    return new SymbolContext("u", n, "Class", "src/"+n+".java", 1, 10, "code", List.of(), List.of());
                }
                public ImpactResult impact(String t, String d, String r) {
                    return new ImpactResult(t, d, "LOW", java.util.Map.of());
                }
                public boolean detectChanges(String r) { return true; }
            };
        }
        @Bean @Primary LlmGateway llm() {
            return (req, ctx) -> List.of();
        }
    }

    @Test
    void claimReadyReturnsStepWithPrompt() {
        var task = new Task("req", 1L);
        tasks.insert(task);

        var step = new TaskStep(task.getId(), "do X", "Sym");
        step.setStatus(TaskStepStatus.READY);
        step.setGeneratedPrompt("PROMPT");
        steps.insert(step);

        var claimed = svc.claim(step.getId(), 7L);
        assertNotNull(claimed);
        assertEquals("PROMPT", claimed.getGeneratedPrompt());
        assertEquals(TaskStepStatus.IN_PROGRESS, claimed.getStatus());
    }

    @Test
    void claimNonReadyReturnsNull() {
        var task = new Task("req", 1L);
        tasks.insert(task);

        var step = new TaskStep(task.getId(), "do X", "Sym");
        step.setStatus(TaskStepStatus.PENDING);
        steps.insert(step);

        assertNull(svc.claim(step.getId(), 7L));
    }
}
