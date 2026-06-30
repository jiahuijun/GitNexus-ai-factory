package com.factory.ai.task.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "task_dependency")
@IdClass(TaskDependency.PK.class)
public class TaskDependency {
    public static class PK implements java.io.Serializable {
        public Long fromStepId;
        public Long toStepId;
        public PK() {}
        public PK(Long f, Long t) { this.fromStepId = f; this.toStepId = t; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK p)) return false;
            return java.util.Objects.equals(fromStepId, p.fromStepId)
                && java.util.Objects.equals(toStepId, p.toStepId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(fromStepId, toStepId); }
    }
    @Id private Long fromStepId;
    @Id private Long toStepId;
    public TaskDependency() {}
    public TaskDependency(Long fromStepId, Long toStepId) {
        this.fromStepId = fromStepId; this.toStepId = toStepId;
    }
    public Long getFromStepId() { return fromStepId; }
    public Long getToStepId() { return toStepId; }
}
