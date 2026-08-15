package com.nimbloo.shortener.dto;

import java.util.List;

public record PagedLinkResponse(
    List<LinkResponse> items,
    String lastEvaluatedKey, 
    int pageSize,
    boolean hasMore          // Indica ao front-end se existe uma próxima página
) {
    public static PagedLinkResponse of(List<LinkResponse> items, String lastKey, int pageSize) {
        return new PagedLinkResponse(
            items,
            lastKey,
            pageSize,
            lastKey != null && !lastKey.isBlank()
        );
    }
}