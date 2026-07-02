package com.factory.ai.task.integration;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.mapper.*;
import com.factory.ai.task.service.*;
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
class VipScenarioTest {

    @Autowired TaskDecompositionService decomp;
    @Autowired TaskClaimService claim;
    @Autowired TaskCompletionService complete;
    @Autowired TaskStepMapper steps;
    @Autowired TaskMapper tasks;

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
            return new LlmGateway() {
                @Override
                public List<TaskDraft> splitTasks(String req, QueryResult ctx) {
                    return List.of(
                        new LlmGateway.TaskDraft("加getVipLevel", "UserService", "产出物: UserService.getVipLevel(userId)\n签名: public VipLevel getVipLevel(Long userId)\n实现: 1. 查 user 表获取 level 字段 2. 映射为 VipLevel 枚举返回\n依赖: UserRepository.findById()"),
                        new LlmGateway.TaskDraft("加HTTP接口", "UserController", "产出物: UserController.getVipLevel()\n签名: @GetMapping(\"/vip/{userId}\") public VipLevel getVipLevel(@PathVariable Long userId)\n实现: 调用 UserService.getVipLevel() 返回结果\n依赖: UserService.getVipLevel()")
                    );
                }
                @Override
                public String executeStep(String prompt) {
                    return "// generated";
                }
                @Override
                public ClarifyReply clarify(String req, QueryResult ctx, List<com.factory.ai.chat.session.ChatMessage> history) {
                    return new ClarifyReply("mock", false, null);
                }
            };
        }
    }

    @Test
    void vipScenarioEndToEnd() {
        // 阶段一：拆解
        Long taskId = decomp.decompose("增加VIP等级查询", "repo", 1L);

        // A=UserService 应 READY, B=UserController 应 PENDING(dep=1)
        var ready = steps.findByTaskIdAndStatus(taskId, TaskStepStatus.READY.name());
        assertEquals(1, ready.size());
        var a = ready.get(0);
        assertEquals("UserService", a.getTargetSymbol());

        var pending = steps.findByTaskIdAndStatus(taskId, TaskStepStatus.PENDING.name());
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
        var bAfter = steps.selectById(b.getId());
        assertNotNull(bAfter);
        assertEquals(TaskStepStatus.READY, bAfter.getStatus());
        assertEquals(0, bAfter.getDependsOnCount());
        assertNotNull(bAfter.getReaggregatedAt());

        // 员工2 领 B
        var claimed2 = claim.claim(b.getId(), 200L);
        assertNotNull(claimed2);
        assertEquals(200L, claimed2.getAssigneeId());
    }
}
