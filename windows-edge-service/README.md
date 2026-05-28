# Windows edge demo (.NET on EC2)

Standalone **ASP.NET Core** service for the OdiMall storefront **Windows** navbar button. The cluster calls your Windows host over HTTP; nothing in this folder runs inside Kubernetes.

| Setting | Default |
|---------|---------|
| Port | **9201** |
| Bind | `http://0.0.0.0:9201` |
| Path | **`GET /run`** |

## Install on Windows EC2

1. Copy this folder to the instance (RDP, SSM, or `scp` via OpenSSH if enabled).
2. Open PowerShell **as Administrator** in the folder.
3. Run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\install-windows.ps1
```

The script publishes to `C:\opt\odimall-windows-edge`, opens firewall port **9201**, registers a Windows service **`OdiMallWindowsEdge`**, and smoke-tests `http://127.0.0.1:9201/run`.

## Wire into OdiMall

Point the API gateway at the Windows host base URL (no path):

**Helm**

```bash
helm upgrade --install odimall ./helm/odimall -n odimall \
  --set apiGateway.windowsPipelineBaseUrl="http://YOUR_WINDOWS_PUBLIC_IP:9201"
```

**Plain k8s** — uncomment/set in `k8s/api-gateway.yaml`:

```yaml
- name: WINDOWS_PIPELINE_BASE_URL
  value: "http://YOUR_WINDOWS_PUBLIC_IP:9201"
```

Then port-forward the frontend and click **Windows** in the navbar. The browser calls `GET /api/windows-pipeline/run` → gateway → `http://<Windows>:9201/run`.

## Response shape

```json
{
  "service": "windows-edge",
  "language": "dotnet",
  "port": 9201,
  "platform": "Microsoft Windows 10.0.20348",
  "machineName": "EC2AMAZ-...",
  "dotnetVersion": "8.0.x",
  "message": "OdiMall Windows edge demo service reached from the storefront.",
  "incomingTraceHeaders": ["traceparent"],
  "odimallHeaders": { "X-OdiMall-Request-Id": "..." }
}
```

Trace headers (`traceparent`, `tracestate`, `baggage`) and OdiMall gateway headers are echoed for Odigos demos.

## OpenTelemetry (manual instrumentation)

Traces are exported over **OTLP gRPC** to a hardcoded endpoint in `Program.cs`:

```text
http://3.146.255.106:4317
```

- **Service name:** `windows-edge`
- **ASP.NET Core** HTTP spans (incoming `/run`)
- **Custom span:** `WindowsEdgeRun` from `ActivitySource` `OdiMall.WindowsEdge`

After redeploying the Windows service, click the storefront **Windows** button and search Tempo for `resource.service.name = "windows-edge"`.

## Environment

| Variable | Purpose |
|----------|---------|
| `WINDOWS_EDGE_BIND_URL` | Kestrel bind URL (default `http://0.0.0.0:9201`) |

## Manual run (dev)

```powershell
$env:WINDOWS_EDGE_BIND_URL = "http://0.0.0.0:9201"
dotnet run
curl http://127.0.0.1:9201/run
```

## Security note

This demo listens on all interfaces. Restrict the EC2 security group to your cluster egress / demo IP only.
