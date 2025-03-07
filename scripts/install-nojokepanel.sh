#!/bin/bash
echo "Installing NoJokePanel..."

# Use specified version or default to latest
VERSION=${NOJOKEPANEL_VERSION:-latest}
echo "Using version: $VERSION"

# Check if Docker is installed
if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is not installed. Installing Docker..."
    curl -fsSL https://get.docker.com -o get-docker.sh
    sh get-docker.sh
    rm get-docker.sh
    sudo usermod -aG docker $USER
    echo "Docker installed. You may need to log out and back in for group changes to take effect."
fi

# Check if traefik-net network exists, create if not
if ! docker network ls | grep -q traefik-net; then
    echo "Creating traefik-net network..."
    docker network create traefik-net || { echo "Network creation failed!"; exit 1; }
else
    echo "traefik-net network already exists, skipping creation..."
fi

# Check if Traefik is running, start if not
if ! docker ps | grep -q traefik; then
    echo "Starting Traefik..."
    docker run -d --name traefik --network traefik-net -p 80:80 -p 443:443 -p 8080:8080 \
      -v /var/run/docker.sock:/var/run/docker.sock \
      -v /etc/traefik/acme.json:/acme.json \
      traefik:latest \
      --api.insecure=true \
      --providers.docker=true \
      --providers.docker.exposedByDefault=false \
      --entrypoints.web.address=:80 \
      --entrypoints.websecure.address=:443 \
      --entrypoints.web.http.redirections.entryPoint.to=websecure \
      --entrypoints.web.http.redirections.entryPoint.scheme=https \
      --certificatesresolvers.myresolver.acme.email=admin@sladercreek.com \
      --certificatesresolvers.myresolver.acme.storage=/acme.json \
      --certificatesresolvers.myresolver.acme.httpchallenge.entrypoint=web \
      --log.level=DEBUG || { echo "Traefik failed to start!"; exit 1; }
else
    echo "Traefik is already running, skipping start..."
fi

# Clean up only the spring-app container
echo "Cleaning up previous spring-app container..."
CID=$(docker stop spring-app 2>/dev/null || true)
docker rm $CID 2>/dev/null || true

# Pull and run NoJokePanel image with specified version
echo "Pulling and starting NoJokePanel version $VERSION..."
docker run -d --pull always --name spring-app --network traefik-net \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -l "traefik.enable=true" \
  -l "traefik.http.routers.spring-app.rule=Host(\`sladercreek.com\`) || Host(\`www.sladercreek.com\`)" \
  -l "traefik.http.routers.spring-app.entrypoints=websecure" \
  -l "traefik.http.routers.spring-app.tls.certresolver=myresolver" \
  -l "traefik.http.services.spring-app.loadbalancer.server.port=8080" \
  -l "traefik.http.routers.spring-app.middlewares=websocket-headers" \
  -l "traefik.http.middlewares.websocket-headers.headers.customrequestheaders.Upgrade=websocket" \
  -l "traefik.http.middlewares.websocket-headers.headers.customrequestheaders.Connection=upgrade" \
  findzach/nojokepanel:$VERSION || { echo "Spring app failed to start!"; exit 1; }

echo "NoJokePanel version $VERSION installed and started successfully!"
echo "Check logs with: docker logs traefik  and  docker logs spring-app"
echo "Access at: https://sladercreek.com (ensure DNS is configured)"