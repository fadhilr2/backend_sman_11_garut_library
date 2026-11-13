package id.sch.sman11garut.library;

import org.apache.catalina.filters.ExpiresFilter.ExpiresConfiguration;
import org.springframework.boot.SpringApplication;

public class TestLibraryApplication {

	public static void main(String[] args) {
		SpringApplication.from(LibraryApplication::main).with(ExpiresConfiguration.class).run(args);
	}

}
