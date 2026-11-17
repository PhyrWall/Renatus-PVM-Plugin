package com.renatus;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.renatus.model.ClanSummary;
import com.renatus.model.LeaderboardEntry;
import com.renatus.model.PlayerProfile;
import com.renatus.model.Split;
import com.renatus.model.SplitResponse;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the read-only plugin API (/api/plugin/v1).
 */
@Slf4j
@Singleton
public class RenatusApiClient
{
	private static final Type LEADERBOARD_TYPE = new TypeToken<List<LeaderboardEntry>>() {}.getType();
	private static final Type SPLITS_TYPE = new TypeToken<List<Split>>() {}.getType();
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final RenatusConfig config;

	@Inject
	RenatusApiClient(OkHttpClient httpClient, Gson gson, RenatusConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson.newBuilder()
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.create();
		this.config = config;
	}

	enum SetupState
	{
		DISABLED,
		NO_URL,
		NO_KEY,
		READY,
	}

	SetupState setupState()
	{
		if (!config.enableApi())
		{
			return SetupState.DISABLED;
		}
		if (baseUrl() == null)
		{
			return SetupState.NO_URL;
		}
		if (config.apiKey().trim().isEmpty())
		{
			return SetupState.NO_KEY;
		}
		return SetupState.READY;
	}

	public interface ResultCallback<T>
	{
		void onResult(@Nullable T result);
	}

	@Nullable
	private HttpUrl baseUrl()
	{
		String raw = config.apiBaseUrl().trim();
		if (raw.isEmpty())
		{
			return null;
		}
		if (!raw.startsWith("http://") && !raw.startsWith("https://"))
		{
			raw = "https://" + raw;
		}
		return HttpUrl.parse(raw);
	}

	/**
	 * @return the configured API root ({@code <site>/api/plugin/v1/}), or
	 * null unless {@link #setupState()} is READY
	 */
	@Nullable
	HttpUrl apiRoot()
	{
		if (setupState() != SetupState.READY)
		{
			return null;
		}
		return baseUrl().newBuilder()
			.addPathSegment("api")
			.addPathSegment("plugin")
			.addPathSegment("v1")
			.build();
	}

	/** The API key owner's own profile, including splits waiting on them. */
	public void fetchMe(ResultCallback<PlayerProfile> onResult, Consumer<String> onError)
	{
		get(b -> b.addPathSegment("me"), PlayerProfile.class, onResult, onError);
	}

	public void fetchPlayer(String rsn, ResultCallback<PlayerProfile> onResult, Consumer<String> onError)
	{
		get(b -> b.addPathSegment("player").addPathSegment(rsn), PlayerProfile.class, onResult, onError);
	}

	public void fetchClan(ResultCallback<ClanSummary> onResult, Consumer<String> onError)
	{
		get(b -> b.addPathSegment("clan"), ClanSummary.class, onResult, onError);
	}

	public void fetchLeaderboard(int limit, ResultCallback<List<LeaderboardEntry>> onResult, Consumer<String> onError)
	{
		get(b -> b.addPathSegment("leaderboard").addQueryParameter("limit", Integer.toString(limit)),
			LEADERBOARD_TYPE, onResult, onError);
	}

	public void fetchRecentSplits(int limit, ResultCallback<List<Split>> onResult, Consumer<String> onError)
	{
		get(b -> b.addPathSegment("recent-splits").addQueryParameter("limit", Integer.toString(limit)),
			SPLITS_TYPE, onResult, onError);
	}

	/**
	 * Confirms (approve) or denies a split the key's owner was tagged in.
	 * Errors carry the server's message (e.g."You have already responded
	 * to this split.").
	 */
	public void respondToSplit(String code, boolean approve, ResultCallback<SplitResponse> onResult, Consumer<String> onError)
	{
		JsonObject json = new JsonObject();
		json.addProperty("approve", approve);
		RequestBody body = RequestBody.create(JSON, json.toString());
		send(b -> b.addPathSegment("splits").addPathSegment(code).addPathSegment("respond"),
			r -> r.post(body), SplitResponse.class, false, onResult, onError);
	}

	private <T> void get(Consumer<HttpUrl.Builder> path, Type type, ResultCallback<T> onResult, Consumer<String> onError)
	{
		send(path, Request.Builder::get, type, true, onResult, onError);
	}

	/**
	 * @param notFoundIsNull whether a 404 is a normal "no such thing" result
	 *                       (lookups) rather than an error (actions)
	 */
	private <T> void send(Consumer<HttpUrl.Builder> path, Consumer<Request.Builder> method, Type type,
		boolean notFoundIsNull, ResultCallback<T> onResult, Consumer<String> onError)
	{
		HttpUrl root = apiRoot();
		if (root == null)
		{
			onError.accept("Clan server not configured");
			return;
		}

		HttpUrl.Builder builder = root.newBuilder();
		path.accept(builder);
		Request.Builder requestBuilder = new Request.Builder()
			.url(builder.build())
			.header("Authorization", "Bearer " + config.apiKey().trim());
		method.accept(requestBuilder);
		Request request = requestBuilder.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Renatus API request to {} failed", request.url(), e);
				onError.accept("Couldn't reach the clan server");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (response.code() == 401)
					{
						onError.accept("API key not accepted. Run /get-api-key in Discord and paste the new key into the plugin config.");
						return;
					}
					if (response.code() == 404 && notFoundIsNull)
					{
						onResult.onResult(null);
						return;
					}
					if (!response.isSuccessful() || body == null)
					{
						log.debug("Renatus API {} returned HTTP {}", request.url(), response.code());
						onError.accept(errorMessage(body, response.code()));
						return;
					}
					T parsed = gson.fromJson(body.charStream(), type);
					onResult.onResult(parsed);
				}
				catch (JsonParseException e)
				{
					log.debug("Renatus API {} returned an unreadable response", request.url(), e);
					onError.accept("Unexpected response from the clan server");
				}
			}
		});
	}

	/** The server's {"error": "..."} message if it sent one, else a generic one. */
	private String errorMessage(ResponseBody body, int code)
	{
		if (body != null)
		{
			try
			{
				JsonObject json = gson.fromJson(body.charStream(), JsonObject.class);
				if (json != null && json.has("error") && json.get("error").isJsonPrimitive())
				{
					return json.get("error").getAsString();
				}
			}
			catch (JsonParseException e)
			{
				log.debug("Renatus API error body wasn't JSON", e);
			}
		}
		return "Clan server error (HTTP " + code + ")";
	}
}
