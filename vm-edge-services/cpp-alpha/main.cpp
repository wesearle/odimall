#include <httplib.h>

#include <cstdlib>
#include <iostream>
#include <string>

namespace {

std::string env_or(const char *key, const char *fallback) {
  const char *v = std::getenv(key);
  return (v && *v) ? std::string(v) : std::string(fallback);
}

void copy_trace_headers(const httplib::Request &req, httplib::Headers &out) {
  static const char *names[] = {"traceparent", "tracestate", "baggage"};
  for (const char *n : names) {
    if (req.has_header(n)) {
      out.emplace(n, req.get_header_value(n));
    }
  }
}

} // namespace

int main() {
  const std::string beta_host = env_or("ALPHA_BETA_HOST", "127.0.0.1");
  const int beta_port = std::atoi(env_or("ALPHA_BETA_PORT", "9102").c_str());
  const std::string bind_host = env_or("ALPHA_BIND_HOST", "0.0.0.0");
  const int bind_port = std::atoi(env_or("ALPHA_PORT", "9101").c_str());

  httplib::Server svr;

  svr.Get("/chain", [&](const httplib::Request &req, httplib::Response &res) {
    httplib::Client cli(beta_host, beta_port);
    cli.set_connection_timeout(5, 0);
    cli.set_read_timeout(30, 0);

    httplib::Headers fwd;
    copy_trace_headers(req, fwd);

    auto upstream = cli.Get("/chain", fwd);
    if (!upstream) {
      res.status = 502;
      res.set_content("{\"error\":\"beta_unreachable\"}", "application/json");
      return;
    }

    if (upstream->status != 200) {
      res.status = upstream->status;
      res.set_content(upstream->body, upstream->get_header_value("Content-Type"));
      return;
    }

    const std::string body = upstream->body;
    std::string json;
    json.reserve(body.size() + 160);
    json.append("{\"service\":\"alpha\",\"language\":\"cpp\",\"port\":9101,\"message\":\"Entry hop; forwarded to Java beta.\",");
    json.append("\"downstream\":");
    json.append(body);
    json.push_back('}');

    res.status = 200;
    res.set_content(json, "application/json; charset=utf-8");
  });

  std::cerr << "vm_alpha (C++) listening on http://" << bind_host << ":" << bind_port << "/chain\n";
  if (!svr.listen(bind_host.c_str(), bind_port)) {
    std::cerr << "listen failed\n";
    return 1;
  }
  return 0;
}
