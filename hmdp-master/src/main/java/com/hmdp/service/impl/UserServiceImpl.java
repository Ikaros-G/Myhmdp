package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.*;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1 验证手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        // 2 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // session.setAttribute("code", code);
        // 3 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送验证码成功，验证码：{}", code);
        // 5 返回验证码
        return Result.ok(code);
    }

    // @Override
    // public Result login(LoginFormDTO loginForm, HttpSession session) {
    //     //校验手机号
    //     String phone = loginForm.getPhone();
    //     if (RegexUtils.isPhoneInvalid(phone)) {
    //         //手机号不符合
    //         return Result.fail("手机号格式错误");
    //     }
    //     //从redis中获取验证码 校验验证码
    //     /*  Object cacheCode = session.getAttribute("code");*/
    //     String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
    //     String code = loginForm.getCode();
    //     if (cacheCode == null || !cacheCode.equals(code)) {
    //         //不一致 报错
    //         return Result.fail("验证码错误");
    //     }
    //     //一致 根据手机号查询用户
    //     User user = baseMapper
    //             .selectOne(new LambdaQueryWrapper<User>()
    //                     .eq(User::getPhone, phone));
    //     //判断用户是否存在
    //     if (user == null) {
    //         //不存在 创建新用户
    //         user = createUserWithPhone(phone);
    //     }
    //     /*//保存用户信息到session
    //     session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));*/
    //     //生成token
    //     String token = UUID.randomUUID().toString(true);
    //     //userDTO转map
    //     UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
    //     Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>()
    //             , CopyOptions.create().setIgnoreNullValue(true)
    //                     .setFieldValueEditor(
    //                             (name, value) -> value.toString()
    //                     ));
    //     //保存用户信息到redis
    //     stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
    //     //设置过期时间
    //     stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
    //     return Result.ok(token);
    // }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1 验证手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误");
        }
        // 2 验证验证码
        String usercode = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        if (cacheCode == null || !cacheCode.equals(usercode)){         // cacheCode == null 验证码超时失效
            return Result.fail("验证码错误");
        }
        // 3 根据手机号查询用户  selete * from user where phone = ?
        User user = baseMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPhone, loginForm.getPhone())
        );
        // 4 判断用户是否存在
        if(user == null){
            // 5 如果不存在，创建新用户
            user = createUserWithPhone(loginForm.getPhone());
        }

        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 6 生成token
        String token = UUID.randomUUID().toString(true);
        // 7 保存用户信息到Redis
        // 7.1 将User对象转为UserDTO，防止敏感信息泄露
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 7.2 将user对象转为map
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((name, value) ->
                                value.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
        // 7.3 设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8 将token返回给前端
        return Result.ok(token);
    }


    @Override
    public Result sign() {
        //获取当前登陆用户
        Long id = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyy:MM:"));
        String key = USER_SIGN_KEY +yyyyMM+ id;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写了redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前登陆用户
        Long id = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyy:MM:"));
        String key = USER_SIGN_KEY +yyyyMM+ id;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取截至本月今天的所有签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key
                , BitFieldSubCommands
                        .create()
                        .get(BitFieldSubCommands.BitFieldType
                                .unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result==null||result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num==null||num==0){
            return Result.ok(0);
        }
        //转二进制字符串
        String binaryString = Long.toBinaryString(num);
        //计算连续签到天数
        int count=0;
        for (int i = binaryString.length()-1; i >=0; i--) {
            if (binaryString.charAt(i)=='1'){
                count++;
            }
            else {
                break;
            }
        }
        //返回
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        // 生成随机昵称
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        baseMapper.insert(user);
        return user;
    }
}
