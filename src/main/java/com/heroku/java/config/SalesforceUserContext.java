package com.heroku.java.config;

public class SalesforceUserContext {

    private final String accessToken;
    private final String apiVersion;
    private final String requestId;
    private final String namespace;
    private final String orgId;
    private final String orgDomainUrl;
    private final String userId;
    private final String username;

    public SalesforceUserContext(String accessToken, String apiVersion, String requestId, String namespace, String orgId,
                                 String orgDomainUrl, String userId, String username) {
        this.accessToken = accessToken;
        this.apiVersion = apiVersion;
        this.requestId = requestId;
        this.namespace = namespace;
        this.orgId = orgId;
        this.orgDomainUrl = orgDomainUrl;
        this.userId = userId;
        this.username = username;
    }

    // Getters
    public String getAccessToken() {
        return accessToken;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getOrgId() {
        return orgId;
    }

    public String getOrgDomainUrl() {
        return orgDomainUrl;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }
}
