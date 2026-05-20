package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static com.hmdp.constants.RedisConstants.ORDER_QUEUE_NAME;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 自己注入自己为了获取代理对象 @Lazy 延迟注入 避免形成循环依赖
     */
    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderService;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final LinkedBlockingQueue<VoucherOrder> orderTasks = new LinkedBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private IVoucherOrderService proxy;

    /**
     * 消息队列订单处理异常Pending函数
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 从Pending list 获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> orderTasks = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamOffset.create(ORDER_QUEUE_NAME, ReadOffset.from("0"))
                );
                if (orderTasks == null || orderTasks.isEmpty()){
                    // 如果获取失败 没有消息 结束循环
                    break;
                }
                // 如果获取成功 解析消息
                Map<Object, Object> value = orderTasks.get(0).getValue();
                VoucherOrder task = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 执行数据库下单
                handleVoucherOrder(task);
                // 订单确认
                stringRedisTemplate.opsForStream().acknowledge(ORDER_QUEUE_NAME, "g1", orderTasks.get(0).getId());
                log.info("订单处理完成：{}", task.getId());
            } catch (Exception e) {
                // 订单处理异常
                log.error("订单处理异常", e);
            }
        }
    }


    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 创建消费者（消息队列）
     */
    @PostConstruct
    public void createConsumer() {
        SECKILL_ORDER_EXECUTOR.submit(() -> {
            while (true) {
                try {
                    // 从消息队列获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> orderTasks = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    if (orderTasks == null || orderTasks.isEmpty()){
                        // 如果获取失败 没有消息 继续循环
                        continue;
                    }
                    // 如果获取成功 解析消息
                    Map<Object, Object> value = orderTasks.get(0).getValue();
                    VoucherOrder task = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 执行数据库下单
                    handleVoucherOrder(task);
                    // 订单确认
                    stringRedisTemplate.opsForStream().acknowledge(ORDER_QUEUE_NAME, "g1", orderTasks.get(0).getId());
                    log.info("订单处理完成：{}", task.getId());
                } catch (Exception e) {
                    // 订单处理异常 Pending
                    handlePendingList();
                    log.error("订单处理异常", e);
                }
            }
        });
    }
    /**
     * 秒杀优惠券判断 (Redis消息队列)
     *
     * @param voucherId 优惠券id
     * @return 订单id
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        Long orderId = redisIdWorker.nextId("order");
        Long executeResult = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
        // 判断秒杀结果
        int result = executeResult.intValue();
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        return Result.ok("下单成功");
    }


    // /**
    //  * 秒杀优惠券判断 (阻塞队列)
    //  *
    //  * @param voucherId 优惠券id
    //  * @return 订单id
    //  */
    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     // 获取用户id
    //     Long userId = UserHolder.getUser().getId();
    //     Long executeResult = stringRedisTemplate.execute(
    //             SECKILL_SCRIPT,
    //             Collections.emptyList(),
    //             voucherId.toString(),
    //             userId.toString()
    //     );
    //     // 判断秒杀结果
    //     int result = executeResult.intValue();
    //     if (result != 0) {
    //         return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
    //     }
    //     // 封装订单
    //     VoucherOrder voucherOrder = CreateVOrderObj(voucherId);
    //     // 将userid与orderId存入阻塞队列
    //     try {
    //         orderTasks.put(voucherOrder);
    //     } catch (InterruptedException e) {
    //         Thread.currentThread().interrupt();
    //         return Result.fail("系统繁忙");
    //     }
    //     return Result.ok("下单成功");
    // }
    //
    // /**
    //  * 创建消费者（阻塞队列）
    //  */
    // @PostConstruct
    // public void createConsumer() {
    //     SECKILL_ORDER_EXECUTOR.submit(() -> {
    //         while (true) {
    //             try {
    //                 // 从阻塞队列取任务，没有任务就阻塞等待
    //                 VoucherOrder task = orderTasks.take();
    //                 // 执行数据库下单
    //                 handleVoucherOrder(task);
    //                 log.info("订单处理完成：{}", task.getId());
    //             } catch (Exception e) {
    //                 Thread.currentThread().interrupt();
    //                 log.error("订单处理异常", e);
    //             }
    //         }
    //     });
    // }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象（兜底）
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if (!isLock) {
            // 获取失败,返回错误或者重试
            throw new RuntimeException("发送未知错误");
        }
        try {
            // 获取成功
            // 创建订单
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    // /**
    //  * 秒杀优惠券 (自定义分布式全局锁/redission)
    //  * @param voucherId 优惠券id
    //  * @return 订单id
    //  */
    // @Override
    // @Transactional(rollbackFor = Exception.class)
    // public Result seckillVoucher(Long voucherId) {
    //     // 判断优惠券是否在秒杀中
    //     SeckillVoucher vouchers = seckillVoucherService.getById(voucherId);
    //     if (vouchers.getBeginTime().isAfter(LocalDateTime.now())) {
    //         return Result.fail("秒杀尚未开始");
    //     }
    //     if (vouchers.getEndTime().isBefore(LocalDateTime.now())) {
    //         return Result.fail("秒杀已结束");
    //     }
    //     // 判断优惠券库存是否充足
    //     if (vouchers.getStock() < 1) {
    //         return Result.fail("库存不足");
    //     }
    //     Long userid = UserHolder.getUser().getId();
    //     // // 悲观锁
    //     // synchronized (userid.toString().intern()){
    //     //     // 获取事务代理对象
    //     //     IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //     //     // getResult实现一人一单
    //     //     return proxy.getResult(voucherId);
    //     // }
    //
    //     // 方案1：自定义分布式全局锁
    //     // SimpleRedisLock simpleRedisLock = new SimpleRedisLock(userid, stringRedisTemplate);
    //     // boolean lock = simpleRedisLock.tryLock(1000L);
    //     // if (!lock) {
    //     //     return Result.fail("不允许重复下单");
    //     // }
    //     // try {
    //     //     // 获取事务代理对象
    //     //     IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //     //     // getResult实现一人一单
    //     //     return proxy.getResult(voucherId);
    //     // } finally {
    //     //     simpleRedisLock.unLock();
    //     // }
    //
    //     // 方案2：Redission 锁
    //     RLock lock = redissonClient.getLock("lock:order:" + userid);
    //     if (!lock.tryLock()) {
    //         return Result.fail("不允许重复下单");
    //     }
    //     try {
    //         // 获取事务代理对象
    //         IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //         // getResult实现一人一单
    //         return proxy.getResult(voucherId);
    //     } finally {
    //         lock.unlock();
    //     }
    // }

    @Transactional(rollbackFor = Exception.class)
    public Result getResult(Long voucherId) {
        // 一人一单
        VoucherOrder voucherOrder = new VoucherOrder();
        Long userid = UserHolder.getUser().getId();
        Long orderNumber = query().eq("user_id", userid).count();
        // 查询用户是否已购
        if (orderNumber > 0) {
            return Result.fail("您已购买过此优惠券");
        }
        // 乐观锁解决超卖问题
        // 判断库存是否更新
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        // 如果不成功，则说明已有线程更新库存，则返回失败
        if (!success) {
            return Result.fail("库存不足");
        }
        // 创建订单
        voucherOrder.setUserId(userid);
        voucherOrder.setVoucherId(voucherId);
        Long orderOId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderOId);
        save(voucherOrder);
        return Result.ok(orderOId);
    }

    // /**
    //  * 秒杀优惠券(消息队列)
    //  *
    //  * @param voucherId 券id
    //  * @return {@link Result}
    //  */
    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     //获取用户
    //     UserDTO user = UserHolder.getUser();
    //     //获取订单id
    //     Long orderId = redisIdWorker.nextId("order");
    //     //执行lua脚本
    //     Long res = stringRedisTemplate.execute(
    //             SECKILL_SCRIPT
    //             , Collections.emptyList()
    //             , voucherId.toString()
    //             , user.getId().toString()
    //             , orderId.toString());
    //     //判断结果是否为0
    //     int r = res.intValue();
    //     if (r != 0) {
    //         //不为0 没有购买资格
    //         return Result.fail(r == 1 ? "库存不足" : "禁止重复下单");
    //     }
    //     return Result.ok(orderId);
    // }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userid = voucherOrder.getUserId();
        Long orderNumber = query().eq("user_id", userid).count();
        // 查询用户是否已购
        if (orderNumber > 0) {
            log.info("不能重复购买优惠券");
        }
        // 扣减库存
        boolean isSuccess = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock = stock - 1"));
        if (!isSuccess){
            log.info("库存不足");
        }
        // 创建订单
        save(voucherOrder);
    }

    public VoucherOrder CreateVOrderObj(Long voucherId) {
        VoucherOrder voucherOrder = new VoucherOrder();
        Long userid = UserHolder.getUser().getId();
        voucherOrder.setUserId(userid);
        voucherOrder.setVoucherId(voucherId);
        Long orderOId = redisIdWorker.nextId("order");
        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setStatus(1);
        voucherOrder.setUpdateTime(LocalDateTime.now());
        voucherOrder.setId(orderOId);
        return voucherOrder;
    }
}

