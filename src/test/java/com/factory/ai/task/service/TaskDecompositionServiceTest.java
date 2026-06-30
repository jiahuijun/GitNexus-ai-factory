package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TaskDecompositionServiceTest {

    @Autowired TaskDecompositionService svc;
    @Autowired TaskStepRepository steps;
    @Autowired TaskDependencyRepository deps;

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary GitNexusClient gitNexus() {
            return new GitNexusClient() {
                public QueryResult query(String q, String r) {
                    var s1 = new SymbolRef("Class:p:UserService", "UserService", "p", 1, 100);
                    var s2 = new SymbolRef("Class:p:UserController", "UserController", "p", 1, 50);
                    return new QueryResult(List.of(s1, s2), List.of());
                }
                public SymbolContext context(String n, String r) {
                    return new SymbolContext("uid", n, "Class", "src/"+n+".java", 1, 50,
                        "code "+n, List.of(), List.of());
                }
                public ImpactResult impact(String t, String d, String r) {
                    if (t.equals("UserService")) {
                        var caller = new SymbolRef("Class:p:UserController", "UserController", "p", 1, 50);
                        return new ImpactResult(t, d, "LOW", java.util.Map.of(1, List.of(caller)));
                    }
                    return new ImpactResult(t, d, "LOW", java.util.Map.of());
                }
                public boolean detectChanges(String r) { return true; }
            };
        }
        @Bean @Primary LlmGateway llm() {
            return (req, ctx) -> List.of(
                new LlmGateway.TaskDraft("加getVipLevel", "UserService", "在UserService加getVipLevel方法"),
                new LlmGateway.TaskDraft("加HTTP接口", "UserController", "在UserController加VIP查询接口")
            );
        }
    }

    @Test
    void decomposeCreatesStepsWithDependencyAndContext() {
        Long taskId = svc.decompose("增加VIP等级查询", "repo", 1L);

        var taskSteps = steps.findByTaskIdAndStatus(taskId, TaskStepStatus.READY);
        assertEquals(1, taskSteps.size(), "UserService should be READY (no deps)");
        var a = taskSteps.get(0);
        assertEquals("UserService", a.getTargetSymbol());
        assertNotNull(a.getGeneratedPrompt());

        var pending = steps.findByTaskIdAndStatus(taskId, TaskStepStatus.PENDING);
        assertEquals(1, pending.size(), "UserController should be PENDING (depends on A)");
        assertEquals(1, pending.get(0).getDependsOnCount());

        assertEquals(1, deps.findAll().size(), "one dependency edge A→B");
    }
}
