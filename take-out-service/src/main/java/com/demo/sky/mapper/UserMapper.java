package com.demo.sky.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.sky.dao.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.Map;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据openid获取当前用户
     * @param openid
     * @return
     */
    User getByOpenId(String openid);

    /**
     * 根据动态条件统计用户数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);
}
