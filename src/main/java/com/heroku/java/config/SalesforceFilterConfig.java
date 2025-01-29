package com.heroku.java.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SalesforceFilterConfig {

    @Bean
    FilterRegistrationBean<SalesforceClientContextFilter> salesforceFilterRegistration() {
        FilterRegistrationBean<SalesforceClientContextFilter> registrationBean = new FilterRegistrationBean<>();
        SalesforceClientContextFilter filter = new SalesforceClientContextFilter();
        registrationBean.setFilter(filter);
        registrationBean.addUrlPatterns("/api/*");
        return registrationBean;
    }
}
