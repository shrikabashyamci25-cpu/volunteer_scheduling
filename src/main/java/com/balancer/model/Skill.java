package com.balancer.model;

import java.util.Locale;

/** A named skill, normalised to lower case so "First Aid" and "first aid" are the same key. */
public record Skill(String name) implements Comparable<Skill> {

    public Skill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        name = name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static Skill of(String name) {
        return new Skill(name);
    }

    @Override
    public int compareTo(Skill o) {
        return name.compareTo(o.name);
    }

    @Override
    public String toString() {
        return name;
    }
}
