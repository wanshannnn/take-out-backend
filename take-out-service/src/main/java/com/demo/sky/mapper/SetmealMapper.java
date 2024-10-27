package com.demo.sky.mapper;

import com.github.pagehelper.Page;
import com.demo.sky.annotation.AutoFill;
import com.demo.sky.dto.SetmealPageQueryDTO;
import com.demo.sky.dao.Setmeal;
import com.demo.sky.enumeration.OperationType;
import com.demo.sky.vo.DishItemVO;
import com.demo.sky.vo.SetmealVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface SetmealMapper {

    /**
     * 根据分类id查询套餐的数量
     * @param categoryId
     * @return
     */
    Integer countByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 向套餐表插入数据
     * @param setmeal
     */
    @AutoFill(OperationType.INSERT)
    void insert(Setmeal setmeal);

    /**
     * 分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    Page<SetmealVO> pageQuery(SetmealPageQueryDTO setmealPageQueryDTO);

    /**
     * 根据id查询数据
     * @param id
     * @return
     */
    Setmeal getById(Long id);

    /**
     * 根据id删除数据
     * @param id
     */
    void deleteById(Long id);

    /**
     * 修改套餐表
     * @param setmeal
     */
    @AutoFill(OperationType.UPDATE)
    void update(Setmeal setmeal);

    /**
     * 条件查询
     * @param setmeal
     * @return
     */
    List<Setmeal> list(Setmeal setmeal);

    /**
     * 根据id查询菜品选项
     * @param id
     * @return
     */
    List<DishItemVO> getDishItemBySetmealId(Long id);

    /**
     * 根据条件统计套餐数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);
}
