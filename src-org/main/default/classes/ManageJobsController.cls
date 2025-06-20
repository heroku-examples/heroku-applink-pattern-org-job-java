public with sharing class ManageJobsController {

    @AuraEnabled(cacheable=false)
    public static String startJob() {
        HerokuAppLink.GenerateQuoteJob service = new HerokuAppLink.GenerateQuoteJob();
        HerokuAppLink.GenerateQuoteJob.executeBatch_Request request = new HerokuAppLink.GenerateQuoteJob.executeBatch_Request();
        HerokuAppLink.GenerateQuoteJob_BatchExecutionRequest body = new HerokuAppLink.GenerateQuoteJob_BatchExecutionRequest();
        body.soqlWhereClause = 'Name LIKE \'Sample Opportunity%\'';
        request.body = body;
        return service.executeBatch(request).Code200.jobId;
    }
}