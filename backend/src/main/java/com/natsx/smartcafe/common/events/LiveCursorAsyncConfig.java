package com.natsx.smartcafe.common.events;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Await-style /live-cursor?after= requests use Servlet async dispatch, so an
 * HTTP worker is not blocked for up to 14 seconds waiting for ORDER/TABLE
 * notifications. The long poll still has hard per-identity/global admission
 * limits; virtual threads are not an excuse for unbounded subscriptions.
 */
@Configuration
public class LiveCursorAsyncConfig implements WebMvcConfigurer {
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(new VirtualThreadTaskExecutor("scs-live-async-"));
        // Service max wait 14s; leave room for dispatch and serialization.
        configurer.setDefaultTimeout(20_000L);
    }
}
