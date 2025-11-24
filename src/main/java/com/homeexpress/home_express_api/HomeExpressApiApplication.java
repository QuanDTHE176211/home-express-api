package com.homeexpress.home_express_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableCaching
public class HomeExpressApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(HomeExpressApiApplication.class, args);
	}

}
