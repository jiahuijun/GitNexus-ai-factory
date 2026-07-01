package com.factory.ai.gitnexus.dto;

import java.util.List;

/**
 * 单个代码符号的 360 度上下文视图。
 *
 * <p>对应 MCP {@code context} 工具的响应，聚合了符号的定位信息、源码内容以及
 * 上下游引用关系，用于支撑 AI 在重构评估、bug 定位等场景下理解符号的全貌。
 *
 * @param uid            符号唯一标识；为空表示服务端未能消歧或符号不存在
 * @param name           符号显示名称；缺失时回退为调用方传入的 symbolName
 * @param kind           符号类型（Function/Class/Method 等）
 * @param filePath       符号所在文件路径
 * @param startLine      符号起始行号；{@code null} 表示未知
 * @param endLine        符号结束行号；{@code null} 表示未知
 * @param sourceContent  符号源码内容，仅当请求 {@code include_content=true} 时返回；否则为空字符串
 * @param incomingCalls  调用本符号的上游符号列表（“谁调用我”），用于评估被依赖情况
 * @param outgoingMethods 本符号的成员/被调用方法列表（“我有哪些成员/我调用谁”），
 *                        对类符号为方法成员，对函数符号为被调用函数
 */
public record SymbolContext(
    String uid, String name, String kind, String filePath,
    Integer startLine, Integer endLine,
    String sourceContent,            // include_content=true 的返回
    List<SymbolRef> incomingCalls,   // 谁调用本符号
    List<SymbolRef> outgoingMethods  // 本符号有哪些成员
) {}
