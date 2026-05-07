package com.snail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置类
 */
@Configuration  // 改为 @Configuration
public class RestTemplateConfiguration {

  @Bean
  public RestTemplate restTemplate() {
    RestTemplate restTemplate = new RestTemplate();

    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(30000);      // 连接超时30秒
    factory.setReadTimeout(60000 * 10);    // 读取超时10分钟

    restTemplate.setRequestFactory(factory);
    return restTemplate;
  }
}