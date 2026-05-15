package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.constants.RedisConstants.*;

/**
 * redis工具
 *
 * @author CHEN
 * @date 2022/10/08
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     *  解决缓存穿透问题
     * @param KeyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(String KeyPrefix, ID id, Class<R> type,
                                         Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1 查询缓存
        String cacheshop = stringRedisTemplate.opsForValue().get(KeyPrefix + id);
        // 2 判断是否存在
        if (StrUtil.isNotBlank(cacheshop)){
            // 3 如果存在 返回java对象
            return JSONUtil.toBean(cacheshop, type);
        }
        // 如果为空字符
        if (cacheshop != null){
            return null;
        }
        // 4 不存在，查询数据库
        R r = dbFallback.apply(id);
        // 5 不存在，返回错误
        if (r == null){
            // 解决缓存穿透
            this.set(KeyPrefix + id, "", time, unit);
            return null;
        }
        // 6 写入缓存
        this.set(KeyPrefix + id, r, time, unit);
        // 结束
        return r;
    }

    /**
     *  互斥锁解决缓存击穿
     * @param KeyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithLock(String KeyPrefix, ID id, Class<R> type, String lockKey,Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1 查询缓存
        String cacheshop = stringRedisTemplate.opsForValue().get(KeyPrefix + id);
        // 2 判断是否存在
        if (StrUtil.isNotBlank(cacheshop)) {
            // 3 如果存在 返回java对象
            return JSONUtil.toBean(cacheshop, type);
        }
        // 如果为空字符，直接返回
        if (cacheshop != null){
            return null;
        }
        // 4 不存在，获取互斥锁
        boolean isLock = tryLock(lockKey + id);
        R r = null;
        try {
            if (!isLock) {
                // 获取锁失败，休眠一段时间
                Thread.sleep(50);
                return queryWithLock(KeyPrefix, id, type, lockKey, dbFallback, time, unit);
            }
            // 获取锁后，二次查询缓存，
            cacheshop = stringRedisTemplate.opsForValue().get(KeyPrefix + id);
            if (StrUtil.isNotBlank(cacheshop)) {
                // 3 如果存在 返回java对象
                return JSONUtil.toBean(cacheshop, type);
            }
            // 5 获取到锁，查询数据库
            r = dbFallback.apply(id);

            // 模拟耗时
            // Thread.sleep(2000);

            // 6 不存在，返回null
            if (r == null) {
                // 空字符串解决缓存穿透
                stringRedisTemplate.opsForValue().set(KeyPrefix + id, "", time, unit);
                return null;
            }
            // 7 写入缓存
            stringRedisTemplate.opsForValue().set(KeyPrefix + id, JSONUtil.toJsonStr(r));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 删除锁
            unLock(lockKey + id);
        }
        // 8 结束
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(String KeyPrefix, ID id, Class<R> type, String lockKey, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        // 1 查询缓存
        String cacheshop = stringRedisTemplate.opsForValue().get(KeyPrefix + id);
        // 2 判断是否命中
        if (StrUtil.isBlank(cacheshop)){
            return null;
        }

        // 3 如果命中,判断缓存是否过期
        RedisDate redisData = JSONUtil.toBean(cacheshop, RedisDate.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = BeanUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            // 4 如果未过期，返回数据
            return r;
        }
        // 5 如果已过期，获取锁
        boolean lock = tryLock(lockKey + id);
        if(lock){
            // 如果未过期，返回数据(二次查缓存)
            redisData = JSONUtil.toBean(cacheshop, RedisDate.class);
            jsonObject = (JSONObject) redisData.getData();
            r = BeanUtil.toBean(jsonObject, type);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                unLock(lockKey + id);
                return r;
            }
            // 6 获取锁成功，创建独立线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 7 重新查询数据库
                try {
                    R r1 = dbFallback.apply(id);
                    setWithLogicalExpire(KeyPrefix + id, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 8 释放锁
                    unLock(lockKey + id);
                }
            });
        }
        // 9 无论获取锁是否成功，都返回过期数据
        return r;

    }



    /**
     * 将任意对象序列化成json存入redis
     *
     * @param key   关键
     * @param value 价值
     * @param time  时间
     * @param unit  单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将任意对象序列化成json存入redis 并且携带逻辑过期时间
     *
     * @param key   关键
     * @param value 价值
     * @param time  时间
     * @param unit  单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //存入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     *  获取互斥锁
     * @param key
     * @return
     */
    public boolean tryLock(String key){
        Boolean bl = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(bl);
    }

    /**
     * 释放锁
     * @param key
     */
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
