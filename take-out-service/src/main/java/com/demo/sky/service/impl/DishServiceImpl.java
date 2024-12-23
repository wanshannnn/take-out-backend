package com.demo.sky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.demo.sky.constant.StatusConstant;
import com.demo.sky.dto.DishDTO;
import com.demo.sky.dto.DishPageQueryDTO;
import com.demo.sky.dao.Dish;
import com.demo.sky.dao.DishFlavor;
import com.demo.sky.dao.Setmeal;
import com.demo.sky.exception.DeletionNotAllowedException;
import com.demo.sky.exception.ErrorCode;
import com.demo.sky.mapper.DishFlavorMapper;
import com.demo.sky.mapper.DishMapper;
import com.demo.sky.mapper.SetmealDishMapper;
import com.demo.sky.mapper.SetmealMapper;
import com.demo.sky.result.PageResult;
import com.demo.sky.service.DishService;
import com.demo.sky.vo.DishVO;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

@Service
@CacheConfig(cacheNames = "dishCache")
public class DishServiceImpl extends ServiceImpl<DishMapper, Dish> implements DishService {

    private final DishMapper dishMapper;
    private final DishFlavorMapper dishFlavorMapper;
    private final SetmealDishMapper setmealDishMapper;
    private final SetmealMapper setmealMapper;

    public DishServiceImpl(DishMapper dishMapper, DishFlavorMapper dishFlavorMapper,
                           SetmealDishMapper setmealDishMapper, SetmealMapper setmealMapper) {
        this.dishMapper = dishMapper;
        this.dishFlavorMapper = dishFlavorMapper;
        this.setmealDishMapper = setmealDishMapper;
        this.setmealMapper = setmealMapper;
    }


    /**
     * 新增菜品
     * @param dishDTO
     */
    @Override
    @Transactional
    @CacheEvict(key = "'dish_id' + #dishDTO.id")
    public void saveWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        this.save(dish);

        Long dishId = dish.getId();

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> dishFlavor.setDishId(dishId));
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 菜品分页查询
     * @param dishPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        Page<DishVO> page = new Page<>(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        dishMapper.pageDish(page, dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getRecords());
    }

    /**
     * 菜品批量删除
     * @param ids
     */
    @Override
    @Transactional
    @CacheEvict(allEntries = true)
    public void deleteBatch(List<Long> ids) {

        // 判断当前菜品是否能够删除---是否存在起售中的菜品？？
        ids.forEach(id->{
            Dish dish = dishMapper.selectById(id);
            if (dish.getStatus() == StatusConstant.ENABLE) {
                // 当前菜品处于起售中，不能删除
                HashMap<String, Object> data = new HashMap<>();
                data.put("category_id", id);
                data.put("timestamp", LocalDateTime.now());
                throw new DeletionNotAllowedException(ErrorCode.DISH_ON_SALE, data);
            }
        });
        // 判断当前菜品是否能够删除---是否被套餐关联了？？
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && setmealIds.size() > 0) {
            // 当前菜品被套餐关联了，不能删除
            HashMap<String, Object> data = new HashMap<>();
            data.put("timestamp", LocalDateTime.now());
            throw new DeletionNotAllowedException(ErrorCode.DISH_BE_RELATED_BY_SETMEAL, data);
        }
        // 删除菜品表中的菜品数据
        ids.forEach(id->{
            dishMapper.deleteById(id);

            // 删除菜单关联的口味数据
            dishFlavorMapper.deleteByDishId(id);
        });

    }

    /**
     * 根据id查询菜品
     * @param id
     * @return
     */
    @Override
    @Cacheable(key = "'dish_id' + #id")
    public DishVO getByIdWithFlavor(Long id) {
        Dish dish = dishMapper.selectById(id);
        List<DishFlavor> dishFlavorList = dishFlavorMapper.getByDishId(id);

        // 将查询到的数据封装到vo
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);
        dishVO.setFlavors(dishFlavorList);

        return dishVO;
    }

    /**
     * 修改菜品
     * @param dishDTO
     */
    @Override
    @CacheEvict(key = "'dish_id' + #dishDTO.id")
    public void updateWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.updateById(dish);

        // 更新口味
        dishFlavorMapper.deleteByDishId(dishDTO.getId());
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> dishFlavor.setDishId(dishDTO.getId()));
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    @Override
    @Cacheable(key = "'category_id' + #categoryId")
    public List<Dish> list(Long categoryId) {
        return dishMapper.listByCategoryId(categoryId);
    }

    /**
     * 缓存数据库中查询菜品
     * @param categoryId
     * @return
     */
    @Cacheable(key = "'category_id' + #categoryId")
    public List<DishVO> listWithFlavorByCategory(Long categoryId) {
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);
        dish.setStatus(StatusConstant.ENABLE);

        // 查询 MySQL 数据库，获取菜品列表和口味
        return listWithFlavor(dish);
    }

    /**
     * 条件查询菜品和口味
     * @param dish
     * @return
     */
    @Override
    public List<DishVO> listWithFlavor(Dish dish) {
        LambdaQueryWrapper<Dish> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(dish.getCategoryId() != null, Dish::getCategoryId, dish.getCategoryId())
                .eq(dish.getStatus() != null, Dish::getStatus, dish.getStatus());
        List<Dish> dishList = dishMapper.selectList(queryWrapper);

        return dishList.stream().map(d -> {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d, dishVO);
            List<DishFlavor> flavors = dishFlavorMapper.getByDishId(d.getId());
            dishVO.setFlavors(flavors);
            return dishVO;
        }).collect(Collectors.toList());
    }

    /**
     * 菜品起售停售
     * @param status
     * @param id
     */
    @Override
    @Transactional
    @CacheEvict(allEntries = true)
    public void startOrStop(Integer status, Long id) {
        Dish dish = Dish.builder()
                .id(id)
                .status(status)
                .build();
        dishMapper.updateById(dish);

        // 如果是停售操作，还需要将包含当前菜品的套餐也停售
        if (status == StatusConstant.DISABLE) {
            List<Long> dishIds = Collections.singletonList(id);
            List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(dishIds);
            if (CollectionUtils.isNotEmpty(setmealIds)) {
                for (Long setmealId : setmealIds) {
                    Setmeal setmeal = Setmeal.builder()
                            .id(setmealId)
                            .status(StatusConstant.DISABLE)
                            .build();
                    setmealMapper.updateById(setmeal);
                }
            }
        }
    }

}

