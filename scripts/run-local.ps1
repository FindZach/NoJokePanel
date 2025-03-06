Write-Host "Starting local test environment on Windows..." -ForegroundColor Green

# Clean up previous runs
Write-Host "Cleaning up previous containers and network..."
docker stop spring-app traefik 2>$null
docker rm spring-app traefik 2>$null
docker network rm traefik-net 2>$null

# Build the Spring Boot app with clean
Write-Host "Building Spring Boot application with clean..."
mvn clean package
if ($LASTEXITCODE -ne 0) {
    Write-Host "Maven build failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Build Docker image
Write-Host "Building Docker image..."
docker build -t nojokepanel:latest .
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker build failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Create network
Write-Host "Creating traefik-net network..."
docker network create traefik-net
if ($LASTEXITCODE -ne 0) {
    Write-Host "Network creation failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Run Traefik
Write-Host "Starting Traefik..."
docker run -d --name traefik --network traefik-net -p 80:80 -p 443:443 -p 8080:8080 -v "//var/run/docker.sock:/var/run/docker.sock" traefik:latest --api.insecure=true --providers.docker=true --providers.docker.exposedByDefault=false --entrypoints.web.address=:80 --entrypoints.websecure.address=:443 --entrypoints.web.http.redirections.entryPoint.to=websecure --entrypoints.web.http.redirections.entryPoint.scheme=https --certificatesresolvers.myresolver.acme.email=admin@gabwiki.com --certificatesresolvers.myresolver.acme.storage=/acme.json --certificatesresolvers.myresolver.acme.httpchallenge.entrypoint=web
if ($LASTEXITCODE -ne 0) {
    Write-Host "Traefik failed to start!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Run Spring app
Write-Host "Starting Spring app..."
docker run -d --name spring-app --network traefik-net -v "//var/run/docker.sock:/var/run/docker.sock" -l "traefik.enable=true" -l "traefik.http.routers.spring-app.rule=Host(`localhost`)" -l "traefik.http.routers.spring-app.entrypoints=websecure" -l "traefik.http.routers.spring-app.tls.certresolver=myresolver" -l "traefik.http.services.spring-app.loadbalancer.server.port=8080" nojokepanel:latest
if ($LASTEXITCODE -ne 0) {
    Write-Host "Spring app failed to start!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "Local environment started successfully!" -ForegroundColor Green
Write-Host "Check logs with: docker logs traefik  and  docker logs spring-app"
Write-Host "Access at: https://localhost"