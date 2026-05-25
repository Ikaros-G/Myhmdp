package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.constants.RedisConstants;
import com.hmdp.constants.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            // 查询Blog有关用户
            queryBlogUser(blog);
            // 获取当前用户是否点赞
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 查询Blog有关用户
        queryBlogUser(blog);
        // 获取当前用户是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 点赞
     *
     * @param id id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 如果未点赞，则点赞
        if (score == null) {
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 已点赞，取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }


    @Override
    public Result queryBlogLikesById(Long id) {
        // 1. 查询博客点赞排名前5的用户
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2. 解析用户ID列表
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join(",", userIds);
        // 3. 根据用户ID查询用户信息，并保持与Redis中相同的顺序
        List<UserDTO> userDTOS = userService.lambdaQuery()
                .in(User::getId, userIds)              // 查询当前用户和前5的点赞用户
                .last("order by field(id," + join + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4. 返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 查询粉丝
        Long userId = user.getId();
        List<Follow> follows = followService.lambdaQuery().eq(Follow::getFollowUserId, userId).list();
        if (follows == null || follows.isEmpty()) {
            return Result.ok(blog.getId());
        }
        // 推送笔记id给粉丝
        for (Follow follow : follows) {
            Long fuserId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add("feed:" + fuserId, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        // 滚动查询 收信箱
        String key = "feed:" + user.getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        // 如果为空，则返回
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 封装数据
        // 查询Blog内容
        // List<String> blogId = typedTuples.stream().map(typedTuple -> {
        //     return typedTuple.getValue();
        // }).collect(Collectors.toList());
        // List<Blog> blogs = lambdaQuery().in(Blog::getId, blogId).last("order by field(id," + StrUtil.join(",", blogId) + ")").list();
        // // 计算minTime， offset
        // Long mintime = typedTuples.stream()
        //         .map(typedTuple -> typedTuple.getScore().longValue()).min(Long::compareTo).get();
        // Integer offsetNum = 1;
        // for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
        //     if (mintime == typedTuple.getScore().longValue()){
        //         offsetNum++;
        //     }
        // }
        //
        // for (Blog blog : blogs){
        //     // 查询Blog有关用户
        //     queryBlogUser(blog);
        //     // 获取当前用户是否点赞
        //     isBlogLiked(blog);
        // }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int offsetNum = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples){
            // 获取blogId
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 计算offset，最小时间戳相同的blog数量
            long time = typedTuple.getScore().longValue();
            if(minTime == time){
                offsetNum++;
            }else {
                minTime = time;
                offsetNum = 1;
            }
        }
        // 获取Blog内容
        String idstr = StrUtil.join(",", ids);
        List<Blog> blogs = lambdaQuery().in(Blog::getId, ids).last("ORDER BY FIELD(id," + idstr + ")").list();
        for (Blog blog : blogs){
            // 查询Blog有关用户
            queryBlogUser(blog);
            // 获取当前用户是否点赞
            isBlogLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(offsetNum);
        scrollResult.setMinTime(minTime);
        // 返回
        return Result.ok(scrollResult);
    }

    /**
     *  查询并设置Blog的发布人
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 查询并设置Blog是否被当前用户点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        // 获取当前登陆用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        // 判断当前用户时候点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

}
