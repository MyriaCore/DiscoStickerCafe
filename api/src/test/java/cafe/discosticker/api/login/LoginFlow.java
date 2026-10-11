package cafe.discosticker.api.login;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A browser stand-in for the login flow against the real Tomcat: redirects are never followed,
 * and cookies are kept in an explicit jar so tests can see every {@code Set-Cookie} and replay
 * every cookie received.
 */
public final class LoginFlow {

	/** The authorization redirect: the response and the decoded query of its Location. */
	public record Authorization(HttpResponse<String> response, Map<String, String> params) {
	}

	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private final String baseUrl;

	private final FakeDiscord discord;

	private final Map<String, String> jar = new LinkedHashMap<>();

	private final List<String> received = new ArrayList<>();

	public LoginFlow(int port, FakeDiscord discord) {
		this.baseUrl = "http://localhost:" + port;
		this.discord = discord;
	}

	public HttpResponse<String> get(String path, String... headers) {
		return send("GET", path, headers);
	}

	public HttpResponse<String> post(String path, String... headers) {
		return send("POST", path, headers);
	}

	public HttpResponse<String> post(String path, HttpRequest.BodyPublisher body, String... headers) {
		return send("POST", path, body, headers);
	}

	public HttpResponse<String> head(String path, String... headers) {
		return send("HEAD", path, headers);
	}

	/** A GET whose body is kept as bytes, for binary responses. */
	public HttpResponse<byte[]> getBytes(String path, String... headers) {
		return send("GET", path, HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.ofByteArray(), headers);
	}

	/** One part of a {@code multipart/form-data} body: a file when {@code filename} is set, else a field. */
	public record FormPart(String name, String filename, String contentType, byte[] content) {

		public static FormPart file(String name, String filename, String contentType, byte[] content) {
			return new FormPart(name, filename, contentType, content);
		}

		public static FormPart field(String name, String value) {
			return new FormPart(name, null, null, value.getBytes(StandardCharsets.UTF_8));
		}

	}

	/** A {@code multipart/form-data} POST, built by hand so tests control every part. */
	public HttpResponse<String> postMultipart(String path, List<FormPart> parts, String... headers) {
		String boundary = "----test" + Long.toHexString(System.nanoTime());
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		for (FormPart part : parts) {
			StringBuilder head = new StringBuilder("--").append(boundary).append("\r\n");
			head.append("Content-Disposition: form-data; name=\"").append(part.name()).append('"');
			if (part.filename() != null) {
				head.append("; filename=\"").append(part.filename()).append('"');
			}
			head.append("\r\n");
			if (part.contentType() != null) {
				head.append("Content-Type: ").append(part.contentType()).append("\r\n");
			}
			head.append("\r\n");
			body.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
			body.writeBytes(part.content());
			body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		String[] all = java.util.Arrays.copyOf(headers, headers.length + 2);
		all[headers.length] = "Content-Type";
		all[headers.length + 1] = "multipart/form-data; boundary=" + boundary;
		return post(path, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()), all);
	}

	/**
	 * The token as Spring renders it into server-side forms (XOR-masked, here with an all-zero mask):
	 * the encoding a {@code _csrf} form field must use to be accepted when parameters are read.
	 */
	public static String xorMaskedCsrfToken(String rawToken) {
		byte[] token = rawToken.getBytes(StandardCharsets.UTF_8);
		byte[] masked = new byte[token.length * 2];
		System.arraycopy(token, 0, masked, token.length, token.length);
		return java.util.Base64.getUrlEncoder().encodeToString(masked);
	}

	/** Starts sign-in, as the frontend's link does. */
	public Authorization startAuthorization(String... headers) {
		HttpResponse<String> response = get("/oauth2/authorization/discord", headers);
		String location = response.headers().firstValue("Location").orElse("");
		return new Authorization(response, queryParams(location));
	}

	/** Discord's redirect back to the api, with the given raw query string. */
	public HttpResponse<String> callback(String query) {
		return get("/login/oauth2/code/discord?" + query);
	}

	/** The whole flow: start, have Discord issue a code for this user, return to the callback. */
	public HttpResponse<String> signIn(FakeDiscord.User user) {
		Authorization authorization = startAuthorization();
		String code = this.discord.issueCode(user);
		return callback("code=" + encode(code) + "&state=" + encode(authorization.params().get("state")));
	}

	/** The cookie's current value in the jar, or null. */
	public String cookie(String name) {
		return this.jar.get(name);
	}

	/** Replaces the jar with every non-empty cookie value ever received, as name=value pairs. */
	public void replayEveryReceivedCookie() {
		this.jar.clear();
		for (String pair : this.received) {
			int eq = pair.indexOf('=');
			this.jar.put(pair.substring(0, eq), pair.substring(eq + 1));
		}
	}

	/** Every non-empty cookie ever received, as name=value pairs, in order. */
	public List<String> receivedCookies() {
		return List.copyOf(this.received);
	}

	/** The {@code Set-Cookie} headers in this response for the named cookie. */
	public static List<String> setCookies(HttpResponse<?> response, String name) {
		return response.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((header) -> header.startsWith(name + "="))
			.toList();
	}

	public static Map<String, String> queryParams(String uri) {
		Map<String, String> params = new LinkedHashMap<>();
		int question = uri.indexOf('?');
		if (question < 0) {
			return params;
		}
		for (String pair : uri.substring(question + 1).split("&")) {
			int eq = pair.indexOf('=');
			String name = URLDecoder.decode((eq < 0) ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			params.put(name, (eq < 0) ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return params;
	}

	public static String encode(String value) {
		return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
	}

	private HttpResponse<String> send(String method, String path, String... headers) {
		return send(method, path, HttpRequest.BodyPublishers.noBody(), headers);
	}

	private HttpResponse<String> send(String method, String path, HttpRequest.BodyPublisher body, String... headers) {
		return send(method, path, body, HttpResponse.BodyHandlers.ofString(), headers);
	}

	private <T> HttpResponse<T> send(String method, String path, HttpRequest.BodyPublisher body,
			HttpResponse.BodyHandler<T> handler, String... headers) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.baseUrl + path)).method(method, body);
		for (int i = 0; i < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		if (!this.jar.isEmpty()) {
			request.header("Cookie", this.jar.entrySet()
				.stream()
				.map((cookie) -> cookie.getKey() + "=" + cookie.getValue())
				.collect(Collectors.joining("; ")));
		}
		try {
			HttpResponse<T> response = this.http.send(request.build(), handler);
			for (String header : response.headers().allValues("Set-Cookie")) {
				store(header);
			}
			return response;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private void store(String setCookie) {
		String pair = setCookie.split(";", 2)[0].trim();
		int eq = pair.indexOf('=');
		String name = pair.substring(0, eq);
		String value = pair.substring(eq + 1);
		boolean expired = value.isEmpty() || setCookie.matches("(?i).*;\\s*Max-Age=0(;.*)?");
		if (expired) {
			this.jar.remove(name);
		}
		else {
			this.jar.put(name, value);
			this.received.add(name + "=" + value);
		}
	}

}
