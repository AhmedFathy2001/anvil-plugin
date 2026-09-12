package com.anvil.api;

import com.google.gson.JsonParser;
import com.anvil.api.dto.AdminUnauthorizedException;
import com.anvil.api.dto.ClanMember;
import com.anvil.api.dto.ClanMismatchException;
import com.anvil.api.dto.ClanSyncResponse;
import com.anvil.api.dto.RateLimitedException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The two requests only a clan admin can make: "am I one?", and "here is the roster".
 *
 * <h2>Why the admin answer is asked for rather than assumed</h2>
 *
 * <p>Site admin is a per-clan fact the client cannot work out on its own, and every control that
 * depends on it — the clan window's Sync button, the sidebar's — is better absent than present and
 * refused. So the answer is probed once and cached per clan, and dropped when the addressed clan
 * changes, rather than guessed from anything local.</p>
 *
 * <h2>Why the push can be refused</h2>
 *
 * <p>A roster read from a clan channel that has not loaded yet is EMPTY, and an empty push once
 * wiped a hundred and forty-four members. The server guards that, and this carries the refusals it
 * sends back — a mismatched clan name, an unauthorised account, a plan limit — as distinct
 * exceptions rather than one failure, because each of them means something different to say.</p>
 */
@Slf4j
@Singleton
public class ClanRosterApi
{
    private final BingoApiClient api;
    private final Gson gson;

    @Inject
    ClanRosterApi(BingoApiClient api, Gson gson) {
        this.api = api;
        this.gson = gson;
    }

    /**
     * GET /api/plugin/me — "is my account token a site admin?" probe.
     *
     * Sends the per-user account token as a Bearer header. Returns true only on HTTP 200
     * (the site returns {isAdmin:true} for admins, 401 for non-admins / invalid tokens).
     * Tolerates network/parse failures by returning false — a hidden panel is the safe default.
     */
    public boolean fetchIsAdmin(String accountToken)
    {
        if (!api.isConfiguredUrl() || accountToken == null || accountToken.isEmpty())
        {
            return false;
        }
        Request request = new Request.Builder()
            .url(api.clanUrl("/api/plugin/me"))
            .header("Authorization", "Bearer " + accountToken)
            .get()
            .build();
        try (Response response = api.newCall(request).execute())
        {
            if (response.code() != 200)
            {
                // 401 = this token's user isn't an admin (or the token is stale). Anything else is
                // the site having a bad time. Logged either way: "the button vanished" is otherwise
                // indistinguishable between the two, and one of them is worth retrying.
                log.info("Anvil: admin probe answered HTTP {} — no clan-sync button this session", response.code());
            }
            return response.code() == 200;
        }
        catch (Exception e)
        {
            log.info("Anvil: admin probe couldn't reach the site ({}) — will retry", e.getMessage());
            return false;
        }
    }

    /**
     * POST /api/plugin/clan-sync — upload the scraped clan roster. Authenticated with the
     * caller's per-user account token (must belong to a site admin).
     */
    public ClanSyncResponse syncClan(String accountToken, String clanName, List<ClanMember> members) throws IOException, ClanMismatchException, AdminUnauthorizedException
    {
        if (!api.isConfiguredUrl())
        {
            throw new IOException("Site URL is not configured");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("clanName", clanName);
        payload.add("members", gson.toJsonTree(members));

        RequestBody body = RequestBody.create(BingoApiClient.JSON, payload.toString());
        Request request = new Request.Builder()
            .url(api.clanUrl("/api/plugin/clan-sync"))
            .header("Authorization", "Bearer " + accountToken)
            .post(body)
            .build();

        try (Response response = api.newCall(request).execute())
        {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (response.code() == 401)
            {
                throw new AdminUnauthorizedException("Account token is not an admin (or was revoked)");
            }
            if (response.code() == 409)
            {
                String serverClan = null;
                try
                {
                    JsonObject err = new JsonParser().parse(responseBody).getAsJsonObject();
                    if (err.has("serverClanName"))
                    {
                        serverClan = err.get("serverClanName").getAsString();
                    }
                }
                catch (Exception ignored) {}
                throw new ClanMismatchException(serverClan);
            }
            if (response.code() == 429)
            {
                // The site has a limit and told us how long it is; the caller waits exactly that
                // long rather than backing off blindly from a message it couldn't read.
                throw new RateLimitedException(ApiErrors.friendlyError(429, responseBody), ApiErrors.retryAfterFrom(responseBody));
            }
            if (!response.isSuccessful())
            {
                throw new IOException("HTTP " + response.code() + " — " + responseBody);
            }
            return gson.fromJson(responseBody, ClanSyncResponse.class);
        }
    }
}
