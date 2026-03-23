package jo.edu.yu.yu_chatbot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TestModels {
    // Paste your key here
    private static final String KEY = "AIzaSyD6fO-Wcz01Tlke71r2q6e1QQFottNC3bg";

    public static void main(String[] args) throws Exception {
        System.out.println("🔎 Checking available embedding models...\n");

        String url = "https://generativelanguage.googleapis.com/v1beta/models?key=" + KEY;

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            System.out.println("✅ API is ENABLED! Available models:\n");
            System.out.println(response.body());

            System.out.println("\n--- EMBEDDING MODELS ONLY ---");
            response.body().lines()
                    .filter(line -> line.contains("embedding") || line.contains("name"))
                    .forEach(line -> System.out.println(line.trim()));

        } else {
            System.out.println("❌ API ERROR (" + response.statusCode() + ")");
            System.out.println(response.body());
        }
    }
}