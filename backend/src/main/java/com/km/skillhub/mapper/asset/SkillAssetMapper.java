package com.km.skillhub.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.skillhub.asset.model.entity.SkillAssetEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

public interface SkillAssetMapper extends BaseMapper<SkillAssetEntity> {

    @Select("SELECT id, asset_key, name, description, owner_scope_id, namespace_id, status, created_by, updated_by "
            + "FROM skill_asset WHERE asset_key = #{assetKey}")
    SkillAssetEntity findByAssetKey(String assetKey);

    @Select("SELECT 1 WHERE pg_advisory_xact_lock(hashtextextended(#{assetKey}, 0)) IS NULL")
    Integer lockAssetKey(String assetKey);

    @Insert("INSERT INTO skill_asset(asset_key, name, description, owner_scope_id, namespace_id, slug, status, created_by, updated_by) "
            + "VALUES(#{assetKey}, #{name}, #{description}, #{ownerScopeId}, "
            + "(SELECT id FROM skill_namespace WHERE namespace_key = 'skillhub'), #{assetKey}, #{status}, #{createdBy}, #{updatedBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertAsset(SkillAssetEntity entity);
}
