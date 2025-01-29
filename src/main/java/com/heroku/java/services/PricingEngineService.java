package com.heroku.java.services;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sforce.soap.partner.PartnerConnection;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Exposed REST endpoints to Salesforce that will enque jobs to generate Quotes and manage sample data
 */
@Tag(name = "Pricing Engine", description = "Leverage dynamic pricing calculation logic and rules to calculate pricing information in the form of Quotes.")
@RestController
@RequestMapping("/api/")
public class PricingEngineService {

    private static final Logger logger = LoggerFactory.getLogger(PricingEngineService.class);

    @Autowired
    private StringRedisTemplate redis;

    @Operation(summary = "Start batch processing for Quote generation", description = "Calculate pricing and generate quotes from Opportunities queried using the SOQL WHERE clause.")
    @PostMapping("/executebatch")
    public BatchExecutionResponse executeBatch(@RequestBody BatchExecutionRequest request, HttpServletRequest httpServletRequest) {
        logger.info("Received generate Quotes request for Opportunities matching: {}", request.soqlWhereClause);
        // Submit the job to the queue
        String jobId = enqueJob("quoteQueue", request.soqlWhereClause, httpServletRequest);
        BatchExecutionResponse response = new BatchExecutionResponse();
        response.jobId = jobId;
        return response;
    }

    @Operation(summary = "Create sample Opportunties to test against", description = "Starts a job to create a large amount of Opportunity records.")
    @PostMapping("/data/create")
    public DataJobResponse datacreate(@RequestParam(defaultValue = "100") Integer numberOfOpportunities, HttpServletRequest httpServletRequest) {
        logger.info("Received Opportunity data creation request to create {} Opportunties", numberOfOpportunities);
        // Submit the job to the queue
        String jobId = enqueJob("dataQueue", "create:" + numberOfOpportunities, httpServletRequest);
        DataJobResponse response = new DataJobResponse();
        response.jobId = jobId;
        return response;
    }

    @Operation(summary = "Deletes all Quotes created by executeBatch", description = "Starts a job to delete generate Quotes")
    @PostMapping("/data/delete")
    public DataJobResponse datadelete(HttpServletRequest httpServletRequest) {
        logger.info("Received Quote data deletion request");
        // Submit the job to the queue
        String jobId = enqueJob("dataQueue", "delete", httpServletRequest);
        DataJobResponse response = new DataJobResponse();
        response.jobId = jobId;
        return response;
    }

    // Schema to define the request for batch execution with a SOQL WHERE clause
    @Schema(description = "Request to execute a batch process, includes a SOQL WHERE clause to extract product information")
    public static class BatchExecutionRequest {
        @Schema(example = "OpportunityId IN ('0065g00000B9tMP', '0065g00000B9tMQ')", description = "A SOQL WHERE clause for filtering opportunities")
        public String soqlWhereClause;
    }

    // Schema to define the response for batch execution containing the job ID
    @Schema(description = "Response includes the unique job ID processing the request.")
    public static class BatchExecutionResponse {
        @Schema(example = "3f7c47f3-7c66-4c9a-92e5-ef2dbb9a1d67", description = "Unique job ID for tracking the worker process")
        public String jobId;
    }

    // Schema to define the response for batch execution containing the job ID
    @Schema(description = "Response includes the unique job ID processing the request.")
    public static class DataJobResponse {
        @Schema(example = "3f7c47f3-7c66-4c9a-92e5-ef2dbb9a1d67", description = "Unique job ID for tracking the worker process")
        public String jobId;
    }

    /**
     * Enque the job by posting a message to the given channel along with Salesforce connection details
     * @param channel
     * @param message
     * @param httpServletRequest
     * @return
     */
    private String enqueJob(String channel, String message, HttpServletRequest httpServletRequest) {
        // Generate a unique Job ID for this request
        String jobId = UUID.randomUUID().toString();
        // Get Salesforce session from request
        PartnerConnection connection = (PartnerConnection) httpServletRequest.getAttribute("salesforcePartnerConnection");
        if (connection == null) {
            logger.error("Salesforce connection is not available.");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Salesforce connection is not available.");
        }
        try {            
            // Store session info in Redis for the worker to use
            String sessionId = connection.getSessionHeader().getSessionId();
            String instanceUrl = connection.getConfig().getServiceEndpoint(); // Extract instance URL
            redis.opsForValue().set("salesforce:session:" + jobId, sessionId);
            redis.opsForValue().set("salesforce:instance:" + jobId, instanceUrl);            
            // Enqueue job
            redis.convertAndSend(channel, jobId + ":" + message);
            logger.info("Job enqueued with ID: {} for message: {} to channel: {}", jobId, message, channel);

        } catch (Exception e) {
            logger.error("Error interacting with Redis: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process the request due to an internal error.");
        }
        return jobId;
    }
}
