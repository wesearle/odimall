#!/usr/bin/env python3
"""
Mock AWS Instance Metadata Service (IMDSv2) for the OdiMall SSRF security demo.

Runs as a sidecar in the product-service pod, listening on 169.254.169.254:80
(the init container adds that address to `lo`). When the SSRF demo points
product-service at http://169.254.169.254/... the request never leaves the pod
and never touches the real cloud metadata service — this mock answers instead,
returning realistic-but-FAKE node IAM credentials.

This is the Capital One breach shape, reproduced safely: the demo shows the real
169.254.169.254 endpoint handing back temporary credentials, with zero change to
the actual EKS node's security posture. The credentials below are not real and
grant nothing.
"""
import datetime
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROLE_NAME = "odimall-eks-node-role"
TOKEN = "AQAEAFakeOdiMallDemoIMDSv2TokenDoNotTrust=="


def _now():
    return datetime.datetime.now(datetime.timezone.utc)


def _iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def fake_credentials():
    issued = _now()
    expires = issued + datetime.timedelta(hours=6)
    return {
        "Code": "Success",
        "LastUpdated": _iso(issued),
        "Type": "AWS-HMAC",
        # FAKE — recognizable as a demo value, grants nothing.
        "AccessKeyId": "ASIAW4ODIMALLDEMO0000",
        "SecretAccessKey": "wJalrXUtnFEMI/K7MDENG/ODIMALLdemoFAKEkey00",
        "Token": (
            "IQoJb3JpZ2luX2VjEComxODIMALLdemoFAKEsessiontoken"
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            "ThisIsNotARealSTSTokenItIsAnOdiMallSecurityDemo=="
        ),
        "Expiration": _iso(expires),
    }


INSTANCE_IDENTITY = {
    "accountId": "000000000000",
    "region": "us-east-2",
    "instanceId": "i-0odimalldemo000000",
    "instanceType": "m5.large",
    "imageId": "ami-0odimalldemo00000",
    "architecture": "x86_64",
    "pendingTime": _iso(_now()),
}


class Handler(BaseHTTPRequestHandler):
    server_version = "EC2ws"  # mimic the real IMDS server banner
    protocol_version = "HTTP/1.1"

    def _send(self, body, code=200, ctype="text/plain"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, indent=2)
            ctype = "application/json"
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Server", "EC2ws")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print("[mock-imds] %s - %s" % (self.address_string(), fmt % args), flush=True)

    # IMDSv2 token handshake.
    def do_PUT(self):
        if self.path == "/latest/api/token":
            self._send(TOKEN)
        else:
            self._send("Not Found", code=404)

    def do_GET(self):
        p = self.path.split("?", 1)[0].rstrip("/")
        if p == "/latest/meta-data":
            self._send("iam/\ninstance-id\nplacement/\nsecurity-groups\n")
        elif p == "/latest/meta-data/instance-id":
            self._send(INSTANCE_IDENTITY["instanceId"])
        elif p == "/latest/meta-data/iam":
            self._send("security-credentials/\ninfo\n")
        elif p == "/latest/meta-data/iam/security-credentials":
            # The role whose temporary credentials are attached to this node.
            self._send(ROLE_NAME)
        elif p == "/latest/meta-data/iam/security-credentials/" + ROLE_NAME:
            self._send(fake_credentials())
        elif p == "/latest/dynamic/instance-identity/document":
            self._send(INSTANCE_IDENTITY)
        elif p == "/latest/meta-data/placement/region":
            self._send(INSTANCE_IDENTITY["region"])
        else:
            self._send("Not Found", code=404)


def main():
    addr = ("0.0.0.0", 80)
    httpd = ThreadingHTTPServer(addr, Handler)
    print("[mock-imds] serving fake IMDSv2 on %s:%d (role=%s)" % (addr[0], addr[1], ROLE_NAME), flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
