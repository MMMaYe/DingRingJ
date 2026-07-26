package com.dingring;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DingRing AI 群聊学习系统启动类。
 */
@SpringBootApplication
@MapperScan("com.dingring.infrastructure.persistence.mapper")
public class DingRingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DingRingApplication.class, args);
    }
}
