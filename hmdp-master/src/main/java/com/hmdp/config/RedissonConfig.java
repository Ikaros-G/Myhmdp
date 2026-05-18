package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * redisson配置
 *
 * @author CHEN
 * @date 2022/10/10
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String host;
    @Value("${spring.redis.port}")
    private String port;
    @Value("${spring.redis.password}")
    private String password;
    @Bean
    public RedissonClient redissonClient(){
        // 创建配置对象
        Config config=new Config();
        // 创建单机模式的配置
        config.useSingleServer().setAddress("redis://"+ host +":"+port).setPassword(password);
        config.useSingleServer().setDatabase(0);    // 选择数据库
        return Redisson.create(config);
    }

}
