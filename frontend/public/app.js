/* ===== State ===== */
const state = {
  products: [],
  cart: [],
  currentPage: 'catalog',
  selectedProduct: null,
  shipping: {}
};

const CHAOS_PRODUCT_IDS = new Set([3, 5]);

let PRODUCT_DATA = [];

async function fetchProducts() {
  try {
    const data = await api('/products');
    PRODUCT_DATA = data.map(p => ({
      id: p.id,
      name: p.name,
      price: p.price,
      category: p.category,
      image: p.imageUrl,
      description: p.description,
      chaos: CHAOS_PRODUCT_IDS.has(p.id)
    }));
  } catch (e) {
    console.warn('Could not load products from API, using fallback', e);
    PRODUCT_DATA = [
      { id: 1, name: 'Trail Blazer Hiking Boots', price: 129.99, category: 'Footwear', image: '/images/boots.svg', chaos: false, description: 'Rugged waterproof hiking boots with Vibram soles and ankle support.' },
      { id: 2, name: 'Summit Backpack 65L', price: 189.99, category: 'Packs', image: '/images/backpack.svg', chaos: false, description: 'Full-featured expedition backpack with adjustable torso length.' },
      { id: 3, name: 'Glacier Sleeping Bag', price: 149.99, category: 'Sleep', image: '/images/sleeping-bag.svg', chaos: true, description: 'Ultra-warm mummy-style sleeping bag rated to 15°F.' },
      { id: 4, name: 'Alpine Trekking Poles', price: 79.99, category: 'Accessories', image: '/images/poles.svg', chaos: false, description: 'Lightweight carbon fiber trekking poles with ergonomic cork grips.' },
      { id: 5, name: 'Storm Chaser Tent 4P', price: 299.99, category: 'Shelter', image: '/images/tent.svg', chaos: true, description: 'Four-person, three-season tent with full-coverage rainfly.' },
      { id: 6, name: 'Rapid River Kayak', price: 499.99, category: 'Water', image: '/images/kayak.svg', chaos: false, description: 'Inflatable touring kayak built for rivers and lakes.' },
      { id: 7, name: 'Peak Performance Jacket', price: 219.99, category: 'Apparel', image: '/images/jacket.svg', chaos: false, description: 'Three-layer waterproof hardshell jacket with fully taped seams.' },
      { id: 8, name: 'Wilderness First Aid Kit', price: 49.99, category: 'Safety', image: '/images/firstaid.svg', chaos: false, description: 'Comprehensive 120-piece backcountry first aid kit.' },
      { id: 9, name: 'Canyon Explorer Headlamp', price: 39.99, category: 'Lighting', image: '/images/headlamp.svg', chaos: false, description: '350-lumen rechargeable headlamp with red night-vision mode.' },
      { id: 10, name: 'Mountain Stream Water Filter', price: 34.99, category: 'Hydration', image: '/images/filter.svg', chaos: false, description: 'Portable hollow-fiber water filter that removes 99.99% of bacteria.' }
    ];
  }
}

/* ===== Session ===== */
function getSessionId() {
  let id = localStorage.getItem('odimall_session');
  if (!id) {
    id = crypto.randomUUID ? crypto.randomUUID() : generateUUID();
    localStorage.setItem('odimall_session', id);
  }
  return id;
}

function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

const sessionId = getSessionId();

/* ===== API Helpers ===== */
async function api(path, options = {}) {
  const url = `/api${path}`;
  const config = {
    headers: { 'Content-Type': 'application/json' },
    ...options
  };
  if (config.body && typeof config.body === 'object') {
    config.body = JSON.stringify(config.body);
  }
  const res = await fetch(url, config);
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: 'Request failed' }));
    throw new Error(err.error || `HTTP ${res.status}`);
  }
  return res.json();
}

/* ===== Toast ===== */
let toastTimer;
function showToast(message, type = '') {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.className = 'toast show' + (type ? ` ${type}` : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.className = 'toast', 2500);
}

/* ===== Loading ===== */
function setLoading(on) {
  document.getElementById('loadingOverlay').style.display = on ? 'flex' : 'none';
}

