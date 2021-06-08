package com.scerra.utils.simplecrawler;

import java.util.List;

public class Page {
    private String url;
    private List<PageLink> links;
    private Page redirectsTo;

    public Page(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<PageLink> getLinks() {
        return links;
    }

    public void setLinks(List<PageLink> links) {
        this.links = links;
    }

    public Page getRedirectsTo() {
        return redirectsTo;
    }

    public void setRedirectsTo(Page redirectsTo) {
        this.redirectsTo = redirectsTo;
    }
}
