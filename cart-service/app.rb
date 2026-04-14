require 'sinatra/base'
require 'sinatra/json'
require 'json'
require 'logger'

class CartApp < Sinatra::Base
  register Sinatra::JSON

  configure do
    set :port, 8083
    set :bind, '0.0.0.0'
    set :logger, Logger.new($stdout)
    set :show_exceptions, false
    set :host_authorization, permitted: :all
  end

  CARTS = {}

  before do
    content_type :json
    headers 'Access-Control-Allow-Origin' => '*',
            'Access-Control-Allow-Methods' => 'GET, POST, PUT, DELETE, OPTIONS',
            'Access-Control-Allow-Headers' => 'Content-Type'
  end

  options '*' do
    200
  end

  helpers do
    def cart_response(session_id)
      items = CARTS[session_id] || []
      total = items.sum { |i| i[:price].to_f * i[:quantity].to_i }
      { sessionId: session_id, items: items, total: total.round(2) }
    end

    def parse_body
      JSON.parse(request.body.read, symbolize_names: true)
    rescue JSON::ParserError
      halt 400, json(error: 'Invalid JSON')
    end
  end

  get '/cart/:session_id' do |session_id|
    settings.logger.info "GET cart for session #{session_id}"
    json cart_response(session_id)
  end

  post '/cart/:session_id/items' do |session_id|
    data = parse_body
    CARTS[session_id] ||= []

    existing = CARTS[session_id].find { |i| i[:productId].to_s == data[:productId].to_s }
    if existing
      existing[:quantity] = existing[:quantity].to_i + (data[:quantity] || 1).to_i
    else
      CARTS[session_id] << {
        productId: data[:productId],
        name:      data[:name],
        price:     data[:price].to_f,
        quantity:  (data[:quantity] || 1).to_i,
        imageUrl:  data[:imageUrl]
      }
    end

    settings.logger.info "Added product #{data[:productId]} to cart #{session_id}"
    json cart_response(session_id)
  end

  delete '/cart/:session_id/items/:product_id' do |session_id, product_id|
    if CARTS[session_id]
      CARTS[session_id].reject! { |i| i[:productId].to_s == product_id }
    end
    settings.logger.info "Removed product #{product_id} from cart #{session_id}"
    json cart_response(session_id)
  end

  put '/cart/:session_id/items/:product_id' do |session_id, product_id|
    data = parse_body
    item = (CARTS[session_id] || []).find { |i| i[:productId].to_s == product_id }
    if item
      item[:quantity] = data[:quantity].to_i
      settings.logger.info "Updated product #{product_id} quantity to #{data[:quantity]} in cart #{session_id}"
    else
      settings.logger.warn "Product #{product_id} not found in cart #{session_id}"
    end
    json cart_response(session_id)
  end

  delete '/cart/:session_id' do |session_id|
    CARTS.delete(session_id)
    settings.logger.info "Cleared cart #{session_id}"
    json cart_response(session_id)
  end

  run! if app_file == $PROGRAM_NAME
end
