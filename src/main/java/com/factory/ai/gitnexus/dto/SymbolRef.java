package com.factory.ai.gitnexus.dto;

import java.util.List;

/**
 * 代码符号引用（轻量引用，不含源码内容）。
 *
 * <p>用于在 {@link QueryResult}、{@link SymbolContext}、{@link ImpactResult} 中
 * 指向图谱中的某个符号，仅保留定位所需的最小信息：唯一 ID、名称、文件路径与行号区间。
 * 设计为不可变 record，便于在多线程 AI 编排链路中安全传递。
 *
 * @param uid        GitNexus 内部分配的符号唯一标识，跨仓库唯一；用于精确消歧
 * @param name       符号显示名称（函数/类/方法名）
 * @param filePath   符号所在文件绝对路径
 * @param startLine  符号起始行号（1-based）；可能为 {@code null} 表示未知
 * @param endLine    符号结束行号（1-based）；可能为 {@code null} 表示未知
 */
public record SymbolRef(String uid, String name, String filePath, Integer startLine, Integer endLine) {}
