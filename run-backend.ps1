# Executa o Backend Spring Boot compilado com Java 21 LTS
$env:JAVA_HOME = "C:\Program Files\Java\jadk-21\jdk-21.0.11"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location -Path "$PSScriptRoot\backend"
Write-Host "Iniciando Meeting Intelligence Backend na porta 8085..." -ForegroundColor Cyan
java -jar target/meeting-intelligence-api-1.0.0-SNAPSHOT.jar

