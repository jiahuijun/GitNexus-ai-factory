package com.factory.ai.gitnexus;

import com.factory.ai.gitnexus.dto.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class GitNexusClientContractTest {

    private final GitNexusClient fake = new GitNexusClient() {
        public QueryResult query(String q, String r) { return new QueryResult(List.of(), List.of()); }
        public SymbolContext context(String n, String r) { return null; }
        public ImpactResult impact(String t, String d, String r) {
            return new ImpactResult(t, d, "LOW", java.util.Map.of());
        }
        public boolean detectChanges(String r) { return true; }
    };

    @Test
    void queryReturnsSymbolsAndProcesses() {
        var result = fake.query("BinaryLogClient", "repo");
        assertNotNull(result);
        assertTrue(result.symbols().isEmpty());
    }

    @Test
    void impactReturnsDirectDependentsEmptyByDefault() {
        var result = fake.impact("X", "upstream", "repo");
        assertTrue(result.directDependents().isEmpty());
    }

    @Test
    void detectChangesReturnsBoolean() {
        assertTrue(fake.detectChanges("repo"));
    }
}
