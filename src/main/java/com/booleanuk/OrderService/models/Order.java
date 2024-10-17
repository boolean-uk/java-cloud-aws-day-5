package com.booleanuk.OrderService.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column
    private String product;
    @Column
    private int quantity;
    @Column
    private int amount;
    @Column
    private boolean processed;
    @Column
    private int total;

    public Order(int id) {
        this.id = id;
    }

    public Order(String product, int quantity, int amount, boolean processed) {
        this.product = product;
        this.quantity = quantity;
        this.amount = amount;
        this.processed = processed;
        this.total = quantity * amount;
    }

}
