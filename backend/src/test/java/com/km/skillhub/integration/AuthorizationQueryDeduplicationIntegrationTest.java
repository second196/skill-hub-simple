package com.km.skillhub.integration;

import com.km.skillhub.catalog.model.vo.AssetCatalogItemVO;
import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import com.km.skillhub.discovery.mapper.SkillDiscoveryMapper;
import com.km.skillhub.discovery.model.vo.SkillDiscoveryItemVO;
import com.km.skillhub.mapper.catalog.AssetCatalogMapper;
import com.km.skillhub.namespace.mapper.SkillNamespaceMapper;
import com.km.skillhub.namespace.model.entity.SkillNamespaceEntity;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthorizationQueryDeduplicationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssetCatalogMapper assetCatalogMapper;

    @Autowired
    private SkillDiscoveryMapper skillDiscoveryMapper;

    @Autowired
    private SkillNamespaceMapper skillNamespaceMapper;

    @Test
    void multipleRolesDoNotDuplicateCatalogRowsOrBreakDetailLookup() {
        Long assetId = firstActiveAssetId();
        Assumptions.assumeTrue(assetId != null, "requires an imported active asset");

        List<AssetCatalogItemVO> items = assetCatalogMapper.search("admin", null, null, 100, 0);
        assertEquals(1, countAsset(items, assetId));

        AssetDetailVO detail = assetCatalogMapper.findDetail(assetId, "admin");
        assertNotNull(detail);
        assertEquals(assetId, detail.getAssetId());

        try {
            mockMvc.perform(get("/api/v1/assets/{assetId}", assetId).with(user("admin")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assetId").value(assetId.intValue()));
        } catch (Exception exception) {
            throw new AssertionError("asset detail endpoint should return one authorized asset", exception);
        }

        List<SkillVersionEntity> versions = assetCatalogMapper.findVersions(assetId, "admin");
        assertEquals(versions.size(), new HashSet<String>(versionDigests(versions)).size());
    }

    @Test
    void multipleRolesDoNotDuplicateDiscoveryOrNamespaceRows() {
        Long assetId = firstDiscoverableAssetId();
        Assumptions.assumeTrue(assetId != null, "requires an imported namespaced active asset");

        List<SkillDiscoveryItemVO> items = skillDiscoveryMapper.search("admin", null, null, null, null, 100, 0);
        assertEquals(1, countDiscoveredAsset(items, assetId));

        List<SkillNamespaceEntity> namespaces = skillNamespaceMapper.findAuthorized("admin");
        Set<Long> namespaceIds = new HashSet<Long>();
        for (SkillNamespaceEntity namespace : namespaces) {
            namespaceIds.add(namespace.getId());
        }
        assertEquals(namespaces.size(), namespaceIds.size());
    }

    private Long firstActiveAssetId() {
        return jdbcTemplate.queryForObject(
                "SELECT min(id) FROM skill_asset WHERE status = 'ACTIVE'", Long.class);
    }

    private Long firstDiscoverableAssetId() {
        return jdbcTemplate.queryForObject(
                "SELECT min(sa.id) FROM skill_asset sa JOIN skill_namespace sn ON sn.id = sa.namespace_id "
                        + "WHERE sa.status = 'ACTIVE' AND sn.status = 'ACTIVE'", Long.class);
    }

    private int countAsset(List<AssetCatalogItemVO> items, Long assetId) {
        int count = 0;
        for (AssetCatalogItemVO item : items) {
            if (assetId.equals(item.getAssetId())) {
                count++;
            }
        }
        return count;
    }

    private int countDiscoveredAsset(List<SkillDiscoveryItemVO> items, Long assetId) {
        int count = 0;
        for (SkillDiscoveryItemVO item : items) {
            if (assetId.equals(item.getAssetId())) {
                count++;
            }
        }
        return count;
    }

    private Set<String> versionDigests(List<SkillVersionEntity> versions) {
        Set<String> digests = new HashSet<String>();
        for (SkillVersionEntity version : versions) {
            digests.add(version.getVersionDigest());
        }
        return digests;
    }
}
