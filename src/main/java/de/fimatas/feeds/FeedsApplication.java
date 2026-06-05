package de.fimatas.feeds;

import lombok.extern.apachecommons.CommonsLog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;

@SpringBootApplication
@EnableScheduling
@CommonsLog
@PropertySource(value = "classpath:application.properties", encoding = "UTF-8")
@PropertySource(value = "file:${user.home}/Documents/base/config/feeds/feeds.properties", encoding = "UTF-8")
public class FeedsApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(FeedsApplication.class);
		app.addListeners((ApplicationListener<ApplicationStartedEvent>) event -> {
			log.info("Spring Boot Version: " + SpringBootVersion.getVersion());
			logBytecodeVersion();
		});
		app.run(args);
	}

	private static void logBytecodeVersion() {
		Class<?> c = MethodHandles.lookup().lookupClass();
		try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
			if (in != null) {
				log.info("Java Bytecode Version: " + (in.readAllBytes()[7] - 44));
			} else {
				log.warn("Java Bytecode Version: unknown");
			}
		} catch (Exception e) {
			log.error("Java Bytecode Version: unknown", e);
		}
	}
}
