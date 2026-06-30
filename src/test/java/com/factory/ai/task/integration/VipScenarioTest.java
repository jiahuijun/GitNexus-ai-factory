package com.factory.ai.task.integration;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.repository.*;
import com.factory.ai.task.service.*;
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
class VipScenarioTest {

    @Autowired TaskDecompositionService decomp;
    @Autowired TaskClaimService claim;
    @Autowired TaskCompletionService complete;
    @Autowired TaskStepRepository steps;
    @Autowired TaskRepository tasks;

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
                    return new SymbolContext("uid", n, "Class", "src/" + n + ".java", 1, 50,
                        "code " + n, List.of(), List.of());
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
    void vipScenarioEndToEnd() {
        // 阶段一：拆解
        Long taskId = decomp.decompose("增加VIP等级查询", "repo", 1L);

        // A=UserService 应 READY, B=UserController 应 PENDING(dep=1)
        var ready = steps.findByTaskIdAndStatus(taskId, TaskStepStatus.READY);
        assertEquals(1, ready.size());
        var a = ready.get(0);
        assertEquals("UserService", a.getTargetSymbol());

        var pending = steps.findByTaskIdAndStatus(taskId, TaskStepStatus.PENDING);
        assertEquals(1, pending.size());
        var b = pending.get(0);
        assertEquals(1, b.getDependsOnCount());

        // 阶段二：员工1 领 A
        var claimed = claim.claim(a.getId(), 100L);
        assertNotNull(claimed);
        assertEquals(TaskStepStatus.IN_PROGRESS, claimed.getStatus());

        // 员工1 完成 A → B 解锁 + 重聚合
        boolean ok = complete.complete(a.getId(), 100L, "repo");
        assertTrue(ok);

        // B 现在 READY 且 prompt 已重聚合
        var bAfter = steps.findById(b.getId()).orElseThrow();
        assertEquals(TaskStepStatus.READY, bAfter.getStatus());
        assertEquals(0, bAfter.getDependsOnCount());
        assertNotNull(bAfter.getReaggregatedAt());

        // 员工2 领 B
        var claimed2 = claim.claim(b.getId(), 200L);
        assertNotNull(claimed2);
        assertEquals(200L, claimed2.getAssigneeId());
    }
}
