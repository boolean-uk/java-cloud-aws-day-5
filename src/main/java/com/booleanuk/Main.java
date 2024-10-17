package com.booleanuk;

import com.booleanuk.OrderService.controllers.OrderController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({OrderController.class})
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}


Caused by: org.springframework.beans.BeanInstantiationException:
Failed to instantiate [com.booleanuk.OrderService.controllers.OrderController]: Constructor threw exception