package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"time"
)

var (
	gatewayURL = getEnv("GATEWAY_URL", "http://api-gateway:8081")
	interval   = parseDuration(getEnv("INTERVAL", "5s"))
	client     = &http.Client{Timeout: 30 * time.Second}
)

var firstNames = []string{
	"James", "Emma", "Liam", "Olivia", "Noah", "Ava", "Ethan", "Sophia",
	"Mason", "Isabella", "Lucas", "Mia", "Logan", "Charlotte", "Alexander",
	"Amelia", "Daniel", "Harper", "Henry", "Evelyn", "Sebastian", "Abigail",
	"Jack", "Emily", "Aiden", "Ella", "Owen", "Scarlett", "Samuel", "Grace",
}

var lastNames = []string{
	"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
	"Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
	"Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
	"Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark",
	"Ramirez", "Lewis", "Robinson",
}

var streets = []string{
	"Main St", "Oak Ave", "Elm St", "Pine Rd", "Maple Dr", "Cedar Ln",
	"Birch Way", "Walnut St", "Spruce Ave", "Willow Ct", "Cherry Ln",
	"Ash Blvd", "Hickory Rd", "Poplar St", "Sycamore Dr", "Chestnut Ave",
}

var cities = []string{
	"Austin", "Denver", "Portland", "Seattle", "Boston", "Nashville",
	"San Diego", "Phoenix", "Atlanta", "Chicago", "Miami", "Minneapolis",
	"Raleigh", "Salt Lake City", "San Francisco", "New York",
}

var states = []string{
	"TX", "CO", "OR", "WA", "MA", "TN", "CA", "AZ", "GA", "IL",
	"FL", "MN", "NC", "UT", "NY", "PA",
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func parseDuration(s string) time.Duration {
	d, err := time.ParseDuration(s)
	if err != nil {
		log.Printf("Invalid INTERVAL %q, defaulting to 5s: %v", s, err)
		return 5 * time.Second
	}
	return d
}

func randName() string {
	return firstNames[rand.Intn(len(firstNames))] + " " + lastNames[rand.Intn(len(lastNames))]
}

func randAddress() string {
	return fmt.Sprintf("%d %s", rand.Intn(9999)+1, streets[rand.Intn(len(streets))])
}

func randZip() string {
	return fmt.Sprintf("%05d", rand.Intn(99999)+1)
}

type OrderItem struct {
	ProductID int     `json:"productId"`
	Quantity  int     `json:"quantity"`
	Price     float64 `json:"price"`
	Name      string  `json:"name"`
}

type Shipping struct {
	Name    string `json:"name"`
	Address string `json:"address"`
	City    string `json:"city"`
	State   string `json:"state"`
	Zip     string `json:"zip"`
}

type OrderRequest struct {
	SessionID string      `json:"sessionId"`
	Items     []OrderItem `json:"items"`
	Shipping  Shipping    `json:"shipping"`
}

type Product struct {
	ID       int     `json:"id"`
	Name     string  `json:"name"`
	Price    float64 `json:"price"`
	Category string  `json:"category"`
}

func fetchProducts() ([]Product, error) {
	resp, err := client.Get(gatewayURL + "/products")
	if err != nil {
		return nil, fmt.Errorf("GET /products: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("GET /products returned %d", resp.StatusCode)
	}

	var products []Product
	if err := json.NewDecoder(resp.Body).Decode(&products); err != nil {
		return nil, fmt.Errorf("decode products: %w", err)
	}
	return products, nil
}

func placeOrder(products []Product) error {
	numItems := rand.Intn(3) + 1
	items := make([]OrderItem, 0, numItems)
	used := make(map[int]bool)

	for i := 0; i < numItems; i++ {
		p := products[rand.Intn(len(products))]
		if used[p.ID] {
			continue
		}
		used[p.ID] = true
		items = append(items, OrderItem{
			ProductID: p.ID,
			Quantity:  rand.Intn(2) + 1,
			Price:     p.Price,
			Name:      p.Name,
		})
	}

	if len(items) == 0 {
		items = append(items, OrderItem{
			ProductID: products[0].ID,
			Quantity:  1,
			Price:     products[0].Price,
			Name:      products[0].Name,
		})
	}

	cityIdx := rand.Intn(len(cities))
	order := OrderRequest{
		SessionID: fmt.Sprintf("loadgen-%d", rand.Int63()),
		Items:     items,
		Shipping: Shipping{
			Name:    randName(),
			Address: randAddress(),
			City:    cities[cityIdx],
			State:   states[cityIdx%len(states)],
			Zip:     randZip(),
		},
	}

	body, _ := json.Marshal(order)
	resp, err := client.Post(gatewayURL+"/orders", "application/json", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("POST /orders: %w", err)
	}
	defer resp.Body.Close()

	productNames := make([]string, len(items))
	for i, item := range items {
		productNames[i] = fmt.Sprintf("%s(x%d)", item.Name, item.Quantity)
	}

	if resp.StatusCode == 201 {
		log.Printf("Order placed: %v → %s, %s %s | HTTP %d",
			productNames, order.Shipping.Name, order.Shipping.City, order.Shipping.State, resp.StatusCode)
	} else {
		log.Printf("Order FAILED: %v → HTTP %d", productNames, resp.StatusCode)
	}

	return nil
}

func browseProducts(products []Product) {
	p := products[rand.Intn(len(products))]
	resp, err := client.Get(fmt.Sprintf("%s/products/%d", gatewayURL, p.ID))
	if err != nil {
		log.Printf("Browse product %d failed: %v", p.ID, err)
		return
	}
	resp.Body.Close()
	log.Printf("Browsed product: %s (id=%d) | HTTP %d", p.Name, p.ID, resp.StatusCode)
}

func main() {
	log.Printf("OdiMall Load Generator starting — interval=%s gateway=%s", interval, gatewayURL)

	// Wait for gateway to be ready
	for i := 0; i < 30; i++ {
		resp, err := client.Get(gatewayURL + "/products")
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == 200 {
				log.Println("Gateway is ready")
				break
			}
		}
		log.Printf("Waiting for gateway... (%d/30)", i+1)
		time.Sleep(2 * time.Second)
	}

	products, err := fetchProducts()
	if err != nil {
		log.Fatalf("Failed to fetch products: %v", err)
	}
	log.Printf("Loaded %d products", len(products))

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		// 70% chance: place an order, 30% chance: just browse
		if rand.Float64() < 0.7 {
			if err := placeOrder(products); err != nil {
				log.Printf("Error placing order: %v", err)
			}
		} else {
			browseProducts(products)
		}
		<-ticker.C
	}
}
