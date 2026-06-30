package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.TaskStep;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContextAggregationServiceTest {

    @Test
    void aggregatesContextAndPromptIntoStep() {
        GitNexusClient client = new GitNexusClient() {
            public QueryResult query(String q, String r) { return null; }
            public SymbolContext context(String n, String r) {
                return new SymbolContext("uid", n, "Class", "src/S.java", 1, 10,
                    "code", List.of(), List.of());
            }
            public ImpactResult impact(String t, String d, String r) {
                return new ImpactResult(t, d, "LOW", java.util.Map.of());
            }
            public boolean detectChanges(String r) { return true; }
        };
        var promptSvc = new PromptAssemblyService();
        var svc = new ContextAggregationService(client, promptSvc);

        var step = new TaskStep(1L, "do X", "UserService");
        step.setId(99L);
        svc.aggregate(step, "repo", "增加VIP");

        assertNotNull(step.getContextSnapshot());
        assertNotNull(step.getGeneratedPrompt());
        assertTrue(step.getGeneratedPrompt().contains("UserService"));
        assertTrue(step.getGeneratedPrompt().contains("complete_task(99)"));
    }
}
