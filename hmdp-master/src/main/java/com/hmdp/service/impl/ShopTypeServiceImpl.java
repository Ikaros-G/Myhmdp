package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getTypeList() {
        // 1 查询缓存
        Long size = stringRedisTemplate.opsForList().size(RedisConstants.CACHE_TYPE_KEY);
        List<String> range = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_TYPE_KEY, 0, size - 1);
        // 2 如果存在，直接返回
        if (range != null && !range.isEmpty()){
            // 将数据转为List<shopType>
            List<ShopType> shopTypes = new ArrayList<>();
            for (String s : range){
                shopTypes.add(JSONUtil.toBean(s, ShopType.class));
            }
            return Result.ok(shopTypes);
        }
        // 3 如果不存在，查询数据库 select * from shop_type order by sort asc;
        List<ShopType> typeList = query().orderByDesc("sort").list();
        // 4 不存在，返回错误
        if(typeList==null){
            return Result.fail("发生错误");
        }
        // 5 存在，写入缓存，返回
        // 将数据转为Json字符串list
        List<String> shoplist = new ArrayList<>();
        for (ShopType shopType : typeList){
            shoplist.add(JSONUtil.toJsonStr(shopType));
        }
        stringRedisTemplate.opsForList().leftPushAll(RedisConstants.CACHE_TYPE_KEY, shoplist);
        return Result.ok(typeList);
    }
}
