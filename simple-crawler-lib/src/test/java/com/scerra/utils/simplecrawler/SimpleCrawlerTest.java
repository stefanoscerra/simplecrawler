package com.scerra.utils.simplecrawler;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimpleCrawlerTest {
    private AsyncHttpClient httpClient;

    @BeforeEach
    void setUp() {
        this.httpClient = mock(AsyncHttpClient.class);
    }

    @Test
    void testLinkedPagesAreCrawled() throws ExecutionException, InterruptedException {
        addMockResponse(httpClient, "https://google.com",
                "<html><head></head><body><a href=\"/my-account\">My Account</a><a href=\"/support\">Support</a></body></html>");
        addMockResponse(httpClient, "https://google.com/my-account",
                "<html><head></head><body><h1>Account data</h1><a href=\"/support\">Account Support</a></body></html>");
        addMockResponse(httpClient, "https://google.com/support",
                "<html><head></head><body><h1>Support page</h1></body></html>");

        SimpleCrawler crawler = new SimpleCrawler(httpClient, new CrawlerConfig());
        Page rootPage = crawler.crawl("https://google.com");

        assertNotNull(rootPage);
        assertEquals("https://google.com", rootPage.getUrl());
        assertNotNull(rootPage.getLinks());
        assertEquals(2, rootPage.getLinks().size());

        PageLink accountPageLink = rootPage.getLinks().stream().filter(link -> link.getUrl().equals("https://google.com/my-account")).findFirst().orElse(null);
        assertNotNull(accountPageLink);
        assertEquals("My Account", accountPageLink.getText());

        PageLink supportPageLink = rootPage.getLinks().stream().filter(link -> link.getUrl().equals("https://google.com/support")).findFirst().orElse(null);
        assertNotNull(supportPageLink);
        assertEquals("Support", supportPageLink.getText());

        Page accountPage = accountPageLink.getPage();
        assertNotNull(accountPage);
        assertEquals("https://google.com/my-account", accountPage.getUrl());

        Page supportPage = supportPageLink.getPage();
        assertNotNull(supportPage);
        assertEquals("https://google.com/support", supportPage.getUrl());
        assertNotNull(supportPage.getLinks());
        assertTrue(supportPage.getLinks().isEmpty());

        assertNotNull(accountPage.getLinks());
        assertEquals(1, accountPage.getLinks().size());
        PageLink accountToSupportPageLink = accountPage.getLinks().get(0);
        assertNotNull(accountToSupportPageLink);
        assertEquals("https://google.com/support", accountToSupportPageLink.getUrl());
        assertEquals("Account Support", accountToSupportPageLink.getText());

        assertEquals(supportPage, accountToSupportPageLink.getPage());
    }

    @Test
    void testRedirectsAreCrawled() throws ExecutionException, InterruptedException {
        addMockResponse(httpClient, "https://google.com",
                "<html><head></head><body><a href=\"/account\">My Account</a></body></html>");
        addMockRedirectResponse(httpClient, "https://google.com/account", "https://google.com/my-account");
        addMockResponse(httpClient, "https://google.com/my-account",
                "<html><head></head><body><h1>Account data</h1></body></html>");

        SimpleCrawler crawler = new SimpleCrawler(httpClient, new CrawlerConfig());
        Page rootPage = crawler.crawl("https://google.com");

        assertNotNull(rootPage);
        assertEquals("https://google.com", rootPage.getUrl());
        assertNotNull(rootPage.getLinks());
        assertEquals(1, rootPage.getLinks().size());

        PageLink accountPageLink = rootPage.getLinks().stream().filter(link -> link.getUrl().equals("https://google.com/account")).findFirst().orElse(null);
        assertNotNull(accountPageLink);
        assertEquals("My Account", accountPageLink.getText());

        Page redirectPage = accountPageLink.getPage();
        assertNotNull(redirectPage);
        assertNotNull(redirectPage.getRedirectsTo());

        Page accountPage = redirectPage.getRedirectsTo();
        assertEquals("https://google.com/my-account", accountPage.getUrl());
    }

    @Test
    void testLinksOutsideRootDomainAreNotCrawled() throws ExecutionException, InterruptedException {
        addMockResponse(httpClient, "https://google.com",
                "<html><head></head><body><a href=\"/my-account\">My Account</a><a href=\"https://community.google.com\">Google Community</a></body></html>");
        addMockResponse(httpClient, "https://google.com/my-account",
                "<html><head></head><body><h1>Account data</h1></body></html>");

        SimpleCrawler crawler = new SimpleCrawler(httpClient, new CrawlerConfig());
        Page rootPage = crawler.crawl("https://google.com");

        assertNotNull(rootPage);
        assertEquals("https://google.com", rootPage.getUrl());
        assertNotNull(rootPage.getLinks());
        assertEquals(1, rootPage.getLinks().size());

        PageLink accountPageLink = rootPage.getLinks().stream().filter(link -> link.getUrl().equals("https://google.com/my-account")).findFirst().orElse(null);
        assertNotNull(accountPageLink);
        assertEquals("My Account", accountPageLink.getText());

        Page accountPage = accountPageLink.getPage();
        assertEquals("https://google.com/my-account", accountPage.getUrl());
    }

    @SuppressWarnings("unchecked")
    private void addMockResponse(AsyncHttpClient httpClient, String url, String response) throws ExecutionException, InterruptedException {
        BoundRequestBuilder requestBuilder = mock(BoundRequestBuilder.class);
        doReturn(requestBuilder).when(httpClient).prepareGet(url);
        when(requestBuilder.addHeader(anyString(), anyString())).thenReturn(requestBuilder);
        ListenableFuture<Response> responseFuture = mock(ListenableFuture.class);
        when(requestBuilder.execute()).thenReturn(responseFuture);

        Response responseObj = mock(Response.class);
        when(responseObj.getStatusCode()).thenReturn(200);
        when(responseObj.getResponseBody()).thenReturn(response);
        when(responseObj.getContentType()).thenReturn("text/html");
        when(responseFuture.get()).thenReturn(responseObj);

        doAnswer((Answer<ListenableFuture<Response>>) invocation -> {
            Runnable callback = invocation.getArgument(0);
            callback.run();
            return responseFuture;
        }).when(responseFuture).addListener(any(), any());
    }

    @SuppressWarnings("unchecked")
    private void addMockRedirectResponse(AsyncHttpClient httpClient, String url, String redirectUrl) throws ExecutionException, InterruptedException {
        BoundRequestBuilder requestBuilder = mock(BoundRequestBuilder.class);
        doReturn(requestBuilder).when(httpClient).prepareGet(url);
        when(requestBuilder.addHeader(anyString(), anyString())).thenReturn(requestBuilder);
        ListenableFuture<Response> responseFuture = mock(ListenableFuture.class);
        when(requestBuilder.execute()).thenReturn(responseFuture);

        Response responseObj = mock(Response.class);
        when(responseObj.getStatusCode()).thenReturn(301);
        when(responseObj.getHeader("location")).thenReturn(redirectUrl);
        when(responseFuture.get()).thenReturn(responseObj);

        doAnswer((Answer<ListenableFuture<Response>>) invocation -> {
            Runnable callback = invocation.getArgument(0);
            callback.run();
            return responseFuture;
        }).when(responseFuture).addListener(any(), any());
    }

}