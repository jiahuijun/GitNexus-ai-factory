package com.factory.ai.task.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskStepStatusTest {

    @Test
    void containsAllFiveStates() {
        var values = TaskStepStatus.values();
        assertEquals(5, values.length);
        assertTrue(java.util.Arrays.asList(values).containsAll(
            java.util.List.of(
                TaskStepStatus.PENDING,
                TaskStepStatus.READY,
                TaskStepStatus.IN_PROGRESS,
                TaskStepStatus.DONE,
                TaskStepStatus.CANCELLED
            )
        ));
    }
}
