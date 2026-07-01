package com.factory.ai.task.web;

import com.factory.ai.gitnexus.GitNexusException;
import com.factory.ai.task.service.LlmException;
import com.factory.ai.task.service.TaskClaimService;
import com.factory.ai.task.service.TaskCompletionService;
import com.factory.ai.task.service.TaskDecompositionService;
import com.factory.ai.task.web.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskDecompositionService decomp;
    private final TaskClaimService claim;
    private final TaskCompletionService complete;

    public TaskController(TaskDecompositionService decomp, TaskClaimService claim,
            TaskCompletionService complete) {
        this.decomp = decomp; this.claim = claim; this.complete = complete;
    }

    @PostMapping("/decompose")
    public ResponseEntity<Long> decompose(@RequestBody DecomposeRequest req) {
        return ResponseEntity.ok(decomp.decompose(req.requirement(), req.repo(), req.adminId()));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<TaskStepResponse> claim(@PathVariable Long id, @RequestBody ClaimRequest req) {
        var step = claim.claim(id, req.userId());
        return step != null ? ResponseEntity.ok(TaskStepResponse.from(step))
                            : ResponseEntity.status(409).build();
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Boolean> complete(@PathVariable Long id, @RequestBody CompleteRequest req) {
        try {
            boolean ok = complete.complete(id, req.userId(), req.repo());
            return ResponseEntity.ok(ok);
        } catch (NoSuchElementException e) {
            return ResponseEntity.ok(false);
        }
    }

    @ExceptionHandler({GitNexusException.class, LlmException.class})
    public ResponseEntity<ErrorResponse> onUpstreamFailure(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse("UPSTREAM_UNAVAILABLE", e.getMessage()));
    }
}
