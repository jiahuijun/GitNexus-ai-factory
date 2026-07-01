package com.factory.ai.gitnexus.dto;

import java.util.List;

/**
 * {@link com.factory.ai.gitnexus.GitNexusClient#query} 方法的查询结果。
 *
 * <p>对应 MCP {@code query} 工具响应的扁平化投影：GitNexus 原生返回按执行流
 * （process）分组的符号，本 DTO 将所有执行流的符号合并为一个列表，同时保留
 * 命中的执行流名称列表，供 AI 决策“按哪条链路深入”时参考。
 *
 * @param symbols      所有命中执行流中的符号集合（已扁平化），按服务端相关性排序
 * @param processNames 命中的执行流（heuristicLabel）名称集合；为空表示未匹配到完整链路
 */
public record QueryResult(List<SymbolRef> symbols, List<String> processNames) {}
