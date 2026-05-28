using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using OpenTelemetry.Exporter;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

// Hardcoded OTLP gRPC ingest for Grafana / LGTM (demo).
const string OtlpTracesEndpoint = "http://3.146.255.106:4317";
const string ServiceName = "windows-edge";

AppContext.SetSwitch("System.Net.Http.SocketsHttpHandler.Http2UnencryptedSupport", true);

var bindUrl = Environment.GetEnvironmentVariable("WINDOWS_EDGE_BIND_URL")
    ?? Environment.GetEnvironmentVariable("ASPNETCORE_URLS")
    ?? "http://0.0.0.0:9201";

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory
});
builder.Host.UseWindowsService();
builder.WebHost.UseUrls(bindUrl);

builder.Services.AddOpenTelemetry()
    .ConfigureResource(resource => resource
        .AddService(serviceName: ServiceName, serviceVersion: "1.0.0")
        .AddAttributes(new Dictionary<string, object>
        {
            ["deployment.environment"] = "demo",
            ["host.name"] = Environment.MachineName
        }))
    .WithTracing(tracing => tracing
        .AddSource(WindowsEdgeTelemetry.ActivitySourceName)
        .AddAspNetCoreInstrumentation(options =>
        {
            options.RecordException = true;
        })
        .AddOtlpExporter(options =>
        {
            options.Endpoint = new Uri(OtlpTracesEndpoint);
            options.Protocol = OtlpExportProtocol.Grpc;
        }));

var app = builder.Build();

app.Use(async (context, next) =>
{
    var start = DateTime.UtcNow;
    await next();
    var elapsed = (DateTime.UtcNow - start).TotalMilliseconds;
    Console.WriteLine($"{context.Request.Method} {context.Request.Path} → {context.Response.StatusCode} ({elapsed:F0}ms)");
});

app.MapGet("/run", (HttpContext ctx) =>
{
    using var activity = WindowsEdgeTelemetry.ActivitySource.StartActivity("WindowsEdgeRun");
    activity?.SetTag("odimall.windows.platform", RuntimeInformation.OSDescription);

    var traceHeaders = new List<string>();
    foreach (var name in new[] { "traceparent", "tracestate", "baggage" })
    {
        if (ctx.Request.Headers.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value))
        {
            traceHeaders.Add(name);
            activity?.SetTag($"http.request.header.{name}", value.ToString());
        }
    }

    var odimallHeaders = new Dictionary<string, string>();
    foreach (var name in new[] { "X-OdiMall-Request-Id", "X-OdiMall-Correlation-Id", "X-OdiMall-Timestamp" })
    {
        if (ctx.Request.Headers.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value))
        {
            odimallHeaders[name] = value.ToString();
            activity?.SetTag($"odimall.header.{name}", value.ToString());
        }
    }

    activity?.SetTag("odimall.windows.trace_header_count", traceHeaders.Count);

    var payload = new
    {
        service = ServiceName,
        language = "dotnet",
        port = ResolvePort(bindUrl),
        platform = RuntimeInformation.OSDescription,
        osArchitecture = RuntimeInformation.OSArchitecture.ToString(),
        processArchitecture = RuntimeInformation.ProcessArchitecture.ToString(),
        machineName = Environment.MachineName,
        dotnetVersion = Environment.Version.ToString(),
        message = "OdiMall Windows edge demo service reached from the storefront.",
        incomingTraceHeaders = traceHeaders,
        odimallHeaders,
        otlpTracesEndpoint = OtlpTracesEndpoint,
        utcTimestamp = DateTime.UtcNow.ToString("o")
    };

    return Results.Json(payload, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
});

Console.WriteLine($"OpenTelemetry traces → {OtlpTracesEndpoint} (service.name={ServiceName})");

app.Run();

static int ResolvePort(string urls)
{
    foreach (var segment in urls.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
    {
        if (!Uri.TryCreate(segment, UriKind.Absolute, out var uri))
        {
            continue;
        }
        if (uri.Port > 0)
        {
            return uri.Port;
        }
    }
    return 9201;
}

static class WindowsEdgeTelemetry
{
    public const string ActivitySourceName = "OdiMall.WindowsEdge";
    public static readonly ActivitySource ActivitySource = new(ActivitySourceName);
}
