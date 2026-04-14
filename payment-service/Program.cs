using System.Text.Json;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls("http://0.0.0.0:8086");

var app = builder.Build();

app.Use(async (context, next) =>
{
    var start = DateTime.UtcNow;
    await next();
    var elapsed = (DateTime.UtcNow - start).TotalMilliseconds;
    Console.WriteLine($"{context.Request.Method} {context.Request.Path} → {context.Response.StatusCode} ({elapsed:F0}ms)");
});

app.MapPost("/payments/process", async (HttpContext ctx) =>
{
    using var reader = new StreamReader(ctx.Request.Body);
    var body = await reader.ReadToEndAsync();
    var doc = JsonDocument.Parse(body);
    var root = doc.RootElement;

    if (!root.TryGetProperty("orderId", out var orderIdEl) ||
        !root.TryGetProperty("amount", out var amountEl))
    {
        ctx.Response.StatusCode = 400;
        await ctx.Response.WriteAsJsonAsync(new { error = "orderId and amount are required" });
        return;
    }

    var orderId = orderIdEl.ToString();
    var amount = amountEl.GetDecimal();
    var currency = root.TryGetProperty("currency", out var currEl) ? currEl.GetString() ?? "USD" : "USD";

    var delay = Random.Shared.Next(200, 501);
    await Task.Delay(delay);

    await ctx.Response.WriteAsJsonAsync(new
    {
        transactionId = Guid.NewGuid().ToString(),
        status = "approved",
        orderId,
        amount,
        currency,
        processedAt = DateTime.UtcNow.ToString("o")
    });
});

app.Run();
