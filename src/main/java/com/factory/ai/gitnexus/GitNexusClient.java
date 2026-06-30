package com.factory.ai.gitnexus;

import com.factory.ai.gitnexus.dto.*;

public interface GitNexusClient {
    QueryResult query(String query, String repo);
    SymbolContext context(String symbolName, String repo);
    ImpactResult impact(String target, String direction, String repo);
    boolean detectChanges(String repo);
}
