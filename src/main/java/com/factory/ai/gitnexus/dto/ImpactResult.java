package com.factory.ai.gitnexus.dto;

import java.util.List;
import java.util.Map;

public record ImpactResult(
    String target, String direction, String risk,
    Map<Integer, List<SymbolRef>> byDepth   // depth=1 是直接受影响方
) {
    public List<SymbolRef> directDependents() { return byDepth.getOrDefault(1, List.of()); }
}
