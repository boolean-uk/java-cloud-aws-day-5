package com.booleanuk.OrderService.controllers;


import com.booleanuk.OrderService.models.Order;
import com.booleanuk.OrderService.repositories.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;

import java.util.List;

@RestController
@RequestMapping("orders")
public class OrderController {
    @Autowired
    private OrderRepository orderRepository;

    private SqsClient sqsClient;
    private SnsClient snsClient;
    private EventBridgeClient eventBridgeClient;
    private ObjectMapper objectMapper;
    private String queueUrl;
    private String topicArn;
    private String eventBusName;
    private String RuleArn;
    private String QueueArn;
    private String subscriptionArn;

    public OrderController() {
        Region region = Region.EU_WEST_1; // Specify your region here




        this.sqsClient = SqsClient.builder().region(region).build();
        this.snsClient = SnsClient.builder().region(region).build();
        this.eventBridgeClient = EventBridgeClient.builder().region(region).build();

        this.queueUrl = "https://sqs.eu-west-1.amazonaws.com/637423341661/josteinruenOrderQueue";
        this.topicArn = "arn:aws:sns:eu-west-1:637423341661:josteinruenOrderCreatedTopic";
        this.eventBusName = "arn:aws:events:eu-west-1:637423341661:event-bus/josteinruenCustomEventBus";
        this.RuleArn ="arn:aws:events:eu-west-1:637423341661:rule/josteinruenCustomEventBus/josteinruenOrderProcessedRule";
        this.QueueArn = "arn:aws:sqs:eu-west-1:637423341661:josteinruenOrderQueue";
        this.subscriptionArn = "\"arn:aws:sns:eu-west-1:637423341661:josteinruenOrderCreatedTopic:4587c0f2-2a33-4c27-9b78-b0bb20dd8ac2\"";

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
                Order order = this.objectMapper.readValue(message.body(), Order.class);
                this.processOrder(order);

                DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build();

                sqsClient.deleteMessage(deleteRequest);
            } catch (JsonProcessingException e) {
//                e.printStackTrace();
            }
        }
        String status = String.format("%d Orders have been processed", messages.size());
        return ResponseEntity.ok(status);
    }

    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody Order order) {
        try {
            order.setTotal(order.getQuantity() * order.getAmount());
            orderRepository.save(order);

            String orderJson = objectMapper.writeValueAsString(order);
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
            return ResponseEntity.status(500).body("Failed to create order");
        }
    }
    

    @PutMapping("/{id}")
    public ResponseEntity<String> updateOrder(@PathVariable Integer id, @RequestBody Order updatedOrder) {
        return orderRepository.findById(id)
                .map(order -> {
                    order.setProcessed(updatedOrder.isProcessed());
                    order.setQuantity(updatedOrder.getQuantity());
                    order.setAmount(updatedOrder.getAmount());
                    order.setTotal(updatedOrder.getQuantity() * updatedOrder.getAmount());
                    orderRepository.save(order);
                    return ResponseEntity.ok("Order updated");
                })
                .orElseGet(() -> ResponseEntity.status(404).body("Order not found"));
    }



    private void processOrder(Order order) {
        System.out.println(order.toString());
    }
}
