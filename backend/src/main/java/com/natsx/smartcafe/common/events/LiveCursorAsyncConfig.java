package com.natsx.smartcafe.common.events;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Defaults for DeferredResult live-cursor responses. The dedicated bridge
 * creates bounded virtual-thread workers with explicit cancellation.
 */
@Configuration
public class LiveCursorAsyncConfig implements WebMvcConfigurer {
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(20_000L);
    }
}
