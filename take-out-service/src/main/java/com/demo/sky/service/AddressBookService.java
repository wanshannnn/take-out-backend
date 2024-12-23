package com.demo.sky.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.demo.sky.dao.AddressBook;

import java.util.List;

public interface AddressBookService extends IService<AddressBook> {

    /**
     * 条件查询
     * @param addressBook
     * @return
     */
    List<AddressBook> list(AddressBook addressBook);

    /**
     * 新增地址
     *
     * @param addressBook
     * @return
     */
    boolean save(AddressBook addressBook);

    /**
     * 根据id查询
     * @param id
     * @return
     */
    AddressBook getById(Long id);

    /**
     * 根据id修改地址
     * @param addressBook
     */
    void update(AddressBook addressBook);

    /**
     * 设置默认地址
     * @param addressBook
     */
    void setDefault(AddressBook addressBook);

    /**
     * 根据id删除地址
     * @param id
     */
    void deleteById(Long id);

}
