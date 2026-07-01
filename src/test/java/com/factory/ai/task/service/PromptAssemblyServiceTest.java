package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.TaskStep;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PromptAssemblyServiceTest {

    @Test
    void assemblesPromptWithAllSections() {
        var step = new TaskStep(1L, "加getVipLevel", "UserService");
        step.setTargetFile("src/UserService.java");
        step.setDesignDetail("产出物: UserService.getVipLevel()\n签名: public VipLevel getVipLevel(Long userId)\n实现: 查 user.level 映射枚举");
        var ctx = new SymbolContext(
            "uid", "UserService", "Class", "src/UserService.java", 10, 100,
            "public class UserService { }",
            List.of(new SymbolRef("Class:p:UserController", "UserController", "p", 1, 50)),
            List.of()
        );
        var impact = new ImpactResult("UserService", "upstream", "LOW",
            java.util.Map.of(1, List.of(new SymbolRef("Class:p:UserController", "UserController", "p", 1, 50))));

        var svc = new PromptAssemblyService();
        String prompt = svc.assemble(step, ctx, impact, "增加VIP等级查询");

        assertTrue(prompt.contains("# Task"));
        assertTrue(prompt.contains("加getVipLevel"));
        assertTrue(prompt.contains("UserService"));
        assertTrue(prompt.contains("public class UserService"));
        assertTrue(prompt.contains("# Callers"));
        assertTrue(prompt.contains("UserController"));
        assertTrue(prompt.contains("# Blast Radius"));
        assertTrue(prompt.contains("# Design Detail"));
        assertTrue(prompt.contains("getVipLevel(Long userId)"));
        assertTrue(prompt.contains("complete_task"));
        assertTrue(prompt.contains("不要改未列入 Target 的文件"));
    }
}
