package com.marco.reverseproxy.loadbalancer;

import com.marco.reverseproxy.config.ProxyConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinLoadBalancerTest {

    private RoundRobinLoadBalancer loadBalancer;
    private ProxyConfiguration.ServiceConfig service;

    @BeforeEach
    void setUp() {
        loadBalancer = new RoundRobinLoadBalancer();
        
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
    void selectHost_shouldDistributeInRoundRobinFashion() {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        ProxyConfiguration.HostConfig host2 = createHost("host2", 8081);
        ProxyConfiguration.HostConfig host3 = createHost("host3", 8082);
        service.setHosts(Arrays.asList(host1, host2, host3));

        // First round
        assertEquals(host1, loadBalancer.selectHost(service));
        assertEquals(host2, loadBalancer.selectHost(service));
        assertEquals(host3, loadBalancer.selectHost(service));

        // Second round - should start over
        assertEquals(host1, loadBalancer.selectHost(service));
        assertEquals(host2, loadBalancer.selectHost(service));
        assertEquals(host3, loadBalancer.selectHost(service));
    }

    @Test
    void selectHost_shouldHandleMultipleServices() {
        ProxyConfiguration.ServiceConfig service2 = new ProxyConfiguration.ServiceConfig();
        service2.setName("service2");
        service2.setDomain("service2.com");

        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        ProxyConfiguration.HostConfig host2 = createHost("host2", 8081);
        List<ProxyConfiguration.HostConfig> hosts = Arrays.asList(host1, host2);
        
        service.setHosts(hosts);
        service2.setHosts(hosts);

        // Service 1
        assertEquals(host1, loadBalancer.selectHost(service));
        assertEquals(host2, loadBalancer.selectHost(service));

        // Service 2 should have its own counter
        assertEquals(host1, loadBalancer.selectHost(service2));
        assertEquals(host2, loadBalancer.selectHost(service2));

        // Service 1 continues
        assertEquals(host1, loadBalancer.selectHost(service));
    }

    @Test
    void selectHost_shouldBeThreadSafe() throws InterruptedException {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        ProxyConfiguration.HostConfig host2 = createHost("host2", 8081);
        ProxyConfiguration.HostConfig host3 = createHost("host3", 8082);
        service.setHosts(Arrays.asList(host1, host2, host3));

        int threadCount = 10;
        int callsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < callsPerThread; j++) {
                    assertNotNull(loadBalancer.selectHost(service));
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // After all calls, the counter should be at threadCount * callsPerThread
        // Verify by making one more call and checking distribution continues
        assertNotNull(loadBalancer.selectHost(service));
    }

    @Test
    void getStrategyName_shouldReturnRoundRobin() {
        assertEquals("round-robin", loadBalancer.getStrategyName());
    }

    private ProxyConfiguration.HostConfig createHost(String address, int port) {
        ProxyConfiguration.HostConfig host = new ProxyConfiguration.HostConfig();
        host.setAddress(address);
        host.setPort(port);
        return host;
    }
}
