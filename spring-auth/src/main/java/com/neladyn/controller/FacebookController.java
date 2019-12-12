package com.neladyn.controller;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.neladyn.domain.AccessToken;
import com.neladyn.domain.AccessTokenData;
import com.neladyn.domain.Data;
import com.neladyn.domain.UserDetails;

@RestController
public class FacebookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(FacebookController.class);

    private RestTemplate restTemplate = new RestTemplate();

    private final String REDIRECT_URI;
    private final String APP_ID;
    private final String APP_SECRET;

    public FacebookController(
        @Value("${REDIRECT_URI}") String REDIRECT_URI,
        @Value("${APP_ID}") String APP_ID,
        @Value("${APP_SECRET}") String APP_SECRET) {
        this.REDIRECT_URI = REDIRECT_URI;
        this.APP_ID = APP_ID;
        this.APP_SECRET = APP_SECRET;
    }
    
 // admin view page after login
    @GetMapping("/success")
 	public String success() {
 		//logger.info("admin");
 		return "success";
 	}

    @GetMapping("/facebook/login")
    public ResponseEntity<?> facebookLogin(@RequestParam("code") String code, @RequestParam("state") String state,
        HttpServletResponse httpServletResponse) throws IOException {
        // Optional: Verify state (csrf) token

        AccessToken accessToken;
        try {
            accessToken = getAccessTokenFromCode(code);
        } catch (RuntimeException e) {
            return ResponseEntity.status(Integer.parseInt(e.getMessage())).build();
        }

        LOGGER.info("Access token = {}", accessToken);

        String appAccessToken;
        try {
            appAccessToken = getAppAccessToken();
        } catch (RuntimeException e) {
            return ResponseEntity.status(Integer.parseInt(e.getMessage())).build();
        }

        AccessTokenData accessTokenData = inspectAccessToken(accessToken.getAccess_token(), appAccessToken);
        LOGGER.info("Verify token = {}", accessTokenData);
        if (!accessTokenData.isIs_valid() || accessTokenData.getApp_id() != Long.valueOf(APP_ID)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UserDetails userDetails;
        try {
            userDetails = getUserDetailsFromAccessToken(accessToken.getAccess_token());
        } catch (RuntimeException e) {
            return ResponseEntity.status(Integer.parseInt(e.getMessage())).build();
        }

        LOGGER.info("User is authenticated: {}", userDetails);

        Cookie cookie = new Cookie("access_token", accessToken.getAccess_token());
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setMaxAge((int) accessToken.getExpires_in());
        httpServletResponse.addCookie(cookie);
        httpServletResponse.sendRedirect(REDIRECT_URI);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/facebook/auth")
    public boolean isAuthenticated(@CookieValue(value = "access_token", required = false) String access_token) {
        if (access_token == null) {
            return false;
        }
        return userIsAuthenticated(access_token);
    }

    @GetMapping("/facebook/logout")
    public ResponseEntity logout(@CookieValue(value = "access_token") String access_token,
        HttpServletResponse httpServletResponse) {
        Cookie cookie = new Cookie("access_token", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setMaxAge(0);
        httpServletResponse.addCookie(cookie);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/facebook/userinfo")
    public UserDetails getUserDetails(@CookieValue("access_token") String access_token) {
        return getUserDetailsFromAccessToken(access_token);
    }

    @GetMapping("/facebook/getLoginUri")
    public String getLoginUri() {
        String uri = "https://www.facebook.com/v2.9/dialog/oauth?client_id=" + APP_ID + "&redirect_uri=" + REDIRECT_URI
            + "&state=" + genCSRF();
        return uri;
    }

    private String genCSRF() {
        return UUID.randomUUID().toString();
    }

    private boolean userIsAuthenticated(String access_token) {
        AccessTokenData accessTokenData;
        try {
            accessTokenData = inspectAccessToken(access_token, getAppAccessToken());
        } catch (RuntimeException e) {
            LOGGER.warn(e.getMessage());
            return false;
        }

        return !(!accessTokenData.isIs_valid() || accessTokenData.getApp_id() != Long.valueOf(APP_ID));
    }

    private AccessTokenData inspectAccessToken(String accessToken, String appAccessToken) {
        Map<String, String> urlparams = new HashMap<>();
        urlparams.put("input_token", accessToken);
        urlparams.put("access_token", appAccessToken);
        try {
            return restTemplate.getForObject(
                "https://graph.facebook.com/debug_token?input_token={input_token}&access_token={access_token}",
                Data.class, urlparams).getData();
        } catch (HttpStatusCodeException exception) {
            LOGGER.warn(exception.getResponseBodyAsString());
            throw new RuntimeException(String.valueOf(exception.getStatusCode()));
        }
    }

    private AccessToken getAccessTokenFromCode(String code) {
        Map<String, String> urlparams = new HashMap<>();
        urlparams.put("client_id", APP_ID);
        urlparams.put("redirect_uri", REDIRECT_URI);
        urlparams.put("client_secret", APP_SECRET);
        urlparams.put("code", code);

        try {
            return restTemplate.getForObject(
                "https://graph.facebook.com/oauth/access_token?client_id={client_id}&code={code}&client_secret"
                    + "={client_secret}&redirect_uri={redirect_uri}",
                AccessToken.class, urlparams);
        } catch (HttpStatusCodeException exception) {
            LOGGER.warn(exception.getResponseBodyAsString());
            throw new RuntimeException(String.valueOf(exception.getStatusCode()));
        }
    }

    private UserDetails getUserDetailsFromAccessToken(String accessToken) {

        Map<String, String> urlparams = new HashMap<>();
        urlparams.put("access_token", accessToken);
        urlparams.put("fields", "id,name,email");
        LOGGER.info("Retrieving user details with {} and {}", accessToken, urlparams);
        try {
            return restTemplate
                .getForObject("https://graph.facebook.com/v2.9/me/?access_token={access_token}&fields={fields}",
                    UserDetails.class, urlparams);
        } catch (HttpStatusCodeException exception) {
            LOGGER.warn(exception.getResponseBodyAsString());
            throw new RuntimeException(String.valueOf(exception.getStatusCode()));
        }
    }

    public String getAppAccessToken() {
        Map<String, String> urlparams = new HashMap<>();
        urlparams.put("client_id", APP_ID);
        urlparams.put("client_secret", APP_SECRET);
        LOGGER.info("Retrieving app access token");

        try {
            String json = restTemplate.getForObject(
                "https://graph.facebook.com/oauth/access_token?client_id={client_id}&client_secret={client_secret"
                    + "}&grant_type=client_credentials",
                String.class, urlparams);
            return new JSONObject(json).getString("access_token");
        } catch (HttpStatusCodeException exception) {
            LOGGER.warn(exception.getResponseBodyAsString());
            throw new RuntimeException(String.valueOf(exception.getStatusCode()));
        }
    }
}
