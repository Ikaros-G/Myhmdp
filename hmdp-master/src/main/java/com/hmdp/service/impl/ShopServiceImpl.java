package com.hmdp.service.impl;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.RedisDate;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.constants.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.domain.geo.Metrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.hmdp.constants.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        Shop shop = cacheClient.queryWithLock(CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 结束
        return Result.ok(shop);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        // 获取店铺id
        Long id = shop.getId();
        // 判断店铺id是否存在
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    /**
     * 根据店铺类型分页查询，支持按距离排序
     * <p>当未提供经纬度时，按类型简单分页查询；
     * 当提供经纬度时，基于 Redis GEO 查询指定范围内的店铺并按距离升序排列。</p>
     *
     * @param typeId  店铺类型ID
     * @param current 当前页码
     * @param x       用户经度（可选，不为 null 时启用距离排序）
     * @param y       用户纬度（可选，不为 null 时启用距离排序）
     * @return 包含店铺列表的分页结果
     */
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        // 未传入经纬度：按类型普通分页
        if (x == null || y == null) {
            Page<Shop> shopPage = lambdaQuery().eq(Shop::getTypeId, typeId)
                    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            return Result.ok(shopPage.getRecords());
        }

        // 计算分页起止索引（from ~ end）
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end = current * SystemConstants.MAX_PAGE_SIZE;

        // 查询 GEO：从指定坐标向外搜索，获取 end 条记录（因 Redis 不支持分页偏移，只能一次查够）
        GeoResults<RedisGeoCommands.GeoLocation<String>> shoplist = stringRedisTemplate.opsForGeo()
                .radius(
                        SHOP_GEO_KEY + typeId,
                        new Circle(new Point(x, y), new Distance(5000, RedisGeoCommands.DistanceUnit.METERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );

        // 无结果时直接返回空
        if (shoplist == null || shoplist.getContent().isEmpty()) {
            return Result.ok();
        }

        // 内存中截取当前页数据（跳过 from 之前的记录）
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = shoplist.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        // 提取当前页店铺 ID 及对应的距离信息
        Map<String, Distance> distanceMap = new HashMap<>(shoplist.getContent().size());
        List<Long> shopIds = new ArrayList<>(shoplist.getContent().size());
        list.stream().skip(from).forEach(geoResult -> {
            String shopId = geoResult.getContent().getName();
            shopIds.add(Long.valueOf(shopId));
            distanceMap.put(shopId, geoResult.getDistance());
        });

        if (shopIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 按 ID 顺序查询数据库并设置距离
        List<Shop> shops = lambdaQuery().in(Shop::getId, shopIds)
                .last("ORDER BY FIELD(id," + StrUtil.join(",", shopIds) + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }


}