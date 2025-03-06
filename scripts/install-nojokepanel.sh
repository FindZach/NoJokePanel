#!/bin/bash
echo "Installing NoJokePanel..."

# Check if Docker is installed
if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is not installed. Installing Docker..."
    curl -fsSL https://get.docker.com -o get-docker.sh
    sh get-docker.sh
    rm get-docker.sh
    # Add current user to docker group (non-root usage)
    sudo usermod -aG docker $USER
    echo "Docker installed. You may need to log out and back in for group changes to take effect."
fi

# Clean up previous runs
echo "Cleaning up previous containers and network..."
CID=$(docker stop spring-app traefik) || true
docker rm $CID || true
docker network rm traefik-net || true

# Create network
echo "Creating traefik-net network..."
docker network create traefik-net || { echo "Network creation failed!"; exit 1; }

# Run Traefik
echo "Starting Traefik..."
docker run -d --name traefik --network traefik-net -p 80:80 -p 443:443 -p 8080:8080 -v /var/run/docker.sock:/var/run/docker.sock traefik:latest --api.insecure=true --providers.docker=true --providers.docker.exposedByDefault=false --entrypoints.web.address=:80 --entrypoints.websecure.address=:443 --entrypoints.web.http.redirections.entryPoint.to=websecure --entrypoints.web.http.redirections.entryPoint.scheme=https --certificatesresolvers.myresolver.acme.email=admin@gabwiki.com --certificatesresolvers.myresolver.acme.storage=/acme.json --certificatesresolvers.myresolver.acme.httpchallenge.entrypoint=web || { echo "Traefik failed to start!"; exit 1; }

# Pull and run NoJokePanel image
echo "Pulling and starting NoJokePanel..."
docker run -d --name spring-app --network traefik-net -v /var/run/docker.sock:/var/run/docker.sock -l "traefik.enable=true" -l "traefik.http.routers.spring-app.rule=Host(\`gabwiki.com\`) || Host(\`www.gabwiki.com\`)" -l "traefik.http.routers.spring-app.entrypoints=websecure" -l "traefik.http.routers.spring-app.tls.certresolver=myresolver" -l "traefik.http.services.spring-app.loadbalancer.server.port=8080" findzach/nojokepanel:latest || { echo "Spring app failed to start!"; exit 1; }

echo "NoJokePanel installed and started successfully!"
echo "Check logs with: docker logs traefik  and  docker logs spring-app"
echo "Access at: https://gabwiki.com (ensure DNS is configured)"