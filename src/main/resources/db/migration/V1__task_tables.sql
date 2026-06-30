-- src/main/resources/db/migration/V1__task_tables.sql

CREATE TABLE `task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `requirement` text NOT NULL,
  `status` varchar(32) DEFAULT 'DECOMPOSING',
  `created_by` bigint NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `task_step` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `step_name` varchar(255) NOT NULL,
  `target_symbol` varchar(255) NOT NULL,
  `target_file` varchar(512),
  `status` varchar(32) DEFAULT 'PENDING',
  `assignee_id` bigint DEFAULT NULL,
  `depends_on_count` int DEFAULT 0,
  `version` int DEFAULT 0,
  `context_snapshot` mediumtext,
  `generated_prompt` mediumtext,
  `reaggregated_at` datetime DEFAULT NULL,
  `needs_review` tinyint DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_status` (`task_id`, `status`, `depends_on_count`),
  CONSTRAINT `fk_step_task` FOREIGN KEY (`task_id`) REFERENCES `task`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `task_dependency` (
  `from_step_id` bigint NOT NULL,
  `to_step_id` bigint NOT NULL,
  PRIMARY KEY (`from_step_id`, `to_step_id`),
  CONSTRAINT `fk_dep_from` FOREIGN KEY (`from_step_id`) REFERENCES `task_step`(`id`),
  CONSTRAINT `fk_dep_to` FOREIGN KEY (`to_step_id`) REFERENCES `task_step`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
