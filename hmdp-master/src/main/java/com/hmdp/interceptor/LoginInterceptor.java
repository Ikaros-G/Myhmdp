package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 登录拦截器
 *
 * @author CHEN
 * @date 2022/10/07
 */
public class LoginInterceptor implements HandlerInterceptor {

    // @Override
    // public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    //     //获取用户
    //     if (UserHolder.getUser() == null) {
    //         //不存在用户 拦截
    //         response.setStatus(401);
    //         return false;
    //     }
    //     //存在用户放行
    //     return true;
    // }

    /**
     *  登录拦截器
     * @param request current HTTP request
     * @param response current HTTP response
     * @param handler chosen handler to execute, for type and/or instance evaluation
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1 从session中获取用户
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        // 2 判断用户是否存在
        if (user == null){
            // 3 如果不存在，拦截
            response.setStatus(401);
            return false;
        }
        // 4 存在，保存用户信息到ThreadLocal，放行
        UserHolder.saveUser(BeanUtil.copyProperties(user, UserDTO.class));
        // 结束
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }

}
