package com.balancer.command;

import com.balancer.model.Violation;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when an edit breaks constraints. Carries the violations so the UI can show them. */
public final class EditRejectedException extends RuntimeException {

    private final transient List<Violation> violations;

    public EditRejectedException(List<Violation> violations) {
        super(violations.stream().map(Violation::message).collect(Collectors.joining("; ")));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
