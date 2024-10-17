package com.booleanuk.OrderService.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@JsonIgnoreProperties(ignoreUnknown = true)
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

    public Order(String product, int quantity, int amount, boolean processed, int total) {
        this.product = product;
        this.quantity = quantity;
        this.amount = amount;
        this.processed = processed;
        this.total = total;
    }
}
