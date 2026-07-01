-- src/main/resources/db/migration/V2__add_design_detail.sql

-- Add design_detail column to task_step, storing LLM-generated detailed design
-- (class names, method signatures, pseudocode, dependencies) for PromptAssemblyService.
-- Note: H2 does not support COMMENT in ALTER TABLE ADD COLUMN, so COMMENT is omitted here.
-- On MySQL, the column COMMENT was added separately via direct DDL.
ALTER TABLE `task_step` ADD COLUMN `design_detail` mediumtext AFTER `generated_prompt`;
