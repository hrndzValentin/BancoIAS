package com.ias.banco_ias;

import org.springframework.boot.SpringApplication;

public class TestBancoIasApplication {

	public static void main(String[] args) {
		SpringApplication.from(BancoIasApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
