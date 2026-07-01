package com.factory.ai.task.integration;

import com.factory.ai.task.repository.*;
import com.factory.ai.task.service.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@Tag("smoke")
@SpringBootTest
@ActiveProfiles("smoke")
@EnabledIfEnvironmentVariable(named = "SMOKE", matches = "true")
class SmokeTest {

    @Autowired TaskDecompositionService decomp;
    @Autowired TaskRepository tasks;
    @Autowired TaskStepRepository steps;

    @Test
    void decomposeWithRealGitNexusAndLlm() {
        // 前提:GitNexus 在 localhost:4747 跑,索引了 GitNexus repo
        //       LLM 在 localhost:11434 跑,模型 qwen2.5:14b 已拉
        Long taskId = decomp.decompose("在 GitNexus 项目里加一个简单的工具方法", "GitNexus", 1L);

        var task = tasks.findById(taskId).orElseThrow();
        // 要么成功(READY,有 step),要么空草稿(DECOMPOSING_FAILED)
        assertTrue(
            task.getStatus().name().equals("READY") || task.getStatus().name().equals("DECOMPOSING_FAILED"),
            "task should be READY or DECOMPOSING_FAILED, got: " + task.getStatus()
        );

        if (task.getStatus().name().equals("READY")) {
            var taskSteps = steps.findAll().stream()
                .filter(s -> s.getTaskId().equals(taskId)).toList();
            assertFalse(taskSteps.isEmpty());
            assertNotNull(taskSteps.get(0).getGeneratedPrompt());
        }
    }
}
