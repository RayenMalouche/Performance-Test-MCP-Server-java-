package com.dicovery.performance.mcp.test.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PerformanceTestServer {

	// Test result classes
	public static class TestResult {
		public String testId;
		public String timestamp;
		public String query;
		public long responseTime;
		public Integer tokensUsed;
		public Double cost;
		public boolean success;
		public String errorMessage;
		public Integer datasetSize;
		public Integer mcpCallsCount;
		public List<String> urlsFetched = new ArrayList<>();

		public TestResult(String testId, String query) {
			this.testId = testId;
			this.query = query;
			this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		}
	}

	public static class LoadTestResult {
		public String testId;
		public String startTime;
		public String endTime;
		public int totalTests;
		public int successfulTests;
		public int failedTests;
		public double averageResponseTime;
		public int totalTokens;
		public double totalCost;
		public int concurrentUsers;
		public int requestsPerUser;
	}

	// In-memory storage for test results
	private static final Map<String, TestResult> testResults = new ConcurrentHashMap<>();
	private static final Map<String, LoadTestResult> loadTestResults = new ConcurrentHashMap<>();
	private static final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(30))
			.build();

	public static void main(String[] args) throws Exception {
		boolean useStdio = args.length > 0 && "--stdio".equals(args[0]);
		boolean useStreamableHttp = args.length > 0 && "--streamable-http".equals(args[0]);

		if (useStdio) {
			System.err.println("Starting Performance Test MCP server with STDIO transport...");
			startStdioServer();
		} else {
			System.out.println("Starting Performance Test MCP server with HTTP/SSE transport...");
			startHttpServer(useStreamableHttp);
		}
	}

	private static void startStdioServer() {
		try {
			System.err.println("Initializing STDIO Performance Test MCP server...");

			StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(new ObjectMapper());

			McpSyncServer syncServer = McpServer.sync(transportProvider)
					.serverInfo("performance-test-server", "1.0.0")
					.capabilities(McpSchema.ServerCapabilities.builder()
							.tools(true)
							.logging()
							.build())
					.tools(
							createPerformanceTestTool(),
							createLoadTestTool(),
							createAnalyzeResultsTool(),
							createMonitorCostsTool(),
							createBenchmarkScenariosTool(),
							createExportResultsTool()
					)
					.build();

			System.err.println("Performance Test MCP server started. Awaiting requests...");

		} catch (Exception e) {
			System.err.println("Fatal error in STDIO server: " + e.getMessage());
			e.printStackTrace(System.err);
			System.exit(1);
		}
	}

	private static void startHttpServer(boolean streamableHttp) throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();

		HttpServletSseServerTransportProvider transportProvider;
		if (streamableHttp) {
			transportProvider = new HttpServletSseServerTransportProvider(objectMapper, "/message", "/sse");
		} else {
			transportProvider = new HttpServletSseServerTransportProvider(objectMapper, "/", "/sse");
		}

		McpSyncServer syncServer = McpServer.sync(transportProvider)
				.serverInfo("performance-test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(true)
						.logging()
						.build())
				.tools(
						createPerformanceTestTool(),
						createLoadTestTool(),
						createAnalyzeResultsTool(),
						createMonitorCostsTool(),
						createBenchmarkScenariosTool(),
						createExportResultsTool()
				)
				.build();

		QueuedThreadPool threadPool = new QueuedThreadPool();
		threadPool.setName("performance-test-server");

		Server server = new Server(threadPool);
		ServerConnector connector = new ServerConnector(server);
		connector.setPort(45451);
		server.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		context.addServlet(new ServletHolder(transportProvider), "/*");
		context.addServlet(new ServletHolder(new HealthServlet()), "/api/health");

		server.setHandler(context);
		server.start();

		System.err.println("=================================");
		System.err.println("Performance Test MCP Server started on port 45451");
		if (streamableHttp) {
			System.err.println("Mode: Streamable HTTP (for MCP Inspector)");
			System.err.println("MCP endpoint: http://localhost:45451/message");
		} else {
			System.err.println("Mode: Standard HTTP/SSE");
			System.err.println("MCP endpoint: http://localhost:45451/");
		}
		System.err.println("SSE endpoint: http://localhost:45451/sse");
		System.err.println("Health check: http://localhost:45451/api/health");
		System.err.println("=================================");
		server.join();
	}

	private static McpServerFeatures.SyncToolSpecification createPerformanceTestTool() {
		return new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool(
						"run_performance_test",
						"Run a single performance test against the Discovery Intech MCP client",
						"""
                        {
                          "type": "object",
                          "properties": {
                            "query": {
                              "type": "string",
                              "description": "The query to test"
                            },
                            "targetUrl": {
                              "type": "string",
                              "description": "Target Discovery client URL (default: http://localhost:8072)"
                            },
                            "testId": {
                              "type": "string",
                              "description": "Unique test identifier"
                            }
                          },
                          "required": ["query"]
                        }
                        """
				),
				(exchange, params) -> {
					try {
						String query = (String) params.get("query");
						String targetUrl = (String) params.getOrDefault("targetUrl", "http://localhost:8072");
						String testId = (String) params.getOrDefault("testId", "test_" + System.currentTimeMillis());

						System.err.printf("Executing performance test: testId=%s, query=%s%n", testId, query);

						TestResult result = runPerformanceTest(query, targetUrl, testId);

						Map<String, Object> response = new HashMap<>();
						response.put("testId", result.testId);
						response.put("success", result.success);
						response.put("responseTime", result.responseTime);
						response.put("tokensUsed", result.tokensUsed);
						response.put("cost", result.cost);
						response.put("datasetSize", result.datasetSize);
						response.put("mcpCallsCount", result.mcpCallsCount);
						response.put("urlsFetched", result.urlsFetched);
						if (!result.success) {
							response.put("error", result.errorMessage);
						}

						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(new ObjectMapper().writeValueAsString(response))),
								false
						);
					} catch (Exception e) {
						System.err.println("ERROR in run_performance_test tool: " + e.getMessage());
						e.printStackTrace(System.err);
						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(
										String.format("{\"status\": \"error\", \"message\": \"%s\"}",
												escapeJsonString(e.getMessage()))
								)),
								true
						);
					}
				}
		);
	}

	private static McpServerFeatures.SyncToolSpecification createLoadTestTool() {
		return new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool(
						"run_load_test",
						"Run a load test with multiple concurrent requests",
						"""
                        {
                          "type": "object",
                          "properties": {
                            "queries": {
                              "type": "array",
                              "items": {"type": "string"},
                              "description": "Array of queries to test"
                            },
                            "concurrentUsers": {
                              "type": "number",
                              "description": "Number of concurrent users",
                              "default": 5
                            },
                            "requestsPerUser": {
                              "type": "number",
                              "description": "Number of requests per user",
                              "default": 2
                            },
                            "targetUrl": {
                              "type": "string",
                              "description": "Target Discovery client URL"
                            }
                          },
                          "required": ["queries"]
                        }
                        """
				),
				(exchange, params) -> {
					try {
						@SuppressWarnings("unchecked")
						List<String> queries = (List<String>) params.get("queries");
						int concurrentUsers = ((Number) params.getOrDefault("concurrentUsers", 5)).intValue();
						int requestsPerUser = ((Number) params.getOrDefault("requestsPerUser", 2)).intValue();
						String targetUrl = (String) params.getOrDefault("targetUrl", "http://localhost:8072");

						System.err.printf("Executing load test: users=%d, requests=%d%n", concurrentUsers, requestsPerUser);

						LoadTestResult result = runLoadTest(queries, concurrentUsers, requestsPerUser, targetUrl);

						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(new ObjectMapper().writeValueAsString(result))),
								false
						);
					} catch (Exception e) {
						System.err.println("ERROR in run_load_test tool: " + e.getMessage());
						e.printStackTrace(System.err);
						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(
										String.format("{\"status\": \"error\", \"message\": \"%s\"}",
												escapeJsonString(e.getMessage()))
								)),
								true
						);
					}
				}
		);
	}

	private static McpServerFeatures.SyncToolSpecification createAnalyzeResultsTool() {
		return new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool(
						"analyze_test_results",
						"Analyze and summarize test results",
						"""
                        {
                          "type": "object",
                          "properties": {
                            "testIds": {
                              "type": "array",
                              "items": {"type": "string"},
                              "description": "Specific test IDs to analyze (optional)"
                            },
                            "generateReport": {
                              "type": "boolean",
                              "description": "Generate a detailed report file",
                              "default": false
                            }
                          }
                        }
                        """
				),
				(exchange, params) -> {
					try {
						@SuppressWarnings("unchecked")
						List<String> testIds = (List<String>) params.get("testIds");
						boolean generateReport = Boolean.TRUE.equals(params.get("generateReport"));

						Map<String, Object> analysis = analyzeTestResults(testIds, generateReport);

						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(new ObjectMapper().writeValueAsString(analysis))),
								false
						);
					} catch (Exception e) {
						System.err.println("ERROR in analyze_test_results tool: " + e.getMessage());
						e.printStackTrace(System.err);
						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(
										String.format("{\"status\": \"error\", \"message\": \"%s\"}",
												escapeJsonString(e.getMessage()))
								)),
								true
						);
					}
				}
		);
	}

	private static McpServerFeatures.SyncToolSpecification createMonitorCostsTool() {
		return new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool(
						"monitor_costs",
						"Monitor and track API costs",
						"""
                        {
                          "type": "object",
                          "properties": {
                            "timeframe": {
                              "type": "string",
                              "description": "Timeframe for cost analysis (hour, day, week)",
                              "default": "day"
                            },
                            "modelName": {
                              "type": "string",
                              "description": "AI model name for cost calculation",
                              "default": "llama-3.1-8b-instant"
                            }
                          }
                        }
                        """
				),
				(exchange, params) -> {
					try {
						String timeframe = (String) params.getOrDefault("timeframe", "day");
						String modelName = (String) params.getOrDefault("modelName", "llama-3.1-8b-instant");

						Map<String, Object> costAnalysis = monitorCosts(timeframe, modelName);

						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(new ObjectMapper().writeValueAsString(costAnalysis))),
								false
						);
					} catch (Exception e) {
						System.err.println("ERROR in monitor_costs tool: " + e.getMessage());
						e.printStackTrace(System.err);
						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(
										String.format("{\"status\": \"error\", \"message\": \"%s\"}",
												escapeJsonString(e.getMessage()))
								)),
								true
						);
					}
				}
		);
	}

	private static McpServerFeatures.SyncToolSpecification createBenchmarkScenariosTool() {
		return new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool(
						"benchmark_scenarios",
						"Run predefined benchmark scenarios for Discovery Intech",
						"""
                        {
                          "type": "object",
                          "properties": {
                            "scenarios": {
                              "type": "array",
                              "items": {
                                "type": "string",
                                "enum": ["sage", "qad", "microsoft", "sap", "sectors", "services", "company", "all"]
                              },
                              "description": "Scenarios to benchmark",
                              "default": ["all"]
                            },
                            "targetUrl": {
                              "type": "string",
                              "description": "Target Discovery client URL"
                            }
                          }
                        }
                        """
				),
				(exchange, params) -> {
					try {
						@SuppressWarnings("unchecked")
						List<String> scenarios = (List<String>) params.getOrDefault("scenarios", List.of("all"));
						String targetUrl = (String) params.getOrDefault("targetUrl", "http://localhost:8072");

						Map<String, Object> benchmarkResult = benchmarkScenarios(scenarios, targetUrl);

						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(new ObjectMapper().writeValueAsString(benchmarkResult))),
								false
						);
					} catch (Exception e) {
						System.err.println("ERROR in benchmark_scenarios tool: " + e.getMessage());
						e.printStackTrace(System.err);
						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(
										String.format("{\"status\": \"error\", \"message\": \"%s\"}",
												escapeJsonString(e.getMessage()))
								)),
								true
						);
					}
				}
		);
	}

	private static McpServerFeatures.SyncToolSpecification createExportResultsTool() {
		return new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool(
						"export_results",
						"Export test results to various formats",
						"""
                        {
                          "type": "object",
                          "properties": {
                            "format": {
                              "type": "string",
                              "enum": ["json", "csv", "html"],
                              "description": "Export format",
                              "default": "json"
                            },
                            "filepath": {
                              "type": "string",
                              "description": "Output file path"
                            },
                            "includeDetails": {
                              "type": "boolean",
                              "description": "Include detailed test data",
                              "default": true
                            }
                          },
                          "required": ["filepath"]
                        }
                        """
				),
				(exchange, params) -> {
					try {
						String format = (String) params.getOrDefault("format", "json");
						String filepath = (String) params.get("filepath");
						boolean includeDetails = !Boolean.FALSE.equals(params.get("includeDetails"));

						String result = exportResults(format, filepath, includeDetails);

						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(result)),
								false
						);
					} catch (Exception e) {
						System.err.println("ERROR in export_results tool: " + e.getMessage());
						e.printStackTrace(System.err);
						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent(
										String.format("{\"status\": \"error\", \"message\": \"%s\"}",
												escapeJsonString(e.getMessage()))
								)),
								true
						);
					}
				}
		);
	}

	// Implementation methods

	private static TestResult runPerformanceTest(String query, String targetUrl, String testId) {
		TestResult testResult = new TestResult(testId, query);
		long startTime = System.currentTimeMillis();

		try {
			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("message", query);
			requestBody.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

			String jsonBody = new ObjectMapper().writeValueAsString(requestBody);

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(targetUrl + "/api/chat"))
					.header("Content-Type", "application/json")
					.timeout(Duration.ofMinutes(5))
					.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			long endTime = System.currentTimeMillis();
			testResult.responseTime = endTime - startTime;
			testResult.success = response.statusCode() >= 200 && response.statusCode() < 300;

			if (testResult.success) {
				analyzeResponseData(response.body(), testResult);
			} else {
				testResult.errorMessage = "HTTP " + response.statusCode() + ": " + response.body();
			}

		} catch (Exception e) {
			long endTime = System.currentTimeMillis();
			testResult.responseTime = endTime - startTime;
			testResult.success = false;
			testResult.errorMessage = e.getMessage();
		}

		testResults.put(testId, testResult);
		return testResult;
	}

	private static void analyzeResponseData(String responseData, TestResult testResult) {
		// Count dataset elements
		Pattern jsonPattern = Pattern.compile("\"Input\":\\s*\"");
		Matcher matcher = jsonPattern.matcher(responseData);
		int datasetCount = 0;
		while (matcher.find()) {
			datasetCount++;
		}
		testResult.datasetSize = datasetCount;

		// Count MCP calls
		String[] mcpPatterns = {"get_markdown", "get_raw_text", "get_rendered_html", "send-email"};
		int mcpCallsCount = 0;
		for (String pattern : mcpPatterns) {
			mcpCallsCount += responseData.split(Pattern.quote(pattern), -1).length - 1;
		}
		testResult.mcpCallsCount = mcpCallsCount;

		// Extract URLs
		Pattern urlPattern = Pattern.compile("https://www\\.discoveryintech\\.com[^\\s]*");
		Matcher urlMatcher = urlPattern.matcher(responseData);
		Set<String> uniqueUrls = new HashSet<>();
		while (urlMatcher.find()) {
			uniqueUrls.add(urlMatcher.group());
		}
		testResult.urlsFetched = new ArrayList<>(uniqueUrls);

		// Estimate tokens (rough estimation: ~4 chars per token)
		testResult.tokensUsed = (int) Math.ceil(responseData.length() / 4.0);

		// Calculate cost (Groq pricing for llama-3.1-8b-instant)
		double inputCostPer1K = 0.05; // cents
		double outputCostPer1K = 0.08; // cents
		double inputTokens = testResult.tokensUsed * 0.7; // 70% input
		double outputTokens = testResult.tokensUsed * 0.3; // 30% output

		testResult.cost = (inputTokens / 1000 * inputCostPer1K) + (outputTokens / 1000 * outputCostPer1K);
	}

	private static LoadTestResult runLoadTest(List<String> queries, int concurrentUsers, int requestsPerUser, String targetUrl) {
		String loadTestId = "load_test_" + System.currentTimeMillis();
		Instant startTime = Instant.now();

		ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
		List<CompletableFuture<TestResult>> futures = new ArrayList<>();

		// Create test tasks
		for (int user = 0; user < concurrentUsers; user++) {
			for (int req = 0; req < requestsPerUser; req++) {
				String query = queries.get(req % queries.size());
				String testId = String.format("%s_user%d_req%d", loadTestId, user, req);

				CompletableFuture<TestResult> future = CompletableFuture.supplyAsync(() ->
						runPerformanceTest(query, targetUrl, testId), executor);
				futures.add(future);
			}
		}

		// Wait for all tests to complete
		List<TestResult> results = futures.stream()
				.map(CompletableFuture::join)
				.collect(Collectors.toList());

		executor.shutdown();
		Instant endTime = Instant.now();

		// Process results
		List<TestResult> successfulTests = results.stream()
				.filter(r -> r.success)
				.collect(Collectors.toList());

		LoadTestResult loadTestResult = new LoadTestResult();
		loadTestResult.testId = loadTestId;
		loadTestResult.startTime = startTime.toString();
		loadTestResult.endTime = endTime.toString();
		loadTestResult.totalTests = results.size();
		loadTestResult.successfulTests = successfulTests.size();
		loadTestResult.failedTests = results.size() - successfulTests.size();
		loadTestResult.concurrentUsers = concurrentUsers;
		loadTestResult.requestsPerUser = requestsPerUser;

		if (!successfulTests.isEmpty()) {
			loadTestResult.averageResponseTime = successfulTests.stream()
					.mapToLong(r -> r.responseTime)
					.average()
					.orElse(0.0);

			loadTestResult.totalTokens = successfulTests.stream()
					.mapToInt(r -> r.tokensUsed != null ? r.tokensUsed : 0)
					.sum();

			loadTestResult.totalCost = successfulTests.stream()
					.mapToDouble(r -> r.cost != null ? r.cost : 0.0)
					.sum();
		}

		loadTestResults.put(loadTestId, loadTestResult);
		return loadTestResult;
	}

	private static Map<String, Object> analyzeTestResults(List<String> testIds, boolean generateReport) {
		Collection<TestResult> resultsToAnalyze;

		if (testIds != null && !testIds.isEmpty()) {
			resultsToAnalyze = testResults.values().stream()
					.filter(r -> testIds.contains(r.testId))
					.collect(Collectors.toList());
		} else {
			resultsToAnalyze = testResults.values();
		}

		if (resultsToAnalyze.isEmpty()) {
			Map<String, Object> response = new HashMap<>();
			response.put("message", "No test results found to analyze.");
			return response;
		}

		List<TestResult> successfulTests = resultsToAnalyze.stream()
				.filter(r -> r.success)
				.collect(Collectors.toList());

		Map<String, Object> analysis = new HashMap<>();
		analysis.put("totalTests", resultsToAnalyze.size());
		analysis.put("successfulTests", successfulTests.size());
		analysis.put("failedTests", resultsToAnalyze.size() - successfulTests.size());

		if (!successfulTests.isEmpty()) {
			analysis.put("averageResponseTime", calculateAverage(
					successfulTests.stream().mapToDouble(r -> r.responseTime).boxed().collect(Collectors.toList())
			));
			analysis.put("totalTokensUsed", successfulTests.stream().mapToInt(r -> r.tokensUsed != null ? r.tokensUsed : 0).sum());
			analysis.put("totalCost", successfulTests.stream().mapToDouble(r -> r.cost != null ? r.cost : 0.0).sum());
			analysis.put("averageDatasetSize", calculateAverage(
					successfulTests.stream().mapToDouble(r -> r.datasetSize != null ? r.datasetSize : 0).boxed().collect(Collectors.toList())
			));
			analysis.put("averageMcpCalls", calculateAverage(
					successfulTests.stream().mapToDouble(r -> r.mcpCallsCount != null ? r.mcpCallsCount : 0).boxed().collect(Collectors.toList())
			));

			Set<String> uniqueUrls = successfulTests.stream()
					.flatMap(r -> r.urlsFetched.stream())
					.collect(Collectors.toSet());
			analysis.put("uniqueUrlsFetched", uniqueUrls);

			List<Long> responseTimes = successfulTests.stream().map(r -> r.responseTime).collect(Collectors.toList());
			Map<String, Object> performanceMetrics = new HashMap<>();
			performanceMetrics.put("fastest", Collections.min(responseTimes));
			performanceMetrics.put("slowest", Collections.max(responseTimes));
			performanceMetrics.put("median", calculateMedian(responseTimes));
			analysis.put("performanceMetrics", performanceMetrics);
		}

		if (generateReport) {
			try {
				generateDetailedReport(analysis, new ArrayList<>(resultsToAnalyze));
			} catch (Exception e) {
				analysis.put("reportError", "Failed to generate report: " + e.getMessage());
			}
		}

		return analysis;
	}

	private static Map<String, Object> monitorCosts(String timeframe, String modelName) {
		long timeframeLimitMs;
		switch (timeframe.toLowerCase()) {
			case "hour":
				timeframeLimitMs = 60 * 60 * 1000L;
				break;
			case "week":
				timeframeLimitMs = 7 * 24 * 60 * 60 * 1000L;
				break;
			default: // day
				timeframeLimitMs = 24 * 60 * 60 * 1000L;
				break;
		}

		Instant cutoffTime = Instant.now().minusMillis(timeframeLimitMs);

		List<TestResult> recentTests = testResults.values().stream()
				.filter(r -> {
					try {
						return LocalDateTime.parse(r.timestamp).atZone(java.time.ZoneId.systemDefault())
								.toInstant().isAfter(cutoffTime);
					} catch (Exception e) {
						return false;
					}
				})
				.collect(Collectors.toList());

		Map<String, Object> costAnalysis = new HashMap<>();
		costAnalysis.put("timeframe", timeframe);
		costAnalysis.put("modelName", modelName);
		costAnalysis.put("periodStart", cutoffTime.toString());
		costAnalysis.put("periodEnd", Instant.now().toString());
		costAnalysis.put("totalTests", recentTests.size());

		int totalTokens = recentTests.stream().mapToInt(r -> r.tokensUsed != null ? r.tokensUsed : 0).sum();
		double totalCost = recentTests.stream().mapToDouble(r -> r.cost != null ? r.cost : 0.0).sum();

		costAnalysis.put("totalTokens", totalTokens);
		costAnalysis.put("totalCost", totalCost);

		if (!recentTests.isEmpty()) {
			double averageCostPerTest = totalCost / recentTests.size();
			costAnalysis.put("averageCostPerTest", averageCostPerTest);

			// Project monthly cost based on current usage pattern
			double testsPerHour = (double) recentTests.size() / (timeframeLimitMs / (60.0 * 60 * 1000));
			double projectedMonthlyTests = testsPerHour * 24 * 30;
			double projectedMonthlyCost = projectedMonthlyTests * averageCostPerTest;
			costAnalysis.put("projectedMonthlyCost", projectedMonthlyCost);
		} else {
			costAnalysis.put("averageCostPerTest", 0.0);
			costAnalysis.put("projectedMonthlyCost", 0.0);
		}

		return costAnalysis;
	}

	private static Map<String, Object> benchmarkScenarios(List<String> scenarios, String targetUrl) {
		Map<String, String> predefinedScenarios = new HashMap<>();
		predefinedScenarios.put("sage", "Créez un dataset complet sur toutes les solutions Sage de Discovery Intech");
		predefinedScenarios.put("qad", "Générez un dataset détaillé sur les solutions QAD proposées par Discovery Intech");
		predefinedScenarios.put("microsoft", "Produisez un dataset sur les solutions Microsoft Dynamics 365 de Discovery Intech");
		predefinedScenarios.put("sap", "Créez un dataset sur les solutions SAP proposées par Discovery Intech");
		predefinedScenarios.put("sectors", "Générez un dataset sur tous les secteurs d'activité couverts par Discovery Intech");
		predefinedScenarios.put("services", "Créez un dataset sur tous les services proposés par Discovery Intech");
		predefinedScenarios.put("company", "Produisez un dataset sur l'entreprise Discovery Intech (équipe, partenaires, références)");

		List<String> scenariosToRun;
		if (scenarios.contains("all")) {
			scenariosToRun = new ArrayList<>(predefinedScenarios.keySet());
		} else {
			scenariosToRun = scenarios.stream()
					.filter(predefinedScenarios::containsKey)
					.collect(Collectors.toList());
		}

		String benchmarkId = "benchmark_" + System.currentTimeMillis();
		List<Map<String, Object>> results = new ArrayList<>();

		for (String scenario : scenariosToRun) {
			String query = predefinedScenarios.get(scenario);
			String testId = benchmarkId + "_" + scenario;

			try {
				TestResult testResult = runPerformanceTest(query, targetUrl, testId);
				Map<String, Object> scenarioResult = new HashMap<>();
				scenarioResult.put("scenario", scenario);
				scenarioResult.put("testId", testResult.testId);
				scenarioResult.put("success", testResult.success);
				scenarioResult.put("responseTime", testResult.responseTime);
				scenarioResult.put("tokensUsed", testResult.tokensUsed);
				scenarioResult.put("cost", testResult.cost);
				scenarioResult.put("datasetSize", testResult.datasetSize);
				scenarioResult.put("mcpCallsCount", testResult.mcpCallsCount);
				if (!testResult.success) {
					scenarioResult.put("error", testResult.errorMessage);
				}
				results.add(scenarioResult);
			} catch (Exception e) {
				Map<String, Object> scenarioResult = new HashMap<>();
				scenarioResult.put("scenario", scenario);
				scenarioResult.put("testId", testId);
				scenarioResult.put("success", false);
				scenarioResult.put("error", e.getMessage());
				results.add(scenarioResult);
			}
		}

		List<Map<String, Object>> successfulResults = results.stream()
				.filter(r -> Boolean.TRUE.equals(r.get("success")))
				.collect(Collectors.toList());

		Map<String, Object> summary = new HashMap<>();
		if (!successfulResults.isEmpty()) {
			summary.put("averageResponseTime", successfulResults.stream()
					.mapToDouble(r -> ((Number) r.get("responseTime")).doubleValue())
					.average().orElse(0.0));
			summary.put("totalTokens", successfulResults.stream()
					.mapToInt(r -> r.get("tokensUsed") != null ? ((Number) r.get("tokensUsed")).intValue() : 0)
					.sum());
			summary.put("totalCost", successfulResults.stream()
					.mapToDouble(r -> r.get("cost") != null ? ((Number) r.get("cost")).doubleValue() : 0.0)
					.sum());
		}

		Map<String, Object> benchmarkResult = new HashMap<>();
		benchmarkResult.put("benchmarkId", benchmarkId);
		benchmarkResult.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		benchmarkResult.put("totalScenarios", results.size());
		benchmarkResult.put("successfulScenarios", successfulResults.size());
		benchmarkResult.put("results", results);
		benchmarkResult.put("summary", summary);

		return benchmarkResult;
	}

	private static String exportResults(String format, String filepath, boolean includeDetails) throws Exception {
		Map<String, Object> exportData = new HashMap<>();
		exportData.put("exportTimestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

		Map<String, Object> summary = new HashMap<>();
		summary.put("totalTests", testResults.size());
		summary.put("totalLoadTests", loadTestResults.size());
		summary.put("successfulTests", testResults.values().stream().mapToInt(r -> r.success ? 1 : 0).sum());
		exportData.put("summary", summary);

		if (includeDetails) {
			exportData.put("testResults", new ArrayList<>(testResults.values()));
			exportData.put("loadTestResults", new ArrayList<>(loadTestResults.values()));
		}

		String content;
		switch (format.toLowerCase()) {
			case "json":
				content = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(exportData);
				break;
			case "csv":
				content = convertToCSV(new ArrayList<>(testResults.values()));
				break;
			case "html":
				content = generateHTMLReport(exportData);
				break;
			default:
				throw new IllegalArgumentException("Unsupported format: " + format);
		}

		Files.write(Paths.get(filepath), content.getBytes(),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

		return String.format("Results exported successfully to %s", filepath);
	}

	// Utility methods

	private static double calculateAverage(List<Double> numbers) {
		if (numbers.isEmpty()) return 0.0;
		return numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
	}

	private static double calculateMedian(List<Long> numbers) {
		if (numbers.isEmpty()) return 0.0;
		List<Long> sorted = new ArrayList<>(numbers);
		Collections.sort(sorted);
		int size = sorted.size();
		if (size % 2 == 0) {
			return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
		} else {
			return sorted.get(size / 2);
		}
	}

	private static void generateDetailedReport(Map<String, Object> analysis, List<TestResult> results) throws Exception {
		String reportPath = "performance_report_" + System.currentTimeMillis() + ".html";
		Map<String, Object> reportData = new HashMap<>();
		reportData.put("analysis", analysis);
		reportData.put("results", results);
		String htmlContent = generateHTMLReport(reportData);
		Files.write(Paths.get(reportPath), htmlContent.getBytes(),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		System.err.println("Detailed report generated: " + reportPath);
	}

	private static String convertToCSV(List<TestResult> results) {
		StringBuilder csv = new StringBuilder();
		csv.append("testId,timestamp,query,responseTime,success,tokensUsed,cost,datasetSize,mcpCallsCount,errorMessage\n");

		for (TestResult result : results) {
			csv.append(String.format("\"%s\",\"%s\",\"%s\",%d,%b,%d,%.4f,%d,%d,\"%s\"\n",
					escapeCSV(result.testId),
					escapeCSV(result.timestamp),
					escapeCSV(result.query),
					result.responseTime,
					result.success,
					result.tokensUsed != null ? result.tokensUsed : 0,
					result.cost != null ? result.cost : 0.0,
					result.datasetSize != null ? result.datasetSize : 0,
					result.mcpCallsCount != null ? result.mcpCallsCount : 0,
					escapeCSV(result.errorMessage != null ? result.errorMessage : "")
			));
		}

		return csv.toString();
	}

	private static String generateHTMLReport(Map<String, Object> data) {
		StringBuilder html = new StringBuilder();
		html.append("""
<!DOCTYPE html>
<html>
<head>
    <title>Performance Test Report</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; }
        .summary { background: #f5f5f5; padding: 20px; border-radius: 8px; margin-bottom: 30px; }
        .metric { display: inline-block; margin: 10px 20px; }
        .metric-value { font-size: 24px; font-weight: bold; color: #2196F3; }
        .metric-label { font-size: 14px; color: #666; }
        table { width: 100%; border-collapse: collapse; margin-top: 20px; }
        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
        th { background-color: #f2f2f2; }
        .success { color: #4CAF50; }
        .failure { color: #f44336; }
    </style>
</head>
<body>
    <h1>Performance Test Report</h1>
    <div class="summary">
        <h2>Summary</h2>
""");

		@SuppressWarnings("unchecked")
		Map<String, Object> summary = (Map<String, Object>) data.get("summary");
		if (summary != null) {
			html.append(String.format("""
        <div class="metric">
            <div class="metric-value">%s</div>
            <div class="metric-label">Total Tests</div>
        </div>
        <div class="metric">
            <div class="metric-value">%s</div>
            <div class="metric-label">Successful Tests</div>
        </div>
""",
					summary.getOrDefault("totalTests", 0),
					summary.getOrDefault("successfulTests", 0)
			));
		}

		html.append("""
    </div>
    <p>Generated on: """).append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("""
</p>
</body>
</html>""");

		return html.toString();
	}

	private static String escapeJsonString(String input) {
		if (input == null) return "";
		return input.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	private static String escapeCSV(String input) {
		if (input == null) return "";
		return input.replace("\"", "\"\"");
	}

	// Health check servlet
	public static class HealthServlet extends HttpServlet {
		@Override
		protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
			sendHealthResponse(resp);
		}

		@Override
		protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
			sendHealthResponse(resp);
		}

		private void sendHealthResponse(HttpServletResponse resp) throws IOException {
			resp.setContentType("application/json");
			resp.setHeader("Access-Control-Allow-Origin", "*");
			resp.getWriter().write("{\"status\": \"healthy\", \"server\": \"Performance Test MCP Server\", \"version\": \"1.0.0\"}");
		}
	}
}