package fr.vvlabs.recherche;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(scanBasePackages = {"com.example.pocrecherche"})
public class POCApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(POCApplication.class, args);
        String jdbcUrl = ctx.getEnvironment().getProperty("spring.datasource.url");
        System.out.println("Webapp : http://localhost:8080/index.html");
        System.out.println("Swagger : http://localhost:8080/swagger-ui/index.html");
        System.out.println("H2 Console: http://localhost:8080/h2-console/ and DB URL : "+ jdbcUrl);
    }
}

