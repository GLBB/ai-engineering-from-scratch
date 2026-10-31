package dev.learning.agent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** Starts the local Spring Boot browser interface. */
@SpringBootApplication
public class WebMain {
    public static void main(String[] args) {
        var context = SpringApplication.run(WebMain.class, args);
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        System.out.println("Agent ready at http://127.0.0.1:" + port);
    }
}
