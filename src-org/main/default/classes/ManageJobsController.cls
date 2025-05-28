public with sharing class ManageJobsController {

    @AuraEnabled(cacheable=false)
    public static String startJob() {
        ExternalService.GenerateQuoteJob service = new ExternalService.GenerateQuoteJob();
        ExternalService.GenerateQuoteJob.executeBatch_Request request = new ExternalService.GenerateQuoteJob.executeBatch_Request();
        ExternalService.GenerateQuoteJob_BatchExecutionRequest body = new ExternalService.GenerateQuoteJob_BatchExecutionRequest();
        body.soqlWhereClause = 'Name LIKE \'Sample Opportunity%\'';
        request.body = body;
        return service.executeBatch(request).Code200.jobId;
    }
}