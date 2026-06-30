package com.factory.ai.task.repository;

import com.factory.ai.task.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class TaskStepRepositoryTest {

    @Autowired TaskStepRepository steps;
    @Autowired TaskRepository tasks;

    @Test
    void claimReadyTaskAtomicallyAssigns() {
        var task = tasks.save(new Task("req", 1L));
        var step = new TaskStep(task.getId(), "do X", "SomeSymbol");
        step.setStatus(TaskStepStatus.READY);
        steps.save(step);

        int affected = steps.claimReadyTask(step.getId(), 42L);
        assertEquals(1, affected, "READY task should be claimed");

        var updated = steps.findById(step.getId()).orElseThrow();
        assertEquals(TaskStepStatus.IN_PROGRESS, updated.getStatus());
        assertEquals(42L, updated.getAssigneeId());
    }

    @Test
    void claimFailsWhenNotReady() {
        var task = tasks.save(new Task("req", 1L));
        var step = new TaskStep(task.getId(), "do X", "SomeSymbol");
        step.setStatus(TaskStepStatus.PENDING);  // not ready
        steps.save(step);

        int affected = steps.claimReadyTask(step.getId(), 42L);
        assertEquals(0, affected, "PENDING task must not be claimable");
    }
}
