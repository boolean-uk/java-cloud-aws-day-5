package com.booleanuk.OrderService.controllers;


import com.booleanuk.OrderService.models.Order;
import com.booleanuk.OrderService.repositories.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

@RestController
@RequestMapping("orders")
public class OrderController {

    @Autowired
    OrderRepository orderRepository;

    private SqsClient sqsClient;
    private SnsClient snsClient;
    private EventBridgeClient eventBridgeClient;
    private ObjectMapper objectMapper;
    private String queueUrl;
    private String topicArn;
    private String eventBusName;

    public OrderController() {
        this.sqsClient = SqsClient.builder().build();
        this.snsClient = SnsClient.builder().build();
        this.eventBridgeClient = EventBridgeClient.builder().build();

        this.queueUrl = "https://sqs.eu-west-1.amazonaws.com/637423341661/sebgro98OrderQueue";
        this.topicArn = "arn:aws:sns:eu-west-1:637423341661:sebgro98OrderCreatedTopic";
        this.eventBusName = "sebgro98CustomEventBus";

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
            try {

                 //Had to extract the message field which contains the order object
                //Instead of using the entire message body which it was doing from the beginning
                String orderJson = extractMessageField(message.body());


                Order order = this.objectMapper.readValue(orderJson, Order.class);
                this.processOrder(order);


                DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build();

                sqsClient.deleteMessage(deleteRequest);
            } catch (JsonProcessingException e) {
                System.err.println("Failed to process order: " + e.getMessage());
            }
        }
        String status = String.format("%d Orders have been processed", messages.size());
        return ResponseEntity.ok(status);
    }

    private String extractMessageField(String messageBody) {
        try {
            // Parse the JSON to extract the message field
            JsonNode rootNode = objectMapper.readTree(messageBody);
            return rootNode.path("Message").asText();
        } catch (JsonProcessingException e) {
            System.err.println("Failed to extract message field: " + e.getMessage());
            return "{}";
        }
    }

    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody Order order) {
        order.setTotal(order.getAmount() * order.getQuantity());
        orderRepository.save(order);

        try {
            String orderJson = objectMapper.writeValueAsString(order);
            System.out.println("Sending order JSON: " + orderJson);
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

    @PutMapping("/{id}")
    public ResponseEntity<String> updateOrder(@PathVariable int id, @RequestBody Order updatedOrder) {
        return orderRepository.findById(id)
                .map(order -> {
                    order.setProduct(updatedOrder.getProduct());
                    order.setQuantity(updatedOrder.getQuantity());
                    order.setAmount(updatedOrder.getAmount());
                    order.setTotal(updatedOrder.getQuantity() * updatedOrder.getAmount());
                    order.setProcessed(updatedOrder.isProcessed());
                    orderRepository.save(order);
                    return ResponseEntity.ok("Order updated successfully");
                })
                .orElseGet(() -> ResponseEntity.status(404).body("Order not found"));
    }

    private void processOrder(Order order) {
        order.setProcessed(true);
        System.out.println(order.toString());
        orderRepository.save(order);

    }
}
