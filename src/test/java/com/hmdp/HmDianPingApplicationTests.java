package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.impl.UserServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 为用户生成token在redis中
 */
@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {
    @Resource
    private UserServiceImpl userService;

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

}
