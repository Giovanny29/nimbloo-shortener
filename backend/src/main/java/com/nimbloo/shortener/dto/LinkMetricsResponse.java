package com.nimbloo.shortener.dto;

import com.nimbloo.shortener.entity.UrlItem;

public record LinkMetricsResponse(
    LinkResponse link,
    Long totalClicks
) {
    public static LinkMetricsResponse fromEntity(UrlItem item, String baseUrl) {
        LinkResponse linkDto = LinkResponse.fromEntity(item, baseUrl);
        return new LinkMetricsResponse(
            linkDto,
            linkDto.clickCount()
        );
    }
}