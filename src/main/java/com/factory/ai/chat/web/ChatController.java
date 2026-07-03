package com.factory.ai.chat.web;

import com.factory.ai.chat.service.ChatClarificationService;
import com.factory.ai.chat.web.dto.*;
import com.factory.ai.gitnexus.GitNexusException;
import com.factory.ai.task.service.LlmException;
import com.factory.ai.task.web.dto.ErrorResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/**
 * 对话式需求澄清 REST 控制器。
 *
 * <p>暴露 {@code /chat/sessions} 下的端点，实现交互式需求澄清流程：
 * <ul>
 *   <li>{@code POST /chat/sessions} — 开始会话：GitNexus 摸底 + LLM 第一个问题</li>
 *   <li>{@code GET /chat/sessions/{id}} — 获取会话状态（恢复对话）</li>
 *   <li>{@code POST /chat/sessions/{id}/messages} — 发送回答，获取下一个问题</li>
 *   <li>{@code POST /chat/sessions/{id}/preview} — 预览拆解结果（不入库）</li>
 *   <li>{@code POST /chat/sessions/{id}/decompose} — 确认拆解，生成任务</li>
 * </ul>
 *
 * <p>复用 TaskController 的"不降级"异常处理策略：
 * GitNexus / LLM 失败 → 503；会话不存在 → 404。</p>
 */
@RestController
@RequestMapping("/chat/sessions")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClarificationService service;

    public ChatController(ChatClarificationService service) {
        this.service = service;
    }

    /**
     * 开始澄清会话。
     */
    @PostMapping
    public ResponseEntity<StartSessionResponse> start(@Valid @RequestBody StartSessionRequest req) {
        StartSessionResponse resp = service.start(req.requirement(), req.repo(), req.adminId());
        return ResponseEntity.ok(resp);
    }

    /**
     * 获取会话状态（用于前端恢复对话界面）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<GetSessionResponse> getSession(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.getSession(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 发送消息（用户回答），获取 LLM 的下一个问题。
     */
    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable String id, @Valid @RequestBody SendMessageRequest req) {
        try {
            MessageResponse resp = service.sendMessage(id, req.text());
            return ResponseEntity.ok(resp);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 预览拆解结果：调用 LLM 生成任务草稿，不入库。
     */
    @PostMapping("/{id}/preview")
    public ResponseEntity<PreviewResponse> preview(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.preview(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 确认拆解：用精炼需求调用拆解服务，生成任务。
     * 可携带用户在预览中修改后的 drafts。
     */
    @PostMapping("/{id}/decompose")
    public ResponseEntity<DecomposeResponse> decompose(@PathVariable String id,
            @RequestBody(required = false) ConfirmDecomposeRequest req) {
        try {
            var drafts = req != null ? req.drafts() : null;
            DecomposeResponse resp = service.decompose(id, drafts);
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
        log.error("Upstream failure in chat controller: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse("UPSTREAM_UNAVAILABLE", e.getMessage()));
    }

    /**
     * 输入校验失败 → 400 Bad Request。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidationFailure(MethodArgumentNotValidException e) {
        StringBuilder sb = new StringBuilder();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(fe.getField()).append(": ").append(fe.getDefaultMessage());
        }
        log.warn("Validation failed in chat controller: {}", sb);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("VALIDATION_FAILED", sb.toString()));
    }
}
