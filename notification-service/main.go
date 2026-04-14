package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/segmentio/kafka-go"
)

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

type OrderEvent struct {
	OrderID string `json:"orderId"`
	Items   []struct {
		ProductID int `json:"productId"`
		Quantity  int `json:"quantity"`
	} `json:"items"`
	Status    string `json:"status"`
	Timestamp string `json:"timestamp"`
	Chaos     bool   `json:"chaos"`
}

func connectKafkaReader(brokers []string, group, topic string) *kafka.Reader {
	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        brokers,
		GroupID:        group,
		Topic:          topic,
		StartOffset:    kafka.FirstOffset,
		CommitInterval: time.Second,
		MaxWait:        500 * time.Millisecond,
	})

	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := kafka.DialLeader(context.Background(), "tcp", brokers[0], topic, 0)
		if err == nil {
			conn.Close()
			log.Println("Connected to Kafka (consumer reader)")
			return r
		}
		log.Printf("Waiting for Kafka: %v", err)
		time.Sleep(2 * time.Second)
	}
	log.Println("Warning: could not verify Kafka connection, proceeding anyway")
	return r
}

func processMessage(msg kafka.Message) {
	var event OrderEvent
	if err := json.Unmarshal(msg.Value, &event); err != nil {
		log.Printf("Failed to parse message at offset %d: %v", msg.Offset, err)
		return
	}

	log.Printf("Processing order notification for order %s", event.OrderID)

	if event.Chaos {
		processChaosMessage(event)
	} else {
		processNormalMessage(event)
	}
}

func processNormalMessage(event OrderEvent) {
	delay := time.Duration(100+rand.Intn(200)) * time.Millisecond
	log.Printf("Sending notification for order %s (normal path, delay=%v)", event.OrderID, delay)
	time.Sleep(delay)
	log.Printf("Successfully sent notification for order %s", event.OrderID)
}

func processChaosMessage(event OrderEvent) {
	log.Printf("CHAOS: Detected chaos flag for order %s", event.OrderID)

	log.Printf("CHAOS: Attempt 1/3 — processing notification for order %s", event.OrderID)
	time.Sleep(1 * time.Second)
	log.Printf("ERROR: Failed to process notification: connection timeout to email service (order %s, attempt 1/3)", event.OrderID)

	log.Printf("CHAOS: Retrying after 500ms for order %s", event.OrderID)
	time.Sleep(500 * time.Millisecond)
	log.Printf("CHAOS: Attempt 2/3 — processing notification for order %s", event.OrderID)
	time.Sleep(1500 * time.Millisecond)
	log.Printf("ERROR: Failed to process notification: email service returned 503 (order %s, attempt 2/3)", event.OrderID)

	log.Printf("CHAOS: Retrying after 1s for order %s", event.OrderID)
	time.Sleep(1 * time.Second)
	log.Printf("CHAOS: Attempt 3/3 — processing notification for order %s", event.OrderID)
	time.Sleep(2 * time.Second)
	log.Printf("ERROR: Failed to process notification: max retries exceeded (order %s, attempt 3/3)", event.OrderID)

	log.Printf("ERROR: All retry attempts exhausted for order %s — marking message as processed with error", event.OrderID)
}

func main() {
	brokers := strings.Split(getEnv("KAFKA_BROKERS", "kafka:9092"), ",")
	group := "notification-group"
	topic := "order-events"

	reader := connectKafkaReader(brokers, group, topic)
	defer reader.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		for {
			msg, err := reader.FetchMessage(ctx)
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				log.Printf("Error fetching message: %v", err)
				continue
			}

			processMessage(msg)

			if err := reader.CommitMessages(ctx, msg); err != nil {
				log.Printf("Error committing message: %v", err)
			}
		}
	}()

	log.Println("Notification service started — consuming from order-events")

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutting down notification service...")
	cancel()
	fmt.Println("Notification service stopped")
}
