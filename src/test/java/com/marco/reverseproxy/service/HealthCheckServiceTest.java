package com.marco.reverseproxy.service;

import com.marco.reverseproxy.config.ProxyConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthCheckServiceTest {

    private HealthCheckService healthCheckService;
    private ServiceRegistry serviceRegistry;
    private ProxyConfiguration proxyConfiguration;
    
    @Mock
    private WebClient webClient;
    
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    
    @Mock
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        proxyConfiguration = new ProxyConfiguration();
        List<ProxyConfiguration.ServiceConfig> services = new ArrayList<>();
        
        ProxyConfiguration.ServiceConfig service = new ProxyConfiguration.ServiceConfig();
        service.setName("test-service");
        service.setDomain("test.example.com");
        
        List<ProxyConfiguration.HostConfig> hosts = new ArrayList<>();
        ProxyConfiguration.HostConfig host = new ProxyConfiguration.HostConfig();
        host.setAddress("localhost");
        host.setPort(9090);
        host.setHealthy(true);
        hosts.add(host);
        
        service.setHosts(hosts);
        services.add(service);
        
        proxyConfiguration.setServices(services);
        
        serviceRegistry = spy(new ServiceRegistry(proxyConfiguration));
        
        WebClient.Builder webClientBuilder = mock(WebClient.Builder.class);
        when(webClientBuilder.build()).thenReturn(webClient);
        
        healthCheckService = new HealthCheckService(serviceRegistry, webClientBuilder);
    }

    @Test
    void performHealthChecks_shouldMarkHostHealthyOnSuccess() {
        // Setup mock WebClient behavior
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.empty());

        ProxyConfiguration.ServiceConfig service = serviceRegistry.getProxyConfiguration()
                .getServices().get(0);
        ProxyConfiguration.HostConfig host = service.getHosts().get(0);

        assertTrue(host.isHealthy());

        healthCheckService.performHealthChecks();

        verify(serviceRegistry, never()).markHostUnhealthy(any(), any());
    }

    @Test
    void performHealthChecks_shouldMarkHostUnhealthyOnFailure() {
        // Setup mock WebClient to return error
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity())
                .thenReturn(Mono.error(WebClientResponseException.create(
                        500, "Internal Server Error", null, null, null)));

        ProxyConfiguration.ServiceConfig service = serviceRegistry.getProxyConfiguration()
                .getServices().get(0);
        ProxyConfiguration.HostConfig host = service.getHosts().get(0);

        assertTrue(host.isHealthy());

        healthCheckService.performHealthChecks();

        // Wait for async operation
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        verify(serviceRegistry).markHostUnhealthy(service, host);
    }

    @Test
    void performHealthChecks_shouldUpdateHealthyStatusToHealthy() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getProxyConfiguration()
                .getServices().get(0);
        ProxyConfiguration.HostConfig host = service.getHosts().get(0);
        
        // Mark as unhealthy first
        serviceRegistry.markHostUnhealthy(service, host);
        assertFalse(host.isHealthy());

        // Setup mock for success
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.empty());

        healthCheckService.performHealthChecks();

        // Wait for async operation
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        verify(serviceRegistry).markHostHealthy(service, host);
    }

    @Test
    void performHealthChecks_shouldHandleTimeout() {
        // Setup mock WebClient to timeout
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity())
                .thenReturn(Mono.delay(Duration.ofSeconds(5))
                        .then(Mono.empty()));

        ProxyConfiguration.ServiceConfig service = serviceRegistry.getProxyConfiguration()
                .getServices().get(0);
        ProxyConfiguration.HostConfig host = service.getHosts().get(0);

        assertTrue(host.isHealthy());

        healthCheckService.performHealthChecks();

        // Wait for timeout
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        verify(serviceRegistry).markHostUnhealthy(service, host);
    }

    @Test
    void performHealthChecks_shouldCheckAllServices() {
        // Add another service
        ProxyConfiguration.ServiceConfig service2 = new ProxyConfiguration.ServiceConfig();
        service2.setName("service2");
        service2.setDomain("service2.example.com");
        
        List<ProxyConfiguration.HostConfig> hosts2 = new ArrayList<>();
        ProxyConfiguration.HostConfig host2 = new ProxyConfiguration.HostConfig();
        host2.setAddress("localhost");
        host2.setPort(9091);
        host2.setHealthy(true);
        hosts2.add(host2);
        
        service2.setHosts(hosts2);
        proxyConfiguration.getServices().add(service2);

        // Setup mock for both health checks
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.empty());

        healthCheckService.performHealthChecks();

        // Verify health check was called for both hosts
        verify(webClient, times(2)).get();
    }

    @Test
    void performHealthChecks_shouldUseHealthEndpoint() {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.empty());

        healthCheckService.performHealthChecks();

        verify(requestHeadersUriSpec).uri("http://localhost:9090/health");
    }

    @Test
    void performHealthChecks_shouldHandleMultipleHostsPerService() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getProxyConfiguration()
                .getServices().get(0);
        
        // Add another host
        ProxyConfiguration.HostConfig host2 = new ProxyConfiguration.HostConfig();
        host2.setAddress("localhost");
        host2.setPort(9091);
        host2.setHealthy(true);
        service.getHosts().add(host2);

        // First host succeeds, second fails
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity())
                .thenReturn(Mono.empty())
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        healthCheckService.performHealthChecks();

        // Wait for async operations
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        verify(serviceRegistry, never()).markHostUnhealthy(eq(service), eq(service.getHosts().get(0)));
        verify(serviceRegistry).markHostUnhealthy(service, host2);
    }
}
