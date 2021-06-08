package com.scerra.utils.simplecrawler;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

public class SimpleCrawler {
    /* Number of currently pending HTTP requests. */
    private int pendingRequests;
    /* Controls mutual exclusion access on shared data structures by the controller and the workers. */
    private Semaphore mutex = new Semaphore(1);
    /* This represents a possible update in the request queue and/or in the number of pending requests. */
    private Semaphore queueMightBeUpdated = new Semaphore(0);
    /* The main request queue. */
    private Queue<Page> queue = new LinkedList<>();
    /* The page cache, indexed by URL. */
    private Map<String, Page> pages = new HashMap<>();
    /* Maps a URL to the list of page links pointing to it. */
    private Map<String, List<PageLink>> referringLinks = new HashMap<>();
    /* Maps a URL to a list of pages redirecting to it. */
    private Map<String, List<Page>> redirectingPages = new HashMap<>();
    /* The async HTTP client. */
    private AsyncHttpClient asyncHttpClient;
    /* Crawler configuration. */
    private CrawlerConfig config;
    /* Determines if the crawler has been shut down. */
    private boolean isShutdown;

    protected SimpleCrawler(AsyncHttpClient asyncHttpClient, CrawlerConfig config) {
        this.asyncHttpClient = asyncHttpClient;
        this.config = config;
    }

