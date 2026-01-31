package com.marco.reverseproxy.service;

import com.marco.reverseproxy.config.ProxyConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Health check service to monitor downstream service instances
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class HealthCheckService {

    private final ServiceRegistry serviceRegistry;
    private final WebClient.Builder webClientBuilder;

    /**
     * Perform health checks on all configured hosts every 30 seconds
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 2000)
    public void performHealthChecks() {
        log.debug("Starting health check cycle");
        
        serviceRegistry.getProxyConfiguration().getServices().forEach(service -> {
            service.getHosts().forEach(host -> checkHost(service, host));
        });
    }

    private void checkHost(ProxyConfiguration.ServiceConfig service, ProxyConfiguration.HostConfig host) {
        String healthCheckUrl = String.format("http://%s:%d/health", host.getAddress(), host.getPort());
        
        WebClient webClient = webClientBuilder.build();
        
        webClient.get()
                .uri(healthCheckUrl)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .doOnSuccess(response -> {
                    if (!host.isHealthy()) {
                        serviceRegistry.markHostHealthy(service, host);
                    }
                })
                .onErrorResume(error -> {
                    log.debug("Health check failed for {}:{} - {}", 
                            host.getAddress(), host.getPort(), error.getMessage());
                    if (host.isHealthy()) {
                        serviceRegistry.markHostUnhealthy(service, host);
                    }
                    return Mono.empty();
                })
                .subscribe();
    }
}
