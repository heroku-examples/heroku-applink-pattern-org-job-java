package com.heroku.java.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SalesforceClientContextFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(SalesforceClientContextFilter.class);

    private static final String X_CLIENT_CONTEXT_HEADER = "x-client-context";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        logger.info("Filter called");
        String encodedClientContext = request.getHeader(X_CLIENT_CONTEXT_HEADER);
        if (encodedClientContext == null)
            throw new ServletException("Required " + X_CLIENT_CONTEXT_HEADER + " header not found");
        try {
            // Decode the base64 header value and parse the JSON
            String decodedClientContext = new String(Base64.getDecoder().decode(encodedClientContext), StandardCharsets.UTF_8);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode clientContextNode = objectMapper.readTree(decodedClientContext);
            // Extract fields to hydrate a UserContext and WSC PartnerConnection on the request
            String accessToken = clientContextNode.get("accessToken").asText();
            String apiVersion = clientContextNode.get("apiVersion").asText();
            String requestId = clientContextNode.get("requestId").asText();
            String namespace = clientContextNode.get("namespace").asText();
            String orgId = clientContextNode.get("orgId").asText();
            String orgDomainUrl = clientContextNode.get("orgDomainUrl").asText();
            logger.info("Got info accessToken:{} also orgDomainURL: {}", accessToken, orgDomainUrl);
            JsonNode userContextNode = clientContextNode.get("userContext");
            String userId = userContextNode.get("userId").asText();
            String username = userContextNode.get("username").asText();
            // Validate required fields
            if (accessToken == null || apiVersion == null || orgDomainUrl == null || userId == null || username == null)
                throw new ServletException("Missing required fields in " + X_CLIENT_CONTEXT_HEADER);
            // Create the SalesforceUserContext object
            SalesforceUserContext userContext = new SalesforceUserContext(accessToken, apiVersion, requestId, namespace, orgId, orgDomainUrl, userId, username);
            // Initialize the PartnerConnection
            PartnerConnection partnerConnection = initializePartnerConnection(accessToken, apiVersion, orgDomainUrl, username);
            // Make the SalesforceUserContext and PartnerConnection available in the request context
            request.setAttribute("salesforceUserContext", userContext);
            request.setAttribute("salesforcePartnerConnection", partnerConnection);
            logger.info("Set request attributes");
        } catch (Exception e) {
            throw new ServletException("Error processing " + X_CLIENT_CONTEXT_HEADER + " header", e);
        }
        filterChain.doFilter(request, response);
    }

    private PartnerConnection initializePartnerConnection(String accessToken, String apiVersion, String orgDomainUrl, String username) throws ConnectionException {
        ConnectorConfig config = new ConnectorConfig();
        config.setUsername(username);
        config.setSessionId(accessToken);
        config.setServiceEndpoint(orgDomainUrl + "/services/Soap/u/" + apiVersion);
        return new PartnerConnection(config);
    }
}
