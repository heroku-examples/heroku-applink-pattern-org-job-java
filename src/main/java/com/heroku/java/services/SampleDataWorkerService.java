package com.heroku.java.services;

import jakarta.annotation.PostConstruct;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Service;

import com.sforce.async.AsyncApiException;
import com.sforce.async.BatchInfo;
import com.sforce.async.BatchStateEnum;
import com.sforce.async.BulkConnection;
import com.sforce.async.ContentType;
import com.sforce.async.JobInfo;
import com.sforce.async.JobStateEnum;
import com.sforce.async.OperationEnum;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles messages sent to the dataQueue channel to start data related jobs
 */
@Service
@Profile("worker")
public class SampleDataWorkerService implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(SampleDataWorkerService.class);
    private static final int OPPORTUNITY_PRODUCTS_PER_OPPORTUNITY = 2;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * Start listening for messages on the dataQueue
     * @throws InterruptedException
     */
    @PostConstruct
    public void subscribeToRedisQueue() throws InterruptedException {
        logger.info("Worker subscribing to Redis queue: dataQueue");
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(new MessageListenerAdapter(this, "onMessage"), new PatternTopic("dataQueue"));
        container.afterPropertiesSet();
        container.start();
        logger.info("Worker is now listening for messages.");
    }

    /**
     * Process messages received on the dataQueue
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String[] messageParts = new String(message.getBody()).split(":", 3);
        if (messageParts.length < 2) {
            logger.error("Invalid message format received: {}", message);
            return;
        }
        // Parse message and obtain job id, data operation and connection details
        String jobId = messageParts[0];
        String dataOperation = messageParts[1];
        Integer numberOfOpportunties = messageParts.length == 3 ? Integer.parseInt(messageParts[2]) : 0;
        logger.info("Worker received job with ID: {} for data operation: {}", jobId, dataOperation);
        String sessionId = redis.opsForValue().get("salesforce:session:" + jobId);
        String instanceUrl = redis.opsForValue().get("salesforce:instance:" + jobId);
        PartnerConnection connection = createSalesforceConnection(sessionId, instanceUrl);
        if (connection == null) {
            logger.error("Failed to reconnect to Salesforce for Job ID: {}", jobId);
            return;
        }
        try {
            // Process either creation of sample data or deletion of it via Salesforce Bulk API
            BulkConnection bulkConnection = getBulkConnection(connection);
            if ("create".equalsIgnoreCase(dataOperation)) {
                handleCreateOperation(connection, bulkConnection, numberOfOpportunties);
            } else if ("delete".equalsIgnoreCase(dataOperation)) {
                handleDeleteOperation(connection, bulkConnection);
            } else {
                logger.warn("Unknown data operation received: {}", dataOperation);
            }
        } catch (Exception e) {
            logger.error("Error processing bulk operation: {}", e.getMessage(), e);
        }
    }

    /**
     * Creates a number of sample Opportunity and Opportunity Product records using the Bulk API (v1)
     * @param connection
     * @param bulkConnection
     * @param numberOfOpportunities
     */
    private void handleCreateOperation(PartnerConnection connection, BulkConnection bulkConnection, Integer numberOfOpportunities) {
        try {
            String pricebookId = fetchStandardPricebookId(connection);
            if (pricebookId == null) {
                logger.error("No active Standard Pricebook found. Cannot proceed.");
                return;
            }            
            // Create and submit the bulk insert job for Opportunties
            logger.info("Creating {} Opportunties.", numberOfOpportunities);
            JobInfo opportunityJob = createBulkJob(bulkConnection, "Opportunity");            
            submitBatch(bulkConnection, opportunityJob, generateOpportunitiesCSV(pricebookId, numberOfOpportunities));
            waitForBulkJobCompletion(bulkConnection, opportunityJob.getId());
            closeBulkJob(bulkConnection, opportunityJob.getId());
            logger.info("Opportunities created successfully.");
            List<String> opportunityIds = fetchCreatedOpportunities(connection);
            Map<String, String> productPricebookMap = fetchPricebookEntries(connection, pricebookId);
            // Create and submit the bulk insert job for Opportunty Products
            logger.info("Creating Opportunity Products for {} Opportunities.", opportunityIds.size());
            JobInfo opportunityProductJob = createBulkJob(bulkConnection, "OpportunityLineItem");
            submitBatch(bulkConnection, opportunityProductJob, generateOpportunityProductsCSV(opportunityIds, productPricebookMap));
            waitForBulkJobCompletion(bulkConnection, opportunityProductJob.getId());
            closeBulkJob(bulkConnection, opportunityProductJob.getId());
            logger.info("Opportunity Products created successfully.");
        } catch (Exception e) {
            logger.error("Error in bulk create operation: {}", e.getMessage(), e);
        }
    }

    /**
     * Deletes sample Opportunity records, including Opportunity Products and Quotes
     * @param connection
     * @param bulkConnection
     */
    private void handleDeleteOperation(PartnerConnection connection, BulkConnection bulkConnection) {
        try {
            List<String> opportunityIds = fetchCreatedOpportunities(connection);
            if (opportunityIds.isEmpty()) {
                logger.info("No Opportunities found for deletion.");
                return;
            }
            // Create and submit bulk delete job
            logger.info("Starting job to delete {} Opportunities and related Quotes.", opportunityIds.size());
            JobInfo deleteJob = new JobInfo();
            deleteJob.setObject("Opportunity");
            deleteJob.setOperation(OperationEnum.hardDelete);
            deleteJob.setContentType(ContentType.CSV);
            deleteJob = bulkConnection.createJob(deleteJob);
            ByteArrayOutputStream csvStream = generateOpportunityDeletionCSV(opportunityIds);            
            submitBatch(bulkConnection, deleteJob, csvStream);    
            waitForBulkJobCompletion(bulkConnection, deleteJob.getId());
            closeBulkJob(bulkConnection, deleteJob.getId());
            logger.info("Deleted {} Opportunities successfully.", opportunityIds.size());
        } catch (Exception e) {
            logger.error("Error in bulk delete operation: {}", e.getMessage(), e);
        }
    }

    /**
     * Queries the Salesforce org for sample Opportunities
     * @param connection
     * @return
     * @throws Exception
     */
    private List<String> fetchCreatedOpportunities(PartnerConnection connection) throws Exception {
        List<String> opportunityIds = new ArrayList<>();
        for (var record : queryAllRecords(connection, "SELECT Id FROM Opportunity WHERE Name LIKE 'Sample Opportunity%'")) {
            opportunityIds.add(record.getId());
        }
        return opportunityIds;
    }

    /**
     * Queries the Saleforce org for all the products from the given price book 
     * @param connection
     * @param pricebookId
     * @return
     * @throws Exception
     */
    private Map<String, String> fetchPricebookEntries(PartnerConnection connection, String pricebookId) throws Exception {
        Map<String, String> productPricebookMap = new HashMap<>();    
        for (var record : queryAllRecords(connection, "SELECT Id, Product2Id FROM PricebookEntry WHERE IsActive = TRUE AND Pricebook2Id = '" + pricebookId + "'")) {
            productPricebookMap.put(record.getField("Product2Id").toString(), record.getId());
        }    
        return productPricebookMap;
    }

    /**
     * Query for the orgs standard pricebook
     * @param connection
     * @return
     * @throws Exception
     */
    private String fetchStandardPricebookId(PartnerConnection connection) throws Exception {
        var queryResult = connection.query("SELECT Id FROM Pricebook2 WHERE IsActive = TRUE AND IsStandard = TRUE");    
        if (queryResult.getSize() > 0) {
            return queryResult.getRecords()[0].getId();
        }
        return null;
    }

    /**
     * Generate sample Opportunities to pass to the Bulk API for processing
     * @param pricebookId
     * @param numberOfOpportunities
     * @return
     * @throws Exception
     */
    private ByteArrayOutputStream generateOpportunitiesCSV(String pricebookId, Integer numberOfOpportunities) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CSVPrinter csvPrinter = new CSVPrinter(
            new OutputStreamWriter(outputStream, StandardCharsets.UTF_8),
            CSVFormat.DEFAULT.builder().setHeader("Name", "StageName", "CloseDate", "Pricebook2Id").build());
        for (int i = 0; i < numberOfOpportunities; i++) {
            csvPrinter.printRecord("Sample Opportunity " + i, "Prospecting", "2025-12-31", pricebookId);
        }
        csvPrinter.flush();
        csvPrinter.close();
        return outputStream;
    }

    /**
     * Generate sample Opportunity Products to pass to the Bulk API for processing
     * @param opportunityIds
     * @param productPricebookMap
     * @return
     * @throws Exception
     */
    private ByteArrayOutputStream generateOpportunityProductsCSV(List<String> opportunityIds, Map<String, String> productPricebookMap) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CSVPrinter csvPrinter = new CSVPrinter(
            new OutputStreamWriter(outputStream, StandardCharsets.UTF_8),
            CSVFormat.DEFAULT.builder().setHeader("OpportunityId", "PricebookEntryId", "Product2Id", "Quantity", "UnitPrice").build());
        Random random = new Random();
        List<String> productIds = new ArrayList<>(productPricebookMap.keySet());
        for (String opportunityId : opportunityIds) {
            for (int j = 0; j < OPPORTUNITY_PRODUCTS_PER_OPPORTUNITY; j++) {
                String productId = productIds.get(random.nextInt(productIds.size()));
                String pricebookEntryId = productPricebookMap.get(productId);
                csvPrinter.printRecord(opportunityId, pricebookEntryId, productId, 1, 100.00);
            }
        }
        csvPrinter.flush();
        csvPrinter.close();
        return outputStream;
    }
    
    /**
     * Generate a list of Opportunities to delete to pass to the Bulk API for processing
     * @param opportunityIds
     * @return
     * @throws Exception
     */
    private ByteArrayOutputStream generateOpportunityDeletionCSV(List<String> opportunityIds) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CSVPrinter csvPrinter = new CSVPrinter(
            new OutputStreamWriter(outputStream, StandardCharsets.UTF_8),
            CSVFormat.DEFAULT.builder().setHeader("Id").build());    
        for (String opportunityId : opportunityIds) {
            csvPrinter.printRecord(opportunityId);
        }
        csvPrinter.flush();
        csvPrinter.close();
        return outputStream;
    }
    
    /**
     * Convert a Salesforce WSC PartnerConnection to a BulkdConnection
     * @param partnerConnection
     * @return
     * @throws ConnectionException
     * @throws AsyncApiException
     */
    private BulkConnection getBulkConnection(PartnerConnection partnerConnection) throws ConnectionException, AsyncApiException {
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(partnerConnection.getSessionHeader().getSessionId());
        String instanceUrl = partnerConnection.getConfig().getServiceEndpoint();
        config.setRestEndpoint(instanceUrl.replace("/services/Soap/u/", "/services/async/"));
        return new BulkConnection(config);
    }

    /**
     * Create a Bulkd API job
     * @param bulkConnection
     * @param sObjectType
     * @return
     * @throws Exception
     */
    private JobInfo createBulkJob(BulkConnection bulkConnection, String sObjectType) throws Exception {
        JobInfo job = new JobInfo();
        job.setObject(sObjectType);
        job.setOperation(OperationEnum.insert);
        job.setContentType(ContentType.CSV);
        job = bulkConnection.createJob(job);        
        logger.info("Created Bulk Job for {}: Job ID = {}", sObjectType, job.getId());
        return job;
    }

    /**
     * Close a Bulk API job 
     * @param bulkConnection
     * @param jobId
     * @throws AsyncApiException
     */
    private void closeBulkJob(BulkConnection bulkConnection, String jobId) throws AsyncApiException {
        JobInfo job = new JobInfo();
        job.setId(jobId);
        job.setState(JobStateEnum.Closed);
        bulkConnection.updateJob(job);
        logger.info("Closed Bulk Job: {}", jobId);
    }

    /**
     * Submits a Bulk API job
     * @param bulkConnection
     * @param job
     * @param csvStream
     * @throws Exception
     */
    private void submitBatch(BulkConnection bulkConnection, JobInfo job, ByteArrayOutputStream csvStream) throws Exception {
        ByteArrayInputStream batchStream = new ByteArrayInputStream(csvStream.toByteArray());        
        BatchInfo batchInfo = bulkConnection.createBatchFromStream(job, batchStream);        
        logger.info("Submitted batch for Job ID {}: Batch ID = {}", job.getId(), batchInfo.getId());
    }

    /**
     * Wait for a Bulk API job to complete and output status information to the log
     * @param bulkConnection
     * @param jobId
     * @throws Exception
     */
    private void waitForBulkJobCompletion(BulkConnection bulkConnection, String jobId) throws Exception {
        while (true) {
            try {
                Thread.sleep(5000);
                BatchInfo[] batchInfoList = bulkConnection.getBatchInfoList(jobId).getBatchInfo();
                for (BatchInfo bi : batchInfoList) {
                    logger.info("Batch {} - State: {} - Records Processed: {} - Records Failed: {}", bi.getId(), bi.getState(), bi.getNumberRecordsProcessed(), bi.getNumberRecordsFailed());
                    if (bi.getState() == BatchStateEnum.Completed || bi.getState() == BatchStateEnum.Failed) {
                        logger.info("Batch processing complete.");
                        return;
                    }
                }
            } catch (AsyncApiException e) {
                logger.error("Error fetching batch status: {}", e.getMessage());
                return;
            }
        }
    }        

    /**
     * Helper function that understands the query more pattern to ensure all records are retrieved
     * @param connection
     * @param soql
     * @return
     * @throws ConnectionException
     */
    private List<SObject> queryAllRecords(PartnerConnection connection, String soql) throws ConnectionException {
        List<SObject> allRecords = new ArrayList<>();        
        QueryResult queryResult = connection.query(soql);
        while (queryResult != null) {
            SObject[] records = queryResult.getRecords();
            if (records != null) {
                allRecords.addAll(Arrays.asList(records));
            }
            if (queryResult.isDone()) {
                break;
            }
            queryResult = connection.queryMore(queryResult.getQueryLocator());
        }
        return allRecords;
    }

    /**
     * Create Salesforce WSC connections
     * @param sessionId
     * @param instanceUrl
     * @return
     */
    private PartnerConnection createSalesforceConnection(String sessionId, String instanceUrl) {
        try {
            ConnectorConfig config = new ConnectorConfig();
            config.setServiceEndpoint(instanceUrl);
            config.setSessionId(sessionId);
            return Connector.newConnection(config);
        } catch (Exception e) {
            logger.error("Error creating Salesforce connection: {}", e.getMessage(), e);
            return null;
        }
    }
}
