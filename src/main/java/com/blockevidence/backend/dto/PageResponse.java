package com.blockevidence.backend.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** A page of results, for H1/H3/A6. Wraps Spring Data's {@link Page} instead of exposing it directly on the
 *  API (entities/framework types never cross the HTTP boundary, matching the rest of the DTO layer). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
