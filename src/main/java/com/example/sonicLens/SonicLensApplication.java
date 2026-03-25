package com.example.sonicLens;

import com.example.sonicLens.config.GcsConfig;
import com.example.sonicLens.config.SpotifyConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SpotifyConfig.class, GcsConfig.class})
public class SonicLensApplication {

	public static void main(String[] args) {
		SpringApplication.run(SonicLensApplication.class, args);
	}

}
