package com.booleanuk.OrderService.controllers;


import com.booleanuk.OrderService.models.Order;
import com.booleanuk.OrderService.repositories.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("orders")
public class OrderController {
    private final SqsClient sqsClient;
    private final SnsClient snsClient;
    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final OrderRepository repository;
    private final String queueUrl;
    private final String topicArn;
    private final String eventBusName;

    public OrderController(OrderRepository repository) {
        this.sqsClient = SqsClient.builder().build();
        this.snsClient = SnsClient.builder().build();
        this.eventBridgeClient = EventBridgeClient.builder().build();

        this.queueUrl = "";
        this.topicArn = "";
        this.eventBusName = "";

        this.objectMapper = new ObjectMapper();

        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<Order>> get() {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .build();

        List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();
        List<Order> orders = new ArrayList<>();

        for (Message message : messages) {
            try {
                JsonNode jsonNode = objectMapper.readTree(message.body());
                String orderJson = jsonNode.get("Message").asText();
                orders.add(this.objectMapper.readValue(orderJson, Order.class));

                DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build();

                this.sqsClient.deleteMessage(deleteRequest);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }

        return ResponseEntity.ok(orders);
    }

    @PostMapping
    public ResponseEntity<Order> post(@RequestBody Order order) {
        try {
            Order processed = this.process(order);
            String orderJson = objectMapper.writeValueAsString(processed);
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

            return ResponseEntity.ok(processed);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private Order process(Order order) {
        order.setTotal(order.getQuantity() * order.getAmount());
        order.setProcessed(true);
        return this.repository.save(order);
    }

    @PutMapping(value = "{id}")
    public ResponseEntity<Order> put(@PathVariable int id, @RequestBody Order order) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
            this.repository.findById(id)
            .map(existing -> {
                existing.setAmount(order.getAmount());
                existing.setQuantity(order.getQuantity());
                existing.setProduct(order.getProduct());
                existing.setTotal(existing.getQuantity() * existing.getAmount());
                return existing;
            })
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
        );
    }

    @DeleteMapping(value = "{id}")
    public ResponseEntity<Order> delete(@PathVariable int id) {
        var existing = this.repository.findById(id);

        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(5)
            .build();

        List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();

        for (Message message : messages) {
            try {
                JsonNode jsonNode = objectMapper.readTree(message.body());
                String orderJson = jsonNode.get("Message").asText();
                Order queueOrder = objectMapper.readValue(orderJson, Order.class);

                if (queueOrder.getId() == id) {
                    DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build();

                    sqsClient.deleteMessage(deleteRequest);
                    break;
                }
            } catch (JsonProcessingException e) {
                System.err.println("Error processing message while searching for order " + id);
                e.printStackTrace();
            }
        }

        this.repository.deleteById(id);
        return ResponseEntity.ok(existing.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}