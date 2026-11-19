package dev.taskforge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties("taskforge")
public record Settings(String role, String instanceId, String authTokens, String storageEndpoint,
                       String storageAccessKey, String storageSecretKey, String storageBucket,
                       int leaseSeconds, int deadlineSeconds, int maxActiveJobs,
                       int workerConcurrency, int maxAttempts) {
    public Settings {
        if (leaseSeconds < 3 || deadlineSeconds < 1 || maxActiveJobs < 1 || workerConcurrency < 1 || workerConcurrency > 16
                || maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("Invalid resource limits");
    }
}
@Configuration
@EnableConfigurationProperties(Settings.class)
class SettingsConfiguration {}
