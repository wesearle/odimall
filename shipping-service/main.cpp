#include <httplib.h>
#include <nlohmann/json.hpp>
#include <iostream>
#include <string>
#include <cstdlib>

using json = nlohmann::json;

json calculate_shipping(const json& body) {
    double base_rate = 5.99;
    double per_item_rate = 1.50;
    double heavy_surcharge = 15.0;
    double kayak_surcharge = 25.0;
    int kayak_product_id = 6;

    double cost = base_rate;
    bool has_heavy = false;
    bool has_kayak = false;

    if (body.contains("items") && body["items"].is_array()) {
        for (const auto& item : body["items"]) {
            int qty = item.value("quantity", 1);
            double price = item.value("price", 0.0);
            int product_id = item.value("productId", 0);

            cost += per_item_rate * qty;

            if (price > 200.0) has_heavy = true;
            if (product_id == kayak_product_id) has_kayak = true;
        }
    }

    if (has_heavy) cost += heavy_surcharge;
    if (has_kayak) cost += kayak_surcharge;

    cost = std::round(cost * 100.0) / 100.0;

    return {
        {"cost", cost},
        {"method", "Standard Ground"},
        {"estimatedDays", "5-7"},
        {"carrier", "OdiMall Logistics"}
    };
}

int main() {
    httplib::Server svr;

    svr.Post("/shipping/calculate", [](const httplib::Request& req, httplib::Response& res) {
        std::cout << "POST /shipping/calculate" << std::endl;
        try {
            auto body = json::parse(req.body);
            auto result = calculate_shipping(body);
            res.set_content(result.dump(), "application/json");
            std::cout << "  → shipping cost: $" << result["cost"] << std::endl;
        } catch (const std::exception& e) {
            std::cerr << "  → error: " << e.what() << std::endl;
            json err = {{"error", e.what()}};
            res.status = 400;
            res.set_content(err.dump(), "application/json");
        }
    });

    std::cout << "shipping-service listening on 0.0.0.0:8087" << std::endl;
    svr.listen("0.0.0.0", 8087);

    return 0;
}
