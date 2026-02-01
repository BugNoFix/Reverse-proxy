package com.marco.reverseproxy.loadbalancer;

import com.marco.reverseproxy.config.ProxyConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(
                Collections.emptyList(), service);

        assertNull(selected);
    }

    @Test
    void selectHost_shouldReturnNullForNullList() {
        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(null, service);

        assertNull(selected);
    }

    @Test
    void selectHost_shouldReturnOnlyHostForSingleHost() {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        List<ProxyConfiguration.HostConfig> hosts = Collections.singletonList(host1);

        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(hosts, service);

        assertEquals(host1, selected);
    }

    @Test
    void selectHost_shouldReturnOneOfTheHosts() {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        ProxyConfiguration.HostConfig host2 = createHost("host2", 8081);
        ProxyConfiguration.HostConfig host3 = createHost("host3", 8082);
        List<ProxyConfiguration.HostConfig> hosts = Arrays.asList(host1, host2, host3);

        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(hosts, service);

        assertNotNull(selected);
        assertTrue(hosts.contains(selected));
    }

    @Test
    void selectHost_shouldDistributeAcrossAllHosts() {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        ProxyConfiguration.HostConfig host2 = createHost("host2", 8081);
        ProxyConfiguration.HostConfig host3 = createHost("host3", 8082);
        List<ProxyConfiguration.HostConfig> hosts = Arrays.asList(host1, host2, host3);

        Set<ProxyConfiguration.HostConfig> selectedHosts = new HashSet<>();
        
        // Make many selections to increase probability of hitting all hosts
        for (int i = 0; i < 100; i++) {
            ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(hosts, service);
            selectedHosts.add(selected);
        }

        // With random selection over 100 iterations, we should hit all hosts
        // (probability of missing a host is extremely low)
        assertEquals(3, selectedHosts.size());
        assertTrue(selectedHosts.contains(host1));
        assertTrue(selectedHosts.contains(host2));
        assertTrue(selectedHosts.contains(host3));
    }

    @Test
    void selectHost_shouldReturnDifferentHostsOverMultipleCalls() {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        ProxyConfiguration.HostConfig host2 = createHost("host2", 8081);
        List<ProxyConfiguration.HostConfig> hosts = Arrays.asList(host1, host2);

        Set<ProxyConfiguration.HostConfig> selectedHosts = new HashSet<>();
        
        for (int i = 0; i < 20; i++) {
            selectedHosts.add(loadBalancer.selectHost(hosts, service));
            if (selectedHosts.size() == 2) {
                break; // Both hosts have been selected
            }
        }

        // With 2 hosts and 20 calls, should get both
        assertEquals(2, selectedHosts.size());
    }

    @Test
    void selectHost_shouldHandleMultipleHosts() {
        List<ProxyConfiguration.HostConfig> hosts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            hosts.add(createHost("host" + i, 8080 + i));
        }

        for (int i = 0; i < 50; i++) {
            ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(hosts, service);
            assertNotNull(selected);
            assertTrue(hosts.contains(selected));
        }
    }

    @Test
    void getStrategyName_shouldReturnRandom() {
        assertEquals("random", loadBalancer.getStrategyName());
    }

    @Test
    void selectHost_shouldBeThreadSafe() throws InterruptedException {
        ProxyConfiguration.HostConfig host1 = createHost("host1", 8080);
        ProxyConfiguration.HostConfig host2 = createHost("host2", 8081);
        ProxyConfiguration.HostConfig host3 = createHost("host3", 8082);
        List<ProxyConfiguration.HostConfig> hosts = Arrays.asList(host1, host2, host3);

        int threadCount = 10;
        int callsPerThread = 100;
        Thread[] threads = new Thread[threadCount];
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < callsPerThread; j++) {
                        ProxyConfiguration.HostConfig selected = loadBalancer.selectHost(hosts, service);
                        assertNotNull(selected);
                        assertTrue(hosts.contains(selected));
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent access");
    }

    private ProxyConfiguration.HostConfig createHost(String address, int port) {
        ProxyConfiguration.HostConfig host = new ProxyConfiguration.HostConfig();
        host.setAddress(address);
        host.setPort(port);
        return host;
    }
}
