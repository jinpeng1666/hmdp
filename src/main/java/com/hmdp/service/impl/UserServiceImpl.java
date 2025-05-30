package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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

    /**
     * 发送短信验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);

        // 返回ok
        return Result.ok();
    }

    /**
     * 用户登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //根据手机号查询用户
        User user = query().eq("phone", phone).one();

        if (StrUtil.isNotBlank(loginForm.getCode())) {
            // 1）验证码登录
            // 校验验证码
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            String code = loginForm.getCode();
            if(cacheCode == null || !cacheCode.equals(code)){
                //不一致，报错
                return Result.fail("验证码错误");
            }

            //判断用户是否存在
            if(user == null){
                //不存在，则创建
                user =  createUserWithPhone(phone);
            }
            // 保存用户（已存在）信息到redis中
            String token = saveLoginUser(user);

            // 返回token
            return Result.ok(token);
        } else if (StrUtil.isNotBlank(loginForm.getPassword())) {
            // 2）密码登录

            //判断用户是否存在
            if(user == null){
                //不存在（不能创建用户）
                return Result.fail("手机号不存在");
            }

            // 校验密码
            String inputPassword = loginForm.getPassword();
            String inputPasswordMd5 = DigestUtils.md5Hex(inputPassword);
            if (!inputPasswordMd5.equals(user.getPassword())) {
                return Result.fail("密码输入错误");
            }

            // 保存用户信息到redis中
            String token = saveLoginUser(user);

            // 返回token
            return Result.ok(token);
        } else {
            return Result.fail("登录失败");
        }
    }

    /**
     * 用户退出登录
     */
    @Override
    public void logout(String token) {
        // 1. 从 Redis 中获取用户信息
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        Object userId = userMap.get("id"); // 立刻获取 userId，防止 Redis 中数据已被删或被并发清空

        // 2. 删除 Redis 中的 token -> 用户信息 映射
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        log.info("删除Redis中的 token 映射");

        // 3. 删除 Redis 中的 userId -> token 映射
        if (userId != null) {
            stringRedisTemplate.delete(LOGIN_USER_ID + userId);
            log.info("删除Redis中的 userId 映射");
        }
    }

    /**
     * 签到
     * @return
     */
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 签到统计
     * @return
     */
    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 保存用户信息到redis
     * @param user
     * @return
     */
    private String saveLoginUser(User user) {
        String oldToken = stringRedisTemplate.opsForValue().get(LOGIN_USER_ID + user.getId());
        if (StrUtil.isNotBlank(oldToken)) {
            log.info("删除旧token");
            stringRedisTemplate.delete(LOGIN_USER_KEY + oldToken);
        }

        Map<String, Object> userMap = BeanUtil.beanToMap(user, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String token = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, Duration.ofMinutes(LOGIN_USER_TTL)); // 设置过期时间

        stringRedisTemplate.opsForValue().set(LOGIN_USER_ID + user.getId(), token, Duration.ofMinutes(LOGIN_USER_TTL));

        return token;
    }

    /**
     * 创建并保存用户到数据库
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setPassword(DigestUtils.md5Hex("123456"));
        save(user);
        return user;
    }
}
