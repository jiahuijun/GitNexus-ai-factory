package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 提示词构建器，为 {@link LlmGateway} 组装系统提示词与用户消息。
 *
 * <p>在拆解流水线中，由 {@link SpringAiLlmGateway} 在调用大模型前调用本类，
 * 将需求文本 + GitNexus 摸底结果拼装成结构化的用户消息，
 * 配合静态的 {@link #SYSTEM_PROMPT} 约束 LLM 的输出格式与拆解原则。</p>
 *
 * <p>{@link #SYSTEM_PROMPT} 规定了三条核心规则：
 * <ol>
 *   <li>输出 JSON 数组，字段含 stepName / targetSymbol / designDetail</li>
 *   <li>targetSymbol 必须来自摸底结果（具体类名/函数名/方法名，非文件名），不得凭空发明；摸底为空 → 输出空数组</li>
 *   <li>designDetail 必须包含产出物类名、方法签名、实现思路（伪代码）、依赖模块</li>
 * </ol>
 * 这保证下游可直接反序列化为 {@code List<TaskDraft>}。</p>
 *
 * @see SpringAiLlmGateway
 * @see LlmGateway.TaskDraft
 */
@Component
public class LlmPromptBuilder {

    /**
     * 系统提示词，约束 LLM 作为"任务拆解器"的角色、输入格式、输出规则与拆解原则。
     *
     * <p>关键规则：
     * <ul>
     *   <li>输出纯 JSON 数组，无 markdown 代码块标记，便于直接反序列化</li>
     *   <li>targetSymbol 必须从摸底结果符号列表中选取</li>
     *   <li>摸底结果为空时输出空数组 {@code []}</li>
     * </ul>
     */
    public static final String SYSTEM_PROMPT = """
        你是 AI Factory 的任务拆解器。你的职责:基于产品需求 + GitNexus 代码摸底结果,
        把需求拆成若干个可独立执行的开发任务草稿。

        # 输入
        - 需求:管理员提交的产品需求(自然语言)
        - 摸底结果:GitNexus query() 返回的相关符号列表 + 执行流名称

        # 输出规则
        1. 输出一个 JSON 数组,每个元素是一个任务草稿,字段:
           - stepName: 动词短语,描述这个任务做什么(如 "加getVipLevel方法")
           - targetSymbol: 真实符号名,**必须从摸底结果的符号列表中选取**,不得凭空发明。
             **必须是具体的类名、函数名或方法名,不能是文件名**(如应选 "UserService" 而非 "UserService.java")
           - designDetail: 详细设计方案,必须包含以下内容:
             * **产出物类名**: 要新建或修改的类名(如 `HealthCheckService`)
             * **方法名与签名**: 要新建或修改的方法名及完整签名(如 `public HealthStatus checkHealth()`)
             * **实现思路**: 用伪代码或编号步骤描述实现逻辑,具体到变量名、关键判断分支、返回值构造
             * **依赖模块**: 本实现依赖的其他类/模块(如 "依赖 RepoManager.getRepoList()")
        2. 拆解原则:
           - 每个任务改一个符号(类或方法),粒度小、可独立验证
           - 跨符号的需求拆成多个任务(如改 Service + 改 Controller = 两个任务)
           - 不要拆得过细(改一个方法的签名 + 改它的实现 = 一个任务)
           - 不输出与需求无关的任务(不要"加日志""加测试"等噪音)
        3. targetSymbol 必须是摸底结果里出现过的具体符号名(类名/函数名/方法名)。
           摸底结果为空 → 输出空数组 []。
        4. designDetail 要足够详细,让执行者可以直接按方案编码,不需要再做额外的设计决策。
        5. 只输出 JSON 数组,不要任何其他文字、解释、markdown 代码块标记。
        """;

    /**
     * 组装用户消息，将需求文本与摸底结果格式化后嵌入提示模板。
     *
     * <p>消息分三段：需求文本、相关符号列表（{@code name @ filePath}）、
     * 执行流名称（逗号分隔）。摸底为空时以"(无)"占位，
     * 提示 LLM 据此输出空数组。</p>
     *
     * @param requirement 管理员提交的产品需求文本
     * @param queryResult GitNexus {@code query()} 返回的摸底结果，提供符号列表与执行流名称
     * @return 组装好的用户消息字符串，供 {@code ChatClient.user()} 使用
     */
    public String buildUserMessage(String requirement, QueryResult queryResult) {
        // 将符号列表格式化为 "- name @ filePath" 多行文本，便于 LLM 识别
        String symbols = queryResult.symbols().stream()
            .map(s -> "- " + s.name() + " @ " + s.filePath())
            .collect(Collectors.joining("\n"));
        // 执行流名称用逗号拼接，空则占位
        String processes = String.join(", ", queryResult.processNames());

        return """
            需求: %s

            GitNexus 摸底结果:
            相关符号:
            %s

            执行流: %s

            请按系统指令输出任务草稿 JSON 数组。
            """.formatted(
                requirement,
                symbols.isBlank() ? "(无)" : symbols,
                processes.isBlank() ? "(无)" : processes
            );
    }
}
