package com.veluxa;

public record RunRequest(String url, String loginUrl, String username, String password) {
    @Override
    public String toString() {
        return "RunRequest[url=" + url + ", credentials hidden]";
    }
}