    /**
     * Starts crawling on the specified rootUrl, following links pointing to URLs on the same domain.
     * When crawling is done, it will return a root Page object, from which the page graph may be traversed.
     * @param rootUrl
     */
    public Page crawl(String rootUrl) {
        if (isShutdown) {
            throw new IllegalStateException("Crawler has been shut down.");
        }

        final URI rootUriObj;
        try {
            rootUriObj = new URI(rootUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(String.format("Invalid URL: %s", rootUrl));
        }

        // This thread pool will be used by the asyncHttpClient to run our HTTP response handler.
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        System.out.println(String.format("Crawler configuration: {maxConcurrentRequests: %d, requestTimeout: %dms, userAgent: %s}",
                config.getMaxConcurrentRequests(), config.getRequestTimeout(), config.getUserAgent()));

        queue.add(new Page(rootUrl));
        queueMightBeUpdated.release();

        try {
            while (true) {
                /* Main controller loop */
                mutex.acquire();
                while(queue.isEmpty() || pendingRequests > config.getMaxConcurrentRequests()) {
                    /* Controller sleep cycle. The controller will wait here when the request queue is empty but
                    * there are still pending requests, or when the number of pending requests is greater than the limit. */
                    mutex.release();
                    queueMightBeUpdated.acquire();
                    mutex.acquire();
                    if (queue.isEmpty() && pendingRequests == 0) {
                        /* The controller found the request queue to be empty, with no pending requests. Crawling is done. */
                        System.out.println("Crawling completed.");
                        mutex.release();
                        executor.shutdown();
                        System.out.println(String.format("Crawled %d pages.", pages.size()));
                        Page rootPage = pages.get(rootUrl);
                        pages.clear();
                        referringLinks.clear();
                        redirectingPages.clear();
                        return rootPage;
                    }
                }

                /* Fetch next element from the request queue, perform async HTTP request and register response handler. */
                Page currentPage = queue.poll();
                pages.put(currentPage.getUrl(), null);
                pendingRequests += 1;
                ListenableFuture<Response> responseFuture = asyncHttpClient.prepareGet(currentPage.getUrl())
                        .addHeader(HttpConstants.HTTP_HEADER_USER_AGENT, config.getUserAgent())
                        .execute();

                mutex.release();
                responseFuture.addListener(handleResponse(rootUriObj, currentPage, responseFuture), executor);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns an asynchronous handler for processing HTTP responses. It will be invoked in a separate thread by the asyncHttpClient.
     * @param rootUriObj
     * @param currentPage
     * @param responseFuture
     * @return
     */
    private Runnable handleResponse(URI rootUriObj, Page currentPage, ListenableFuture<Response> responseFuture) {
        return () -> {
            try {
                Response response = null;
                try {
                    response = responseFuture.get();
                } catch (ExecutionException e) {
                    System.err.println(String.format("Could not get response from URL %s", currentPage.getUrl()));
                }

                /* Tries to parse the HTML document in the response, avoiding to do so if the content type is not HTML or there is a redirect. */
                Document document = null;
                if (response != null && response.getStatusCode() != HttpConstants.HTTP_STATUS_MOVED_PERMANENTLY &&
                        response.getStatusCode() != HttpConstants.HTTP_STATUS_MOVED_TEMPORARILY &&
                        response.getContentType() != null && response.getContentType().contains(HttpConstants.CONTENT_TYPE_HTML)) {
                    try {
                        document = Jsoup.parse(response.getResponseBody());
                    } catch (IllegalArgumentException e) {
                        System.err.println(String.format("Could not parse HTML response from URL %s", response.getUri().toString()));
                    }
                }

                /* Get the page outbound links. */
                List<PageLink> links = new ArrayList<>();
                if (document != null) {
                    links = scrapePageLinks(document, rootUriObj);
                }
                currentPage.setLinks(links);

                mutex.acquire();

                /* Handling of redirects */
                if (response != null && (response.getStatusCode() == HttpConstants.HTTP_STATUS_MOVED_PERMANENTLY ||
                        response.getStatusCode() == HttpConstants.HTTP_STATUS_MOVED_TEMPORARILY)) {
                    String locationHeader = response.getHeader(HttpConstants.HTTP_HEADER_LOCATION);
                    String redirectUrl = combineUrlSegments(rootUriObj, locationHeader);

                    if (!pages.containsKey(redirectUrl) && !queue.stream().anyMatch(page -> page.getUrl().equals(redirectUrl))) {
                        // Neither the page cache or the queue contains the redirected page, so enqueue it for crawling.
                        queue.add(new Page(redirectUrl));
                    } else {
                        Page redirectPage = pages.get(redirectUrl);
                        if (redirectPage != null) {
                            currentPage.setRedirectsTo(redirectPage);
                        }
                    }

                    /* Add the redirecting page to the list of pages pointing to redirected URL. */
                    redirectingPages.merge(redirectUrl, new ArrayList<>(Arrays.asList(currentPage)),
                            (redirectPagesList, __) -> {
                                redirectPagesList.add(currentPage);
                                return redirectPagesList;
                            });
                }

                pages.put(currentPage.getUrl(), currentPage);

                for (PageLink link : currentPage.getLinks()) {
                    if (!pages.containsKey(link.getUrl()) && !queue.stream().anyMatch(page -> page.getUrl().equals(link.getUrl()))) {
                        // Neither the page cache or the queue contains the linked page, so enqueue it for crawling.
                        queue.add(new Page(link.getUrl()));
                    } else {
                        Page linkedPage = pages.get(link.getUrl());
                        if (linkedPage != null) {
                            link.setPage(linkedPage);
                        }
                    }

                    /* Add the current link to the list of links pointing to the destination page.  */
                    referringLinks.merge(link.getUrl(), new ArrayList<>(Arrays.asList(link)),
                            (referringLinksList, __) -> {
                                referringLinksList.add(link);
                                return referringLinksList;
                            });
                }

                /* Reconcile any links that are pointing to current page. */
                List<PageLink> pageReferringLinks = referringLinks.get(currentPage.getUrl());
                if (pageReferringLinks != null) {
                    pageReferringLinks.forEach(pageLink -> {
                        pageLink.setPage(currentPage);
                    });
                }

                /* Reconcile any pages that are redirecting to current page. */
                List<Page> pagesRedirectingToCurrentPage = redirectingPages.get(currentPage.getUrl());
                if (pagesRedirectingToCurrentPage != null) {
                    pagesRedirectingToCurrentPage.forEach(page -> {
                        page.setRedirectsTo(currentPage);
                    });
                }

                pendingRequests -= 1;
                // Worker has terminated, send a signal to the controller so that it can wake up if asleep.
                queueMightBeUpdated.release();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                mutex.release();
            }
        };
    }

    /**
     * Scrapes any links contained in the specified document,
     * filtering out the ones that do not match the root domain URL.
     * @param document
     * @param rootUri
     * @return
     */
    private List<PageLink> scrapePageLinks(Document document, URI rootUri) {
        Elements anchors = document.select("a");

        return anchors.stream()
                .filter(anchor -> {
                    String url = anchor.attributes().get("href");
                    return urlShouldBeCrawled(url, rootUri.getHost());
                })
                .map(anchor -> {
                    String href = anchor.attributes().get("href");
                    String url = combineUrlSegments(rootUri, href);

                    String text = anchor.text();
                    return new PageLink(url, text);
                }).collect(Collectors.toList());
    }

    /**
     * Combines a root URL with a path (handling edge cases) and returns it.
     * @param rootUri
     * @param path
     * @return
     */
    private String combineUrlSegments(URI rootUri, String path) {
        String url;
        if (!path.startsWith(rootUri.toString())) {
            // This URL is missing the host prefix, let's add it.
            url = rootUri.toString();
            if (!rootUri.getPath().endsWith("/") && !path.startsWith("/")) {
                url = url + "/";
            }
            if (rootUri.getPath().endsWith("/") && path.startsWith("/")) {
                url = url.substring(0, url.length() - 1) + path;
            } else {
                url = url + path;
            }
        } else {
            url = path;
        }
        return url;
    }

    /**
     * Checks if a URL should be crawled by seeing if it matches a root domain.
     * Returns false if the URL has no host part, is outside the specified root domain or is invalid.
     * @param url
     * @param rootDomain
     * @return
     */
    private boolean urlShouldBeCrawled(String url, String rootDomain) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getHost().equals(rootDomain)) {
                return true;
            }
            return false;
        } catch (URISyntaxException e) {
            // Let's ignore invalid URLs.
            System.err.println(String.format("Ignored invalid link with URL %s", url));
            return false;
        }
    }

    /**
     *  Shuts down crawler, closing internal HTTP client.
     *  Crawler instance cannot be reused after this.
     * @throws IOException
     */
    public void shutdown() throws IOException {
        if (!isShutdown) {
            asyncHttpClient.close();
            isShutdown = true;
        }
    }
}
