package id.sch.sman11garut.library;

import id.sch.sman11garut.library.config.JwtProperties;
import id.sch.sman11garut.library.config.StorageProperties;
import id.sch.sman11garut.library.config.TrashProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({StorageProperties.class, JwtProperties.class, TrashProperties.class})
public class LibraryApplication {

	public static void main(String[] args) {
		SpringApplication.run(LibraryApplication.class, args);
	}

}
