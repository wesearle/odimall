#!/usr/bin/env bash
# Build and install C++ alpha + Java beta/gamma + PostgreSQL demo on Amazon Linux 2023 (x86_64 / aarch64).
# Run on the EC2 instance with sudo for install paths. Requires network for CMake FetchContent (cpp-httplib).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${DEST:-/opt/odimall-vm-edge}"
CONF_DIR="${CONF_DIR:-/etc/odimall-vm}"

echo "Installing build dependencies (dnf)..."
sudo dnf install -y cmake gcc-c++ git java-17-amazon-corretto-devel maven postgresql15-server postgresql15

echo "Initializing PostgreSQL + demo schema..."
"${ROOT}/setup-postgres-amzn2023.sh"

echo "Building C++ alpha (vm_alpha)..."
cmake -S "${ROOT}/cpp-alpha" -B "${ROOT}/cpp-alpha/build" -DCMAKE_BUILD_TYPE=Release
cmake --build "${ROOT}/cpp-alpha/build" -j"$(nproc)"

echo "Building Java services..."
mvn -f "${ROOT}/java/pom.xml" -q package -DskipTests

echo "Installing to ${DEST} ..."
sudo mkdir -p "${DEST}/bin" "${DEST}/jars" "${CONF_DIR}"
sudo install -m0755 "${ROOT}/cpp-alpha/build/vm_alpha" "${DEST}/bin/vm_alpha"
sudo install -m0644 "${ROOT}/java/beta-server/target/beta-server.jar" "${DEST}/jars/beta-server.jar"
sudo install -m0644 "${ROOT}/java/gamma-server/target/gamma-server.jar" "${DEST}/jars/gamma-server.jar"

sudo tee "${CONF_DIR}/gamma.env" >/dev/null <<EOF
JDBC_URL=jdbc:postgresql://127.0.0.1:5432/odimall_vm?user=odimall_vm&password=${POSTGRES_ODIMALL_VM_PASSWORD:-odimall_vm_demo}
EOF
sudo chmod 0600 "${CONF_DIR}/gamma.env"

sudo tee "${CONF_DIR}/beta.env" >/dev/null <<EOF
GAMMA_BASE_URL=http://127.0.0.1:9103
EOF
sudo chmod 0644 "${CONF_DIR}/beta.env"

for unit in odimall-vm-gamma.service odimall-vm-beta.service odimall-vm-alpha.service; do
  sudo cp "${ROOT}/systemd/${unit}" /etc/systemd/system/
done

sudo systemctl daemon-reload
sudo systemctl enable --now odimall-vm-gamma.service
sudo systemctl enable --now odimall-vm-beta.service
sudo systemctl enable --now odimall-vm-alpha.service

echo "Done. Public chain: curl -sS http://\$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4):9101/chain"
echo "Open EC2 security group TCP 9101 to your client IP."
