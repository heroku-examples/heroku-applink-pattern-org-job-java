package com.heroku.java;

import java.util.concurrent.CountDownLatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class PricingEngineApplication {

    public static void main(String[] args) throws InterruptedException {
        ApplicationContext context = SpringApplication.run(PricingEngineApplication.class, args);
        if(context.getEnvironment().matchesProfiles("worker")) {
            System.out.println("Worker profile is active. Waiting indefinitely...");
            CountDownLatch latch = new CountDownLatch(1);
            latch.await();
        }
    }
}
