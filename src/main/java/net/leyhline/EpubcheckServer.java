package net.leyhline;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import org.w3c.epubcheck.core.Checker;

import com.adobe.epubcheck.api.EpubCheck;
import com.adobe.epubcheck.api.LocalizableReport;
import com.adobe.epubcheck.reporting.CheckingReport;
import com.adobe.epubcheck.util.ReportingLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

@SuppressWarnings("restriction")
public final class EpubcheckServer {
    private static String hostname = "localhost";
    private static int port = 8003;
    private static int threads = 4;
    private static final String helpMessage = "Usage: java -jar epubcheck-server.jar [options]\n" +
            "Options:\n" +
            "  -h, --hostname <hostname>  Hostname to bind to (default: localhost)\n" +
            "  -p, --port <port>          Port to bind to (default: 8003)\n" +
            "  -t, --threads <threads>    Number of threads to use (default: 4)\n";

    private EpubcheckServer() {
    }

    public static void main(String[] args) throws IOException {
        parseArgs(args);
        startServer();
    }

    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-H":
                case "--hostname":
                    if (i + 1 >= args.length)
                        printMissingArgumentMessage(args[i]);
                    hostname = args[++i];
                    break;
                case "-p":
                case "--port":
                    if (i + 1 >= args.length)
                        printMissingArgumentMessage(args[i]);
                    port = Integer.parseInt(args[++i]);
                    break;
                case "-t":
                case "--threads":
                    if (i + 1 >= args.length)
                        printMissingArgumentMessage(args[i]);
                    threads = Integer.parseInt(args[++i]);
                    break;
                case "-h":
                case "--help":
                    System.out.println(helpMessage);
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    System.err.println(helpMessage);
                    System.exit(1);
            }
        }
    }

    private static void printMissingArgumentMessage(String option) {
        System.err.println("Missing argument for option: " + option);
        System.err.println(helpMessage);
        System.exit(1);
    }

    private static void startServer() throws IOException {
        System.out.println("Starting server on " + hostname + ":" + port);
        HttpServer server = HttpServer.create(new InetSocketAddress(hostname, port), 0);
        server.createContext("/", new EpubCheckHandler());
        ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
        server.setExecutor(threadPoolExecutor);
        server.start();
    }

    static class EpubCheckHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestMethod = exchange.getRequestMethod();
            if (!requestMethod.equalsIgnoreCase("POST")) {
                System.out.println("Request " + requestMethod + " - Response 405 Method Not Allowed");
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String response = "";
            int statusCode = 400;

            String path;
            try (InputStream is = exchange.getRequestBody()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    path = br.lines().collect(Collectors.joining("\n"));
                }
            }

            File file = new File(path);
            if (file.exists()) {
                System.out.println("Request " + requestMethod + " (" + path + ") - Response 200 OK");
                response = checkFile(file);
                statusCode = 200;
            } else {
                System.out.println("Request " + requestMethod + " (" + path + ") - Response 400 Bad Request");
                response = buildErrorMessage(path);
                statusCode = 400;
            }
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }

        private String checkFile(File file) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            LocalizableReport report = new CheckingReport(pw, file.getName());
            report.setLocale(Locale.ENGLISH);
            report.setReportingLevel(ReportingLevel.Info);
            report.initialize();
            Checker checker = new EpubCheck(file, report);
            checker.check();
            report.generate();
            return sw.toString();
        }

        private String buildErrorMessage(String path) {
            String message = "File not found: " + path;
            message = message.replace("\\", "\\\\").replace("\"", "\\\"");
            return "{ \"message\" : \"" + message + "\" }";
        }
    }
}
