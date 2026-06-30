package com.factory.ai.gitnexus.dto;

import java.util.List;

public record QueryResult(List<SymbolRef> symbols, List<String> processNames) {}
