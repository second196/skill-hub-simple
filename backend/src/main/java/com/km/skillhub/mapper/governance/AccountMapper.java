package com.km.skillhub.mapper.governance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.skillhub.governance.model.entity.AccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AccountMapper extends BaseMapper<AccountEntity> {

    @Select("SELECT id, username, password_hash, enabled FROM principal_account WHERE username = #{username}")
    AccountEntity findByUsername(String username);

    @Select("SELECT id, username, password_hash, enabled FROM principal_account WHERE id = #{id}")
    AccountEntity findById(Long id);

    @Select("SELECT id, username, password_hash, enabled FROM principal_account ORDER BY username")
    List<AccountEntity> findAllAccounts();

    @Update("UPDATE principal_account SET enabled = #{enabled} WHERE username = #{username}")
    int updateEnabled(@org.apache.ibatis.annotations.Param("username") String username,
                      @org.apache.ibatis.annotations.Param("enabled") boolean enabled);
}
