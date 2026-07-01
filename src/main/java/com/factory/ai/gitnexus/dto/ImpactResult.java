package com.factory.ai.gitnexus.dto;

import java.util.List;
import java.util.Map;

/**
 * {@link com.factory.ai.gitnexus.GitNexusClient#impact} 方法的影响面分析结果。
 *
 * <p>对应 MCP {@code impact} 工具的响应，描述修改目标符号后的“爆炸半径”：
 * 按依赖深度分层列出受影响的符号，并给出整体风险等级。
 * 用于在改动共享代码前评估破坏范围、决定是否需要扩大测试覆盖。
 *
 * @param target    被分析的目标符号名称；缺失时回退为调用方传入值
 * @param direction 分析方向：{@code upstream}（谁依赖此符号）或 {@code downstream}（此符号依赖谁）；
 *                  缺失时回退为调用方传入值
 * @param risk      风险等级（LOW/MEDIUM/HIGH/CRITICAL）；服务端未返回时为 {@code "UNKNOWN"}
 * @param byDepth   按深度分组的受影响符号：{@code depth=1} 为直接依赖方（立即受影响），
 *                  {@code depth=2} 为间接依赖，{@code depth=3} 为传递性影响。键即深度值。
 */
public record ImpactResult(
    String target, String direction, String risk,
    Map<Integer, List<SymbolRef>> byDepth   // depth=1 是直接受影响方
) {
    /**
     * 获取直接受影响的符号列表（{@code depth=1}）。
     *
     * <p>这是最常被消费的层级——直接依赖方在目标符号变更后会立即受影响，
     * 通常需要优先回归测试。当服务端未返回 depth=1 时返回空列表而非 {@code null}。
     *
     * @return 直接依赖方列表；无数据时返回空列表
     */
    public List<SymbolRef> directDependents() {
        return byDepth.getOrDefault(1, List.of());
    }
}
