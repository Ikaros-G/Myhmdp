package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * redis ID生成器
 *
 * @author CHEN
 * @date 2022/10/09
 */
@Component
public class RedisIdWorker {
    /**
     * 初始时间戳
     */
    private static final Long BEGIN_TIMESTAMP = 1778774400L;
    /**
     * 序列号位数
     */
    private static final Integer COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成全局唯一id
     *
     * @param keyPrefix 前缀
     * @return {@link Long}
     */
    public Long nextId(String keyPrefix) {
        // 生成时间戳差值
        long timestamp = System.currentTimeMillis() / 1000 - BEGIN_TIMESTAMP;
        // 生成序列号
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long SerialNumber = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 返回 0 + 时间戳 + 序列化
        return timestamp << COUNT_BITS | SerialNumber;
    }

}
