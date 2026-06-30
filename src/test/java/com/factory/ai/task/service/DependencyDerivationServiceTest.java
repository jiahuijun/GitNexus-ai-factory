package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.TaskStep;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DependencyDerivationServiceTest {

    @Test
    void noDependenciesWhenNoUpstreamOverlap() {
        GitNexusClient client = new GitNexusClient() {
            public QueryResult query(String q, String r) { return null; }
            public SymbolContext context(String n, String r) { return null; }
            public ImpactResult impact(String t, String d, String r) {
                return new ImpactResult("X", "upstream", "LOW", java.util.Map.of());
            }
            public boolean detectChanges(String r) { return true; }
        };
        var svc = new DependencyDerivationService(client);

        var a = new TaskStep(1L, "A", "ServiceA");
        var b = new TaskStep(1L, "B", "ServiceB");
        var edges = svc.derive(List.of(a, b), "repo");

        assertTrue(edges.isEmpty());
        assertEquals(0, a.getDependsOnCount());
        assertEquals(0, b.getDependsOnCount());
    }

    @Test
    void connectsEdgeWhenBDependsOnA() {
        GitNexusClient client = new GitNexusClient() {
            public com.factory.ai.gitnexus.dto.QueryResult query(String q, String r) { return null; }
            public com.factory.ai.gitnexus.dto.SymbolContext context(String n, String r) { return null; }
            public com.factory.ai.gitnexus.dto.ImpactResult impact(String t, String d, String r) {
                // A=ServiceA 的 upstream 含 ServiceB（B 调用 A）
                if (t.equals("ServiceA")) {
                    var ref = new com.factory.ai.gitnexus.dto.SymbolRef(
                        "Class:p:ServiceB", "ServiceB", "p", null, null);
                    return new com.factory.ai.gitnexus.dto.ImpactResult(
                        t, d, "LOW", java.util.Map.of(1, List.of(ref)));
                }
                return new com.factory.ai.gitnexus.dto.ImpactResult(t, d, "LOW", java.util.Map.of());
            }
            public boolean detectChanges(String r) { return true; }
        };
        var svc = new DependencyDerivationService(client);

        var a = new TaskStep(1L, "A", "ServiceA"); a.setId(10L);
        var b = new TaskStep(1L, "B", "ServiceB"); b.setId(20L);
        var edges = svc.derive(List.of(a, b), "repo");

        assertEquals(1, edges.size());
        assertEquals(10L, edges.get(0).getFromStepId());
        assertEquals(20L, edges.get(0).getToStepId());
        assertEquals(1, b.getDependsOnCount());
        assertEquals(0, a.getDependsOnCount());
    }
}
