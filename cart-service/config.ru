require 'bundler/setup'

# Sinatra must be loaded before OTel installs instrumentation (see opentelemetry-instrumentation-sinatra).
require 'sinatra/base'

require 'opentelemetry/sdk'
require 'opentelemetry/exporter/otlp'
require 'opentelemetry/instrumentation/sinatra'

OpenTelemetry::SDK.configure do |c|
  c.service_name = ENV.fetch('OTEL_SERVICE_NAME', 'cart-service')
  c.use 'OpenTelemetry::Instrumentation::Sinatra'
end

require_relative 'app'
run CartApp
