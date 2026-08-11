package com.example.sample;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * No spring-configuration-metadata.json entry exists for these — this class exists specifically
 * to verify ConfigurationPropertiesContractProvider fills that gap.
 */
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    private String host;
    private Smtp smtp;
    private List<String> aliases;

    public static class Smtp {
        private int port;
    }
}
