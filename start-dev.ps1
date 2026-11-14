# ==============================================================================
# SCRIPT DE INICIALIZAÇÃO DE DESENVOLVIMENTO - MEETING INTELLIGENCE
# ==============================================================================

Write-Host ">>> Iniciando containers da infraestrutura (PostgreSQL, Redis, RabbitMQ, MinIO)..." -ForegroundColor Cyan
docker compose up -d

Write-Host ">>> Configurando JAVA_HOME para Java 21 LTS..." -ForegroundColor Cyan
$env:JAVA_HOME = "C:\Program Files\Java\jadk-21\jdk-21.0.11"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host ">>> Containers ativos e validados:" -ForegroundColor Green
docker compose ps

Write-Host ">>> Ambiente pronto para inicializar Backend e Frontend!" -ForegroundColor Green
Write-Host "Para rodar o backend:  cd backend; mvn spring-boot:run"
Write-Host "Para rodar o frontend: cd frontend; npm run dev"
