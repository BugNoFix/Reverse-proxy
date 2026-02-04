package com.marco.reverseproxy.unit.service;

import com.marco.reverseproxy.config.ProxyConfiguration;
import com.marco.reverseproxy.service.ServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        hosts1.add(createHost("localhost", 9090));
        hosts1.add(createHost("localhost", 9091));
        service1.setHosts(hosts1);
        
        services.add(service1);
        
        // Service 2
        ProxyConfiguration.ServiceConfig service2 = new ProxyConfiguration.ServiceConfig();
        service2.setName("web-service");
        service2.setDomain("web.example.com");
        service2.setStrategy("random");
        
        List<ProxyConfiguration.HostConfig> hosts2 = new ArrayList<>();
        hosts2.add(createHost("localhost", 9092));
        hosts2.add(createHost("localhost", 9093));
        service2.setHosts(hosts2);
        
        services.add(service2);
        
        proxyConfiguration.setServices(services);
        
        serviceRegistry = new ServiceRegistry(proxyConfiguration);
    }

    @Test
    void getServiceByDomain_shouldReturnServiceForValidDomain() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("api.example.com", false);

        assertNotNull(service);
        assertEquals("api-service", service.getName());
        assertEquals("api.example.com", service.getDomain());
    }

    @Test
    void getServiceByDomain_shouldBeCaseInsensitive() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("API.EXAMPLE.COM", true);

        assertNotNull(service);
        assertEquals("api-service", service.getName());
    }

    @Test
    void getServiceByDomain_shouldHandlePortInDomain() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("api.example.com:8080", true);

        assertNotNull(service);
        assertEquals("api-service", service.getName());
    }

    @Test
    void getServiceByDomain_shouldReturnNullForUnknownDomain() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain("unknown.example.com", false);

        assertNull(service);
    }

    @Test
    void getServiceByDomain_shouldReturnNullForNullDomain() {
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain(null, false);

        assertNull(service);
    }

    @Test
    void getProxyConfiguration_shouldReturnConfiguration() {
        ProxyConfiguration config = serviceRegistry.getProxyConfiguration();

        assertNotNull(config);
        assertEquals(2, config.getServices().size());
    }

    @Test
    void serviceRegistry_shouldHandleMultipleServices() {
        assertNotNull(serviceRegistry.getServiceByDomain("api.example.com", false));
        assertNotNull(serviceRegistry.getServiceByDomain("web.example.com", false));
        
        ProxyConfiguration.ServiceConfig service1 = serviceRegistry.getServiceByDomain("api.example.com", false);
        ProxyConfiguration.ServiceConfig service2 = serviceRegistry.getServiceByDomain("web.example.com", false);
        
        assertNotEquals(service1, service2);
        assertEquals("api-service", service1.getName());
        assertEquals("web-service", service2.getName());
    }

    private ProxyConfiguration.HostConfig createHost(String address, int port) {
        ProxyConfiguration.HostConfig host = new ProxyConfiguration.HostConfig();
        host.setAddress(address);
        host.setPort(port);
        return host;
    }
}
