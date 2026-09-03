package com.km.skillhub.gate.mapper;

import com.km.skillhub.gate.model.entity.GateEvidenceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface GateEvidenceMapper {
    @Insert("INSERT INTO gate_evidence(version_digest, evidence_type, result, producer_type, producer_id, "
            + "evidence_uri, evidence_digest, conditions, generated_at, expires_at) VALUES(#{versionDigest}, "
            + "#{evidenceType}, #{result}, #{producerType}, #{producerId}, #{evidenceUri}, #{evidenceDigest}, "
            + "CAST(#{conditions} AS jsonb), #{generatedAt}, #{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(GateEvidenceEntity entity);

    @Select({"<script>", "SELECT id, version_digest, evidence_type, result, producer_type, producer_id,",
            "evidence_uri, evidence_digest, conditions::text AS conditions, generated_at, expires_at",
            "FROM gate_evidence WHERE version_digest = #{versionDigest} AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<GateEvidenceEntity> findByIds(@Param("versionDigest") String versionDigest,
                                        @Param("ids") List<Long> ids);
}
