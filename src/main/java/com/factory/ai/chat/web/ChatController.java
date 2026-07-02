package com.factory.ai.chat.web;

import com.factory.ai.chat.service.ChatClarificationService;
import com.factory.ai.chat.web.dto.*;
import com.factory.ai.gitnexus.GitNexusException;
import com.factory.ai.task.service.LlmException;
import com.factory.ai.task.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/**
 * 对话式需求澄清 REST 控制器。
 *
 * <p>暴露 {@code /chat/sessions} 下的三个端点，实现交互式需求澄清流程：
 * <ul>
 *   <li>{@code POST /chat/sessions} — 开始会话：GitNexus 摸底 + LLM 第一个问题</li>
 *   <li>{@code POST /chat/sessions/{id}/messages} — 发送回答，获取下一个问题</li>
 *   <li>{@code POST /chat/sessions/{id}/decompose} — 确认拆解，生成任务</li>
 * </ul>
 *
 * <p>复用 TaskController 的"不降级"异常处理策略：
 * GitNexus / LLM 失败 → 503；会话不存在 → 404。</p>
 */
@RestController
@RequestMapping("/chat/sessions")
public class ChatController {

    private final ChatClarificationService service;

    public ChatController(ChatClarificationService service) {
        this.service = service;
    }

    /**
     * 开始澄清会话。
     *
     * @param req 包含需求、仓库名、管理员 ID
     * @return 会话 ID + 第一个澄清问题
     */
    @PostMapping
    public ResponseEntity<StartSessionResponse> start(@RequestBody StartSessionRequest req) {
        StartSessionResponse resp = service.start(req.requirement(), req.repo(), req.adminId());
        return ResponseEntity.ok(resp);
    }

    /**
     * 发送消息（用户回答），获取 LLM 的下一个问题。
     *
     * @param id  会话 ID
     * @param req 包含用户回答文本
     * @return LLM 回复 + ready 标志
     */
    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable String id, @RequestBody SendMessageRequest req) {
        try {
            MessageResponse resp = service.sendMessage(id, req.text());
            return ResponseEntity.ok(resp);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 确认拆解：用精炼需求调用拆解服务，生成任务。
     *
     * @param id 会话 ID
     * @return 生成的任务 ID
     */
    @PostMapping("/{id}/decompose")
    public ResponseEntity<DecomposeResponse> decompose(@PathVariable String id) {
        try {
            DecomposeResponse resp = service.decompose(id);
            return ResponseEntity.ok(resp);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 异常处理：GitNexus / LLM 失败 → 503。
     */
    @ExceptionHandler({GitNexusException.class, LlmException.class})
    public ResponseEntity<ErrorResponse> onUpstreamFailure(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse("UPSTREAM_UNAVAILABLE", e.getMessage()));
    }
}
