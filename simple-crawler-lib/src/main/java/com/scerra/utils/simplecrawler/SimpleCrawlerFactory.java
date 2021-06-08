package com.scerra.utils.simplecrawler;

import org.asynchttpclient.AsyncHttpClient;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;

public class SimpleCrawlerFactory {
    /**
     * Creates a SimpleCrawler with the default config.
     * @return
     */
    public static SimpleCrawler createSimpleCrawler() {
        return createSimpleCrawler(new CrawlerConfig());
    }

    /**
     * Creates a SimpleCrawler with the specified config.
     * @param config
     * @return
     */
    public static SimpleCrawler createSimpleCrawler(CrawlerConfig config) {
        AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setRequestTimeout(config.getRequestTimeout()));
        SimpleCrawler crawler = new SimpleCrawler(asyncHttpClient, config);
        return crawler;
    }
}
