package com.nimbloo.shortener.dto;

import com.nimbloo.shortener.entity.LinkStatus;
import com.nimbloo.shortener.entity.UrlItem;

public record LinkResponse(
    String code,
    String originalUrl,
    String shortUrl,
    Long clickCount,
    Boolean active,
    String createdAt,
    String expiresAt,
    LinkStatus status
) {
    public static LinkResponse fromEntity(UrlItem item, String baseUrl) {
        LinkStatus computedStatus = LinkStatus.calculateStatus(item.getActive(), item.getExpiresAt());

        return new LinkResponse(
            item.getCode(),
            item.getOriginalUrl(),
            baseUrl + "/" + item.getCode(),
            item.getClickCount() != null ? item.getClickCount() : 0L,
            item.getActive() != null ? item.getActive() : true,
            item.getCreatedAt(),
            item.getExpiresAt(),
            computedStatus
        );
    }
}