package com.balancer.io;

import com.balancer.model.Shift;
import com.balancer.model.Volunteer;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads volunteers.json / shifts.json (NIO.2 + Jackson) into immutable domain objects. */
public final class JsonDataLoader {

    public List<Volunteer> volunteers(Path file) {
        return volunteersFromString(read(file));
    }

    public List<Shift> shifts(Path file) {
        return shiftsFromString(read(file));
    }

    public List<Volunteer> volunteersFromString(String json) {
        try {
            List<Dtos.VolunteerDto> dtos = Json.MAPPER.readValue(json, new TypeReference<>() { });
            return dtos.stream().map(Dtos::toDomain).toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid volunteers JSON: " + e.getMessage(), e);
        }
    }

    public List<Shift> shiftsFromString(String json) {
        try {
            List<Dtos.ShiftDto> dtos = Json.MAPPER.readValue(json, new TypeReference<>() { });
            return dtos.stream().map(Dtos::toDomain).toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid shifts JSON: " + e.getMessage(), e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
