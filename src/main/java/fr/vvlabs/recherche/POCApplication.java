package fr.vvlabs.recherche;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(scanBasePackages = {"fr.vvlabs.recherche"})
public class POCApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(POCApplication.class, args);
        String jdbcUrl = ctx.getEnvironment().getProperty("spring.datasource.url");
        String indexer = ctx.getEnvironment().getProperty("app.indexer.default");
        String parser = ctx.getEnvironment().getProperty("app.parser.ocr.default");

        System.out.println("Webapp\t\t\t\t: http://localhost:8080/index.html");
        System.out.println("Swagger\t\t\t\t: http://localhost:8080/swagger-ui/index.html");
        System.out.println("H2 Console\t\t\t: http://localhost:8080/h2-console/ and DB URL : "+ jdbcUrl);
        System.out.println("Index & Search with\t: "+ indexer);
        System.out.println("Content Parser with\t: "+ parser);
    }
}

