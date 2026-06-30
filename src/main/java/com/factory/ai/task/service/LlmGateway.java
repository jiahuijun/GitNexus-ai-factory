package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import java.util.List;

public interface LlmGateway {
    /** 基于需求 + query 摸底结果，输出任务草稿列表 */
    List<TaskDraft> splitTasks(String requirement, QueryResult context);

    record TaskDraft(String stepName, String targetSymbol, String instruction) {}
}
