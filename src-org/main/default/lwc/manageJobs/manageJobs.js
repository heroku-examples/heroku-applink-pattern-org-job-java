import { LightningElement } from 'lwc';
import startJob from '@salesforce/apex/ManageJobsController.startJob';
import {
    subscribe,
    unsubscribe,
    onError,
    setDebugFlag,
    isEmpEnabled,
} from 'lightning/empApi';

export default class ManageJobs extends LightningElement {
    progress = 0;
    channelName = '/event/JobProgress__e';
    isSubscribeDisabled = false;
    isUnsubscribeDisabled = !this.isSubscribeDisabled;
    subscription = {};
    startTime; // Added to track start time
    elapsedTime = ''; // Added to store elapsed time

    // Tracks changes to channelName text field
    handleChannelName(event) {
        this.channelName = event.target.value;
    }

    // Initializes the component
    connectedCallback() {
        // Register error listener
        this.registerErrorListener();
    }

    // Handles subscribe button click
    async handleStart() {
        this.startTime = new Date(); // Start time recorded
        this.progress = 0;
        this.elapsedTime = '';
        this.isSubscribeDisabled = true;

        // Callback invoked whenever a new event message is received
        const messageCallbackHandler = function (response) {
            console.log('This is ' + JSON.stringify(this));
            console.log('New message received: ', JSON.stringify(response));
            console.log('Progress is ' + response.data.payload.Progress__c);
            this.progress = response.data.payload.Progress__c;
            // Response contains the payload of the new message received

            // Check for 100% progress
            if (this.progress === 100) {
                const endTime = new Date();
                const elapsed = (endTime - this.startTime) / 1000; // seconds
                this.elapsedTime = `Completed in ${elapsed.toFixed(2)} seconds`;
                this.isSubscribeDisabled = false;
                this.handleUnsubscribe(); // Optionally unsubscribe
            }
        };
        const messageCallback = messageCallbackHandler.bind(this);

        // Invoke subscribe method of empApi. Pass reference to messageCallback
        subscribe(this.channelName, -1, messageCallback).then((response) => {
            // Response contains the subscription information on subscribe call
            console.log(
                'Subscription request sent to: ',
                JSON.stringify(response.channel)
            );
            this.subscription = response;
            this.toggleSubscribeButton(true);
        });

        // Start the job
        const jobId = await startJob();
        console.log('Job id is ' + jobId);
    }

    // Handles unsubscribe button click
    handleUnsubscribe() {
        this.toggleSubscribeButton(false);

        // Invoke unsubscribe method of empApi
        unsubscribe(this.subscription, (response) => {
            console.log('unsubscribe() response: ', JSON.stringify(response));
            // Response is true for successful unsubscribe
        });
    }

    toggleSubscribeButton(enableSubscribe) {
        this.isSubscribeDisabled = enableSubscribe;
        this.isUnsubscribeDisabled = !enableSubscribe;
    }

    registerErrorListener() {
        // Invoke onError empApi method
        onError((error) => {
            console.log('Received error from server: ', JSON.stringify(error));
            // Error contains the server-side error
        });
    }    
}
