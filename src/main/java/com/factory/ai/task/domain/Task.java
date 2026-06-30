package com.factory.ai.task.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "task")
public class Task {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Lob private String requirement;
    @Enumerated(EnumType.STRING) private TaskStatus status = TaskStatus.DECOMPOSING;
    private Long createdBy;
    public Task() {}
    public Task(String requirement, Long createdBy) {
        this.requirement = requirement; this.createdBy = createdBy;
    }
    public Long getId() { return id; }
    public String getRequirement() { return requirement; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus s) { this.status = s; }
    public Long getCreatedBy() { return createdBy; }
}
