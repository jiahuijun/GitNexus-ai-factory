package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.gitnexus.dto.SymbolRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class LlmPromptBuilder {

    public static final String SYSTEM_PROMPT = """
        你是 AI Factory 的任务拆解器。你的职责:基于产品需求 + GitNexus 代码摸底结果,
        把需求拆成若干个可独立执行的开发任务草稿。

        # 输入
        - 需求:管理员提交的产品需求(自然语言)
        - 摸底结果:GitNexus query() 返回的相关符号列表 + 执行流名称

        # 输出规则
        1. 输出一个 JSON 数组,每个元素是一个任务草稿,字段:
           - stepName: 动词短语,描述这个任务做什么(如 "加getVipLevel方法")
           - targetSymbol: 真实符号名,**必须从摸底结果的符号列表中选取**,不得凭空发明
           - instruction: 给执行员工的指令,简明扼要
        2. 拆解原则:
           - 每个任务改一个符号(类或方法),粒度小、可独立验证
           - 跨符号的需求拆成多个任务(如改 Service + 改 Controller = 两个任务)
           - 不要拆得过细(改一个方法的签名 + 改它的实现 = 一个任务)
           - 不输出与需求无关的任务(不要"加日志""加测试"等噪音)
        3. targetSymbol 必须是摸底结果里出现过的符号名。摸底结果为空 → 输出空数组 []。
        4. 只输出 JSON 数组,不要任何其他文字、解释、markdown 代码块标记。
        """;

    public String buildUserMessage(String requirement, QueryResult queryResult) {
        String symbols = queryResult.symbols().stream()
            .map(s -> "- " + s.name() + " @ " + s.filePath())
            .collect(Collectors.joining("\n"));
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
