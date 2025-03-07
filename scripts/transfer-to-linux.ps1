param (
    [string]$Version = "1.0.0"  # Default version, override with -Version
)

Write-Host "Preparing NoJokePanel for Linux deployment with version $Version..." -ForegroundColor Green

# Ensure the correct Docker context
Write-Host "Setting Docker context to desktop-linux..."
docker context use desktop-linux
if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to set Docker context to desktop-linux!" -ForegroundColor Redocked
    exit $LASTEXITCODE
}

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

# Tag with version and latest
Write-Host "Tagging image as findzach/nojokepanel:$Version and findzach/nojokepanel:latest..."
docker tag nojokepanel:latest findzach/nojokepanel:$Version
docker tag nojokepanel:latest findzach/nojokepanel:latest

# Push both tags to Docker Hub
Write-Host "Pushing findzach/nojokepanel:$Version to Docker Hub..."
docker push findzach/nojokepanel:$Version
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker push failed for version $Version! Ensure you're logged in with 'docker login' using username 'findzach'." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "Pushing findzach/nojokepanel:latest to Docker Hub..."
docker push findzach/nojokepanel:latest
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker push failed for latest! Ensure you're logged in with 'docker login' using username 'findzach'." -ForegroundColor Red
    exit $LASTEXITCODE
}

# Rerun on the server via SSH with version (using correct master branch)
Write-Host "Rerunning NoJokePanel on the server with version $Version..."
ssh administrator@93.127.135.101 "export NOJOKEPANEL_VERSION=$Version && curl -sSL https://raw.githubusercontent.com/FindZach/NoJokePanel/refs/heads/master/scripts/install-nojokepanel.sh | bash"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Server rerun failed! Check SSH access, script URL, or GitHub access. Error: $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "Deployment and rerun completed successfully!" -ForegroundColor Green
Write-Host "Access at: https://gabwiki.com (ensure DNS is configured)"