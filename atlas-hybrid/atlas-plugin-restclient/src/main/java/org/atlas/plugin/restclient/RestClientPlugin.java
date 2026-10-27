package org.atlas.plugin.restclient;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttp;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.atlas.plugin.api.Plugin;
import org.atlas.plugin.api.PluginException;
import org.atlas.plugin.api.PluginRequest;
import org.atlas.plugin.api.PluginResult;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

/**
 * Connects to an external REST service and returns the response; nothing else (no transactions, no
 * transformation: those belong to the orchestrator and to transformer plugins).
 * <p>
 * Operation {@code call}, parameters:
 * <ul>
 * <li>{@code url} (required), {@code method} (default {@code GET}), {@code headers} and {@code query} (maps);</li>
 * <li>{@code body}: a map or list is sent as JSON, anything else as text;</li>
 * <li>{@code timeoutMs}: whole call timeout (default 10000);</li>
 * <li>{@code errorStatus}: responses with a status greater than or equal to it raise {@code HTTP_ERROR}
 * (default 500). Lower statuses are returned so flow rules can decide.</li>
 * </ul>
 * Result: {@code status}, {@code headers}, {@code body} (parsed when the response is JSON),
 * {@code durationMillis} and {@code client} (plugin and OkHttp versions, for diagnostics).
 * <p>
 * One {@link OkHttpClient}, with its connection pool, per component instance: the plugin is a
 * thread-safe singleton.
 */
@Component(service = Plugin.class, property = {
    Plugin.NAME_PROPERTY + "=rest-client",
    Plugin.DESCRIPTION_PROPERTY + "=Conecta con servicios REST externos y devuelve la respuesta (OkHttp)" })
public class RestClientPlugin implements Plugin {

    private static final Logger LOG = Logger.getLogger(RestClientPlugin.class.getName());
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final MediaType TEXT_TYPE = MediaType.get("text/plain; charset=utf-8");

    private OkHttpClient client;
    private Json json;
    private String pluginVersion;

    @Activate
    void activate(BundleContext context) {
        pluginVersion = context.getBundle().getVersion().toString();
        json = new Json();
        client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        LOG.info(() -> "Atlas plugin rest-client " + pluginVersion + " activated with OkHttp " + OkHttp.VERSION);
    }

    @Deactivate
    void deactivate() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        LOG.info(() -> "Atlas plugin rest-client " + pluginVersion + " deactivated");
    }

    @Override
    public PluginResult execute(PluginRequest request) {
        if (!"call".equals(request.operation())) {
            throw new IllegalArgumentException("rest-client supports only the 'call' operation, not '" + request.operation() + "'");
        }
        Request httpRequest = buildRequest(request);
        Call call = client.newCall(httpRequest);
        call.timeout().timeout(Long.parseLong(request.getString("timeoutMs", "10000")), TimeUnit.MILLISECONDS);
        int errorStatus = Integer.parseInt(request.getString("errorStatus", "500"));

        long start = System.nanoTime();
        try (Response response = call.execute()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", (long) response.code());
            data.put("headers", headers(response));
            data.put("body", body(response));
            data.put("durationMillis", (System.nanoTime() - start) / 1_000_000);
            data.put("client", Map.of("pluginVersion", pluginVersion, "okhttpVersion", OkHttp.VERSION));
            if (response.code() >= errorStatus) {
                throw new PluginException("HTTP_ERROR", httpRequest.method() + " " + httpRequest.url().encodedPath()
                    + " answered " + response.code());
            }
            return new PluginResult(data);
        } catch (InterruptedIOException e) {
            throw new PluginException("TIMEOUT", httpRequest.method() + " " + httpRequest.url().encodedPath() + " timed out", e);
        } catch (IOException e) {
            throw new PluginException("CONNECTION", httpRequest.method() + " " + httpRequest.url().encodedPath() + " failed: "
                + e.getMessage(), e);
        }
    }

    private Request buildRequest(PluginRequest request) {
        HttpUrl url = HttpUrl.parse(request.require("url"));
        if (url == null) {
            throw new IllegalArgumentException("Invalid url: " + request.require("url"));
        }
        HttpUrl.Builder urlBuilder = url.newBuilder();
        if (request.get("query") instanceof Map<?, ?> query) {
            query.forEach((name, value) -> urlBuilder.addQueryParameter(String.valueOf(name), String.valueOf(value)));
        }

        String method = request.getString("method", "GET").toUpperCase(Locale.ROOT);
        Object body = request.get("body");
        RequestBody requestBody = null;
        if (body instanceof Map<?, ?> || body instanceof java.util.List<?>) {
            requestBody = RequestBody.create(json.write(body), JSON_TYPE);
        } else if (body != null) {
            requestBody = RequestBody.create(body.toString(), TEXT_TYPE);
        } else if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            requestBody = RequestBody.create("", TEXT_TYPE);
        }

        Request.Builder builder = new Request.Builder().url(urlBuilder.build()).method(method, requestBody);
        if (request.get("headers") instanceof Map<?, ?> headers) {
            headers.forEach((name, value) -> builder.header(String.valueOf(name), String.valueOf(value)));
        }
        return builder.build();
    }

    private static Map<String, Object> headers(Response response) {
        Map<String, Object> headers = new LinkedHashMap<>();
        for (String name : response.headers().names()) {
            headers.put(name.toLowerCase(Locale.ROOT), response.header(name));
        }
        return headers;
    }

    private Object body(Response response) throws IOException {
        ResponseBody responseBody = response.body();
        String text = responseBody == null ? "" : responseBody.string();
        if (text.isEmpty()) {
            return null;
        }
        String contentType = response.header("Content-Type", "");
        return contentType.contains("json") ? json.parse(text) : text;
    }
}
