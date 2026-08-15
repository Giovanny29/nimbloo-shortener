package com.nimbloo.shortener.entity;

import java.time.Instant;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@DynamoDbBean
public class UrlItem {

    private String code;
    private String originalUrl;
    private Long clickCount;
    private Boolean active;
    private String createdAt;
    private String expiresAt;

    public UrlItem() {
    }

    public UrlItem(String code, String originalUrl, String expiresAt) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.clickCount = 0L;
        this.active = true;
        this.createdAt = Instant.now().toString();
        this.expiresAt = expiresAt;
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute("code")
    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @DynamoDbAttribute("original_url")
    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    @DynamoDbAttribute("click_count")
    public Long getClickCount() {
        return clickCount;
    }

    public void setClickCount(Long clickCount) {
        this.clickCount = clickCount;
    }

    @DynamoDbAttribute("active")
    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    @DynamoDbAttribute("created_at")
    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("expires_at")
    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }
}