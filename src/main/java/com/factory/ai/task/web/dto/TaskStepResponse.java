package com.factory.ai.task.web.dto;

import com.factory.ai.task.domain.TaskStep;
import com.factory.ai.task.domain.TaskStepStatus;

public record TaskStepResponse(Long taskId, String stepName, String targetSymbol,
                               TaskStepStatus status, String instruction) {
    public static TaskStepResponse from(TaskStep s) {
        return new TaskStepResponse(s.getTaskId(), s.getStepName(), s.getTargetSymbol(),
            s.getStatus(), s.getGeneratedPrompt());
    }
}
