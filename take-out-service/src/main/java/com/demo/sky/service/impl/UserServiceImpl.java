package com.demo.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.demo.sky.dto.UserLoginDTO;
import com.demo.sky.dao.User;
import com.demo.sky.exception.LoginFailedException;
import com.demo.sky.mapper.UserMapper;
import com.demo.sky.properties.WeChatProperties;
import com.demo.sky.service.UserService;
import com.demo.sky.utils.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    //微信服务接口地址
    public static final String WX_LOGIN = "https://api.weixin.qq.com/sns/jscode2session";

    private final WeChatProperties weChatProperties;
    private final UserMapper userMapper;

    public UserServiceImpl(WeChatProperties weChatProperties, UserMapper userMapper) {
        this.weChatProperties = weChatProperties;
        this.userMapper = userMapper;
    }

    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    @Override
    public User wxLogin(UserLoginDTO userLoginDTO) {
        // 调用微信接口服务，获取当前微信用户的Openid
        String openid = getOpenid(userLoginDTO.getCode());

        Map<String, Object> data = new HashMap<>();
        data.put("timestamp", LocalDateTime.now());

        // 判断openId是否为空，如果为空标识登录失败，抛出业务异常
        if (openid == null) {
            throw new LoginFailedException(data);
        }

        // 判断当前用户是否为新用户
        User user = userMapper.getByOpenId(openid);

        // 如果是新用户,自动完成注册
        if (user == null) {
            user = User.builder()
                    .openid(openid).build();
            userMapper.insert(user);
        }

        // 返回这个用户对象
        return user;
    }

    /**
     * 调用微信接口服务，获取微信用户的openid
     * @param code
     * @return
     */
    private String getOpenid(String code) {
        // 调用微信接口服务，获得当前微信用户的openid
        Map<String, String> map = new HashMap<>();
        map.put("appid",weChatProperties.getAppid());
        map.put("secret",weChatProperties.getSecret());
        map.put("js_code",code);
        map.put("grant_type","authorization_code");
        String json = HttpClientUtil.doGet(WX_LOGIN, map);

        JSONObject jsonObject = JSON.parseObject(json);
        String openid = jsonObject.getString("openid");
        return openid;
    }
}

