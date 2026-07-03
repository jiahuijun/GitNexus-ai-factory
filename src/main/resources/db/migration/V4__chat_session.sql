-- V4: Chat session table for persistent clarification sessions.
-- Stores JSON-serialized QueryResult and ChatMessage history in TEXT columns.
CREATE TABLE `chat_session` (
  `session_id`           varchar(36)  NOT NULL                COMMENT 'UUID, app-generated (IdType.INPUT)',
  `original_requirement` text         NOT NULL                COMMENT '原始需求',
  `repo`                 varchar(255) NOT NULL                COMMENT '目标仓库名',
  `admin_id`             bigint       NOT NULL                COMMENT '管理员 ID',
  `query_result`         text                                 COMMENT 'JSON: QueryResult {symbols, processNames}',
  `history`              text                                 COMMENT 'JSON: List<ChatMessage> [{role, text}]',
  `state`                varchar(32)  DEFAULT 'CHAT'          COMMENT 'CHAT | DECOMPOSED',
  `task_id`              bigint       DEFAULT NULL            COMMENT '拆解后任务 ID',
  `refined_requirement`  text                                 COMMENT 'LLM 合成的精炼需求',
  `last_accessed_at`     datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '最后访问时间，用于 30 分钟 TTL',
  `created_at`           datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话澄清会话表';
