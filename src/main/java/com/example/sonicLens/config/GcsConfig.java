package com.example.sonicLens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gcp.storage")
@Data
public class GcsConfig {
    private String bucketName;
    private String projectId;
}
