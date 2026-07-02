package com.factory.ai.task.web.dto;

import java.util.List;

/**
 * 分页响应 DTO。
 *
 * @param items     当前页数据列表
 * @param total     总记录数
 * @param page      当前页码（从 1 开始）
 * @param size      每页条数
 * @param totalPages 总页数
 */
public record PageResponse<T>(List<T> items, long total, int page, int size, int totalPages) {
    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        int totalPages = (int) Math.ceil((double) total / size);
        return new PageResponse<>(items, total, page, size, totalPages);
    }
}
