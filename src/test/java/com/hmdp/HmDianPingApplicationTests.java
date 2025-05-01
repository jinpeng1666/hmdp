package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.UserServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 为用户生成token在redis中
 */
@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {
    @Resource
    private UserServiceImpl userService;

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void generateTokenForAllUsers() {
        // 1.查询所有用户
        List<User> userList = userService.list();
        log.info("开始为" + userList.size() + "个用户生成token...");

        // 创建token保存文件
        String filePath = "user_tokens.txt";
        Path path = Paths.get(filePath);

        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            // 2.遍历用户列表
            for (User user : userList) {
                // 3.随机生成token作为登录令牌
                String token = UUID.randomUUID().toString(true);

                // 4.将User对象转为UserDTO
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

                // 5.将UserDTO转为HashMap存储
                Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) ->
                                        fieldValue == null ? "" : fieldValue.toString()));

                // 6.存储到Redis
                String tokenKey = LOGIN_USER_KEY + token;
                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

                // 7.设置token有效期
                stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

                // 8.将token写入文件，每行只包含一个token
                writer.write(token);
                writer.newLine();

                // 控制台输出用户信息和token，方便调试
                System.out.println("用户ID: " + user.getId() + ", 昵称: " + user.getNickName() + ", Token: " + token);
            }

            System.out.println("所有用户token生成完毕！");
            System.out.println("Token已保存到文件: " + path.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("写入token文件失败: " + e.getMessage());
            e.printStackTrace();
        }

    }

    @Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

}
