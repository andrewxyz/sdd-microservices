package com.example.learnhub.probe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Throwaway service for the SC-007 walkthrough: no registration, config or health code. */
@SpringBootApplication
@RestController
public class ProbeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProbeApplication.class, args);
    }

    @GetMapping("/api/v1/probe/ping")
    public String ping() {
        return "probe alive";
    }
}
