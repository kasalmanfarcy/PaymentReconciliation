package com.reconflow.paymentreconflow.shared.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the rate-limit interceptor for the API (M10.4). */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    RateLimitConfig(RateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
