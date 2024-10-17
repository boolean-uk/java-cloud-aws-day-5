package com.booleanuk.OrderService.controllers;


import com.booleanuk.OrderService.models.Order;
import com.booleanuk.OrderService.models.QueueMessage;
import com.booleanuk.OrderService.repositories.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.JSONPObject;
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
    private final SqsClient sqsClient;
    private final SnsClient snsClient;
    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;
    private final String topicArn;
    private final String eventBusName;

    private final OrderRepository orderRepository;

    public OrderController(OrderRepository orderRepository) {
        this.sqsClient = SqsClient.builder().build();
        this.snsClient = SnsClient.builder().build();
        this.eventBridgeClient = EventBridgeClient.builder().build();

        this.queueUrl = "https://sqs.eu-west-1.amazonaws.com/637423341661/dagOrderQueue";
        this.topicArn = "arn:aws:sns:eu-west-1:637423341661:dagOrderCreatedTopic";
        this.eventBusName = "dagCustomEventBus";

        this.objectMapper = new ObjectMapper();
        this.orderRepository = orderRepository;
    }


    @GetMapping
    public ResponseEntity<String> GetAllOrders() {
        // bygg den request: hent alle orders
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                // fra denne url'en
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .build();

        // hent messages fra client fra requesten vi bygde
        List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();

        // for alle messages:
        for (Message message : messages) {
            try {
                // map en json order til Order order
                JSONPObject obj = new JSONPObject(message.body(), QueueMessage.class);
                QueueMessage qm = this.objectMapper.readValue(message.body(), QueueMessage.class);
                Order order = this.objectMapper.readValue(qm.getMessage(), Order.class);

                //retrieve the obj id

                //TODO: fix processOrder
                this.processOrder(order);

                // slett en order etter at vi har processert den
                DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build();

                sqsClient.deleteMessage(deleteRequest);
            } catch (JsonProcessingException e) {

                System.out.println(message.body());
                e.printStackTrace();
            }
        }

        String status = String.format("%d Orders have been processed", messages.size());
        return ResponseEntity.ok(status);
    }

    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody Order order) {
//        System.out.println("Putting order:" +order);
//        orderRepository.save(order);

        // creating a new order which is unprocessed and correct total
        Order newOrder = new Order();
        newOrder.setProduct(order.getProduct());
        newOrder.setQuantity(order.getQuantity());
        newOrder.setAmount(order.getAmount());
        newOrder.setProcessed(false);
        newOrder.setTotal(order.getQuantity() * order.getAmount());

        // should be updated with id now.
        newOrder = orderRepository.save(newOrder);

        try {
            String orderJson = objectMapper.writeValueAsString(newOrder);
            System.out.println("orderjson: " + orderJson);
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
            e.printStackTrace();
            System.out.println("Error when creating");
            return ResponseEntity.status(500).body("Failed to create order");
        }
    }

    //TODO: fix processOrder
    private void processOrder(Order order) {
        System.out.println(order.toString());
        Order updateOrder = orderRepository.findById(order.getId()).get();
        updateOrder.setProcessed(true);
        System.out.println("Saved: " + updateOrder + " to db.");
        orderRepository.save(updateOrder);

    }
}
