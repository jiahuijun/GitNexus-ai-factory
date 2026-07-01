package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LlmPromptBuilderTest {

    private final LlmPromptBuilder builder = new LlmPromptBuilder();

    @Test
    void buildsMessageWithRequirementAndSymbols() {
        var symbols = List.of(
            new SymbolRef("uid1", "UserService", "src/UserService.java", 1, 100),
            new SymbolRef("uid2", "UserController", "src/UserController.java", 1, 50)
        );
        var processes = List.of("UserAuth");
        var queryResult = new QueryResult(symbols, processes);

        String msg = builder.buildUserMessage("增加VIP等级查询", queryResult);

        assertTrue(msg.contains("增加VIP等级查询"));
        assertTrue(msg.contains("UserService"));
        assertTrue(msg.contains("UserController"));
        assertTrue(msg.contains("src/UserService.java"));
        assertTrue(msg.contains("UserAuth"));
    }

    @Test
    void handlesEmptyQueryResult() {
        var queryResult = new QueryResult(List.of(), List.of());
        String msg = builder.buildUserMessage("test requirement", queryResult);

        assertTrue(msg.contains("test requirement"));
        assertTrue(msg.contains("(无)"));  // 空符号列表显示"(无)"
    }
}
