package com.company.ability;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 能力测评系统启动类
 */
@SpringBootApplication
@MapperScan("com.company.ability.mapper")
public class AbilityAssessmentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AbilityAssessmentApplication.class, args);
    }
}
