package com.factory.ai.gitnexus;

import com.factory.ai.gitnexus.dto.*;

import java.util.List;

/**
 * GitNexus 代码知识图谱客户端接口。
 *
 * <p>该接口是 AI Factory 系统访问 GitNexus 知识图谱的统一入口。AI Factory 在
 * 将产品需求拆解为开发任务的过程中，需要通过此接口查询代码符号之间的调用关系、
 * 上下文信息以及变更影响面，从而辅助理解代码结构与依赖关系。
 *
 * <p>实现方通过 MCP（Model Context Protocol）协议与 GitNexus 服务通信，
 * 4 个方法分别对应 4 个 MCP 工具：{@code query}、{@code context}、{@code impact}
 * 和 {@code detect_changes}。当调用失败时，实现应抛出 {@link GitNexusException}
 * 以保证“快速失败”语义——不做降级处理，让上层决策能感知到知识图谱不可用。
 *
 * @see GitNexusException
 */
public interface GitNexusClient {

    /**
     * 按自然语言/关键词查询代码知识图谱中相关的执行流（call chain）。
     *
     * <p>对应 MCP 工具 {@code query}：返回按相关性排序的执行流，每个执行流包含
     * 一组符号及其文件位置。本接口将多个执行流的符号扁平化为一个列表返回。
     *
     * @param query 查询语句（自然语言或关键词），描述想要定位的代码逻辑
     * @param repo  目标仓库名或路径；为空时由服务端决定默认仓库
     * @return 命中的符号列表与对应的执行流（process）名称集合
     * @throws GitNexusException 当 MCP 调用失败或服务返回错误时抛出
     */
    QueryResult query(String query, String repo);

    /**
     * 获取某个代码符号的 360 度上下文视图：调用方、被调用方、所在文件位置等。
     *
     * <p>对应 MCP 工具 {@code context}：用于在确定某个符号后深入了解其依赖关系，
     * 例如在评估重构影响、定位 bug 调用链时使用。
     *
     * @param symbolName 符号名称（函数/类/方法名），用于在图谱中定位；同名符号可能
     *                   存在歧义，由服务端做消歧
     * @param repo       目标仓库名或路径
     * @return 该符号的完整上下文，包含源码内容（当请求 {@code include_content=true}）
     * @throws GitNexusException 当 MCP 调用失败、符号不存在或服务返回错误时抛出
     */
    SymbolContext context(String symbolName, String repo);

    /**
     * 分析修改某个符号后的影响面（blast radius）。
     *
     * <p>对应 MCP 工具 {@code impact}：返回按深度分层的受影响符号列表，
     * {@code depth=1} 为直接依赖方（会立即受影响），{@code depth=2/3} 为间接依赖。
     * 用于在改动共享代码前评估破坏范围。
     *
     * @param target    目标符号名称
     * @param direction 方向：{@code upstream}（谁依赖此符号）或 {@code downstream}（此符号依赖谁）
     * @param repo      目标仓库名或路径
     * @return 影响结果，包含风险等级与按深度分组的受影响符号
     * @throws GitNexusException 当 MCP 调用失败或服务返回错误时抛出
     */
    ImpactResult impact(String target, String direction, String repo);

    /**
     * 检测仓库中是否存在未提交的代码变更（受 GitNexus 索引范围限制）。
     *
     * <p>对应 MCP 工具 {@code detect_changes}：将 git diff 映射到已索引的符号，
     * 用于在提交前评估变更影响哪些执行流。本方法仅返回是否存在变更的布尔值，
     * 详细变更列表需调用 MCP 原始接口获取。
     *
     * @param repo 目标仓库名或路径
     * @return {@code true} 表示存在已索引的变更符号；{@code false} 表示无变更或未索引
     * @throws GitNexusException 当 MCP 调用失败或服务返回错误时抛出
     */
    boolean detectChanges(String repo);

    /**
     * 列出 GitNexus 中所有已索引的仓库。
     *
     * <p>对应 MCP 工具 {@code list_repos}：返回仓库名称与路径列表，
     * 供前端渲染仓库选择下拉框。该方法为 {@code default} 实现，
     * 返回空列表——测试桩无需覆写。</p>
     *
     * @return 已索引仓库列表；无仓库或未实现时返回空列表
     */
    default List<RepoInfo> listRepos() { return List.of(); }
}
