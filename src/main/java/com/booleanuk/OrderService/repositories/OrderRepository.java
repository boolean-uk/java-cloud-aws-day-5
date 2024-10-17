package com.booleanuk.OrderService.repositories;

import com.booleanuk.OrderService.models.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Integer> {


}
