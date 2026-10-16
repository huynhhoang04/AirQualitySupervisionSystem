package com.aqss.backend.dto.common;

/** Generic {@code {"status": "..."}} acknowledgement. */
public record StatusResponse(String status) {

    public static StatusResponse of(String status) {
        return new StatusResponse(status);
    }
}
