package com.factory.ai.gitnexus.dto;

import java.util.List;

public record SymbolContext(
    String uid, String name, String kind, String filePath,
    Integer startLine, Integer endLine,
    String sourceContent,            // include_content=true 的返回
    List<SymbolRef> incomingCalls,   // 谁调用本符号
    List<SymbolRef> outgoingMethods  // 本符号有哪些成员
) {}
