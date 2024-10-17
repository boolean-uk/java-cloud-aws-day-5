package com.booleanuk.OrderService.controllers;


import com.booleanuk.OrderService.models.Order;
import com.booleanuk.OrderService.repositories.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.sns.model.NotFoundException;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

@RestController
@RequestMapping("orders")
public class OrderController {
    private SqsClient sqsClient;
    private SnsClient snsClient;
    private EventBridgeClient eventBridgeClient;
    private ObjectMapper objectMapper;
    private String queueUrl;
    private String topicArn;
    private String eventBusName;

    private final OrderRepository orderRepository;

    public OrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
        this.sqsClient = SqsClient.builder().build();
        this.snsClient = SnsClient.builder().build();
        this.eventBridgeClient = EventBridgeClient.builder().build();

        this.queueUrl = "https://sqs.eu-west-1.amazonaws.com/637423341661/ic4rus90OrderQueue";
        this.topicArn = "arn:aws:sns:eu-west-1:637423341661:ic4rus90OrderCreatedTopic";
        this.eventBusName = "ic4rus90CustomEventBus";

        this.objectMapper = new ObjectMapper();
    }

    @GetMapping
    public ResponseEntity<String> GetAllOrders() {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .build();

        List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();

        for (Message message : messages) {
            System.out.println(message);
            try {
                String messageJson = this.objectMapper.readTree(message.body()).get("Message").asText();
                Order order = this.objectMapper.readValue(messageJson, Order.class);

                this.processOrder(order);

                DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build();

                sqsClient.deleteMessage(deleteRequest);
            } catch (JsonProcessingException e) {
                System.out.println(e.getMessage());
            }
        }
        String status = String.format("%d Orders have been processed", messages.size());
        return ResponseEntity.ok(status);
    }

    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody Order order) {
        try {
            Order savedOrder = orderRepository.save(order);
            int savedOrderId = savedOrder.getId();
            order.setId(savedOrderId);
            String orderJson = objectMapper.writeValueAsString(order);
            System.out.println(orderJson);

            PublishRequest publishRequest = PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(orderJson)
                    .build();
            snsClient.publish(publishRequest);

            PutEventsRequestEntry eventEntry = PutEventsRequestEntry.builder()
                    .source("order.service")
                    .detailType("OrderCreated")
                    .detail(orderJson)
                    .eventBusName(eventBusName)
                    .build();

            PutEventsRequest putEventsRequest = PutEventsRequest.builder()
                    .entries(eventEntry)
                    .build();

            this.eventBridgeClient.putEvents(putEventsRequest);

            String status = "Order created, Message Published to SNS and Event Emitted to EventBridge";
            return ResponseEntity.ok(status);
        } catch (JsonProcessingException e) {
//            e.printStackTrace();
            return ResponseEntity.status(500).body("Failed to create order");
        }
    }

    /*
    @PutMapping("/{id}")
    public ResponseEntity<String> updateOrder(@PathVariable (name="id") String id, @RequestBody Order order) {
        try {
            Order existingOrder = orderRepository
                    .findById(Integer.valueOf(id))
                    .orElseThrow();

            existingOrder.setProduct(order.getProduct());
            existingOrder.setQuantity(order.getQuantity());
            existingOrder.setAmount(order.getAmount());
            existingOrder.setTotal(order.getTotal());
            order.setProcessed(order.isProcessed());

            orderRepository.save(order);

            String status = "Order updated, Message Published to SNS and Event Emitted to EventBridge";
            return ResponseEntity.ok(status);
        } catch (Exception e) {
//            e.printStackTrace();
            return ResponseEntity.status(500).body("Failed to create order");
        }
    }
    */


    private void processOrder(Order order) {
        int updatedTotal = order.getAmount() * order.getQuantity();
        order.setTotal(updatedTotal);

        orderRepository.findById(order.getId()).ifPresent(existingOrder -> {
            existingOrder.setTotal(updatedTotal);
            existingOrder.setProcessed(true);
            orderRepository.save(existingOrder);
        });
        System.out.println("Processed order: " + order.getId());
    }
}
