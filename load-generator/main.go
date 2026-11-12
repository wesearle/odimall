package main

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

var (
	gatewayURL = getEnv("GATEWAY_URL", "https://api-gateway:8081")
	interval   = parseDuration(getEnv("INTERVAL", "5s"))
	client     *http.Client
)

// newHTTPClient builds an HTTP client. For https:// GATEWAY_URL it loads a CA PEM
// from TLS_CA_FILE and verifies the server certificate (InsecureSkipVerify is never set).
func newHTTPClient(baseURL string) *http.Client {
	c := &http.Client{Timeout: 30 * time.Second}
	if !strings.HasPrefix(strings.ToLower(baseURL), "https://") {
		return c
	}
	caFile := getEnv("TLS_CA_FILE", "/etc/odimall/tls/ca.crt")
	pem, err := os.ReadFile(caFile)
	if err != nil {
		log.Fatalf("HTTPS gateway requires CA file %s: %v", caFile, err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem) {
		log.Fatalf("Failed to parse CA certificate(s) from %s", caFile)
	}
	c.Transport = &http.Transport{
		TLSClientConfig: &tls.Config{
			RootCAs:    pool,
			MinVersion: tls.VersionTLS12,
		},
	}
	return c
}

func kayakFaultEnabled() bool {
	v := strings.ToLower(strings.TrimSpace(getEnv("KAYAK_FAULT_ENABLED", "false")))
	return v == "true" || v == "1"
}

func attackEnabled() bool {
	v := strings.ToLower(strings.TrimSpace(getEnv("ATTACK_ENABLED", "false")))
	return v == "true" || v == "1"
}

// seededAccount is a real storefront credential (see mysql-init/init.sql). The
// load generator uses these for BASELINE good logins — the normal traffic the
// trace-anomaly detector learns before an attack stands out against it.
type seededAccount struct {
	Username string
	Password string
}

var seededAccounts = []seededAccount{
	{"alice", "Password123!"},
	{"bob", "Hunter2Pass!"},
	{"carol", "Tr@ilM1x99"},
}

// loginRequest / loginResponse mirror auth-service POST /auth/login.
type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type loginResponse struct {
	Token        string `json:"token"`
	Warning      string `json:"warning"`
	RowsReturned int    `json:"rowsReturned"`
	Exfiltrated  []struct {
		Username string `json:"username"`
		Role     string `json:"role"`
	} `json:"exfiltrated"`
	User struct {
		Username string `json:"username"`
		Role     string `json:"role"`
	} `json:"user"`
}

// postLogin sends POST /auth/login. extraHeaders lets the attacker spoof origin
// context (X-Forwarded-For, User-Agent, X-OdiMall-Origin) that the gateway forwards.
func postLogin(req loginRequest, extraHeaders map[string]string) (int, *loginResponse, error) {
	body, _ := json.Marshal(req)
	httpReq, err := http.NewRequest(http.MethodPost, gatewayURL+"/auth/login", bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	for k, v := range extraHeaders {
		httpReq.Header.Set(k, v)
	}
	resp, err := client.Do(httpReq)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	var out loginResponse
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, &out, nil
}

// authBaseline performs a legitimate login as a random seeded user from a normal
// origin. This is the "known good" authentication traffic.
func authBaseline() {
	acct := seededAccounts[rand.Intn(len(seededAccounts))]
	status, _, err := postLogin(
		loginRequest{Username: acct.Username, Password: acct.Password},
		map[string]string{"User-Agent": "OdiGear-Storefront/1.0"},
	)
	if err != nil {
		log.Printf("[auth] baseline login %s failed: %v", acct.Username, err)
		return
	}
	log.Printf("[auth] baseline login user=%s | HTTP %d", acct.Username, status)
}

// runAttacker runs the two-phase breach on its own cadence:
//
//	Phase 1 — credential theft: a SQL-injection payload in the username turns the
//	          login lookup into a full dump of the users table (tautology). The
//	          anomalous db.statement + row count on the `users` SELECT is the signal.
//	Phase 2 — stolen-credential login: a VALID login for a real victim, but from an
//	          anomalous origin (foreign IP + attacker-tool user-agent). The login
//	          succeeds; the trace carries the anomalous context to flag.
func runAttacker(every time.Duration) {
	log.Printf("[attack] attacker simulation ENABLED — breach burst every %s", every)
	ticker := time.NewTicker(every)
	defer ticker.Stop()

	// Anomalous origin markers forwarded by the gateway to auth-service.
	anomalousHeaders := map[string]string{
		"User-Agent":       "sqlmap/1.8#stable (http://sqlmap.org)",
		"X-Forwarded-For":  "203.0.113.66", // TEST-NET-3 — stands in for an unfamiliar external IP
		"X-OdiMall-Origin": "anomalous",
	}

	for {
		<-ticker.C

		// Phase 1: SQL-injection credential dump.
		dumpPayload := loginRequest{Username: "' OR '1'='1", Password: "x"}
		status, resp, err := postLogin(dumpPayload, anomalousHeaders)
		if err != nil {
			log.Printf("[attack] phase1 dump failed: %v", err)
			continue
		}
		if resp != nil && resp.RowsReturned > 1 {
			names := make([]string, 0, len(resp.Exfiltrated))
			for _, e := range resp.Exfiltrated {
				names = append(names, fmt.Sprintf("%s(%s)", e.Username, e.Role))
			}
			log.Printf("[attack] phase1 SQLi DUMP ok | HTTP %d | rows=%d exfiltrated=%v",
				status, resp.RowsReturned, names)
		} else {
			log.Printf("[attack] phase1 dump returned HTTP %d (no multi-row leak — secureQueries on?)", status)
		}

		// Phase 2: stolen-credential login as the real victim (alice).
		victim := loginRequest{Username: "alice", Password: "Password123!"}
		status2, _, err := postLogin(victim, anomalousHeaders)
		if err != nil {
			log.Printf("[attack] phase2 stolen-credential login failed: %v", err)
			continue
		}
		log.Printf("[attack] phase2 STOLEN-CREDENTIAL login user=alice from anomalous origin | HTTP %d", status2)
	}
}

// rapidRiverKayakProductID is the Rapid River Kayak SKU (Kafka notification fault demo).
const rapidRiverKayakProductID = 6

// uiManualDemoProductID matches order-service RetailFulfillmentGate.MANUAL_FULFILLMENT_HOLD_SKU — storefront only.
const uiManualDemoProductID = 11

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

func parseInt(s string, fallback int) int {
	n, err := strconv.Atoi(strings.TrimSpace(s))
	if err != nil {
		return fallback
	}
	return n
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

// Flat body for POST /users/shipping (same shape as the checkout UI).
type userShippingPayload struct {
	SessionID string `json:"sessionId"`
	Name      string `json:"name"`
	Address   string `json:"address"`
	City      string `json:"city"`
	State     string `json:"state"`
	Zip       string `json:"zip"`
}

type Product struct {
	ID       int     `json:"id"`
	Name     string  `json:"name"`
	Price    float64 `json:"price"`
	Category string  `json:"category"`
}

// excludedProductIDs parses EXCLUDE_PRODUCT_IDS (comma-separated, e.g. "3,5") — SKUs the load
// generator never browses or buys. Use it to silence other problem patterns during a focused demo.
func excludedProductIDs() map[int]bool {
	out := map[int]bool{uiManualDemoProductID: true}
	for _, raw := range strings.Split(getEnv("EXCLUDE_PRODUCT_IDS", ""), ",") {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		id, err := strconv.Atoi(raw)
		if err != nil {
			log.Printf("Ignoring invalid EXCLUDE_PRODUCT_IDS entry %q", raw)
			continue
		}
		out[id] = true
	}
	return out
}

func productsForAutomatedOrders(all []Product, excluded map[int]bool) []Product {
	out := make([]Product, 0, len(all))
	for _, p := range all {
		if !excluded[p.ID] {
			out = append(out, p)
		}
	}
	return out
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

func placeOrder(products []Product, kayakFault bool) error {
	var items []OrderItem

	if kayakFault {
		var kayak Product
		found := false
		for _, p := range products {
			if p.ID == rapidRiverKayakProductID {
				kayak = p
				found = true
				break
			}
		}
		if !found {
			return fmt.Errorf("Rapid River Kayak (product %d) not in catalog", rapidRiverKayakProductID)
		}
		items = []OrderItem{{
			ProductID: kayak.ID,
			Quantity:  1,
			Price:     kayak.Price,
			Name:      kayak.Name,
		}}
	} else {
		numItems := rand.Intn(3) + 1
		items = make([]OrderItem, 0, numItems)
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
	if kayakFault {
		order.SessionID = fmt.Sprintf("loadgen-kayak-fault-%d", rand.Int63())
	}

	// Match checkout UI: persist shipping via user-service before placing the order.
	// Without this call, user-service receives no traffic (load gen used to POST /orders only).
	shipPayload, _ := json.Marshal(userShippingPayload{
		SessionID: order.SessionID,
		Name:      order.Shipping.Name,
		Address:   order.Shipping.Address,
		City:      order.Shipping.City,
		State:     order.Shipping.State,
		Zip:       order.Shipping.Zip,
	})
	shipResp, shipErr := client.Post(gatewayURL+"/users/shipping", "application/json", bytes.NewReader(shipPayload))
	if shipErr != nil {
		log.Printf("POST /users/shipping failed: %v (continuing with order)", shipErr)
	} else {
		shipResp.Body.Close()
		if shipResp.StatusCode != 200 {
			log.Printf("POST /users/shipping → HTTP %d (continuing with order)", shipResp.StatusCode)
		}
	}

	body, _ := json.Marshal(order)
	req, err := http.NewRequest(http.MethodPost, gatewayURL+"/orders", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("POST /orders request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if kayakFault {
		req.Header.Set("X-OdiMall-Kayak-Chaos", "true")
	}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("POST /orders: %w", err)
	}
	defer resp.Body.Close()

	productNames := make([]string, len(items))
	for i, item := range items {
		productNames[i] = fmt.Sprintf("%s(x%d)", item.Name, item.Quantity)
	}

	if resp.StatusCode == 201 {
		if kayakFault {
			log.Printf("Kayak fault order placed: %v → %s, %s %s | HTTP %d",
				productNames, order.Shipping.Name, order.Shipping.City, order.Shipping.State, resp.StatusCode)
		} else {
			log.Printf("Order placed: %v → %s, %s %s | HTTP %d",
				productNames, order.Shipping.Name, order.Shipping.City, order.Shipping.State, resp.StatusCode)
		}
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
	client = newHTTPClient(gatewayURL)
	log.Printf("OdiMall Load Generator starting — interval=%s gateway=%s kayakFault=%v",
		interval, gatewayURL, kayakFaultEnabled())

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
	excluded := excludedProductIDs()
	automatedCatalog := productsForAutomatedOrders(products, excluded)
	if len(automatedCatalog) == 0 {
		log.Fatal("No products left for load generation after exclusions")
	}
	excludedList := make([]int, 0, len(excluded))
	for id := range excluded {
		excludedList = append(excludedList, id)
	}
	log.Printf("Loaded %d products (%d eligible for automated checkout, excluded=%v)",
		len(products), len(automatedCatalog), excludedList)

	// Attacker simulation runs on its own cadence so normal traffic is unaffected.
	if attackEnabled() {
		attackEvery := time.Duration(parseInt(getEnv("ATTACK_INTERVAL_SECONDS", "30"), 30)) * time.Second
		go runAttacker(attackEvery)
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	var orderSeq uint64

	for {
		// ~15% of ticks: a legitimate login (baseline "known good" auth traffic).
		if rand.Float64() < 0.15 {
			authBaseline()
		}

		// 70% chance: place an order, 30% chance: just browse
		if rand.Float64() < 0.7 {
			kayakOrder := false
			if kayakFaultEnabled() {
				orderSeq++
				kayakOrder = orderSeq%2 == 0
			}
			if err := placeOrder(automatedCatalog, kayakOrder); err != nil {
				log.Printf("Error placing order: %v", err)
			}
		} else {
			browseProducts(automatedCatalog)
		}
		<-ticker.C
	}
}
