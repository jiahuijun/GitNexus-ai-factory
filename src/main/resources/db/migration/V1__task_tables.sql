-- src/main/resources/db/migration/V1__task_tables.sql

CREATE TABLE `task` (
  `id`          bigint       NOT NULL AUTO_INCREMENT      COMMENT '主键，数据库自增 ID',
  `requirement` text         NOT NULL                     COMMENT '原始需求描述文本，由管理员提交',
  `status`      varchar(32)  DEFAULT 'DECOMPOSING'        COMMENT '任务整体状态：DECOMPOSING-分解中 / READY-就绪 / PARTIAL-部分完成 / DONE-完成 / CANCELLED-取消 / DECOMPOSING_FAILED-分解失败',
  `created_by`  bigint       NOT NULL                     COMMENT '创建人用户 ID',
  `created_at`  datetime     DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务表，表示一个父级需求（如"加VIP等级查询"），会被拆解为多个 task_step';

CREATE TABLE `task_step` (
  `id`               bigint       NOT NULL AUTO_INCREMENT  COMMENT '主键，数据库自增 ID',
  `task_id`          bigint       NOT NULL                 COMMENT '所属父任务 ID，关联 task.id',
  `step_name`        varchar(255) NOT NULL                 COMMENT '步骤名称，动词短语描述本步骤要做什么（如"加getVipLevel方法"）',
  `target_symbol`    varchar(255) NOT NULL                 COMMENT '目标代码符号（类名/函数名/方法名），用于定位修改位置',
  `target_file`      varchar(512)                          COMMENT '目标文件路径，由 GitNexus context() 回填，精确定位修改位置',
  `status`           varchar(32)  DEFAULT 'PENDING'        COMMENT '步骤状态：PENDING-等待前置依赖 / READY-可认领 / IN_PROGRESS-执行中 / DONE-完成 / CANCELLED-取消',
  `assignee_id`      bigint       DEFAULT NULL             COMMENT '认领人用户 ID（人或 AI 实例），认领时写入',
  `depends_on_count` int          DEFAULT 0                COMMENT '未完成的前置步骤数，归零时从 PENDING 跃迁为 READY',
  `version`          int          DEFAULT 0                COMMENT '乐观锁版本号，防止并发修改冲突，由 MyBatis-Plus OptimisticLockerInnerInterceptor 管理',
  `context_snapshot` mediumtext                            COMMENT '上下文快照，记录 GitNexus 拉取的符号源码、调用方等信息，供审计使用',
  `generated_prompt` mediumtext                            COMMENT 'AI 组装的执行提示词，含目标符号、源码、调用方、影响面、设计详情，指导具体执行',
  `reaggregated_at`  datetime     DEFAULT NULL             COMMENT '上下文重新聚合时间，前置步骤完成后后继步骤需重新拉取上下文',
  `needs_review`     tinyint      DEFAULT 0                COMMENT '是否需要人工复核，如检测到依赖环时置为 1',
  `created_at`       datetime     DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
  `updated_at`       datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_status` (`task_id`, `status`, `depends_on_count`),
  CONSTRAINT `fk_step_task` FOREIGN KEY (`task_id`) REFERENCES `task`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务步骤表，表示父任务拆解后的一个具体开发子任务，含状态机、乐观锁、AI 辅助字段';

CREATE TABLE `task_dependency` (
  `from_step_id` bigint NOT NULL COMMENT '前置步骤 ID（被依赖的步骤），关联 task_step.id',
  `to_step_id`   bigint NOT NULL COMMENT '后继步骤 ID（依赖前者的步骤），关联 task_step.id',
  PRIMARY KEY (`from_step_id`, `to_step_id`),
  CONSTRAINT `fk_dep_from` FOREIGN KEY (`from_step_id`) REFERENCES `task_step`(`id`),
  CONSTRAINT `fk_dep_to` FOREIGN KEY (`to_step_id`) REFERENCES `task_step`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖关系表，表示步骤间的 DAG 有向边（fromStepId→toStepId），from 完成后 to 的 dependsOnCount 递减';
