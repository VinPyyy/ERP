package com.wille.erp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ERP 进销存系统（模块化单体，spec §1）。
 * 包级模块：common / system / basedata / inventory / purchase / sale，
 * 依赖方向由 ArchitectureTest（ArchUnit）守护。
 */
@SpringBootApplication
@MapperScan("com.wille.erp.**.mapper")
public class ErpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpApplication.class, args);
    }
}
