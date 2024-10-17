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

import java.lang.reflect.Type;
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

    @Autowired
    private OrderRepository repository;

    public OrderController() {
        this.sqsClient = SqsClient.builder().build();
        this.snsClient = SnsClient.builder().build();
        this.eventBridgeClient = EventBridgeClient.builder().build();

        this.queueUrl = "https://sqs.eu-west-1.amazonaws.com/637423341661/leoWahlandtOrderQueue";
        this.topicArn = "arn:aws:sns:eu-west-1:637423341661:leoWahlandtOrderCreatedTopic";
        this.eventBusName = "leoWahlandtCustomEventBus";
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
            System.out.println("Inside message: " + message.body());
            try {


                String msgJson = this.objectMapper.readTree(message.body()).get("Message").asText();

                Order order = this.objectMapper.readValue(msgJson, Order.class);
                System.out.println(order);
                System.out.println("Order: " + order);
                this.processOrder(order);


                DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build();

                sqsClient.deleteMessage(deleteRequest);
            } catch (JsonProcessingException e) {
                System.out.println("Catcher");
//                e.printStackTrace();
            }
        }

        /*
        aws sqs create-queue --queue-name leoWahlandtOrderQueue
        aws sns subscribe --topic-arn arn:aws:sns:eu-west-1:637423341661:leoWahlandtOrderCreatedTopic --protocol sqs --notification-endpoint arn:aws:sqs:eu-west-1:637423341661:leoWahlandtOrderQueue
        aws events create-event-bus --name leoWahlandtCustomEventBus --region eu-west-1
        aws events put-rule --name leoWahlandtOrderProcessedRule --event-pattern '{"source": ["order.service"]}' --event-bus-name leoWahlandtCustomEventBus
        aws sqs get-queue-attributes --queue-url https://sqs.eu-west-1.amazonaws.com/637423341661/leoWahlandtOrderQueue --attribute-name QueueArn --region eu-west-1
        aws sns subscribe --topic-arn arn:aws:sns:eu-west-1:637423341661:leoWahlandtOrderCreatedTopic --protocol sqs --notification-endpoint arn:aws:sqs:eu-west-1:637423341661:leoWahlandtOrderQueue --region eu-west-1
        aws sqs set-queue-attributes --queue-url https://sqs.eu-west-1.amazonaws.com/637423341661/leoWahlandtOrderQueue --attributes '{"Policy":"{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"SQS:SendMessage\",\"Resource\":\"arn:aws:sqs:eu-west-1:637423341661:leoWahlandtOrderQueue\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"arn:aws:sns:eu-west-1:637423341661:leoWahlandtOrderCreatedTopic\"}}}]}"}' --region eu-west-1
         */
        String status = String.format("%d Orders have been processed", messages.size());
        return ResponseEntity.ok(status);
    }

    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody Order order) {
        try {
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

    private void processOrder(Order order) {
        int updatedTotal = order.getAmount() * order.getQuantity();
        order.setTotal(updatedTotal);
        order.setProcessed(true);
        this.repository.save(order);
        System.out.println(order.toString());
    }
}
