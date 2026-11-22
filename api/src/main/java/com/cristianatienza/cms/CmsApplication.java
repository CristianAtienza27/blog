package com.cristianatienza.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Backend del CMS: punto de entrada de la aplicación Spring Boot. */
@SpringBootApplication
public class CmsApplication {

  public static void main(String[] args) {
    SpringApplication.run(CmsApplication.class, args);
  }
}
