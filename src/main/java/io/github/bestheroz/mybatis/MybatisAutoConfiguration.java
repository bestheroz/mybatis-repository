package io.github.bestheroz.mybatis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration
public class MybatisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MybatisProperties mybatisProperties() {
        return new MybatisProperties();
    }
}
