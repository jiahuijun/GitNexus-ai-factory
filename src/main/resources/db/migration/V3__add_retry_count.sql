-- V3: Add retry_count column to task_step for retry limit tracking.
-- Each time a step fails (detectChanges not passed), retry_count is incremented.
-- When retry_count exceeds max_retries (default 3), the step is marked CANCELLED.
ALTER TABLE task_step ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
