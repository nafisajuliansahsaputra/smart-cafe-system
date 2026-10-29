package com.natsx.smartcafe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartCafeApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartCafeApplication.class, args);
    }
}
