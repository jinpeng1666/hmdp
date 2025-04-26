package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.exception.NoDataException;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商户
     * @param id
     * @return
     */
    @Override
    public Shop queryById(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // isNotBlank("") 为false，isNotBlank(null) 为false
        if (StrUtil.isNotBlank(shopJson)) {
            // 缓存命中（非空非null）
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        } else {
            // 缓存命中，但是为""值
            if (shopJson != null) {
                throw new NoDataException("数据不存在");
            }

            // 缓存未命中（互斥锁）
            String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

            Shop shop = null;
            try {
                boolean isLock = tryLock(lockKey);

                if (!isLock) {
                    // 获取锁失败
                    Thread.sleep(50);
                    return queryById(id);
                } else {
                    // 获取锁成功
                    shop = this.getById(id);
                    if (shop == null) {
                        // 缓存空值
                        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                        // 数据库不存在
                        throw new NoDataException("数据不存在");
                    } else {
                        // 数据库存在
                        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                        return shop;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                // 释放锁
                unlock(lockKey);
            }
        }
    }

    /**
     * 更新商户信息
     * @param shop
     */
    @Override
    @Transactional
    public void updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            throw new NoDataException("数据不存在");
        }

        // 更新数据库
        this.updateById(shop);

        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
    }

    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
