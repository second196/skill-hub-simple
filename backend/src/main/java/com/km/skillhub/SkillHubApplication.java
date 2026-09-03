package com.km.skillhub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(basePackages = {
        "com.km.skillhub.mapper",
        "com.km.skillhub.release.mapper",
        "com.km.skillhub.policy.mapper",
        "com.km.skillhub.gate.mapper",
        "com.km.skillhub.audit.mapper",
        "com.km.skillhub.governance.mapper",
        "com.km.skillhub.installation.mapper",
        "com.km.skillhub.integration.event",
        "com.km.skillhub.discovery.mapper",
        "com.km.skillhub.namespace.mapper",
        "com.km.skillhub.review.mapper",
        "com.km.skillhub.version.mapper",
        "com.km.skillhub.token.mapper"
})
public class SkillHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillHubApplication.class, args);
    }
}
