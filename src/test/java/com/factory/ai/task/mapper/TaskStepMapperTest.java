package com.factory.ai.task.mapper;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.service.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskStepMapperTest {

    @Autowired TaskStepMapper steps;
    @Autowired TaskMapper tasks;
    @MockBean GitNexusClient gitNexusClient;
    @MockBean LlmGateway llmGateway;

    @Test
    void claimReadyTaskAtomicallyAssigns() {
        var task = new Task("req", 1L);
        tasks.insert(task);

        var step = new TaskStep(task.getId(), "do X", "SomeSymbol");
        step.setStatus(TaskStepStatus.READY);
        steps.insert(step);

        int affected = steps.claimTask(step.getId(), 42L,
                TaskStepStatus.READY.name(), TaskStepStatus.IN_PROGRESS.name());
        assertEquals(1, affected, "READY task should be claimed");

        var updated = steps.selectById(step.getId());
        assertNotNull(updated);
        assertEquals(TaskStepStatus.IN_PROGRESS, updated.getStatus());
        assertEquals(42L, updated.getAssigneeId());
    }

    @Test
    void claimFailsWhenNotReady() {
        var task = new Task("req", 1L);
        tasks.insert(task);

        var step = new TaskStep(task.getId(), "do X", "SomeSymbol");
        step.setStatus(TaskStepStatus.PENDING);  // not ready
        steps.insert(step);

        int affected = steps.claimTask(step.getId(), 42L,
                TaskStepStatus.READY.name(), TaskStepStatus.IN_PROGRESS.name());
        assertEquals(0, affected, "PENDING task must not be claimable");
    }
}
