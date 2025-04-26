package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商户
     * @param id
     * @return
     */
    Shop queryById(Long id);

    /**
     * 更新商户信息
     * @param shop
     */
    void updateShop(Shop shop);
}
