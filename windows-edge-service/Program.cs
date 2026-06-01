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

var hostAttributes = WindowsHostAttributes.Collect();

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
        .AddAttributes(hostAttributes.ToResourceDictionary()))
    .WithTracing(tracing => tracing
        .AddSource(WindowsEdgeTelemetry.ActivitySourceName)
        .AddAspNetCoreInstrumentation(options =>
        {
            options.RecordException = true;
            options.EnrichWithHttpRequest = (activity, request) =>
            {
                WindowsHostAttributes.ApplySemanticTags(activity, hostAttributes);
            };
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
    WindowsHostAttributes.ApplySemanticTags(activity, hostAttributes);

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
        platform = hostAttributes.OsDescription,
        osType = hostAttributes.OsType,
        osVersion = hostAttributes.OsVersion,
        hostArch = hostAttributes.HostArch,
        hostType = hostAttributes.HostType,
        osArchitecture = RuntimeInformation.OSArchitecture.ToString(),
        processArchitecture = RuntimeInformation.ProcessArchitecture.ToString(),
        machineName = hostAttributes.HostName,
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

sealed class WindowsHostAttributes
{
    public string OsType { get; init; } = "windows";
    public string OsDescription { get; init; } = RuntimeInformation.OSDescription;
    public string OsVersion { get; init; } = Environment.OSVersion.Version.ToString();
    public string HostName { get; init; } = Environment.MachineName;
    public string HostArch { get; init; } = MapHostArch(RuntimeInformation.ProcessArchitecture);
    public string HostType { get; init; } = "cloud";
    public string CloudProvider { get; init; } = "aws";
    public string CloudPlatform { get; init; } = "aws_ec2";
    public string DeploymentEnvironment { get; init; } = "demo";

    public static WindowsHostAttributes Collect() => new();

    public Dictionary<string, object> ToResourceDictionary() => new()
    {
        ["deployment.environment"] = DeploymentEnvironment,
        ["os.type"] = OsType,
        ["os.description"] = OsDescription,
        ["os.version"] = OsVersion,
        ["host.name"] = HostName,
        ["host.arch"] = HostArch,
        ["host.type"] = HostType,
        ["cloud.provider"] = CloudProvider,
        ["cloud.platform"] = CloudPlatform,
        ["process.runtime.name"] = "dotnet",
        ["process.runtime.version"] = Environment.Version.ToString(),
    };

    public static void ApplySemanticTags(Activity? activity, WindowsHostAttributes attrs)
    {
        if (activity is null)
        {
            return;
        }

        activity.SetTag("os.type", attrs.OsType);
        activity.SetTag("os.description", attrs.OsDescription);
        activity.SetTag("os.version", attrs.OsVersion);
        activity.SetTag("host.name", attrs.HostName);
        activity.SetTag("host.arch", attrs.HostArch);
        activity.SetTag("host.type", attrs.HostType);
        activity.SetTag("cloud.provider", attrs.CloudProvider);
        activity.SetTag("cloud.platform", attrs.CloudPlatform);
    }

    private static string MapHostArch(Architecture architecture) => architecture switch
    {
        Architecture.X64 => "amd64",
        Architecture.Arm64 => "arm64",
        Architecture.X86 => "x86",
        Architecture.Arm => "arm32",
        _ => architecture.ToString().ToLowerInvariant()
    };
}
