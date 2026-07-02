package com.factory.ai.task.service;

import com.factory.ai.task.domain.TaskStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * {@link TaskExecutionService} 单元测试。
 *
 * <p>纯 Mockito 单元测试（不加载 Spring 上下文），通过反射设置 {@code repoBasePath}
 * 为临时目录，验证文件写入与围栏剥离逻辑。</p>
 */
class TaskExecutionServiceTest {

    private TaskClaimService claimService;
    private TaskCompletionService completionService;
    private LlmGateway llm;
    private TaskExecutionService executionService;

    @BeforeEach
    void setUp() {
        claimService = mock(TaskClaimService.class);
        completionService = mock(TaskCompletionService.class);
        llm = mock(LlmGateway.class);
        executionService = new TaskExecutionService(claimService, completionService, llm);
    }

    @Test
    void executeWritesCodeAndCompletes(@TempDir Path tempDir) throws IOException {
        setRepoBasePath(tempDir.toString());

        TaskStep step = new TaskStep();
        step.setId(1L);
        step.setTargetFile("src/com/test/Generated.java");
        step.setGeneratedPrompt("do something");

        when(claimService.claim(anyLong(), anyLong())).thenReturn(step);
        when(llm.executeStep(any())).thenReturn("public class Generated {}");
        when(completionService.complete(anyLong(), anyLong(), any())).thenReturn(true);

        boolean result = executionService.execute(1L, 42L, "test-repo");

        assertTrue(result);
        Path written = tempDir.resolve("test-repo/src/com/test/Generated.java");
        assertTrue(Files.exists(written));
        assertEquals("public class Generated {}", Files.readString(written));
        verify(completionService).complete(1L, 42L, "test-repo");
    }

    @Test
    void executeReturnsFalseWhenClaimFails() {
        when(claimService.claim(anyLong(), anyLong())).thenReturn(null);

        boolean result = executionService.execute(1L, 42L, "test-repo");

        assertFalse(result);
        verifyNoInteractions(llm);
        verifyNoInteractions(completionService);
    }

    @Test
    void executeStripsCodeFences(@TempDir Path tempDir) throws IOException {
        setRepoBasePath(tempDir.toString());

        TaskStep step = new TaskStep();
        step.setId(2L);
        step.setTargetFile("src/Test.java");
        step.setGeneratedPrompt("prompt");

        when(claimService.claim(anyLong(), anyLong())).thenReturn(step);
        when(llm.executeStep(any())).thenReturn("```java\npublic class Test {}\n```");
        when(completionService.complete(anyLong(), anyLong(), any())).thenReturn(true);

        executionService.execute(2L, 42L, "repo");

        Path written = tempDir.resolve("repo/src/Test.java");
        assertEquals("public class Test {}", Files.readString(written));
    }

    @Test
    void executeRevertsToReadyWhenDetectChangesFails(@TempDir Path tempDir) {
        setRepoBasePath(tempDir.toString());

        TaskStep step = new TaskStep();
        step.setId(3L);
        step.setTargetFile("src/Fail.java");
        step.setGeneratedPrompt("prompt");

        when(claimService.claim(anyLong(), anyLong())).thenReturn(step);
        when(llm.executeStep(any())).thenReturn("code");
        // detectChanges 未通过 → complete 返回 false
        when(completionService.complete(anyLong(), anyLong(), any())).thenReturn(false);

        boolean result = executionService.execute(3L, 42L, "repo");

        assertFalse(result);
        // 应调用 revertClaim 回退为 READY
        verify(claimService).revertClaim(3L);
    }

    @Test
    void executeDoesNotRevertOnSuccess(@TempDir Path tempDir) {
        setRepoBasePath(tempDir.toString());

        TaskStep step = new TaskStep();
        step.setId(4L);
        step.setTargetFile("src/Ok.java");
        step.setGeneratedPrompt("prompt");

        when(claimService.claim(anyLong(), anyLong())).thenReturn(step);
        when(llm.executeStep(any())).thenReturn("code");
        when(completionService.complete(anyLong(), anyLong(), any())).thenReturn(true);

        executionService.execute(4L, 42L, "repo");

        // 成功时不应调用 revertClaim
        verify(claimService, never()).revertClaim(anyLong());
    }

    @Test
    void stripCodeFencesHandlesPlainCode() {
        assertEquals("public class A {}", TaskExecutionService.stripCodeFences("public class A {}"));
    }

    @Test
    void stripCodeFencesHandlesNull() {
        assertNull(TaskExecutionService.stripCodeFences(null));
    }

    @Test
    void stripCodeFencesHandlesEmpty() {
        assertEquals("", TaskExecutionService.stripCodeFences(""));
    }

    private void setRepoBasePath(String path) {
        try {
            var field = TaskExecutionService.class.getDeclaredField("repoBasePath");
            field.setAccessible(true);
            field.set(executionService, path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
