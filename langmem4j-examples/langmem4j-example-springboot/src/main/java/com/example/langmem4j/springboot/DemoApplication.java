package com.example.langmem4j.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * langMem4j + Spring Boot in one file: the starter auto-configures a
 * MemoryManager bean from application.yml — no @Bean, no config class.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
