package com.factory.ai.gitnexus.dto;

/**
 * 已索引仓库的摘要信息。
 *
 * <p>由 GitNexus {@code list_repos} MCP 工具返回，供前端渲染仓库选择下拉框。
 *
 * @param name 仓库名称（唯一标识，用于 API 调用中的 {@code repo} 参数）
 * @param path 仓库在服务器上的绝对路径
 */
public record RepoInfo(String name, String path) {}
