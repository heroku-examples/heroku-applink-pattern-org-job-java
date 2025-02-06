global class OpportunityToQuoteBatch implements Database.Batchable<SObject>, Database.Stateful {
    
    global Database.QueryLocator start(Database.BatchableContext BC) {
        // Retrieve only Opportunities
        String query = 'SELECT Id FROM Opportunity WHERE Name LIKE \'Sample Opportunity%\'';
        return Database.getQueryLocator(query);
    }

    global void execute(Database.BatchableContext BC, List<SObject> scope) {
        // Extract Opportunity IDs
        List<Id> opportunityIds = new List<Id>();
        for (SObject obj : scope) {
            Opportunity opp = (Opportunity) obj;
            opportunityIds.add(opp.Id);
        }
        // Retrieve OpportunityLineItems separately
        Map<Id, List<OpportunityLineItem>> opportunityLineItemMap = new Map<Id, List<OpportunityLineItem>>();
        if (!opportunityIds.isEmpty()) {
            List<OpportunityLineItem> lineItems = 
                [SELECT Id, OpportunityId, Product2Id, Quantity, UnitPrice, PricebookEntryId 
                    FROM OpportunityLineItem 
                    WHERE OpportunityId IN :opportunityIds];
        for (OpportunityLineItem oli : lineItems) {
                if (!opportunityLineItemMap.containsKey(oli.OpportunityId)) {
                    opportunityLineItemMap.put(oli.OpportunityId, new List<OpportunityLineItem>());
                }
                opportunityLineItemMap.get(oli.OpportunityId).add(oli);
            }
        }
        // Prepare Quotes
        List<Quote> quotesToInsert = new List<Quote>();
        Map<Id, Id> opportunityToQuoteMap = new Map<Id, Id>();
        for (Id oppId : opportunityIds) {
            Quote newQuote = new Quote(
                Name = 'New Quote',
                OpportunityId = oppId
            );
            quotesToInsert.add(newQuote);
        }
        // Bulk insert Quotes
        if (!quotesToInsert.isEmpty()) {
            insert quotesToInsert;
        }
        // Map Quotes back to Opportunities
        for (Quote q : quotesToInsert) {
            opportunityToQuoteMap.put(q.OpportunityId, q.Id);
        }
        // Prepare QuoteLineItems
        List<QuoteLineItem> quoteLineItemsToInsert = new List<QuoteLineItem>();
        for (Id oppId : opportunityToQuoteMap.keySet()) {
            if (opportunityLineItemMap.containsKey(oppId)) {
                Id quoteId = opportunityToQuoteMap.get(oppId);
                for (OpportunityLineItem oli : opportunityLineItemMap.get(oppId)) {
                    Double discountRate = getDiscountForRegion('US'); // Assuming US region
                    Double discountedPrice = (oli.Quantity * oli.UnitPrice) * (1 - discountRate);                    
                    QuoteLineItem qli = new QuoteLineItem(
                        QuoteId = quoteId,
                        PricebookEntryId = oli.PricebookEntryId,
                        Quantity = oli.Quantity,
                        UnitPrice = discountedPrice / oli.Quantity
                    );
                    quoteLineItemsToInsert.add(qli);
                }
            }
        }
        // Bulk insert QuoteLineItems
        if (!quoteLineItemsToInsert.isEmpty()) {
            insert quoteLineItemsToInsert;
        }
    }

    global void finish(Database.BatchableContext BC) {
        System.debug('Batch Process Completed');
    }

    // Helper method to apply discounts based on region
    private Double getDiscountForRegion(String region) {
        if (region == 'US') return 0.10;
        if (region == 'EU') return 0.15;
        if (region == 'APAC') return 0.05;
        return 0.0;
    }
}
