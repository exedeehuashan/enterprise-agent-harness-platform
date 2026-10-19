package com.enterprise.agentharness.task;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AgentTaskService {
    private final AgentTaskRepository repository;
    private final jakarta.validation.Validator validator;

    public AgentTaskService(AgentTaskRepository repository, jakarta.validation.Validator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional
    public TaskResponse create(CreateTaskRequest request) {
        return TaskResponse.from(repository.save(new AgentTask(request.title(), request.description())));
    }

    public TaskPageResponse list(TaskListQuery query) {
        return TaskPageResponse.from(query.status() == null
                ? repository.findAll(query.pageable())
                : repository.findByStatus(query.status(), query.pageable()));
    }

    @Transactional
    public TaskResponse edit(UUID id, long expectedVersion, TaskPatch patch) {
        AgentTask task = repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
        if (task.getVersion() != expectedVersion) throw new TaskEditException(
                org.springframework.http.HttpStatus.PRECONDITION_FAILED, "Task changed; reload before editing");
        String title = patch.hasTitle() ? patch.title() : task.getTitle();
        String description = patch.hasDescription() ? patch.description() : task.getDescription();
        var violations = validator.validate(new CreateTaskRequest(title, description));
        if (!violations.isEmpty()) throw new jakarta.validation.ConstraintViolationException(violations);
        task.edit(title, description);
        repository.flush();
        return TaskResponse.from(task);
    }

    public TaskResponse get(UUID id) {
        return TaskResponse.from(repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id)));
    }
}
