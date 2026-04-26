package de.fimatas.feeds;

import lombok.extern.apachecommons.CommonsLog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@CommonsLog
@PropertySource(value = "classpath:application.properties", encoding = "UTF-8")
@PropertySource(value = "file:/Users/mfi/Documents/config/feeds/feeds.properties", encoding = "UTF-8", ignoreResourceNotFound = true)
@PropertySource(value = "file:/home/feedsapp/Documents/config/feeds/feeds.properties", encoding = "UTF-8", ignoreResourceNotFound = true)
public class FeedsApplication {

	public static void main(String[] args) {
		log.info("Spring Boot Version: " + SpringBootVersion.getVersion());
		SpringApplication.run(FeedsApplication.class, args);
	}

}
