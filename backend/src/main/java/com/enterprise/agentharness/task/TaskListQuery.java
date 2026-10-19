package com.enterprise.agentharness.task;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

public record TaskListQuery(PageRequest pageable, TaskStatus status) {
    private static final Set<String> ALLOWED = Set.of("page", "size", "status", "sort");

    public static TaskListQuery parse(MultiValueMap<String, String> params) {
        if (params.entrySet().stream().anyMatch(e -> !ALLOWED.contains(e.getKey()) || e.getValue().size() != 1)) {
            throw new InvalidTaskQueryException("Unknown or repeated query parameter");
        }
        int page = integer(params.getFirst("page"), 0, "page");
        int size = integer(params.getFirst("size"), 20, "size");
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new InvalidTaskQueryException("page must be nonnegative, size must be 1..100, and offset must fit an integer");
        }
        TaskStatus status = null;
        if (params.containsKey("status")) {
            try { status = TaskStatus.valueOf(params.getFirst("status")); }
            catch (IllegalArgumentException ex) { throw new InvalidTaskQueryException("Invalid status"); }
        }
        String sort = params.getFirst("sort");
        Sort.Direction direction;
        if (sort == null || sort.equals("createdAt,desc")) direction = Sort.Direction.DESC;
        else if (sort.equals("createdAt,asc")) direction = Sort.Direction.ASC;
        else throw new InvalidTaskQueryException("sort must be createdAt,asc or createdAt,desc");
        return new TaskListQuery(PageRequest.of(page, size, Sort.by(direction, "createdAt", "id")), status);
    }

    private static int integer(String value, int fallback, String field) {
        if (value == null) return fallback;
        if (!value.matches("[0-9]+")) throw new InvalidTaskQueryException("Invalid " + field);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new InvalidTaskQueryException("Invalid " + field); }
    }
}
