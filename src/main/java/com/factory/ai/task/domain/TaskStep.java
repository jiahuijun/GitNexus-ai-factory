package com.factory.ai.task.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "task_step")
public class TaskStep {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private String stepName;
    private String targetSymbol;
    private String targetFile;
    @Enumerated(EnumType.STRING) private TaskStepStatus status = TaskStepStatus.PENDING;
    private Long assigneeId;
    private int dependsOnCount;
    @Version private int version;
    @Lob private String contextSnapshot;
    @Lob private String generatedPrompt;
    private LocalDateTime reaggregatedAt;
    private boolean needsReview;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public TaskStep() {}
    public TaskStep(Long taskId, String stepName, String targetSymbol) {
        this.taskId = taskId; this.stepName = stepName; this.targetSymbol = targetSymbol;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTargetSymbol() { return targetSymbol; }
    public String getTargetFile() { return targetFile; }
    public void setTargetFile(String f) { this.targetFile = f; }
    public TaskStepStatus getStatus() { return status; }
    public void setStatus(TaskStepStatus s) { this.status = s; }
    public int getDependsOnCount() { return dependsOnCount; }
    public void setDependsOnCount(int c) { this.dependsOnCount = c; }
    public void decrementDependsOnCount() { this.dependsOnCount--; }
    public String getGeneratedPrompt() { return generatedPrompt; }
    public void setGeneratedPrompt(String p) { this.generatedPrompt = p; }
    public String getContextSnapshot() { return contextSnapshot; }
    public void setContextSnapshot(String c) { this.contextSnapshot = c; }
    public void setReaggregatedAt(LocalDateTime t) { this.reaggregatedAt = t; }
    public boolean isNeedsReview() { return needsReview; }
    public void setNeedsReview(boolean v) { this.needsReview = v; }
    public Long getTaskId() { return taskId; }
    public String getStepName() { return stepName; }
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long a) { this.assigneeId = a; }
}
