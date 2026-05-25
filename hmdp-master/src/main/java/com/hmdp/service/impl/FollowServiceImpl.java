package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;


    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        // 1. 判断是否 要关注
        if (isFollow) {
            // 3. 关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean save = save(follow);
            if(save){
               stringRedisTemplate.opsForSet().add("follows:" + userId, followUserId.toString());
            }
        } else {
            // 2. 取消关注
            boolean remove = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getFollowUserId, followUserId)
                    .eq(Follow::getUserId, userId));
            if (!remove) {
                return Result.fail("取消关注失败");
            }
            stringRedisTemplate.opsForSet().remove("follows:" + userId, followUserId.toString());
        }
        // 4. 返回结果
        return Result.ok();
    }


    @Override
    public Result isFollow(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 判断是否关注
        Long count = lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, id).count();

        return Result.ok(count > 0 ? true : false);
    }

    /**
     * 获取共同关注 (Redis 实现)
     */
    @Override
    public Result followCommons(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 判断目标用户与当前用户的交集
        String key = "follows:" + userId;
        String key2 = "follows:" + id;
        // 求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        // 如果没有共同关注用户直接返回
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 将Redis取出的Json字符串转为Long
        List<Long> collect = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        // 根据关注Id列表获取用户
        List<UserDTO> users = userService.listByIds(collect).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    // /**
    //  * 获取共同关注 (Mybatis-plus 数据库实现)
    //  */
    // @Override
    // public Result followCommons(Long id) {
    //     // 获取当前用户
    //     Long userId = UserHolder.getUser().getId();
    //     // 判断关注用户的关注对象与当前用户的关注对象是否有交集
    //     List<Long> list1 = lambdaQuery().eq(Follow::getUserId, id)
    //             .list()
    //             .stream()
    //             .map(follow -> follow.getFollowUserId())
    //             .collect(Collectors.toList());
    //     List<Long> list2 = lambdaQuery().eq(Follow::getUserId, userId)
    //             .list()
    //             .stream()
    //             .map(follow -> follow.getFollowUserId())
    //             .collect(Collectors.toList());
    //     list1.retainAll(list2); // 求交集
    //     // 返回
    //     return Result.ok(list1);
    // }
}
