# Performance Test MCP Server

Un serveur MCP (Model Context Protocol) développé en Java pour tester les performances du client Discovery Intech.

## Fonctionnalités

### Outils MCP Disponibles

1. **run_performance_test** - Exécute un test de performance unique
2. **run_load_test** - Exécute un test de charge avec plusieurs requêtes concurrent es
3. **analyze_test_results** - Analyse et résume les résultats des tests
4. **monitor_costs** - Surveille et suit les coûts de l'API
5. **benchmark_scenarios** - Exécute des scénarios de benchmark prédéfinis
6. **export_results** - Exporte les résultats vers différents formats

## Prérequis

- Java 23 ou supérieur
- Maven 3.8+
- Le client Discovery Intech en cours d'exécution (par défaut sur http://localhost:8072)

## Installation

1. Cloner le projet
2. Compiler avec Maven :
```bash
mvn clean compile
```

## Utilisation

### Mode STDIO (pour MCP clients)
```bash
java -cp target/classes com.dicovery.performance.mcp.test.server.PerformanceTestServer --stdio
```

### Mode HTTP/SSE (pour tests et inspection)
```bash
java -cp target/classes com.dicovery.performance.mcp.test.server.PerformanceTestServer
```

### Mode Streamable HTTP (pour MCP Inspector)
```bash
java -cp target/classes com.dicovery.performance.mcp.test.server.PerformanceTestServer --streamable-http
```

## Endpoints

Quand le serveur fonctionne en mode HTTP :

- **MCP endpoint** : http://localhost:45451/
- **SSE endpoint** : http://localhost:45451/sse
- **Health check** : http://localhost:45451/api/health

## Exemples d'utilisation des outils

### 1. Test de performance unique

```json
{
  "query": "Créez un dataset complet sur toutes les solutions Sage de Discovery Intech",
  "targetUrl": "http://localhost:8072",
  "testId": "test_sage_001"
}
```

### 2. Test de charge

```json
{
  "queries": [
    "Créez un dataset sur les solutions Sage",
    "Générez un dataset sur les solutions QAD",
    "Produisez un dataset sur Microsoft Dynamics 365"
  ],
  "concurrentUsers": 5,
  "requestsPerUser": 2,
  "targetUrl": "http://localhost:8072"
}
```

### 3. Scénarios de benchmark

```json
{
  "scenarios": ["sage", "qad", "microsoft", "sap"],
  "targetUrl": "http://localhost:8072"
}
```

Scénarios disponibles :
- `sage` : Solutions Sage
- `qad` : Solutions QAD
- `microsoft` : Microsoft Dynamics 365
- `sap` : Solutions SAP
- `sectors` : Secteurs d'activité
- `services` : Services proposés
- `company` : Information sur l'entreprise
- `all` : Tous les scénarios

### 4. Analyse des résultats

```json
{
  "testIds": ["test_sage_001", "test_qad_002"],
  "generateReport": true
}
```

### 5. Monitoring des coûts

```json
{
  "timeframe": "day",
  "modelName": "llama-3.1-8b-instant"
}
```

Options de timeframe : `hour`, `day`, `week`

### 6. Export des résultats

```json
{
  "format": "json",
  "filepath": "/tmp/test_results.json",
  "includeDetails": true
}
```

Formats disponibles : `json`, `csv`, `html`

## Métriques collectées

Pour chaque test, les métriques suivantes sont collectées :

- **Temps de réponse** (ms)
- **Nombre de tokens** utilisés (estimation)
- **Coût** estimé (basé sur le pricing Groq)
- **Taille du dataset** (nombre d'éléments)
- **Nombre d'appels MCP** effectués
- **URLs** récupérées
- **Status** de réussite/échec
- **Messages d'erreur** (si applicable)

## Structure des résultats

### TestResult
```json
{
  "testId": "test_001",
  "timestamp": "2025-01-XX:XX:XX",
  "query": "Question de test",
  "responseTime": 15000,
  "tokensUsed": 1500,
  "cost": 0.12,
  "success": true,
  "datasetSize": 25,
  "mcpCallsCount": 8,
  "urlsFetched": ["https://www.discoveryintech.com/..."],
  "errorMessage": null
}
```

### LoadTestResult
```json
{
  "testId": "load_test_123",
  "startTime": "2025-01-XX:XX:XX",
  "endTime": "2025-01-XX:XX:XX", 
  "totalTests": 10,
  "successfulTests": 9,
  "failedTests": 1,
  "averageResponseTime": 12500.5,
  "totalTokens": 15000,
  "totalCost": 1.25,
  "concurrentUsers": 5,
  "requestsPerUser": 2
}
```

## Configuration

Le serveur utilise les ports suivants par défaut :
- **HTTP/SSE** : 45451
- **Health check** : 45451/api/health

Pour modifier la configuration, vous pouvez :
1. Modifier les constantes dans le code source
2. Utiliser des variables d'environnement
3. Ajouter un fichier application.properties

## Logging

Les logs sont affichés sur stderr pour ne pas interférer avec le protocole MCP sur stdout.

## Développement

### Structure du projet
```
src/main/java/com/dicovery/performance/mcp/test/server/
├── PerformanceTestServer.java  # Classe principale
└── README.md                   # Documentation
```

### Compilation et exécution
```bash
mvn clean compile
mvn spring-boot:run -Dspring-boot.run.arguments="--stdio"
```

### Tests
```bash
mvn test
```

## Dépannage

### Erreurs communes

1. **Connexion refusée** : Vérifiez que le client Discovery Intech est en cours d'exécution
2. **Timeout** : Augmentez le timeout pour les requêtes longues
3. **Erreur de parsing JSON** : Vérifiez le format des requêtes

### Debug

Activez les logs détaillés en ajoutant :
```bash
-Dlogging.level.com.dicovery=DEBUG
```

## Contribution

1. Fork le projet
2. Créez une branche feature
3. Committez vos changements
4. Pushez vers la branche
5. Créez une Pull Request

