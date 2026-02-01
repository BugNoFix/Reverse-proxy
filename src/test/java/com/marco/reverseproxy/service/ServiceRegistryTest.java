package com.marco.reverseproxy.service;

import com.marco.reverseproxy.config.ProxyConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServiceRegistryTest {

    private ServiceRegistry serviceRegistry;
    private ProxyConfiguration proxyConfiguration;

    @BeforeEach
    void setUp() {
        proxyConfiguration = new ProxyConfiguration();
        
        ProxyConfiguration.ListenConfig listenConfig = new ProxyConfiguration.ListenConfig();
        listenConfig.setAddress("0.0.0.0");
        listenConfig.setPort(8080);
        proxyConfiguration.setListen(listenConfig);

        List<ProxyConfiguration.ServiceConfig> services = new ArrayList<>();
        
        // Service 1
        ProxyConfiguration.ServiceConfig service1 = new ProxyConfiguration.ServiceConfig();
        service1.setName("api-service");
        service1.setDomain("api.example.com");
        service1.setStrategy("round-robin");
        
        List<ProxyConfiguration.HostConfig> hosts1 = new ArrayList<>();
        hosts1.add(createHost("localhost", 9090, true));
        hosts1.add(createHost("localhost", 9091, true));
        service1.setHosts(hosts1);
        
        services.add(service1);
        
        // Service 2
        ProxyConfiguration.ServiceConfig service2 = new ProxyConfiguration.ServiceConfig();
        service2.setName("web-service");
        service2.setDomain("web.example.com");
        service2.setStrategy("random");
        
        List<ProxyConfiguration.HostConfig> hosts2 = new ArrayList<>();
        hosts2.add(createHost("localhost", 9092, true));
        hosts2.add(createHost("localhost", 9093, false)); // Unhealthy
        service2.setHosts(hosts2);
        
        services.add(service2);
        
        proxyConfiguration.setServices(services);
        
        serviceRegistry = new ServiceRegistry(proxyConfiguration);
    }

    @Test
    void getServiceByDomain_shouldReturnServiceForValidDomain() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("api.example.com");

        assertNotNull(service);
        assertEquals("api-service", service.getName());
        assertEquals("api.example.com", service.getDomain());
    }

    @Test
    void getServiceByDomain_shouldBeCaseInsensitive() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("API.EXAMPLE.COM");

        assertNotNull(service);
        assertEquals("api-service", service.getName());
    }

    @Test
    void getServiceByDomain_shouldHandlePortInDomain() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("api.example.com:8080");

        assertNotNull(service);
        assertEquals("api-service", service.getName());
    }

    @Test
    void getServiceByDomain_shouldReturnNullForUnknownDomain() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("unknown.example.com");

        assertNull(service);
    }

    @Test
    void getServiceByDomain_shouldReturnNullForNullDomain() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain(null);

        assertNull(service);
    }

    @Test
    void getHealthyHosts_shouldReturnOnlyHealthyHosts() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("web.example.com");
        List<ProxyConfiguration.HostConfig> healthyHosts = serviceRegistry.getHealthyHosts(service);

        assertEquals(1, healthyHosts.size());
        assertEquals(9092, healthyHosts.get(0).getPort());
    }

    @Test
    void getHealthyHosts_shouldReturnAllHealthyHosts() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("api.example.com");
        List<ProxyConfiguration.HostConfig> healthyHosts = serviceRegistry.getHealthyHosts(service);

        assertEquals(2, healthyHosts.size());
    }

    @Test
    void markHostUnhealthy_shouldUpdateHostStatus() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("api.example.com");
        ProxyConfiguration.HostConfig host = service.getHosts().get(0);
        
        assertTrue(host.isHealthy());
        
        serviceRegistry.markHostUnhealthy(service, host);
        
        assertFalse(host.isHealthy());
        assertTrue(host.getLastHealthCheck() > 0);
    }

    @Test
    void markHostHealthy_shouldUpdateHostStatus() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("web.example.com");
        ProxyConfiguration.HostConfig host = service.getHosts().get(1); // Unhealthy host
        
        assertFalse(host.isHealthy());
        
        serviceRegistry.markHostHealthy(service, host);
        
        assertTrue(host.isHealthy());
        assertTrue(host.getLastHealthCheck() > 0);
    }

    @Test
    void markHostUnhealthy_shouldUpdateHealthyHostsList() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("api.example.com");
        ProxyConfiguration.HostConfig host = service.getHosts().get(0);
        
        assertEquals(2, serviceRegistry.getHealthyHosts(service).size());
        
        serviceRegistry.markHostUnhealthy(service, host);
        
        assertEquals(1, serviceRegistry.getHealthyHosts(service).size());
    }

    @Test
    void markHostHealthy_shouldUpdateHealthyHostsList() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("web.example.com");
        ProxyConfiguration.HostConfig host = service.getHosts().get(1); // Unhealthy host
        
        assertEquals(1, serviceRegistry.getHealthyHosts(service).size());
        
        serviceRegistry.markHostHealthy(service, host);
        
        assertEquals(2, serviceRegistry.getHealthyHosts(service).size());
    }

    @Test
    void getProxyConfiguration_shouldReturnConfiguration() {
        ProxyConfiguration config = serviceRegistry.getProxyConfiguration();

        assertNotNull(config);
        assertEquals(2, config.getServices().size());
    }

    @Test
    void serviceRegistry_shouldInitializeWithEmptyServices() {
        ProxyConfiguration emptyConfig = new ProxyConfiguration();
        emptyConfig.setServices(null);
        
        ServiceRegistry emptyRegistry = new ServiceRegistry(emptyConfig);
        
        assertNull(emptyRegistry.getServiceByDomain("any.example.com"));
    }

    @Test
    void getHealthyHosts_shouldReturnEmptyListWhenAllUnhealthy() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("api.example.com");
        
        // Mark all hosts as unhealthy
        service.getHosts().forEach(host -> serviceRegistry.markHostUnhealthy(service, host));
        
        List<ProxyConfiguration.HostConfig> healthyHosts = serviceRegistry.getHealthyHosts(service);
        
        assertTrue(healthyHosts.isEmpty());
    }

    @Test
    void serviceRegistry_shouldHandleMultipleServices() {
        assertNotNull(serviceRegistry.getServiceByDomain("api.example.com"));
        assertNotNull(serviceRegistry.getServiceByDomain("web.example.com"));
        
        ProxyConfiguration.ServiceConfig service1 = serviceRegistry.getServiceByDomain("api.example.com");
        ProxyConfiguration.ServiceConfig service2 = serviceRegistry.getServiceByDomain("web.example.com");
        
        assertNotEquals(service1, service2);
        assertEquals("api-service", service1.getName());
        assertEquals("web-service", service2.getName());
    }

    private ProxyConfiguration.HostConfig createHost(String address, int port, boolean healthy) {
        ProxyConfiguration.HostConfig host = new ProxyConfiguration.HostConfig();
        host.setAddress(address);
        host.setPort(port);
        host.setHealthy(healthy);
        return host;
    }
}
