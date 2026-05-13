package com.pr.automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AutomationApplication {
	public static void main(String[] args) {
		SpringApplication.run(AutomationApplication.class, args);
	}
}
