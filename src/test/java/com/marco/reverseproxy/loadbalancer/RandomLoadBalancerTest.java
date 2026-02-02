package com.marco.reverseproxy.loadbalancer;

import com.marco.reverseproxy.config.ProxyConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RandomLoadBalancerTest {

    private RandomLoadBalancer loadBalancer;
    private ProxyConfiguration.ServiceConfig service;

    @BeforeEach
    void setUp() {
        loadBalancer = new RandomLoadBalancer();

        service = new ProxyConfiguration.ServiceConfig();
        service.setName("test-service");
        service.setDomain("test.com");
    }

    @Test
    void selectHost_shouldReturnNullForEmptyList() {
        service.setHosts(Collections.emptyList());
        
        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(service);

        assertNull(selected);
    }

    @Test
    void selectHost_shouldReturnNullForNullList() {
        service.setHosts(null);
        
        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(service);

        assertNull(selected);
    }

    @Test
    void selectHost_shouldReturnOnlyHostForSingleHost() {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        service.setHosts(Collections.singletonList(host1));

        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(service);

        assertEquals(host1, selected);
    }

    @Test
    void selectHost_shouldReturnOneOfTheHosts() {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        ProxyConfiguration.HostConfig host2 = createHost("host2", 8081);
        ProxyConfiguration.HostConfig host3 = createHost("host3", 8082);
        List<ProxyConfiguration.HostConfig> hosts = Arrays.asList(host1, host2, host3);
        service.setHosts(hosts);

        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(service);

        assertNotNull(selected);
        assertTrue(hosts.contains(selected));
    }


    @Test
    void getStrategyName_shouldReturnRandom() {
        assertEquals("random", loadBalancer.getStrategyName());
    }


    private ProxyConfiguration.HostConfig createHost(String address, int port) {
        ProxyConfiguration.HostConfig host = new ProxyConfiguration.HostConfig();
        host.setAddress(address);
        host.setPort(port);
        return host;
    }

}
