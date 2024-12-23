package com.demo.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.sky.context.BaseContext;
import com.demo.sky.dto.*;
import com.demo.sky.dao.*;
import com.demo.sky.exception.AddressBookBusinessException;
import com.demo.sky.exception.ErrorCode;
import com.demo.sky.exception.OrderBusinessException;
import com.demo.sky.exception.ShoppingCartBusinessException;
import com.demo.sky.mapper.*;
import com.demo.sky.rabbitmq.RabbitMQProducer;
import com.demo.sky.result.PageResult;
import com.demo.sky.service.OrderService;
import com.demo.sky.websocket.WebSocketServer;
import com.demo.sky.utils.HttpClientUtil;
import com.demo.sky.utils.WeChatPayUtil;
import com.demo.sky.vo.OrderPaymentVO;
import com.demo.sky.vo.OrderStatisticsVO;
import com.demo.sky.vo.OrderSubmitVO;
import com.demo.sky.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Orders> implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final ShoppingCartMapper shoppingCartMapper;
    private final AddressBookMapper addressBookMapper;
    private final UserMapper userMapper;
    private final WeChatPayUtil weChatPayUtil;
    private final RabbitMQProducer rabbitMQProducer;
    private final WebSocketServer webSocketServer;

    public OrderServiceImpl(OrderMapper orderMapper,
                            OrderDetailMapper orderDetailMapper,
                            ShoppingCartMapper shoppingCartMapper,
                            AddressBookMapper addressBookMapper,
                            UserMapper userMapper,
                            WeChatPayUtil weChatPayUtil,
                            RabbitMQProducer rabbitMQProducer,
                            WebSocketServer webSocketServer) {
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.shoppingCartMapper = shoppingCartMapper;
        this.addressBookMapper = addressBookMapper;
        this.userMapper = userMapper;
        this.weChatPayUtil = weChatPayUtil;
        this.rabbitMQProducer = rabbitMQProducer;
        this.webSocketServer = webSocketServer;
    }


    @Value("${sky.shop.address}")
    private String shopAddress;

    @Value("${sky.baidu.ak}")
    private String ak;


    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        // 异常情况的处理（收货地址为空、超出配送氛围、购物车为空）
        AddressBook addressBook = addressBookMapper.selectById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            throw new AddressBookBusinessException(data);
        }

        // 检查用户的收货地址是否超出配送范围
        checkOutOfRange(addressBook.getCityName() + addressBook.getDistrictName() + addressBook.getDetail());

        Long currentId = BaseContext.getCurrentId();

        ShoppingCart shoppingCart = new ShoppingCart();
        // 只能查询当前用户数据
        shoppingCart.setUserId(currentId);

        // 询当前用户的购物车数据
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            data.put("userId", currentId);
            throw new ShoppingCartBusinessException(data);
        }

        // 构造订单数据
        Orders order = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,order);
        order.setPhone(addressBook.getPhone());
        order.setAddress(addressBook.getDetail());
        order.setConsignee(addressBook.getConsignee());
        order.setNumber(String.valueOf(System.currentTimeMillis()));
        order.setUserId(currentId);
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setOrderTime(LocalDateTime.now());
        orderMapper.insert(order);

        // 将订单创建同步到消息队列，用于判断支付是否超时
        rabbitMQProducer.createOrder(order);

        // 订单明细数据
        ArrayList<OrderDetail> orderDetailList = new ArrayList<>();
        shoppingCartList.forEach(cart->{
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(order.getId());
            orderDetailList.add(orderDetail);
        });

        // 向明细表中查询n条数据
        orderDetailMapper.insertBatch(orderDetailList);

        // 清理购物车中的数据
        shoppingCartMapper.deleteByUserId(currentId);

        // 封装返回结果
        OrderSubmitVO submitVO = OrderSubmitVO.builder()
                .id(order.getId())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .orderTime(order.getOrderTime())
                .build();

        return submitVO;
    }

    /**
     * 订单支付
     * @param ordersPaymentDTO
     * @return
     * @throws Exception
     */
    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.selectById(String.valueOf(userId));

        String orderNumber = ordersPaymentDTO.getOrderNumber();
        Orders orders = orderMapper.getByNumberAndUserId(orderNumber, userId);

        // 调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(),
                orders.getAmount(),
                "外卖订单" + orders.getId(),
                user.getOpenid()
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            throw new OrderBusinessException(ErrorCode.ORDER_ALREADY_PAID, data);
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     * @param outTradeNo
     */
    @Override
    public void paySuccess(String outTradeNo) {
        Long userId = BaseContext.getCurrentId();

        // 根据订单号查询当前用户的订单
        Orders orderDB = orderMapper.getByNumberAndUserId(outTradeNo, userId);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(orderDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();
        orderMapper.updateById(orders);

        HashMap map = new HashMap();
        map.put("type", 1);
        map.put("orderId", orders.getId());
        map.put("content", "订单号：" + outTradeNo);

        // 通过WebSocket实现来电提醒，向客户端浏览器推送消息
        webSocketServer.sendToAllClient(JSON.toJSONString(map));

    }

    /**
     * 历史订单查询
     *
     * @param pageNum
     * @param pageSize
     * @param status   订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
     * @return
     */
    @Override
    public PageResult pageQueryForUser(int pageNum, int pageSize, Integer status) {
        // 设置分页
        Page<Orders> page = new Page<>(pageNum, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 分页条件查询
        IPage<Orders> resultPage = orderMapper.pageOrder(page, ordersPageQueryDTO);

        ArrayList<OrderVO> list = new ArrayList<>();

        // 查询出订单明细，并封装入OrderVo进行响应
        if (resultPage != null && resultPage.getTotal() > 0) {
            resultPage.getRecords().forEach(orders -> {
                Long ordersId = orders.getId();

                // 查询订单明细
                List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(ordersId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetailList);
                list.add(orderVO);
            });
        }

        return new PageResult(page.getTotal(), list);
    }

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO details(Long id) {
        Orders orders = orderMapper.selectById(id);

        // 查询该订单对应的菜品/套餐明细
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        // 将订单及其详情封装到OrderVo并返回
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    /**
     * 用户取消订单
     * @param id
     */
    @Override
    public void userCancelById(Long id) throws Exception {
        Orders orderDB = orderMapper.selectById(id);

        // 校验订单是否存在
        if (orderDB == null) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            data.put("orderId", orderDB.getId());
            throw new OrderBusinessException(ErrorCode.ORDER_NOT_FOUND, data);
        }

        // 订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        if (orderDB.getStatus() > 2) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            data.put("orderId", orderDB.getId());
            data.put("status", orderDB.getStatus());
            throw new OrderBusinessException(ErrorCode.ORDER_STATUS_ERROR, data);
        }

        Orders orders = new Orders();
        orders.setId(orderDB.getId());

        // 订单处于待接单的状态下取消，需要进行退款
        if (orderDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            // 调用微信支付退款接口
            weChatPayUtil.refund(
                    orderDB.getNumber(),
                    orderDB.getNumber(),
                    orders.getAmount(),
                    orders.getAmount()
            );

            // 支付状态修改为 退款
            orders.setPayStatus(Orders.REFUND);
        }

        // 更新订单状态，取消原因、时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(orders);
    }

    /**
     * 再来一单
     * @param id
     */
    @Override
    public void repetition(Long id) {
        Long userId = BaseContext.getCurrentId();

        // 根据订单id查询当前订单详情
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        // 将订单详情对象转为购物车对象
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(orderDetail -> {
            ShoppingCart shoppingCart = new ShoppingCart();

            // 将原订单详情里面的菜品信息重新复制到购物车对象中
            BeanUtils.copyProperties(orderDetail, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            return shoppingCart;
        }).collect(Collectors.toList());

        // 将购物车对象批量添加到购物车
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        Page<Orders> page = new Page<>(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        IPage<Orders> pageQuery = orderMapper.pageOrder(page, ordersPageQueryDTO);

        // 部分订单状态，需要额外返回订单菜品信息，将orders转化为orderVo
        List<OrderVO> orderVoList = getOrderVoList((Page<Orders>) pageQuery.getRecords());
        return new PageResult(pageQuery.getTotal(), orderVoList);
    }

    /**
     * 各个状态的订单数量统计
     * @return
     */
    @Override
    public OrderStatisticsVO statistics() {
        // 根据状态，分别查询出接待单，待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 将查询出的数据封装
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);

        return orderStatisticsVO;
    }

    /**
     * 接单
     * @param ordersCancelDTO
     */
    @Override
    public void confirm(OrdersCancelDTO ordersCancelDTO) {
        Orders orders = Orders.builder()
                .id(ordersCancelDTO.getId())
                .status(Orders.CONFIRMED)
                .build();
        orderMapper.updateById(orders);
    }

    /**
     * 拒单
     * @param ordersRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        Orders ordersDB = orderMapper.selectById(ordersRejectionDTO.getId());

        // 订单只有存在且状态为2（待接单）才可以拒单
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            data.put("orderId", ordersDB.getId());
            data.put("status", ordersDB.getStatus());
            throw new OrderBusinessException(ErrorCode.ORDER_STATUS_ERROR, data);
        }

        // 支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == Orders.PAID) {
            // 用户已支付，需要退款
            String refund = weChatPayUtil.refund(
                    ordersDB.getNumber(),
                    ordersDB.getNumber(),
                    ordersDB.getAmount(),
                    ordersDB.getAmount()
            );
            log.info("申请退款：{}", refund);
        }

        // 拒单需要退款，根据订单id更新订单状态，拒单原因，取消时间
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.updateById(orders);
    }

    /**
     * 取消订单
     * @param ordersCancelDTO
     */
    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        // 根据id查询订单
        Orders orderDB = orderMapper.selectById(ordersCancelDTO.getId());

        // 支付状态
        Integer payStatus = orderDB.getPayStatus();
        if (payStatus == 1) {
            // 用于已支付，需要退款
            String refund = weChatPayUtil.refund(
                    orderDB.getNumber(),
                    orderDB.getNumber(),
                    orderDB.getAmount(),
                    orderDB.getAmount()
            );
            log.info("申请退款：{}", refund);
        }

        // 管理端取消订单需要退款，根据订单id更新订单状态、取消原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersCancelDTO.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(orders);
    }

    /**
     * 派送订单
     * @param id
     */
    @Override
    public void delivery(Long id) {
        Orders orderDB = orderMapper.selectById(id);

        // 校验订单是否存在，并且状态为3
        if (orderDB == null || !orderDB.getStatus().equals(Orders.CONFIRMED)) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            data.put("orderId", orderDB.getId());
            data.put("status", orderDB.getStatus());
            throw new OrderBusinessException(ErrorCode.ORDER_STATUS_ERROR, data);
        }

        Orders orders = new Orders();
        orders.setId(orderDB.getId());
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.updateById(orders);

        // 开始派送时将状态同步到消息队列
        rabbitMQProducer.updateOrderStatusToDeliveryInProgress(id);
    }

    /**
     * 完成订单
     * @param id
     */
    @Override
    public void complete(Long id) {
        Orders orderDB = orderMapper.selectById(id);

        // 校验订单是否存在，并且状态为4
        if (orderDB == null || !orderDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            data.put("orderId", orderDB.getId());
            data.put("status", orderDB.getStatus());
            throw new OrderBusinessException(ErrorCode.ORDER_STATUS_ERROR, data);
        }

        Orders orders = new Orders();
        orders.setId(orders.getId());

        // 更新订单状态，状态转为完成
        orders.setStatus(Orders.CONFIRMED);
        orders.setDeliveryTime(LocalDateTime.now());

        orderMapper.updateById(orders);
    }

    /**
     * 用户催单
     * @param id
     */
    @Override
    public void reminder(Long id) {
        Orders orders = orderMapper.selectById(id);
        if (orders == null) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            data.put("orderId", orders.getId());
            throw new OrderBusinessException(ErrorCode.ORDER_NOT_FOUND, data);
        }

        // 基于WebSocket实现催单
        HashMap map = new HashMap();
        map.put("type", 2);
        map.put("orderId", id);
        map.put("content", "订单号：" + orders.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }

    /**
     * 部分订单状态，需要额外返回订单菜品信息，将orders转化为orderVo
     * @param page
     * @return
     */
    private List<OrderVO> getOrderVoList(IPage<Orders> page) {
        // 需要返回订单菜品信息，自定义OrderVo响应结果
        ArrayList<OrderVO> orderVOArrayList = new ArrayList<>();

        List<Orders> ordersList = page.getRecords();
        if (!CollectionUtils.isEmpty(ordersList)) {
            ordersList.forEach(orders -> {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);

                String orderDishStr = getOrderDishStr(orders);
                orderVO.setOrderDishes(orderDishStr);
                orderVOArrayList.add(orderVO);
            });
        }
        return orderVOArrayList;
    }

    /**
     * 根据订单id获取菜品信息字符串
     * @param orders
     * @return
     */
    private String getOrderDishStr(Orders orders) {
        // 查询订单菜品详情信息（订单中的菜品和数量）
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将每一条订单菜品信息拼接为字符串（格式：宫保鸡丁*3；）
        List<String> ordewrDishList = orderDetailList.stream().map(orderDetail -> orderDetail.getName() + "*" + orderDetail.getNumber() + ";").collect(Collectors.toList());

        // 将该订单对应的所有菜品信息拼接在一起
        return String.join("", ordewrDishList);
    }

    /**
     * 检查客户的收货地址是否超出配送范围
     * @param address
     */
    private void checkOutOfRange(String address) {
        HashMap map = new HashMap();
        map.put("address", shopAddress);
        map.put("output", "json");
        map.put("ak", ak);

        // 获取店铺的经纬度坐标
        String shopCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        JSONObject jsonObject = JSON.parseObject(shopCoordinate);
        if (!jsonObject.getString("status").equals("0")) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            throw new OrderBusinessException(ErrorCode.SHOP_ADDRESS_ANALYSIS_FAILED, data);
        }

        // 数据解析
        JSONObject location = jsonObject.getJSONObject("result").getJSONObject("location");
        String lat = location.getString("lat");
        String lng = location.getString("lng");

        // 店铺经纬度坐标
        String shopLngLat = lat + "," + lng;

        map.put("address", address);

        // 获取用户地址的经纬度坐标
        String userCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        // 数据解析
        location = JSON.parseObject("result").getJSONObject("location");
        lat = location.getString("lat");
        lng = location.getString("lng");

        // 用户收货地址经纬度坐标
        String userLngLat = lat + "," + lng;

        map.put("orgin", shopLngLat);
        map.put("destination", userLngLat);
        map.put("steps_info", "0");

        // 路线规划
        String json = HttpClientUtil.doGet("https://api.map.baidu.com/directionlite/v1/driving", map);

        jsonObject = JSON.parseObject(json);
        if (!jsonObject.getString("status").equals("0")) {
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            throw new OrderBusinessException(ErrorCode.DISTRIBUTION_ROUTE_FAILED, data);
        }

        // 数据解析
        JSONObject result = jsonObject.getJSONObject("result");
        JSONArray jsonArray = (JSONArray) result.get("routes");
        Integer distance = (Integer) ((JSONObject) jsonArray.get(0)).get("distance");

        if(distance > 5000){
            // 配送距离超过5000米
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            throw new OrderBusinessException(ErrorCode.OUT_OF_DISTRIBUTION_RANGE, data);
        }
    }
}
