package poc.spring4.samples.spring4_poc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class Spring4PocApplication {

	public static void main(String[] args) {
		SpringApplication.run(Spring4PocApplication.class, args);
	}

}
