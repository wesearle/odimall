package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
	"github.com/gorilla/mux"
	"github.com/segmentio/kafka-go"
)

var (
	db          *sql.DB
	kafkaWriter *kafka.Writer
)

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func connectMySQL() (*sql.DB, error) {
	host := getEnv("DB_HOST", "mysql")
	port := getEnv("DB_PORT", "3306")
	user := getEnv("DB_USER", "odimall")
	pass := getEnv("DB_PASSWORD", "odimall123")
	name := getEnv("DB_NAME", "odimall")

	dsn := fmt.Sprintf("%s:%s@tcp(%s:%s)/%s?parseTime=true", user, pass, host, port, name)

	var conn *sql.DB
	var err error
	deadline := time.Now().Add(60 * time.Second)

	for time.Now().Before(deadline) {
		conn, err = sql.Open("mysql", dsn)
		if err == nil {
			err = conn.Ping()
		}
		if err == nil {
			log.Println("Connected to MySQL")
			return conn, nil
		}
		log.Printf("Waiting for MySQL: %v", err)
		time.Sleep(2 * time.Second)
	}
	return nil, fmt.Errorf("failed to connect to MySQL after 60s: %w", err)
}

func initKafkaWriter() *kafka.Writer {
	brokers := strings.Split(getEnv("KAFKA_BROKERS", "kafka:9092"), ",")

	w := &kafka.Writer{
		Addr:         kafka.TCP(brokers...),
		Topic:        "order-events",
		Balancer:     &kafka.LeastBytes{},
		RequiredAcks: kafka.RequireAll,
		MaxAttempts:  5,
	}

	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := kafka.DialLeader(context.Background(), "tcp", brokers[0], "order-events", 0)
		if err == nil {
			conn.Close()
			log.Println("Connected to Kafka (producer)")
			return w
		}
		log.Printf("Waiting for Kafka: %v", err)
		time.Sleep(2 * time.Second)
	}
	log.Println("Warning: could not verify Kafka connection, proceeding anyway")
	return w
}

type ReserveItem struct {
	ProductID int `json:"productId"`
	Quantity  int `json:"quantity"`
}

type ReserveRequest struct {
	OrderID string        `json:"orderId"`
	Items   []ReserveItem `json:"items"`
}

type KafkaOrderEvent struct {
	OrderID   string        `json:"orderId"`
	Items     []ReserveItem `json:"items"`
	Status    string        `json:"status"`
	Timestamp string        `json:"timestamp"`
	Chaos     bool          `json:"chaos"`
}

type KafkaDLQEvent struct {
	OrderID   string `json:"orderId"`
	Error     string `json:"error"`
	ProductID int    `json:"productId"`
}

func sqlComment(r *http.Request, query string) string {
	if tp := r.Header.Get("Traceparent"); tp != "" {
		return query + " /*traceparent='" + tp + "'*/"
	}
	return query
}

func reserveHandler(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var req ReserveRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid request body"}`, http.StatusBadRequest)
		return
	}

	log.Printf("Reserve request for order %s with %d items", req.OrderID, len(req.Items))

	var reservedItems []ReserveItem
	chaosTriggered := false

	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	updateSQL := sqlComment(r, "UPDATE inventory SET quantity = quantity - ?, reserved = reserved + ? WHERE product_id = ? AND quantity >= ?")
	for _, item := range req.Items {
		result, err := tx.ExecContext(ctx,
			updateSQL,
			item.Quantity, item.Quantity, item.ProductID, item.Quantity,
		)
		if err != nil {
			log.Printf("Failed to reserve product %d: %v", item.ProductID, err)
			http.Error(w, fmt.Sprintf(`{"error":"failed to reserve product %d"}`, item.ProductID), http.StatusInternalServerError)
			return
		}

		rows, _ := result.RowsAffected()
		if rows == 0 {
			log.Printf("Insufficient inventory for product %d (requested %d)", item.ProductID, item.Quantity)
			http.Error(w, fmt.Sprintf(`{"error":"insufficient inventory for product %d"}`, item.ProductID), http.StatusConflict)
			return
		}

		reservedItems = append(reservedItems, item)

		if item.ProductID == 5 {
			chaosTriggered = true
		}
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to commit reservation"}`, http.StatusInternalServerError)
		return
	}

	event := KafkaOrderEvent{
		OrderID:   req.OrderID,
		Items:     reservedItems,
		Status:    "reserved",
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Chaos:     chaosTriggered,
	}

	if chaosTriggered {
		log.Printf("CHAOS: Storm Chaser Tent (productId=5) detected in order %s — injecting delay", req.OrderID)
		time.Sleep(2 * time.Second)
	}

	eventBytes, _ := json.Marshal(event)

	err = kafkaWriter.WriteMessages(ctx, kafka.Message{
		Key:   []byte(req.OrderID),
		Value: eventBytes,
	})
	if err != nil {
		log.Printf("Failed to produce Kafka message: %v", err)
		http.Error(w, `{"error":"failed to produce event"}`, http.StatusInternalServerError)
		return
	}
	log.Printf("Produced order event to order-events for order %s (chaos=%v)", req.OrderID, chaosTriggered)

	if chaosTriggered {
		dlqEvent := KafkaDLQEvent{
			OrderID:   req.OrderID,
			Error:     "chaos_injection",
			ProductID: 5,
		}
		dlqBytes, _ := json.Marshal(dlqEvent)

		dlqWriter := &kafka.Writer{
			Addr:         kafkaWriter.Addr,
			Topic:        "order-events-dlq",
			Balancer:     &kafka.LeastBytes{},
			RequiredAcks: kafka.RequireAll,
		}
		dlqErr := dlqWriter.WriteMessages(ctx, kafka.Message{
			Key:   []byte(req.OrderID),
			Value: dlqBytes,
		})
		dlqWriter.Close()
		if dlqErr != nil {
			log.Printf("Failed to produce DLQ message: %v", dlqErr)
		} else {
			log.Printf("Produced DLQ event to order-events-dlq for order %s", req.OrderID)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"success":       true,
		"reservedItems": reservedItems,
	})
}

func getInventoryHandler(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	vars := mux.Vars(r)
	productID, err := strconv.Atoi(vars["productId"])
	if err != nil {
		http.Error(w, `{"error":"invalid product ID"}`, http.StatusBadRequest)
		return
	}

	var quantity, reserved int
	err = db.QueryRowContext(ctx, sqlComment(r, "SELECT quantity, reserved FROM inventory WHERE product_id = ?"), productID).Scan(&quantity, &reserved)
	if err == sql.ErrNoRows {
		http.Error(w, `{"error":"product not found"}`, http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"productId": productID,
		"quantity":  quantity,
		"reserved":  reserved,
	})
}

func main() {
	var err error

	db, err = connectMySQL()
	if err != nil {
		log.Fatalf("MySQL connection failed: %v", err)
	}
	defer db.Close()

	kafkaWriter = initKafkaWriter()
	defer kafkaWriter.Close()

	r := mux.NewRouter()
	r.HandleFunc("/inventory/reserve", reserveHandler).Methods("POST")
	r.HandleFunc("/inventory/{productId}", getInventoryHandler).Methods("GET")

	log.Println("Inventory service listening on :8088")
	if err := http.ListenAndServe(":8088", r); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}
