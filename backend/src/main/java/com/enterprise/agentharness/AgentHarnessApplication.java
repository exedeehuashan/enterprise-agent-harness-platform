package com.enterprise.agentharness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class AgentHarnessApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentHarnessApplication.class, args);
    }
}
