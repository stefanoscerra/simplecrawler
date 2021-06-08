package com.scerra.utils.simplecrawler;

public class CrawlerConfig {
    private int requestTimeout;
    private int maxConcurrentRequests;
    private String userAgent;

    public CrawlerConfig() {
        /* Set default config data. */
        this.requestTimeout = 15000;
        this.maxConcurrentRequests = 40;
        this.userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.114 Safari/537.36";
    }

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public CrawlerConfig setRequestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
        return this;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public CrawlerConfig setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
        return this;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public CrawlerConfig setUserAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }
}