/* ===== Navigation ===== */
function showPage(pageId) {
  document.querySelectorAll('.page').forEach(p => p.style.display = 'none');
  const page = document.getElementById(pageId);
  page.style.display = '';
  page.style.animation = 'none';
  page.offsetHeight; // reflow
  page.style.animation = '';
  state.currentPage = pageId;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

/* ===== Catalog Page ===== */
function showCatalog() {
  showPage('pageCatalog');
  renderCatalog();
}

function renderCatalog(filter = 'all') {
  const grid = document.getElementById('productGrid');
  const products = filter === 'all'
    ? PRODUCT_DATA
    : PRODUCT_DATA.filter(p => p.category === filter);

  grid.innerHTML = products.map(p => `
    <div class="product-card" onclick="showProductDetail(${p.id})">
      <div class="product-card-img">
        ${p.chaos ? '<span class="chaos-badge">⚡ Demo Chaos</span>' : ''}
        <img src="${p.image}" alt="${p.name}">
      </div>
      <div class="product-card-body">
        <span class="product-category">${p.category}</span>
        <h3 class="product-card-title">${p.name}</h3>
        <div class="product-card-footer">
          <span class="product-price">$${p.price.toFixed(2)}</span>
          <button class="btn-add-cart" onclick="event.stopPropagation(); addToCart(${p.id})">Add to Cart</button>
        </div>
      </div>
    </div>
  `).join('');

  renderFilterButtons(filter);
}

function renderFilterButtons(active) {
  const categories = ['all', ...new Set(PRODUCT_DATA.map(p => p.category))];
  const container = document.querySelector('.catalog-filters');
  container.innerHTML = categories.map(c => `
    <button class="filter-btn ${c === active ? 'active' : ''}" onclick="filterProducts('${c}')">
      ${c === 'all' ? 'All' : c}
    </button>
  `).join('');
}

function filterProducts(category) {
  renderCatalog(category);
}

/* ===== Product Detail ===== */
function showProductDetail(id) {
  const product = PRODUCT_DATA.find(p => p.id === id);
  if (!product) return;
  state.selectedProduct = product;
  showPage('pageDetail');

  document.getElementById('productDetail').innerHTML = `
    <div class="detail-image">
      <img src="${product.image}" alt="${product.name}">
    </div>
    <div class="detail-info">
      <span class="detail-category">${product.category}</span>
      <h1>${product.name}</h1>
      ${product.chaos ? '<div class="detail-chaos">⚡ Demo Chaos — This item triggers simulated issues</div>' : ''}
      <div class="detail-price">$${product.price.toFixed(2)}</div>
      <p class="detail-description">${product.description}</p>
      <div class="quantity-selector">
        <label>Quantity</label>
        <div class="qty-controls">
          <button class="qty-btn" onclick="changeDetailQty(-1)">−</button>
          <input class="qty-value" id="detailQty" type="number" value="1" min="1" max="20" readonly>
          <button class="qty-btn" onclick="changeDetailQty(1)">+</button>
        </div>
      </div>
      <button class="btn btn-primary" onclick="addToCartFromDetail()">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/></svg>
        Add to Cart
      </button>
    </div>
  `;
}

function changeDetailQty(delta) {
  const input = document.getElementById('detailQty');
  const newVal = Math.max(1, Math.min(20, parseInt(input.value) + delta));
  input.value = newVal;
}

function addToCartFromDetail() {
  if (!state.selectedProduct) return;
  const qty = parseInt(document.getElementById('detailQty').value) || 1;
  addToCart(state.selectedProduct.id, qty);
}

/* ===== Cart Operations ===== */
async function addToCart(productId, quantity = 1) {
  const product = PRODUCT_DATA.find(p => p.id === productId);
  if (!product) return;

  try {
    await api(`/cart/${sessionId}/items`, {
      method: 'POST',
      body: { productId, name: product.name, price: product.price, quantity, imageUrl: product.image }
    });
  } catch (e) {
    // API unavailable — work with local cart
  }

  const existing = state.cart.find(i => i.productId === productId);
  if (existing) {
    existing.quantity += quantity;
  } else {
    state.cart.push({ productId, quantity });
  }

  updateCartBadge();
  showToast('Added to cart!', 'success');
}

async function removeFromCart(productId) {
  try {
    await api(`/cart/${sessionId}/items/${productId}`, { method: 'DELETE' });
  } catch (e) { /* local fallback */ }

  state.cart = state.cart.filter(i => i.productId !== productId);
  updateCartBadge();
  showCart();
}

async function updateCartItemQty(productId, delta) {
  const item = state.cart.find(i => i.productId === productId);
  if (!item) return;

  item.quantity += delta;
  if (item.quantity <= 0) {
    removeFromCart(productId);
    return;
  }

  try {
    await api(`/cart/${sessionId}/items`, {
      method: 'POST',
      body: { productId, quantity: delta }
    });
  } catch (e) { /* local fallback */ }

  updateCartBadge();
  showCart();
}

async function fetchCart() {
  try {
    const data = await api(`/cart/${sessionId}`);
    if (data && data.items) {
      state.cart = data.items;
    }
  } catch (e) { /* use local state */ }
}

function updateCartBadge() {
  const count = state.cart.reduce((sum, i) => sum + i.quantity, 0);
  const badge = document.getElementById('cartBadge');
  badge.textContent = count;
  badge.classList.remove('bump');
  void badge.offsetWidth;
  badge.classList.add('bump');
}

/* ===== Cart Page ===== */
function showCart() {
  showPage('pageCart');
  renderCart();
}

function renderCart() {
  const container = document.getElementById('cartContent');

  if (state.cart.length === 0) {
    container.innerHTML = `
      <div class="cart-empty">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/></svg>
        <h3>Your cart is empty</h3>
        <p>Discover amazing outdoor gear in our catalog</p>
        <button class="btn btn-primary" onclick="showCatalog()">Browse Products</button>
      </div>
    `;
    return;
  }

  const subtotal = state.cart.reduce((sum, item) => {
    const prod = PRODUCT_DATA.find(p => p.id === item.productId);
    return sum + (prod ? prod.price * item.quantity : 0);
  }, 0);

  const itemsHtml = state.cart.map(item => {
    const prod = PRODUCT_DATA.find(p => p.id === item.productId);
    if (!prod) return '';
    return `
      <div class="cart-item">
        <div class="cart-item-img"><img src="${prod.image}" alt="${prod.name}"></div>
        <div class="cart-item-info">
          <div class="cart-item-name">${prod.name}</div>
          <div class="cart-item-price">$${prod.price.toFixed(2)}</div>
        </div>
        <div class="cart-item-actions">
          <div class="cart-item-qty">
            <button class="qty-btn" onclick="updateCartItemQty(${prod.id}, -1)">−</button>
            <input class="qty-value" value="${item.quantity}" readonly>
            <button class="qty-btn" onclick="updateCartItemQty(${prod.id}, 1)">+</button>
          </div>
          <button class="cart-remove-btn" onclick="removeFromCart(${prod.id})" title="Remove">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
          </button>
        </div>
      </div>
    `;
  }).join('');

  container.innerHTML = `
    <div class="cart-layout">
      <div class="cart-items">${itemsHtml}</div>
      <div class="cart-summary">
        <h3>Order Summary</h3>
        <div class="summary-row"><span>Subtotal (${state.cart.reduce((s, i) => s + i.quantity, 0)} items)</span><span>$${subtotal.toFixed(2)}</span></div>
        <div class="summary-row"><span>Shipping</span><span>Free</span></div>
        <div class="summary-row total"><span>Total</span><span>$${subtotal.toFixed(2)}</span></div>
        <button class="btn btn-primary btn-lg" style="margin-top:16px" onclick="showCheckout()">Proceed to Checkout</button>
      </div>
    </div>
  `;
}

/* ===== Checkout Page ===== */
function showCheckout() {
  if (state.cart.length === 0) {
    showToast('Your cart is empty', 'error');
    return;
  }
  showPage('pageCheckout');
  renderCheckoutSummary();
}

function renderCheckoutSummary() {
  const container = document.getElementById('checkoutSummary');
  const subtotal = state.cart.reduce((sum, item) => {
    const prod = PRODUCT_DATA.find(p => p.id === item.productId);
    return sum + (prod ? prod.price * item.quantity : 0);
  }, 0);

  const itemsHtml = state.cart.map(item => {
    const prod = PRODUCT_DATA.find(p => p.id === item.productId);
    if (!prod) return '';
    return `
      <div class="checkout-item">
        <span class="item-name">${prod.name} × ${item.quantity}</span>
        <span class="item-total">$${(prod.price * item.quantity).toFixed(2)}</span>
      </div>
    `;
  }).join('');

  container.innerHTML = `
    <h3>Order Summary</h3>
    ${itemsHtml}
    <div class="summary-row total" style="margin-top:16px; padding-top:16px; border-top:2px solid var(--border);">
      <span>Total</span><span>$${subtotal.toFixed(2)}</span>
    </div>
  `;
}

/* ===== Place Order ===== */
async function placeOrder(e) {
  e.preventDefault();

  const shipping = {
    name: document.getElementById('shipName').value.trim(),
    address: document.getElementById('shipAddress').value.trim(),
    city: document.getElementById('shipCity').value.trim(),
    state: document.getElementById('shipState').value.trim(),
    zip: document.getElementById('shipZip').value.trim()
  };

  if (!shipping.name || !shipping.address || !shipping.city || !shipping.state || !shipping.zip) {
    showToast('Please fill in all shipping fields', 'error');
    return;
  }

  const items = state.cart.map(item => {
    const prod = PRODUCT_DATA.find(p => p.id === item.productId);
    return { productId: item.productId, quantity: item.quantity, price: prod ? prod.price : 0, name: prod ? prod.name : '' };
  });

  const btn = document.getElementById('placeOrderBtn');
  btn.disabled = true;
  btn.textContent = 'Processing...';
  setLoading(true);

  try {
    // Save shipping info
    try {
      await api('/users/shipping', {
        method: 'POST',
        body: { sessionId, ...shipping }
      });
    } catch (e) { /* continue if user service unavailable */ }

    // Place order
    const order = await api('/orders', {
      method: 'POST',
      body: { sessionId, items, shipping }
    });

    state.cart = [];
    updateCartBadge();
    showConfirmation(order, items, shipping);
  } catch (err) {
    showToast(err.message || 'Failed to place order. Please try again.', 'error');
  } finally {
    setLoading(false);
    btn.disabled = false;
    btn.textContent = 'Place Order';
  }
}

/* ===== Confirmation Page ===== */
function showConfirmation(order, items, shipping) {
  showPage('pageConfirmation');

  const total = items.reduce((s, i) => s + i.price * i.quantity, 0);

  const itemsHtml = items.map(item => {
    const prod = PRODUCT_DATA.find(p => p.id === item.productId);
    return `
      <div class="confirmation-item">
        <span>${prod ? prod.name : `Product #${item.productId}`} × ${item.quantity}</span>
        <span>$${(item.price * item.quantity).toFixed(2)}</span>
      </div>
    `;
  }).join('');

  document.getElementById('confirmationContent').innerHTML = `
    <div class="confirmation-icon">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
    </div>
    <h2>Order Confirmed!</h2>
    <p class="confirmation-order-id">Order ID: <code>${order.orderId || order.id || 'N/A'}</code></p>
    <div class="confirmation-details">
      <h4>Items Ordered</h4>
      ${itemsHtml}
      <div class="confirmation-total"><span>Total</span><span>$${total.toFixed(2)}</span></div>
    </div>
    <div class="confirmation-details">
      <h4>Shipping To</h4>
      <p>${shipping.name}<br>${shipping.address}<br>${shipping.city}, ${shipping.state} ${shipping.zip}</p>
    </div>
    <button class="btn btn-primary" onclick="showCatalog()">Continue Shopping</button>
  `;
}

/* ===== Init ===== */
async function init() {
  await fetchProducts();
  renderCatalog();
  await fetchCart();
  updateCartBadge();
}

init();
