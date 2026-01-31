package com.marco.reverseproxy.loadbalancer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and managing load balancer instances
 */
@Slf4j
@Component
public class LoadBalancerFactory {
    
    private final Map<String, LoadBalancer> loadBalancers;

    public LoadBalancerFactory(List<LoadBalancer> loadBalancerList) {
        this.loadBalancers = new ConcurrentHashMap<>();
        loadBalancerList.forEach(lb -> {
            loadBalancers.put(lb.getStrategyName(), lb);
            log.info("Registered load balancer strategy: {}", lb.getStrategyName());
        });
    }

    public LoadBalancer getLoadBalancer(String strategyName) {
        LoadBalancer loadBalancer = loadBalancers.get(strategyName);
        if (loadBalancer == null) {
            log.warn("Load balancer strategy '{}' not found, using 'random' as default", strategyName);
            loadBalancer = loadBalancers.get("random");
        }
        return loadBalancer;
    }
}
