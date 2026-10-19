package com.enterprise.agentharness.run;

import java.util.List;

public record RunPageResponse(List<RunResponse> items, int page, int size, long totalElements, long totalPages) {}
