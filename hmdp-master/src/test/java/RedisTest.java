import com.hmdp.HmDianPingApplication;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.ShopTypeServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.constants.RedisConstants.*;

@SpringBootTest(classes = HmDianPingApplication.class)
public class RedisTest {
    @Resource
    private CacheClient cacheClient;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Test
    public void testSaveShop() throws InterruptedException {
        //预热
        List<Shop> shopList = shopService.list();
        for (Shop shop : shopList) {
            cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + shop.getId(), shop, 30L, TimeUnit.MINUTES);
        }
    }
    @Test
    public void dateTest() {
        // 构造 2026-05-15 00:00:00
        LocalDateTime dateTime = LocalDateTime.of(2026, 5, 15, 0, 0, 0);
        // 转 秒级时间戳
        long secondTs = dateTime.toEpochSecond(ZoneOffset.ofHours(8));
        System.out.println("2026-05-15 00:00:00 秒级时间戳：" + secondTs);
    }

    @Test
    public void testIdWorker() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(500);
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                Long id = redisIdWorker.nextId("order");
                //println有同步锁 会增加耗时
                System.out.println("id:" + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));
    }

    @Test
    public void addShopGeo(){
        // 获取店铺列表
        List<Shop> shops = shopService.list();
        // for (Shop shop : shops){
        //     // 根据店铺类型分组，添加到Redis（单条添加方式）
        //     stringRedisTemplate.opsForGeo().add(
        //             "shop:geo:"+shop.getTypeId(),
        //             new Point(shop.getX(),shop.getY()),
        //             shop.getId().toString()
        //     );
        // }
        // 批量添加方式
        Map<Long, List<Shop>> shopTypeByGroup = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : shopTypeByGroup.entrySet()) {
            List<RedisGeoCommands.GeoLocation<String>> geoLocations = new ArrayList<>(entry.getValue().size());
            // 存储店铺经纬度信息
            for (Shop shop1 : entry.getValue()){
                geoLocations.add(new RedisGeoCommands.GeoLocation<>(
                        shop1.getId().toString(),
                        new Point(shop1.getX(), shop1.getY())
                ));
            }
            // 分组存入Redis
            stringRedisTemplate.opsForGeo().geoAdd(SHOP_GEO_KEY + entry.getKey(), geoLocations);
        }
    }
    @Test
    public void testHyperLogLog(){
        String[] values=new String[1000];
        int j=0;
        for (int i = 0; i < 1000000; i++) {
            j=i%1000;
            values[j]="user_"+i;
            if (j==999){
                stringRedisTemplate.opsForHyperLogLog().add("hl1",values);
            }
        }
        Long hl1 = stringRedisTemplate.opsForHyperLogLog().size("hl1");
        System.out.println(hl1);
    }
}
