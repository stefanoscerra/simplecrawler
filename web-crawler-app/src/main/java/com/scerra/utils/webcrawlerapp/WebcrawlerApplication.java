package com.scerra.utils.webcrawlerapp;

import com.scerra.utils.simplecrawler.CrawlerConfig;
import com.scerra.utils.simplecrawler.Page;
import com.scerra.utils.simplecrawler.SimpleCrawler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import static com.scerra.utils.simplecrawler.SimpleCrawlerFactory.createSimpleCrawler;

@SpringBootApplication
public class WebcrawlerApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebcrawlerApplication.class, args);
		if (args.length < 1 || args.length > 4) {
			System.out.println("Usage: \njava -jar webcrawler.jar rootUrl [maxConcurrentRequests] [requestTimeout (ms)] [userAgent]");
			System.out.println("Please specify at least an argument containing a root URL where to start crawling from.");
			System.exit(1);
		}

		String rootUrl = args[0];
		Integer maxConcurrentRequests = null;
		Integer requestTimeout = null;
		String userAgent = null;
		if (args.length >= 2) {
			maxConcurrentRequests = Integer.valueOf(args[1]);
		}
		if (args.length >= 3) {
			requestTimeout = Integer.valueOf(args[2]);
		}
		if (args.length == 4) {
			userAgent = args[3];
		}

		CrawlerConfig config = new CrawlerConfig();
		if (maxConcurrentRequests != null) {
			config.setMaxConcurrentRequests(maxConcurrentRequests);
		}

		if (requestTimeout != null) {
			config.setRequestTimeout(requestTimeout);
		}

		if (userAgent != null) {
			config.setUserAgent(userAgent);
		}

		try {
			SimpleCrawler crawler = createSimpleCrawler(config);
			System.out.println(String.format("Starting crawler on URL %s", rootUrl));
			Page rootPage = crawler.crawl(rootUrl);
			crawler.shutdown();
			handleCrawlResult(rootPage);
			System.exit(0);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	private static void handleCrawlResult(Page rootPage) {
		/*
		*  Performs a breadth-first traversal of the page graph, printing page URLs, links and redirects.
		*  Uses a visited pages set to avoid cycles in the page graph.
		*/
		Set<String> visitedPagesUrl = new HashSet<>();
		Queue<Page> queue = new LinkedList<>();
		queue.add(rootPage);

		while (!queue.isEmpty()) {
			Page currentPage = queue.poll();
			if (!visitedPagesUrl.contains(currentPage.getUrl())) {
				visitedPagesUrl.add(currentPage.getUrl());
				System.out.println(String.format("Page %s", currentPage.getUrl()));
				if (currentPage.getRedirectsTo() == null) {
					System.out.println(String.format("\t%d outbound links", currentPage.getLinks().size()));
				}

				currentPage.getLinks().forEach(link -> {
					System.out.println(String.format("\t\t%s -> %s", link.getText(), link.getUrl()));
					if (link.getPage() != null && !visitedPagesUrl.contains(link.getPage().getUrl())) {
						queue.add(link.getPage());
					}
				});

				if (currentPage.getRedirectsTo() != null) {
					System.out.println(String.format("\t[redirect] -> %s", currentPage.getRedirectsTo().getUrl()));
					if (!visitedPagesUrl.contains(currentPage.getRedirectsTo().getUrl())) {
						queue.add(currentPage.getRedirectsTo());
					}
				}
			}
		}
	}


}
