package com.yijiazzz.pubsubauthorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.chat.v1.ChatServiceClient;
import com.google.chat.v1.ChatServiceSettings;
import com.google.chat.v1.CreateMessageRequest;
import com.google.chat.v1.Message;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

  private static final Logger logger = LoggerFactory.getLogger(Controller.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final HttpTransport httpTransport = new NetHttpTransport();
  private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

  private static final List<String> SCOPES =
      Arrays.asList(
          "https://www.googleapis.com/auth/chat.messages",
          "https://www.googleapis.com/auth/chat.spaces"
          // Add other required scopes here
          );

  private final ChatServiceClient chatServiceClient;

  @Value("${google.client.id:}")
  private String clientId;

  @Value("${google.client.secret:}")
  private String clientSecret;

  @Value("${google.redirect.uri:}")
  private String redirectUri;

  @Value("${google.token.uri:https://oauth2.googleapis.com/token}")
  private String tokenUri;

  public Controller() {
    ChatServiceClient client = null;
    try {
      GoogleCredentials credentials =
          GoogleCredentials.getApplicationDefault()
              .createScoped("https://www.googleapis.com/auth/chat.bot");
      ChatServiceSettings settings =
          ChatServiceSettings.newBuilder()
              .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
              .build();
      client = ChatServiceClient.create(settings);
    } catch (Exception e) {
      System.err.println("Failed to initialize ChatServiceClient: " + e.getMessage());
      e.printStackTrace();
      logger.error("Failed to initialize ChatServiceClient", e);
    }
    this.chatServiceClient = client;
  }

  @GetMapping("/")
  public String healthCheck() {
    return "PubSub Bot is running!";
  }

  @GetMapping("/oauth2/callback")
  public String oauthCallback(
      @RequestParam("code") String code,
      @RequestParam(value = "state", required = false) String state) {
    logger.info("Received OAuth callback");
    try {
      GoogleTokenResponse tokenResponse =
          new GoogleAuthorizationCodeTokenRequest(
                  httpTransport, jsonFactory, tokenUri, clientId, clientSecret, code, redirectUri)
              .execute();

      String accessToken = tokenResponse.getAccessToken();
      String refreshToken = tokenResponse.getRefreshToken();

      logger.info("Access token received: " + accessToken);
      // 2. Store tokens in DB associated with the user (passed in 'state' or session)
      // 3. (Optional) Use the token immediately to post a message?

      return "Authorization successful! You can now use the slash command.";
    } catch (IOException e) {
      logger.error("Failed to exchange code for token", e);
      return "Authorization failed: " + e.getMessage();
    }
  }

  @PostMapping("/")
  public void receiveMessage(@RequestBody PubSubBody body) {
    System.out.println("Received request at / endpoint");
    logger.info("Received request at / endpoint");
    if (body == null || body.message == null || body.message.data == null) {
      logger.warn("Invalid Pub/Sub message format. Body or data is null.");
      System.out.println("Invalid Pub/Sub message format. Body or data is null.");
      return;
    }

    try {
      String data = new String(Base64.getDecoder().decode(body.message.data));
      logger.info("Decoded data: " + data);
      System.out.println("Decoded data: " + data);
      JsonNode event = objectMapper.readTree(data);

      String type = event.path("type").asText();
      if ("SLASH_COMMAND".equals(type)
          || ("MESSAGE".equals(type) && event.path("message").has("slashCommand"))) {
        handleSlashCommand(event);
      } else {
        logger.info("Received event type: " + type);
      }
    } catch (Exception e) {
      logger.error("Error processing message: " + e.getMessage(), e);
      e.printStackTrace();
    }
  }

  private void handleSlashCommand(JsonNode event) {
    long commandId = event.path("message").path("slashCommand").path("commandId").asLong();

    if (commandId == 7) {
      logger.info("Handling /triggerOAuth slash command: " + commandId);
      // Mock check for user credentials
      boolean hasCredentials = checkUserCredentials(event.path("user").path("name").asText());

      if (!hasCredentials) {
        triggerAuthorizationFlow(event);
      } else {
        createMessage(event);
      }
    } else if (commandId == 1) {
      logger.info("Handling /pubsubtest slash command: " + commandId);
      String spaceName = event.path("space").path("name").asText();
      sendMessage(spaceName, "This is a test message from Pub/Sub!");
    } else {
      logger.info("Received unknown slash command: " + commandId);
    }
  }

  private String generateAuthUrl(String userName) {
    return new GoogleAuthorizationCodeRequestUrl(clientId, redirectUri, SCOPES)
        .setState(userName)
        .setAccessType("offline")
        .build();
  }

  private boolean checkUserCredentials(String userId) {
    // In a real app, check your database for stored tokens for this user.
    return false;
  }

  private void triggerAuthorizationFlow(JsonNode event) {
    String spaceName = event.path("space").path("name").asText();
    String userName = event.path("user").path("name").asText();
    logger.info("Triggering auth flow for space: " + spaceName);

    if (clientId == null
        || clientId.isEmpty()
        || clientSecret == null
        || clientSecret.isEmpty()
        || redirectUri == null
        || redirectUri.isEmpty()) {
      sendMessage(
          spaceName,
          "Configuration Error: Google OAuth parameters are not fully set. Please configure"
              + " google.client.id, google.client.secret, and google.redirect.uri.");
      return;
    }

    String authUrl = generateAuthUrl(userName);
    sendMessage(spaceName, "Please authorize access to use this command: " + authUrl);
  }

  private void createMessage(JsonNode event) {
    logger.info("Creating message with user credentials...");
  }

  private void sendMessage(String spaceName, String text) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient is null. Cannot send message.");
      return;
    }
    try {
      Message message = Message.newBuilder().setText(text).build();
      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(message).build();
      chatServiceClient.createMessage(request);
      logger.info("Message sent to space: " + spaceName);
    } catch (Exception e) {
      logger.error("Failed to send message to Chat: " + e.getMessage(), e);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PubSubBody {
    public PubSubMessage message;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PubSubMessage {
    public String data;
    public String messageId;
    public String publishTime;
  }
}
