package com.balancer.strategy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/** Looks strategies up by id so the UI / CLI can switch rules at runtime. */
public final class StrategyRegistry {

    private final Map<String, FairnessStrategy> strategies = new LinkedHashMap<>();

    public StrategyRegistry() {
        register(new MinHoursBalancingStrategy());
        register(new CapacityRatioStrategy());
        register(new StrictPriorityStrategy());
    }

    public void register(FairnessStrategy s) {
        strategies.put(s.id(), s);
    }

    public FairnessStrategy get(String id) {
        FairnessStrategy s = strategies.get(id == null ? defaultStrategy().id() : id);
        if (s == null) throw new NoSuchElementException("Unknown strategy: " + id);
        return s;
    }

    public FairnessStrategy defaultStrategy() {
        return strategies.values().iterator().next();
    }

    public Collection<FairnessStrategy> all() {
        return strategies.values();
    }
}
