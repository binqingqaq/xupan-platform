package com.xupan.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class XupanServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(XupanServerApplication.class, args);
    }

}
